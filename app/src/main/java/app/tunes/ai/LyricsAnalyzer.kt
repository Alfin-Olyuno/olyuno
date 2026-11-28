package app.tunes.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import kotlin.random.Random

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

/** [retryAfterMs] = isi header Retry-After dari server (kalau ada), dipakai untuk menunda retry kuota. */
class AiException(val kind: AiErrorKind, message: String? = null, val retryAfterMs: Long? = null) : Exception(message)

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

        val analysis = withRetry {
            try {
                LyricsAnalysis.fromJson(JSONObject(stripFences(call(deviceId, modelId, body))))
            } catch (e: org.json.JSONException) {
                throw AiException(AiErrorKind.Parse)
            } catch (e: IllegalArgumentException) {
                throw AiException(AiErrorKind.Parse)
            }
        }
        runCatching { cacheFile(videoId, lang, modelId).writeText(analysis.toJson().toString()) }
        analysis
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
        withRetry { call(deviceId, modelId, body).trim() }
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
        withRetry { call(deviceId, modelId, body).trim() }
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
                // Server/Parse tidak diulang di sini: jalur tanpa alat di bawah sudah jadi percobaan ulangnya.
                return@withContext withRetry(kinds = setOf(AiErrorKind.Network, AiErrorKind.Quota), canRetry = { !received }) {
                    agent(deviceId, modelId, chatContents(history, question), lang, tools, onStatus, onReset) {
                        received = true
                        onText(it)
                    }
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
        withRetry(canRetry = { !got }) {
            streamTurn(deviceId, modelId, body) { got = true; onText(it) }.text.trim()
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
            // Alat sudah selesai: indikator padam, jangan tampil lagi selama AI menulis jawaban.
            onStatus(null)
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
                    throw httpError(resp.code, msg, err?.optString("status").orEmpty(), retryAfterMs(resp))
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

    /** Header Retry-After (detik) -> milidetik; null kalau tidak ada atau bukan angka. */
    private fun retryAfterMs(resp: okhttp3.Response): Long? =
        resp.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)

    private fun httpError(code: Int, msg: String, status: String = "", retryAfterMs: Long? = null): AiException = when {
        // Status dari proxy Olyuno: Olyuno AI dimatikan admin / belum ada kunci aktif.
        status == "AI_DISABLED" || status == "NO_KEY" -> AiException(AiErrorKind.NoKey, msg)
        code == 404 -> AiException(AiErrorKind.ModelNotFound, msg)
        code == 429 -> AiException(AiErrorKind.Quota, msg, retryAfterMs)
        code == 401 || code == 403 -> AiException(AiErrorKind.BadKey, msg)
        code == 400 && (msg.contains("API key", true) || msg.contains("API_KEY", true)) -> AiException(AiErrorKind.BadKey, msg)
        code == 400 && msg.contains("not found", true) -> AiException(AiErrorKind.ModelNotFound, msg)
        else -> AiException(AiErrorKind.Server, msg)
    }

    /**
     * Jalankan [block] dan ulangi otomatis kalau gagal dengan gangguan sementara (lihat [retryDelayMs]).
     * [kinds] membatasi jenis galat yang boleh diulang (null = semua yang layak); [canRetry] dicek sebelum tiap ulangan,
     * mis. false kalau teks jawaban sudah sempat tampil supaya tidak dobel. Pembatalan coroutine tidak pernah diulang.
     */
    private suspend fun <T> withRetry(
        kinds: Set<AiErrorKind>? = null,
        canRetry: () -> Boolean = { true },
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: AiException) {
                val wait = if (kinds == null || e.kind in kinds) retryDelayMs(e, attempt) else null
                if (wait == null || !canRetry()) throw e
                attempt++
                delay(wait)
            }
        }
    }

    /** Jeda sebelum ulangan ke-([attempt]+1), atau null kalau tidak boleh diulang lagi. */
    private fun retryDelayMs(e: AiException, attempt: Int): Long? = when (e.kind) {
        // Gangguan server / JSON rusak: 2 ulangan, jeda 1 dtk lalu 2 dtk (+ jitter).
        AiErrorKind.Server, AiErrorKind.Parse ->
            if (attempt < MAX_RETRIES) (1000L shl attempt) + Random.nextLong(0, 300) else null
        // Timeout / koneksi putus: cukup 1 ulangan, karena read timeout saja sudah 60 dtk.
        AiErrorKind.Network -> if (attempt < 1) 1500L else null
        // Kuota: 1 ulangan, hanya kalau server memberi Retry-After yang pendek. Kuota habis -> tombol "Coba lagi".
        AiErrorKind.Quota -> e.retryAfterMs?.takeIf { attempt < 1 && it <= MAX_QUOTA_WAIT_MS }
        else -> null
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
                    throw httpError(resp.code, msg, err?.optString("status").orEmpty(), retryAfterMs(resp))
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
        const val MAX_RETRIES = 2
        const val MAX_QUOTA_WAIT_MS = 5_000L
        /**
         * Pengetahuan Olyuno AI tentang aplikasi Olyuno sendiri (fitur, letak menu, cara pakai). Ringkas supaya hemat token;
         * perbarui kalau ada fitur / menu baru. Disambungkan ke SYSTEM_GENERAL (otomatis ikut SYSTEM_TOOLS) dan SYSTEM_CHAT.
         */
        const val APP_GUIDE =
            " TENTANG APLIKASI OLYUNO: Olyuno adalah pemutar musik Android (sumber musik: YouTube Music) buatan PINZZ. Kalau pengguna bertanya soal aplikasi ini (fitur, letak menu, cara memakai, pengaturan), jawab dari panduan ini TANPA memakai alat, singkat dan berupa langkah yang jelas, tanpa blok [[LAGU]] dan sejenisnya. Hanya sebut fitur yang ada di panduan ini; kalau yang ditanyakan tidak tercantum, katakan jujur bahwa kamu tidak melihatnya di Olyuno, jangan mengarang menu atau langkah. " +
            "Bilah bawah punya 4 tab: Beranda, Cari, Koleksi, Anda. " +
            "BERANDA: salam sesuai waktu, Lanjutkan, Pilihan cepat, Mix untukmu, Sedang trending, Rilis baru, Andalanmu, Dengarkan lagi, Paling sering diputar, rekomendasi Genre dan Mirip dengan lagu yang kamu dengar. " +
            "CARI: cari lagu, artis, album, playlist; ada saran pencarian, riwayat pencarian, dan input suara (dikte). " +
            "KOLEKSI: Disukai, Diunduh, Playlist saya (buat playlist baru, lalu tambah lagu lewat menu titik tiga lagu; titik tiga di tiap playlist membuka menu berisi Putar, Acak, Tambah semua ke antrean, Ganti nama, Tambahkan ke YouTube Music, Perbarui di YouTube Music, Buka di YouTube Music, Bagikan tautan, Lepas tautan YouTube Music, dan Hapus playlist; menu YouTube Music butuh login dan playlist dikirim sebagai playlist pribadi, lagu perangkat tidak ikut terkirim, dan menghapus playlist di Olyuno tidak menghapusnya di YouTube Music), Lagu perangkat (lagu di penyimpanan HP, perlu izin akses), Terakhir dilihat. " +
            "ANDA: Akun (Masuk dengan Google untuk memuat album, artis, dan podcast dari akun YouTube Music; bisa Keluar), Cari lagu, Olyuno AI (obrolan ini), Riwayat lagu, Statistik & Rekap, Streak, Ekualiser, Ekspor data, Impor data, Pengaturan, Informasi (pengumuman dari Olyuno), Tentang Olyuno. " +
            "PEMUTAR: antrean (putar berikutnya, naikkan, turunkan, hapus, bersihkan), acak, ulangi, Mulai radio dari sebuah lagu, tambah ke playlist, unduh, bagikan lagu, timer tidur (berhenti di akhir lagu atau setelah waktu tertentu, volume bisa dipudarkan), kecepatan pemutaran (nada bisa dijaga tetap). Menu titik tiga di layar pemutar (Pemutaran) berisi Timer tidur, Unduh, Mulai radio, Tambah ke playlist, slider volume perangkat, Normalisasi volume, dan Jaga layar tetap hidup (pengaturan yang sama dengan di menu lirik). " +
            "LIRIK: lirik tersinkron per baris (Lyrics Plus bisa per kata), cari kata di dalam lirik, terjemahkan lirik ke 37 bahasa (termasuk Indonesia, Inggris, Jawa, Sunda), bagikan lirik, jaga layar tetap hidup. Sumber lirik dipilih di menu lirik atau Pengaturan > Lirik: Otomatis, LRCLIB, Better Lyrics, BiniLyrics, Lyrics Plus. " +
            "CARI LAGU (Anda > Cari lagu): ketuk mikrofon lalu nyanyikan atau ucapkan potongan lirik atau judul; suara diubah jadi teks oleh pengenal suara perangkat lalu dicari di YouTube Music. Butuh izin mikrofon dan internet. " +
            "OLYUNO AI: punya dua model, Olyuno 1.0 (model buatan Olyuno sendiri, berjalan lokal di server Olyuno) dan Olyuno 2.0 (bukan buatan Olyuno sendiri, memakai Gemini dari Google); bisa mengobrol soal musik, mencari lagu, artis, album, playlist, mencari lagu dari potongan lirik, menjelaskan makna lirik, membuatkan playlist di Olyuno, dan (kalau diminta dan sudah login) mengirim playlist itu ke akun YouTube Music. Model diganti di kanan atas (Olyuno 1.0 atau Olyuno 2.0). Ada Obrolan baru dan Riwayat chat. Pemakaian gratis ada batasnya. " +
            "STATISTIK & REKAP: lagu, artis, album, dan playlist teratas per periode, plus kartu rekap yang bisa dibagikan. " +
            "STREAK: hari beruntun mendengarkan. Sehari terhitung kalau 1 lagu didengar sampai 80% (atau 2 menit), atau total 5 menit. Freeze menyelamatkan streak saat 1 hari terlewat: dapat 1 tiap 7 hari streak, maksimal 2. " +
            "EKUALISER: ekualiser dan penguat bass. " +
            "EKSPOR/IMPOR DATA: cadangan berisi lagu disukai, riwayat putar, hitungan putar, riwayat pencarian, statistik, dan streak; file lagu yang diunduh tidak ikut. " +
            "PENGATURAN: Bahasa (Indonesia atau English, ikut bahasa perangkat; bahasa lain memakai English), Tampilan (gaya Kaca atau Biasa, warna kustom atau otomatis dari cover lagu, background player dan lirik, animasi, refresh rate tinggi, Penghemat baterai, Dynamic Island), Pemutaran, Lirik, Notifikasi, Penyimpanan, Tentang Olyuno, Keluar. " +
            "PEMUTARAN (pengaturan): Kualitas audio (Otomatis: tinggi di Wi-Fi, rendah di data seluler; Rendah; Tinggi), Hemat data (kualitas rendah, fitur boros data dimatikan, unduhan hanya Wi-Fi), Unduh hanya lewat Wi-Fi, Normalisasi volume, Lewati bagian hening, Putar otomatis (lanjut ke lagu serupa saat antrean habis), Lanjutkan terakhir diputar, Lanjut saat headset tersambung lagi dalam 10 menit. " +
            "DYNAMIC ISLAND: kartu info lagu melayang di atas layar, perlu izin tampil di atas aplikasi lain; bentuk, sudut, dan perilaku kartu bisa diatur. " +
            "NOTIFIKASI: kontrol musik, progres dan hasil unduhan (ada tombol ulangi kalau gagal), pengingat streak, pengumuman baru. " +
            "PENYIMPANAN: lihat ruang terpakai; hapus cache audio, gambar, lirik, unduhan, riwayat pencarian, riwayat putar; atur batas cache audio (lagu paling lama tidak diputar dihapus otomatis). " +
            "UNDUHAN: unduh lagu, album, atau playlist untuk didengar offline; ada notifikasi progres, dan unduhan yang gagal bisa diulangi. " +
            "Kalau ada masalah teknis, sarankan Tentang Olyuno > Salin semua info, lalu laporkan ke pengembang. Jangan menyebut detail teknis internal, nama server, atau kode."

        const val SYSTEM_GENERAL =
            "Kamu Olyuno AI, asisten AI di aplikasi musik Olyuno. Olyuno dikembangkan oleh PINZZ, seorang solo developer (mengerjakannya sendirian, tanpa tim). Kalau ditanya siapa yang membuat atau mengembangkan Olyuno, jawab itu dan jangan menambah detail tentang dirinya yang tidak kamu ketahui. Bantu pengguna soal musik: makna lirik, latar belakang lagu, artis, album, genre, playlist, rekomendasi. " +
                "Gaya jawaban: langsung ke inti, tanpa basa-basi pembuka (jangan mulai dengan \"Tentu\", \"Baik\", atau mengulang pertanyaan). Kalimat pertama sudah memuat jawaban utamanya. " +
                "Susun jawaban supaya mudah dipindai di layar HP: paragraf pendek (maksimal 4 kalimat); daftar berpoin (- ) untuk beberapa hal yang setara; " +
                "daftar bernomor (1. ) untuk urutan, peringkat, atau rekomendasi; **huruf tebal** untuk judul lagu, nama artis, dan istilah kunci. " +
                "Kalau jawabanmu punya dua bagian atau lebih, beri judul bagian yang pendek dengan ## (tanpa emoji dan tanpa titik dua); jawaban pendek tidak perlu judul. " +
                "Untuk rekomendasi lagu, pakai daftar bernomor berformat: 1. **Judul** - Artis: alasan 1-2 kalimat yang spesifik (suasana, tema atau isi lirik, gaya musik, atau hal khas dari lagunya), bukan kalimat umum yang sama untuk semua lagu. " +
                "Jangan pakai tabel, garis pemisah, atau emoji berlebihan. KELENGKAPAN: jawab selengkap, sejelas, dan sedetail yang berguna, jangan berhenti di satu kalimat. Pertanyaan tentang makna lagu, latar belakang, artis, album, atau perbandingan dijawab utuh: jelaskan inti jawabannya, lalu alasan dan konteksnya (isi dan tema lirik, kiasan, suasana, gaya musik, latar belakang, hal menarik yang relevan), dan beri contoh bila membantu. Pertanyaan yang agak luas dijawab dengan beberapa bagian berjudul. Rekomendasi diberi penjelasan kenapa cocok dengan permintaan pengguna. Tetap enak dibaca (kalimat jelas, tanpa bertele-tele atau mengulang) dan hanya sampaikan yang benar-benar kamu ketahui. Jawaban satu kalimat hanya untuk pertanyaan yang memang sangat sederhana (sapaan atau konfirmasi singkat). " +
                "Jangan mengarang fakta (rilis, cerita pembuatan, penghargaan); kalau tidak yakin, katakan. " +
                "Jangan menyalin lirik utuh, kutipan paling banyak di bawah 15 kata. " +
                "Kalau pertanyaan di luar musik, jawab singkat lalu arahkan kembali ke musik. " +
                "Kalau jawabanmu membahas hal tertentu (bukan sekadar menyebut sekilas), tutup jawaban dengan blok-blok persis ini di baris paling akhir, tanpa teks apa pun setelahnya. " +
                "Blok lagu: [[LAGU]] lalu satu lagu per baris berformat Judul | Artis, lalu [[/LAGU]]. " +
                "Blok artis: [[ARTIS]] lalu satu nama artis per baris, lalu [[/ARTIS]]. " +
                "Blok album: [[ALBUM]] lalu satu album per baris berformat Judul | Artis, lalu [[/ALBUM]]. " +
                "Blok playlist: [[PLAYLIST]] lalu satu playlist per baris berformat Judul | Pembuat, lalu [[/PLAYLIST]] (untuk playlist, tulis judul yang bisa dipakai untuk mencarinya). " +
                "Jangan menulis label atau judul seperti \"Lagu:\" atau \"Artis:\" sebelum blok; tulis penandanya saja. " +
                "Tulis hanya blok yang relevan, maksimal 5 item per blok, hanya yang nyata dan kamu yakin ada, urut dari yang paling dibahas. Kalau tidak ada yang dibahas, jangan tulis blok apa pun." +
            APP_GUIDE

        /** SYSTEM_GENERAL + aturan memakai alat. Dipakai hanya saat alat dikirim bersama permintaan. */
        const val SYSTEM_TOOLS = SYSTEM_GENERAL +
            " ALAT: kamu punya alat untuk mencari di YouTube Music dan layanan lirik; panggil sendiri, jangan minta pengguna yang mencari. " +
            "ATURAN UTAMA: setiap pertanyaan yang menyebut lagu, artis, album, playlist, atau lirik tertentu WAJIB dijawab setelah memakai alat, bukan dari ingatan. Jangan pernah menjawab langsung untuk hal itu. " +
            "Pilih sendiri alat yang paling cocok: arti, makna, atau maksud sebuah lagu = get_lyrics dulu lalu jelaskan dari liriknya; mencari atau merekomendasikan lagu = search_songs; artis, album, atau playlist = search_artists, search_albums, search_playlists; isi album, playlist, atau lagu populer artis = get_collection_tracks; potongan lirik yang diingat pengguna = search_lyrics, lalu check_lyrics untuk memverifikasi tebakan judul. " +
            "MENCARI LAGU: kalau pengguna menyebut bahasa, negara, genre, atau suasana (termasuk pertanyaan lanjutan pendek seperti \"kalau lagu Inggris?\"), buat kata kunci search_songs yang baru dan eksplisit (mis. \"English pop songs\"), jangan mengulang hasil jawaban sebelumnya. Cocokkan hasilnya dengan permintaan (bahasa dan artisnya benar), buang yang tidak cocok, dan lewati video kompilasi atau playlist (judul seperti \"Top 100\", \"Playlist\", \"Cover 2026\"); kalau hasilnya tidak ada yang cocok, katakan apa adanya. Tiap lagu diberi keterangan 1-2 kalimat yang berbeda dan khas (jangan kalimat template yang sama untuk semua), hanya dari hal yang benar-benar kamu ketahui. " +
            "Pertanyaan yang tidak ada hubungannya dengan musik (sapaan, obrolan umum) dan pertanyaan tentang aplikasi Olyuno sendiri (fitur, menu, cara pakai) dijawab langsung tanpa alat. " +
            "MEMBUAT PLAYLIST: kalau pengguna meminta dibuatkan playlist, cari lagunya dulu (search_songs atau get_collection_tracks), tunggu hasilnya, baru panggil create_playlist dengan judul dan artis persis seperti di hasil alat (maksimal 30 lagu); jangan memanggilnya bersamaan dengan pencarian. Isi add_to_youtube_music = true HANYA kalau pengguna jelas meminta playlist itu juga dimasukkan ke akun YouTube Music-nya. Untuk playlist yang sudah ada, pakai add_playlist_to_youtube_music saat pengguna memintanya. Jangan membuat atau mengirim playlist kalau pengguna hanya bertanya atau minta rekomendasi. Hasil not_signed_in = beri tahu pengguna untuk masuk lewat tab Anda > Masuk dengan Google (playlist di Olyuno tetap sudah dibuat); jangan bilang sudah masuk ke YouTube Music kecuali youtube_music bernilai created atau updated. Sebut jumlah lagu yang masuk dan lagu yang tidak ditemukan (kalau ada) apa adanya; playlist di YouTube Music dibuat sebagai pribadi. " +
            "Boleh memanggil beberapa alat sekaligus, dan memanggil lagi kalau hasil pertama kurang. Sebut hanya lagu, artis, album, atau playlist yang muncul di hasil alat; jangan mengarang. " +
            "match \"exact\" = potongan lirik ditemukan utuh di lirik lagu itu, \"partial\" = sebagian cocok, \"no_lyrics\" = liriknya tidak tersedia sehingga tidak bisa dipastikan. " +
            "Kalau lirik tidak tersedia untuk menjelaskan makna, katakan jujur lalu jelaskan seperlunya dengan menyebut bahwa itu bukan dari liriknya. " +
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
                "Jangan mengarang fakta; kalau tidak yakin, katakan. Jangan menyalin lirik, kutipan paling banyak di bawah 15 kata." +
            APP_GUIDE

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
