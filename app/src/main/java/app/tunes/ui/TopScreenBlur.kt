package app.tunes.ui

import android.os.Build
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

/**
 * Blur di ATAS LAYAR (area status bar / bar judul), dipakai semua halaman.
 *
 * KUNCI ANTI-BUG: blur digambar DI DALAM container isi halaman itu sendiri ([topBlurSource]), dalam satu
 * pass gambar yang sama. Isi direkam sekali ke satu layer; layer yang sama digambar normal, lalu sekali lagi
 * (diburamkan) di pita atas. Tidak ada komponen terpisah yang menebak posisi salinan terhadap isi aslinya,
 * jadi salinan tidak bisa basi / bergeser / menggandakan diri saat halaman digulir, bergeser, atau dibuka-tutup.
 *
 * Pita blur: paling kabur di atas, memudar mulus ke bawah (blur bertahap), lalu dipucatkan tipis oleh warna
 * latar tema ([TopScreenBlur], hanya gradien warna, tanpa salinan isi). Di bawah Android 12 tidak ada blur:
 * hanya pemucatan.
 */

/** Saklar blur atas-layar (Android 12+). false = hanya pemucat warna latar. */
private const val TOP_BLUR_ENABLED = true

/** Kekuatan blur di bagian paling atas pita. */
private val TOP_BLUR_RADIUS = 10.dp

/** Sedikit warna aksen di pemucat (0 = murni warna latar). */
private const val TOP_TINT_MIX = 0.03f

/** Kepekatan pemucat di bagian paling atas, dengan blur / tanpa blur (Android di bawah 12). */
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

/** True kalau blur atas-layar aktif dan perangkat bisa memblur isi (Android 12+). */
fun topBlurSupported(): Boolean = TOP_BLUR_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Tinggi status bar. */
@Composable
fun statusBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * Pasang di container isi halaman yang menjangkau tepi ATAS layar (ujung atas container = ujung atas layar).
 * [solid] = tinggi bagian atas yang diburamkan penuh; di bawahnya blur memudar sepanjang [fade].
 * Tanpa efek di bawah Android 12.
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
    val sharpBand = rememberGraphicsLayer()
    val blurBand = rememberGraphicsLayer()
    remember(sharpBand, blurBand, blurPx) {
        blurBand.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
        // Offscreen: gradien (DstOut / DstIn) hanya memengaruhi isi layer ini, bukan halaman di belakangnya.
        sharpBand.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
        blurBand.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
        sharpBand.clip = true
        blurBand.clip = true
    }
    return this.drawWithContent {
        // 1) Isi halaman direkam SEKALI.
        contentLayer.record { this@drawWithContent.drawContent() }

        val h = (solidPx + fadePx).coerceAtMost(size.height)
        if (h <= 0f) {
            drawLayer(contentLayer)
            return@drawWithContent
        }
        val solidFraction = (solidPx / h).coerceIn(0f, 1f)
        val mask = Brush.verticalGradient(
            0f to Color.Black,
            solidFraction to Color.Black,
            1f to Color.Transparent,
        )

        // 2) Di bawah pita: isi tajam seperti biasa.
        clipRect(top = h) { drawLayer(contentLayer) }

        // 3) Di dalam pita (layer yang SAMA, frame yang sama): isi tajam dihapus makin ke atas, diganti salinan buramnya.
        inset(0f, 0f, 0f, size.height - h) {
            sharpBand.record {
                drawLayer(contentLayer)
                drawRect(brush = mask, blendMode = BlendMode.DstOut)
            }
            drawLayer(sharpBand)
            blurBand.record {
                drawLayer(contentLayer)
                drawRect(brush = mask, blendMode = BlendMode.DstIn)
            }
            drawLayer(blurBand)
        }
    }
}

/** Pemucat atas-layar setinggi status bar + pudar. [barBelow] = tinggi tambahan yang ikut ditutup penuh di bawah status bar. */
@Composable
fun TopScreenBlur(modifier: Modifier = Modifier, barBelow: Dp = 0.dp) {
    val solid = (statusBarHeight() + barBelow - 2.dp).coerceAtLeast(0.dp)
    TopWash(solid = solid, height = solid + TOP_FADE, modifier = modifier)
}

/** Varian untuk halaman dengan bar judul yang tinggi totalnya sudah diketahui ([barHeight] termasuk status bar). */
@Composable
fun TopScreenBlurForBar(barHeight: Dp, modifier: Modifier = Modifier) {
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
