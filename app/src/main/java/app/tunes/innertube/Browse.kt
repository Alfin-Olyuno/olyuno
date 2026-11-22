package app.tunes.innertube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Fitur "jelajah" di atas [InnerTube]: pencarian berkelompok, Pilihan cepat, Genre, dan isi
 * artis / album / playlist. Seperti sisanya, parsing dilakukan dengan menelusuri pohon JSON.
 */

private const val GENRE_PAGE = "FEmusic_moods_and_genres"
private const val GENRE_CATEGORY_PAGE = "FEmusic_moods_and_genres_category"
private const val MAX_GENRE_PLAYLISTS = 3
private const val MAX_COLLECTION_SONGS = 100

// ------------------------------------------------------------ helpers

private fun List<JsonObject>.joined() = joinToString("") { it["text"].text.orEmpty() }

private fun JsonObject.flexRuns(i: Int): List<JsonObject> =
    (this["flexColumns"] as? JsonArray)?.getOrNull(i)
        .path("musicResponsiveListItemFlexColumnRenderer", "text", "runs").objects()

private fun JsonObject.listVideoId(): String? =
    path("playlistItemData", "videoId").text
        ?: path(
            "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
            "playNavigationEndpoint", "watchEndpoint", "videoId",
        ).text

private fun thumbOf(thumbs: JsonElement?): String? =
    (thumbs as? JsonArray)?.lastOrNull().path("url").text?.hiRes()

private fun entityOf(nav: JsonElement?, title: String, subtitle: String?, thumb: String?): CollectionRef? {
    val be = nav.path("browseEndpoint") ?: return null
    val id = be.path("browseId").text ?: return null
    val type = be.path(
        "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType",
    ).text
    val kind = when (type) {
        "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" -> CollectionKind.ARTIST
        "MUSIC_PAGE_TYPE_ALBUM" -> CollectionKind.ALBUM
        "MUSIC_PAGE_TYPE_PLAYLIST" -> CollectionKind.PLAYLIST
        else -> when {
            id.startsWith("MPREb_") -> CollectionKind.ALBUM
            id.startsWith("UC") -> CollectionKind.ARTIST
            id.startsWith("VL") -> CollectionKind.PLAYLIST
            else -> return null
        }
    }
    return CollectionRef(kind, title, id, be.path("params").text, subtitle?.takeIf { it.isNotBlank() }, thumb)
}

internal suspend fun InnerTube.browse(
    browseId: String,
    params: String? = null,
    headers: Map<String, String> = emptyMap(),
): JsonObject =
    post(Clients.WEB_REMIX, "browse", headers) {
        put("browseId", browseId)
        if (params != null) put("params", params)
    }

// ------------------------------------------------------------- search

private fun InnerTube.parseHit(item: JsonObject): SearchHit? {
    val title = item.flexRuns(0).joined()
    if (title.isBlank()) return null
    val thumbs = item.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
    val sub = item.flexRuns(1)

    val videoId = item.listVideoId()
    if (videoId != null) {
        return buildSong(videoId, title, sub, thumbs)?.let { SearchHit.Track(it) }
    }
    val nav = item["navigationEndpoint"] ?: item.flexRuns(0).firstOrNull().path("navigationEndpoint")
    return entityOf(nav, title, sub.joined(), thumbOf(thumbs))?.let { SearchHit.Entity(it) }
}

private fun InnerTube.cardHit(card: JsonObject): SearchHit? {
    val titleRun = card.path("title", "runs", 0)
    val title = card.path("title", "runs").objects().joined()
    val sub = card.path("subtitle", "runs").objects()
    val thumbs = card.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
    val videoId = titleRun.path("navigationEndpoint", "watchEndpoint", "videoId").text
    if (videoId != null) return buildSong(videoId, title, sub, thumbs)?.let { SearchHit.Track(it) }
    return entityOf(titleRun.path("navigationEndpoint"), title, sub.joined(), thumbOf(thumbs))
        ?.let { SearchHit.Entity(it) }
}

/** Jumlah saran pencarian yang ditampilkan saat pengguna mengetik. */
const val SEARCH_SUGGESTION_COUNT = 5

/** Saran pencarian YouTube Music untuk [query] (maksimal [SEARCH_SUGGESTION_COUNT]), urutan dari server. */
suspend fun InnerTube.searchSuggestions(query: String): List<String> {
    val root = post(Clients.WEB_REMIX, "music/get_search_suggestions") { put("input", query) }
    return root.findAll("searchSuggestionRenderer")
        .mapNotNull { r ->
            val fromRuns = r.path("suggestion", "runs").objects().joinToString("") { it["text"].text.orEmpty() }
            fromRuns.ifBlank { r.path("navigationEndpoint", "searchEndpoint", "query").text.orEmpty() }
                .trim().takeIf { it.isNotEmpty() }
        }
        .distinctBy { it.lowercase() }
        .take(SEARCH_SUGGESTION_COUNT)
}

/** Cari lalu kelompokkan: Teratas, Lainnya (lagu), Artis, Playlist, Album. */
suspend fun InnerTube.searchAll(query: String): SearchResults {
    val root = post(Clients.WEB_REMIX, "search") { put("query", query) }

    fun MutableMap<String, SearchHit>.add(hit: SearchHit?) {
        if (hit != null) putIfAbsent(hit.key, hit)
    }

    // Teratas: kartu "top result" + lagu-lagu di dalam kartunya.
    val top = LinkedHashMap<String, SearchHit>()
    root.findAll("musicCardShelfRenderer").firstOrNull()?.let { card ->
        top.add(cardHit(card))
        card.findAll("musicResponsiveListItemRenderer").forEach { top.add(parseHit(it)) }
    }

    val all = LinkedHashMap<String, SearchHit>()
    root.findAll("musicResponsiveListItemRenderer").forEach { all.add(parseHit(it)) }

    var topList = top.values.take(4)
    // Tanpa kartu: hasil pertama dianggap yang teratas.
    if (topList.isEmpty()) topList = all.values.take(1)
    val used = topList.map { it.key }.toSet()
    val rest = all.values.filter { it.key !in used }

    val entities = rest.filterIsInstance<SearchHit.Entity>().map { it.ref }
    return SearchResults(
        top = topList,
        songs = rest.filterIsInstance<SearchHit.Track>().map { it.song },
        artists = entities.filter { it.kind == CollectionKind.ARTIST },
        playlists = entities.filter { it.kind == CollectionKind.PLAYLIST },
        albums = entities.filter { it.kind == CollectionKind.ALBUM },
    )
}

// --------------------------------------------------------- lagu dari JSON

private fun InnerTube.listItemSong(
    item: JsonObject,
    fallbackThumb: String?,
    fallbackArtist: String,
    strictArtist: Boolean,
): SongItem? {
    val id = item.listVideoId() ?: return null
    val title = item.flexRuns(0).joined()
    val sub = item.flexRuns(1)
    val thumbs = item.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
    var song = buildSong(id, title, sub, thumbs) ?: return null

    if (strictArtist) {
        // Halaman album: kolom ke-2 sering berisi jumlah putar, jadi hanya terima run bertipe artis.
        val artists = sub.filter { r ->
            val type = r.path(
                "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig", "pageType",
            ).text
            type == "MUSIC_PAGE_TYPE_ARTIST" || type == "MUSIC_PAGE_TYPE_USER_CHANNEL"
        }.joined()
        song = song.copy(artist = artists.ifBlank { fallbackArtist })
    } else if (song.artist.isBlank()) {
        song = song.copy(artist = fallbackArtist)
    }
    if (song.durationSec == 0) {
        val d = item["fixedColumns"]
            .path(0, "musicResponsiveListItemFixedColumnRenderer", "text", "runs", 0, "text").text
            ?.let(::parseDuration)
        if (d != null) song = song.copy(durationSec = d)
    }
    if (song.thumbnail == null) song = song.copy(thumbnail = fallbackThumb)
    return song
}

/** Kartu dua baris (dipakai di halaman genre / beranda) yang menunjuk ke satu lagu. */
private fun InnerTube.twoRowSong(item: JsonObject): SongItem? {
    val id = item.path("navigationEndpoint", "watchEndpoint", "videoId").text ?: return null
    val title = item.path("title", "runs").objects().joined()
    val sub = item.path("subtitle", "runs").objects()
    val thumbs = item.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails")
    return buildSong(id, title, sub, thumbs)
}

internal fun InnerTube.collectSongs(
    root: JsonElement,
    includeTwoRow: Boolean,
    fallbackThumb: String? = null,
    fallbackArtist: String = "",
    strictArtist: Boolean = false,
): List<SongItem> {
    // Halaman playlist: pakai isi daftarnya saja, bukan rak "saran lagu" di bawahnya.
    val scopes = root.findAll("musicPlaylistShelfRenderer").ifEmpty { listOf(root) }
    val rows = scopes.flatMap { it.findAll("musicResponsiveListItemRenderer") }
        .mapNotNull { listItemSong(it, fallbackThumb, fallbackArtist, strictArtist) }
    val tiles = if (includeTwoRow) root.findAll("musicTwoRowItemRenderer").mapNotNull { twoRowSong(it) } else emptyList()
    return (rows + tiles).distinctBy { it.videoId }
}

// -------------------------------------------------------- Pilihan cepat

/**
 * Lagu untuk "Pilihan cepat". Bila ada [seedVideoId] (lagu yang sering diputar), pakai radio-nya supaya
 * terasa personal; selain itu ambil rak "Quick picks" di beranda YouTube Music, lalu cadangan terakhir
 * lewat pencarian.
 */
suspend fun InnerTube.quickPicks(seedVideoId: String?): List<SongItem> {
    if (seedVideoId != null) {
        try {
            val radio = radio(seedVideoId).filter { it.videoId != seedVideoId }
            if (radio.size >= 4) return radio
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // lanjut ke sumber berikutnya
        }
    }
    try {
        val root = browse("FEmusic_home")
        val shelves = root.findAll("musicCarouselShelfRenderer")
        fun titleOf(s: JsonObject) = s.path(
            "header", "musicCarouselShelfBasicHeaderRenderer", "title", "runs", 0, "text",
        ).text.orEmpty()

        val shelf = shelves.firstOrNull { titleOf(it).contains("quick pick", ignoreCase = true) }
        val songs = if (shelf != null) {
            collectSongs(shelf, includeTwoRow = true)
        } else {
            shelves.map { collectSongs(it, includeTwoRow = true) }.firstOrNull { it.size >= 4 }.orEmpty()
        }
        if (songs.isNotEmpty()) return songs
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // lanjut ke cadangan
    }
    return searchAll("lagu populer indonesia").let { r ->
        (r.top.filterIsInstance<SearchHit.Track>().map { it.song } + r.songs).distinctBy { it.videoId }
    }
}

// ------------------------------------------------------- Sedang trending

/**
 * Lagu yang sedang trending: rak tangga lagu (Trending / Top songs) di halaman "Charts" YouTube Music;
 * bila tidak ada, cadangan lewat pencarian kata kunci trending.
 */
suspend fun InnerTube.trendingSongs(): List<SongItem> {
    try {
        val root = browse("FEmusic_charts")
        val shelves = root.findAll("musicCarouselShelfRenderer")
        fun titleOf(s: JsonObject) = s.path(
            "header", "musicCarouselShelfBasicHeaderRenderer", "title", "runs", 0, "text",
        ).text.orEmpty()

        val keys = listOf("trending", "top song", "lagu teratas", "teratas", "tren")
        val shelf = shelves.firstOrNull { sh -> titleOf(sh).let { t -> keys.any { t.contains(it, ignoreCase = true) } } }
        val songs = if (shelf != null) {
            collectSongs(shelf, includeTwoRow = true)
        } else {
            shelves.map { collectSongs(it, includeTwoRow = true) }.firstOrNull { it.size >= 4 }.orEmpty()
        }
        if (songs.size >= 4) return songs.take(MAX_TRENDING)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // lanjut ke cadangan
    }
    return querySongs("lagu trending indonesia 2026").take(MAX_TRENDING)
}

private const val MAX_TRENDING = 20

// --------------------------------------------------------------- Genre

/** Cadangan bila daftar genre dari YouTube Music gagal dimuat: dibuka lewat pencarian kata kunci. */
val fallbackGenres: List<CollectionRef> = listOf(
    "Pop", "Rock", "Hip-hop", "Dangdut", "Indie", "R&B", "Jazz", "K-pop", "Elektronik",
    "Dance", "Reggae", "Metal", "Akustik", "Klasik", "Country", "Latin",
).map { CollectionRef(CollectionKind.QUERY, it, query = "$it music") }

/** Daftar genre dari YouTube Music ("Moods & genres"). Kosong bila tidak ditemukan. */
suspend fun InnerTube.genres(): List<CollectionRef> {
    val root = browse(GENRE_PAGE)
    val grids = root.findAll("gridRenderer")
    fun titleOf(g: JsonObject) = g.path("header", "gridHeaderRenderer", "title", "runs", 0, "text").text.orEmpty()

    // Halaman ini memuat bagian "Moods & moments" dan "Genres"; kita hanya butuh yang kedua.
    val chosen = grids.filter { titleOf(it).contains("genre", ignoreCase = true) }.ifEmpty { grids }
    return chosen.flatMap { g ->
        g["items"].objects().mapNotNull { it["musicNavigationButtonRenderer"] as? JsonObject }
    }.mapNotNull { b ->
        val title = b.path("buttonText", "runs", 0, "text").text ?: return@mapNotNull null
        val be = b.path("clickCommand", "browseEndpoint")
        if (be.path("browseId").text != GENRE_CATEGORY_PAGE) return@mapNotNull null
        CollectionRef(
            kind = CollectionKind.GENRE,
            title = title,
            browseId = GENRE_CATEGORY_PAGE,
            params = be.path("params").text,
            accent = (b.path("solid", "leftStripeColor") as? JsonPrimitive)?.longOrNull,
        )
    }.distinctBy { it.title }
}

private suspend fun InnerTube.genreSongs(ref: CollectionRef): List<SongItem> {
    val root = browse(GENRE_CATEGORY_PAGE, ref.params)
    val direct = collectSongs(root, includeTwoRow = true)

    val playlistIds = root.findAll("musicTwoRowItemRenderer").mapNotNull { r ->
        val be = r.path("navigationEndpoint", "browseEndpoint")
        val id = be.path("browseId").text ?: return@mapNotNull null
        val type = be.path(
            "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType",
        ).text
        id.takeIf { type == "MUSIC_PAGE_TYPE_PLAYLIST" || it.startsWith("VL") }
    }.distinct().take(MAX_GENRE_PLAYLISTS)

    val fromPlaylists = coroutineScope {
        playlistIds.map { id ->
            async {
                try {
                    collectSongs(browse(id), includeTwoRow = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll()
    }

    // Selang-seling antar playlist supaya genre terasa beragam, bukan satu playlist habis dulu.
    val mixed = ArrayList<SongItem>()
    val longest = fromPlaylists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) fromPlaylists.forEach { l -> l.getOrNull(i)?.let(mixed::add) }

    return (direct + mixed).distinctBy { it.videoId }.take(MAX_COLLECTION_SONGS)
}

// ------------------------------------------------------ Genre cepat (mood)

/**
 * Genre cepat di bawah sapaan beranda. Tiap genre memakai beberapa kata kunci (dipisah "|") yang
 * condong ke lagu yang sedang trending; hasilnya digabung selang-seling.
 */
val quickGenres: List<CollectionRef> = listOf(
    "Feel good" to "feel good songs trending 2026|lagu semangat bahagia trending",
    "Sad" to "sad songs trending 2026|lagu galau sedih trending",
    "Romance" to "romantic love songs trending 2026|lagu romantis trending",
    "Nostalgic" to "nostalgic throwback songs trending|lagu nostalgia populer",
    "Focus" to "focus music trending|lagu fokus belajar santai",
    "Party" to "party songs trending 2026|lagu pesta dance trending",
    "Sleep" to "sleep music trending|lagu pengantar tidur tenang",
).map { (title, q) -> CollectionRef(CollectionKind.MOOD, title, query = q) }

private const val MAX_MOOD_PLAYLISTS = 12

/** Lagu + playlist untuk satu genre cepat: tiap kata kunci dicari, hasilnya digabung selang-seling. */
private suspend fun InnerTube.moodData(ref: CollectionRef): CollectionData {
    val queries = (ref.query ?: ref.title).split("|").map { it.trim() }.filter { it.isNotEmpty() }
    val results = coroutineScope {
        queries.map { q ->
            async {
                try {
                    searchAll(q)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val mixed = ArrayList<T>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) lists.forEach { l -> l.getOrNull(i)?.let(mixed::add) }
        return mixed
    }
    val songs = interleave(
        results.map { r -> (r.top.filterIsInstance<SearchHit.Track>().map { it.song } + r.songs).distinctBy { it.videoId } },
    ).distinctBy { it.videoId }
    val playlists = interleave(results.map { it.playlists })
        .distinctBy { it.key }
        .take(MAX_MOOD_PLAYLISTS)
    return CollectionData(songs, playlists = playlists)
}

// -------------------------------------------------- Artis / Album / Playlist

private suspend fun InnerTube.collectionData(ref: CollectionRef): CollectionData {
    val id = ref.browseId ?: return CollectionData(emptyList())
    val root = browse(id)
    val header = root.findAll("musicResponsiveHeaderRenderer").firstOrNull()
        ?: root.findAll("musicDetailHeaderRenderer").firstOrNull()
        ?: root.findAll("musicImmersiveHeaderRenderer").firstOrNull()
    val headerThumb = header?.let {
        thumbOf(
            it.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
                ?: it.path("thumbnail", "croppedSquareThumbnailRenderer", "thumbnail", "thumbnails"),
        )
    } ?: ref.thumbnail

    val result = when (ref.kind) {
        CollectionKind.ARTIST -> {
            var songs = collectSongs(root, includeTwoRow = false, fallbackArtist = ref.title)
            // Rak lagu di halaman artis hanya memuat sebagian; tombol "lainnya" menuju daftar lengkap.
            val moreId = root.findAll("musicShelfRenderer").firstNotNullOfOrNull {
                it.path("bottomEndpoint", "browseEndpoint", "browseId").text
                    ?: it.path("title", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId").text
            }
            if (moreId != null && moreId != id) {
                try {
                    val full = collectSongs(browse(moreId), includeTwoRow = false, fallbackArtist = ref.title)
                    if (full.size > songs.size) songs = full
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // tetap pakai daftar singkat
                }
            }
            songs
        }

        CollectionKind.ALBUM -> {
            val artist = header.path("straplineTextOne", "runs").objects().joined()
                .ifBlank { ref.subtitle?.split(" • ")?.getOrNull(1).orEmpty() }
            collectSongs(root, includeTwoRow = false, fallbackThumb = headerThumb, fallbackArtist = artist, strictArtist = true)
        }

        else -> collectSongs(root, includeTwoRow = false, fallbackThumb = headerThumb)
    }
    return CollectionData(result, collectionInfo(header, headerThumb))
}

/** Deskripsi, pemilik (mis. "YouTube Music"), dan gambar besar dari header halaman koleksi. */
private fun collectionInfo(header: JsonObject?, headerThumb: String?): CollectionInfo {
    if (header == null) return CollectionInfo(thumbnail = headerThumb?.hiRes(1080))
    val description = header.path("description", "musicDescriptionShelfRenderer", "description", "runs").objects().joined()
        .ifBlank { header.path("description", "runs").objects().joined() }
        .trim().takeIf { it.isNotBlank() }
    val owner = header.path("straplineTextOne", "runs").objects().joined().trim().ifBlank {
        // Header lama: subtitle "Playlist • YouTube Music • 2024" -> ambil bagian ke-2.
        val parts = header.path("subtitle", "runs").objects()
            .map { it["text"].text.orEmpty().trim() }
            .filter { it.isNotBlank() && it != "•" }
        if (parts.size >= 3) parts[1] else ""
    }.takeIf { it.isNotBlank() }
    return CollectionInfo(description, owner, headerThumb?.hiRes(1080))
}

/** Muat daftar lagu + info header untuk [ref] (genre, artis, album, playlist, atau kata kunci). */
suspend fun InnerTube.loadCollection(ref: CollectionRef): CollectionData {
    val data = when (ref.kind) {
        CollectionKind.QUERY -> CollectionData(querySongs(ref.query ?: ref.title))
        CollectionKind.GENRE -> CollectionData(genreSongs(ref))
        CollectionKind.MOOD -> moodData(ref)
        // Artis dari statistik (mis. "Andalanmu") hanya punya nama: cari halaman artis aslinya dulu.
        // Kalau tidak ketemu yang namanya sama persis, cadangannya lagu-lagu hasil pencarian nama itu.
        CollectionKind.ARTIST -> if (ref.browseId != null) {
            collectionData(ref)
        } else {
            resolveArtist(ref.title)
                ?.let { collectionData(it.copy(title = ref.title, thumbnail = it.thumbnail ?: ref.thumbnail)) }
                ?: CollectionData(querySongs(ref.title))
        }
        else -> collectionData(ref)
    }
    // Halaman kosong (mis. genre tanpa lagu langsung): cadangan lewat pencarian judulnya.
    val songs = data.songs.ifEmpty { if (ref.kind == CollectionKind.QUERY || ref.kind == CollectionKind.MOOD) data.songs else querySongs(ref.title) }
        .take(MAX_COLLECTION_SONGS)
    return data.copy(songs = songs)
}

/** Cari halaman artis YouTube Music untuk [name]; hanya yang namanya sama persis (tanpa beda huruf besar/kecil). */
private suspend fun InnerTube.resolveArtist(name: String): CollectionRef? {
    val q = name.trim()
    val r = searchAll(q)
    val fromTop = r.top.filterIsInstance<SearchHit.Entity>().map { it.ref }
    return (fromTop + r.artists)
        .filter { it.kind == CollectionKind.ARTIST }
        .firstOrNull { it.title.trim().equals(q, ignoreCase = true) }
}

private suspend fun InnerTube.querySongs(q: String): List<SongItem> {
    val r = searchAll(q)
    return (r.top.filterIsInstance<SearchHit.Track>().map { it.song } + r.songs).distinctBy { it.videoId }
}
