package app.tunes.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicBoolean
import app.tunes.i18n.t

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
        "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

private const val MUSIC_URL = "https://music.youtube.com"

private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/130.0.0.0 Mobile Safari/537.36"

/**
 * Halaman penuh berisi login Google (lewat WebView). Begitu sampai di music.youtube.com dan cookie
 * login sudah ada, cookie diserahkan lewat [onCookie] sekali saja.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onCookie: (String) -> Unit, onBack: () -> Unit) {
    PageBackHandler(onBack = onBack)
    val delivered = remember { AtomicBoolean(false) }
    PageSurface {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Masuk") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("Kembali"))
                        }
                    },
                )
            },
        ) { padding ->
            AndroidView(
                modifier = Modifier.padding(padding).fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = BROWSER_UA
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            // Hanya https yang boleh dibuka di sini; skema lain (http, intent:, file:, dll.) diblokir.
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = request?.url?.scheme?.equals("https", ignoreCase = true) != true

                            override fun onPageFinished(view: WebView?, url: String?) {
                                // Host harus persis music.youtube.com (startsWith akan meloloskan music.youtube.com.evil.com).
                                val u = url?.let(Uri::parse)
                                if (u == null || u.scheme != "https" || u.host != "music.youtube.com") return
                                val cookie = CookieManager.getInstance().getCookie(MUSIC_URL).orEmpty()
                                if (cookie.contains("SAPISID=") && delivered.compareAndSet(false, true)) {
                                    onCookie(cookie)
                                }
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
            )
        }
    }
}
