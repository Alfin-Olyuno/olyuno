package app.tunes.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.tunes.innertube.AudioStream
import app.tunes.innertube.InnerTubeException
import app.tunes.innertube.StreamResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStream
import app.tunes.i18n.t

/**
 * DataSource untuk "tunes://<videoId>" yang tahan putus:
 *
 * 1. Mengunduh per potongan ~1 MB lewat parameter `range=` (seperti klien YouTube asli), bukan satu
 *    koneksi panjang yang sering dipotong server (HTTP 416 / koneksi reset).
 * 2. Potongan yang gagal diulang otomatis dengan jeda bertahap; pemutar tidak melihat error.
 * 3. Kalau URL kedaluwarsa/ditolak (403/410/416), URL baru diminta ke YouTube lalu unduhan
 *    dilanjutkan dari byte terakhir.
 *
 * URL asli (googlevideo) di-resolve di sini, jadi tidak perlu ResolvingDataSource lagi.
 */
@OptIn(UnstableApi::class)
class ChunkedYoutubeDataSource(
    private val client: OkHttpClient,
    private val resolver: StreamResolver,
) : BaseDataSource(/* isNetwork = */ true) {

    private var spec: DataSpec? = null
    private var videoId = ""
    private var stream: AudioStream? = null
    private var knownLength: Long? = null
    private var body: ResponseBody? = null
    private var input: InputStream? = null
    private var position = 0L
    private var end = -1L        // eksklusif; -1 = panjang tidak diketahui
    private var chunkEnd = -1L   // eksklusif; -1 = request tanpa batas
    private var opened = false
    private var readFailures = 0

    // ------------------------------------------------------------------ open

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        transferInitializing(dataSpec)
        videoId = dataSpec.uri.authority ?: dataSpec.key ?: throw InnerTubeException(t("videoId kosong"))
        position = dataSpec.position
        readFailures = 0

        val s = resolve(refresh = false)
        stream = s
        val clen = clenOf(s.url)
        knownLength = clen
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> position + dataSpec.length
            clen != null -> clen
            else -> -1L
        }
        if (clen != null && end > clen) end = clen

        if (end >= 0 && position >= end) {
            // ExoPlayer kadang membuka tepat di ujung file; jangan kirim request (server menjawab 416).
            chunkEnd = -1L
            opened = true
            transferStarted(dataSpec)
            return 0
        }
        connect()
        opened = true
        transferStarted(dataSpec)
        return if (end >= 0) (end - position).coerceAtLeast(0) else C.LENGTH_UNSET.toLong()
    }

    // ------------------------------------------------------------------ read

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (end >= 0 && position >= end) return C.RESULT_END_OF_INPUT
            if (chunkEnd >= 0 && position >= chunkEnd) connect()

            val want = if (chunkEnd >= 0) minOf(length.toLong(), chunkEnd - position).toInt() else length
            val n = try {
                input!!.read(buffer, offset, want)
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                recover(e)
                continue
            }
            if (n == -1) {
                if (end < 0) return C.RESULT_END_OF_INPUT
                recover(EOFException(t("stream berakhir di %d dari %d", position, end)))
                continue
            }
            readFailures = 0
            position += n
            bytesTransferred(n)
            return n
        }
    }

    /** Koneksi putus di tengah potongan: tunggu sebentar, (kalau berulang) minta URL baru, buka lagi. */
    private fun recover(cause: IOException) {
        if (++readFailures > MAX_READ_FAILURES) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                cause, spec!!, HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
        backoff(readFailures)
        if (readFailures >= 2) refreshStream()
        connect()
    }

    // --------------------------------------------------------------- connect

    /** Buka potongan yang mulai dari [position], dengan retry + refresh URL bila perlu. */
    private fun connect() {
        var attempt = 0
        while (true) {
            try {
                openChunkOnce()
                return
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= MAX_CONNECT_ATTEMPTS || !isRetryable(e)) throw e
                backoff(attempt)
                if (needsFreshUrl(e) || attempt >= 2) refreshStream()
            }
        }
    }

    private fun openChunkOnce() {
        closeStream()
        val d = spec!!
        val s = stream!!
        val url = s.url.substringBefore('#')
        val last = if (end >= 0) minOf(position + CHUNK, end) else -1L
        chunkEnd = last
        // Penting: pakai SATU penanda rentang saja. Kalau `range=` di URL dan header `Range` dikirim
        // bersamaan, server menghitung header relatif terhadap potongan itu sehingga setiap request
        // dari posisi > 0 dijawab 416 (inilah penyebab seek "loading terus").
        val useQueryRange = last > position && !url.contains("range=")
        val finalUrl = if (useQueryRange) "$url&range=$position-${last - 1}" else url

        val rb = Request.Builder().url(finalUrl)
        d.httpRequestHeaders.forEach { (k, v) -> rb.header(k, v) }
        rb.header("User-Agent", s.userAgent)
        rb.header("Accept-Encoding", "identity")
        if (!useQueryRange) rb.header("Range", "bytes=$position-")

        val resp = try {
            client.newCall(rb.build()).execute()
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, d, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            val msg = resp.message
            val headers = resp.headers.toMultimap()
            resp.close()
            throw HttpDataSource.InvalidResponseCodeException(code, msg, null, headers, d, ByteArray(0))
        }
        if (!useQueryRange && position > 0 && resp.code == 200) {
            resp.close()
            throw IOException(t("server mengabaikan Range di posisi %d", position))
        }
        body = resp.body
        input = resp.body?.byteStream()
    }

    // ----------------------------------------------------------- URL & retry

    private fun resolve(refresh: Boolean): AudioStream {
        if (refresh) resolver.invalidate(videoId)
        return runBlocking { resolver.resolve(videoId) }
    }

    /** Minta URL baru. Kegagalan resolve diabaikan (URL lama dicoba lagi); format berubah = menyerah. */
    private fun refreshStream() {
        val fresh = try {
            resolve(refresh = true)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            return
        } catch (e: Exception) {
            return
        }
        val newLen = clenOf(fresh.url)
        val old = knownLength
        if (old != null && newLen != null && newLen != old) {
            // Format/ukuran berbeda: byte yang sudah ada di cache tidak cocok lagi.
            throw InnerTubeException(t("format stream berubah saat retry (%d -> %d)", old, newLen))
        }
        stream = fresh
    }

    private fun isRetryable(e: IOException): Boolean {
        if (e is HttpDataSource.InvalidResponseCodeException) return e.responseCode != 404
        return true
    }

    private fun needsFreshUrl(e: IOException): Boolean =
        e is HttpDataSource.InvalidResponseCodeException && e.responseCode in FRESH_URL_CODES

    private fun backoff(n: Int) {
        try {
            Thread.sleep(400L * n)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(t("dibatalkan"))
        }
    }

    // ----------------------------------------------------------------- misc

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        closeStream()
        if (opened) {
            opened = false
            transferEnded()
        }
        spec = null
        stream = null
    }

    private fun closeStream() {
        runCatching { body?.close() }
        body = null
        input = null
    }

    private fun clenOf(url: String): Long? = CLEN.find(url)?.groupValues?.get(1)?.toLongOrNull()

    private companion object {
        const val CHUNK = 1L * 1024 * 1024
        const val MAX_CONNECT_ATTEMPTS = 5
        const val MAX_READ_FAILURES = 4
        val FRESH_URL_CODES = setOf(403, 410, 416)
        val CLEN = Regex("[?&#]clen=(\\d+)")
    }
}
