package app.tunes.lyrics

/**
 * Potongan teks beserta waktunya (satu kata atau satu suku kata). [text] bisa membawa spasi di ujung,
 * supaya gabungan semua potongan sama persis dengan [LyricLine.text].
 */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * Satu baris lirik. [timeMs] = -1 untuk lirik tanpa sinkronisasi. Teks kosong = jeda/instrumental.
 * [words] terisi kalau sumber lirik membawa waktu per kata / suku kata (dipakai untuk sorotan per huruf);
 * kosong = hanya waktu per baris.
 */
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList())

sealed interface LyricsState {
    data object Loading : LyricsState
    data object NotFound : LyricsState
    data object Instrumental : LyricsState
    data class Error(val message: String) : LyricsState
    /** [source] = nama layanan asal lirik (mis. "LRCLIB"), null kalau tidak diketahui. */
    data class Ready(val lines: List<LyricLine>, val synced: Boolean, val source: String? = null) : LyricsState
}

/** Jenis lirik yang tersedia dari satu layanan untuk satu lagu (dipakai deskripsi di sheet pilihan layanan). */
enum class LyricsKind { None, Word, Line, Plain }

/** Word = ada waktu per kata, Line = tersinkron per baris, Plain = tidak tersinkron. Instrumental / belum jelas = null. */
fun LyricsState.kindOrNull(): LyricsKind? = when (this) {
    is LyricsState.NotFound -> LyricsKind.None
    is LyricsState.Ready -> when {
        !synced -> LyricsKind.Plain
        lines.any { it.words.isNotEmpty() } -> LyricsKind.Word
        else -> LyricsKind.Line
    }
    else -> null
}

/** Pilihan layanan lirik. [Auto] menanyai semua layanan lalu memilih yang liriknya cocok; lirik per kata yang sehat diutamakan. */
enum class LyricsSource(val key: String, val label: String, val description: String) {
    Auto("auto", "Otomatis", "Pemilihan lirik yang cocok"),
    Lrclib("lrclib", "LRCLIB", "Menampilkan lirik per baris"),
    BetterLyrics("better", "Better Lyrics", "Menampilkan lirik per baris"),
    BiniLyrics("bini", "BiniLyrics", "Menampilkan lirik per baris"),
    LyricsPlus("lyricsplus", "Lyrics Plus", "Menampilkan lirik per kata");

    companion object {
        fun fromKey(key: String?): LyricsSource = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/** Parser format LRC ("[mm:ss.xx] teks") dan lirik polos. */
object LrcParser {
    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val WORD_TAG = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val WORD_TAG_CAP = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /**
     * Waktu per kata dari format LRC "enhanced": "<mm:ss.xxx>kata<mm:ss.xxx>kata<mm:ss.xxx>". Tiap tag = awal
     * potongan teks sesudahnya; potongan berakhir di tag berikutnya (tag di ujung tanpa teks = akhir potongan
     * terakhir). Akhir potongan terakhir yang tidak diketahui ditandai -1 dan diisi belakangan.
     */
    private fun parseWords(rest: String, offset: Long): List<LyricWord> {
        val tags = WORD_TAG_CAP.findAll(rest).toList()
        if (tags.isEmpty()) return emptyList()
        fun ms(m: MatchResult): Long {
            val v = m.groupValues[1].toLong() * 60_000L + m.groupValues[2].toLong() * 1_000L +
                m.groupValues[3].padEnd(3, '0').take(3).toLong()
            return (v - offset).coerceAtLeast(0L)
        }
        val out = ArrayList<LyricWord>()
        val lead = rest.substring(0, tags[0].range.first)
        if (lead.isNotBlank()) out += LyricWord(ms(tags[0]), ms(tags[0]), lead)
        for ((i, tag) in tags.withIndex()) {
            val next = tags.getOrNull(i + 1)
            val text = rest.substring(tag.range.last + 1, next?.range?.first ?: rest.length)
            if (text.isEmpty()) continue
            val start = ms(tag)
            val end = next?.let { ms(it) }?.coerceAtLeast(start) ?: -1L
            out += LyricWord(start, end, text)
        }
        if (out.isEmpty()) return out
        out[0] = out[0].copy(text = out[0].text.trimStart())
        out[out.lastIndex] = out.last().copy(text = out.last().text.trimEnd())
        return out.filter { it.text.isNotEmpty() }
    }

    fun parse(raw: String): List<LyricLine> {
        var offset = 0L
        val out = ArrayList<LyricLine>()
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val off = OFFSET.find(line)
            if (off != null) {
                offset = off.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            // Satu baris boleh punya beberapa stempel waktu: "[00:10.00][01:20.00] teks".
            val stamps = ArrayList<Long>()
            var end = 0
            var m = TAG.find(line)
            while (m != null && m.range.first == end) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toLong()
                stamps += min * 60_000L + sec * 1_000L + frac
                end = m.range.last + 1
                m = TAG.find(line, end)
            }
            if (stamps.isEmpty()) continue // tag metadata ([ar:], [ti:], dll.) atau teks tanpa waktu

            val rest = line.substring(end)
            val text = rest.replace(WORD_TAG, "").trim()
            val words = parseWords(rest, offset)
            for (t in stamps) {
                val shift = t - stamps[0] // beberapa stempel di satu baris: waktu kata ikut bergeser
                val w = if (shift == 0L) words else words.map {
                    it.copy(startMs = it.startMs + shift, endMs = if (it.endMs < 0) -1L else it.endMs + shift)
                }
                out += LyricLine((t - offset).coerceAtLeast(0L), text, w)
            }
        }

        out.sortBy { it.timeMs } // sortBy stabil

        // Gabungkan jeda kosong berurutan, buang jeda di ujung.
        val cleaned = ArrayList<LyricLine>(out.size)
        for (l in out) {
            if (l.text.isEmpty() && cleaned.lastOrNull()?.text?.isEmpty() == true) continue
            cleaned += l
        }
        while (cleaned.lastOrNull()?.text?.isEmpty() == true) cleaned.removeAt(cleaned.lastIndex)
        // Akhir kata terakhir yang tidak tertulis: sampai baris berikutnya mulai (atau sebentar saja kalau tak ada).
        for (i in cleaned.indices) {
            val l = cleaned[i]
            val last = l.words.lastOrNull() ?: continue
            if (last.endMs >= 0) continue
            val next = cleaned.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE
            val end = if (next > last.startMs) minOf(next, last.startMs + 1_500L) else last.startMs + 600L
            cleaned[i] = l.copy(words = l.words.dropLast(1) + last.copy(endMs = end))
        }
        return cleaned
    }

    fun parsePlain(raw: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (rawLine in raw.lineSequence()) {
            val text = rawLine.trim()
            if (text.isEmpty() && (out.isEmpty() || out.last().text.isEmpty())) continue
            out += LyricLine(-1L, text)
        }
        while (out.lastOrNull()?.text?.isEmpty() == true) out.removeAt(out.lastIndex)
        return out
    }

    /** Indeks baris aktif untuk posisi [positionMs] (baris terakhir yang waktunya sudah lewat), -1 kalau belum mulai. */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
