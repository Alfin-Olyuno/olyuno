package app.tunes.innertube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

internal fun requireAuth(cookie: String): Map<String, String> =
    authHeaders(cookie) ?: throw InnerTubeException(t("HTTP 401 (cookie tidak valid)"))

internal fun continuationToken(root: JsonObject): String? =
    root.findAll("continuationCommand").firstNotNullOfOrNull { it["token"].text }
        ?: root.findAll("nextContinuationData").firstNotNullOfOrNull { it["continuation"].text }

/** Suka / batal suka satu lagu di akun YouTube Music. */
suspend fun InnerTube.setLike(cookie: String, videoId: String, liked: Boolean) {
    val headers = requireAuth(cookie)
    post(Clients.WEB_REMIX, if (liked) "like/like" else "like/removelike", headers) {
        put("target", buildJsonObject { put("videoId", videoId) })
    }
}

/**
 * Buat playlist baru di akun YouTube Music (endpoint `playlist/create`, sama dengan yang dipakai situs YouTube Music).
 * [videoIds] ikut langsung masuk. Mengembalikan id playlist baru. Bukan API resmi: bisa berubah sewaktu-waktu.
 */
suspend fun InnerTube.createPlaylist(
    cookie: String,
    title: String,
    videoIds: List<String>,
    description: String = "",
    privacy: String = "PRIVATE",
): String {
    val headers = requireAuth(cookie)
    val root = post(Clients.WEB_REMIX, "playlist/create", headers) {
        put("title", title.take(150))
        put("description", description.take(5000))
        put("privacyStatus", privacy)
        if (videoIds.isNotEmpty()) put("videoIds", buildJsonArray { videoIds.forEach { add(JsonPrimitive(it)) } })
    }
    return root["playlistId"].text?.takeIf { it.isNotBlank() }
        ?: throw InnerTubeException(t("Gagal membuat playlist di YouTube Music"))
}

/**
 * Tambah lagu ke playlist akun (endpoint `browse/edit_playlist`, aksi ADD_VIDEO). Lagu yang sudah ada di playlist
 * dilewati oleh YouTube Music sendiri (DEDUPE_OPTION_SKIP). Dikirim per [EDIT_CHUNK] lagu. Mengembalikan jumlah lagu yang dikirim.
 */
suspend fun InnerTube.addToPlaylist(cookie: String, playlistId: String, videoIds: List<String>): Int {
    val headers = requireAuth(cookie)
    var sent = 0
    for (chunk in videoIds.distinct().chunked(EDIT_CHUNK)) {
        val root = post(Clients.WEB_REMIX, "browse/edit_playlist", headers) {
            put("playlistId", playlistId.removePrefix("VL"))
            put("actions", buildJsonArray {
                chunk.forEach { id ->
                    add(buildJsonObject {
                        put("action", "ACTION_ADD_VIDEO")
                        put("addedVideoId", id)
                        put("dedupeOption", "DEDUPE_OPTION_SKIP")
                    })
                }
            })
        }
        val status = root["status"].text
        if (status != null && status != "STATUS_SUCCEEDED") throw InnerTubeException(t("Gagal menambah lagu ke playlist di YouTube Music"))
        sent += chunk.size
    }
    return sent
}

private const val EDIT_CHUNK = 50

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

internal fun JsonObject.runsText(vararg keys: Any): String =
    path(*keys).objects().joinToString("") { it["text"].text.orEmpty() }.trim()

internal fun JsonObject.tileThumb(): String? =
    (path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails") as? JsonArray)
        ?.lastOrNull().path("url").text?.hiRes()

/** Isi "Mix untukmu": kartu Mix + pemilik rak (nama & foto akun persis seperti header rak "Mix" di YouTube Music). */
data class MixFeed(val items: List<MixPlaylist>, val owner: AccountInfo?)

private const val HOME_PAGES = 4

/**
 * Mix asli YouTube Music di beranda akun (Supermix, Mix 1, Mix Terakhir Didengar, dst.), lengkap dengan sampul aslinya.
 * Rak "Mix" sering ada di halaman lanjutan beranda, jadi beberapa halaman dibaca. Nama + foto akun diambil dari
 * header rak itu (strapline + gambar), yaitu yang YouTube Music sendiri tampilkan di atas Mix.
 */
private suspend fun InnerTube.fetchHomeMixes(headers: Map<String, String>): Pair<List<MixPlaylist>, AccountInfo?> {
    val out = ArrayList<MixPlaylist>()
    var owner: AccountInfo? = null
    for (root in browsePages("FEmusic_home", headers, HOME_PAGES)) {
        for (shelf in root.findAll("musicCarouselShelfRenderer")) {
            val head = shelf.path("header", "musicCarouselShelfBasicHeaderRenderer")
            val shelfTitle = head.path("title", "runs", 0, "text").text.orEmpty()
            val mixShelf = shelfTitle.contains("mix", ignoreCase = true)
            if (mixShelf && owner == null) {
                val name = head.path("strapline", "runs").objects().joinToString("") { it["text"].text.orEmpty() }.trim()
                if (name.isNotBlank()) {
                    val photo = (head.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails") as? JsonArray)
                        ?.lastOrNull().path("url").text?.let(::normalizePhoto)
                    owner = AccountInfo(name, photo)
                }
            }
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
    }
    return out.distinctBy { it.ref.browseId }.take(MAX_HOME_MIXES) to owner
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
suspend fun InnerTube.fetchMixPlaylists(cookie: String): MixFeed {
    val headers = requireAuth(cookie)
    val (mixes, playlists) = coroutineScope {
        val a = async { runCatching { fetchHomeMixes(headers) } }
        val b = async { runCatching { fetchLibraryPlaylists(headers) } }
        a.await() to b.await()
    }
    (mixes.exceptionOrNull() as? CancellationException)?.let { throw it }
    (playlists.exceptionOrNull() as? CancellationException)?.let { throw it }
    if (mixes.isFailure && playlists.isFailure) throw mixes.exceptionOrNull()!!
    val home = mixes.getOrNull()
    val items = (home?.first.orEmpty() + playlists.getOrDefault(emptyList())).distinctBy { it.ref.browseId }
    return MixFeed(items, home?.second)
}

/** Isi satu Mix asli (lagu-lagunya) atas nama akun, supaya Mix personal seperti Supermix cocok dengan YouTube Music. */
suspend fun InnerTube.fetchMixSongs(cookie: String, playlistId: String): List<SongItem> =
    playlistQueue(playlistId, requireAuth(cookie))
