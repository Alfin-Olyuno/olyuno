package app.tunes.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import app.tunes.OlyunoApp
import app.tunes.data.ColorMode
import app.tunes.ui.theme.systemLightScheme

/*
 * Warna semua bottom sheet. Bawaannya gelap berteks putih (mengikuti layar pemutar). Kalau Warna tampilan =
 * "Ikuti sistem", semua sheet jadi putih berteks gelap: putihnya sedikit bersemburat warna aksen wallpaper
 * (sama seperti mode Putih), dan semua komponen di dalamnya (toggle, radio, menu) memakai skema terang.
 */

/** Warna teks / ikon / garis di dalam sheet: putih (sheet gelap) atau arang (sheet putih). */
val LocalSheetInk = compositionLocalOf { Color.White }

private val SheetInkOnLight = Color(0xFF1B1B1F)

/** Warna teks sheet saat ini; pakai ini (bukan Color.White) di dalam isi sheet. */
val sheetInk: Color @Composable get() = LocalSheetInk.current

/** true kalau sheet harus putih: Warna tampilan = Ikuti sistem. */
@Composable
fun sheetIsLight(): Boolean {
    val context = LocalContext.current
    val appearance = remember(context) { (context.applicationContext as OlyunoApp).container.appearance }
    val mode by appearance.mode.collectAsState()
    return mode == ColorMode.System
}

/** Warna latar sheet: putih bersemburat aksen (Ikuti sistem) atau permukaan gelap tema (lainnya). */
@Composable
fun sheetContainerColor(): Color =
    if (sheetIsLight()) glassContainer(lerp(Color.White, systemLightScheme().primary, 0.035f))
    else glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh)

/** Nilai contentColor untuk ModalBottomSheet. */
@Composable
fun sheetContentColor(): Color = if (sheetIsLight()) SheetInkOnLight else Color.White

/** Bungkus isi sheet: pada sheet putih, pakai skema terang + teks gelap untuk semua yang ada di dalamnya. */
@Composable
fun SheetThemed(content: @Composable () -> Unit) {
    if (sheetIsLight()) {
        MaterialTheme(
            colorScheme = systemLightScheme(),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
            CompositionLocalProvider(
                LocalSheetInk provides SheetInkOnLight,
                LocalContentColor provides SheetInkOnLight,
                content = content,
            )
        }
    } else {
        content()
    }
}
