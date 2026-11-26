package app.tunes.data

import android.content.Context
import android.os.StatFs
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import app.tunes.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Rincian pemakaian penyimpanan Olyuno (byte) + ruang perangkat. */
data class StorageUsage(
    val downloads: Long,
    val audioCache: Long,
    val lyricsCache: Long,
    val imageCache: Long,
    val appData: Long,
    val deviceFree: Long,
    val deviceTotal: Long,
) {
    val total: Long get() = downloads + audioCache + lyricsCache + imageCache + appData
    val cache: Long get() = audioCache + lyricsCache + imageCache
}

/**
 * Mengukur dan membersihkan penyimpanan aplikasi: unduhan, cache audio streaming, cache lirik &
 * terjemahan, cache gambar, dan data aplikasi (database + pengaturan). Semua pengukuran / penghapusan
 * berjalan di thread IO.
 */
class StorageManager(private val context: Context, private val container: AppContainer) {

    suspend fun measure(): StorageUsage = withContext(Dispatchers.IO) {
        val caches = container.caches
        val downloads = runCatching { caches.downloadCache.cacheSpace }.getOrDefault(0L) +
            dirSize(File(context.filesDir, "download_thumbs"))
        val audio = runCatching { caches.playbackCache.cacheSpace }.getOrDefault(0L)
        val lyrics = dirSize(File(context.cacheDir, "lyrics")) + dirSize(File(context.cacheDir, "translations"))
        val images = imageCacheSize()

        val skip = setOf("downloads_cache", "download_thumbs")
        val files = context.filesDir.listFiles().orEmpty().filter { it.name !in skip }.sumOf { dirSize(it) }
        val dbDir = context.getDatabasePath("tunes.db").parentFile
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val appData = files + dirSize(dbDir) + dirSize(prefsDir)

        val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        StorageUsage(
            downloads = downloads,
            audioCache = audio,
            lyricsCache = lyrics,
            imageCache = images,
            appData = appData,
            deviceFree = stat?.availableBytes ?: 0L,
            deviceTotal = stat?.totalBytes ?: 0L,
        )
    }

    suspend fun clearAudioCache() = withContext(Dispatchers.IO) {
        val cache = container.caches.playbackCache
        cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
    }

    suspend fun clearLyricsCache() = withContext(Dispatchers.IO) {
        container.lyrics.clearAll()
        container.translations.clearAll()
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        val loader = context.imageLoader
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        Unit
    }

    suspend fun clearAllCache() {
        clearAudioCache()
        clearLyricsCache()
        clearImageCache()
    }

    /** Hapus semua unduhan (lagu + paket playlist/album). */
    fun removeAllDownloads() = container.downloads.removeAll()

    /** Ubah batas cache audio: tersimpan, langsung berlaku, dan kelebihannya dibuang. */
    suspend fun setAudioCacheLimit(mb: Int) = withContext(Dispatchers.IO) {
        container.storagePrefs.setAudioCacheLimitMb(mb)
        container.caches.setPlaybackLimit(mb.toLong() * 1024 * 1024)
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun imageCacheSize(): Long = runCatching { context.imageLoader.diskCache?.size ?: 0L }.getOrDefault(0L)

    private fun dirSize(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        return f.listFiles().orEmpty().sumOf { dirSize(it) }
    }
}
