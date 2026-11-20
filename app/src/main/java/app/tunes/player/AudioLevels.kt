package app.tunes.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Pengukur kerasnya suara lagu yang SEBENARNYA. Dipasang sebagai [TeeAudioProcessor] di jalur audio ExoPlayer,
 * jadi yang diukur adalah sampel PCM hasil dekode lagu itu sendiri: tanpa izin rekam, tanpa mikrofon, dan tidak
 * terpengaruh volume perangkat (hanya mengikuti keras-lembutnya lagu).
 *
 * Sinyal dipecah jadi [BANDS] pita frekuensi (bass -> treble). Tiap 25 md audio dihitung RMS tiap pita lalu
 * diubah ke skala dB 0..1. Tiap bingkai diberi cap waktu kapan bunyinya terdengar (mengikuti jumlah sampel +
 * latensi perangkat), jadi [read] mengembalikan level yang cocok dengan suara yang sedang terdengar.
 */
@OptIn(UnstableApi::class)
class AudioLevels : TeeAudioProcessor.AudioBufferSink {

    private class Frame {
        @Volatile var atMs = 0L
        val level = FloatArray(BANDS)
    }

    private val ring = Array(RING) { Frame() }
    private var head = 0 // indeks bingkai berikutnya yang ditulis
    private var count = 0
    private val lock = Any()

    // ---- state analisis (hanya diakses dari thread pemutaran)
    private var rate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var hopFrames = 1_102
    private var inHop = 0
    private var nextAtMs = 0L
    private val a = FloatArray(3)
    private val s1 = FloatArray(3) // tahap 1 tiap low-pass
    private val s2 = FloatArray(3) // tahap 2 tiap low-pass
    private val sumSq = DoubleArray(BANDS)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz.coerceAtLeast(8_000)
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        hopFrames = (rate * HOP_MS / 1000).coerceAtLeast(1)
        for (i in 0 until 3) a[i] = (1.0 - exp(-2.0 * Math.PI * CROSSOVER_HZ[i] / rate)).toFloat()
        s1.fill(0f); s2.fill(0f); sumSq.fill(0.0)
        inHop = 0
        nextAtMs = 0L
        synchronized(lock) { head = 0; count = 0 }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val bytesPer = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return // format lain tidak dianalisis (jarang; ExoPlayer memakai 16-bit / float)
        }
        val b = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        var pos = b.position()
        val frameBytes = bytesPer * channels
        val frames = (b.limit() - pos) / frameBytes
        repeat(frames) {
            var x = 0f
            for (ch in 0 until channels) {
                x += if (bytesPer == 2) b.getShort(pos) / 32768f else b.getFloat(pos)
                pos += bytesPer
            }
            x /= channels
            // Low-pass 2 tahap di 3 titik pisah; selisih antar-tahap = energi tiap pita.
            var prev = 0f
            for (i in 0 until 3) {
                s1[i] += a[i] * (x - s1[i])
                s2[i] += a[i] * (s1[i] - s2[i])
                val band = s2[i] - prev
                sumSq[i] += (band * band).toDouble()
                prev = s2[i]
            }
            val top = x - prev
            sumSq[3] += (top * top).toDouble()
            if (++inHop >= hopFrames) emit()
        }
    }

    private fun emit() {
        val now = SystemClock.uptimeMillis()
        // Awal / setelah seek / habis jeda: bunyi pertama terdengar sebentar lagi. Selanjutnya waktu
        // dihitung dari jumlah sampel, jadi tetap akurat walau ExoPlayer mengisi buffer lebih dulu.
        if (nextAtMs < now) nextAtMs = now + OUTPUT_LATENCY_MS
        synchronized(lock) {
            val f = ring[head]
            f.atMs = nextAtMs
            for (i in 0 until BANDS) {
                val rms = sqrt(sumSq[i] / inHop)
                val db = 20.0 * log10(rms + 1e-9)
                f.level[i] = ((db - FLOOR_DB[i]) / (CEIL_DB[i] - FLOOR_DB[i])).toFloat().coerceIn(0f, 1f)
            }
            head = (head + 1) % RING
            if (count < RING) count++
        }
        nextAtMs += HOP_MS
        inHop = 0
        sumSq.fill(0.0)
    }

    /**
     * Isi [out] (ukuran [BANDS]) dengan level 0..1 yang sedang terdengar pada waktu [nowMs]
     * (SystemClock.uptimeMillis). Mengembalikan false kalau belum ada data (hening / baru mulai).
     */
    fun read(nowMs: Long, out: FloatArray): Boolean = synchronized(lock) {
        var i = (head - 1 + RING) % RING
        var n = 0
        while (n < count) {
            val f = ring[i]
            if (f.atMs <= nowMs) {
                if (nowMs - f.atMs > STALE_MS) return false
                for (k in 0 until BANDS) out[k] = f.level[k]
                return true
            }
            i = (i - 1 + RING) % RING
            n++
        }
        false
    }

    companion object {
        const val BANDS = 4
        private const val HOP_MS = 25L
        private const val RING = 160 // ~4 detik
        private const val OUTPUT_LATENCY_MS = 90L
        private const val STALE_MS = 200L
        private val CROSSOVER_HZ = floatArrayOf(200f, 1000f, 4000f)
        private val FLOOR_DB = doubleArrayOf(-50.0, -54.0, -58.0, -62.0)
        private val CEIL_DB = doubleArrayOf(-12.0, -17.0, -23.0, -29.0)
    }
}
