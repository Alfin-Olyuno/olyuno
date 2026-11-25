package app.tunes.ai

/** Hal yang sedang "dicari" Olyuno AI; dipakai untuk teks indikator di samping titik menunggu jawaban. */
enum class SearchTopic { Song, Playlist, Artist, Album }

/**
 * Menebak dari kata-kata pertanyaan pengguna apakah pertanyaannya soal lagu, playlist, artis, atau album.
 * Hasilnya berurutan sesuai kemunculan pertama di kalimat, tanpa duplikat. Kosong = bukan soal ketiganya
 * (indikatornya tidak ditampilkan, hanya titik menunggu).
 */
object SearchTopics {
    private val OPT = RegexOption.IGNORE_CASE

    // "playlist lagu" dan "daftar lagu" dihitung sebagai playlist saja, bukan juga sebagai lagu.
    private val PLAYLIST = Regex("\\b(playlist lagu|playlist|daftar putar|daftar lagu|kumpulan lagu)\\w*", OPT)
    private val ARTIST = Regex(
        "\\b(artis|artist|penyanyi|musisi|vokalis|rapper|singer|solois|band|grup musik|girl ?group|boy ?group)(s|nya)?\\b",
        OPT,
    )
    private val ALBUM = Regex("\\b(album|diskografi|discography)(s|nya)?\\b", OPT)
    private val SONG = Regex("\\b(lagu|song|track|tembang|lirik|lyrics?|makna|maksud)(s|nya)?\\b", OPT)
    private val MUSIC = Regex("\\b(musik|music)\\b", OPT)
    private val ASK = Regex("\\b(rekomendasi\\w*|recommend\\w*|saran\\w*|carikan|cari|putarkan|putar|dengerin|dengarkan)\\b", OPT)

    fun detect(text: String?): List<SearchTopic> {
        if (text.isNullOrBlank()) return emptyList()
        val found = ArrayList<Pair<Int, SearchTopic>>(3)
        PLAYLIST.find(text)?.let { found += it.range.first to SearchTopic.Playlist }
        // Frasa playlist diganti spasi (panjang sama) supaya "playlist lagu galau" tidak ikut dihitung sebagai lagu.
        val rest = PLAYLIST.replace(text) { " ".repeat(it.value.length) }
        ARTIST.find(rest)?.let { found += it.range.first to SearchTopic.Artist }
        ALBUM.find(rest)?.let { found += it.range.first to SearchTopic.Album }
        val song = SONG.find(rest)
            ?: MUSIC.find(rest)?.takeIf { ASK.containsMatchIn(rest) }
        song?.let { found += it.range.first to SearchTopic.Song }
        return found.sortedBy { it.first }.map { it.second }
    }
}
