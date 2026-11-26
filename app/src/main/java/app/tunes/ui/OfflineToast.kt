package app.tunes.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import app.tunes.i18n.t

/**
 * Munculkan toast "Kamu sedang offline." saat perangkat kehilangan internet (atau aplikasi dibuka dalam
 * keadaan offline), dan "Kamu kembali online." saat internet kembali. Ada jeda singkat supaya pindah
 * Wi-Fi <-> data seluler tidak dianggap offline, dan toast tidak diulang selama keadaannya sama.
 */
@Composable
fun OfflineToastEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val handler = Handler(Looper.getMainLooper())
        var shown = false

        fun isOffline(): Boolean = try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }

        val check = Runnable {
            if (isOffline()) {
                if (!shown) {
                    shown = true
                    Toast.makeText(app, t("Kamu sedang offline."), Toast.LENGTH_SHORT).show()
                }
            } else {
                shown = false
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                handler.removeCallbacks(check)
                handler.postDelayed(check, 1_500)
            }

            override fun onAvailable(network: Network) {
                handler.removeCallbacks(check)
                // Hanya kalau tadi sudah diberi tahu offline (pindah Wi-Fi <-> seluler tidak memicunya).
                if (shown) {
                    shown = false
                    Toast.makeText(app, t("Kamu kembali online."), Toast.LENGTH_SHORT).show()
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback, handler) }
        handler.post(check)

        onDispose {
            runCatching { cm.unregisterNetworkCallback(callback) }
            handler.removeCallbacksAndMessages(null)
        }
    }
}
