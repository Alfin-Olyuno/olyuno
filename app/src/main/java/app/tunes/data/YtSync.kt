package app.tunes.data

import app.tunes.innertube.InnerTube
import app.tunes.innertube.SongItem
import app.tunes.innertube.AccountTab
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.MixFeed
import app.tunes.innertube.fetchAccountTab
import app.tunes.innertube.fetchHistory
import app.tunes.innertube.fetchMixPlaylists
import app.tunes.innertube.fetchMixSongs
import app.tunes.innertube.fetchLiked
import app.tunes.innertube.registerPlayback
import app.tunes.innertube.addToPlaylist
import app.tunes.innertube.createPlaylist
import app.tunes.innertube.setLike
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Hasil [YtSync.pushPlaylist]: [sent] = jumlah lagu yang dikirim, [skippedLocal] = lagu perangkat yang dilewati. */
data class PlaylistPush(val remoteId: String, val created: Boolean, val sent: Int, val skippedLocal: Int)

private const val CREATE_BATCH = 50

/**
 * Menyambungkan penyimpanan lokal (Room) dengan akun YouTube Music. Tanpa login semua fungsi di sini
 * tidak melakukan apa-apa, jadi aplikasi tetap jalan seperti biasa (hanya tersimpan di perangkat ini).
 */
class YtSync(
    private val innerTube: InnerTube,
    private val account: AccountStore,
    private val repo: SongRepository,
    private val links: PlaylistLinkStore,
) {
    private val mutex = Mutex()

    /** Kirim suka / batal suka ke akun. Kalau gagal, perubahan tetap antre dan dicoba lagi di sinkron berikutnya. */
    suspend fun pushLike(videoId: String, liked: Boolean) {
        if (videoId.startsWith(app.tunes.innertube.LOCAL_ID_PREFIX)) return // lagu perangkat: tidak ada di YouTube
        val cookie = account.cookie() ?: return
        account.setPending(videoId, liked)
        // Sinkronisasi otomatis mati: perubahan tetap antre dan baru naik saat "Sinkronkan sekarang".
        if (!account.autoSync.value) return
        innerTube.setLike(cookie, videoId, liked)
        account.clearPending(videoId, liked)
    }

    /**
     * Kirim playlist lokal ke akun YouTube Music. Belum pernah dikirim: dibuat sebagai playlist PRIBADI baru.
     * Sudah pernah (ada tautan): lagu yang belum ada ditambahkan (yang sudah ada dilewati YouTube Music). Perubahan
     * lain (hapus / urutan) tidak ikut dikirim. Lagu perangkat tidak ada di YouTube jadi dilewati.
     * Mengembalikan null kalau belum masuk; melempar exception kalau jaringan / sesi bermasalah.
     */
    suspend fun pushPlaylist(playlist: LocalPlaylist): PlaylistPush? {
        val cookie = account.cookie() ?: return null
        val ids = playlist.songs.map { it.videoId }.filterNot { it.startsWith(app.tunes.innertube.LOCAL_ID_PREFIX) }.distinct()
        val skipped = playlist.songs.size - ids.size
        return mutex.withLock {
            val existing = links.get(playlist.id)
            if (ids.isEmpty()) return@withLock PlaylistPush(existing.orEmpty(), created = false, sent = 0, skippedLocal = skipped)
            if (existing != null) {
                val sent = innerTube.addToPlaylist(cookie, existing, ids)
                PlaylistPush(existing, created = false, sent = sent, skippedLocal = skipped)
            } else {
                val first = ids.take(CREATE_BATCH)
                val remote = innerTube.createPlaylist(cookie, playlist.name, first, description = "Dibuat lewat Olyuno")
                links.set(playlist.id, remote)
                val rest = ids.drop(CREATE_BATCH)
                if (rest.isNotEmpty()) innerTube.addToPlaylist(cookie, remote, rest)
                PlaylistPush(remote, created = true, sent = ids.size, skippedLocal = skipped)
            }
        }
    }

    /** Catat putaran ke riwayat akun. Bersifat tambahan, jadi kegagalan diabaikan. */
    suspend fun registerPlay(videoId: String) {
        val cookie = account.cookie() ?: return
        if (!account.autoSync.value) return
        try {
            innerTube.registerPlayback(cookie, videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // diabaikan
        }
    }

    /** Playlist perpustakaan akun untuk "Mix untukmu", atau null kalau belum masuk. */
    suspend fun fetchMixPlaylists(): MixFeed? {
        val cookie = account.cookie() ?: return null
        return innerTube.fetchMixPlaylists(cookie)
    }

    /** Isi satu tab halaman "Akun" dari perpustakaan akun YouTube Music, atau null kalau belum masuk. */
    suspend fun fetchAccountTab(tab: AccountTab): List<CollectionRef>? {
        val cookie = account.cookie() ?: return null
        return innerTube.fetchAccountTab(cookie, tab)
    }

    /** Lagu satu Mix asli YouTube Music (butuh login supaya Mix personal sama dengan di aplikasi aslinya), atau null kalau belum masuk. */
    suspend fun fetchMixSongs(playlistId: String): List<SongItem>? {
        val cookie = account.cookie() ?: return null
        return innerTube.fetchMixSongs(cookie, playlistId)
    }

    /** Riwayat dari akun, atau null kalau belum masuk. */
    suspend fun fetchHistory(): List<SongItem>? {
        val cookie = account.cookie() ?: return null
        return innerTube.fetchHistory(cookie)
    }

    /**
     * Sinkron "Disukai". Pertama kali (setelah login): gabung dua arah, lagu yang hanya disukai di sini ikut
     * naik ke akun. Berikutnya: akun jadi acuan, kecuali perubahan lokal yang masih antre.
     * Melempar exception kalau jaringan / sesi bermasalah.
     */
    suspend fun syncLikes() {
        mutex.withLock {
            val cookie = account.cookie() ?: return@withLock
            val remote = innerTube.fetchLiked(cookie)
            val remoteIds = remote.songs.mapTo(HashSet()) { it.videoId }
            val localOnly = repo.likedSnapshot().filter {
                it.videoId !in remoteIds && !it.videoId.startsWith(app.tunes.innertube.LOCAL_ID_PREFIX)
            }

            if (!account.likesSynced) {
                localOnly.forEach { account.setPending(it.videoId, true) }
            }
            val pending = account.pending()

            // Yang sudah dibatalkan di sini tapi belum sampai ke akun jangan disukai lagi.
            val toLike = remote.songs.filter { pending[it.videoId] != false }
            // Daftar akun yang tidak lengkap / kosong tidak boleh dipakai untuk mencabut suka lokal.
            val toUnlike = if (remote.complete && remote.songs.isNotEmpty()) {
                localOnly.map { it.videoId }.filter { pending[it] != true }
            } else {
                emptyList()
            }
            repo.applyRemoteLikes(toLike, toUnlike)

            for ((id, liked) in account.pending()) {
                innerTube.setLike(cookie, id, liked)
                account.clearPending(id, liked)
            }
            account.likesSynced = true
        }
    }
}
