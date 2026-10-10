package com.shiro.tyfo.backend

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Minimal Context for shell uid app_process. Provides just enough for
 * DisplayManager, WindowManager, and UserManager instantiation via reflection.
 * Modeled after scrcpy's FakeContext approach.
 *
 * Only usable when running as shell uid (2000) via app_process — throws
 * if called from the main app process.
 */
@SuppressLint("PrivateApi")
object FakeContext {
    private const val TAG = "FakeContext"
    const val PACKAGE = "com.android.shell"

    val instance: Context by lazy {
        val uid = Process.myUid()
        if (uid != 2000 && uid != 0 && uid != 1000) {
            throw UnsupportedOperationException(
                "FakeContext is only available in shell process mode (current uid=$uid)"
            )
        }
        ShellContext()
    }

    private class ShellContext : ContextWrapper(null) {

        override fun getPackageName(): String = PACKAGE
        override fun getOpPackageName(): String = PACKAGE

        // VirtualDeviceInternal's constructor does context.getApplicationContext()
        // .getAttributionSource(); with a null base ContextWrapper these would NPE, so
        // answer them here. The attribution package must be the one the CDM association
        // was created for (com.android.shell); at system/root uid the uid field isn't
        // enforced to match, so shell's 2000 is fine.
        override fun getApplicationContext(): Context = this

        override fun getAttributionSource(): android.content.AttributionSource =
            android.content.AttributionSource.Builder(2000)
                .setPackageName(PACKAGE)
                .build()

        override fun getSystemService(name: String): Any? {
            return when (name) {
                Context.WINDOW_SERVICE -> getWindowManager()
                Context.USER_SERVICE -> getUserManager()
                Context.DISPLAY_SERVICE -> getDisplayManager()
                else -> {
                    Log.w(TAG, "getSystemService($name) not supported in FakeContext")
                    null
                }
            }
        }

        private fun getWindowManager(): Any? {
            return try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "window")
                val stubClass = Class.forName("android.view.IWindowManager\$Stub")
                val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                asInterface.invoke(null, binder)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get IWindowManager: ${e.message}")
                null
            }
        }

        private fun getUserManager(): Any? {
            return try {
                if (Build.VERSION.SDK_INT >= 34) {
                    // API 34+ DisplayManager.createVirtualDisplay checks UserManager.
                    // Use Unsafe to allocate without constructor (no Context needed).
                    val unsafeClass = Class.forName("sun.misc.Unsafe")
                    val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
                        isAccessible = true
                    }.get(null)
                    val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
                    allocate.invoke(theUnsafe, android.os.UserManager::class.java)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create UserManager: ${e.message}")
                null
            }
        }

        private fun getDisplayManager(): Any? {
            return try {
                val ctor = android.hardware.display.DisplayManager::class.java
                    .getDeclaredConstructor(Context::class.java)
                ctor.isAccessible = true
                ctor.newInstance(this)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create DisplayManager: ${e.message}")
                null
            }
        }
    }
}
