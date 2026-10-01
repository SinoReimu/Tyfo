package com.shiro.tyfo

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 系统实况窗（Live Updates）：任务进度通知被提升为“进行中”卡片，状态栏上有一枚胶囊，
 * 锁屏和通知栏顶部常驻。能用时任务进度只走实况窗，顶部悬浮岛不再显示。
 */
object LiveUpdates {
    /**
     * 36.0 首发版只接受请求、不会画状态栏胶囊（Pixel 实测），36.1 起才真正显示；
     * 用户还可以在本应用的通知设置里关掉实况窗。
     */
    fun isAvailable(context: Context): Boolean =
        isSupportedPlatform() &&
            context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

    /** 申请提升为实况窗。系统还要求通知常驻、有标题、不着色、不用自定义视图，进度通知都满足。 */
    fun requestPromotion(builder: Notification.Builder): Notification.Builder {
        if (isSupportedPlatform()) builder.setRequestPromotedOngoing(true)
        return builder
    }

    // SDK_INT_FULL 是 36 才有的字段，旧系统上先挡住
    private fun isSupportedPlatform(): Boolean =
        Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
}
