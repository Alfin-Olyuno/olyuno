package app.tunes.data

import android.content.Context
import app.tunes.i18n.AppLanguage
import app.tunes.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** Satu bagian isi pesan informasi. */
sealed interface InfoBlock {
    data class Heading(val text: String) : InfoBlock
    data class Paragraph(val text: String) : InfoBlock
    /** Butir daftar; [label] ditebalkan, [text] menjelaskan. */
    data class Bullet(val label: String, val text: String) : InfoBlock
}

data class InfoMessage(
    val id: String,
    val title: String,
    /** Cuplikan satu baris di daftar. */
    val preview: String,
    val blocks: List<InfoBlock>,
    /** Terjemahan English (pengumuman dari server). Null = tidak ada, pakai teks Indonesia. */
    val titleEn: String? = null,
    val previewEn: String? = null,
    val blocksEn: List<InfoBlock>? = null,
) {
    private val en get() = I18n.effective == AppLanguage.English
    fun shownTitle(): String = if (en && !titleEn.isNullOrBlank()) titleEn else title
    fun shownPreview(): String = if (en && !previewEn.isNullOrBlank()) previewEn else preview
    fun shownBlocks(): List<InfoBlock> = if (en && !blocksEn.isNullOrEmpty()) blocksEn else blocks
}

/**
 * Daftar pesan informasi (terbaru paling atas). Untuk menambah pengumuman baru cukup tambahkan satu
 * [InfoMessage] dengan id baru di paling atas; pengguna akan melihat tanda angka sampai membukanya.
 */
val INFO_MESSAGES: List<InfoMessage> = listOf(
    InfoMessage(
        id = "welcome",
        title = "Selamat Datang di Olyuno!",
        preview = "Terima kasih sudah memakai Olyuno.",
        blocks = listOf(
            InfoBlock.Paragraph("Halo, Olyners!"),
            InfoBlock.Paragraph("Olyuno dibuat supaya mendengarkan musik terasa simpel dan nyaman."),
            InfoBlock.Heading("Fitur Olyuno"),
            InfoBlock.Bullet("Temukan", "Jelajahi musik baru."),
            InfoBlock.Bullet("Pencarian", "Cari lagu dan artis favoritmu."),
            InfoBlock.Bullet("Koleksi", "Atur lagu dan playlistmu."),
            InfoBlock.Bullet("Disukai", "Simpan lagu favorit."),
            InfoBlock.Bullet("Riwayat", "Lagu yang pernah kamu putar."),
            InfoBlock.Heading("Terus Berkembang"),
            InfoBlock.Paragraph("Olyuno akan terus diperbarui."),
            InfoBlock.Paragraph("Ada kendala atau saran? Kabari saya ya."),
            InfoBlock.Paragraph("Selamat menikmati musik!"),
        ),
    ),
)

private const val SUPABASE_URL = "https://wiglnpjlzawejqohqqlk.supabase.co"
private const val SUPABASE_ANON = "sb_publishable_0rPGQANJuinJJbHk73vNvw_0a-iM8wJ"

private fun parseBlocks(arr: JSONArray?): List<InfoBlock>? {
    if (arr == null) return null
    val out = ArrayList<InfoBlock>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        when (o.optString("type")) {
            "heading" -> out += InfoBlock.Heading(o.optString("text"))
            "paragraph" -> out += InfoBlock.Paragraph(o.optString("text"))
            "bullet" -> out += InfoBlock.Bullet(o.optString("label"), o.optString("text"))
        }
    }
    return out
}

private fun parseAnnouncements(json: String): List<InfoMessage> {
    val arr = JSONArray(json)
    val out = ArrayList<InfoMessage>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
        out += InfoMessage(
            id = id,
            title = o.optString("title"),
            preview = o.optString("preview"),
            blocks = parseBlocks(o.optJSONArray("blocks")).orEmpty(),
            titleEn = if (o.isNull("title_en")) null else o.optString("title_en"),
            previewEn = if (o.isNull("preview_en")) null else o.optString("preview_en"),
            blocksEn = parseBlocks(o.optJSONArray("blocks_en")),
        )
    }
    return out
}

/** Menyimpan id pesan informasi yang sudah dibuka (SharedPreferences). Pesan baru otomatis berstatus belum dibaca. */
class InfoStore(context: Context) {
    private val prefs = context.getSharedPreferences("info", Context.MODE_PRIVATE)

    private val _read = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val readIds: StateFlow<Set<String>> = _read.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun withRemote(remote: List<InfoMessage>): List<InfoMessage> = remote + INFO_MESSAGES

    // Pengumuman dari server (terbaru di atas) lalu pesan bawaan; cache terakhir dipakai saat offline.
    private val _messages = MutableStateFlow(
        withRemote(runCatching { parseAnnouncements(prefs.getString(CACHE_KEY, null) ?: "[]") }.getOrDefault(emptyList())),
    )
    val messages: StateFlow<List<InfoMessage>> = _messages.asStateFlow()

    // Id pengumuman server yang sudah diberi notifikasi (atau sudah terlihat di aplikasi). Belum ada = belum pernah diinisialisasi.
    private val notified: MutableSet<String> = prefs.getStringSet(NOTIFIED_KEY, null)?.toMutableSet() ?: mutableSetOf()
    private var notifiedReady = prefs.contains(NOTIFIED_KEY)

    /**
     * Pengumuman server yang belum pernah diberi notifikasi dan belum dibuka, lalu menandai semua pengumuman yang ada
     * sebagai sudah ditangani. Panggilan pertama sepanjang masa hanya menandai (tanpa notifikasi) supaya pengumuman
     * lama tidak membanjiri pengguna yang baru memperbarui aplikasi.
     */
    @Synchronized
    fun takeUnnotified(): List<InfoMessage> {
        val builtIn = INFO_MESSAGES.map { it.id }.toSet()
        val remote = _messages.value.filter { it.id !in builtIn }
        val fresh = if (!notifiedReady) emptyList() else remote.filter { it.id !in notified && it.id !in _read.value }
        notified += remote.map { it.id }
        notifiedReady = true
        prefs.edit().putStringSet(NOTIFIED_KEY, HashSet(notified)).apply()
        return fresh
    }

    /** Ambil pengumuman terbaru dari server. Gagal / offline = tetap pakai yang tersimpan. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        try {
            val url = "$SUPABASE_URL/rest/v1/announcements" +
                "?select=id,title,preview,blocks,title_en,preview_en,blocks_en,created_at" +
                "&published=eq.true&order=created_at.desc&limit=50"
            val req = Request.Builder().url(url).header("apikey", SUPABASE_ANON).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use
                val text = r.body?.string().orEmpty()
                val parsed = parseAnnouncements(text)
                prefs.edit().putString(CACHE_KEY, text).apply()
                _messages.value = withRemote(parsed)
            }
        } catch (e: Exception) {
            // offline atau server bermasalah: abaikan
        }
    }

    fun markRead(id: String) {
        val cur = _read.value
        if (id in cur) return
        val next = cur + id
        prefs.edit().putStringSet(KEY, next).apply()
        _read.value = next
    }

    private companion object {
        const val KEY = "read_ids"
        const val CACHE_KEY = "announcements_json"
        const val NOTIFIED_KEY = "notified_ids"
    }
}
