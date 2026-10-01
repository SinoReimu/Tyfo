package ai.opencyvis.assistant

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 「长按电源键唤起 OpenCyvis」= 在系统里把 OpenCyvis 设为默认数字助理。
 * 这一步只能由用户在系统设置里自己选，应用只负责判断现状、把用户带到对应页面。
 */
object AssistantRole {
    fun isHeld(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

    /** 打开系统「默认应用」设置（数字助理在里面）；个别 ROM 没有就退到语音输入设置。 */
    fun openSystemSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}
