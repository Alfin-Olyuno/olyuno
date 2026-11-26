package app.tunes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** True kalau halaman ini yang sedang paling atas dan aktif (bukan tertutup halaman lain). */
val LocalPageActive = compositionLocalOf { true }

/**
 * True hanya saat halaman penuh pertama baru saja dibuka dari tab dasar (bilah tab ikut hilang). Pemutar mini
 * di halaman itu memakainya untuk meluncur turun dari posisinya di atas bilah tab ke posisinya di halaman.
 */
val LocalMiniFromTabs = compositionLocalOf { false }

/**
 * BackHandler untuk isi halaman penuh: hanya aktif kalau halamannya sedang paling atas. Halaman yang
 * tertutup halaman lain tidak menangkap tombol Kembali, jadi menekan Kembali dua kali dengan cepat
 * benar-benar mundur dua halaman.
 */
@Composable
fun PageBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = enabled && LocalPageActive.current, onBack = onBack)
}

/** Menahan semua sentuhan (dikonsumsi sebelum sampai ke isi), dipakai saat halaman tertutup halaman lain. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        }
    }
}

private val NoCover = mutableStateOf(0f)

/** Tidak ada lagi efek tertutup (geser / redup): halaman membuka dan menutup langsung, tanpa animasi. */
@Composable
fun rememberCoverProgress(covered: Boolean): State<Float> = NoCover

/** Dipertahankan agar pemanggil lama tetap jalan; tanpa animasi jadi tidak berbuat apa-apa. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.pageCover(progress: () -> Float): Modifier = this

/**
 * Halaman penuh bertumpuk TANPA animasi: halaman muncul langsung saat dibuka dan hilang langsung saat
 * ditutup. Halaman yang sedang tertutup halaman lain tetap tersusun (posisi scroll & isiannya terjaga),
 * tapi tidak menerima sentuhan dan tidak menangkap tombol Kembali.
 *
 * @param visible halaman sedang dibuka.
 * @param covered ada halaman lain yang dibuka di atas halaman ini.
 */
@Composable
fun PageTransition(visible: Boolean, covered: Boolean = false, content: @Composable () -> Unit) {
    if (!visible) return
    val active = !covered
    CompositionLocalProvider(LocalPageActive provides active) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (active) Modifier else Modifier.blockTouches()),
        ) { content() }
    }
}

/**
 * Pergantian tab bawah: hanya satu tab yang tersusun (tab lama langsung dibuang, tidak ada dua layar berat
 * sekaligus), tab baru memudar masuk dengan geseran tipis. Gerakan lewat `graphicsLayer` saja (tanpa
 * rekomposisi) dan baru mulai setelah frame pertama tab itu selesai disusun, sama seperti [PageTransition].
 */
@Composable
fun TabEnter(tab: Int, content: @Composable () -> Unit) {
    // Animasi smooth mati: tab langsung berganti, tanpa memudar / geser.
    val smooth = LocalSmoothAnim.current
    if (!smooth) {
        androidx.compose.runtime.key(tab) {
            Box(Modifier.fillMaxSize()) { content() }
        }
        return
    }
    androidx.compose.runtime.key(tab) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            progress.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = progress.value
                    translationY = (1f - progress.value) * 10f * density
                },
        ) { content() }
    }
}
