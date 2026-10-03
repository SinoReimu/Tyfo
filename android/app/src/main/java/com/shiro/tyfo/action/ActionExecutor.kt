package com.shiro.tyfo.action

import android.content.Context
import android.graphics.Point
import com.shiro.tyfo.backend.PrivilegeBackend
import com.shiro.tyfo.engine.StepResult
import com.shiro.tyfo.input.InputInjector
import kotlinx.coroutines.delay

/**
 * Dispatches Action objects to InputInjector / AppLauncher and returns StepResults.
 * Ported from Python ActionExecutor.
 *
 * @param displayId Target display (0 for physical, >0 for virtual)
 * @param displaySize Fixed display size (for virtual displays with known resolution)
 */
class ActionExecutor(
    private val context: Context,
    private val displayId: Int = 0,
    displaySize: Point? = null,
    private val onOpenAppSuccess: ((packageName: String) -> Unit)? = null,
    backend: PrivilegeBackend,
    private val blacklistedPackages: Set<String> = emptySet()
) {

    private val inputInjector = InputInjector(context, displayId, displaySize, backend)
    private val appLauncher = AppLauncher(context, displayId, backend)
    /** Keys that don't work on the virtual display (home / recents); see [execute]. */
    private val vdUnsupportedKeys = setOf("home", "recent")
    private val launcherPackage: String? by lazy {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }

    /**
     * Execute an action and return a StepResult.
     */

    suspend fun execute(action: Action, step: Int): StepResult {
        val startTime = System.currentTimeMillis()
        val completed = false  // will be overridden by caller based on LLM response

        if (displayId != 0 && (action is Action.Tap || action is Action.Swipe || action is Action.LongPress)) {
            if (isLauncherOnTop()) {
                val elapsed = System.currentTimeMillis() - startTime
                return StepResult(step, action.typeName,
                    "Cannot tap/swipe on home launcher. Use open_app to launch apps, or list_apps to search.", false,
                    "Action blocked: launcher is the foreground app on virtual display. Use open_app or list_apps instead.", elapsed, false)
            }
        }

        val (success, detail) = try {
            when (action) {
                is Action.Tap -> {
                    val ok = inputInjector.tap(action.x, action.y)
                    ok to "Tapped at (${action.x}, ${action.y})"
                }

                is Action.LongPress -> {
                    val ok = inputInjector.longPress(action.x, action.y, action.durationMs)
                    ok to "Long pressed at (${action.x}, ${action.y})"
                }

                is Action.OpenApp -> {
                    val result = appLauncher.launch(action.appName)
                    val ok = result.packageName != null
                    if (ok && result.packageName in blacklistedPackages) {
                        false to "Cannot open protected app: ${action.appName}"
                    } else {
                        if (ok) {
                            onOpenAppSuccess?.invoke(result.packageName!!)
                        }
                        ok to result.description
                    }
                }

                is Action.Swipe -> {
                    val ok = inputInjector.swipe(
                        action.startX, action.startY, action.endX, action.endY, action.durationMs
                    )
                    ok to "Swiped (${action.startX}, ${action.startY}) -> (${action.endX}, ${action.endY}) in ${action.durationMs}ms"
                }

                is Action.KeyEvent -> {
                    if (displayId != 0 && action.key in vdUnsupportedKeys) {
                        // An injected HOME does nothing on the VD, and recents isn't per display;
                        // tell the model to use open_app rather than let it retry.
                        false to "Home and recent keys don't work on the virtual display. Use open_app to switch apps, or back to leave the current screen."
                    } else {
                        val ok = inputInjector.keyEvent(action.key)
                        ok to "Key event: ${action.key}"
                    }
                }

                is Action.TypeText -> {
                    val typed = inputInjector.typeText(action.text)
                    when {
                        !typed -> false to "Typing failed: ${action.text}"
                        !action.submit -> true to "Typed: ${action.text}"
                        else -> {
                            delay(150)
                            if (inputInjector.keyEvent("enter")) true to "Typed: ${action.text} + enter"
                            else false to "Typed: ${action.text}, but pressing enter failed"
                        }
                    }
                }

                is Action.Wait -> {
                    delay(action.seconds * 1000L)
                    true to "Waited ${action.seconds}s"
                }

                is Action.ListApps -> {
                    val apps = appLauncher.listApps(action.keyword.ifBlank { null })
                    val kw = if (action.keyword.isNotBlank()) " matching '${action.keyword}'" else ""
                    if (apps.isEmpty() && action.keyword.isNotBlank()) {
                        true to "No apps found matching '${action.keyword}'. Try a different keyword, or use list_apps without keyword to see all apps."
                    } else {
                        true to "Installed apps$kw (${apps.size}): ${apps.joinToString(", ")}"
                    }
                }
            }
        } catch (e: Exception) {
            false to "Error: ${e.message}"
        }

        val duration = System.currentTimeMillis() - startTime

        return StepResult(
            step = step,
            actionType = action.typeName,
            thought = action.thought,
            success = success,
            detail = detail,
            durationMs = duration,
            completed = completed
        )
    }

    private fun isLauncherOnTop(): Boolean {
        if (displayId == 0) return false
        val pkg = launcherPackage ?: return false
        val atm = try {
            Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
        } catch (_: Exception) { return false }
        val tasks = try {
            val method = atm.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
            method.invoke(atm, 10, false, false, displayId) as? List<*>
        } catch (_: Exception) { null } ?: return false
        val topTask = tasks.firstOrNull() ?: return false
        val baseComponent = try {
            topTask.javaClass.getField("baseActivity").get(topTask) as? android.content.ComponentName
        } catch (_: Exception) { null }
        return baseComponent?.packageName == pkg
    }
}
