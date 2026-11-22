package app.tunes.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Gaya tampilan "Kaca": bilah & kontrol (bilah navigasi, pemutar mini) transparan dengan blur.
 *
 * [enabled] = gaya Kaca dipilih. [backdrop] = latar yang bisa di-blur (isi halaman yang digambar di
 * belakang bilah); null kalau di tempat itu tidak ada isi di belakangnya, jadi hanya tembus pandang
 * tanpa blur. Gaya Biasa = enabled false, semua komponen tampil solid seperti semula.
 */
@Stable
class GlassConfig(val enabled: Boolean, val backdrop: GlassBackdrop?)

/** Rekaman isi halaman (graphics layer) + posisinya di jendela, dipakai bilah untuk menggambar blur-nya. */
@Stable
class GlassBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

/** Kejenuhan warna isi yang diblur di dalam kaca (1 = asli, 0 = abu-abu). Kecil = warna tidak menempel di kaca. */
const val GLASS_BACKDROP_SATURATION = 0.25f

val LocalGlass = compositionLocalOf { GlassConfig(enabled = false, backdrop = null) }

/** Pengaturan "Animasi smooth": false = bilah navigasi & pemutar mini berubah seketika, tanpa animasi. */
val LocalSmoothAnim = compositionLocalOf { true }

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Tandai isi halaman sebagai latar yang boleh di-blur oleh bilah di atasnya. */
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInWindow() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * Latar kaca: potongan isi halaman di belakang komponen ini di-blur, lalu dilapisi [tint] yang
 * tembus pandang. Tanpa latar (atau Android di bawah 12) hanya lapisan tembus pandang yang agak pekat.
 * Dipakai hanya kalau [GlassConfig.enabled]; selain itu pemanggil memakai warna solid biasa.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    tint: Color,
    config: GlassConfig = LocalGlass.current,
    minAlpha: Float = GLASS_ALPHA,
): Modifier {
    if (!config.enabled) return this
    val backdrop = config.backdrop
    val canBlur = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blurPx = with(LocalDensity.current) { 32.dp.toPx() }
    val blurLayer = rememberGraphicsLayer()
    remember(blurLayer, blurPx) {
        blurLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
        // Warna isi di belakang (sampul, kartu berwarna) dipucatkan sebelum masuk kaca, supaya tidak ada
        // bercak warna yang "menempel" di bilah / kartu kaca. Bentuk dan terang-gelapnya tetap terlihat.
        blurLayer.colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(GLASS_BACKDROP_SATURATION) })
    }
    val pageBg = MaterialTheme.colorScheme.background
    val pageTint = LocalTopTint.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    // Kaca pekat: lapisan selalu minimal GLASS_ALPHA supaya gambar di belakang tidak tampak (tidak ada bercak sampul).
    // Tanpa blur (Android < 12 / tanpa latar) tetap pekat supaya isi di belakang tidak terlihat tajam.
    val overlay = tint.copy(alpha = maxOf(tint.alpha, if (canBlur) minAlpha else GLASS_ALPHA))

    return this
        .clip(shape)
        .onGloballyPositioned { pos = it.positionInWindow() }
        .drawWithContent {
            if (canBlur && backdrop != null) {
                val dx = pos.x - backdrop.origin.x
                val dy = pos.y - backdrop.origin.y
                blurLayer.record {
                    // Alas solid: tanpa ini area kosong (layer transparan) dibur menjadi bercak warna sisa
                    // tekstur GPU. Alas = latar halaman yang sama dengan di belakang layer, jadi tampilan tetap.
                    drawRect(pageBg)
                    translate(-dx, -dy) {
                        val area = Size(backdrop.layer.size.width.toFloat(), backdrop.layer.size.height.toFloat())
                        if (pageTint != Color.Unspecified) drawPageBackdrop(pageBg, pageTint, area)
                        drawLayer(backdrop.layer)
                    }
                }
                drawLayer(blurLayer)
            }
            drawRect(overlay)
            drawContent()
        }
}
