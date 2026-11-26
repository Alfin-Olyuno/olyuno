package app.tunes.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import app.tunes.i18n.t

/** Status terjemahan lirik untuk lagu yang sedang tampil. */
sealed interface TranslationState {
    data object Off : TranslationState
    data object Loading : TranslationState
    data object Error : TranslationState

    /** [lines] sejajar dengan baris lirik; null = lirik sudah memakai bahasa tujuan (tidak perlu diterjemahkan). */
    data class Done(val lines: List<String>?) : TranslationState
}

data class Language(val code: String, val name: String)

/** Bahasa tujuan terjemahan (kode mengikuti Google Translate). */
object Languages {
    val all = listOf(
        Language("id", "Indonesia"), Language("en", "English"), Language("ms", "Bahasa Melayu"),
        Language("jw", "Basa Jawa"), Language("su", "Basa Sunda"), Language("ar", "العربية"),
        Language("zh-CN", "中文 (简体)"), Language("zh-TW", "中文 (繁體)"), Language("ja", "日本語"),
        Language("ko", "한국어"), Language("hi", "हिन्दी"), Language("bn", "বাংলা"),
        Language("ta", "தமிழ்"), Language("th", "ไทย"), Language("vi", "Tiếng Việt"),
        Language("tl", "Filipino"), Language("es", "Español"), Language("pt", "Português"),
        Language("fr", "Français"), Language("de", "Deutsch"), Language("it", "Italiano"),
        Language("nl", "Nederlands"), Language("ru", "Русский"), Language("uk", "Українська"),
        Language("pl", "Polski"), Language("tr", "Türkçe"), Language("fa", "فارسی"),
        Language("ur", "اردو"), Language("he", "עברית"), Language("sv", "Svenska"),
        Language("no", "Norsk"), Language("da", "Dansk"), Language("fi", "Suomi"),
        Language("el", "Ελληνικά"), Language("cs", "Čeština"), Language("hu", "Magyar"),
        Language("ro", "Română"),
    )

    fun nameOf(code: String): String = all.firstOrNull { it.code == code }?.name ?: code

    /**
     * Bahasa awal terjemahan = bahasa sistem HP (nanti bisa diganti ke bahasa aplikasi Olyuno
     * cukup dengan mengubah fungsi ini). Kalau tidak ada di daftar, pakai Inggris.
     */
    fun defaultCode(): String {
        val loc = Locale.getDefault()
        val code = when (val l = loc.language) {
            "in" -> "id"
            "iw" -> "he"
            "fil" -> "tl"
            "jv" -> "jw"
            "nb", "nn" -> "no"
            "zh" -> if (loc.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            else -> l
        }
        return if (all.any { it.code == code }) code else "en"
    }
}

/**
 * Terjemahan lirik lewat endpoint publik Google Translate (translate.googleapis.com, client=gtx).
 * Semua baris dikirim sekaligus (satu permintaan per ±4000 karakter) dan hasilnya disimpan di memori +
 * cache disk, jadi lagu yang sama tidak diterjemahkan dua kali. Bahasa asal dideteksi otomatis; kalau
 * sama dengan bahasa tujuan, hasilnya null (tidak ada terjemahan yang perlu ditampilkan).
 * Catatan: endpoint ini tidak resmi, jadi bisa dibatasi Google sewaktu-waktu.
 */
class TranslationRepository(
    cacheDir: File,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val dir = File(cacheDir, "translations")
    private val memory = HashMap<String, List<String>?>()
    private val gate = Semaphore(4)

    /** Buang semua terjemahan tersimpan (memori + disk). */
    fun clearAll() {
        synchronized(memory) { memory.clear() }
        runCatching { dir.deleteRecursively() }
    }

    suspend fun translate(lines: List<String>, target: String): List<String>? = withContext(Dispatchers.IO) {
        val key = sha1("$target\u0000" + lines.joinToString("\n"))
        synchronized(memory) { if (memory.containsKey(key)) return@withContext memory[key] }
        readDisk(key)?.let { hit ->
            synchronized(memory) { memory[key] = hit.lines }
            return@withContext hit.lines
        }

        val idx = lines.indices.filter { lines[it].isNotBlank() }
        val result: List<String>? = if (idx.isEmpty()) {
            null
        } else {
            val texts = idx.map { lines[it].trim() }
            var detected: String? = null
            val out = ArrayList<String>(texts.size)
            for (chunk in chunks(texts)) {
                val (tr, det) = translateChunk(chunk, target)
                if (detected == null) detected = det
                out += tr
            }
            if (detected != null && sameLanguage(detected, target)) {
                null
            } else {
                MutableList(lines.size) { "" }.also { full -> idx.forEachIndexed { i, li -> full[li] = out[i] } }
            }
        }
        writeDisk(key, result)
        synchronized(memory) {
            if (memory.size > 40) memory.clear()
            memory[key] = result
        }
        result
    }

    private fun chunks(texts: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var size = 0
        for (t in texts) {
            if (cur.isNotEmpty() && size + t.length > MAX_CHARS) {
                out += cur; cur = ArrayList(); size = 0
            }
            cur += t; size += t.length + 1
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** Satu permintaan untuk banyak baris. Kalau jumlah baris hasil tidak cocok, terjemahkan per baris. */
    private suspend fun translateChunk(chunk: List<String>, target: String): Pair<List<String>, String?> {
        val (joined, det) = request(chunk.joinToString("\n"), target)
        val parts = joined.trimEnd('\n').split("\n")
        if (parts.size == chunk.size) return parts.map { it.trim() } to det
        // Penyelarasan meleset (jarang): terjemahkan satu per satu, paralel terbatas.
        val each = coroutineScope {
            chunk.map { line -> async { gate.withPermit { request(line, target) } } }.awaitAll()
        }
        return each.map { it.first.trim() } to (det ?: each.firstNotNullOfOrNull { it.second })
    }

    /** Mengembalikan (teks terjemahan gabungan, kode bahasa asal terdeteksi). */
    private fun request(text: String, target: String): Pair<String, String?> {
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target)
            .addQueryParameter("dt", "t")
            .build()
        val req = Request.Builder().url(url).post(FormBody.Builder().add("q", text).build()).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(t("Terjemahan gagal (%d)", resp.code))
            val root = Json.parseToJsonElement(resp.body?.string().orEmpty()).jsonArray
            val segs = root.getOrNull(0)?.jsonArray ?: throw IOException(t("Respons terjemahan kosong"))
            val joined = segs.joinToString("") { seg ->
                (seg as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val detected = root.getOrNull(2)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            return joined to detected
        }
    }

    private fun sameLanguage(detected: String, target: String): Boolean =
        detected.substringBefore('-').equals(target.substringBefore('-'), ignoreCase = true)

    // ------------------------------------------------------------------ cache disk

    private class Cached(val lines: List<String>?)

    private fun readDisk(key: String): Cached? = runCatching {
        val f = File(dir, "$key.json")
        if (!f.isFile) return@runCatching null
        val o = Json.parseToJsonElement(f.readText()).jsonObject
        if (o["same"]?.jsonPrimitive?.booleanOrNull == true) Cached(null)
        else Cached(o["lines"]!!.jsonArray.map { it.jsonPrimitive.content })
    }.getOrNull()

    private fun writeDisk(key: String, lines: List<String>?) {
        runCatching {
            dir.mkdirs()
            val o = buildJsonObject {
                if (lines == null) put("same", true) else put("lines", JsonArray(lines.map { JsonPrimitive(it) }))
            }
            File(dir, "$key.json").writeText(o.toString())
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_CHARS = 4000
    }
}
