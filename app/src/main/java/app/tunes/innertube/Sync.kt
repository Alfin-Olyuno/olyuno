package app.tunes.innertube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    // Cookie dan Authorization ikut terkirim, jadi URL dari respons hanya dipakai kalau https ke domain YouTube.
    val target = base.toHttpUrlOrNull() ?: return
    if (!target.isHttps || !(target.host == "youtube.com" || target.host.endsWith(".youtube.com"))) return
    val cpn = (1..16).map { CPN_ALPHABET[Random.nextInt(CPN_ALPHABET.length)] }.joinToString("")
    val sep = if (base.contains('?')) '&' else '?'
    getStatus("$base${sep}ver=2&c=WEB_REMIX&cpn=$cpn", headers, Clients.WEB_REMIX.userAgent)
}

/**
 * Satu kartu di "Mix untukmu". [ref] membawa judul, sampul ASLI dari YouTube Music, dan teks keterangan aslinya
 * ([CollectionRef.subtitle]). [isMix] true = Mix buatan YouTube Music (Supermix dsb.), false = playlist perpustakaan akun.
 */
data class MixPlaylist(val ref: CollectionRef, val isMix: Boolean)

private const val MAX_HOME_MIXES = 10
private const val MAX_MIX_PLAYLISTS = 20

private fun JsonObject.runsText(vararg keys: Any): String =
    path(*keys).objects().joinToString("") { it["text"].text.orEmpty() }.trim()

private fun JsonObject.tileThumb(): String? =
    (path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails") as? JsonArray)
        ?.lastOrNull().path("url").text?.hiRes()

/** Mix asli YouTube Music di beranda akun (Supermix, Mix 1, Mix Terakhir Didengar, dst.), lengkap dengan sampul aslinya. */
private suspend fun InnerTube.fetchHomeMixes(headers: Map<String, String>): List<MixPlaylist> {
    val root = browse("FEmusic_home", headers = headers)
    val out = ArrayList<MixPlaylist>()
    for (shelf in root.findAll("musicCarouselShelfRenderer")) {
        val shelfTitle = shelf.path("header", "musicCarouselShelfBasicHeaderRenderer", "title", "runs", 0, "text").text.orEmpty()
        val mixShelf = shelfTitle.contains("mix", ignoreCase = true)
        for (r in shelf.findAll("musicTwoRowItemRenderer")) {
            val nav = r["navigationEndpoint"]
            val id = nav.path("watchPlaylistEndpoint", "playlistId").text
                ?: nav.path("browseEndpoint", "browseId").text?.removePrefix("VL")
                ?: continue
            val isPersonalMix = id.startsWith("RDTMAK5uy_")
            if (!isPersonalMix && !(mixShelf && id.startsWith("RD"))) continue
            val title = r.runsText("title", "runs")
            if (title.isBlank()) continue
            val sub = r.runsText("subtitle", "runs").takeIf { it.isNotBlank() }
            out += MixPlaylist(CollectionRef(CollectionKind.MIX, title, id, null, sub, r.tileThumb()), isMix = true)
        }
    }
    return out.distinctBy { it.ref.browseId }.take(MAX_HOME_MIXES)
}

/** Playlist di perpustakaan akun (tanpa Disukai), apa adanya: judul, sampul, dan keterangan jumlah lagu dari YouTube Music. */
private suspend fun InnerTube.fetchLibraryPlaylists(headers: Map<String, String>): List<MixPlaylist> {
    val root = browse("FEmusic_liked_playlists", headers = headers)
    return root.findAll("musicTwoRowItemRenderer").mapNotNull { r ->
        val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").text ?: return@mapNotNull null
        if (id == "VLLM" || id == "LM" || id == "VLSE" || id == "SE") return@mapNotNull null
        if (!id.startsWith("VL")) return@mapNotNull null
        val title = r.runsText("title", "runs").ifBlank { return@mapNotNull null }
        val sub = r.runsText("subtitle", "runs").takeIf { it.isNotBlank() }
        MixPlaylist(CollectionRef(CollectionKind.PLAYLIST, title, id, null, sub, r.tileThumb()), isMix = false)
    }.distinctBy { it.ref.browseId }.take(MAX_MIX_PLAYLISTS)
}

/** Isi "Mix untukmu": Mix asli beranda dulu, lalu playlist perpustakaan. Salah satu gagal tetap menampilkan yang lain. */
suspend fun InnerTube.fetchMixPlaylists(cookie: String): List<MixPlaylist> {
    val headers = requireAuth(cookie)
    val (mixes, playlists) = coroutineScope {
        val a = async { runCatching { fetchHomeMixes(headers) } }
        val b = async { runCatching { fetchLibraryPlaylists(headers) } }
        a.await() to b.await()
    }
    (mixes.exceptionOrNull() as? CancellationException)?.let { throw it }
    (playlists.exceptionOrNull() as? CancellationException)?.let { throw it }
    if (mixes.isFailure && playlists.isFailure) throw mixes.exceptionOrNull()!!
    return (mixes.getOrDefault(emptyList()) + playlists.getOrDefault(emptyList())).distinctBy { it.ref.browseId }
}

/** Isi satu Mix asli (lagu-lagunya) atas nama akun, supaya Mix personal seperti Supermix cocok dengan YouTube Music. */
suspend fun InnerTube.fetchMixSongs(cookie: String, playlistId: String): List<SongItem> =
    playlistQueue(playlistId, requireAuth(cookie))
