package com.shiro.tyfo.backend

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

sealed class DetectionResult {
    data class Ready(val backend: PrivilegeBackend) : DetectionResult()
    data class SetupRequired(val availableConnectors: List<ServiceConnector>) : DetectionResult()
    object NoneAvailable : DetectionResult()
}

object BackendDetector {
    private const val TAG = "BackendDetector"

    fun isSystemUid(uid: Int = Process.myUid()): Boolean = uid == 1000

    suspend fun detect(context: Context): DetectionResult {
        if (isSystemUid()) {
            Log.i(TAG, "Running as system app (uid=${Process.myUid()})")
            return DetectionResult.Ready(SystemBackend())
        }

        val connectors = buildConnectorList(context)
        for (connector in connectors) {
            if (!connector.isAvailable()) continue
            Log.i(TAG, "Trying ${connector.name}...")
            connector.connect()

            val result = withTimeoutOrNull(10_000L) {
                connector.state.first {
                    it is ConnectionState.Connected || it is ConnectionState.Failed || it is ConnectionState.NeedsPairing
                }
            }

            when (result) {
                is ConnectionState.Connected -> {
                    val svc = IPrivilegedService.Stub.asInterface(result.serviceBinder)
                    Log.i(TAG, "Connected via ${connector.name} (uid=${svc.serviceUid})")
                    return DetectionResult.Ready(RemoteBackend(connector, svc))
                }
                is ConnectionState.NeedsPairing -> {
                    Log.i(TAG, "${connector.name} needs pairing")
                    // Return setup required — UI will handle pairing flow
                    return DetectionResult.SetupRequired(connectors.filter { it.isAvailable() })
                }
                else -> {
                    Log.w(TAG, "${connector.name} failed or timed out")
                    connector.disconnect()
                }
            }
        }

        // Shizuku is running but not authorized yet: lead to its permission UI rather
        // than reporting nothing available.
        if (ShizukuConnector.status() == ShizukuStatus.PERMISSION_REQUIRED) {
            Log.i(TAG, "Shizuku is running and needs app permission")
            return DetectionResult.SetupRequired(connectors)
        }

        Log.w(TAG, "No privileged backend available")
        return DetectionResult.NoneAvailable
    }

    /**
     * Root first (silent once granted in Magisk/KernelSU, and uid 0 can capture secure
     * windows), then Shizuku. Wireless ADB is only tried after the user picked it in the
     * setup screen — otherwise a phone without wireless debugging would wait out its
     * 10s timeout on every launch.
     */
    private fun buildConnectorList(context: Context): List<ServiceConnector> {
        return buildList {
            add(RootConnector(context))
            add(ShizukuConnector(context))
            if (SetupStateDetector.isWirelessAdbChosen(context)) add(DirectConnector(context))
        }
    }
}
