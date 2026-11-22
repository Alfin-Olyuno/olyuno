package app.tunes.innertube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import app.tunes.i18n.t

/**
 * Request yang bertindak atas nama akun Google yang masuk (lewat cookie dari LoginScreen):
 * suka / batal suka, daftar Disukai, riwayat, dan pencatatan putaran ke riwayat akun.
 */

/** Daftar lagu dari akun. [complete] false = berhenti di tengah (error / terlalu panjang), jangan dipakai untuk menghapus. */
data class RemoteSongs(val songs: List<SongItem>, val complete: Boolean)

private const val MAX_PAGES = 10
private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

/** Cookie ditolak / kedaluwarsa (HTTP 401 / 403). */
internal fun Throwable.isAuthError(): Boolean {
    val m = (this as? InnerTubeException)?.message ?: return false
    return m.startsWith("HTTP 401") || m.startsWith("HTTP 403")
}

private fun requireAuth(cookie: String): Map<String, String> =
    authHeaders(cookie) ?: throw InnerTubeException(t("HTTP 401 (cookie tidak valid)"))

private fun continuationToken(root: JsonObject): String? =
    root.findAll("continuationCommand").firstNotNullOfOrNull { it["token"].text }
        ?: root.findAll("nextContinuationData").firstNotNullOfOrNull { it["continuation"].text }

/** Suka / batal suka satu lagu di akun YouTube Music. */
suspend fun InnerTube.setLike(cookie: String, videoId: String, liked: Boolean) {
    val headers = requireAuth(cookie)
    post(Clients.WEB_REMIX, if (liked) "like/like" else "like/removelike", headers) {
        put("target", buildJsonObject { put("videoId", videoId) })
    }
}

/** Playlist "Disukai" (LM) milik akun, dengan halaman lanjutan sampai [MAX_PAGES]. */
suspend fun InnerTube.fetchLiked(cookie: String): RemoteSongs {
    val headers = requireAuth(cookie)
    var root = browse("VLLM", headers = headers)
    val out = ArrayList<SongItem>(collectSongs(root, includeTwoRow = false))
    var pages = 1
    while (true) {
        val token = continuationToken(root)
            ?: return RemoteSongs(out.distinctBy { it.videoId }, complete = true)
        if (pages >= MAX_PAGES) return RemoteSongs(out.distinctBy { it.videoId }, complete = false)
        root = try {
            post(Clients.WEB_REMIX, "browse", headers) { put("continuation", token) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RemoteSongs(out.distinctBy { it.videoId }, complete = false)
        }
        out += collectSongs(root, includeTwoRow = false)
        pages++
    }
}

/** Riwayat putar akun (terbaru di depan). */
suspend fun InnerTube.fetchHistory(cookie: String): List<SongItem> {
    val headers = requireAuth(cookie)
    return collectSongs(browse("FEmusic_history", headers = headers), includeTwoRow = false).take(200)
}

/**
 * Catat satu putaran ke riwayat akun, cara yang sama dengan pemutar web: ambil URL pelacakan dari respons
 * `player` (WEB_REMIX, sudah login) lalu panggil dengan cpn acak.
 */
suspend fun InnerTube.registerPlayback(cookie: String, videoId: String) {
    val headers = authHeaders(cookie) ?: return
    val root = post(Clients.WEB_REMIX, "player", headers) { put("videoId", videoId) }
    val base = root.path("playbackTracking", "videostatsPlaybackUrl", "baseUrl").text ?: return
    val cpn = (1..16).map { CPN_ALPHABET[Random.nextInt(CPN_ALPHABET.length)] }.joinToString("")
    val sep = if (base.contains('?')) '&' else '?'
    getStatus("$base${sep}ver=2&c=WEB_REMIX&cpn=$cpn", headers, Clients.WEB_REMIX.userAgent)
}

/** Satu playlist milik akun untuk bagian "Mix untukmu": [covers] = sampul sampai 4 lagu pertama (kolase), [count] = jumlah lagu. */
data class MixPlaylist(val ref: CollectionRef, val covers: List<String>, val count: Int?)

private const val MAX_MIX_PLAYLISTS = 10

/** Playlist di perpustakaan akun (tanpa Disukai), beserta sampul kolase dan jumlah lagunya. */
suspend fun InnerTube.fetchMixPlaylists(cookie: String): List<MixPlaylist> {
    val headers = requireAuth(cookie)
    val root = browse("FEmusic_liked_playlists", headers = headers)
    val refs = root.findAll("musicTwoRowItemRenderer").mapNotNull { r ->
        val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").text ?: return@mapNotNull null
        if (id == "VLLM" || id == "LM" || id == "VLSE" || id == "SE") return@mapNotNull null
        if (!id.startsWith("VL")) return@mapNotNull null
        val title = r.path("title", "runs").objects().firstNotNullOfOrNull { it["text"].text } ?: return@mapNotNull null
        val thumb = (r.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails") as? JsonArray)
            ?.lastOrNull().path("url").text?.hiRes()
        CollectionRef(CollectionKind.PLAYLIST, title, id, null, null, thumb)
    }.distinctBy { it.browseId }.take(MAX_MIX_PLAYLISTS)

    return coroutineScope {
        refs.map { ref ->
            async {
                try {
                    val songs = collectSongs(browse(ref.browseId!!, headers = headers), includeTwoRow = false)
                    val covers = songs.mapNotNull { it.thumbnail }.distinct().take(4)
                    MixPlaylist(ref, covers.ifEmpty { listOfNotNull(ref.thumbnail) }, songs.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    MixPlaylist(ref, listOfNotNull(ref.thumbnail), null)
                }
            }
        }.awaitAll()
    }
}
