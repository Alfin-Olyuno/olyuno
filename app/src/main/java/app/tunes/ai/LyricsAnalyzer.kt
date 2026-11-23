package app.tunes.ai

import kotlinx.coroutines.Dispatchers
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

/** Hasil Tanya AI satu lagu. [background] null = AI tidak cukup yakin soal latar belakang. */
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
        apiKey: String,
        modelId: String,
        videoId: String,
        title: String,
        artist: String,
        lyrics: String?,
        lang: String,
        useCache: Boolean = true,
    ): LyricsAnalysis = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException(AiErrorKind.NoKey)
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
                val text = call(apiKey, modelId, body)
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
        apiKey: String,
        modelId: String,
        title: String,
        artist: String,
        analysis: LyricsAnalysis,
        history: List<Pair<Boolean, String>>,
        question: String,
        lang: String,
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException(AiErrorKind.NoKey)
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
            call(apiKey, modelId, body).trim()
        } catch (e: AiException) {
            if (e.kind in RETRYABLE) call(apiKey, modelId, body).trim() else throw e
        }
    }

    /**
     * Obrolan bebas tentang musik (lagu, artis, makna lirik, rekomendasi). [history] = pasangan (dariPengguna, teks)
     * sebelum [question]; jawaban berupa teks biasa dengan format markdown ringan.
     */
    suspend fun chat(
        apiKey: String,
        modelId: String,
        history: List<Pair<Boolean, String>>,
        question: String,
        lang: String,
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException(AiErrorKind.NoKey)
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
            call(apiKey, modelId, body).trim()
        } catch (e: AiException) {
            if (e.kind in RETRYABLE) call(apiKey, modelId, body).trim() else throw e
        }
    }

    private fun call(apiKey: String, modelId: String, body: JSONObject): String {
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${modelId.trim()}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                    throw when {
                        resp.code == 404 -> AiException(AiErrorKind.ModelNotFound, msg)
                        resp.code == 429 -> AiException(AiErrorKind.Quota, msg)
                        resp.code == 401 || resp.code == 403 -> AiException(AiErrorKind.BadKey, msg)
                        resp.code == 400 && (msg.contains("API key", true) || msg.contains("API_KEY", true)) -> AiException(AiErrorKind.BadKey, msg)
                        resp.code == 400 && msg.contains("not found", true) -> AiException(AiErrorKind.ModelNotFound, msg)
                        resp.code >= 500 -> AiException(AiErrorKind.Server, msg)
                        else -> AiException(AiErrorKind.Server, msg)
                    }
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
        const val MAX_LYRICS = 6000
        const val MAX_TURNS = 8
        const val MAX_CHAT_TURNS = 20
        const val SYSTEM_GENERAL =
            "Kamu Olyuno, asisten AI di aplikasi musik Olyuno. Bantu pengguna soal musik: makna lirik, latar belakang lagu, artis, album, genre, rekomendasi. " +
                "Jawab natural seperti asisten AI pada umumnya: langsung ke inti, paragraf pendek, boleh memakai daftar dan **huruf tebal**. " +
                "Jangan mengarang fakta (rilis, cerita pembuatan, penghargaan); kalau tidak yakin, katakan. " +
                "Jangan menyalin lirik utuh, kutipan paling banyak di bawah 15 kata. " +
                "Kalau pertanyaan di luar musik, jawab singkat lalu arahkan kembali ke musik."
        val RETRYABLE = setOf(AiErrorKind.Server, AiErrorKind.Parse)

        const val SYSTEM =
            "Kamu analis lirik musik. Jelaskan makna lagu dengan kata-katamu sendiri, ramah dan jelas, dalam bahasa yang diminta. " +
                "Sebut fakta latar belakang (artis, rilis, cerita pembuatan) HANYA jika kamu yakin; jangan mengarang. " +
                "Jangan menyalin lirik: kutipan paling banyak di bawah 15 kata per bagian. " +
                "Isi 'sections' dengan makna per bagian lagu (bait, reff, bridge) termasuk kiasan dan istilah sulit. " +
                "Isi 'background' dengan null jika tidak yakin."
        const val SYSTEM_CHAT =
            "Kamu analis lirik musik yang menjawab pertanyaan lanjutan tentang satu lagu. Jawab ringkas, ramah, dalam bahasa yang diminta. " +
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
