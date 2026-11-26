package cn.qaiu.nfd.android.termux

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cn.qaiu.nfd.android.MainActivity
import cn.qaiu.nfd.android.NfdApp
import cn.qaiu.nfd.android.R
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BootstrapInfo(val baseUrl: String, val installerServed: Boolean = false, val bundleServed: Boolean = false)

/**
 * Foreground service so the bootstrap server keeps answering while the user is in Termux;
 * a cached background app would otherwise be frozen mid-download.
 */
class BootstrapService : Service() {

    private var server: BootstrapServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopSelfRunnable = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val running = server ?: try {
            BootstrapServer(open = ::openAsset, onServed = ::served).start().also { server = it }
        } catch (ex: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        mutableState.value = mutableState.value?.takeIf { it.baseUrl == running.baseUrl } ?: BootstrapInfo(running.baseUrl)
        handler.removeCallbacks(stopSelfRunnable)
        handler.postDelayed(stopSelfRunnable, TIMEOUT_MILLIS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopSelfRunnable)
        server?.close()
        server = null
        mutableState.value = null
        super.onDestroy()
    }

    private fun openAsset(name: String): java.io.InputStream? {
        val path = ASSETS[name] ?: return null
        return try {
            assets.open(path)
        } catch (ex: FileNotFoundException) {
            null
        }
    }

    private fun served(name: String) {
        val current = mutableState.value ?: return
        mutableState.value = when (name) {
            BootstrapServer.INSTALLER -> current.copy(installerServed = true)
            BootstrapServer.BUNDLE -> current.copy(bundleServed = true)
            else -> current
        }
        if (name == BootstrapServer.BUNDLE) {
            handler.removeCallbacks(stopSelfRunnable)
            handler.postDelayed(stopSelfRunnable, AFTER_BUNDLE_MILLIS)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BootstrapService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NfdApp.CHANNEL_SETUP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在为 Termux 提供安装包")
            .setContentText("在 Termux 中粘贴安装命令；完成后此通知会自动消失")
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "cn.qaiu.nfd.android.STOP_BOOTSTRAP"
        private const val TIMEOUT_MILLIS = 15 * 60 * 1000L
        private const val AFTER_BUNDLE_MILLIS = 60 * 1000L

        /** URL name -> asset path; the app build stores the bundle as .tgz (see app/build.gradle.kts). */
        private val ASSETS = mapOf(
            BootstrapServer.INSTALLER to "termux/install.sh",
            BootstrapServer.BUNDLE to "termux/nfd-termux-daemon.tgz",
        )

        private val mutableState = MutableStateFlow<BootstrapInfo?>(null)
        val state: StateFlow<BootstrapInfo?> = mutableState.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BootstrapService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BootstrapService::class.java))
        }

        fun installCommand(info: BootstrapInfo, token: String, port: Int, allowAppControl: Boolean): String = buildString {
            append("curl -fsSL ").append(info.baseUrl).append("/install.sh | bash -s -- --source ").append(info.baseUrl)
            append(" --token ").append(token).append(" --port ").append(port)
            if (allowAppControl) append(" --allow-app-control")
        }
    }
}
