package app.tunes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Blur di ATAS LAYAR (area status bar / bar judul), dipakai semua halaman.
 *
 * Blur ini buatan sendiri, bukan RenderEffect / blur bawaan sistem, jadi sama di semua versi Android dan tidak
 * bergantung pada cache layer milik sistem. Itu penyebab bug "salinan basi": layer ber-RenderEffect yang disimpan
 * antar frame di beberapa HP tidak ikut diperbarui saat halaman digulir, sehingga isi lama menggantung di pita atas.
 *
 * Cara kerja ([topBlurSource]): isi halaman direkam sekali per frame ke satu layer. Di bawah pita, layer itu
 * digambar biasa. Di dalam pita, isi tajam dihapus oleh gradien, lalu diganti rata-rata beberapa salinan layer
 * yang sama yang digeser sedikit ke segala arah (blur gaussian kasar). Semuanya memakai saveLayer sesaat yang
 * dibuat dan dibuang di frame itu juga: tidak ada layer yang disimpan, jadi tidak ada yang bisa basi.
 *
 * Pita blur: paling kabur di atas, memudar mulus ke bawah, lalu dipucatkan tipis oleh warna latar tema
 * ([TopScreenBlur], hanya gradien warna, tanpa salinan isi).
 */

/** Saklar blur atas-layar. false = hanya pemucat warna latar. */
private const val TOP_BLUR_ENABLED = false

/** Jangkauan blur (jari-jari sebaran salinan) di bagian paling atas pita. */
private val TOP_BLUR_RADIUS = 9.dp

/** Sedikit warna aksen di pemucat (0 = murni warna latar). */
private const val TOP_TINT_MIX = 0.03f

/** Kepekatan pemucat di bagian paling atas, dengan blur / tanpa blur (saklar dimatikan). */
private const val TOP_TINT_ALPHA = 0.18f
private const val TOP_TINT_ALPHA_NO_BLUR = 0.30f

/** Panjang pudar di bawah area penuh (status bar / bar judul). */
private val TOP_FADE = 14.dp

/**
 * Ruang atas yang harus disisakan isi tab utama supaya tidak tertutup status bar waktu belum
 * digulir. Isi tab (Beranda, Anda, ...) memanjang sampai ke belakang status bar, jadi blur terlihat
 * saat digulir. 0 di luar tab utama.
 */
val LocalTabTopInset = compositionLocalOf { 0.dp }

/** True kalau blur atas-layar aktif. Blurnya buatan sendiri, jadi berlaku di semua versi Android. */
fun topBlurSupported(): Boolean = TOP_BLUR_ENABLED

/** Tinggi status bar. */
@Composable
fun statusBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** Satu salinan geser untuk blur: [dx], [dy] dalam piksel, [weight] = bobot rata-rata (total semua = 1). */
private class BlurTap(val dx: Float, val dy: Float, val weight: Float)

/** Tengah + 6 salinan di lingkaran dalam + 6 di lingkaran luar (diputar 30°): pendekatan gaussian dengan 13 salinan. */
private fun buildBlurTaps(radiusPx: Float): List<BlurTap> {
    val taps = ArrayList<BlurTap>(13)
    taps += BlurTap(0f, 0f, 0.25f)
    for (i in 0 until 6) {
        val a = i * PI / 3.0
        taps += BlurTap((cos(a) * radiusPx * 0.5).toFloat(), (sin(a) * radiusPx * 0.5).toFloat(), 0.5f / 6f)
    }
    for (i in 0 until 6) {
        val a = i * PI / 3.0 + PI / 6.0
        taps += BlurTap((cos(a) * radiusPx).toFloat(), (sin(a) * radiusPx).toFloat(), 0.25f / 6f)
    }
    return taps
}

/**
 * Pasang di container isi halaman yang menjangkau tepi ATAS layar (ujung atas container = ujung atas layar).
 * [solid] = tinggi bagian atas yang diburamkan penuh; di bawahnya blur memudar sepanjang [fade].
 *
 * Sengaja TIDAK memegang warna apa pun (latar, tema, tint): bagian atas isi yang tajam dihapus lewat gradien,
 * lalu salinan buramnya digambar di atas latar halaman yang asli. Jadi saat tema / warna tampilan diganti,
 * pita atas ikut berubah seketika karena warnanya selalu dari latar halaman yang sedang tampil, bukan salinan lama.
 */
@Composable
fun Modifier.topBlurSource(solid: Dp, fade: Dp = TOP_FADE): Modifier {
    if (!topBlurSupported()) return this
    val density = LocalDensity.current
    val blurPx = with(density) { TOP_BLUR_RADIUS.toPx() }
    val solidPx = with(density) { solid.coerceAtLeast(0.dp).toPx() }
    val fadePx = with(density) { fade.toPx() }
    val contentLayer = rememberGraphicsLayer()
    val taps = remember(blurPx) { buildBlurTaps(blurPx) }
    val plain = remember { Paint() }
    val tapPaint = remember { Paint().apply { blendMode = BlendMode.Plus } }
    return this.drawWithContent {
        // 1) Isi halaman direkam SEKALI per frame.
        contentLayer.record { this@drawWithContent.drawContent() }

        val h = (solidPx + fadePx).coerceAtMost(size.height)
        if (h <= 0f) {
            drawLayer(contentLayer)
            return@drawWithContent
        }
        val solidFraction = (solidPx / h).coerceIn(0f, 1f)
        val bandSize = Size(size.width, h)
        val bounds = Rect(0f, 0f, size.width, h)
        val mask = Brush.verticalGradient(
            0f to Color.Black,
            solidFraction to Color.Black,
            1f to Color.Transparent,
            startY = 0f,
            endY = h,
        )

        // 2) Di bawah pita: isi tajam seperti biasa.
        clipRect(top = h) { drawLayer(contentLayer) }

        drawIntoCanvas { canvas ->
            // 3a) Di dalam pita: isi tajam dihapus makin ke atas (DstOut).
            canvas.saveLayer(bounds, plain)
            drawLayer(contentLayer)
            drawRect(brush = mask, size = bandSize, blendMode = BlendMode.DstOut)
            canvas.restore()

            // 3b) Salinan buram: rata-rata salinan layer yang sama (tiap salinan ditambah dengan bobotnya, BlendMode.Plus),
            // lalu hanya yang di dalam mask yang tampil (DstIn). Semua layer sesaat, dibuang di akhir frame ini.
            canvas.saveLayer(bounds, plain)
            for (tap in taps) {
                tapPaint.alpha = tap.weight
                canvas.saveLayer(bounds, tapPaint)
                translate(tap.dx, tap.dy) { drawLayer(contentLayer) }
                canvas.restore()
            }
            drawRect(brush = mask, size = bandSize, blendMode = BlendMode.DstIn)
            canvas.restore()
        }
    }
}

/** Pemucat atas-layar setinggi status bar + pudar. [barBelow] = tinggi tambahan yang ikut ditutup penuh di bawah status bar. */
@Composable
fun TopScreenBlur(modifier: Modifier = Modifier, barBelow: Dp = 0.dp) {
    if (!TOP_BLUR_ENABLED) return // blur / pemucat atas-layar dimatikan
    val solid = (statusBarHeight() + barBelow - 2.dp).coerceAtLeast(0.dp)
    TopWash(solid = solid, height = solid + TOP_FADE, modifier = modifier)
}

/** Varian untuk halaman dengan bar judul yang tinggi totalnya sudah diketahui ([barHeight] termasuk status bar). */
@Composable
fun TopScreenBlurForBar(barHeight: Dp, modifier: Modifier = Modifier) {
    if (!TOP_BLUR_ENABLED) return // blur / pemucat atas-layar dimatikan
    val solid = (barHeight - 10.dp).coerceAtLeast(0.dp)
    TopWash(solid = solid, height = solid + TOP_FADE + 10.dp, modifier = modifier)
}

/** Pemucat warna latar yang pekat di atas dan hilang ke bawah. Hanya gradien warna: tidak menyalin isi apa pun. */
@Composable
private fun TopWash(solid: Dp, height: Dp, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tint = remember(scheme.background, scheme.primary) { lerp(scheme.background, scheme.primary, TOP_TINT_MIX) }
    val washAlpha = if (topBlurSupported()) TOP_TINT_ALPHA else TOP_TINT_ALPHA_NO_BLUR
    val solidFraction = if (height.value > 0f) (solid.value / height.value).coerceIn(0f, 1f) else 0f

    Box(modifier.fillMaxWidth().height(height)) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val rest = 1f - solidFraction
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to tint.copy(alpha = washAlpha),
                            solidFraction to tint.copy(alpha = washAlpha * 0.8f),
                            solidFraction + rest * 0.4f to tint.copy(alpha = washAlpha * 0.4f),
                            solidFraction + rest * 0.75f to tint.copy(alpha = washAlpha * 0.12f),
                            1f to Color.Transparent,
                        ),
                    )
                },
        )
    }
}
