package com.shiro.tyfo

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File

class App : Application() {

    companion object {
        private const val TAG = "TyfoApp"

        @Volatile
        var agentService: AgentService? = null
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
    }

    private fun isDebuggableBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
