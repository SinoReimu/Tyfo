package com.shiro.tyfo.backend

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Connects to PrivilegedService by launching app_process directly as root via `su`.
 * Skips wireless-debugging pairing entirely -- works whenever the device grants this
 * app root access (Magisk, KernelSU, etc). BinderExchangeProvider already accepts
 * callers at uid 0 alongside shell (2000) and system (1000).
 */
class RootConnector(private val context: Context) : ServiceConnector {

    companion object {
        private const val TAG = "RootConnector"

        // Android 17+: the VD must belong to a VirtualDevice to leave display group 0, and the
        // VirtualDevice needs a CDM association on a VDM-eligible profile for com.android.shell.
        // NEARBY_DEVICE_STREAMING is eligible and, unlike APP_STREAMING, grants no notification
        // access. The MAC is a fixed locally-administered address so the association is reused.
        private const val VD_ASSOCIATION_MAC = "02:00:00:00:00:F0"
        private const val VD_ASSOCIATION_PROFILE =
            "android.app.role.COMPANION_DEVICE_NEARBY_DEVICE_STREAMING"
        // The VirtualDevice owner must be shell or system; system (1000) is also the only uid
        // (besides root) that may create a SECURE VD, so launch the service as system on 17.
        private const val SYSTEM_UID = "1000"

        private fun usesVirtualDevice(): Boolean = Build.VERSION.SDK_INT >= 37

        @Volatile private var cachedAvailable: Boolean? = null

        /**
         * Runs `su -c id` and checks it reports uid=0. Only a success is cached, so granting
         * the app in Magisk afterwards takes effect on the next check without a restart.
         */
        fun checkRootAvailable(): Boolean {
            cachedAvailable?.let { return it }
            val available = try {
                val process = ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val exited = process.waitFor()
                exited == 0 && output.contains("uid=0")
            } catch (e: Exception) {
                Log.i(TAG, "Root not available: ${e.message}")
                false
            }
            if (available) cachedAvailable = true
            return available
        }
    }

    override val name = "root"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null

    override fun isAvailable(): Boolean = checkRootAvailable()

    override fun connect() {
        _state.value = ConnectionState.Connecting
        scope.launch {
            try {
                val token = BinderExchangeProvider.prepare()
                val apkPath = context.applicationInfo.sourceDir
                val authority = "${context.packageName}.binder_exchange"

                // On Android 17 set up the CDM association and run the service as system uid so it
                // can own a VirtualDevice and create a SECURE VD. On 16 keep running as root with
                // the classic VD path (no association needed).
                val associationId = if (usesVirtualDevice()) ensureVdAssociation() else -1

                var cmd = "CLASSPATH=$apkPath app_process /system/bin " +
                    "--nice-name=tyfo:privilege " +
                    "com.shiro.tyfo.backend.PrivilegedServiceMain " +
                    "--token=$token --authority=$authority"
                if (associationId >= 0) cmd += " --association-id=$associationId"

                val suArgs = if (usesVirtualDevice()) {
                    arrayOf("su", SYSTEM_UID, "-c", cmd)
                } else {
                    arrayOf("su", "-c", cmd)
                }
                Log.i(TAG, "Launching privileged service via su (uid=${if (usesVirtualDevice()) SYSTEM_UID else "0"}, association=$associationId)")
                val proc = ProcessBuilder(*suArgs)
                    .redirectErrorStream(true)
                    .start()
                process = proc

                // Drain stdout/stderr so the child never blocks on a full pipe buffer.
                Thread {
                    try {
                        proc.inputStream.bufferedReader().forEachLine { line ->
                            Log.d(TAG, "su output: $line")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "su output stream ended: ${e.message}")
                    }
                }.start()

                val binder = BinderExchangeProvider.awaitBinder(10_000)
                if (binder != null) {
                    binder.linkToDeath({
                        Log.w(TAG, "Privileged service binder died")
                        _state.value = ConnectionState.Disconnected
                    }, 0)
                    _state.value = ConnectionState.Connected(binder)
                    Log.i(TAG, "Service started via root, binder received")
                } else {
                    _state.value = ConnectionState.Failed("Root service did not start (no binder received)")
                    proc.destroy()
                    process = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "connect() failed", e)
                _state.value = ConnectionState.Failed("Root connect error: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        process?.destroy()
        process = null
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Find (or create) the CDM association the VirtualDevice path needs, and return its id, or -1.
     * Idempotent: a matching association persists across reboots and is reused. Runs as root.
     */
    private fun ensureVdAssociation(): Int {
        findVdAssociationId()?.let { return it }
        runSuShell(
            "cmd companiondevice associate 0 com.android.shell " +
                "$VD_ASSOCIATION_MAC $VD_ASSOCIATION_PROFILE false"
        )
        val id = findVdAssociationId()
        if (id == null) Log.w(TAG, "VD association could not be created; falling back to group-0 VD")
        return id ?: -1
    }

    private fun findVdAssociationId(): Int? {
        val out = runSuShell("cmd companiondevice list 0") ?: return null
        val mac = VD_ASSOCIATION_MAC.lowercase()
        // Each association prints as: Association{mId=N, ... mPackageName='com.android.shell',
        // mDeviceMacAddress=02:00:...:f0, ...}
        for (line in out.lineSequence()) {
            if (!line.contains("mPackageName='com.android.shell'")) continue
            if (!line.contains("mDeviceMacAddress=$mac")) continue
            val id = Regex("mId=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            return id
        }
        return null
    }

    /** Run a shell command as root and return combined stdout/stderr, or null on failure. */
    private fun runSuShell(command: String): String? {
        return try {
            val proc = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            output
        } catch (e: Exception) {
            Log.w(TAG, "runSuShell failed: ${e.message}")
            null
        }
    }
}
