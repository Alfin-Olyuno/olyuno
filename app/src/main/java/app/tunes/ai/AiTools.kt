package app.tunes.ai

import app.tunes.innertube.CollectionKind
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.InnerTube
import app.tunes.innertube.SearchHit
import app.tunes.innertube.SongItem
import app.tunes.innertube.loadCollection
import app.tunes.innertube.searchAll
import app.tunes.lyrics.LyricsRepository
import app.tunes.lyrics.LyricsSource
import app.tunes.lyrics.LyricsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Alat (function calling) yang dipanggil Olyuno AI sendiri: cari lagu, cari lagu dari potongan lirik, cek / ambil lirik,
 * cari artis, album, playlist, dan isi sebuah koleksi. Hasilnya data nyata dari YouTube Music dan layanan lirik
 * (LRCLIB, Better Lyrics, BiniLyrics, Lyrics Plus lewat pilihan "Otomatis"), bukan tebakan AI.
 */
class AiTools(
    private val innerTube: InnerTube,
    private val lyrics: LyricsRepository,
) {
    /** Teks indikator (bahasa Indonesia, lewat t() di UI) saat alat [name] sedang berjalan. */
    fun statusOf(name: String): String = when (name) {
        SEARCH_SONGS -> "Mencari lagu"
        SEARCH_LYRICS -> "Mencari lagu dari lirik"
        CHECK_LYRICS -> "Memeriksa lirik"
        GET_LYRICS -> "Membaca lirik"
        SEARCH_ARTISTS -> "Mencari artis"
        SEARCH_ALBUMS -> "Mencari album"
        SEARCH_PLAYLISTS -> "Mencari playlist"
        GET_TRACKS -> "Membuka daftar lagu"
        else -> "Mencari"
    }

    /** Daftar deklarasi alat untuk Gemini (functionDeclarations). */
    fun declarations(): JSONArray = JSONArray()
        .put(
            decl(
                SEARCH_SONGS,
                "Cari lagu di YouTube Music berdasarkan judul, nama artis, suasana, atau kata kunci apa pun. Pakai ini untuk setiap permintaan mencari / merekomendasikan lagu.",
                props("query" to str("Kata kunci pencarian, mis. \"Tulus Hati-Hati di Jalan\" atau \"lagu galau 2000an\""), "limit" to int("Jumlah hasil, 1-10 (bawaan 8)")),
                listOf("query"),
            ),
        )
        .put(
            decl(
                SEARCH_LYRICS,
                "Cari lagu dari potongan LIRIK yang diingat pengguna. Kandidat dicari lalu diperiksa langsung ke layanan lirik; hasil diurutkan dari yang liriknya paling cocok dengan potongan itu.",
                props("lyrics" to str("Potongan lirik persis seperti yang diingat pengguna (bahasa aslinya, jangan diterjemahkan)")),
                listOf("lyrics"),
            ),
        )
        .put(
            decl(
                CHECK_LYRICS,
                "Periksa apakah sebuah lagu memang memuat potongan lirik tertentu, lewat layanan lirik. Pakai untuk memverifikasi tebakanmu sendiri sebelum menjawab.",
                props(
                    "title" to str("Judul lagu"),
                    "artist" to str("Nama artis"),
                    "lyrics_snippet" to str("Potongan lirik yang ingin dicocokkan"),
                ),
                listOf("title", "artist", "lyrics_snippet"),
            ),
        )
        .put(
            decl(
                GET_LYRICS,
                "Ambil lirik sebuah lagu dari layanan lirik untuk dianalisis (makna, tema, arti baris). Jangan disalin utuh ke jawaban.",
                props("title" to str("Judul lagu"), "artist" to str("Nama artis")),
                listOf("title", "artist"),
            ),
        )
        .put(
            decl(
                SEARCH_ARTISTS,
                "Cari artis / penyanyi / grup di YouTube Music.",
                props("query" to str("Nama artis atau kata kunci")),
                listOf("query"),
            ),
        )
        .put(
            decl(
                SEARCH_ALBUMS,
                "Cari album di YouTube Music.",
                props("query" to str("Judul album, boleh disertai nama artis")),
                listOf("query"),
            ),
        )
        .put(
            decl(
                SEARCH_PLAYLISTS,
                "Cari playlist di YouTube Music (mis. playlist suasana, genre, atau tema).",
                props("query" to str("Kata kunci playlist")),
                listOf("query"),
            ),
        )
        .put(
            decl(
                GET_TRACKS,
                "Ambil daftar lagu di dalam sebuah artis (lagu populer), album, atau playlist. Pakai untuk pertanyaan seperti \"isi album X\" atau \"lagu terpopuler artis Y\".",
                props(
                    "type" to str("Salah satu: artist, album, playlist"),
                    "name" to str("Nama artis / judul album / judul playlist"),
                    "by" to str("Artis album atau pembuat playlist (opsional, membantu mencari yang tepat)"),
                ),
                listOf("type", "name"),
            ),
        )

    /** Jalankan alat. Tidak pernah melempar kecuali dibatalkan: kegagalan dikembalikan sebagai {"error": ...} supaya AI bisa menjelaskannya. */
    suspend fun run(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            SEARCH_SONGS -> searchSongs(args.optString("query"), args.optInt("limit", 8))
            SEARCH_LYRICS -> searchLyrics(args.optString("lyrics"))
            CHECK_LYRICS -> checkLyrics(args.optString("title"), args.optString("artist"), args.optString("lyrics_snippet"))
            GET_LYRICS -> getLyrics(args.optString("title"), args.optString("artist"))
            SEARCH_ARTISTS -> searchEntities(args.optString("query"), CollectionKind.ARTIST)
            SEARCH_ALBUMS -> searchEntities(args.optString("query"), CollectionKind.ALBUM)
            SEARCH_PLAYLISTS -> searchEntities(args.optString("query"), CollectionKind.PLAYLIST)
            GET_TRACKS -> getTracks(args.optString("type"), args.optString("name"), args.optString("by"))
            else -> JSONObject().put("error", "alat tidak dikenal: $name")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        JSONObject().put("error", "gagal: " + (e.message ?: e.javaClass.simpleName))
    }

    // ------------------------------------------------------------------ pencarian

    private suspend fun searchSongs(query: String, limit: Int): JSONObject {
        val q = query.trim()
        if (q.isEmpty()) return JSONObject().put("error", "query kosong")
        val songs = innerTube.search(q).distinctBy { it.videoId }.take(limit.coerceIn(1, 10))
        return JSONObject().put("songs", JSONArray(songs.map { songJson(it) })).also {
            if (songs.isEmpty()) it.put("note", "tidak ada hasil")
        }
    }

    private suspend fun searchEntities(query: String, kind: CollectionKind): JSONObject {
        val q = query.trim()
        if (q.isEmpty()) return JSONObject().put("error", "query kosong")
        val res = innerTube.searchAll(q)
        val pool = (res.top.filterIsInstance<SearchHit.Entity>().map { it.ref } + res.artists + res.albums + res.playlists)
            .filter { it.kind == kind }
            .distinctBy { it.key }
            .take(8)
        val key = when (kind) {
            CollectionKind.ARTIST -> "artists"
            CollectionKind.ALBUM -> "albums"
            else -> "playlists"
        }
        return JSONObject().put(key, JSONArray(pool.map { entityJson(it) })).also {
            if (pool.isEmpty()) it.put("note", "tidak ada hasil")
        }
    }

    private suspend fun getTracks(type: String, name: String, by: String): JSONObject {
        val kind = when (type.trim().lowercase()) {
            "artist", "artis" -> CollectionKind.ARTIST
            "album" -> CollectionKind.ALBUM
            "playlist" -> CollectionKind.PLAYLIST
            else -> return JSONObject().put("error", "type harus artist, album, atau playlist")
        }
        val n = name.trim()
        if (n.isEmpty()) return JSONObject().put("error", "name kosong")
        val q = if (kind == CollectionKind.ARTIST) n else (n + " " + by.trim()).trim()
        val res = innerTube.searchAll(q)
        val pool = (res.top.filterIsInstance<SearchHit.Entity>().map { it.ref } + res.artists + res.albums + res.playlists)
            .filter { it.kind == kind }
            .distinctBy { it.key }
        val low = n.lowercase()
        val byName = pool.firstOrNull { val t = it.title.trim().lowercase(); t.contains(low) || low.contains(t) }
        val ref = byName ?: (if (kind == CollectionKind.ALBUM) null else pool.firstOrNull())
            ?: return JSONObject().put("error", "tidak ditemukan")
        val data = innerTube.loadCollection(ref)
        val out = JSONObject()
            .put("name", ref.title)
            .put("type", type.trim().lowercase())
            .put("songs", JSONArray(data.songs.take(15).map { songJson(it) }))
        ref.subtitle?.takeIf { it.isNotBlank() }?.let { out.put("details", it) }
        data.info.description?.takeIf { it.isNotBlank() }?.let { out.put("description", it.take(300)) }
        if (data.songs.isEmpty()) out.put("note", "daftar lagu kosong")
        return out
    }

    // ------------------------------------------------------------------ lirik

    /**
     * Cari lagu dari potongan lirik: kandidat dari pencarian YouTube Music, lalu tiap kandidat diperiksa liriknya
     * lewat layanan lirik (pilihan Otomatis, jadi semua layanan ikut ditanya) dan dicocokkan dengan potongannya.
     */
    private suspend fun searchLyrics(snippet: String): JSONObject {
        val s = snippet.trim()
        if (s.length < 4) return JSONObject().put("error", "potongan lirik terlalu pendek")
        val candidates = LinkedHashMap<String, SongItem>()
        for (q in listOf(s, "$s lirik")) {
            runCatching { innerTube.search(q) }.getOrNull().orEmpty().forEach { candidates.putIfAbsent(it.videoId, it) }
            if (candidates.size >= MAX_LYRIC_CANDIDATES) break
        }
        val list = candidates.values.take(MAX_LYRIC_CANDIDATES)
        if (list.isEmpty()) return JSONObject().put("matches", JSONArray()).put("note", "tidak ada kandidat; coba tebak lagunya lalu pakai check_lyrics")
        val checked = withTimeoutOrNull(LYRIC_TIMEOUT_MS) {
            coroutineScope { list.map { song -> async { verify(song, s) } }.awaitAll() }
        }.orEmpty()
        val ranked = checked.sortedByDescending { it.score }
        val out = JSONObject().put("matches", JSONArray(ranked.map { it.toJson() }))
        if (ranked.none { it.label == "exact" || it.label == "partial" }) {
            out.put("note", "tidak ada yang liriknya cocok; coba tebak lagunya dari ingatanmu lalu verifikasi dengan check_lyrics, atau minta potongan lirik lain")
        }
        return out
    }

    private suspend fun checkLyrics(title: String, artist: String, snippet: String): JSONObject {
        if (snippet.isBlank()) return JSONObject().put("error", "lyrics_snippet kosong")
        val song = resolveSong(title, artist) ?: return JSONObject().put("error", "lagu tidak ditemukan")
        return verify(song, snippet.trim()).toJson()
    }

    private suspend fun getLyrics(title: String, artist: String): JSONObject {
        val song = resolveSong(title, artist) ?: return JSONObject().put("error", "lagu tidak ditemukan")
        val st = lyrics.load(song, LyricsSource.Auto)
        val out = JSONObject().put("title", song.title).put("artist", song.artist)
        return when (st) {
            is LyricsState.Ready -> out
                .put("lyric_type", lyricType(st))
                .put("source", st.source ?: JSONObject.NULL)
                .put("lyrics", st.lines.joinToString("\n") { it.text }.take(MAX_LYRICS_CHARS))
                .put("note", "untuk dianalisis; jangan disalin utuh ke jawaban (kutipan di bawah 15 kata)")
            is LyricsState.Instrumental -> out.put("note", "lagu instrumental, tidak ada lirik")
            else -> out.put("note", "lirik tidak tersedia di layanan lirik")
        }
    }

    private class Verdict(
        val song: SongItem,
        val label: String, // exact | partial | none | no_lyrics
        val score: Double,
        val matchedLine: String?,
        val lyricType: String?,
        val source: String?,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("title", song.title)
            .put("artist", song.artist)
            .put("duration_sec", song.durationSec)
            .put("match", label)
            .put("match_score", Math.round(score * 100) / 100.0)
            .also { o ->
                matchedLine?.let { o.put("matched_line", it) }
                lyricType?.let { o.put("lyric_type", it) }
                source?.let { o.put("lyric_source", it) }
            }
    }

    private suspend fun verify(song: SongItem, snippet: String): Verdict {
        val st = try {
            lyrics.load(song, LyricsSource.Auto)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (st !is LyricsState.Ready) return Verdict(song, "no_lyrics", 0.0, null, null, null)
        val m = match(snippet, st.lines.map { it.text })
        return Verdict(song, m.label, m.score, m.line, lyricType(st), st.source)
    }

    private fun lyricType(st: LyricsState.Ready): String = when {
        st.lines.any { it.words.isNotEmpty() } -> "per_word"
        st.synced -> "per_line"
        else -> "plain"
    }

    /** Cari lagu YouTube Music untuk judul + artis; pilih yang judulnya paling mirip, kalau tidak ada hasil pertama. */
    private suspend fun resolveSong(title: String, artist: String): SongItem? {
        val t = title.trim()
        if (t.isEmpty()) return null
        val results = innerTube.search((t + " " + artist.trim()).trim())
        val low = t.lowercase()
        val a = artist.trim().lowercase()
        return results.firstOrNull { it.title.lowercase().contains(low) && (a.isEmpty() || it.artist.lowercase().contains(a)) }
            ?: results.firstOrNull { it.title.lowercase().contains(low) }
            ?: results.firstOrNull()
    }

    // ------------------------------------------------------------------ pencocokan lirik

    private class Match(val label: String, val score: Double, val line: String?)

    private fun words(s: String): List<String> = WORD.findAll(s.lowercase()).map { it.value }.toList()

    /**
     * Cocokkan [snippet] dengan baris-baris lirik: "exact" bila urutan kata potongan muncul utuh, "partial" bila
     * sebagian besar pasangan kata berurutan (bigram) muncul, selain itu "none".
     */
    private fun match(snippet: String, lines: List<String>): Match {
        val sw = words(snippet)
        if (sw.isEmpty() || lines.isEmpty()) return Match("none", 0.0, null)
        val lineWords = lines.map { words(it) }
        val all = lineWords.flatten()
        val joined = " " + all.joinToString(" ") + " "
        val exact = joined.contains(" " + sw.joinToString(" ") + " ")

        val swSet = sw.toSet()
        var bestIdx = -1
        var bestHits = 0
        lineWords.forEachIndexed { i, lw ->
            val hits = lw.count { it in swSet }
            if (hits > bestHits) { bestHits = hits; bestIdx = i }
        }
        val line = if (bestIdx >= 0 && bestHits >= minOf(2, sw.size)) lines[bestIdx].trim().take(120) else null
        if (exact) return Match("exact", 1.0, line)

        val lyricSet = all.toHashSet()
        val wordCov = sw.count { it in lyricSet }.toDouble() / sw.size
        if (sw.size < 2) return Match("none", wordCov * 0.5, null)
        val lyricBigrams = HashSet<String>()
        for (i in 0 until all.size - 1) lyricBigrams.add(all[i] + " " + all[i + 1])
        val snipBigrams = (0 until sw.size - 1).map { sw[it] + " " + sw[it + 1] }
        val bigramCov = snipBigrams.count { it in lyricBigrams }.toDouble() / snipBigrams.size
        val partial = bigramCov >= 0.6
        return Match(if (partial) "partial" else "none", maxOf(bigramCov, wordCov * 0.5), if (partial) line else null)
    }

    // ------------------------------------------------------------------ JSON

    private fun songJson(s: SongItem): JSONObject =
        JSONObject().put("title", s.title).put("artist", s.artist).put("duration_sec", s.durationSec)

    private fun entityJson(r: CollectionRef): JSONObject {
        val o = JSONObject().put("name", r.title)
        r.subtitle?.takeIf { it.isNotBlank() }?.let { o.put("details", it) }
        return o
    }

    private fun str(desc: String) = JSONObject().put("type", "STRING").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "INTEGER").put("description", desc)

    private fun props(vararg p: Pair<String, JSONObject>): JSONObject {
        val o = JSONObject()
        for ((k, v) in p) o.put(k, v)
        return o
    }

    private fun decl(name: String, desc: String, properties: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", properties)
                    .put("required", JSONArray(required)),
            )

    private companion object {
        const val SEARCH_SONGS = "search_songs"
        const val SEARCH_LYRICS = "search_lyrics"
        const val CHECK_LYRICS = "check_lyrics"
        const val GET_LYRICS = "get_lyrics"
        const val SEARCH_ARTISTS = "search_artists"
        const val SEARCH_ALBUMS = "search_albums"
        const val SEARCH_PLAYLISTS = "search_playlists"
        const val GET_TRACKS = "get_collection_tracks"

        const val MAX_LYRIC_CANDIDATES = 6
        const val LYRIC_TIMEOUT_MS = 25_000L
        const val MAX_LYRICS_CHARS = 4000
        val WORD = Regex("[\\p{L}\\p{N}]+")
    }
}
