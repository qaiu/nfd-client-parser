package cn.qaiu.nfd.android.ui

import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.net.URI
import org.json.JSONTokener

internal enum class WebLogin(
    val type: String,
    val url: String,
    val credentialHost: String,
    val domain: String,
    val field: String = "cookie",
) {
    QUARK("qk", "https://pan.quark.cn/?fr=pc&platform=pc", "pan.quark.cn", "quark.cn"),
    UC("uc", "https://drive.uc.cn/", "drive.uc.cn", "uc.cn"),
    PAN123("ye", "https://yun.123pan.cn/", "yun.123pan.cn", "123pan.cn", "token");

    fun allowsNavigation(url: String?): Boolean {
        val host = secureHost(url) ?: return false
        return host == domain || host.endsWith(".$domain") ||
            (this == QUARK && (host == "uc.cn" || host.endsWith(".uc.cn")))
    }

    fun canRead(url: String?): Boolean = secureHost(url) == credentialHost

    private fun secureHost(url: String?): String? {
        val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        return uri.host?.lowercase(java.util.Locale.ROOT)?.takeIf {
            uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443)
        }
    }

    fun hasSession(cookie: String): Boolean {
        val keys = cookie.split(';').mapNotNull { pair ->
            val parts = pair.trim().split('=', limit = 2)
            parts.first().takeIf { parts.getOrNull(1)?.isNotBlank() == true }
        }.toSet()
        return "__pus" in keys && "__puus" in keys
    }
}

private val jwt = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
private val chromeVersion = Regex("Chrome/[0-9.]+")

internal fun pan123Token(value: String): String? =
    value.trim().removePrefix("Bearer ").takeIf { it.length <= 8192 && jwt.matches(it) }

@Composable
internal fun WebLoginDialog(
    name: String,
    login: WebLogin,
    onDismiss: () -> Unit,
    onSave: (String, (Boolean) -> Unit) -> Unit,
) {
    var web by remember(login) { mutableStateOf<WebView?>(null) }
    var error by remember(login) { mutableStateOf("") }
    var saving by remember(login) { mutableStateOf(false) }

    fun goBack() {
        if (saving) return
        val view = web
        if (view?.canGoBack() == true) view.goBack() else onDismiss()
    }

    fun save(view: WebView, credential: String?) {
        if (web !== view || !login.canRead(view.url)) {
            saving = false
            return
        }
        if (credential == null) {
            saving = false
            error = "尚未取得登录信息。请完成官网登录后重试。"
            return
        }
        onSave(credential) { saved ->
            saving = false
            if (web === view) {
                if (saved) onDismiss() else error = "写入失败，请检查本机解析引擎是否在线。"
            }
        }
    }

    Dialog(
        onDismissRequest = ::goBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        BackHandler { goBack() }
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭") }
                    Text(name, Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(enabled = !saving && web != null, onClick = {
                        val view = web ?: return@TextButton
                        if (!login.canRead(view.url)) {
                            error = "请先完成登录并回到网盘主页。"
                            return@TextButton
                        }
                        saving = true
                        error = ""
                        if (login.field == "cookie") {
                            val jar = CookieManager.getInstance()
                            val cookie = jar.getCookie(login.url)?.takeIf(login::hasSession)
                            if (cookie != null) jar.flush()
                            save(view, cookie)
                        } else {
                            view.evaluateJavascript(
                                "location.origin === 'https://yun.123pan.cn' ? localStorage.getItem('authorToken') : null",
                            ) { reply ->
                                val token = runCatching { JSONTokener(reply).nextValue() as? String }.getOrNull()
                                save(view, token?.let(::pan123Token))
                            }
                        }
                    }) { Text(if (saving) "保存中…" else "保存登录") }
                }
                if (error.isNotEmpty()) {
                    Text(error, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            val chrome = chromeVersion.find(settings.userAgentString)?.value ?: "Chrome/131.0.0.0"
                            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (!request.isForMainFrame) return false
                                    return !login.allowsNavigation(request.url.toString()).also { allowed ->
                                        if (!allowed) error = "已阻止跳转到网盘登录域名之外。"
                                    }
                                }
                            }
                            web = this
                            loadUrl(login.url)
                        }
                    },
                    onRelease = { view ->
                        web = null
                        view.stopLoading()
                        view.destroy()
                    },
                )
            }
        }
    }
}
