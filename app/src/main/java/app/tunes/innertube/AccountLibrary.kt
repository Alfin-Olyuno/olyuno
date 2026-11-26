package app.tunes.innertube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** Tab di halaman "Akun": isi perpustakaan akun YouTube Music. */
enum class AccountTab { PLAYLIST, ARTIST, ALBUM, PODCAST }

private const val LIB_PAGES = 8

/** Satu halaman browse + halaman lanjutannya (maks. [maxPages]). Halaman lanjutan yang gagal diabaikan. */
internal suspend fun InnerTube.browsePages(
    browseId: String,
    headers: Map<String, String>,
    maxPages: Int = LIB_PAGES,
): List<JsonObject> {
    var root = browse(browseId, headers = headers)
    val pages = arrayListOf(root)
    while (pages.size < maxPages) {
        val token = continuationToken(root) ?: break
        root = try {
            post(Clients.WEB_REMIX, "browse", headers) { put("continuation", token) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            break
        }
        pages += root
    }
    return pages
}

private fun JsonObject.flexText(i: Int): String =
    (this["flexColumns"] as? JsonArray)?.getOrNull(i)
        .path("musicResponsiveListItemFlexColumnRenderer", "text", "runs").objects()
        .joinToString("") { it["text"].text.orEmpty() }.trim()

private fun JsonObject.rowThumb(): String? =
    (path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails") as? JsonArray)
        ?.lastOrNull().path("url").text?.hiRes()

private fun JsonObject.rowBrowseId(): String? =
    path("navigationEndpoint", "browseEndpoint", "browseId").text
        ?: ((this["flexColumns"] as? JsonArray)?.getOrNull(0)
            .path("musicResponsiveListItemFlexColumnRenderer", "text", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId").text)

private fun List<JsonObject>.tiles() = flatMap { it.findAll("musicTwoRowItemRenderer") }
private fun List<JsonObject>.rows() = flatMap { it.findAll("musicResponsiveListItemRenderer") }

private fun List<JsonObject>.playlistRefs(): List<CollectionRef> = tiles().mapNotNull { r ->
    val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").text ?: return@mapNotNull null
    // Disukai & "Episode untuk nanti" punya halaman sendiri; hanya playlist biasa (VL...) yang ditampilkan di sini.
    if (id == "VLLM" || id == "LM" || id == "VLSE" || id == "SE" || !id.startsWith("VL")) return@mapNotNull null
    val title = r.runsText("title", "runs").ifBlank { return@mapNotNull null }
    CollectionRef(CollectionKind.PLAYLIST, title, id, null, r.runsText("subtitle", "runs").takeIf { it.isNotBlank() }, r.tileThumb())
}

private fun List<JsonObject>.artistRefs(): List<CollectionRef> = rows().mapNotNull { r ->
    val id = r.rowBrowseId() ?: return@mapNotNull null
    if (!id.startsWith("UC")) return@mapNotNull null
    val title = r.flexText(0).ifBlank { return@mapNotNull null }
    CollectionRef(CollectionKind.ARTIST, title, id, null, r.flexText(1).takeIf { it.isNotBlank() }, r.rowThumb())
}

private fun List<JsonObject>.albumRefs(): List<CollectionRef> = tiles().mapNotNull { r ->
    val title = r.runsText("title", "runs").ifBlank { return@mapNotNull null }
    val sub = r.runsText("subtitle", "runs").takeIf { it.isNotBlank() }
    entityOf(r["navigationEndpoint"], title, sub, r.tileThumb())?.takeIf { it.kind == CollectionKind.ALBUM }
} + rows().mapNotNull { r ->
    val id = r.rowBrowseId() ?: return@mapNotNull null
    if (!id.startsWith("MPREb_")) return@mapNotNull null
    val title = r.flexText(0).ifBlank { return@mapNotNull null }
    CollectionRef(CollectionKind.ALBUM, title, id, null, r.flexText(1).takeIf { it.isNotBlank() }, r.rowThumb())
}

/** Acara podcast yang diikuti akun (browseId MPSPP...). Dibuka lewat halaman koleksi: episodenya jadi daftar putar. */
private fun List<JsonObject>.podcastRefs(): List<CollectionRef> = tiles().mapNotNull { r ->
    val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").text ?: return@mapNotNull null
    if (!id.startsWith("MPSPP")) return@mapNotNull null
    val title = r.runsText("title", "runs").ifBlank { return@mapNotNull null }
    CollectionRef(CollectionKind.PLAYLIST, title, id, null, r.runsText("subtitle", "runs").takeIf { it.isNotBlank() }, r.tileThumb())
} + rows().mapNotNull { r ->
    val id = r.rowBrowseId() ?: return@mapNotNull null
    if (!id.startsWith("MPSPP")) return@mapNotNull null
    val title = r.flexText(0).ifBlank { return@mapNotNull null }
    CollectionRef(CollectionKind.PLAYLIST, title, id, null, r.flexText(1).takeIf { it.isNotBlank() }, r.rowThumb())
}

/**
 * Isi satu tab halaman "Akun", seluruhnya dari perpustakaan akun YouTube Music (butuh login):
 * playlist (FEmusic_liked_playlists), artis (FEmusic_library_corpus_artists + artis dari lagu di perpustakaan),
 * album (FEmusic_liked_albums), podcast (FEmusic_library_non_music_audio_list).
 */
suspend fun InnerTube.fetchAccountTab(cookie: String, tab: AccountTab): List<CollectionRef> {
    val headers = requireAuth(cookie)
    val refs = when (tab) {
        AccountTab.PLAYLIST -> browsePages("FEmusic_liked_playlists", headers).playlistRefs()
        AccountTab.ALBUM -> browsePages("FEmusic_liked_albums", headers).albumRefs()
        AccountTab.PODCAST -> browsePages("FEmusic_library_non_music_audio_list", headers).podcastRefs()
        AccountTab.ARTIST -> {
            val (subs, tracks) = coroutineScope {
                val a = async { runCatching { browsePages("FEmusic_library_corpus_artists", headers).artistRefs() } }
                val b = async { runCatching { browsePages("FEmusic_library_corpus_track_artists", headers).artistRefs() } }
                a.await() to b.await()
            }
            (subs.exceptionOrNull() as? CancellationException)?.let { throw it }
            (tracks.exceptionOrNull() as? CancellationException)?.let { throw it }
            if (subs.isFailure && tracks.isFailure) throw subs.exceptionOrNull()!!
            subs.getOrDefault(emptyList()) + tracks.getOrDefault(emptyList())
        }
    }
    return refs.distinctBy { it.browseId }
}
