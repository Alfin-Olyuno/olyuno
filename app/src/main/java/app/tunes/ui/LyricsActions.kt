package app.tunes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import app.tunes.lyrics.LyricLine
import app.tunes.i18n.t

/**
 * Lirik jadi teks biasa. Kalau ada terjemahan, tiap baris jadi "Who are you [Siapa kamu]".
 * Baris kosong (jeda bait) dipertahankan, tapi tidak lebih dari satu berturut-turut.
 */
fun lyricsAsText(lines: List<LyricLine>, translations: List<String>?): String {
    val out = ArrayList<String>()
    lines.forEachIndexed { i, line ->
        val t = line.text.trim()
        if (t.isEmpty()) {
            if (out.isNotEmpty() && out.last().isNotEmpty()) out += ""
        } else {
            val tr = translations?.getOrNull(i)?.trim().orEmpty()
            out += if (tr.isNotEmpty() && !tr.equals(t, ignoreCase = true)) "$t [$tr]" else t
        }
    }
    return out.joinToString("\n").trim()
}

fun copyLyrics(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Lirik", text))
    // Android 13+ sudah menampilkan konfirmasi salin sendiri.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, t("Lirik disalin"), Toast.LENGTH_SHORT).show()
    }
}

fun shareLyrics(context: Context, title: String, artist: String, text: String) {
    val label = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" — ")
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, if (label.isEmpty()) text else "$label\n\n$text")
    }
    try {
        context.startActivity(Intent.createChooser(send, t("Bagikan lirik")))
    } catch (e: Exception) {
        Toast.makeText(context, t("Tidak ada aplikasi untuk berbagi"), Toast.LENGTH_SHORT).show()
    }
}
