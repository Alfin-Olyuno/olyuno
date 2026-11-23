package app.tunes.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import app.tunes.i18n.t
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/** Satu baris lagu / artis di kartu Rekap. [subtitle] = nama artis untuk lagu, null untuk artis. */
data class RekapEntry(val title: String, val subtitle: String?, val thumbnail: String?)

/** Isi kartu Rekap untuk satu periode. */
data class RekapData(
    val periodLabel: String,
    val minutes: Long,
    val songs: Int,
    val activeDays: Int,
    val longestStreak: Int,
    val topSongs: List<RekapEntry>,
    val topArtists: List<RekapEntry>,
)

private val GRAD_TOP = Color(0xFFC4492F)
private val GRAD_MID = Color(0xFF7A2A3D)
private val GRAD_BOTTOM = Color(0xFF2F2144)

private fun number(n: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)

/**
 * Kartu Rekap di atas halaman Statistik & Rekap: total menit, jumlah lagu, hari aktif, 3 lagu dan 3 artis teratas
 * (dengan sampul), streak terpanjang di periode itu, dan tombol Bagikan yang membuat satu gambar rapi.
 * Warnanya tetap (tidak mengikuti tema) supaya gambar yang dibagikan sama dengan yang terlihat.
 */
@Composable
fun RekapCard(data: RekapData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(GRAD_TOP, GRAD_MID, GRAD_BOTTOM)))
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Text(
            "${t("Rekap")} · ${data.periodLabel}",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(number(data.minutes), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold, lineHeight = 52.sp)
        Text(t("menit mendengarkan"), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RekapTile(t("Lagu"), number(data.songs.toLong()), Modifier.weight(1f))
            RekapTile(t("Hari aktif"), number(data.activeDays.toLong()), Modifier.weight(1f))
            RekapTile(t("Streak terpanjang"), t("%d hari", data.longestStreak), Modifier.weight(1f), fire = true)
        }

        if (data.topSongs.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(t("Lagu teratas"), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            data.topSongs.forEachIndexed { i, e ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.width(20.dp))
                    Artwork(url = e.thumbnail, modifier = Modifier.size(44.dp), shape = RoundedCornerShape(8.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(e.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.subtitle != null) {
                            Text(e.subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (data.topArtists.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(t("Artis teratas"), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data.topArtists.forEach { e ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(url = e.thumbnail, modifier = Modifier.size(64.dp), shape = CircleShape)
                        Spacer(Modifier.height(6.dp))
                        Text(e.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Isi sisa kolom bila artisnya kurang dari 3 supaya yang ada tetap rapi di kiri.
                repeat(3 - data.topArtists.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .clickable(enabled = !busy) {
                    busy = true
                    scope.launch {
                        val ok = shareRekapImage(context, data)
                        busy = false
                        if (!ok) Toast.makeText(context, t("Tidak ada aplikasi untuk berbagi"), Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Share, contentDescription = null, tint = Color(0xFF1C1B1A), modifier = Modifier.size(18.dp))
            Text(
                t("Bagikan rekap"),
                color = Color(0xFF1C1B1A),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun RekapTile(label: String, value: String, modifier: Modifier, fire: Boolean = false) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (fire) {
                Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = Color(0xFFFFB02E), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
            }
            Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ gambar untuk dibagikan

private const val W = 1080
private const val H = 1920
private const val PAD = 84f

/** Membuat gambar 1080x1920 dari [data], menyimpannya di cache, lalu membuka menu berbagi. false = gagal / tak ada aplikasi tujuan. */
private suspend fun shareRekapImage(context: Context, data: RekapData): Boolean {
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = renderRekap(context, data)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "olyuno-rekap.png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            out
        }.getOrNull()
    } ?: return false
    return runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Rekap Olyuno ${data.periodLabel}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, t("Bagikan rekap")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    }.getOrDefault(false)
}

private suspend fun loadBitmap(context: Context, url: String?, size: Int): Bitmap? {
    if (url == null) return null
    val request = ImageRequest.Builder(context).data(url).size(size).allowHardware(false).build()
    return (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
}

private fun paint(color: Int, size: Float, bold: Boolean = false, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    this.alpha = alpha
    textSize = size
    typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
}

private fun Canvas.textFit(text: String, x: Float, y: Float, p: Paint, maxWidth: Float, center: Boolean = false) {
    val s = TextUtils.ellipsize(text, android.text.TextPaint(p), maxWidth, TextUtils.TruncateAt.END).toString()
    val left = if (center) x - p.measureText(s) / 2f else x
    drawText(s, left, y, p)
}

private fun Canvas.drawCover(bmp: Bitmap?, rect: RectF, circle: Boolean, radius: Float) {
    val path = Path().apply {
        if (circle) addOval(rect, Path.Direction.CW) else addRoundRect(rect, radius, radius, Path.Direction.CW)
    }
    save()
    clipPath(path)
    if (bmp != null) {
        // center-crop ke persegi
        val side = minOf(bmp.width, bmp.height)
        val src = android.graphics.Rect((bmp.width - side) / 2, (bmp.height - side) / 2, (bmp.width + side) / 2, (bmp.height + side) / 2)
        drawBitmap(bmp, src, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    } else {
        drawRect(rect, Paint().apply { color = AColor.argb(60, 255, 255, 255) })
    }
    restore()
}

private suspend fun renderRekap(context: Context, data: RekapData): Bitmap {
    val songThumbs = data.topSongs.map { loadBitmap(context, it.thumbnail, 300) }
    val artistThumbs = data.topArtists.map { loadBitmap(context, it.thumbnail, 400) }

    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val white = AColor.WHITE

    // Latar: gradasi yang sama dengan kartu di layar + kilau di kanan atas.
    c.drawRect(
        0f, 0f, W.toFloat(), H.toFloat(),
        Paint().apply {
            shader = LinearGradient(0f, 0f, W * 0.35f, H.toFloat(), intArrayOf(0xFFC4492F.toInt(), 0xFF7A2A3D.toInt(), 0xFF2F2144.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        },
    )
    c.drawRect(
        0f, 0f, W.toFloat(), H.toFloat(),
        Paint().apply {
            shader = RadialGradient(W * 0.85f, H * 0.06f, W * 0.7f, AColor.argb(140, 255, 180, 120), AColor.TRANSPARENT, Shader.TileMode.CLAMP)
        },
    )

    val innerW = W - PAD * 2
    var y = 150f
    c.drawText("OLYUNO · ${t("Rekap").uppercase()}", PAD, y, paint(white, 34f, bold = true, alpha = 215))
    y += 56f
    c.textFit(data.periodLabel, PAD, y, paint(white, 40f, alpha = 200), innerW)

    // Menit besar
    y += 250f
    c.drawText(number(data.minutes), PAD, y, paint(white, 230f, bold = true))
    y += 64f
    c.drawText(t("menit mendengarkan"), PAD, y, paint(white, 46f, alpha = 230))

    // Tiga angka
    y += 70f
    val tileW = (innerW - 2 * 24f) / 3f
    val tileH = 170f
    val tiles = listOf(
        t("Lagu") to number(data.songs.toLong()),
        t("Hari aktif") to number(data.activeDays.toLong()),
        t("Streak terpanjang") to t("%d hari", data.longestStreak),
    )
    tiles.forEachIndexed { i, (label, value) ->
        val left = PAD + i * (tileW + 24f)
        c.drawRoundRect(RectF(left, y, left + tileW, y + tileH), 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(38, 255, 255, 255) })
        c.textFit(label, left + 28f, y + 56f, paint(white, 28f, alpha = 205), tileW - 56f)
        c.textFit(value, left + 28f, y + 126f, paint(white, 50f, bold = true), tileW - 56f)
    }
    y += tileH + 90f

    // Lagu teratas
    if (data.topSongs.isNotEmpty()) {
        c.drawText(t("Lagu teratas"), PAD, y, paint(white, 36f, alpha = 215))
        y += 36f
        val rowH = 150f
        data.topSongs.forEachIndexed { i, e ->
            val top = y + i * (rowH + 22f)
            c.drawText("${i + 1}", PAD, top + rowH / 2f + 18f, paint(white, 48f, bold = true, alpha = 170))
            val rect = RectF(PAD + 70f, top, PAD + 70f + rowH, top + rowH)
            c.drawCover(songThumbs.getOrNull(i), rect, circle = false, radius = 24f)
            val tx = rect.right + 32f
            val tw = W - PAD - tx
            c.textFit(e.title, tx, top + rowH / 2f - 4f, paint(white, 42f, bold = true), tw)
            e.subtitle?.let { c.textFit(it, tx, top + rowH / 2f + 46f, paint(white, 32f, alpha = 200), tw) }
        }
        y += data.topSongs.size * (rowH + 22f) + 56f
    }

    // Artis teratas
    if (data.topArtists.isNotEmpty()) {
        c.drawText(t("Artis teratas"), PAD, y, paint(white, 36f, alpha = 215))
        y += 40f
        val col = innerW / 3f
        val d = 200f
        data.topArtists.forEachIndexed { i, e ->
            val cx = PAD + col * i + col / 2f
            c.drawCover(artistThumbs.getOrNull(i), RectF(cx - d / 2, y, cx + d / 2, y + d), circle = true, radius = 0f)
            c.textFit(e.title, cx, y + d + 52f, paint(white, 34f, bold = true), col - 20f, center = true)
        }
    }

    // Penutup
    c.drawText("Olyuno", PAD, H - 110f, paint(white, 44f, bold = true))
    c.drawText(t("Pemutar musik Android"), PAD, H - 62f, paint(white, 30f, alpha = 190))
    return bmp
}
