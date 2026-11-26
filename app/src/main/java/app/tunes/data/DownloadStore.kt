package app.tunes.data

import android.content.Context
import app.tunes.innertube.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Satu lagu di daftar unduhan. [done] false = masih antre / berjalan / gagal. */
data class DownloadEntry(
    val song: SongItem,
    val done: Boolean,
    val sizeBytes: Long,
    val addedAt: Long,
    /**
     * true = diunduh sendiri (tampil sebagai lagu di halaman Diunduh). false = hanya bagian dari satu
     * atau lebih paket playlist/album ([DownloadedCollection]) sehingga tidak ditampilkan terpisah.
     */
    val standalone: Boolean = true,
)

/**
 * Daftar unduhan (metadata saja; isi audionya ada di cache unduhan). Disimpan sebagai file JSON kecil
 * supaya skema database lagu tidak perlu diubah.
 */
class DownloadStore(context: Context) {
    private val file = File(context.filesDir, "downloads.json")

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadEntry>> = _items.asStateFlow()

    private fun load(): List<DownloadEntry> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                DownloadEntry(
                    song = SongItem(
                        videoId = o.getString("videoId"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        durationSec = o.optInt("durationSec"),
                        thumbnail = if (o.isNull("thumbnail")) null else o.optString("thumbnail").ifBlank { null },
                    ),
                    done = o.optBoolean("done"),
                    sizeBytes = o.optLong("sizeBytes"),
                    addedAt = o.optLong("addedAt"),
                    standalone = o.optBoolean("standalone", true),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save(list: List<DownloadEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("videoId", e.song.videoId)
                    .put("title", e.song.title)
                    .put("artist", e.song.artist)
                    .put("durationSec", e.song.durationSec)
                    .put("thumbnail", e.song.thumbnail ?: JSONObject.NULL)
                    .put("done", e.done)
                    .put("sizeBytes", e.sizeBytes)
                    .put("addedAt", e.addedAt)
                    .put("standalone", e.standalone),
            )
        }
        val text = arr.toString()
        val tmp = File(file.parentFile, "downloads.json.tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            // gagal menulis: daftar di memori tetap benar untuk sesi ini
        }
        _items.value = list
    }

    @Synchronized
    fun get(videoId: String): DownloadEntry? = _items.value.firstOrNull { it.song.videoId == videoId }

    /** Tambah / ganti entri; yang terbaru di depan. */
    @Synchronized
    fun put(entry: DownloadEntry) =
        save(listOf(entry) + _items.value.filterNot { it.song.videoId == entry.song.videoId })

    @Synchronized
    fun remove(videoId: String) = save(_items.value.filterNot { it.song.videoId == videoId })
}
