package app.tunes.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Warna turunan dari cover lagu.
 * [top] dan [bottom] membentuk gradien latar sheet (gelap, jenuh), [accent] terang untuk tombol/slider.
 */
@Immutable
data class SongPalette(
    val top: Color,
    val bottom: Color,
    val accent: Color,
    /** Warna tambahan dari rona lain di cover (dipakai latar tanpa thumbnail); bawaannya sama dengan [top]. */
    val extra1: Color = top,
    val extra2: Color = top,
    val extra3: Color = top,
) {
    companion object {
        val Default = SongPalette(
            top = Color(0xFF2E2A3D),
            bottom = Color(0xFF121014),
            accent = Color(0xFFC9BFFF),
        )
    }
}

object PaletteExtractor {
    private val cache = LruCache<String, SongPalette>(96)
    private val blurCache = LruCache<String, Bitmap>(24)

    fun peek(url: String?): SongPalette? = url?.let { cache.get(it) }

    suspend fun load(context: Context, url: String?): SongPalette? {
        if (url == null) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(96)
                .allowHardware(false) // piksel harus bisa dibaca CPU
                .build()
            val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                ?: return@withContext null
            val palette = runCatching { fromBitmap(drawable.toBitmap()) }.getOrNull()
                ?: return@withContext null
            cache.put(url, palette)
            palette
        }
    }

    /**
     * Sampul yang diburamkan oleh Olyuno sendiri (bukan blur sistem HP): dikecilkan ke 32x32 lalu di-box-blur
     * beberapa kali. Dipakai gaya Kaca Dynamic Island sebagai isi kaca; bitmap kecil ini diregangkan ke ukuran kartu.
     */
    suspend fun loadBlurred(context: Context, url: String?): Bitmap? {
        if (url == null) return null
        blurCache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(64)
                .allowHardware(false)
                .build()
            val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                ?: return@withContext null
            val small = runCatching { Bitmap.createScaledBitmap(drawable.toBitmap(), 32, 32, true) }.getOrNull()
                ?: return@withContext null
            val out = boxBlur(small, radius = 3, passes = 3)
            blurCache.put(url, out)
            out
        }
    }

    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            blurPass(a, b, w, h, radius, horizontal = true)
            blurPass(b, a, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blurPass(inp: IntArray, out: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val p = if (horizontal) inp[l * w + j] else inp[j * w + l]
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                    n++
                }
                val c = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                if (horizontal) out[l * w + i] = c else out[i * w + l] = c
            }
        }
    }

    /**
     * Cari rona dominan yang "hidup": piksel dikelompokkan per rona (24 keranjang), bobotnya
     * saturasi x kecerahan, jadi latar abu-abu/hitam/putih tidak mengalahkan warna sebenarnya cover.
     * Cover hampir monokrom menghasilkan palet abu-abu netral.
     */
    internal fun fromBitmap(src: Bitmap): SongPalette {
        val n = 40
        val bmp = Bitmap.createScaledBitmap(src, n, n, true)
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)

        val buckets = 24
        val weight = FloatArray(buckets)
        val sumHue = FloatArray(buckets)
        val sumSat = FloatArray(buckets)
        val hsv = FloatArray(3)
        var colorful = 0
        for (c in px) {
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.18f || hsv[2] < 0.18f) continue
            colorful++
            val b = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            val w = hsv[1] * (0.35f + hsv[2])
            weight[b] += w
            sumHue[b] += hsv[0] * w
            sumSat[b] += hsv[1] * w
        }

        if (colorful < px.size * 0.06f) return neutral()

        // Skor keranjang = bobot sendiri + setengah bobot tetangga (rona yang berdekatan dianggap satu keluarga).
        var best = 0
        var bestScore = -1f
        for (b in 0 until buckets) {
            val score = weight[b] + 0.5f * (weight[(b + 1) % buckets] + weight[(b + buckets - 1) % buckets])
            if (score > bestScore) {
                bestScore = score
                best = b
            }
        }
        val w = weight[best]
        if (w <= 0f) return neutral()
        val hue = sumHue[best] / w
        val sat = sumSat[best] / w

        // Rona lain yang cukup kuat dan tidak bertetangga dengan rona utama -> warna tambahan latar.
        val extras = (0 until buckets)
            .filter { b ->
                val d = minOf(abs(b - best), buckets - abs(b - best))
                d >= 3 && weight[b] >= w * 0.18f
            }
            .sortedByDescending { weight[it] }
            .fold(mutableListOf<Int>()) { acc, b ->
                val far = acc.all { a -> minOf(abs(a - b), buckets - abs(a - b)) >= 3 }
                if (far && acc.size < 3) acc.add(b)
                acc
            }
            .map { b ->
                hsvColor(sumHue[b] / weight[b], (sumSat[b] / weight[b]).coerceIn(0.40f, 0.85f), 0.50f)
            }
        val top = hsvColor(hue, sat.coerceIn(0.40f, 0.85f), 0.46f)
        return SongPalette(
            top = top,
            bottom = hsvColor(hue, (sat * 0.9f).coerceIn(0.35f, 0.80f), 0.14f),
            accent = hsvColor(hue, (sat * 0.45f).coerceIn(0.15f, 0.50f), 0.97f),
            extra1 = extras.getOrElse(0) { hsvColor((hue + 40f) % 360f, 0.55f, 0.50f) },
            extra2 = extras.getOrElse(1) { hsvColor((hue + 320f) % 360f, 0.55f, 0.46f) },
            extra3 = extras.getOrElse(2) { hsvColor((hue + 180f) % 360f, 0.45f, 0.42f) },
        )
    }

    private fun neutral() = SongPalette(
        top = hsvColor(0f, 0f, 0.27f),
        bottom = hsvColor(0f, 0f, 0.09f),
        accent = hsvColor(0f, 0f, 0.95f),
    )

    private fun hsvColor(h: Float, s: Float, v: Float): Color =
        Color(android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 359.9f), s, v)))
}

/**
 * Palet untuk cover [url]. Berganti halus (animasi warna) saat lagu berubah, dan tetap memakai
 * palet sebelumnya selama palet baru dihitung, jadi tidak ada kedipan ke warna default.
 */
@Composable
fun rememberSongPalette(url: String?): SongPalette {
    val context = LocalContext.current.applicationContext
    var target by remember { mutableStateOf(PaletteExtractor.peek(url) ?: SongPalette.Default) }
    LaunchedEffect(url) {
        PaletteExtractor.load(context, url)?.let { target = it }
    }
    val spec = smoothSpec(tween<Color>(durationMillis = 900))
    val top by animateColorAsState(target.top, spec, label = "paletteTop")
    val bottom by animateColorAsState(target.bottom, spec, label = "paletteBottom")
    val accent by animateColorAsState(target.accent, spec, label = "paletteAccent")
    val extra1 by animateColorAsState(target.extra1, spec, label = "paletteExtra1")
    val extra2 by animateColorAsState(target.extra2, spec, label = "paletteExtra2")
    val extra3 by animateColorAsState(target.extra3, spec, label = "paletteExtra3")
    return SongPalette(top, bottom, accent, extra1, extra2, extra3)
}
