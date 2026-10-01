package ai.opencyvis

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import ai.opencyvis.remoteim.ImChannelManager
import ai.opencyvis.remoteim.ImPairingManager
import ai.opencyvis.remoteim.RemoteImControlService
import ai.opencyvis.config.ConfigRepository
import java.io.File

class App : Application() {

    companion object {
        private const val TAG = "OpencyvisApp"

        @Volatile
        var agentService: AgentService? = null

        @Volatile
        var imChannelManager: ImChannelManager? = null

        @Volatile
        var imPairingManager: ImPairingManager? = null
    }

    override fun onCreate() {
        super.onCreate()
        if (isDebuggableBuild()) {
            TestShellService.register()
        }

        // 早期版本的离线语音模型（约 90MB）留在 files/asr 里；语音识别已改走云端，删掉
        File(filesDir, "asr").takeIf { it.exists() }?.let { dir ->
            Thread { Log.i(TAG, "Removed legacy ASR model: ${dir.deleteRecursively()}") }.start()
        }

        // Start remote IM control if enabled
        val config = ConfigRepository(this)
        if (config.imRemoteEnabled) {
            RemoteImControlService.start(this)
        }
    }

    private fun isDebuggableBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
