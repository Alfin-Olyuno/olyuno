package app.tunes.innertube

import app.tunes.data.AudioQuality
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache URL stream di memori; URL YouTube kedaluwarsa dalam beberapa jam.
 *
 * [quality] memberi kualitas audio yang berlaku saat ini (sudah diputuskan: Otomatis -> Tinggi/Rendah sesuai
 * jaringan). Stream yang sudah ada di cache tetap dipakai apa adanya supaya satu lagu tidak berganti format di
 * tengah pemutaran; pengaturan baru berlaku untuk lagu berikutnya lewat [dropExcept].
 */
class StreamResolver(
    private val innerTube: InnerTube,
    private val onLoudness: (String, Float) -> Unit = { _, _ -> },
    private val quality: () -> AudioQuality = { AudioQuality.High },
) {
    private val cache = ConcurrentHashMap<String, AudioStream>()

    suspend fun resolve(videoId: String): AudioStream {
        val now = System.currentTimeMillis()
        cache[videoId]?.takeIf { it.expiresAtMs > now }?.let { return it }
        return innerTube.resolveStream(videoId, quality()).also {
            cache[videoId] = it
            it.loudnessDb?.let { db -> onLoudness(videoId, db) }
        }
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    /** Buang semua stream di cache kecuali lagu [keepId] (yang sedang diputar), setelah kualitas diganti. */
    fun dropExcept(keepId: String?) {
        cache.keys.filter { it != keepId }.forEach { cache.remove(it) }
    }
}
