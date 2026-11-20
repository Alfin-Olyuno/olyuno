package app.tunes.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import android.net.Uri
import app.tunes.data.DownloadEntry
import app.tunes.data.DownloadStore
import app.tunes.data.DownloadedCollection
import app.tunes.data.DownloadedCollectionStore
import app.tunes.innertube.CollectionInfo
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.SongItem
import app.tunes.innertube.isLocal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import app.tunes.i18n.t

/**
 * Unduh lagu untuk didengar offline. Audio ditulis ke [MediaCaches.downloadCache] lewat jalur yang sama
 * dengan streaming ([ChunkedYoutubeDataSource]: tahan putus, URL kedaluwarsa diperbarui otomatis), jadi lagu
 * yang sudah diunduh langsung diputar dari sana tanpa jaringan. Unduhan yang terputus dilanjutkan dari byte
 * terakhir yang sudah tersimpan.
 *
 * Berjalan selama aplikasi hidup (mis. saat musik diputar). Kalau aplikasi ditutup, yang belum selesai
 * dilanjutkan saat aplikasi dibuka lagi.
 */
@OptIn(UnstableApi::class)
class SongDownloads(
    private val store: DownloadStore,
    private val collectionStore: DownloadedCollectionStore,
    private val thumbDir: File,
    private val caches: Lazy<MediaCaches>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val jobs = ConcurrentHashMap<String, Job>()

    val entries: StateFlow<List<DownloadEntry>> = store.items

    /** Paket playlist / album yang diunduh (satu baris di halaman Diunduh). */
    val collections: StateFlow<List<DownloadedCollection>> = collectionStore.items

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Kemajuan 0..1 untuk unduhan yang sedang berjalan. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _failed = MutableStateFlow<Set<String>>(emptySet())

    /** Unduhan yang gagal sesi ini (jaringan putus dsb.); bisa diulang. */
    val failed: StateFlow<Set<String>> = _failed.asStateFlow()

    /**
     * [standalone] false = lagu ini hanya bagian dari paket playlist/album, jadi tidak tampil sebagai
     * lagu sendiri di halaman Diunduh. Kalau lagunya sudah ada sebagai unduhan biasa, statusnya tetap.
     */
    fun enqueue(song: SongItem, standalone: Boolean = true) {
        val id = song.videoId
        if (id.isBlank()) return
        val existing = store.get(id)
        if (existing?.done == true) {
            if (standalone && !existing.standalone) store.put(existing.copy(standalone = true))
            return
        }
        if (jobs[id]?.isActive == true) {
            if (standalone && existing != null && !existing.standalone) store.put(existing.copy(standalone = true))
            return
        }
        store.put(
            DownloadEntry(
                song, done = false, sizeBytes = 0, addedAt = System.currentTimeMillis(),
                standalone = standalone || existing?.standalone == true,
            ),
        )
        _failed.update { it - id }
        start(song)
    }

    /**
     * Unduh satu playlist / album sebagai satu paket: metadata + sampulnya disimpan, lalu semua lagunya
     * diantre (yang sudah selesai dilewati).
     */
    fun enqueueCollection(ref: CollectionRef, info: CollectionInfo, songs: List<SongItem>) {
        val list = songs.filter { it.videoId.isNotBlank() && !it.isLocal }.distinctBy { it.videoId }
        if (list.isEmpty()) return
        val existing = collectionStore.get(ref.key)
        val localThumb = existing?.ref?.thumbnail?.takeIf { it.startsWith("file:") }
        collectionStore.put(
            DownloadedCollection(
                ref = if (localThumb != null) ref.copy(thumbnail = localThumb) else ref,
                info = if (localThumb != null) info.copy(thumbnail = localThumb) else info,
                songs = list,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            ),
        )
        list.forEach { enqueue(it, standalone = false) }
        if (localThumb == null) scope.launch { saveThumbnail(ref.key) }
    }

    /** Ulangi lagu paket yang gagal / belum selesai. */
    fun retryCollection(key: String) {
        val c = collectionStore.get(key) ?: return
        c.songs.forEach { enqueue(it, standalone = false) }
    }

    /** Hapus paket; lagu yang hanya milik paket ini ikut terhapus (yang juga diunduh sendiri atau ada di paket lain tetap). */
    fun removeCollection(key: String) {
        val c = collectionStore.get(key) ?: return
        collectionStore.remove(key)
        val stillUsed = collectionStore.items.value.flatMap { it.songs }.map { it.videoId }.toSet()
        c.songs.forEach { s ->
            val e = store.get(s.videoId)
            if (e != null && !e.standalone && s.videoId !in stillUsed) remove(s.videoId)
        }
        scope.launch {
            val local = c.ref.thumbnail
            if (local != null && local.startsWith("file:")) {
                runCatching { File(Uri.parse(local).path.orEmpty()).takeIf { it.parentFile == thumbDir }?.delete() }
            }
        }
    }

    /**
     * Hapus unduhan satu lagu dari menu. Kalau lagu ini juga bagian dari paket playlist, file-nya
     * dipertahankan untuk paket itu dan hanya hilang dari daftar lagu sendiri.
     */
    fun removeSong(videoId: String) {
        val e = store.get(videoId)
        val inPack = collectionStore.items.value.any { c -> c.songs.any { it.videoId == videoId } }
        if (e != null && e.done && e.standalone && inPack) {
            store.put(e.copy(standalone = false))
        } else {
            remove(videoId)
        }
    }

    private fun saveThumbnail(key: String) {
        try {
            val c = collectionStore.get(key) ?: return
            val url = c.info.thumbnail ?: c.ref.thumbnail ?: return
            if (!url.startsWith("http")) return
            thumbDir.mkdirs()
            val f = File(thumbDir, "${key.hashCode().toUInt()}.img")
            val conn = URL(url).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            conn.getInputStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            if (f.length() <= 0) {
                f.delete()
                return
            }
            val local = Uri.fromFile(f).toString()
            // Paket mungkin sudah dihapus selama sampul diunduh.
            val cur = collectionStore.get(key)
            if (cur == null) {
                f.delete()
                return
            }
            collectionStore.put(cur.copy(ref = cur.ref.copy(thumbnail = local), info = cur.info.copy(thumbnail = local)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // sampul tetap dimuat dari URL aslinya (cache Coil)
        }
    }

    /** Lanjutkan yang belum selesai (dipanggil saat aplikasi dibuka). */
    fun resumePending() {
        store.items.value.filter { !it.done }.forEach { e ->
            if (jobs[e.song.videoId]?.isActive != true) start(e.song)
        }
    }

    /** Hapus semua unduhan (lagu, paket playlist/album, dan sampulnya); unduhan yang berjalan dibatalkan. */
    fun removeAll() {
        collectionStore.items.value.map { it.ref.key }.forEach { removeCollection(it) }
        store.items.value.map { it.song.videoId }.forEach { remove(it) }
    }

    /** Batalkan unduhan yang berjalan (kalau ada) dan hapus lagunya dari penyimpanan. */
    fun remove(videoId: String) {
        val running = jobs.remove(videoId)
        scope.launch {
            running?.cancelAndJoin()
            try {
                caches.value.downloadCache.removeResource(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // sisa file akan tertimpa kalau lagu diunduh lagi
            }
            store.remove(videoId)
            _failed.update { it - videoId }
            _progress.update { it - videoId }
        }
    }

    private fun start(song: SongItem) {
        val id = song.videoId
        val job = scope.launch(start = CoroutineStart.LAZY) {
            gate.withPermit { download(song) }
        }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id, job)
            _progress.update { it - id }
        }
        job.start()
    }

    private suspend fun download(song: SongItem) {
        val id = song.videoId
        try {
            _progress.update { it + (id to 0f) }
            val c = caches.value
            val dataSource = c.downloadFactory().createDataSource()
            val spec = DataSpec.Builder().setUri("tunes://$id").setKey(id).build()
            val writer = CacheWriter(dataSource, spec, null) { requestLength, bytesCached, _ ->
                if (requestLength > 0) {
                    _progress.update { it + (id to (bytesCached.toFloat() / requestLength).coerceIn(0f, 1f)) }
                }
            }
            val handle = currentCoroutineContext()[Job]!!.invokeOnCompletion { cause ->
                if (cause != null) writer.cancel()
            }
            try {
                writer.cache()
            } finally {
                handle.dispose()
            }
            currentCoroutineContext().ensureActive()

            // Anggap selesai hanya kalau seluruh isi file benar-benar ada di cache.
            val length = ContentMetadata.getContentLength(c.downloadCache.getContentMetadata(id))
            if (length <= 0 || !c.downloadCache.isCached(id, 0, length)) {
                throw IOException(t("Unduhan tidak lengkap"))
            }
            val standalone = store.get(id)?.standalone ?: true
            store.put(
                DownloadEntry(song, done = true, sizeBytes = length, addedAt = System.currentTimeMillis(), standalone = standalone),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Dibatalkan di tengah jalan bisa berwujud IOException: jangan dianggap gagal.
            currentCoroutineContext().ensureActive()
            _failed.update { it + id }
        }
    }
}
