package com.shiro.tyfo.backend

import android.content.Context
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

                val cmd = "CLASSPATH=$apkPath app_process /system/bin " +
                    "--nice-name=tyfo:privilege " +
                    "com.shiro.tyfo.backend.PrivilegedServiceMain " +
                    "--token=$token --authority=$authority"

                Log.i(TAG, "Launching privileged service via su")
                val proc = ProcessBuilder("su", "-c", cmd)
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
}
