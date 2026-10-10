package com.shiro.tyfo.display

import com.shiro.tyfo.backend.DisplayOps
import com.shiro.tyfo.backend.PrivilegeBackend
import com.shiro.tyfo.backend.RemoteBackend
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Surface

data class TaskSnapshot(
    val taskId: Int,
    val displayId: Int,
    val basePackage: String?,
    val topPackage: String?,
    val lastActiveTime: Long
) {
    fun containsPackage(packageName: String): Boolean {
        return basePackage == packageName || topPackage == packageName
    }
}

/**
 * Creates and manages a VirtualDisplay for agent operation.
 * The agent runs apps on this virtual screen while the user uses the physical screen.
 *
 * The display lives in the privileged service process ([RemoteBackend]), which renders it
 * into its own ImageReader and serves screenshots of it over Binder.
 *
 * Supports task reparenting: moving Activity tasks between the VD and physical display
 * via moveTaskToDisplay() for view/takeover mode.
 */
class VirtualDisplayManager(
    private val context: Context,
    private val backend: PrivilegeBackend
) {

    companion object {
        private const val TAG = "VirtualDisplayManager"
        private const val DISPLAY_NAME = "Tyfo-Agent"

        // Default virtual display size (compact to reduce rendering + LLM token cost)
        const val DEFAULT_WIDTH = 540
        const val DEFAULT_HEIGHT = 1170
        const val DEFAULT_DPI = 240

        // Hidden VirtualDisplay flags (not in public API)
        // VirtualDisplay flags: mFlags=120533
        private const val VIRTUAL_DISPLAY_FLAG_SECURE = 1 shl 2                 // 4
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6         // 64
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7   // 128
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10              // 1024
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11   // 2048
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12      // 4096
        private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13 // 8192
        private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14            // 16384
        private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15 // 32768
        private const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16 // 65536
    }

    /**
     * The display ID of the virtual display, or -1 if not created.
     */
    var displayId: Int = -1
        private set

    val isCreated: Boolean
        get() = displayId > 0

    var width: Int = DEFAULT_WIDTH
        private set

    var height: Int = DEFAULT_HEIGHT
        private set

    // ── Task reparenting API (delegates to PrivilegeBackend) ──────────

    /**
     * Get the top non-Tyfo task ID running on the specified display.
     * Returns null if no suitable task is found.
     */
    fun getTopTaskIdOnDisplay(targetDisplayId: Int): Int? {
        val id = backend.getTopTaskIdOnDisplay(targetDisplayId, context.packageName)
        return if (id > 0) id else null
    }

    fun getTopTaskOnDisplay(targetDisplayId: Int): TaskSnapshot? {
        return getRunningTasks(limit = 100)
            .filter { it.displayId == targetDisplayId }
            .firstOrNull { !isTyfoTask(it) }
    }

    fun getTaskSnapshot(taskId: Int): TaskSnapshot? {
        return getRunningTasks(limit = 100).firstOrNull { it.taskId == taskId }
    }

    fun toTaskSnapshot(taskInfo: Any?): TaskSnapshot? {
        return taskInfoToSnapshot(taskInfo)
    }

    fun getRunningTasks(limit: Int = 50): List<TaskSnapshot> {
        return try {
            DisplayOps.getRunningTaskInfos(limit, displayId)
                .mapNotNull { taskInfoToSnapshot(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get running tasks", e)
            emptyList()
        }
    }

    private fun isTyfoTask(task: TaskSnapshot): Boolean {
        return task.containsPackage(context.packageName)
    }

    fun isOwnPackage(task: TaskSnapshot): Boolean {
        val pkg = context.packageName
        return task.topPackage == pkg || task.basePackage == pkg
    }

    private fun taskInfoToSnapshot(taskInfo: Any?): TaskSnapshot? {
        if (taskInfo == null) return null
        return try {
            val taskId = DisplayOps.readIntField(taskInfo, "taskId") ?: return null
            val displayId = DisplayOps.readIntField(taskInfo, "displayId") ?: -1
            val baseActivity = DisplayOps.readComponent(taskInfo, "baseActivity")
                ?: DisplayOps.readComponentFromGetter(taskInfo, "getBaseActivity")
            val topActivity = DisplayOps.readComponent(taskInfo, "topActivity")
                ?: DisplayOps.readComponentFromGetter(taskInfo, "getTopActivity")
            val lastActiveTime = DisplayOps.readLongField(taskInfo, "lastActiveTime") ?: 0L
            TaskSnapshot(
                taskId = taskId,
                displayId = displayId,
                basePackage = baseActivity?.packageName,
                topPackage = topActivity?.packageName,
                lastActiveTime = lastActiveTime
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse task snapshot: ${e.message}")
            null
        }
    }

    /**
     * Move a task to a different display.
     * Returns true on success.
     */
    fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean {
        return backend.moveTaskToDisplay(taskId, targetDisplayId)
    }

    // ── Surface switching ──────────────────────────────────────────────

    /** True when the VD surface is set to SurfaceView (VIEW mode). */
    @Volatile
    var isUsingExternalSurface = false
        private set

    /**
     * Switch the VD's rendering target between a SurfaceView and the service's ImageReader.
     * Pass a SurfaceView's surface to render VD content on Display 0 (VIEW mode); the Surface
     * goes cross-process to the PrivilegedService. Pass null to switch back (BACKGROUND mode).
     *
     * Screenshots (SurfaceFlinger) are unaffected — they capture from the compositor
     * regardless of which surface the VD renders to.
     */
    fun setSurface(surface: Surface?) {
        if (!isCreated) return
        isUsingExternalSurface = surface != null
        backend.setVirtualDisplaySurface(surface)
        Log.i(TAG, if (surface != null) "VD surface switched to SurfaceView" else "VD surface switched back to ImageReader")
    }

    // ── VirtualDisplay lifecycle ─────────────────────────────────────────

    /**
     * Create the virtual display in the privileged service.
     * Returns the displayId or -1 on failure.
     */
    fun create(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI
    ): Int {
        if (isCreated) {
            Log.w(TAG, "Virtual display already created (displayId=$displayId)")
            return displayId
        }
        if (backend !is RemoteBackend) {
            Log.e(TAG, "No privileged backend connected — can't create the virtual display")
            return -1
        }

        try {
            // No SHOULD_SHOW_SYSTEM_DECORATIONS: on Android 17 it puts a status bar and a
            // connected-display Taskbar (3-button nav piled onto dock icons) inside the VD.
            // The VD gets home via VirtualDisplayConfig.setHomeSupported instead (see
            // PrivilegedService). A VD without home support can't host HOME, and HOME launched
            // at it lands on the phone's own screen, so never launch HOME onto the VD.
            //
            // No AUTO_MIRROR: without TRUSTED permission it makes the VD permanently mirror
            // display 0 instead of showing independently launched activities.
            // OWN_CONTENT_ONLY is required for OWN_DISPLAY_GROUP to stick: DMS turns a PUBLIC
            // display into AUTO_MIRROR, and AUTO_MIRROR forces the default display group —
            // where the VD powers off (renders black) whenever the phone screen turns off.
            // DEVICE_DISPLAY_GROUP stays as a fallback: without some non-default group flag
            // DMS drops ALWAYS_UNLOCKED.
            //
            // SECURE: lets the VD show (and the agent capture) FLAG_SECURE windows instead of
            // black. On Android 17 it also exempts the VD from the canDisplayOnRemoteDevices
            // opt-out that otherwise blocks some activities. DMS only honours it for a caller
            // that holds CAPTURE_SECURE_VIDEO_OUTPUT (root) or is the system uid, so the
            // privileged service strips it when it runs as shell (would reject the whole VD).
            // The service also decides the display-group strategy: on 17 it drops
            // OWN/DEVICE_DISPLAY_GROUP and routes creation through a VirtualDevice instead,
            // which is the only way to leave display group 0 there (separate_timeouts).
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                VIRTUAL_DISPLAY_FLAG_SECURE or
                VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                VIRTUAL_DISPLAY_FLAG_TRUSTED or
                VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED or
                VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP or
                VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED

            // Try progressively reduced flag sets until VD creation succeeds
            val flagSetsToTry = listOf(
                flags,
                // Without ALWAYS_UNLOCKED but keep TRUSTED
                flags and VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED.inv(),
                // Without TRUSTED but keep ALWAYS_UNLOCKED
                flags and VIRTUAL_DISPLAY_FLAG_TRUSTED.inv(),
                // Without both TRUSTED + ALWAYS_UNLOCKED
                flags and (VIRTUAL_DISPLAY_FLAG_TRUSTED or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED).inv(),
                // Minimal: PUBLIC + SUPPORTS_TOUCH + ROTATES_WITH_CONTENT
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                    VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
            )
            var id = -1
            for ((idx, flagSet) in flagSetsToTry.withIndex()) {
                id = backend.createVirtualDisplay(DISPLAY_NAME, width, height, dpi, flagSet)
                if (id > 0) {
                    Log.i(TAG, "VD created with flag set $idx (flags=0x${Integer.toHexString(flagSet)})")
                    break
                }
                Log.w(TAG, "VD creation failed with flag set $idx (flags=0x${Integer.toHexString(flagSet)})")
            }
            if (id <= 0) {
                Log.e(TAG, "Remote createVirtualDisplay failed (id=$id)")
                return -1
            }
            displayId = id
            this.width = width
            this.height = height
            Log.i(TAG, "VD created: displayId=$id, ${width}x${height} @${dpi}dpi (capture via remote service)")

            // Set IME policy to LOCAL so the keyboard renders inside the VD
            // (visible through SurfaceView in VIEW/TAKEOVER mode).
            // Without this, keyboard appears on Display 0 and input doesn't reach the VD.
            // DISPLAY_IME_POLICY_LOCAL = 0
            backend.setDisplayImePolicy(id, 0)
            // Note: keyguard dismissal is handled by the PrivilegedService (shell uid)
            // immediately after VD creation via `wm dismiss-keyguard`.

            return id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display", e)
            destroy()
            return -1
        }
    }

    /**
     * Destroy the virtual display and release resources.
     */
    fun destroy() {
        if (displayId > 0) {
            backend.releaseVirtualDisplay()
            displayId = -1
        }
        width = DEFAULT_WIDTH
        height = DEFAULT_HEIGHT
        Log.i(TAG, "Virtual display destroyed")
    }
}
