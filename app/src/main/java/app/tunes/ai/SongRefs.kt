package app.tunes.ai

import app.tunes.innertube.CollectionKind

/** Lagu yang disebut AI di akhir jawaban. [key] dipakai sebagai kunci cache hasil pencarian. */
data class SongRef(val title: String, val artist: String) {
    val key: String get() = title.trim().lowercase() + "|" + artist.trim().lowercase()
}

/**
 * Artis / album / playlist yang disebut AI. [by] = artis album atau pembuat playlist (boleh kosong; artis tidak memakainya).
 * [kind] hanya ARTIST, ALBUM, atau PLAYLIST.
 */
data class EntityRef(val kind: CollectionKind, val title: String, val by: String = "") {
    val key: String get() = "$kind|" + title.trim().lowercase() + "|" + by.trim().lowercase()
}

/** Satu rujukan di bagian "Ringkasan": lagu, atau artis / album / playlist. */
sealed interface AiRef {
    val key: String

    data class Song(val ref: SongRef) : AiRef {
        override val key: String get() = "s|" + ref.key
    }

    data class Entity(val ref: EntityRef) : AiRef {
        override val key: String get() = ref.key
    }
}

/** Jawaban AI yang sudah dipisah: [text] = teks yang ditampilkan, [items] = yang dibahas (boleh kosong), urut seperti ditulis AI. */
data class ParsedReply(val text: String, val items: List<AiRef>) {
    val songs: List<SongRef> get() = items.filterIsInstance<AiRef.Song>().map { it.ref }
}

/**
 * AI diminta menutup jawaban yang membahas sesuatu dengan satu atau lebih blok:
 *
 *     [[LAGU]]      Judul | Artis       [[/LAGU]]
 *     [[ARTIS]]     Nama                [[/ARTIS]]
 *     [[ALBUM]]     Judul | Artis       [[/ALBUM]]
 *     [[PLAYLIST]]  Judul | Pembuat     [[/PLAYLIST]]
 *
 * Blok ini tidak boleh tampil sebagai teks, jadi dipotong dari jawaban dan diubah jadi daftar [AiRef].
 * Saat jawaban masih mengalir, awalan penanda yang baru separuh ("[", "[[LA") ikut disembunyikan.
 */
object SongRefs {
    const val OPEN = "[[LAGU]]"
    const val CLOSE = "[[/LAGU]]"
    private const val MAX_ITEMS = 8
    private val OPENERS = listOf("[[LAGU]]", "[[ARTIS]]", "[[ALBUM]]", "[[PLAYLIST]]")
    private val OPEN_RE = Regex("\\[\\[(LAGU|ARTIS|ALBUM|PLAYLIST)\\]\\]")

    /** [complete] = jawaban sudah selesai. Kalau belum, baris terakhir blok yang belum ditutup dianggap belum utuh dan dilewati. */
    fun parse(raw: String, complete: Boolean): ParsedReply {
        val idx = OPENERS.map { raw.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (idx == null) {
            var drop = 0
            for (o in OPENERS) {
                for (k in minOf(o.length - 1, raw.length) downTo 1) {
                    if (raw.endsWith(o.take(k))) {
                        if (k > drop) drop = k
                        break
                    }
                }
            }
            return ParsedReply(raw.dropLast(drop).trimEnd(), emptyList())
        }
        val text = raw.substring(0, idx).trimEnd()
        val items = ArrayList<AiRef>()
        var pos = idx
        while (pos < raw.length) {
            val m = OPEN_RE.find(raw, pos) ?: break
            val tag = m.groupValues[1]
            val bodyStart = m.range.last + 1
            val closeTok = "[[/$tag]]"
            val closeAt = raw.indexOf(closeTok, bodyStart)
            val nextOpen = OPEN_RE.find(raw, bodyStart)?.range?.first
            // Blok berakhir di penutupnya; kalau lupa ditutup tapi blok lain sudah dibuka, berakhir di pembuka itu.
            val end = when {
                closeAt >= 0 && (nextOpen == null || closeAt < nextOpen) -> closeAt
                nextOpen != null -> nextOpen
                else -> -1
            }
            val closed = end >= 0
            val body = if (closed) raw.substring(bodyStart, end) else raw.substring(bodyStart)
            var lines = body.lines()
            if (!closed && !complete && lines.isNotEmpty()) lines = lines.dropLast(1)
            for (line in lines) parseLine(tag, line)?.let { items += it }
            if (!closed) break
            pos = if (end == closeAt) closeAt + closeTok.length else end
        }
        return ParsedReply(text, items.distinctBy { it.key }.take(MAX_ITEMS))
    }

    private fun parseLine(tag: String, line: String): AiRef? {
        val l = line.trim().trimStart('-', '*', '•').trim()
        if (l.isEmpty() || l.startsWith("[[")) return null
        val bar = l.indexOf('|')
        val first = (if (bar >= 0) l.substring(0, bar) else l).trim().trim('"', '“', '”')
        val second = if (bar >= 0) l.substring(bar + 1).trim() else ""
        if (first.isEmpty()) return null
        return when (tag) {
            "LAGU" -> if (second.isEmpty()) null else AiRef.Song(SongRef(first, second))
            "ARTIS" -> AiRef.Entity(EntityRef(CollectionKind.ARTIST, first))
            "ALBUM" -> AiRef.Entity(EntityRef(CollectionKind.ALBUM, first, second))
            else -> AiRef.Entity(EntityRef(CollectionKind.PLAYLIST, first, second))
        }
    }
}
