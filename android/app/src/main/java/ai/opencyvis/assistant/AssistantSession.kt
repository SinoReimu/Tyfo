package ai.opencyvis.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * 长按电源键弹出的助手会话。窗口铺满全屏、透明、画到系统栏后面，内容全部交给 [AssistantPanel]。
 * 不需要系统截屏 / 读屏上下文，关掉以免每次唤起都采集。
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context) {

    private var panel: AssistantPanel? = null

    override fun onCreate() {
        super.onCreate()
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        window.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDecorFitsSystemWindows(false)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 会话的系统主题带着旧的半透明系统栏标志，DecorView 会在导航栏后面垫一层约 30% 的黑，
            // 设透明色也盖不掉；窗口一出现底部就硬切变暗，收起后又硬切变亮。清掉，系统栏背景由我们自己画（透明）
            @Suppress("DEPRECATION")
            w.clearFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
            )
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            // 说话、看进度时屏幕不能自己暗下去（锁屏时系统亮屏超时尤其短）
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            @Suppress("DEPRECATION")
            w.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            w.isNavigationBarContrastEnforced = false
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }

    override fun onCreateContentView(): View {
        val p = AssistantPanel(context, object : AssistantPanel.Host {
            override fun close() = hide()
        })
        panel = p
        return p.view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // 系统的会话布局外层带 fitsSystemWindows / padding，拆掉才能真正全屏（到 DecorView 为止）
        val decor = window.window?.decorView
        var parent = panel?.view?.parent
        while (parent is ViewGroup && parent !== decor) {
            parent.fitsSystemWindows = false
            parent.setPadding(0, 0, 0, 0)
            parent = parent.parent
        }
        panel?.onShow()
    }

    override fun onHide() {
        panel?.onHide()
        super.onHide()
    }

    override fun onBackPressed() {
        panel?.onBackPressed() ?: super.onBackPressed()
    }

    override fun onDestroy() {
        panel?.onDestroy()
        panel = null
        super.onDestroy()
    }
}
