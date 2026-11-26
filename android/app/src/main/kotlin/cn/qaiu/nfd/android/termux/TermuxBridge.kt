package cn.qaiu.nfd.android.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * Talks to the Termux app. Starting the daemon from the app uses Termux's RUN_COMMAND intent,
 * which needs {@code allow-external-apps = true} in ~/.termux/termux.properties (the installer
 * sets it when asked) plus the runtime permission {@link #RUN_COMMAND_PERMISSION}.
 */
object TermuxBridge {
    const val PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val PREFIX = "/data/data/com.termux/files/usr"
    private const val HOME = "/data/data/com.termux/files/home"
    const val FDROID_URL = "https://f-droid.org/packages/com.termux/"
    const val GITHUB_URL = "https://github.com/termux/termux-app/releases"

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (ex: PackageManager.NameNotFoundException) {
        false
    }

    fun canRunCommands(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun open(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun openStore(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Runs {@code nfd-termux <args>} in the background; returns an error message or null. */
    fun runDaemonCommand(context: Context, vararg args: String): String? {
        if (!installed(context)) return "未安装 Termux"
        if (!canRunCommands(context)) return "未授予「在 Termux 中运行命令」权限"
        val intent = Intent("com.termux.RUN_COMMAND")
            .setClassName(PACKAGE, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("$PREFIX/bin/nfd-termux", *args))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        return try {
            ContextCompat.startForegroundService(context, intent)
            null
        } catch (ex: SecurityException) {
            "Termux 拒绝了请求：请确认已在 Termux 中开启 allow-external-apps"
        } catch (ex: RuntimeException) {
            "无法启动 Termux 服务：${ex.message}"
        }
    }
}
