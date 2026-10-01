package com.shiro.tyfo.display

import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

object AppOccupancyPolicy {
    enum class Decision { ALLOW, BLOCK, ALLOW_AND_WATCH }

    /**
     * Decide an activity start for [pkg] reported by IActivityController.activityStarting.
     *
     * The intent is a cloneFilter() copy (no flags, extras or caller), so the display it will
     * land on is unknown. A MAIN/LAUNCHER start of an occupied package is what a home-screen
     * icon sends and is blocked. Any other start may be the app navigating inside the VD, so
     * it's allowed and the escape guard is asked to double-check Display 0.
     */
    fun decide(
        action: String?,
        categories: Set<String>?,
        pkg: String?,
        occupied: Set<String>,
        agentLaunching: Boolean
    ): Decision {
        if (pkg == null || pkg !in occupied) return Decision.ALLOW
        if (agentLaunching) return Decision.ALLOW
        val isLauncherIntent = action == Intent.ACTION_MAIN &&
                categories?.contains(Intent.CATEGORY_LAUNCHER) == true
        return if (isLauncherIntent) Decision.BLOCK else Decision.ALLOW_AND_WATCH
    }
}

/**
 * System-wide activity-start gate that keeps the app the agent is driving on the virtual
 * display from being opened on the phone's own screen.
 *
 * Without it, tapping the app's icon makes Android reparent the agent's task to Display 0,
 * and [TaskDisplayGuard] can only move it back after it has already flashed on screen.
 * Installed through IActivityManager.setActivityController (needs SET_ACTIVITY_WATCHER, which
 * the root/shell backend holds), this vetoes the launch before any task moves.
 *
 * system_server calls in synchronously while holding the WM global lock, so handlers only
 * read in-memory state and post their side effects. Unhandled failures fall back to the
 * framework's default answers. If this process dies, system_server drops the controller on
 * its next call.
 */
class AppOccupancyController(
    private val onLaunchBlocked: (String) -> Unit,
    private val onOccupiedStart: (String) -> Unit
) : Binder() {

    companion object {
        private const val TAG = "AppOccupancy"
        private const val DESCRIPTOR = "android.app.IActivityController"

        // android.app.IActivityController.Stub transaction codes (unchanged since Android 4.x,
        // verified against the API 36 framework).
        private const val TRANSACTION_activityStarting = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TRANSACTION_activityResuming = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_appCrashed = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_appEarlyNotResponding = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_appNotResponding = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_systemNotResponding = IBinder.FIRST_CALL_TRANSACTION + 5

        private val agentLaunchDepth = AtomicInteger(0)

        /** Run [block] (an agent-initiated launch) without the gate blocking it. */
        fun <T> asAgentLaunch(block: () -> T): T {
            agentLaunchDepth.incrementAndGet()
            try {
                return block()
            } finally {
                agentLaunchDepth.decrementAndGet()
            }
        }
    }

    init {
        attachInterface(null, DESCRIPTOR)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var occupiedPackages: Set<String> = emptySet()
        private set

    fun setOccupiedPackages(packages: Set<String>) {
        if (packages == occupiedPackages) return
        occupiedPackages = packages
        Log.i(TAG, "Occupied packages: $packages")
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_activityStarting -> {
                val allow = try {
                    data.enforceInterface(DESCRIPTOR)
                    val intent = data.readTypedObject(Intent.CREATOR)
                    val pkg = data.readString()
                    activityStarting(intent, pkg)
                } catch (e: Exception) {
                    Log.w(TAG, "activityStarting failed, allowing: ${e.message}")
                    true
                }
                reply?.writeNoException()
                reply?.writeInt(if (allow) 1 else 0)
            }
            // true: resume normally / show the normal crash dialog.
            TRANSACTION_activityResuming, TRANSACTION_appCrashed -> {
                reply?.writeNoException()
                reply?.writeInt(1)
            }
            // 0: normal ANR handling.
            TRANSACTION_appEarlyNotResponding, TRANSACTION_appNotResponding -> {
                reply?.writeNoException()
                reply?.writeInt(0)
            }
            // -1: let the watchdog proceed as if no controller were installed.
            TRANSACTION_systemNotResponding -> {
                reply?.writeNoException()
                reply?.writeInt(-1)
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    private fun activityStarting(intent: Intent?, pkg: String?): Boolean {
        val decision = AppOccupancyPolicy.decide(
            action = intent?.action,
            categories = intent?.categories,
            pkg = pkg,
            occupied = occupiedPackages,
            agentLaunching = agentLaunchDepth.get() > 0
        )
        return when (decision) {
            AppOccupancyPolicy.Decision.ALLOW -> true
            AppOccupancyPolicy.Decision.ALLOW_AND_WATCH -> {
                mainHandler.post { onOccupiedStart(pkg!!) }
                true
            }
            AppOccupancyPolicy.Decision.BLOCK -> {
                Log.i(TAG, "Blocked launch of occupied $pkg (${intent?.component?.flattenToShortString()})")
                mainHandler.post { onLaunchBlocked(pkg!!) }
                false
            }
        }
    }
}
