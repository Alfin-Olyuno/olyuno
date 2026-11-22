package app.tunes.data

import app.tunes.innertube.InnerTube
import app.tunes.innertube.SongItem
import app.tunes.innertube.MixPlaylist
import app.tunes.innertube.fetchHistory
import app.tunes.innertube.fetchMixPlaylists
import app.tunes.innertube.fetchMixSongs
import app.tunes.innertube.fetchLiked
import app.tunes.innertube.registerPlayback
import app.tunes.innertube.setLike
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Menyambungkan penyimpanan lokal (Room) dengan akun YouTube Music. Tanpa login semua fungsi di sini
 * tidak melakukan apa-apa, jadi aplikasi tetap jalan seperti biasa (hanya tersimpan di perangkat ini).
 */
class YtSync(
    private val innerTube: InnerTube,
    private val account: AccountStore,
    private val repo: SongRepository,
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
    suspend fun fetchMixPlaylists(): List<MixPlaylist>? {
        val cookie = account.cookie() ?: return null
        return innerTube.fetchMixPlaylists(cookie)
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
