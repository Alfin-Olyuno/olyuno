package app.tunes.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.tunes.innertube.StreamResolver
import okhttp3.OkHttpClient
import java.io.File
import java.util.TreeSet
import java.util.concurrent.TimeUnit

/**
 * Dua cache audio yang dipakai bersama oleh pemutar dan pengunduh (satu proses hanya boleh punya satu
 * [SimpleCache] per folder, jadi dibuat sekali di sini, bukan di PlaybackService).
 *
 * - [playbackCache]: hasil streaming, LRU 512 MB, boleh dibuang otomatis (folder cache aplikasi).
 * - [downloadCache]: lagu yang diunduh pengguna, tidak pernah dibuang otomatis (folder files aplikasi,
 *   jadi tidak ikut terhapus saat "Hapus cache").
 */
@OptIn(UnstableApi::class)
class MediaCaches(context: Context, resolver: StreamResolver, playbackLimitBytes: Long = 512L * 1024 * 1024) {
    private val db = StandaloneDatabaseProvider(context)
    private val playbackEvictor = AdjustableLruEvictor(playbackLimitBytes)

    val playbackCache = SimpleCache(
        File(context.cacheDir, "audio"),
        playbackEvictor,
        db,
    )

    /** Ubah batas cache streaming; kelebihan (yang terlama dipakai) langsung dibuang. */
    fun setPlaybackLimit(bytes: Long) = playbackEvictor.setLimit(playbackCache, bytes)

    val downloadCache = SimpleCache(
        File(context.filesDir, "downloads_cache"),
        NoOpCacheEvictor(),
        db,
    )

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val upstream = DataSource.Factory { ChunkedYoutubeDataSource(okHttp, resolver) }

    /**
     * Untuk pemutar: cek unduhan dulu (hanya baca), lalu cache streaming, lalu jaringan.
     * Unduhan tidak ikut terisi saat streaming biasa.
     */
    fun playbackFactory(): DataSource.Factory {
        val streaming = CacheDataSource.Factory()
            .setCache(playbackCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(streaming)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Untuk pengunduh: tulis langsung ke [downloadCache]. */
    fun downloadFactory(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(upstream)
}

/**
 * LRU seperti [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor], tapi batasnya bisa diubah
 * saat aplikasi berjalan (pengaturan "Batas cache audio" langsung berlaku tanpa membuka ulang aplikasi).
 */
@OptIn(UnstableApi::class)
private class AdjustableLruEvictor(@Volatile private var maxBytes: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { a, b ->
        val d = a.lastTouchTimestamp - b.lastTouchTimestamp
        if (d == 0L) a.compareTo(b) else if (d < 0L) -1 else 1
    }
    private var currentSize = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() {}

    @Synchronized
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != -1L) evict(cache, length)
    }

    @Synchronized
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        currentSize += span.length
        evict(cache, 0)
    }

    @Synchronized
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    @Synchronized
    fun setLimit(cache: Cache, bytes: Long) {
        maxBytes = bytes
        evict(cache, 0)
    }

    private fun evict(cache: Cache, requiredSpace: Long) {
        while (currentSize + requiredSpace > maxBytes && spans.isNotEmpty()) {
            try {
                cache.removeSpan(spans.first())
            } catch (e: Cache.CacheException) {
                // lewati; span berikutnya dicoba lain kali
                return
            }
        }
    }
}
