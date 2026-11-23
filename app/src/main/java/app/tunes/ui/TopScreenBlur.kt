package app.tunes.ui

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

/**
 * Blur di ATAS LAYAR (area status bar), dipakai oleh semua halaman.
 *
 * Bukan pita warna rata: isi halaman yang menggulir ke belakang status bar tetap terlihat bentuk dan
 * warnanya, tapi dikaburkan bertahap (makin ke atas makin kabur: satu lapisan blur tipis di seluruh
 * pita + satu lapisan blur kuat di bagian atas) lalu "dipucatkan" oleh warna latar tema yang memudar
 * ke bawah. Karena mengikuti isi di belakangnya, tepinya tidak lurus rapi, melainkan tidak teratur
 * seperti kaca buram. Di bawah Android 12 tidak ada blur: hanya pemucatan warna latar.
 *
 * Gaya "nyaris mati": blur dan pemucat dibuat sangat tipis, jadi hampir tak terlihat tapi tetap aktif.
 * Mau ganti tampilannya? Cukup ubah konstanta di bawah.
 */

/** Sedikit warna aksen di pemucat (0 = murni warna latar). Dibuat kecil supaya warna isi tetap terlihat. */
private const val TOP_TINT_MIX = 0.03f

/** Kepekatan pemucat di bagian paling atas (dengan blur). */
private const val TOP_TINT_ALPHA = 0.10f

/** Kepekatan pemucat kalau blur tidak tersedia (Android di bawah 12), supaya isi di belakang tetap samar. */
private const val TOP_TINT_ALPHA_NO_BLUR = 0.30f

/** Blur tipis (seluruh pita) dan blur kuat (bagian atas pita). */
private val TOP_BLUR_LIGHT = 1.5.dp
private val TOP_BLUR_STRONG = 3.dp

/** Panjang pudar di bawah area status bar. */
private val TOP_FADE = 14.dp

/**
 * Ruang atas yang harus disisakan isi tab utama supaya tidak tertutup status bar waktu belum
 * digulir. Isi tab (Beranda, Anda, ...) memanjang sampai ke belakang status bar, jadi blur terlihat
 * saat digulir. 0 di luar tab utama.
 */
val LocalTabTopInset = compositionLocalOf { 0.dp }

/** True kalau perangkat bisa memblur isi (Android 12+); selain itu isi halaman tidak perlu direkam. */
fun topBlurSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Tinggi status bar. */
@Composable
fun statusBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * Blur atas-layar setinggi status bar + pudar. [barBelow] = tinggi tambahan yang ikut ditutup penuh
 * di bawah status bar (mis. bar judul halaman), supaya judul tetap terbaca di atas isi yang digulir.
 */
@Composable
fun TopScreenBlur(backdrop: GlassBackdrop?, modifier: Modifier = Modifier, barBelow: Dp = 0.dp) {
    val solid = (statusBarHeight() + barBelow - 2.dp).coerceAtLeast(0.dp)
    TopBlurBand(backdrop = backdrop, solid = solid, height = solid + TOP_FADE, modifier = modifier)
}

/** Varian untuk halaman dengan bar judul yang tinggi totalnya sudah diketahui ([barHeight] termasuk status bar). */
@Composable
fun TopScreenBlurForBar(backdrop: GlassBackdrop?, barHeight: Dp, modifier: Modifier = Modifier) {
    val solid = (barHeight - 10.dp).coerceAtLeast(0.dp)
    TopBlurBand(backdrop = backdrop, solid = solid, height = solid + TOP_FADE + 10.dp, modifier = modifier)
}

@Composable
private fun TopBlurBand(backdrop: GlassBackdrop?, solid: Dp, height: Dp, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tint = remember(scheme.background, scheme.primary) { lerp(scheme.background, scheme.primary, TOP_TINT_MIX) }
    val canBlur = backdrop != null && topBlurSupported()
    val washAlpha = if (canBlur) TOP_TINT_ALPHA else TOP_TINT_ALPHA_NO_BLUR
    val solidFraction = if (height.value > 0f) (solid.value / height.value).coerceIn(0f, 1f) else 0f

    Box(modifier.fillMaxWidth().height(height)) {
        if (canBlur && backdrop != null) {
            // Blur tipis: seluruh pita, memudar ke bawah.
            BlurLayer(backdrop, TOP_BLUR_LIGHT, solidFraction, endFraction = 1f)
            // Blur kuat: hanya bagian atas, memudar lebih cepat -> kaburnya bertahap.
            BlurLayer(backdrop, TOP_BLUR_STRONG, solidFraction * 0.5f, endFraction = 0.6f)
        }
        // Pemucat warna latar: pekat di atas, hilang ke bawah. Isi di belakang tetap tampak lewat blur.
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

/** Salinan isi halaman yang diburamkan, hanya terlihat sampai [endFraction] tinggi pita (penuh sampai [solidFraction]). */
@Composable
private fun BlurLayer(backdrop: GlassBackdrop, radius: Dp, solidFraction: Float, endFraction: Float) {
    val blurPx = with(LocalDensity.current) { radius.toPx() }
    val pageBg = MaterialTheme.colorScheme.background
    val pageTint = LocalTopTint.current
    val blurLayer = rememberGraphicsLayer()
    remember(blurLayer, blurPx) {
        blurLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
    }
    val anchor = remember { GlassAnchor() }
    val solidAt = solidFraction.coerceIn(0f, endFraction)

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { anchor.update(it) }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                backdrop.observeLayout()
                anchor.observeLayout()
                // Posisi pita terhadap isi halaman dihitung saat ini (bukan angka tersimpan yang bisa basi);
                // belum bisa dihitung = jangan gambar apa pun, daripada menggambar salinan yang bergeser.
                val at = backdrop.offsetOf(anchor.coordinates) ?: return@drawWithContent
                blurLayer.record {
                    // Alas solid supaya area kosong tidak dibur menjadi bercak warna (lihat Modifier.glass).
                    drawRect(pageBg)
                    translate(-at.x, -at.y) {
                        val area = androidx.compose.ui.geometry.Size(
                            backdrop.layer.size.width.toFloat(), backdrop.layer.size.height.toFloat(),
                        )
                        if (pageTint != Color.Unspecified) drawPageBackdrop(pageBg, pageTint, area)
                        drawLayer(backdrop.layer)
                    }
                }
                drawLayer(blurLayer)
                val rest = endFraction - solidAt
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        solidAt to Color.Black,
                        solidAt + rest * 0.5f to Color.Black.copy(alpha = 0.55f),
                        endFraction to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}
