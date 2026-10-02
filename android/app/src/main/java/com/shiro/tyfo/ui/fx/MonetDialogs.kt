package com.shiro.tyfo.ui.fx

import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 主界面 / 记忆页的对话框：大圆角、侧边栏同色的底，按钮用强调色（莫奈色板随壁纸变，主题里写不死）。 */
object MonetDialogs {

    fun builder(context: Context, palette: MonetPalette): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(context).setBackground(
            GradientDrawable().apply {
                cornerRadius = 28 * context.resources.displayMetrics.density
                setColor(palette.drawer)
            }
        )

    /** 显示，并把按钮文字换成强调色。 */
    fun MaterialAlertDialogBuilder.showTinted(palette: MonetPalette): AlertDialog =
        show().also { dialog ->
            for (which in intArrayOf(
                DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL,
            )) {
                dialog.getButton(which)?.setTextColor(palette.accent)
            }
        }
}
