package ai.opencyvis.backend

import android.os.Bundle
import android.os.IBinder
import android.view.InputEvent
import android.view.Surface

interface PrivilegeBackend {
    val capabilities: BackendCapabilities

    fun injectInputEvent(event: InputEvent, displayId: Int, mode: Int): Boolean
    fun injectKeyEvent(action: Int, keyCode: Int, repeat: Int, metaState: Int, displayId: Int, mode: Int): Boolean
    fun captureScreen(displayId: Int, maxWidth: Int = 540, quality: Int = 85): ByteArray?
    fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int): Int
    fun releaseVirtualDisplay()
    fun setVirtualDisplaySurface(surface: Surface?)
    fun setDisplayImePolicy(displayId: Int, policy: Int)
    fun startActivityOnDisplay(intentUri: String, displayId: Int): Boolean
    fun getTopTaskIdOnDisplay(displayId: Int, callerPackage: String): Int
    fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean
    fun setVirtualDisplayAwake(awake: Boolean) {}
    fun getTopTaskOnDisplay(displayId: Int, callerPackage: String): Bundle? = null
    /** Install (non-null) or clear (null) the system-wide IActivityController. */
    fun setActivityController(controller: IBinder?): Boolean = false
    /** 移除某块屏上的所有非桌面任务（像在最近任务里划掉），返回被移除的任务 id。 */
    fun removeTasksOnDisplay(displayId: Int): IntArray = IntArray(0)
    fun destroy()
}

data class BackendCapabilities(
    val name: String,
    val canInjectInput: Boolean,
    val canCaptureScreen: Boolean,
    val canCaptureSecure: Boolean,
    val canCreateVirtualDisplay: Boolean,
) {
    companion object {
        const val CAP_INJECT_INPUT = 1
        const val CAP_CAPTURE_SCREEN = 2
        const val CAP_CAPTURE_SECURE = 4
        const val CAP_CREATE_VD = 8

        fun fromProbeMask(mask: Int, name: String) = BackendCapabilities(
            name = name,
            canInjectInput = mask and CAP_INJECT_INPUT != 0,
            canCaptureScreen = mask and CAP_CAPTURE_SCREEN != 0,
            canCaptureSecure = mask and CAP_CAPTURE_SECURE != 0,
            canCreateVirtualDisplay = mask and CAP_CREATE_VD != 0,
        )

        val SYSTEM = BackendCapabilities("system", true, true, true, true)
        val NONE = BackendCapabilities("none", false, false, false, false)
    }
}
