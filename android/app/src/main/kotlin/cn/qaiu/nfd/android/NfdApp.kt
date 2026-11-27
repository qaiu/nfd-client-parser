package cn.qaiu.nfd.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import cn.qaiu.nfd.android.daemon.DaemonClient
import cn.qaiu.nfd.android.daemon.Endpoint
import cn.qaiu.nfd.android.history.ParseHistory
import cn.qaiu.nfd.android.runtime.RuntimeLog
import java.io.File
import java.security.SecureRandom
import java.util.TimeZone
import android.util.Base64

class NfdApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var daemon: DaemonClient
        private set
    lateinit var history: ParseHistory
        private set

    val runtimeLog by lazy { RuntimeLog(File(filesDir, "parser-runtime.log")) }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        daemon = DaemonClient({ prefs.endpoint })
        history = ParseHistory(File(filesDir, "parse-history.json"))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SETUP, "Termux 安装", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_SETUP = "setup"
    }
}

/** Connection settings. The token is generated on first launch and handed to Termux by the installer. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("nfd", Context.MODE_PRIVATE)

    init {
        if (prefs.getString(KEY_TOKEN, null).isNullOrBlank()) {
            prefs.edit { putString(KEY_TOKEN, newToken()) }
        }
        if (BuildConfig.EMBEDDED_RUNTIME && prefs.getString(KEY_LOCAL_TOKEN, null).isNullOrBlank()) {
            prefs.edit { putString(KEY_LOCAL_TOKEN, newToken()) }
        }
    }

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit { putString(KEY_HOST, value.trim().ifEmpty { DEFAULT_HOST }) }

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit { putInt(KEY_PORT, value) }

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TOKEN, value.trim()) }

    var lastLink: String
        get() = prefs.getString(KEY_LINK, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LINK, value) }

    /** Defaults to on for phones set to a mainland China time zone; the user can switch it. */
    var useChinaMirror: Boolean
        get() = prefs.getBoolean(KEY_CHINA_MIRROR, TimeZone.getDefault().id in CHINA_ZONES)
        set(value) = prefs.edit { putBoolean(KEY_CHINA_MIRROR, value) }

    val endpoint: Endpoint
        get() = if (BuildConfig.EMBEDDED_RUNTIME) {
            Endpoint(DEFAULT_HOST, LOCAL_PORT, prefs.getString(KEY_LOCAL_TOKEN, "") ?: "")
        } else {
            Endpoint(host, port, token)
        }

    fun regenerateToken(): String = newToken().also { token = it }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 17890
        private const val LOCAL_PORT = 17891
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_LOCAL_TOKEN = "local_token"
        private const val KEY_LINK = "last_link"
        private const val KEY_CHINA_MIRROR = "china_mirror"
        private val CHINA_ZONES = setOf("Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin", "Asia/Urumqi", "PRC")

        fun newToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
}
