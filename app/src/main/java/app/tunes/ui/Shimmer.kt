package app.tunes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tunes.data.ShimmerLevel
import app.tunes.i18n.t

/**
 * Satu animasi yang dibagi ke semua kotak skeleton di dalamnya, supaya kilauannya
 * bergerak serempak (dan hemat: hanya satu InfiniteTransition untuk satu daftar).
 */
private val LocalShimmerProgress = compositionLocalOf<State<Float>?> { null }

@Composable
private fun rememberShimmerProgress(): State<Float> {
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
}

/** Pengaturan "Kilau memuat" (Tema): Mati = kotak skeleton diam tanpa kilau. */
val LocalShimmerLevel = compositionLocalOf { ShimmerLevel.Soft }

@Composable
fun ProvideShimmer(content: @Composable () -> Unit) {
    // Mati: tidak ada animasi sama sekali, jadi tidak perlu InfiniteTransition.
    if (LocalShimmerLevel.current == ShimmerLevel.Off) {
        content()
        return
    }
    val progress = rememberShimmerProgress()
    CompositionLocalProvider(LocalShimmerProgress provides progress, content = content)
}

/**
 * Isi latar dengan kilau yang menyapu dari kiri ke kanan.
 * [color] = warna dasar kilau (default: onSurface tema). Pakai [Color.White] di atas latar gelap.
 */
fun Modifier.shimmer(color: Color? = null): Modifier = composed {
    val tint = color ?: MaterialTheme.colorScheme.onSurface
    val level = LocalShimmerLevel.current
    if (level == ShimmerLevel.Off) {
        return@composed Modifier.drawBehind { drawRect(tint.copy(alpha = 0.14f)) }
    }
    val progress = LocalShimmerProgress.current ?: rememberShimmerProgress()
    val base = tint.copy(alpha = if (level == ShimmerLevel.Strong) 0.14f else 0.12f)
    val highlight = tint.copy(alpha = if (level == ShimmerLevel.Strong) 0.42f else 0.26f)
    drawWithCache {
        val band = size.width * 0.8f
        onDrawBehind {
            val x = -band + progress.value * (size.width + band)
            drawRect(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(x, 0f),
                    end = Offset(x + band, size.height * 0.25f),
                ),
            )
        }
    }
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    color: Color? = null,
) {
    Box(modifier.clip(shape).shimmer(color))
}

// ------------------------------------------------------------ daftar lagu

private val TitleWidths = listOf(0.62f, 0.48f, 0.74f, 0.56f, 0.68f, 0.42f)
private val SubWidths = listOf(0.38f, 0.3f, 0.45f, 0.34f, 0.4f, 0.28f)

/** Bentuknya meniru [SongRow] (sampul 52dp + dua baris teks) agar tidak ada loncatan layout. */
@Composable
fun SongRowSkeleton(index: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerBox(Modifier.size(52.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBox(
                Modifier.fillMaxWidth(TitleWidths[index % TitleWidths.size]).height(14.dp),
                RoundedCornerShape(7.dp),
            )
            ShimmerBox(
                Modifier.fillMaxWidth(SubWidths[index % SubWidths.size]).height(11.dp),
                RoundedCornerShape(6.dp),
            )
        }
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
fun SongListSkeleton(count: Int = 10, modifier: Modifier = Modifier) {
    ProvideShimmer {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .semantics { contentDescription = t("Memuat") },
            userScrollEnabled = false,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = LocalTopInset.current),
        ) {
            items(count) { SongRowSkeleton(it) }
        }
    }
}

// ------------------------------------------------------------------ lirik

private val LyricWidths = listOf(0.78f, 0.55f, 0.86f, 0.66f, 0.8f, 0.48f, 0.72f, 0.6f)

/** Baris-baris lirik palsu berwarna putih transparan, cocok di atas latar cover yang gelap. */
@Composable
fun LyricsSkeleton(modifier: Modifier = Modifier) {
    ProvideShimmer {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .semantics { contentDescription = t("Mencari lirik") },
            verticalArrangement = Arrangement.Center,
        ) {
            LyricWidths.forEachIndexed { i, w ->
                if (i > 0) Spacer(Modifier.height(20.dp))
                ShimmerBox(
                    modifier = Modifier.fillMaxWidth(w).height(22.dp),
                    shape = RoundedCornerShape(11.dp),
                    color = Color.White,
                )
            }
        }
    }
}
