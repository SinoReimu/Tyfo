package ai.opencyvis.backend

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings

enum class SetupState {
    NEED_WIFI,
    UNSUPPORTED_VERSION,
    NEED_DEVELOPER_OPTIONS,
    NEED_WIRELESS_DEBUGGING,
    NEED_PAIRING,
    ALREADY_CONNECTED
}

object SetupStateDetector {

    private const val PREFS = "opencyvis_config"
    private const val KEY_WIRELESS_ADB = "backend_wireless_adb"

    /**
     * 用户在引导页手动选了「无线 ADB」。默认走 root（其次 Shizuku），只有选了无线 ADB
     * 才检测 WiFi / 无线调试，启动时也才去尝试 adb-direct。
     */
    fun isWirelessAdbChosen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WIRELESS_ADB, false)

    fun setWirelessAdbChosen(context: Context, chosen: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WIRELESS_ADB, chosen).apply()
    }

    /** 无线 ADB 的前置条件检查，只在用户选了无线 ADB 之后调用。 */
    fun detect(context: Context, isBackendConnected: Boolean): SetupState {
        if (isBackendConnected) return SetupState.ALREADY_CONNECTED
        if (!hasWifi(context)) return SetupState.NEED_WIFI
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return SetupState.UNSUPPORTED_VERSION
        if (!isDevOptionsEnabled(context)) return SetupState.NEED_DEVELOPER_OPTIONS
        if (!isWirelessDebuggingEnabled(context)) return SetupState.NEED_WIRELESS_DEBUGGING
        return SetupState.NEED_PAIRING
    }

    fun hasWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isDevOptionsEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) == 1
    }

    fun isWirelessDebuggingEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            "adb_wifi_enabled", 0
        ) == 1
    }
}
