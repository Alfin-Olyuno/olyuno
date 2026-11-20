package app.tunes.ui

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * Penjaga refresh rate tinggi. HP dengan layar adaptif (Xiaomi, Samsung, dll) menurunkan ke 60Hz saat
 * layar terlihat "diam" atau jarang menggambar, sehingga permintaan refresh rate saja sering tidak terasa.
 * Selama nyala:
 *  - Android 15+: kategori frame rate jendela diminta TINGGI.
 *  - Semua versi: satu titik 1dp hampir transparan digambar ulang di tiap frame, jadi sistem melihat
 *    aplikasi terus menggambar di kecepatan penuh dan tidak menurunkan refresh rate.
 * Mati: tidak ada yang dipasang, perilaku kembali seperti biasa.
 */
@Composable
fun HighRefreshKeeper(enabled: Boolean) {
    val view = LocalView.current
    SideEffect {
        if (Build.VERSION.SDK_INT >= 35) {
            view.requestedFrameRate =
                if (enabled) View.REQUESTED_FRAME_RATE_CATEGORY_HIGH else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }
    }
    if (!enabled) return

    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }
    }
    Box(
        Modifier
            .size(1.dp)
            .drawBehind {
                // Membaca [frame] membuat bagian ini digambar ulang tiap frame; warnanya nyaris tak terlihat.
                val a = 0.004f + (frame % 2L) * 0.002f
                drawRect(Color.White.copy(alpha = a))
            },
    )
}
