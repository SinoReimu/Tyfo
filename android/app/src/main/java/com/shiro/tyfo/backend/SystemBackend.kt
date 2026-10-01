package com.shiro.tyfo.backend

import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import android.view.Surface

class SystemBackend : PrivilegeBackend {
    override val capabilities = BackendCapabilities.SYSTEM

    companion object {
        private const val TAG = "SystemBackend"
    }

    override fun injectInputEvent(event: InputEvent, displayId: Int, mode: Int): Boolean {
        return InputOps.injectInputEvent(event, displayId, mode)
    }

    override fun injectKeyEvent(action: Int, keyCode: Int, repeat: Int, metaState: Int, displayId: Int, mode: Int): Boolean {
        return InputOps.injectKeyEvent(action, keyCode, repeat, metaState, displayId, mode)
    }

    override fun captureScreen(displayId: Int, maxWidth: Int, quality: Int): ByteArray? {
        return CaptureOps.captureScreen(displayId, maxWidth, quality)
    }

    // VD creation — SystemBackend's VDM creates the VirtualDisplay locally via
    // DisplayManager.createVirtualDisplay(), so these backend methods are no-ops.
    // They exist for RemoteBackend where VD lives in a different process.
    override fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int): Int = -1
    override fun releaseVirtualDisplay() {}
    override fun setVirtualDisplaySurface(surface: Surface?) {}

    // Task management — delegate to DisplayOps reflection
    override fun setDisplayImePolicy(displayId: Int, policy: Int) {
        DisplayOps.setDisplayImePolicy(displayId, policy)
    }

    override fun startActivityOnDisplay(intentUri: String, displayId: Int): Boolean {
        // System app has full permissions — direct startActivity with launchDisplayId works.
        // This codepath is only used for system flavor; standard flavor uses RemoteBackend.
        Log.i(TAG, "SystemBackend.startActivityOnDisplay not needed — system app can use Context.startActivity directly")
        return false  // Caller will fall through to normal Context.startActivity path
    }

    override fun getTopTaskIdOnDisplay(displayId: Int, callerPackage: String): Int {
        return DisplayOps.getTopTaskIdOnDisplay(displayId, callerPackage)
    }

    override fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean {
        return DisplayOps.moveTaskToDisplay(taskId, targetDisplayId)
    }

    override fun getTopTaskOnDisplay(displayId: Int, callerPackage: String): Bundle? {
        return DisplayOps.getTopTaskOnDisplay(displayId, callerPackage)
    }

    override fun removeTasksOnDisplay(displayId: Int): IntArray {
        return DisplayOps.removeTasksOnDisplay(displayId)
    }

    override fun setActivityController(controller: IBinder?): Boolean {
        return DisplayOps.setActivityController(controller)
    }

    override fun destroy() {}
}
