package app.tunes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Dulu dipakai bagian layar pemutar yang tidak bisa di-scroll supaya ikut menarik sheet. Sekarang seluruh
 * layar pemutar sudah bisa ditarik ([PlayerSheetHost]), jadi modifier ini tidak perlu berbuat apa-apa.
 * Dipertahankan hanya supaya pemanggil di NowPlayingScreen tidak perlu diubah.
 */
@Composable
fun Modifier.sheetDraggable(): Modifier = this

/** Kecepatan lempar (px/detik) yang dianggap sengaja membuka / menutup, apa pun posisinya. */
private const val FLING_PX = 900f

/** Pemutar sudah terlihat jelas (tapi transparan) begitu mulai ditarik; makin ditarik makin pekat. */
private const val ALPHA_MIN = 0.35f

/**
 * Status layar pemutar: [progress] 0 = tertutup, 1 = terbuka penuh. Ditarik dari pemutar mini (naik) atau dari
 * layar pemutar sendiri (turun); selama ditarik, layar pemutar naik dari tepi bawah layar dan memudar masuk
 * (transparan lalu makin jelas) mengikuti progres.
 */
@Stable
class PlayerSheetState(private val scope: CoroutineScope) {
    val progress = Animatable(0f)

    /** Jarak tarik (px) dari tertutup sampai terbuka penuh = tinggi layar, jadi layar pemutar bergerak 1:1 dengan jari. */
    var rangePx: Float = 1f

    /** Dipanggil saat gestur memutuskan terbuka (true) atau tertutup (false). */
    var onSetOpen: (Boolean) -> Unit = {}

    /** [dy] > 0 = jari turun (menutup), < 0 = jari naik (membuka). */
    fun dragBy(dy: Float) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progress.snapTo((progress.value - dy / rangePx).coerceIn(0f, 1f))
        }
    }

    /** Jari dilepas dengan kecepatan [velocityY] (px/detik, positif = ke bawah): selesaikan ke terbuka / tertutup. */
    fun release(velocityY: Float) {
        val open = when {
            velocityY <= -FLING_PX -> true
            velocityY >= FLING_PX -> false
            else -> progress.value >= 0.5f
        }
        onSetOpen(open)
        animateTo(open)
    }

    fun animateTo(open: Boolean) {
        scope.launch {
            progress.animateTo(
                if (open) 1f else 0f,
                tween(if (open) 320 else 260, easing = FastOutSlowInEasing),
            )
        }
    }
}

/** Layar pemutar yang bisa ditarik dari pemutar mini; null di luar akar aplikasi. */
val LocalPlayerSheet = compositionLocalOf<PlayerSheetState?> { null }

/**
 * Tarik ke atas pada pemutar mini (dalam bentuk apa pun) untuk membuka layar pemutar, mengikuti jari.
 * Geser ke samping tetap dipakai untuk ganti lagu.
 */
@Composable
fun Modifier.dragToOpenPlayer(): Modifier {
    val sheet = LocalPlayerSheet.current ?: return this
    val state = rememberDraggableState { dy -> sheet.dragBy(dy) }
    return draggable(
        state = state,
        orientation = Orientation.Vertical,
        onDragStopped = { velocity -> sheet.release(velocity) },
    )
}

/** Setelah sekian persen tarikan, bilah (pemutar mini & tab) mulai memudar; hilang total di [FOLLOW_GONE]. */
private const val FOLLOW_FADE_START = 0.08f
private const val FOLLOW_GONE = 0.22f

/**
 * Bilah yang ikut bergerak saat layar pemutar ditarik: [direction] -1 = naik mengikuti jari (pemutar mini),
 * +1 = turun (bilah tab). Jaraknya sama dengan jari, lalu memudar dan hilang setelah beberapa persen tarikan;
 * saat pemutar ditutup, geraknya berbalik. Pasang SEBELUM latar bilah supaya latarnya ikut bergerak, dan
 * jangan pasang di node yang menangkap gestur tarik (pembungkusnya harus diam).
 */
@Composable
fun Modifier.followPlayerSheet(direction: Float): Modifier {
    val sheet = LocalPlayerSheet.current ?: return this
    return graphicsLayer {
        val p = sheet.progress.value
        translationY = direction * p * sheet.rangePx
        alpha = 1f - ((p - FOLLOW_FADE_START) / (FOLLOW_GONE - FOLLOW_FADE_START)).coerceIn(0f, 1f)
    }
}

/**
 * Wadah layar pemutar penuh. Tampil selama progres > 0: memudar masuk dan naik tipis, lapisan gelap di
 * belakangnya menebal. Bisa ditarik ke bawah dari mana saja (lewat gestur langsung dan lewat scroll
 * bersarang dengan daftar di dalamnya) untuk menutup, dengan efek yang sama berbalik.
 */
@Composable
fun PlayerSheetHost(
    sheet: PlayerSheetState,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Didaftarkan sebelum isi, jadi Kembali di dalam layar pemutar (mis. tutup lirik) didahulukan.
    BackHandler(onBack = onDismiss)

    val connection = remember(sheet) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Sedang tertarik sebagian: gerak naik dipakai dulu untuk mengembalikan layar ke penuh.
                if (available.y < 0f && sheet.progress.value < 1f) {
                    sheet.dragBy(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Daftar sudah di ujung atas dan jari masih turun: sisanya menarik layar pemutar.
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    sheet.dragBy(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (sheet.progress.value < 1f) {
                    sheet.release(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    val dragState = rememberDraggableState { dy -> sheet.dragBy(dy) }

    Box(Modifier.fillMaxSize()) {
        // Lapisan gelap di belakang: menebal seiring layar pemutar muncul.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = sheet.progress.value * 0.4f }
                .background(Color.Black),
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = sheet.progress.value
                    // Mulai dari tepi bawah layar perangkat, naik mengikuti jari.
                    translationY = (1f - p) * size.height
                    // Transparan dulu (tapi jelas), makin naik makin pekat sampai biasa.
                    alpha = ((p * 10f).coerceAtMost(1f)) * (ALPHA_MIN + (1f - ALPHA_MIN) * p)
                    // Sudut atas membulat saat belum penuh, lalu rata dengan layar saat sudah terbuka.
                    shape = RoundedCornerShape(topStart = (28.dp * (1f - p)).toPx(), topEnd = (28.dp * (1f - p)).toPx())
                    clip = true
                }
                // Sentuhan tidak tembus ke halaman di belakang.
                .pointerInput(Unit) { detectTapGestures { } }
                .nestedScroll(connection)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> sheet.release(velocity) },
                ),
        ) {
            content()
        }
    }
}
