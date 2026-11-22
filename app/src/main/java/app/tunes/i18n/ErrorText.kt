package app.tunes.i18n

import app.tunes.innertube.InnerTubeException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Pesan error yang aman ditampilkan ke pengguna, dalam bahasa aplikasi saat ini.
 * Kegagalan jaringan umum dipetakan ke kalimat yang jelas; pesan buatan aplikasi sendiri (yang ada di kamus)
 * diterjemahkan; selain itu dipakai [fallback] (teks Indonesia, diterjemahkan lewat [t]).
 * Pesan mentah dari library (selalu English / teknis) sengaja tidak ditampilkan.
 */
fun Throwable.userMessage(fallback: String): String {
    var c: Throwable? = this
    while (c != null) {
        when (c) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                return t("Tidak ada koneksi internet")
            is SocketTimeoutException -> return t("Koneksi terlalu lama, coba lagi")
            is SSLException -> return t("Koneksi aman gagal, coba lagi")
        }
        c = c.cause
    }
    val msg = message.orEmpty()
    if (this is InnerTubeException) {
        when {
            msg.startsWith("HTTP 401") || msg.startsWith("HTTP 403") ->
                return t("Sesi YouTube Music habis, masuk lagi lewat tab Anda")
            msg.startsWith("HTTP 429") -> return t("Terlalu banyak permintaan, coba lagi nanti")
            msg.startsWith("HTTP 5") -> return t("Server sedang bermasalah, coba lagi nanti")
            msg.startsWith("Semua client ditolak") || msg.startsWith("All clients rejected") ->
                return t("YouTube Music menolak permintaan, coba lagi nanti")
        }
    }
    if (this is InterruptedIOException && msg.isNotEmpty() && msg !in KnownMessages) {
        return t("Koneksi terlalu lama, coba lagi")
    }
    if (msg.isNotEmpty() && msg in KnownMessages) return t(msg)
    // Server lirik membalas kode HTTP tertentu (mis. 429 / 5xx): tampilkan kodenya supaya penyebabnya kelihatan.
    if (this is java.io.IOException && (msg.startsWith("Server lirik membalas HTTP") || msg.startsWith("Lyrics server replied HTTP"))) return msg
    return t(fallback)
}

/** Semua kalimat buatan aplikasi (kunci Indonesia dan terjemahan English), untuk mengenali pesan sendiri. */
private val KnownMessages: Set<String> by lazy { EnglishStrings.keys + EnglishStrings.values }
