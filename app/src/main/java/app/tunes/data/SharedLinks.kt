package app.tunes.data

import android.net.Uri

/** Membaca tautan YouTube / YouTube Music (youtu.be, music.youtube.com, shorts, playlist) menjadi id lagu atau playlist. */
object SharedLinks {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val LIST_ID = Regex("^[A-Za-z0-9_-]{10,64}$")
    private val URL_IN_TEXT = Regex("https?://\\S+")

    private fun isYouTubeHost(host: String) =
        host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")

    /** Id video 11 karakter dari tautan, atau null kalau bukan tautan lagu YouTube. */
    fun videoId(uri: Uri): String? {
        val host = uri.host?.lowercase() ?: return null
        if (!isYouTubeHost(host)) return null
        val segs = uri.pathSegments
        val first = segs.firstOrNull()
        val id = when {
            host == "youtu.be" -> first
            first == "watch" -> uri.getQueryParameter("v")
            first == "shorts" || first == "embed" || first == "live" || first == "v" -> segs.getOrNull(1)
            else -> null
        }
        return id?.takeIf { VIDEO_ID.matches(it) }
    }

    /** Id playlist dari tautan /playlist?list=... atau watch?list=... (awalan "VL" dibuang). */
    fun playlistId(uri: Uri): String? {
        val host = uri.host?.lowercase() ?: return null
        if (!isYouTubeHost(host)) return null
        val first = uri.pathSegments.firstOrNull()
        if (first != "playlist" && first != "watch") return null
        return uri.getQueryParameter("list")?.removePrefix("VL")?.takeIf { LIST_ID.matches(it) }
    }

    /** Tautan pertama dalam teks yang dibagikan (mis. "Dengar lagu ini https://youtu.be/xxxx"). */
    fun firstUrl(text: String?): Uri? =
        text?.let { URL_IN_TEXT.find(it)?.value }?.trimEnd('.', ',', ')')?.let(Uri::parse)
}
