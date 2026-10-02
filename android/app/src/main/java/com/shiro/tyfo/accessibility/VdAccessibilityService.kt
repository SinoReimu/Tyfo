package com.shiro.tyfo.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shiro.tyfo.tools.UiElement

class VdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VdA11yService"
        private const val MAX_DEPTH = 30
        private const val MAX_NODES = 400

        @Volatile
        var instance: VdAccessibilityService? = null
            private set

        /** 服务连上 / 断开时回调（主线程），浮窗据此切换辅助功能悬浮窗和普通悬浮窗。 */
        val connectionListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

        private fun notifyConnectionChanged() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                connectionListeners.forEach { it() }
            }
        }

        /**
         * 这块屏幕上可见的界面元素，边界是设备像素（裁到屏幕内）。无障碍服务没连上或拿不到窗口时返回 null。
         * 包括纯布局容器，筛选交给 [com.shiro.tyfo.tools.ScreenOutline]。
         */
        fun captureElements(displayId: Int, displayWidth: Int, displayHeight: Int): List<UiElement>? {
            return instance?.captureElementsInternal(displayId, displayWidth, displayHeight)
        }

        fun getTopPackageOnDisplay(displayId: Int): String? {
            val service = instance ?: return null
            return try {
                val windowList = if (displayId == 0) {
                    service.windows
                } else {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val allWindows = service.javaClass.getMethod("getWindowsOnAllDisplays")
                            .invoke(service) as? android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>>
                        allWindows?.get(displayId)
                    } catch (e: Exception) {
                        service.windows
                    }
                }
                windowList?.firstNotNullOfOrNull { window ->
                    val root = window.getRoot(0) ?: return@firstNotNullOfOrNull null
                    val pkg = root.packageName?.toString()
                    root.recycle()
                    pkg?.takeIf { it != "com.android.systemui" }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getTopPackageOnDisplay failed", e)
                null
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        Log.i(TAG, "AccessibilityService connected")
        notifyConnectionChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We only use on-demand tree queries, not event-driven
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        notifyConnectionChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            notifyConnectionChanged()
        }
        super.onDestroy()
    }

    private fun captureElementsInternal(
        displayId: Int, displayWidth: Int, displayHeight: Int
    ): List<UiElement>? {
        return try {
            val windowsMap = windows?.takeIf { displayId == 0 }
                ?: try {
                    @Suppress("UNCHECKED_CAST")
                    val allWindows = javaClass.getMethod("getWindowsOnAllDisplays").invoke(this)
                        as? android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>>
                    allWindows?.get(displayId)
                } catch (e: Exception) {
                    Log.w(TAG, "getWindowsOnAllDisplays failed, falling back to getWindows()", e)
                    windows
                }

            if (windowsMap.isNullOrEmpty()) {
                Log.w(TAG, "No windows found for displayId=$displayId")
                return null
            }

            val out = mutableListOf<UiElement>()
            for (window in windowsMap) {
                val root = window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
                    ?: continue
                val packageName = root.packageName?.toString() ?: ""
                if (packageName == "com.android.systemui") {
                    root.recycle()
                    continue
                }
                collect(root, 0, out, displayWidth, displayHeight)
                root.recycle()
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture elements", e)
            null
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<UiElement>,
        displayWidth: Int,
        displayHeight: Int,
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_NODES) return
        if (!node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val left = bounds.left.coerceIn(0, displayWidth)
        val top = bounds.top.coerceIn(0, displayHeight)
        val right = bounds.right.coerceIn(0, displayWidth)
        val bottom = bounds.bottom.coerceIn(0, displayHeight)
        if (right > left && bottom > top) {
            out.add(
                UiElement(
                    type = node.className?.toString()?.substringAfterLast('.') ?: "View",
                    text = node.text?.toString(),
                    label = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.hintText?.toString(),
                    id = node.viewIdResourceName?.substringAfterLast('/'),
                    left = left, top = top, right = right, bottom = bottom,
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    focused = node.isFocused,
                    selected = node.isSelected,
                    enabled = node.isEnabled,
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, depth + 1, out, displayWidth, displayHeight)
            child.recycle()
        }
    }
}
