package app.tunes.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration

/**
 * true kalau layar sedang lebar (landscape): lebar > tinggi. Dipakai untuk memilih tata letak dua kolom
 * dan membatasi lebar kontrol supaya tidak melebar sampai ujung layar.
 */
@Composable
fun isLandscape(): Boolean {
    val c = LocalConfiguration.current
    return c.screenWidthDp > c.screenHeightDp
}

/**
 * Ruang aman di sisi kiri/kanan layar: lekuk kamera (notch) dan bilah navigasi tiga tombol yang pindah ke sisi
 * saat landscape. Di portrait nilainya 0, jadi tampilan portrait tidak berubah.
 */
fun Modifier.safeHorizontalPadding(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
