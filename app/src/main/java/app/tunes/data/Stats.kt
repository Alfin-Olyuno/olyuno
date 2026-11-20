package app.tunes.data

import app.tunes.innertube.CollectionKind
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.SongItem

/** Lagu beserta berapa kali diputar pada periode yang dipilih. */
data class StatSong(val song: SongItem, val count: Int)

/** Artis beserta berapa kali lagunya diputar; [thumbnail] = sampul lagu terbarunya. */
data class StatArtist(val name: String, val thumbnail: String?, val count: Int)

/** Album atau playlist yang lagunya diputar dari halamannya; [count] = jumlah lagu yang diputar dari sana. */
data class StatCollection(val ref: CollectionRef, val thumbnail: String?, val count: Int)

/** Ringkasan satu periode untuk halaman Statistik. */
data class Stats(
    val total: Int,
    val songs: List<StatSong>,
    val artists: List<StatArtist>,
    val albums: List<StatCollection>,
    val playlists: List<StatCollection>,
)

/** Pemisah beberapa artis dalam satu lagu ("A, B" atau "A & B"). */
private val ARTIST_SPLIT = Regex("\\s*(?:,|&)\\s*")

/**
 * Hitung statistik dari log pemutaran ([PlayRow], terbaru di depan). Satu putaran dihitung sekali untuk lagunya
 * dan sekali untuk tiap artisnya; album / playlist hanya dihitung bila lagu diputar dari halaman koleksinya.
 * Tiap daftar diurutkan dari yang paling sering diputar (seri: yang terbaru dulu) dan dipotong sampai [limit].
 */
fun List<PlayRow>.toStats(limit: Int = 10): Stats {
    val songCount = HashMap<String, Int>()
    val songRow = HashMap<String, PlayRow>()
    val artistCount = HashMap<String, Int>()
    val artistThumb = HashMap<String, String?>()
    val artistLast = HashMap<String, Long>()
    val colCount = HashMap<String, Int>()
    val colRow = HashMap<String, PlayRow>()

    for (r in this) {
        songCount[r.videoId] = (songCount[r.videoId] ?: 0) + 1
        if (!songRow.containsKey(r.videoId)) songRow[r.videoId] = r

        r.artist.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { a ->
            artistCount[a] = (artistCount[a] ?: 0) + 1
            if (!artistThumb.containsKey(a)) {
                artistThumb[a] = r.thumbnail
                artistLast[a] = r.playedAt
            }
        }

        val kind = r.ctxKind
        if (kind != null && !r.ctxTitle.isNullOrBlank()) {
            val key = "$kind|${r.ctxBrowseId}|${r.ctxParams}|${r.ctxTitle}"
            colCount[key] = (colCount[key] ?: 0) + 1
            if (!colRow.containsKey(key)) colRow[key] = r
        }
    }

    val songs = songCount.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { songRow.getValue(it.key).playedAt },
        )
        .take(limit)
        .map { e ->
            val r = songRow.getValue(e.key)
            StatSong(SongItem(e.key, r.title, r.artist, r.durationSec, r.thumbnail), e.value)
        }

    val artists = artistCount.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { artistLast[it.key] ?: 0L },
        )
        .take(limit)
        .map { e -> StatArtist(e.key, artistThumb[e.key], e.value) }

    fun top(kind: CollectionKind): List<StatCollection> =
        colCount.entries
            .filter { it.key.startsWith(kind.name + "|") }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { colRow.getValue(it.key).playedAt },
            )
            .take(limit)
            .map { e ->
                val r = colRow.getValue(e.key)
                val ref = CollectionRef(
                    kind = kind,
                    title = r.ctxTitle.orEmpty(),
                    browseId = r.ctxBrowseId,
                    params = r.ctxParams,
                    thumbnail = r.ctxThumb,
                )
                StatCollection(ref, r.ctxThumb ?: r.thumbnail, e.value)
            }

    return Stats(
        total = size,
        songs = songs,
        artists = artists,
        albums = top(CollectionKind.ALBUM),
        playlists = top(CollectionKind.PLAYLIST),
    )
}
