package app.tunes.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Hasil Olyuno AI satu lagu. [background] null = AI tidak cukup yakin soal latar belakang. */
data class LyricsAnalysis(
    val summary: String,
    val themes: List<String>,
    val sections: List<Section>,
    val background: String?,
) {
    data class Section(val name: String, val meaning: String)

    fun toJson(): JSONObject = JSONObject()
        .put("summary", summary)
        .put("themes", JSONArray(themes))
        .put("sections", JSONArray(sections.map { JSONObject().put("name", it.name).put("meaning", it.meaning) }))
        .put("background", background ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): LyricsAnalysis {
            val themes = o.optJSONArray("themes")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
            val sections = o.optJSONArray("sections")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val s = a.optJSONObject(i) ?: return@mapNotNull null
                    val m = s.optString("meaning")
                    if (m.isBlank()) null else Section(s.optString("name"), m)
                }
            }.orEmpty()
            val bg = if (o.isNull("background")) null else o.optString("background").trim().takeIf { it.isNotEmpty() }
            val summary = o.optString("summary").trim()
            require(summary.isNotEmpty()) { "ringkasan kosong" }
            return LyricsAnalysis(summary, themes, sections, bg)
        }
    }
}

/** Jenis kegagalan, dipakai UI untuk memilih pesan dan tombol yang pas. */
enum class AiErrorKind { NoKey, BadKey, ModelNotFound, Quota, Network, Blocked, Server, Parse }

class AiException(val kind: AiErrorKind, message: String? = null) : Exception(message)

class LyricsAnalyzer(cacheDir: File) {
    private val dir = File(cacheDir, "lyrics_analysis").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun cacheFile(videoId: String, lang: String, modelId: String): File {
        val safe = "${videoId}_${lang}_$modelId".replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.json")
    }

    fun cached(videoId: String, lang: String, modelId: String): LyricsAnalysis? = runCatching {
        val f = cacheFile(videoId, lang, modelId)
        if (!f.exists()) null else LyricsAnalysis.fromJson(JSONObject(f.readText()))
    }.getOrNull()

    fun clearCache(videoId: String, lang: String, modelId: String) {
        runCatching { cacheFile(videoId, lang, modelId).delete() }
    }

    /** Analisis satu lagu. [lyrics] boleh null (lirik tidak ditemukan): AI diminta lebih hati-hati. */
    suspend fun analyze(
        deviceId: String,
        modelId: String,
        videoId: String,
        title: String,
        artist: String,
        lyrics: String?,
        lang: String,
        useCache: Boolean = true,
    ): LyricsAnalysis = withContext(Dispatchers.IO) {
        if (deviceId.isBlank()) throw AiException(AiErrorKind.NoKey)
        if (useCache) cached(videoId, lang, modelId)?.let { return@withContext it }

        val prompt = buildString {
            append("Lagu: \"").append(title).append("\" oleh ").append(artist).append(".\n")
            if (lyrics.isNullOrBlank()) {
                append("Lirik lagu TIDAK tersedia. Jelaskan seperlunya dari judul dan artis saja, jujur soal ketidakpastian, ")
                append("dan kosongkan latar belakang kalau tidak yakin.\n")
            } else {
                append("Lirik (bahan analisis, jangan disalin ulang):\n").append(lyrics.take(MAX_LYRICS)).append('\n')
            }
            append("\nBahasa jawaban: ").append(lang).append('.')
        }
        val body = JSONObject()
            .put("systemInstruction", textContent(null, SYSTEM))
            .put("contents", JSONArray().put(textContent("user", prompt)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.6)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", SCHEMA),
            )

        var last: AiException? = null
        repeat(2) { attempt ->
            try {
                val text = call(deviceId, modelId, body)
                val analysis = LyricsAnalysis.fromJson(JSONObject(stripFences(text)))
                runCatching { cacheFile(videoId, lang, modelId).writeText(analysis.toJson().toString()) }
                return@withContext analysis
            } catch (e: AiException) {
                last = e
                // Coba ulang otomatis sekali hanya untuk gangguan sementara / JSON rusak.
                if (attempt == 1 || e.kind !in RETRYABLE) throw e
            } catch (e: org.json.JSONException) {
                last = AiException(AiErrorKind.Parse)
                if (attempt == 1) throw last!!
            } catch (e: IllegalArgumentException) {
                last = AiException(AiErrorKind.Parse)
                if (attempt == 1) throw last!!
            }
        }
        throw last ?: AiException(AiErrorKind.Server)
    }

    /**
     * Tanya lanjutan. [history] = pasangan (dariPengguna, teks) setelah ringkasan awal; jawaban berupa teks biasa.
     * Konteks yang dikirim ringkas (judul, artis, ringkasan, tema) supaya hemat token.
     */
    suspend fun ask(
        deviceId: String,
        modelId: String,
        title: String,
        artist: String,
        analysis: LyricsAnalysis,
        history: List<Pair<Boolean, String>>,
        question: String,
        lang: String,
    ): String = withContext(Dispatchers.IO) {
        if (deviceId.isBlank()) throw AiException(AiErrorKind.NoKey)
        val context = "Kita sedang membahas lagu \"$title\" oleh $artist. Ringkasan makna yang sudah kamu berikan: " +
            analysis.summary + " Tema: " + analysis.themes.joinToString(", ") + ". Bahasa jawaban: $lang."
        val contents = JSONArray().put(textContent("user", context)).put(textContent("model", "Baik, silakan bertanya."))
        history.takeLast(MAX_TURNS).forEach { (fromUser, text) ->
            contents.put(textContent(if (fromUser) "user" else "model", text.take(1500)))
        }
        contents.put(textContent("user", question.take(800)))
        val body = JSONObject()
            .put("systemInstruction", textContent(null, SYSTEM_CHAT))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.7))
        try {
            call(deviceId, modelId, body).trim()
        } catch (e: AiException) {
            if (e.kind in RETRYABLE) call(deviceId, modelId, body).trim() else throw e
        }
    }

    /**
     * Obrolan bebas tentang musik (lagu, artis, makna lirik, rekomendasi). [history] = pasangan (dariPengguna, teks)
     * sebelum [question]; jawaban berupa teks biasa dengan format markdown ringan.
     */
    suspend fun chat(
        deviceId: String,
        modelId: String,
        history: List<Pair<Boolean, String>>,
        question: String,
        lang: String,
    ): String = withContext(Dispatchers.IO) {
        if (deviceId.isBlank()) throw AiException(AiErrorKind.NoKey)
        val contents = JSONArray()
        history.takeLast(MAX_CHAT_TURNS).forEach { (fromUser, text) ->
            contents.put(textContent(if (fromUser) "user" else "model", text.take(3000)))
        }
        contents.put(textContent("user", question.take(1500)))
        val body = JSONObject()
            .put("systemInstruction", textContent(null, SYSTEM_GENERAL + " Bahasa jawaban: " + lang + "."))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.7))
        try {
            call(deviceId, modelId, body).trim()
        } catch (e: AiException) {
            if (e.kind in RETRYABLE) call(deviceId, modelId, body).trim() else throw e
        }
    }

    /**
     * Seperti [chat], tapi jawaban dialirkan (streaming): [onText] dipanggil berulang dengan SELURUH teks yang sudah
     * diterima sejauh ini (bukan hanya potongan baru), dari thread IO. Mengembalikan teks akhir.
     *
     * Kalau [tools] diberikan, Olyuno AI boleh memanggil alat (cari lagu / lirik / artis / album / playlist) di tengah
     * jawaban: [onStatus] menerima nama kegiatan yang sedang berjalan (null = selesai) dan [onReset] dipanggil kalau
     * teks yang sudah sempat tampil harus dibuang karena AI ternyata memanggil alat. Kalau jalur alat gagal sebelum ada
     * teks yang tampil (mis. server menolak alat), jawaban diulang tanpa alat supaya obrolan tetap jalan.
     * Gangguan sementara dicoba ulang sekali, hanya kalau belum ada teks yang sempat diterima.
     */
    suspend fun chatStream(
        deviceId: String,
        modelId: String,
        history: List<Pair<Boolean, String>>,
        question: String,
        lang: String,
        tools: AiTools? = null,
        onStatus: (String?) -> Unit = {},
        onReset: () -> Unit = {},
        onText: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (deviceId.isBlank()) throw AiException(AiErrorKind.NoKey)
        var received = false
        if (tools != null) {
            try {
                return@withContext agent(deviceId, modelId, chatContents(history, question), lang, tools, onStatus, onReset) {
                    received = true
                    onText(it)
                }
            } catch (e: AiException) {
                onStatus(null)
                // Hanya gangguan server / keluaran rusak yang diulang tanpa alat; kunci, kuota, jaringan, dll. langsung dilaporkan.
                if (received || e.kind !in RETRYABLE) throw e
            }
        }
        val body = JSONObject()
            .put("systemInstruction", textContent(null, SYSTEM_GENERAL + " Bahasa jawaban: " + lang + "."))
            .put("contents", chatContents(history, question))
            .put("generationConfig", JSONObject().put("temperature", 0.7))
        var got = false
        try {
            streamTurn(deviceId, modelId, body) { got = true; onText(it) }.text.trim()
        } catch (e: AiException) {
            if (!got && e.kind in RETRYABLE) streamTurn(deviceId, modelId, body) { onText(it) }.text.trim() else throw e
        }
    }

    private fun chatContents(history: List<Pair<Boolean, String>>, question: String): JSONArray {
        val contents = JSONArray()
        history.takeLast(MAX_CHAT_TURNS).forEach { (fromUser, text) ->
            contents.put(textContent(if (fromUser) "user" else "model", text.take(3000)))
        }
        contents.put(textContent("user", question.take(1500)))
        return contents
    }

    /**
     * Putaran function calling: kirim percakapan + alat; kalau AI meminta alat, jalankan (bersamaan) lalu kirim hasilnya
     * dan ulangi, sampai AI menjawab dengan teks. Setelah [MAX_TOOL_ROUNDS] putaran alat dicabut supaya pasti ada jawaban.
     */
    private suspend fun agent(
        deviceId: String,
        modelId: String,
        contents: JSONArray,
        lang: String,
        tools: AiTools,
        onStatus: (String?) -> Unit,
        onReset: () -> Unit,
        onText: (String) -> Unit,
    ): String {
        var rounds = 0
        while (true) {
            val useTools = rounds < MAX_TOOL_ROUNDS
            val body = JSONObject()
                .put("systemInstruction", textContent(null, SYSTEM_TOOLS + " Bahasa jawaban: " + lang + "."))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("temperature", 0.7))
            if (useTools) {
                body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools.declarations())))
            }
            var emitted = false
            val turn = streamTurn(deviceId, modelId, body) { emitted = true; onText(it) }
            if (turn.calls.isEmpty() || !useTools) {
                onStatus(null)
                if (turn.text.isBlank()) throw AiException(AiErrorKind.Parse)
                return turn.text.trim()
            }
            // Teks pembuka sebelum alat dipanggil tidak ikut jadi jawaban.
            if (emitted) onReset()
            onStatus(tools.statusOf(turn.calls.first().name))
            contents.put(JSONObject().put("role", "model").put("parts", turn.parts))
            val results = coroutineScope {
                turn.calls.map { c -> async { c to tools.run(c.name, c.args) } }.awaitAll()
            }
            val responses = JSONArray()
            for ((c, result) in results) {
                responses.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", c.name).put("response", result),
                    ),
                )
            }
            contents.put(JSONObject().put("role", "user").put("parts", responses))
            rounds++
        }
    }

    private class ToolCall(val name: String, val args: JSONObject)

    /** Satu giliran model: [text] = teks jawaban, [parts] = bagian mentah (dikirim balik apa adanya saat ada alat), [calls] = alat yang diminta. */
    private class Turn(val text: String, val parts: JSONArray, val calls: List<ToolCall>)

    /** Satu permintaan streaming (SSE). Membaca baris "data: {json}" satu per satu. */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun streamTurn(deviceId: String, modelId: String, body: JSONObject, onText: (String) -> Unit): Turn {
        val req = Request.Builder()
            .url("$AI_BASE/${modelId.trim()}:streamGenerateContent?alt=sse")
            .header("x-device-id", deviceId)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(req)
        // Batalkan koneksi saat coroutine dibatalkan (mis. pengguna membuka obrolan lain), supaya tidak menggantung.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        val acc = StringBuilder()
        val rawParts = JSONArray()
        val calls = ArrayList<ToolCall>()
        var blocked = false
        var finish = ""
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    val err = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
                    val msg = err?.optString("message").orEmpty()
                    throw httpError(resp.code, msg, err?.optString("status").orEmpty())
                }
                val source = resp.body?.source() ?: throw AiException(AiErrorKind.Parse)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    if (json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().isNotEmpty()) blocked = true
                    val cand = json.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                    cand.optString("finishReason").takeIf { it.isNotEmpty() }?.let { finish = it }
                    val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    var delta = ""
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        if (part.optBoolean("thought", false)) continue
                        val fc = part.optJSONObject("functionCall")
                        if (fc != null) {
                            val n = fc.optString("name")
                            if (n.isNotEmpty()) {
                                calls += ToolCall(n, fc.optJSONObject("args") ?: JSONObject())
                                rawParts.put(part)
                            }
                            continue
                        }
                        val tx = part.optString("text")
                        if (tx.isNotEmpty()) {
                            delta += tx
                            rawParts.put(part)
                        } else if (part.has("thoughtSignature")) {
                            rawParts.put(part)
                        }
                    }
                    if (delta.isNotEmpty()) {
                        acc.append(delta)
                        onText(acc.toString())
                    }
                }
            }
        } catch (e: IOException) {
            // Dibatalkan dari luar: lempar CancellationException, bukan galat jaringan.
            currentCoroutineContext().ensureActive()
            throw AiException(AiErrorKind.Network, e.message)
        } finally {
            handle?.dispose()
        }
        if (acc.isBlank() && calls.isEmpty()) {
            throw AiException(if (blocked || finish == "SAFETY") AiErrorKind.Blocked else AiErrorKind.Parse)
        }
        return Turn(acc.toString(), rawParts, calls)
    }

    private fun httpError(code: Int, msg: String, status: String = ""): AiException = when {
        // Status dari proxy Olyuno: Olyuno AI dimatikan admin / belum ada kunci aktif.
        status == "AI_DISABLED" || status == "NO_KEY" -> AiException(AiErrorKind.NoKey, msg)
        code == 404 -> AiException(AiErrorKind.ModelNotFound, msg)
        code == 429 -> AiException(AiErrorKind.Quota, msg)
        code == 401 || code == 403 -> AiException(AiErrorKind.BadKey, msg)
        code == 400 && (msg.contains("API key", true) || msg.contains("API_KEY", true)) -> AiException(AiErrorKind.BadKey, msg)
        code == 400 && msg.contains("not found", true) -> AiException(AiErrorKind.ModelNotFound, msg)
        else -> AiException(AiErrorKind.Server, msg)
    }

    private fun call(deviceId: String, modelId: String, body: JSONObject): String {
        val req = Request.Builder()
            .url("$AI_BASE/${modelId.trim()}:generateContent")
            .header("x-device-id", deviceId)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
                    val msg = err?.optString("message").orEmpty()
                    throw httpError(resp.code, msg, err?.optString("status").orEmpty())
                }
                val json = JSONObject(raw)
                if (json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().isNotEmpty()) {
                    throw AiException(AiErrorKind.Blocked)
                }
                val cand = json.optJSONArray("candidates")?.optJSONObject(0) ?: throw AiException(AiErrorKind.Blocked)
                val parts = cand.optJSONObject("content")?.optJSONArray("parts")
                val text = parts?.let { p -> (0 until p.length()).joinToString("") { p.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
                if (text.isBlank()) {
                    throw AiException(if (cand.optString("finishReason") == "SAFETY") AiErrorKind.Blocked else AiErrorKind.Parse)
                }
                return text
            }
        } catch (e: IOException) {
            throw AiException(AiErrorKind.Network, e.message)
        }
    }

    private fun textContent(role: String?, text: String): JSONObject {
        val o = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))
        if (role != null) o.put("role", role)
        return o
    }

    private fun stripFences(s: String): String =
        s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    private companion object {
        /** Proxy Gemini di server Olyuno: kunci API disimpan di server, tidak pernah dikirim ke aplikasi. */
        const val AI_BASE = "https://wiglnpjlzawejqohqqlk.supabase.co/functions/v1/ai-proxy/v1beta/models"
        const val MAX_LYRICS = 6000
        const val MAX_TURNS = 8
        const val MAX_CHAT_TURNS = 20
        const val MAX_TOOL_ROUNDS = 5
        const val SYSTEM_GENERAL =
            "Kamu Olyuno AI, asisten AI di aplikasi musik Olyuno. Olyuno dikembangkan oleh PINZZ, seorang solo developer (mengerjakannya sendirian, tanpa tim). Kalau ditanya siapa yang membuat atau mengembangkan Olyuno, jawab itu dan jangan menambah detail tentang dirinya yang tidak kamu ketahui. Bantu pengguna soal musik: makna lirik, latar belakang lagu, artis, album, genre, playlist, rekomendasi. " +
                "Gaya jawaban: langsung ke inti, tanpa basa-basi pembuka (jangan mulai dengan \"Tentu\", \"Baik\", atau mengulang pertanyaan). Kalimat pertama sudah memuat jawaban utamanya. " +
                "Susun jawaban supaya mudah dipindai di layar HP: paragraf pendek (maksimal 3 kalimat); daftar berpoin (- ) untuk beberapa hal yang setara; " +
                "daftar bernomor (1. ) untuk urutan, peringkat, atau rekomendasi; **huruf tebal** untuk judul lagu, nama artis, dan istilah kunci. " +
                "Kalau jawabanmu punya dua bagian atau lebih, beri judul bagian yang pendek dengan ## (tanpa emoji dan tanpa titik dua); jawaban pendek tidak perlu judul. " +
                "Untuk rekomendasi lagu, pakai daftar bernomor berformat: 1. **Judul** - Artis: alasan singkat satu kalimat. " +
                "Jangan pakai tabel, garis pemisah, atau emoji berlebihan. Padat lebih baik daripada panjang; panjang jawaban mengikuti kebutuhan pertanyaan. " +
                "Jangan mengarang fakta (rilis, cerita pembuatan, penghargaan); kalau tidak yakin, katakan. " +
                "Jangan menyalin lirik utuh, kutipan paling banyak di bawah 15 kata. " +
                "Kalau pertanyaan di luar musik, jawab singkat lalu arahkan kembali ke musik. " +
                "Kalau jawabanmu membahas hal tertentu (bukan sekadar menyebut sekilas), tutup jawaban dengan blok-blok persis ini di baris paling akhir, tanpa teks apa pun setelahnya. " +
                "Blok lagu: [[LAGU]] lalu satu lagu per baris berformat Judul | Artis, lalu [[/LAGU]]. " +
                "Blok artis: [[ARTIS]] lalu satu nama artis per baris, lalu [[/ARTIS]]. " +
                "Blok album: [[ALBUM]] lalu satu album per baris berformat Judul | Artis, lalu [[/ALBUM]]. " +
                "Blok playlist: [[PLAYLIST]] lalu satu playlist per baris berformat Judul | Pembuat, lalu [[/PLAYLIST]] (untuk playlist, tulis judul yang bisa dipakai untuk mencarinya). " +
                "Jangan menulis label atau judul seperti \"Lagu:\" atau \"Artis:\" sebelum blok; tulis penandanya saja. " +
                "Tulis hanya blok yang relevan, maksimal 5 item per blok, hanya yang nyata dan kamu yakin ada, urut dari yang paling dibahas. Kalau tidak ada yang dibahas, jangan tulis blok apa pun."

        /** SYSTEM_GENERAL + aturan memakai alat. Dipakai hanya saat alat dikirim bersama permintaan. */
        const val SYSTEM_TOOLS = SYSTEM_GENERAL +
            " ALAT: kamu punya alat untuk mencari di YouTube Music dan layanan lirik; panggil sendiri, jangan minta pengguna yang mencari. " +
            "Untuk setiap permintaan mencari atau merekomendasikan lagu, artis, album, atau playlist, WAJIB pakai alatnya (search_songs, search_artists, search_albums, search_playlists, get_collection_tracks), jangan menjawab dari ingatan. " +
            "Kalau pengguna hanya ingat potongan lirik atau menceritakan isi liriknya, panggil search_lyrics dengan potongan itu. Kalau hasilnya tidak cocok tapi kamu punya tebakan judulnya, verifikasi dengan check_lyrics sebelum menyebutnya. " +
            "Untuk pertanyaan makna atau arti lirik sebuah lagu, ambil liriknya dengan get_lyrics lebih dulu. " +
            "Boleh memanggil beberapa alat sekaligus, dan memanggil lagi kalau hasil pertama kurang. Sebut hanya lagu, artis, album, atau playlist yang muncul di hasil alat; jangan mengarang. " +
            "match \"exact\" = potongan lirik ditemukan utuh di lirik lagu itu, \"partial\" = sebagian cocok, \"no_lyrics\" = liriknya tidak tersedia sehingga tidak bisa dipastikan. " +
            "Kalau tidak ada hasil yang yakin, katakan jujur dan tawarkan cara lain. Jangan menyebut nama alat atau istilah teknis kepada pengguna. " +
            "Blok [[LAGU]], [[ARTIS]], [[ALBUM]], [[PLAYLIST]] di akhir jawaban diisi dari hasil alat dengan judul dan artis persis seperti di hasil."

        val RETRYABLE = setOf(AiErrorKind.Server, AiErrorKind.Parse)

        const val SYSTEM =
            "Kamu analis lirik musik. Jelaskan makna lagu dengan kata-katamu sendiri, ramah dan jelas, dalam bahasa yang diminta. " +
                "Sebut fakta latar belakang (artis, rilis, cerita pembuatan) HANYA jika kamu yakin; jangan mengarang. " +
                "Jangan menyalin lirik: kutipan paling banyak di bawah 15 kata per bagian. " +
                "Isi 'sections' dengan makna per bagian lagu (bait, reff, bridge) termasuk kiasan dan istilah sulit. " +
                "Isi 'background' dengan null jika tidak yakin."
        const val SYSTEM_CHAT =
            "Kamu analis lirik musik yang menjawab pertanyaan lanjutan tentang satu lagu. Olyuno dikembangkan oleh PINZZ, seorang solo developer (mengerjakannya sendirian, tanpa tim). Kalau ditanya siapa yang membuat atau mengembangkan Olyuno, jawab itu dan jangan menambah detail tentang dirinya yang tidak kamu ketahui. Jawab ringkas, ramah, dalam bahasa yang diminta. " +
                "Jangan mengarang fakta; kalau tidak yakin, katakan. Jangan menyalin lirik, kutipan paling banyak di bawah 15 kata."

        val SCHEMA: JSONObject = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("summary", JSONObject().put("type", "STRING"))
                    .put("themes", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING")))
                    .put(
                        "sections",
                        JSONObject().put("type", "ARRAY").put(
                            "items",
                            JSONObject().put("type", "OBJECT").put(
                                "properties",
                                JSONObject()
                                    .put("name", JSONObject().put("type", "STRING"))
                                    .put("meaning", JSONObject().put("type", "STRING")),
                            ).put("required", JSONArray().put("name").put("meaning")),
                        ),
                    )
                    .put("background", JSONObject().put("type", "STRING").put("nullable", true)),
            )
            .put("required", JSONArray().put("summary").put("themes").put("sections"))
    }
}
