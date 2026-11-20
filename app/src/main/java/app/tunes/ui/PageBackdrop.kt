package app.tunes.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Warna semburat di bagian atas layar (mode "Ikuti sistem"). [Color.Unspecified] = latar polos satu warna.
 * Diisi oleh OlyunoTheme dari warna wallpaper (Material You).
 */
val LocalTopTint = staticCompositionLocalOf { Color.Unspecified }

/** Tinggi semburat warna atas, sebagai bagian dari tinggi layar (setengah layar). */
private const val TINT_FRACTION = 0.5f

/**
 * Latar halaman: warna latar tema, dan bila ada [LocalTopTint], setengah bagian atas layar memudar halus dari
 * warna semburat itu ke warna latar (putih di mode terang) ke bawah.
 */
@Composable
fun Modifier.pageBackdrop(): Modifier {
    val bg = MaterialTheme.colorScheme.background
    val tint = LocalTopTint.current
    return drawBehind { drawPageBackdrop(bg, tint, size) }
}

/** Menggambar latar halaman (warna latar + semburat atas) di area [area]; dipakai juga sebagai alas blur kaca. */
fun DrawScope.drawPageBackdrop(bg: Color, tint: Color, area: Size) {
    drawRect(bg, size = area)
    if (tint != Color.Unspecified) {
        val h = area.height * TINT_FRACTION
        // Beberapa titik henti dengan kurva halus (bukan linear) supaya tidak ada garis tepi / banding.
        val brush = Brush.verticalGradient(
            0.00f to tint,
            0.25f to tint.copy(alpha = tint.alpha * 0.72f),
            0.50f to tint.copy(alpha = tint.alpha * 0.38f),
            0.75f to tint.copy(alpha = tint.alpha * 0.12f),
            1.00f to tint.copy(alpha = 0f),
            startY = 0f,
            endY = h,
        )
        drawRect(brush, size = Size(area.width, h))
    }
}

/** Pengganti `Surface(color = background)` untuk halaman penuh: latarnya berupa [pageBackdrop]. */
@Composable
fun PageSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxSize().pageBackdrop(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        content = content,
    )
}
