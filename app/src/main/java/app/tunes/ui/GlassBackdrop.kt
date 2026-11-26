package app.tunes.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Gaya tampilan "Kaca": bilah & kontrol (bilah navigasi, pemutar mini) putih tembus pandang pekat, tanpa blur.
 *
 * [enabled] = gaya Kaca dipilih. [backdrop] = latar yang bisa di-blur (isi halaman yang digambar di
 * belakang bilah); null kalau di tempat itu tidak ada isi di belakangnya, jadi hanya tembus pandang
 * tanpa blur. Gaya Biasa = enabled false, semua komponen tampil solid seperti semula.
 */
@Stable
class GlassConfig(val enabled: Boolean, val backdrop: GlassBackdrop?)

/**
 * Rekaman isi halaman (graphics layer) + koordinatnya, dipakai bilah untuk menggambar blur-nya.
 *
 * PENTING: posisi bilah terhadap isi halaman TIDAK disimpan sebagai angka. `onGloballyPositioned` tidak dipanggil
 * lagi kalau bilah bergerak lewat `graphicsLayer` (tarik layar pemutar, pemutar mini naik/turun, tombol muat
 * ulang), jadi angka yang disimpan jadi basi dan salinan blur tertinggal / bergeser (tampak seperti duplikat
 * isi halaman yang menyangkut). Karena itu yang disimpan adalah [LayoutCoordinates] (selalu hidup) dan selisih
 * posisinya dihitung ulang tiap kali menggambar lewat [offsetOf].
 */
@Stable
class GlassBackdrop(val layer: GraphicsLayer) {
    /** Koordinat isi halaman. Sengaja bukan state: dibaca saat menggambar supaya selalu yang terbaru. */
    var coordinates: LayoutCoordinates? = null

    private var version by mutableIntStateOf(0)

    internal fun update(c: LayoutCoordinates) {
        coordinates = c
        version++
    }

    /** Dibaca di fase gambar supaya kaca menggambar ulang saat tata letak isi halaman berubah. */
    internal fun observeLayout(): Int = version

    /** Posisi pojok kiri-atas [target] di ruang isi halaman, dihitung saat ini; null kalau belum bisa dihitung. */
    internal fun offsetOf(target: LayoutCoordinates?): Offset? {
        val src = coordinates ?: return null
        if (target == null || !src.isAttached || !target.isAttached) return null
        return src.localPositionOf(target, Offset.Zero)
    }
}

/** Koordinat sebuah komponen kaca (hidup) + penanda supaya digambar ulang saat tata letaknya berubah. */
@Stable
class GlassAnchor {
    var coordinates: LayoutCoordinates? = null
        private set

    private var version by mutableIntStateOf(0)

    internal fun update(c: LayoutCoordinates) {
        coordinates = c
        version++
    }

    internal fun observeLayout(): Int = version
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
    .onGloballyPositioned { backdrop.update(it) }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * Latar kaca TANPA blur: putih (tema terang) / permukaan gelap (tema gelap) yang tembus pandang pekat.
 * Isi di belakang tidak diburamkan dan hampir tidak terlihat (kepekatan [minAlpha], bawaan [GLASS_ALPHA] = 86%).
 * Parameter [config] dan [motion] dipertahankan demi kompatibilitas pemanggil.
 * Dipakai hanya kalau [GlassConfig.enabled]; selain itu pemanggil memakai warna solid biasa.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    tint: Color,
    config: GlassConfig = LocalGlass.current,
    minAlpha: Float = GLASS_ALPHA,
    @Suppress("UNUSED_PARAMETER") motion: (() -> Float)? = null,
): Modifier {
    if (!config.enabled) return this
    return this
        .clip(shape)
        .background(tint.copy(alpha = minAlpha))
}
