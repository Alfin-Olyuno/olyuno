package app.tunes.data

import android.content.Context
import app.tunes.innertube.CollectionInfo
import app.tunes.innertube.CollectionKind
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Satu "paket" playlist / album yang diunduh. Semua metadata (judul, sampul, daftar lagu berurutan)
 * disimpan di sini supaya halamannya bisa dibuka tanpa internet. Isi audionya sendiri ada di cache
 * unduhan; status selesai tiap lagu dibaca dari [DownloadStore].
 */
data class DownloadedCollection(
    val ref: CollectionRef,
    val info: CollectionInfo,
    /** Urutan asli playlist. */
    val songs: List<SongItem>,
    val addedAt: Long,
)

/** Daftar paket playlist/album yang diunduh, disimpan sebagai JSON kecil (seperti [DownloadStore]). */
class DownloadedCollectionStore(context: Context) {
    private val file = File(context.filesDir, "download_collections.json")

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadedCollection>> = _items.asStateFlow()

    private fun songToJson(s: SongItem) = JSONObject()
        .put("videoId", s.videoId)
        .put("title", s.title)
        .put("artist", s.artist)
        .put("durationSec", s.durationSec)
        .put("thumbnail", s.thumbnail ?: JSONObject.NULL)

    private fun songFromJson(o: JSONObject) = SongItem(
        videoId = o.getString("videoId"),
        title = o.optString("title"),
        artist = o.optString("artist"),
        durationSec = o.optInt("durationSec"),
        thumbnail = if (o.isNull("thumbnail")) null else o.optString("thumbnail").ifBlank { null },
    )

    private fun JSONObject.str(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }

    private fun load(): List<DownloadedCollection> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val r = o.getJSONObject("ref")
                val inf = o.getJSONObject("info")
                val songs = o.getJSONArray("songs")
                DownloadedCollection(
                    ref = CollectionRef(
                        kind = CollectionKind.valueOf(r.getString("kind")),
                        title = r.optString("title"),
                        browseId = r.str("browseId"),
                        params = r.str("params"),
                        subtitle = r.str("subtitle"),
                        thumbnail = r.str("thumbnail"),
                        accent = if (r.has("accent") && !r.isNull("accent")) r.getLong("accent") else null,
                        query = r.str("query"),
                    ),
                    info = CollectionInfo(
                        description = inf.str("description"),
                        owner = inf.str("owner"),
                        thumbnail = inf.str("thumbnail"),
                    ),
                    songs = List(songs.length()) { j -> songFromJson(songs.getJSONObject(j)) },
                    addedAt = o.optLong("addedAt"),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save(list: List<DownloadedCollection>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put(
                        "ref",
                        JSONObject()
                            .put("kind", c.ref.kind.name)
                            .put("title", c.ref.title)
                            .put("browseId", c.ref.browseId ?: JSONObject.NULL)
                            .put("params", c.ref.params ?: JSONObject.NULL)
                            .put("subtitle", c.ref.subtitle ?: JSONObject.NULL)
                            .put("thumbnail", c.ref.thumbnail ?: JSONObject.NULL)
                            .put("accent", c.ref.accent ?: JSONObject.NULL)
                            .put("query", c.ref.query ?: JSONObject.NULL),
                    )
                    .put(
                        "info",
                        JSONObject()
                            .put("description", c.info.description ?: JSONObject.NULL)
                            .put("owner", c.info.owner ?: JSONObject.NULL)
                            .put("thumbnail", c.info.thumbnail ?: JSONObject.NULL),
                    )
                    .put("songs", JSONArray().also { a -> c.songs.forEach { a.put(songToJson(it)) } })
                    .put("addedAt", c.addedAt),
            )
        }
        val text = arr.toString()
        val tmp = File(file.parentFile, "download_collections.json.tmp")
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
    fun get(key: String): DownloadedCollection? = _items.value.firstOrNull { it.ref.key == key }

    /** Tambah / ganti paket; yang terbaru di depan. */
    @Synchronized
    fun put(c: DownloadedCollection) =
        save(listOf(c) + _items.value.filterNot { it.ref.key == c.ref.key })

    @Synchronized
    fun remove(key: String) = save(_items.value.filterNot { it.ref.key == key })
}
