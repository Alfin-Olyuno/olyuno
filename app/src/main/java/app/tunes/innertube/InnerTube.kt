package app.tunes.innertube

import kotlinx.coroutines.CancellationException
import app.tunes.data.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject


import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import app.tunes.i18n.t

/**
 * Klien InnerTube minimal: search, radio (antrian "next"), dan resolve URL audio.
 * Tidak ada parsing berbasis kelas data; semua lewat pencarian di pohon JSON supaya tahan
 * terhadap perubahan struktur kecil di sisi YouTube.
 */
class InnerTube(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val hl: String = "en",
    private val gl: String = "ID",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    @Volatile
    private var visitorData: String? = null

    // ---------------------------------------------------------------- HTTP

    internal suspend fun post(
        client: ClientConfig,
        endpoint: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: JsonObjectBuilder.() -> Unit,
    ): JsonObject = withContext(Dispatchers.IO) {
        val vd = visitorData
        val payload = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", hl)
                    put("gl", gl)
                    if (vd != null) put("visitorData", vd)
                    client.extra.forEach { (k, v) -> put(k, v) }
                })
            })
            body()
        }

        val request = Request.Builder()
            .url("https://${client.host}/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.id.toString())
            .header("X-YouTube-Client-Version", client.version)
            .apply {
                if (client.host.endsWith("youtube.com")) {
                    header("Origin", "https://${client.host}")
                    header("Referer", "https://${client.host}/")
                }
            }
            .apply { if (vd != null) header("X-Goog-Visitor-Id", vd) }
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw InnerTubeException(t("HTTP %d dari %s (%s)", resp.code, endpoint, client.name))
            }
            val root = json.parseToJsonElement(text).jsonObject
            if (visitorData == null) {
                visitorData = root["responseContext"].path("visitorData").text
            }
            root
        }
    }

    /** GET sederhana, mengembalikan kode HTTP-nya (dipakai untuk mencatat riwayat putar ke akun). */
    internal suspend fun getStatus(url: String, headers: Map<String, String>, userAgent: String): Int =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            http.newCall(req).execute().use { it.code }
        }

    // -------------------------------------------------------------- Search

    suspend fun search(query: String): List<SongItem> {
        val root = post(Clients.WEB_REMIX, "search") { put("query", query) }

        // Kartu "top result" (kalau ada) ditaruh paling atas.
        val top = root.findAll("musicCardShelfRenderer").mapNotNull { card ->
            val id = card.path("title", "runs", 0, "navigationEndpoint", "watchEndpoint", "videoId").text
                ?: return@mapNotNull null
            val title = card.path("title", "runs").objects().joinToString("") { it["text"].text.orEmpty() }
            buildSong(id, title, card.path("subtitle", "runs").objects(), card.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        }

        val items = root.findAll("musicResponsiveListItemRenderer").mapNotNull { item ->
            val id = item.path("playlistItemData", "videoId").text
                ?: item.path(
                    "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
                    "playNavigationEndpoint", "watchEndpoint", "videoId",
                ).text
                ?: return@mapNotNull null
            val cols = item["flexColumns"] as? JsonArray ?: return@mapNotNull null
            fun runs(i: Int) = cols.getOrNull(i)
                .path("musicResponsiveListItemFlexColumnRenderer", "text", "runs").objects()
            val title = runs(0).joinToString("") { it["text"].text.orEmpty() }
            buildSong(id, title, runs(1), item.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        }

        return (top + items).distinctBy { it.videoId }
    }

    internal fun buildSong(
        videoId: String,
        title: String,
        subRuns: List<JsonObject>,
        thumbs: JsonElement?,
    ): SongItem? {
        if (title.isBlank()) return null
        val parts = subRuns.filter { it["text"].text != " • " }
        val texts = parts.map { it["text"].text.orEmpty() }

        val duration = texts.lastOrNull()?.let(::parseDuration) ?: 0

        val artistRuns = parts.filter { r ->
            val type = r.path(
                "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig", "pageType",
            ).text
            type == "MUSIC_PAGE_TYPE_ARTIST" || type == "MUSIC_PAGE_TYPE_USER_CHANNEL"
        }
        val artist = if (artistRuns.isNotEmpty()) {
            artistRuns.joinToString(", ") { it["text"].text.orEmpty() }
        } else {
            // Tanpa label tipe di depan ("Song"/"Video"), ambil teks pertama yang bukan durasi.
            texts.drop(1).firstOrNull { parseDuration(it) == null }.orEmpty()
        }

        val thumb = (thumbs as? JsonArray)?.lastOrNull().path("url").text?.hiRes()
        return SongItem(videoId, title, artist, duration, thumb)
    }

    // --------------------------------------------------------------- Radio

    /** Antrian "radio" ala YouTube Music berdasarkan satu lagu (dipakai untuk autoplay). */
    suspend fun radio(videoId: String): List<SongItem> {
        val root = post(Clients.WEB_REMIX, "next") {
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
        }
        return root.findAll("playlistPanelVideoRenderer").mapNotNull { r ->
            val id = r["videoId"].text ?: return@mapNotNull null
            val title = r.path("title", "runs").objects().joinToString("") { it["text"].text.orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val artist = r.path("longBylineText", "runs").objects().firstOrNull()?.get("text").text.orEmpty()
            val duration = r.path("lengthText", "runs", 0, "text").text?.let(::parseDuration) ?: 0
            val thumb = (r.path("thumbnail", "thumbnails") as? JsonArray)?.lastOrNull().path("url").text?.hiRes()
            SongItem(id, title, artist, duration, thumb)
        }.distinctBy { it.videoId }
    }

    // -------------------------------------------------------------- Stream

    /**
     * Ambil URL audio langsung. Mencoba [Clients.PLAYER_CHAIN] berurutan.
     *
     * Sejak 2026 YouTube meminta PO Token untuk format adaptif (audio-only) di client ANDROID_VR;
     * URL-nya tetap keluar tapi googlevideo menjawab 403 (ERROR_CODE_IO_BAD_HTTP_STATUS). Hanya
     * format progresif (mis. itag 18: mp4 360p + AAC) yang masih lolos tanpa token. Jadi tiap kandidat
     * URL dites dulu dengan request kecil, dan kalau ditolak dipakai kandidat berikutnya.
     */
    suspend fun resolveStream(videoId: String, quality: AudioQuality = AudioQuality.High): AudioStream {
        // Kumpulkan hasil tiap client supaya pesan error menunjukkan semuanya, bukan hanya yang terakhir.
        val report = ArrayList<String>()
        var lastError: Throwable? = null
        for (client in Clients.PLAYER_CHAIN) {
            try {
                val root = post(client, "player") {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }
                val status = root.path("playabilityStatus", "status").text
                if (status != "OK") {
                    val reason = root.path("playabilityStatus", "reason").text.orEmpty()
                    report += "${client.name}: $status ${reason.take(40)}".trim()
                    continue
                }
                val candidates = audioCandidates(root, client, quality)
                if (candidates.isEmpty()) {
                    report += "${client.name}: tanpa URL"
                    continue
                }
                for (c in candidates) {
                    val code = probe(c)
                    if (code in 200..299) return c
                    report += "${client.name}/${c.itag}: HTTP $code"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                report += "${client.name}: ${e.message?.take(50)}"
            }
        }
        throw InnerTubeException(t("Semua client ditolak -> %s", report.joinToString("; ")), lastError)
    }

    /** Request 2 byte pertama untuk memastikan URL benar-benar bisa diputar (bukan 403). */
    private suspend fun probe(stream: AudioStream): Int = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(withRangeParam(stream.url, 0))
                .header("User-Agent", stream.userAgent)
                .build()
            // Hanya header yang dibaca; body tidak diunduh (use{} menutup koneksi).
            http.newCall(req).execute().use { it.code }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            -1
        }
    }

    /** Urutan: audio-only dengan bitrate tertinggi dulu (atau terendah dulu untuk kualitas Rendah), lalu format progresif (audio+video) sebagai cadangan. */
    private fun audioCandidates(root: JsonObject, client: ClientConfig, quality: AudioQuality): List<AudioStream> {
        val sd = root["streamingData"] as? JsonObject ?: return emptyList()
        val ttlSec = sd["expiresInSeconds"].text?.toLongOrNull() ?: 21_000L
        val expiresAt = System.currentTimeMillis() + (ttlSec - 120).coerceAtLeast(60) * 1000

        // Panjang file dari JSON dititipkan sebagai fragment (#clen=) bila URL tidak memuat clen sendiri.
        fun urlOf(f: JsonObject): String {
            val u = f["url"].text!!
            val len = f["contentLength"].text?.toLongOrNull()
            return if (len != null && !u.contains("clen=")) "$u#clen=$len" else u
        }

        // Kekerasan lagu (untuk Normalisasi volume): per format kalau ada, kalau tidak dari config pemutar.
        val configDb = root.path("playerConfig", "audioConfig", "loudnessDb").text?.toFloatOrNull()

        fun toStream(f: JsonObject) = AudioStream(
            url = urlOf(f),
            mimeType = f["mimeType"].text.orEmpty(),
            bitrate = f["bitrate"].int ?: 0,
            userAgent = client.userAgent,
            expiresAtMs = expiresAt,
            clientName = client.name,
            itag = f["itag"].int ?: 0,
            loudnessDb = f["loudnessDb"].text?.toFloatOrNull() ?: configDb,
        )

        val adaptive = sd["adaptiveFormats"].objects()
            .filter { it["mimeType"].text?.startsWith("audio/") == true && it["url"].text != null }
            // Tinggi (dan Otomatis yang sudah diputuskan Tinggi) = bitrate terbesar dulu; Rendah = terkecil dulu.
            .let { l ->
                if (quality == AudioQuality.Low) l.sortedBy { it["bitrate"].int ?: 0 }
                else l.sortedByDescending { it["bitrate"].int ?: 0 }
            }
            .map(::toStream)

        // Progresif: ada audio + video dalam satu file. Pilih bitrate terendah (hemat data).
        val progressive = sd["formats"].objects()
            .filter { it["url"].text != null }
            .sortedBy { it["bitrate"].int ?: 0 }
            .map(::toStream)

        // Probe hanya audio terbaik (kalau 403, yang lain di grup yang sama hampir pasti ikut 403).
        return adaptive.take(2) + progressive
    }
}
