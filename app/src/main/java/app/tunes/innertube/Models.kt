package app.tunes.innertube

import java.io.IOException

data class SongItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val thumbnail: String?,
)

/** Lagu dari perangkat memakai videoId "local:<id MediaStore>" (bukan id YouTube). */
const val LOCAL_ID_PREFIX = "local:"

fun String.isLocalId(): Boolean = startsWith(LOCAL_ID_PREFIX)

val SongItem.isLocal: Boolean get() = videoId.isLocalId()

data class AudioStream(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val userAgent: String,
    val expiresAtMs: Long,
    val clientName: String,
    val itag: Int = 0,
    /** Selisih kerasnya lagu dari patokan YouTube (dB); positif = lebih keras. Null bila tidak diberikan. */
    val loudnessDb: Float? = null,
)

/** Turunan IOException supaya ExoPlayer memperlakukannya sebagai error sumber yang bisa di-retry. */
class InnerTubeException(message: String, cause: Throwable? = null) : IOException(message, cause)

enum class CollectionKind { ARTIST, ALBUM, PLAYLIST, GENRE, QUERY, MOOD, MIX }

/**
 * Rujukan ke satu "koleksi lagu" yang bisa dibuka di halaman penuh: artis, album, playlist, genre,
 * atau (cadangan) hasil pencarian kata kunci.
 */
data class CollectionRef(
    val kind: CollectionKind,
    val title: String,
    val browseId: String? = null,
    val params: String? = null,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    /** Warna aksen ARGB dari YouTube Music (khusus genre), boleh kosong. */
    val accent: Long? = null,
    /** Dipakai oleh [CollectionKind.QUERY] dan [CollectionKind.MOOD] (kata kunci dipisah "|"). */
    val query: String? = null,
) {
    val key: String get() = "$kind|$browseId|$params|$query|$title"
}

/** Info header halaman koleksi (playlist / album / artis): deskripsi, pemilik, dan gambar besar. */
data class CollectionInfo(
    val description: String? = null,
    val owner: String? = null,
    val thumbnail: String? = null,
)

/** Isi satu koleksi: daftar lagu + info header-nya. */
data class CollectionData(
    val songs: List<SongItem>,
    val info: CollectionInfo = CollectionInfo(),
    /** Playlist terkait (dipakai halaman genre cepat); kosong untuk koleksi lain. */
    val playlists: List<CollectionRef> = emptyList(),
)

/** Satu hasil pencarian: lagu, atau artis/album/playlist. */
sealed interface SearchHit {
    val key: String

    data class Track(val song: SongItem) : SearchHit {
        override val key: String get() = "s_${song.videoId}"
    }

    data class Entity(val ref: CollectionRef) : SearchHit {
        override val key: String get() = "e_${ref.browseId}"
    }
}

/** Hasil pencarian yang sudah dikelompokkan: Teratas, Lainnya (lagu), Artis, Playlist, Album. */
data class SearchResults(
    val top: List<SearchHit>,
    val songs: List<SongItem>,
    val artists: List<CollectionRef>,
    val playlists: List<CollectionRef>,
    val albums: List<CollectionRef>,
) {
    val isEmpty: Boolean
        get() = top.isEmpty() && songs.isEmpty() && artists.isEmpty() && playlists.isEmpty() && albums.isEmpty()
}

/**
 * Satu bagian "Mirip dengan [artis]" di Beranda: artis yang sering didengar ([artist]) beserta
 * artis-artis yang mirip menurut YouTube Music ([similar], rak "Fans might also like" di halaman artisnya).
 */
data class SimilarArtists(
    val artist: String,
    val artistThumbnail: String?,
    /** Halaman artis asli (browseId sudah ada), supaya header bisa langsung dibuka. */
    val artistRef: CollectionRef,
    val similar: List<CollectionRef>,
)
