package app.tunes.lyrics

import app.tunes.innertube.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import app.tunes.i18n.t
import app.tunes.i18n.userMessage

/**
 * Lirik dari LRCLIB (https://lrclib.net): gratis, tanpa API key, ada lirik tersinkron (format LRC).
 * Hasil disimpan di memori + cache disk (cacheDir/lyrics) supaya lagu yang sama tidak diunduh lagi.
 */
class LyricsRepository(
    cacheDir: File,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
) {
    private val dir = File(cacheDir, "lyrics")
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = HashMap<String, LyricsState>()

    /** Hasil yang sudah ada di memori (tanpa jaringan), atau null. */
    fun cached(videoId: String, source: LyricsSource): LyricsState? =
        synchronized(memory) { memory[memKey(videoId, source)] }

    /** Buang lirik tersimpan (memori + disk) untuk lagu & layanan ini, supaya dimuat ulang dari jaringan. */
    fun invalidate(videoId: String, source: LyricsSource) {
        synchronized(memory) { memory.remove(memKey(videoId, source)) }
        runCatching { fileFor(videoId, source).delete() }
    }

    /** Buang semua lirik tersimpan (memori + disk). */
    fun clearAll() {
        synchronized(memory) { memory.clear() }
        runCatching { dir.deleteRecursively() }
    }

    private fun memKey(videoId: String, source: LyricsSource) = "$videoId|${source.key}"

    suspend fun load(song: SongItem, source: LyricsSource): LyricsState = withContext(Dispatchers.IO) {
        cached(song.videoId, source)?.let { return@withContext it }
        readDisk(song.videoId, source)?.let { state ->
            store(song.videoId, source, state)
            return@withContext state
        }
        try {
            val stored = if (source == LyricsSource.Auto) fetchAuto(song) else fetchFrom(source, song)
            val state = stored?.toState() ?: LyricsState.NotFound
            if (stored != null) writeDisk(song.videoId, source, stored)
            store(song.videoId, source, state)
            state
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Gagal jaringan tidak disimpan, supaya bisa dicoba lagi.
            LyricsState.Error(e.userMessage("Gagal memuat lirik"))
        }
    }

    private fun store(videoId: String, source: LyricsSource, state: LyricsState) = synchronized(memory) {
        if (memory.size > 80) memory.clear()
        memory[memKey(videoId, source)] = state
    }

    // ------------------------------------------------------- pilihan layanan

    private fun fetchFrom(source: LyricsSource, song: SongItem): Stored? = try {
        when (source) {
            LyricsSource.Lrclib -> fetchLrclib(song)?.copy(source = "LRCLIB")
            LyricsSource.BetterLyrics -> fetchBetter(song)?.copy(source = "Better Lyrics")
            LyricsSource.BiniLyrics -> fetchBini(song)?.copy(source = "BiniLyrics")
            LyricsSource.LyricsPlus -> fetchLyricsPlus(song)?.copy(source = "Lyrics Plus")
            LyricsSource.Auto -> null // ditangani fetchAuto
        }
    } catch (e: ServiceRejected) {
        // 401/403 dari layanan mana pun = layanan tidak mau/bisa melayani lagu ini: "Lirik tidak tersedia", bukan error.
        null
    }

    /**
     * Otomatis: semua layanan ditanya bersamaan, lalu hasilnya dinilai dan dipilih yang paling cocok:
     *  1. Lirik yang tidak cocok dengan lagunya dibuang: waktunya melewati durasi lagu, atau isinya berbeda jauh
     *     dari hasil layanan lain yang saling sepakat (lagu lain / salah cari).
     *  2. Lirik per kata yang sehat (waktu kata runtut, selaras dengan baris, hampir semua baris punya kata)
     *     menang atas lirik per baris. Kalau waktu per katanya janggal, dianggap per baris saja (tidak ngebug).
     *  3. Berikutnya lirik per baris, lalu lirik polos; instrumental hanya kalau tidak ada yang lain.
     *  4. Seri: urutan Lyrics Plus, Better Lyrics, BiniLyrics, LRCLIB.
     * Kalau tidak ada hasil sama sekali, null (tetap "Lirik belum tersedia"). Error hanya dilaporkan kalau
     * tidak ada hasil dan ada layanan yang gagal dijangkau.
     */
    private suspend fun fetchAuto(song: SongItem): Stored? = coroutineScope {
        val order = listOf(LyricsSource.LyricsPlus, LyricsSource.BetterLyrics, LyricsSource.BiniLyrics, LyricsSource.Lrclib)
        val results = order.map { src -> async { src to runCatching { fetchFrom(src, song) } } }.awaitAll()
        val found = results.mapNotNull { r -> r.second.getOrNull()?.let { r.first to it } }
        val best = pickBest(song, order, found)
        if (best != null) return@coroutineScope best
        // Ada layanan yang berhasil dijangkau tapi tidak punya lirik, sementara layanan lain gagal:
        // internet jelas ada, jadi penyebabnya lirik tidak tersedia, bukan "Tidak ada koneksi internet".
        if (results.any { it.second.isSuccess }) throw IOException("Lirik tidak tersedia")
        results.firstNotNullOfOrNull { it.second.exceptionOrNull() }?.let { throw it }
        null
    }

    private class Candidate(
        val order: Int,
        val stored: Stored,
        val lines: List<LyricLine>,
        val wordSynced: Boolean,
        val signature: Set<String>,
    ) {
        var suspect = false
        var score = 0.0
    }

    private fun pickBest(song: SongItem, order: List<LyricsSource>, found: List<Pair<LyricsSource, Stored>>): Stored? {
        if (found.isEmpty()) return null
        val real = found.filter { !it.second.instrumental }
        if (real.isEmpty()) return found.first().second

        val cands = real.mapNotNull { (src, st) ->
            val lines = if (st.synced) LrcParser.parse(st.text) else LrcParser.parsePlain(st.text)
            if (lines.isEmpty()) return@mapNotNull null
            val words = st.synced && wordSyncHealthy(lines)
            Candidate(order.indexOf(src), st, lines, words, signatureOf(lines))
        }
        if (cands.isEmpty()) return found.first().second

        val durMs = song.durationSec * 1000L
        for (c in cands) {
            // Lirik tersinkron yang berjalan jauh melewati akhir lagu = lagu / versi lain.
            if (durMs > 0 && c.stored.synced) {
                val last = c.lines.maxOf { it.timeMs }
                if (last > durMs + 15_000L) c.suspect = true
                else if (last < durMs * 0.4) c.score -= 25.0 // terpotong / hanya sebagian
            }
        }
        // Kecocokan isi dengan layanan lain: yang bertentangan dengan beberapa layanan yang saling sepakat = salah lagu.
        if (cands.size >= 3) {
            for (c in cands) {
                val others = cands.filter { it !== c }
                val sims = others.map { jaccard(c.signature, it.signature) }
                val agreeingOthers = others.indices.count { i ->
                    others.indices.any { j -> j != i && jaccard(others[i].signature, others[j].signature) >= 0.5 }
                }
                if (agreeingOthers >= 2 && (sims.maxOrNull() ?: 0.0) < 0.25) c.suspect = true
            }
        }
        for (c in cands) {
            val others = cands.filter { it !== c }
            val agree = if (others.isEmpty()) 0.5 else others.map { jaccard(c.signature, it.signature) }.average()
            c.score += (if (c.stored.synced) 100.0 else 0.0) +
                (if (c.wordSynced) 60.0 else 0.0) +
                agree * 20.0 -
                c.order * 0.5
        }
        val pool = cands.filter { !it.suspect }.ifEmpty { cands }
        return pool.maxByOrNull { it.score }?.stored
    }

    private fun normLine(x: String) = x.lowercase().filter { it.isLetterOrDigit() }

    private fun signatureOf(lines: List<LyricLine>): Set<String> =
        lines.map { normLine(it.text) }.filter { it.length >= 3 }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /**
     * true = waktu per kata layak dipakai: hampir semua baris punya kata, tiap baris rata-rata lebih dari satu
     * potongan, waktu kata runtut dan tidak mundur, mulai dekat waktu barisnya, tidak melewati baris berikutnya
     * terlalu jauh, dan gabungan katanya sama dengan teks barisnya. Lebih dari 10% baris janggal = tidak sehat.
     */
    private fun wordSyncHealthy(lines: List<LyricLine>): Boolean {
        val textLines = lines.filter { it.text.isNotBlank() }
        if (textLines.size < 3) return false
        val withWords = textLines.filter { it.words.isNotEmpty() }
        if (withWords.size < textLines.size * 0.7) return false
        if (withWords.sumOf { it.words.size }.toDouble() / withWords.size < 1.5) return false
        var bad = 0
        for ((idx, l) in lines.withIndex()) {
            if (l.words.isEmpty() || l.text.isBlank()) continue
            val w = l.words
            val next = lines.getOrNull(idx + 1)?.timeMs
            var ok = true
            if (w.first().startMs < l.timeMs - 1_500L || w.first().startMs > l.timeMs + 2_500L) ok = false
            for (i in w.indices) {
                if (w[i].endMs < w[i].startMs) ok = false
                if (i > 0 && w[i].startMs < w[i - 1].startMs) ok = false
            }
            if (next != null && next > l.timeMs && w.last().endMs > next + 3_000L) ok = false
            if (normLine(w.joinToString("") { it.text }) != normLine(l.text)) ok = false
            if (!ok) bad++
        }
        return bad <= withWords.size * 0.1
    }

    // ---------------------------------------------------- Better Lyrics (TTML)

    /**
     * Better Lyrics: API publik mengembalikan TTML; waktu tiap baris diambil dari atribut `begin` pada `<p>`.
     * Catatan: endpoint & format belum bisa diuji dari lingkungan pengembangan; kegagalan dianggap "tidak ada".
     */
    private fun fetchBetter(song: SongItem): Stored? {
        val artists = splitArtists(song.artist)
        val first = artists.firstOrNull().orEmpty()
        val all = artists.joinToString(", ")
        val clean = cleanTitle(song.title, first)
        val raw = song.title.trim()
        val dur = song.durationSec

        // Beberapa percobaan berurutan: durasi lagu dari YouTube Music sering beda beberapa detik dari data
        // Apple Music yang dipakai Better Lyrics, dan nama artis/judul bisa beda penulisan. Berhenti di hasil pertama.
        val attempts = LinkedHashSet<Triple<String, String, Int>>().apply {
            add(Triple(clean, first, dur))
            add(Triple(clean, first, 0))
            if (all.isNotEmpty() && all != first) {
                add(Triple(clean, all, dur))
                add(Triple(clean, all, 0))
            }
            if (raw != clean) {
                add(Triple(raw, first, dur))
                add(Triple(raw, first, 0))
            }
        }
        try {
            for ((title, artist, d) in attempts) {
                fetchBetterOnce(title, artist, d)?.let { return it }
            }
        } catch (e: ServiceRejected) {
            // Server menolak permintaan (401/403): percobaan lain pasti ditolak juga. Anggap lirik tidak tersedia.
        }
        return null
    }

    private fun fetchBetterOnce(title: String, artist: String, durationSec: Int): Stored? {
        val url = "https://lyrics-api.boidu.dev/getLyrics".toHttpUrl().newBuilder()
            .addQueryParameter("s", title)
            .addQueryParameter("a", artist)
            .apply { if (durationSec > 0) addQueryParameter("d", durationSec.toString()) }
            .build()
        val body = requestSoft(url)?.trim().orEmpty()
        if (body.isEmpty()) return null
        // Balasan yang tidak bisa dibaca / bentuknya tak terduga = Better Lyrics tidak punya lirik lagu ini
        // (bukan gagal jaringan), jadi dianggap tidak ditemukan ("Lirik tidak tersedia"), bukan error.
        val lrc = runCatching {
            val ttml = if (body.startsWith("{")) {
                val o = json.parseToJsonElement(body) as? JsonObject
                runCatching { o?.get("ttml")?.jsonPrimitive?.contentOrNull }.getOrNull()
                    ?: runCatching { o?.get("lyrics")?.jsonPrimitive?.contentOrNull }.getOrNull()
            } else body
            ttml?.let { TtmlParser.toLrc(it) }?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return null
        val parsed = runCatching { LrcParser.parse(lrc).isNotEmpty() }.getOrDefault(false)
        return if (parsed) Stored(false, true, lrc) else null
    }

    // ------------------------------------------------------------ Lyrics Plus

    /**
     * Lyrics Plus (backend YouLy+, github.com/ibratabian17/LyricsPlus): GET /v2/lyrics/get?title=&artist=&duration=,
     * tanpa API key, balasan JSON dengan waktu per kata (suku kata) atau per baris. Server punya beberapa mirror;
     * kalau satu mirror tidak bisa dijangkau / menolak, mirror berikutnya dicoba. Balasan diubah ke LRC enhanced.
     * Catatan: endpoint belum bisa diuji dari lingkungan pengembangan; balasan yang tak terduga dianggap "tidak ada".
     */
    private fun fetchLyricsPlus(song: SongItem): Stored? {
        val first = splitArtists(song.artist).firstOrNull().orEmpty()
        val title = cleanTitle(song.title, first)
        val dur = song.durationSec
        // Percobaan kedua tanpa durasi: durasi YouTube Music sering beda beberapa detik dari data sumber.
        val attempts = LinkedHashSet<Int>().apply { add(dur); add(0) }
        for (d in attempts) {
            fetchLyricsPlusOnce(title, first, d)?.let { return it }
        }
        return null
    }

    private fun fetchLyricsPlusOnce(title: String, artist: String, durationSec: Int): Stored? {
        var failure: IOException? = null
        for (base in LYRICS_PLUS_MIRRORS) {
            val url = "$base/v2/lyrics/get".toHttpUrl().newBuilder()
                .addQueryParameter("title", title)
                .apply { if (artist.isNotEmpty()) addQueryParameter("artist", artist) }
                .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
                .build()
            val body = try {
                request(url)
            } catch (e: IOException) {
                // Mirror ini gagal / menolak / kena batas permintaan: coba mirror berikutnya.
                failure = e
                continue
            }
            // 4xx (mis. 404 "Lyrics not found") = layanan tidak punya lirik lagu ini; mirror lain berisi data yang sama.
            if (body.isNullOrBlank()) return null
            val lrc = runCatching { LyricsPlusParser.toLrc(body) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val ok = runCatching { LrcParser.parse(lrc).isNotEmpty() }.getOrDefault(false)
            return if (ok) Stored(false, true, lrc) else null
        }
        throw failure ?: return null
    }

    // ------------------------------------------------------------ BiniLyrics

    /**
     * BiniLyrics (lyrics-api.binimum.org): GET /?track=&artist=&album=&duration= mengembalikan indeks
     * `{ total, results[] }`; tiap hasil punya `lyricsUrl` ke berkas TTML (lyrics-storage.binimum.org/{ISRC}.ttml),
     * yang diunduh lalu diubah ke LRC enhanced. Tanpa API key. Bentuk balasan dibaca longgar (beberapa nama kunci),
     * dan balasan yang tak terduga dianggap "tidak ada".
     */
    private fun fetchBini(song: SongItem): Stored? {
        val artists = splitArtists(song.artist)
        val first = artists.firstOrNull().orEmpty()
        val title = cleanTitle(song.title, first)
        val dur = song.durationSec
        // Percobaan kedua tanpa durasi: durasi YouTube Music sering beda beberapa detik dari data Apple Music.
        val attempts = LinkedHashSet<Int>().apply { add(dur); add(0) }
        try {
            for (d in attempts) {
                fetchBiniOnce(title, first, d)?.let { return it }
            }
        } catch (e: ServiceRejected) {
            // 401/403: percobaan lain pasti ditolak juga. Anggap lirik tidak tersedia.
        }
        return null
    }

    private fun fetchBiniOnce(title: String, artist: String, durationSec: Int): Stored? {
        val index = "https://lyrics-api.binimum.org/".toHttpUrl()
        val url = index.newBuilder()
            .addQueryParameter("track", title)
            .apply { if (artist.isNotEmpty()) addQueryParameter("artist", artist) }
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        val body = request(url)?.trim().orEmpty()
        if (body.isEmpty()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val items: List<JsonObject> = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["results"] ?: root["data"] ?: root["lyrics"]) as? JsonArray
            else -> null
        }?.mapNotNull { it as? JsonObject }.orEmpty()
        if (items.isEmpty()) return null

        fun JsonObject.str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
        fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
        val wantTitle = norm(title)
        val wantArtist = norm(artist)

        // Urutkan kandidat: judul cocok > artis cocok > durasi dekat > ada waktu per kata.
        val ranked = items.mapNotNull { o ->
            val link = o.str("lyricsUrl", "lyrics_url", "ttmlUrl", "url") ?: return@mapNotNull null
            val t = norm(o.str("track", "trackName", "title", "name", "songName").orEmpty())
            val a = norm(o.str("artist", "artistName").orEmpty())
            var score = 0.0
            if (t.isNotEmpty() && wantTitle.isNotEmpty()) {
                score += when {
                    t == wantTitle -> 10.0
                    t.contains(wantTitle) || wantTitle.contains(t) -> 4.0
                    else -> return@mapNotNull null // judul beda = lagu lain
                }
            }
            if (wantArtist.isNotEmpty() && a.isNotEmpty()) {
                score += if (a == wantArtist) 6.0 else if (a.contains(wantArtist) || wantArtist.contains(a)) 3.0 else 0.0
            }
            val d = (o["duration"] as? JsonPrimitive)?.doubleOrNull
            if (durationSec > 0 && d != null) {
                val sec = if (d > 1000) d / 1000.0 else d // milidetik atau detik
                val diff = abs(sec - durationSec)
                if (diff > MAX_DURATION_DIFF_SEC) return@mapNotNull null
                score -= diff
            }
            if (o.toString().contains("rich", ignoreCase = true) || o.toString().contains("word", ignoreCase = true)) score += 2
            score to link
        }.sortedByDescending { it.first }

        // Coba beberapa kandidat teratas: berkas TTML kadang 404 / kosong.
        for ((_, link) in ranked.take(3)) {
            val ttmlUrl = (link.toHttpUrlOrNull() ?: index.resolve(link)) ?: continue
            val ttml = request(ttmlUrl)?.takeIf { it.isNotBlank() } ?: continue
            val lrc = runCatching { TtmlParser.toLrc(ttml) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            if (runCatching { LrcParser.parse(lrc).isNotEmpty() }.getOrDefault(false)) return Stored(false, true, lrc)
        }
        return null
    }

    /**
     * Seperti [request]: semua 4xx selain 408/429 (tidak ada, parameter tidak cocok, 401/403 ditolak server, dst.)
     * dianggap "tidak ditemukan" supaya tampil "Lirik tidak tersedia", bukan layar error. Hanya masalah sementara
     * (timeout, 408, 429, 5xx, jaringan putus) yang dianggap gagal, supaya tombol "Coba lagi" masuk akal.
     */
    private fun requestSoft(url: okhttp3.HttpUrl): String? = request(url)

    // ------------------------------------------------------------- pencarian

    private data class Hit(
        val trackName: String,
        val artistName: String,
        val duration: Double?,
        val instrumental: Boolean,
        val synced: String?,
        val plain: String?,
    )

    private fun fetchLrclib(song: SongItem): Stored? {
        val artists = splitArtists(song.artist)
        val first = artists.firstOrNull().orEmpty()
        val title = cleanTitle(song.title, first)
        val durationSec = song.durationSec

        // 1) Pencocokan tepat (judul + artis + durasi).
        for (artist in listOfNotNull(first.takeIf { it.isNotEmpty() }, artists.joinToString(", ").takeIf { artists.size > 1 })) {
            val hit = get(title, artist, durationSec)
            if (hit != null) hit.toStored()?.let { return it }
        }

        // 2) Pencarian longgar, lalu pilih kandidat terbaik berdasarkan durasi dan keberadaan lirik tersinkron.
        val searches = ArrayList<List<Hit>>()
        if (first.isNotEmpty()) searches.add(search("track_name" to title, "artist_name" to first))
        searches.add(search("q" to listOf(title, first).filter { it.isNotEmpty() }.joinToString(" ")))
        for (hits in searches) {
            val best = pickBest(hits, durationSec, title, first)
            if (best != null) best.toStored()?.let { return it }
        }
        return null
    }

    private fun get(title: String, artist: String, durationSec: Int): Hit? {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        val body = request(url) ?: return null
        return (json.parseToJsonElement(body) as? JsonObject)?.toHit()
    }

    private fun search(vararg params: Pair<String, String>): List<Hit> {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val body = request(url) ?: return emptyList()
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.toHit() }
    }

    /** Penolakan server (401/403) - dipakai agar pencarian berulang ke layanan yang sama bisa dihentikan. */
    private class ServiceRejected(val code: Int) : IOException("HTTP $code")

    /**
     * null = tidak ada (4xx permanen: 400, 401, 403, 404, 410, 422, dst.). Hanya 408, 429, 5xx dan
     * gangguan jaringan yang dianggap gagal sementara (dilempar sebagai IOException).
     */
    private fun request(url: okhttp3.HttpUrl): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Olyuno/1.0.1 (personal Android player)")
            .build()
        http.newCall(req).execute().use { resp ->
            val c = resp.code
            if (resp.isSuccessful) return resp.body?.string()
            if (c == 401 || c == 403) throw ServiceRejected(c)
            if (c in 400..499 && c != 408 && c != 429) return null
            throw IOException(t("Server lirik membalas HTTP %d", c))
        }
    }

    private fun JsonObject.toHit(): Hit {
        fun str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
        return Hit(
            trackName = str("trackName").orEmpty(),
            artistName = str("artistName").orEmpty(),
            duration = this["duration"]?.jsonPrimitive?.doubleOrNull,
            instrumental = this["instrumental"]?.jsonPrimitive?.booleanOrNull == true,
            synced = str("syncedLyrics"),
            plain = str("plainLyrics"),
        )
    }

    private fun pickBest(hits: List<Hit>, durationSec: Int, title: String, artist: String): Hit? {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val wantTitle = norm(title)
        val wantArtist = norm(artist)
        var best: Hit? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (h in hits) {
            val hasLyrics = h.instrumental || !h.synced.isNullOrBlank() || !h.plain.isNullOrBlank()
            if (!hasLyrics) continue
            var score = 0.0
            if (durationSec > 0 && h.duration != null) {
                val diff = abs(h.duration - durationSec)
                // Beda durasi besar = versi lain; sinkronisasinya pasti meleset.
                if (diff > MAX_DURATION_DIFF_SEC) continue
                score -= diff * 2
            }
            val t = norm(h.trackName)
            // Judul harus mirip, supaya tidak salah menampilkan lirik lagu lain milik artis yang sama.
            if (t == wantTitle) score += 10
            else if (t.isNotEmpty() && wantTitle.isNotEmpty() && (t.contains(wantTitle) || wantTitle.contains(t))) score += 4
            else continue
            if (wantArtist.isNotEmpty()) {
                val a = norm(h.artistName)
                if (a == wantArtist) score += 6 else if (a.contains(wantArtist) || wantArtist.contains(a)) score += 3
            }
            if (!h.synced.isNullOrBlank()) score += 5
            if (score > bestScore) {
                bestScore = score
                best = h
            }
        }
        return best
    }

    // --------------------------------------------------------------- cache

    private data class Stored(
        val instrumental: Boolean,
        val synced: Boolean,
        val text: String,
        val source: String? = null,
    ) {
        fun toState(): LyricsState {
            if (instrumental) return LyricsState.Instrumental
            val lines = if (synced) LrcParser.parse(text) else LrcParser.parsePlain(text)
            return if (lines.isEmpty()) LyricsState.NotFound else LyricsState.Ready(lines, synced, source)
        }
    }

    private fun Hit.toStored(): Stored? {
        if (instrumental) return Stored(true, false, "")
        val s = synced?.takeIf { it.isNotBlank() }
        if (s != null && LrcParser.parse(s).isNotEmpty()) return Stored(false, true, s)
        val p = plain?.takeIf { it.isNotBlank() }
        if (p != null) return Stored(false, false, p)
        return null
    }

    private fun fileFor(videoId: String, source: LyricsSource) =
        File(dir, videoId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "__" + source.key + ".json")

    private fun readDisk(videoId: String, source: LyricsSource): LyricsState? = runCatching {
        val f = fileFor(videoId, source)
        if (!f.isFile) return@runCatching null
        val o = json.parseToJsonElement(f.readText()).jsonObject
        // Cache versi lama tidak punya waktu per kata: abaikan supaya diunduh ulang.
        if (o["v"]?.jsonPrimitive?.intOrNull != CACHE_VERSION) return@runCatching null
        Stored(
            instrumental = o["instrumental"]?.jsonPrimitive?.booleanOrNull == true,
            synced = o["synced"]?.jsonPrimitive?.booleanOrNull == true,
            text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            source = o["source"]?.jsonPrimitive?.contentOrNull,
        ).toState().takeIf { it !is LyricsState.NotFound }
    }.getOrNull()

    private fun writeDisk(videoId: String, source: LyricsSource, s: Stored) {
        runCatching {
            dir.mkdirs()
            val o = buildJsonObject {
                put("v", CACHE_VERSION)
                put("instrumental", s.instrumental)
                put("synced", s.synced)
                put("text", s.text)
                s.source?.let { put("source", it) }
            }
            fileFor(videoId, source).writeText(o.toString())
        }
    }

    companion object {
        private const val MAX_DURATION_DIFF_SEC = 8.0

        /** Naik tiap format cache berubah (3 = ada waktu per kata). */
        private const val CACHE_VERSION = 4

        /** "A, B", "A & B", "A x B", "A - Topic" -> ["A", "B"]. */
        private val LYRICS_PLUS_MIRRORS = listOf(
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app",
        )

        internal fun splitArtists(raw: String): List<String> =
            raw.replace(Regex("""\s+-\s+Topic$""", RegexOption.IGNORE_CASE), "")
                .split(Regex("""\s*(?:,|&|\sx\s|\sfeat\.?\s|\sft\.?\s)\s*""", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        /** Buang embel-embel judul YouTube: "(Official Video)", "[Lyrics]", "feat. X", "Artis - Judul", dsb. */
        internal fun cleanTitle(raw: String, artist: String): String {
            var t = raw
            t = t.replace(
                Regex(
                    """[(\[【（][^)\]】）]*(official|video|audio|lyric|lirik|visuali[sz]er|m/?v|\bhd\b|\bhq\b|4k|remaster|klip|clip|feat|\bft\.)[^)\]】）]*[)\]】）]""",
                    RegexOption.IGNORE_CASE,
                ),
                " ",
            )
            t = t.replace(Regex("""\s*[-|–]\s*(official|lyrics?|lirik|audio|video).*$""", RegexOption.IGNORE_CASE), "")
            t = t.replace(Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE), "")

            // Format "Artis - Judul": ambil bagian judul kalau bagian kiri adalah nama artisnya.
            val dash = t.indexOf(" - ")
            if (dash > 0 && artist.isNotEmpty()) {
                val left = t.substring(0, dash).trim()
                if (left.contains(artist, ignoreCase = true) || artist.contains(left, ignoreCase = true)) {
                    t = t.substring(dash + 3)
                }
            }
            return t.replace(Regex("""\s+"""), " ").trim().ifEmpty { raw.trim() }
        }
    }
}

/**
 * Mengubah TTML (mis. dari Better Lyrics) menjadi teks LRC. Kalau `<p>` berisi `<span begin end>` per kata /
 * suku kata, hasilnya LRC "enhanced" dengan waktu tiap potongan: "[mm:ss.xxx]<mm:ss.xxx>kata <mm:ss.xxx>lagi<mm:ss.xxx>"
 * (dipakai untuk sorotan per huruf). Tanpa span berwaktu: satu baris biasa "[mm:ss.xxx]teks".
 */
internal object TtmlParser {
    private val P = Regex("""<p\b([^>]*)>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val BEGIN = Regex("""\bbegin\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val END = Regex("""\bend\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("""<[^>]+>""")
    private val PIECE = Regex("""<span\b([^>]*)>([^<]*)</span>|<[^>]+>|([^<]+)""", RegexOption.IGNORE_CASE)
    private val WS = Regex("""\s+""")

    private class Seg(var begin: Long?, var end: Long?, var text: String)

    private fun stamp(ms: Long, open: Char, close: Char): String {
        val min = ms / 60_000
        val sec = (ms / 1_000) % 60
        val frac = ms % 1_000
        return buildString {
            append(open).append(min.toString().padStart(2, '0')).append(':')
                .append(sec.toString().padStart(2, '0')).append('.')
                .append(frac.toString().padStart(3, '0')).append(close)
        }
    }

    fun toLrc(ttml: String): String {
        val sb = StringBuilder()
        for (m in P.findAll(ttml)) {
            val begin = BEGIN.find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            val ms = parseTime(begin) ?: continue
            val pEnd = END.find(m.groupValues[1])?.groupValues?.get(1)?.let { parseTime(it) }
            val inner = m.groupValues[2]

            // Pecah isi <p> menjadi potongan berwaktu (span) dan teks lepas (spasi / teks tanpa waktu).
            val segs = ArrayList<Seg>()
            var carry = "" // teks lepas sebelum potongan berwaktu pertama
            for (piece in PIECE.findAll(inner)) {
                val spanAttrs = piece.groups[1]?.value
                if (spanAttrs != null) {
                    if (spanAttrs.contains("x-translation", true) || spanAttrs.contains("x-roman", true)) continue
                    val text = WS.replace(decode(piece.groupValues[2]), " ")
                    val b = BEGIN.find(spanAttrs)?.groupValues?.get(1)?.let { parseTime(it) }
                    val e = END.find(spanAttrs)?.groupValues?.get(1)?.let { parseTime(it) }
                    if (b != null) {
                        segs += Seg(b, e, carry + text)
                        carry = ""
                    } else if (segs.isNotEmpty()) {
                        segs.last().text += text
                    } else carry += text
                } else if (piece.groups[3] != null) {
                    val text = WS.replace(decode(piece.groupValues[3]), " ")
                    if (segs.isNotEmpty()) {
                        val last = segs.last()
                        // Spasi antar kata menempel di ujung potongan sebelumnya (tanpa menggandakan spasi).
                        last.text += if (last.text.endsWith(" ") && text.startsWith(" ")) text.drop(1) else text
                    } else carry += text
                }
            }

            val timed = segs.filter { it.text.isNotEmpty() }
            val lineText = WS.replace(decode(inner.replace(TAGS, "")), " ").trim()
            sb.append(stamp(ms, '[', ']'))
            if (timed.isEmpty()) {
                sb.append(lineText).append('\n')
                continue
            }
            for ((i, s) in timed.withIndex()) {
                sb.append(stamp(s.begin!!, '<', '>')).append(s.text)
                val next = timed.getOrNull(i + 1)
                val end = s.end
                // Ada jeda sebelum potongan berikutnya: tulis akhir potongan ini supaya sorotan berhenti tepat.
                if (next != null && end != null && end + 40 < next.begin!!) sb.append(stamp(end, '<', '>'))
            }
            val lastEnd = timed.last().end ?: pEnd
            if (lastEnd != null) sb.append(stamp(lastEnd, '<', '>'))
            sb.append('\n')
        }
        return sb.toString()
    }

    /** "12.5", "1:02.345", "1:02:03.5", "12.5s" -> milidetik. */
    private fun parseTime(raw: String): Long? {
        val t = raw.trim().removeSuffix("s")
        val parts = t.split(':')
        if (parts.size > 3) return null
        var seconds = 0.0
        for (part in parts) seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        return (seconds * 1000).toLong()
    }

    private fun decode(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
}


/**
 * Mengubah balasan Lyrics Plus (/v2/lyrics/get) ke LRC "enhanced" yang dipahami [LrcParser]:
 * "[mm:ss.xxx]<mm:ss.xxx>kata <mm:ss.xxx>kata<mm:ss.xxx>". Semua waktu di JSON dalam milidetik.
 * Lirik per baris saja (tanpa syllabus) menjadi LRC biasa.
 */
internal object LyricsPlusParser {
    private val json = Json { ignoreUnknownKeys = true }

    private class Piece(val begin: Long, val end: Long?, val text: String)

    private fun stamp(ms: Long, open: Char, close: Char): String {
        val v = ms.coerceAtLeast(0L)
        return buildString {
            append(open).append((v / 60_000).toString().padStart(2, '0')).append(':')
                .append(((v / 1_000) % 60).toString().padStart(2, '0')).append('.')
                .append((v % 1_000).toString().padStart(3, '0')).append(close)
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.doubleOrNull?.toLong()

    fun toLrc(body: String): String? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        if (root.str("type").equals("None", ignoreCase = true)) return null
        val lines = root["lyrics"] as? JsonArray ?: return null
        val sb = StringBuilder()
        for (el in lines) {
            val line = el as? JsonObject ?: continue
            val start = line.num("time") ?: continue
            val lineText = line.str("text").orEmpty().replace(Regex("""\s+"""), " ").trim()

            val pieces = ((line["syllabus"] as? JsonArray) ?: emptyList<kotlinx.serialization.json.JsonElement>())
                .mapNotNull { s ->
                    val o = s as? JsonObject ?: return@mapNotNull null
                    if ((o["isBackground"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
                    val text = o.str("text") ?: return@mapNotNull null
                    val b = o.num("time") ?: return@mapNotNull null
                    Piece(b, o.num("duration")?.let { b + it }, text)
                }
                .sortedBy { it.begin }

            sb.append(stamp(start, '[', ']'))
            if (pieces.isEmpty() || lineText.isEmpty()) {
                sb.append(lineText).append('\n')
                continue
            }

            // Spasi antar kata diambil dari teks baris (potongan dari server belum tentu membawa spasi).
            var cursor = 0
            val timed = ArrayList<Piece>()
            for (p in pieces) {
                val core = p.text.trim()
                if (core.isEmpty()) continue
                val idx = lineText.indexOf(core, cursor)
                val text = if (idx >= 0) {
                    cursor = idx + core.length
                    if (cursor < lineText.length && lineText[cursor] == ' ') core + " " else core
                } else p.text
                timed += Piece(p.begin, p.end, text)
            }
            if (timed.isEmpty()) {
                sb.append(lineText).append('\n')
                continue
            }
            for ((i, p) in timed.withIndex()) {
                sb.append(stamp(p.begin, '<', '>')).append(p.text)
                val next = timed.getOrNull(i + 1)
                val end = p.end
                // Ada jeda sebelum potongan berikutnya: tulis akhir potongan ini supaya sorotan berhenti tepat.
                if (next != null && end != null && end + 40 < next.begin) sb.append(stamp(end, '<', '>'))
            }
            val lastEnd = timed.last().end ?: line.num("duration")?.let { start + it }
            if (lastEnd != null) sb.append(stamp(lastEnd, '<', '>'))
            sb.append('\n')
        }
        return sb.toString()
    }
}
