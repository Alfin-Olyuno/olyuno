package app.tunes.data

import androidx.room.withTransaction
import app.tunes.innertube.SongItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Playlist buatan pengguna beserta lagu-lagunya (urut). */
data class LocalPlaylist(val id: Long, val name: String, val createdAt: Long, val songs: List<SongItem>)

/** Satu playlist di file cadangan. */
data class BackupPlaylist(val name: String, val createdAt: Long, val songs: List<SongItem>)

/**
 * Playlist lokal di database Room (tabel playlists + playlist_songs). Semua perubahan lebih dari satu langkah
 * berjalan dalam satu transaksi supaya tidak setengah jadi.
 */
class PlaylistRepository(private val db: OlyunoDatabase) {
    private val dao = db.playlistDao()

    /** Semua playlist (terbaru dulu), tiap playlist dengan lagunya urut menurut posisi. */
    val playlists: Flow<List<LocalPlaylist>> = combine(dao.playlists(), dao.entries()) { ps, es ->
        val byPlaylist = es.groupBy { it.playlistId }
        ps.map { p ->
            LocalPlaylist(
                id = p.id,
                name = p.name,
                createdAt = p.createdAt,
                songs = byPlaylist[p.id].orEmpty().map { SongItem(it.videoId, it.title, it.artist, it.durationSec, it.thumbnail) },
            )
        }
    }

    /** Buat playlist baru (opsional langsung berisi [songs]); mengembalikan id-nya. */
    suspend fun create(name: String, songs: List<SongItem> = emptyList(), createdAt: Long = System.currentTimeMillis()): Long =
        db.withTransaction {
            val id = dao.insertPlaylist(PlaylistEntity(name = name.trim(), createdAt = createdAt))
            append(id, songs)
            id
        }

    /** Tambah lagu ke akhir playlist; yang sudah ada dilewati. Mengembalikan jumlah lagu yang benar-benar masuk. */
    suspend fun addSongs(id: Long, songs: List<SongItem>): Int = db.withTransaction { append(id, songs) }

    private suspend fun append(id: Long, songs: List<SongItem>): Int {
        if (songs.isEmpty()) return 0
        var pos = dao.maxPosition(id) + 1
        val now = System.currentTimeMillis()
        val rows = songs.distinctBy { it.videoId }.map {
            PlaylistSongEntity(id, it.videoId, it.title, it.artist, it.durationSec, it.thumbnail, pos++, now)
        }
        return dao.insertSongs(rows).count { it != -1L }
    }

    suspend fun rename(id: Long, name: String) {
        val n = name.trim()
        if (n.isNotEmpty()) dao.rename(id, n)
    }

    suspend fun delete(id: Long) = db.withTransaction {
        dao.deleteSongs(id)
        dao.deletePlaylist(id)
    }

    suspend fun removeSong(id: Long, videoId: String) = dao.deleteSong(id, videoId)

    /** Geser satu lagu ke atas ([delta] = -1) atau ke bawah (+1) dengan menukar posisinya dengan tetangganya. */
    suspend fun moveSong(id: Long, videoId: String, delta: Int) = db.withTransaction {
        val list = dao.songsOf(id)
        val i = list.indexOfFirst { it.videoId == videoId }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@withTransaction
        dao.setPosition(id, list[i].videoId, list[j].position)
        dao.setPosition(id, list[j].videoId, list[i].position)
    }

    // ------------------------------------------------ cadangan

    suspend fun exportAll(): List<BackupPlaylist> {
        val byPlaylist = dao.allEntries().groupBy { it.playlistId }
        return dao.allPlaylists().map { p ->
            BackupPlaylist(
                p.name,
                p.createdAt,
                byPlaylist[p.id].orEmpty().map { SongItem(it.videoId, it.title, it.artist, it.durationSec, it.thumbnail) },
            )
        }
    }

    /** Gabungkan playlist dari cadangan: nama yang sama (tanpa peduli huruf besar) ditambah lagunya, selain itu dibuat baru. */
    suspend fun mergeImported(items: List<BackupPlaylist>) = db.withTransaction {
        for (b in items) {
            if (b.name.isBlank()) continue
            val existing = dao.allPlaylists().firstOrNull { it.name.equals(b.name.trim(), ignoreCase = true) }
            if (existing != null) append(existing.id, b.songs) else create(b.name, b.songs, b.createdAt)
        }
    }
}
