package app.tunes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Satu percakapan Olyuno AI = satu lagu: lagunya, model terakhir, dan obrolan "Tanya lebih lanjut".
 * Hasil analisisnya sendiri ada di cache LyricsAnalyzer; membuka entri memakai cache itu (atau memanggil AI lagi bila hilang).
 */
data class BedahEntry(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val thumbnail: String?,
    val model: String,
    val updatedAt: Long,
    /** (dariPengguna, teks). */
    val chat: List<Pair<Boolean, String>>,
)

/** Riwayat Olyuno AI, disimpan sebagai satu berkas JSON di filesDir (tidak ikut cadangan karena allowBackup mati). */
class BedahHistoryStore(context: Context) {
    private val file = File(context.filesDir, "bedah_history.json")
    private val lock = Any()

    private val _entries = MutableStateFlow(load())
    /** Terbaru di atas. */
    val entries: StateFlow<List<BedahEntry>> = _entries.asStateFlow()

    fun get(videoId: String): BedahEntry? = _entries.value.firstOrNull { it.videoId == videoId }

    fun upsert(entry: BedahEntry) {
        synchronized(lock) {
            val list = (listOf(entry) + _entries.value.filter { it.videoId != entry.videoId }).take(MAX)
            _entries.value = list
            save(list)
        }
    }

    fun remove(videoId: String) {
        synchronized(lock) {
            val list = _entries.value.filter { it.videoId != videoId }
            _entries.value = list
            save(list)
        }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            runCatching { file.delete() }
        }
    }

    private fun load(): List<BedahEntry> = runCatching {
        if (!file.exists()) return@runCatching emptyList<BedahEntry>()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("videoId")
            if (id.isEmpty()) return@mapNotNull null
            val chat = o.optJSONArray("chat")?.let { c ->
                (0 until c.length()).mapNotNull { j ->
                    val m = c.optJSONObject(j) ?: return@mapNotNull null
                    m.optString("text").takeIf { it.isNotEmpty() }?.let { m.optBoolean("user") to it }
                }
            }.orEmpty()
            BedahEntry(
                videoId = id,
                title = o.optString("title"),
                artist = o.optString("artist"),
                durationSec = o.optInt("durationSec"),
                thumbnail = if (o.isNull("thumbnail")) null else o.optString("thumbnail").takeIf { it.isNotEmpty() },
                model = o.optString("model"),
                updatedAt = o.optLong("updatedAt"),
                chat = chat,
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<BedahEntry>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("videoId", e.videoId).put("title", e.title).put("artist", e.artist)
                        .put("durationSec", e.durationSec).put("thumbnail", e.thumbnail ?: JSONObject.NULL)
                        .put("model", e.model).put("updatedAt", e.updatedAt)
                        .put("chat", JSONArray(e.chat.map { JSONObject().put("user", it.first).put("text", it.second) })),
                )
            }
            file.writeText(arr.toString())
        }
    }

    private companion object { const val MAX = 50 }
}
