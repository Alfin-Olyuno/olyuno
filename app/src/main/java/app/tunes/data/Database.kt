package app.tunes.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.SongItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val thumbnail: String?,
    val liked: Boolean = false,
    val likedAt: Long = 0,
    val lastPlayedAt: Long = 0,
    val playCount: Int = 0,
)

/**
 * Satu kali putar (log untuk halaman Statistik). Kolom ctx* terisi bila lagu diputar dari halaman album / playlist
 * (lihat [PlayContext]); kosong untuk pemutaran dari tempat lain.
 */
@Entity(tableName = "plays", indices = [Index("playedAt")])
data class PlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val playedAt: Long,
    val ctxKind: String? = null,
    val ctxTitle: String? = null,
    val ctxBrowseId: String? = null,
    val ctxParams: String? = null,
    val ctxThumb: String? = null,
)

/** Satu putaran digabung dengan info lagunya (hasil query untuk statistik). */
data class PlayRow(
    val videoId: String,
    val playedAt: Long,
    val ctxKind: String?,
    val ctxTitle: String?,
    val ctxBrowseId: String?,
    val ctxParams: String?,
    val ctxThumb: String?,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val thumbnail: String?,
)

/**
 * Koleksi (album / playlist) tempat antrean yang sedang berjalan dimulai. Diisi saat pengguna memutar dari
 * halaman koleksi, dikosongkan saat memutar dari tempat lain; dibaca layanan pemutar saat mencatat putaran.
 */
object PlayContext {
    @Volatile var current: CollectionRef? = null
}

/** Versi 1 -> 2: tabel log putaran. Riwayat lama dibawa sebagai satu putaran per lagu (waktu terakhir diputar). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `plays` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`videoId` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, `ctxKind` TEXT, `ctxTitle` TEXT, " +
                "`ctxBrowseId` TEXT, `ctxParams` TEXT, `ctxThumb` TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plays_playedAt` ON `plays` (`playedAt`)")
        db.execSQL("INSERT INTO plays (videoId, playedAt) SELECT videoId, lastPlayedAt FROM songs WHERE lastPlayedAt > 0")
    }
}

/** Playlist buatan pengguna (lokal, tidak terhubung ke akun YouTube Music). */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/**
 * Isi playlist: satu baris per lagu, urut menurut [position]. Info lagu disimpan di sini (bukan di tabel songs)
 * supaya playlist tetap tampil lengkap walau offline dan tidak ikut mencemari riwayat.
 */
@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "videoId"], indices = [Index("playlistId")])
data class PlaylistSongEntity(
    val playlistId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val thumbnail: String?,
    val position: Int,
    val addedAt: Long,
)

/** Versi 2 -> 3: tabel playlist lokal. Data yang ada tidak disentuh. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlist_songs` (`playlistId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSec` INTEGER NOT NULL, `thumbnail` TEXT, " +
                "`position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `videoId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_songs_playlistId` ON `playlist_songs` (`playlistId`)")
    }
}

/** Lagu baru masuk "Paling sering diputar" setelah diputar sebanyak ini. */
const val MOST_PLAYED_MIN_COUNT = 3

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE liked = 1 ORDER BY likedAt DESC")
    fun liked(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 200")
    fun history(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE playCount >= $MOST_PLAYED_MIN_COUNT ORDER BY playCount DESC, lastPlayedAt DESC LIMIT 60")
    fun mostPlayed(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun all(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE liked = 1")
    suspend fun likedNow(): List<SongEntity>

    @Query("SELECT liked FROM songs WHERE videoId = :id")
    fun isLiked(id: String): Flow<Boolean?>

    @Query("SELECT * FROM songs WHERE videoId = :id")
    suspend fun get(id: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(song: SongEntity)

    @Query("UPDATE songs SET lastPlayedAt = 0, playCount = 0 WHERE lastPlayedAt > 0")
    suspend fun clearHistory()

    @Query("UPDATE songs SET lastPlayedAt = 0, playCount = 0 WHERE videoId = :id")
    suspend fun removeFromHistory(id: String)

    @Query("UPDATE songs SET liked = :liked, likedAt = :at WHERE videoId = :id")
    suspend fun setLiked(id: String, liked: Boolean, at: Long)

    @Insert
    suspend fun insertPlay(play: PlayEntity)

    @Query(
        "SELECT p.videoId AS videoId, p.playedAt AS playedAt, p.ctxKind AS ctxKind, p.ctxTitle AS ctxTitle, " +
            "p.ctxBrowseId AS ctxBrowseId, p.ctxParams AS ctxParams, p.ctxThumb AS ctxThumb, " +
            "s.title AS title, s.artist AS artist, s.durationSec AS durationSec, s.thumbnail AS thumbnail " +
            "FROM plays p INNER JOIN songs s ON s.videoId = p.videoId " +
            "WHERE p.playedAt >= :since ORDER BY p.playedAt DESC",
    )
    fun plays(since: Long): Flow<List<PlayRow>>

    @Query("SELECT * FROM plays")
    suspend fun allPlays(): List<PlayEntity>

    @Query("SELECT COUNT(*) FROM plays WHERE videoId = :id AND playedAt = :at")
    suspend fun countPlay(id: String, at: Long): Int

    @Query("DELETE FROM plays")
    suspend fun clearPlays()

    @Query("DELETE FROM plays WHERE videoId = :id")
    suspend fun removePlays(id: String)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC, id DESC")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId, position")
    fun entries(): Flow<List<PlaylistSongEntity>>

    @Insert
    suspend fun insertPlaylist(p: PlaylistEntity): Long

    /** Lagu yang sudah ada di playlist itu diabaikan (hasil -1). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(items: List<PlaylistSongEntity>): List<Long>

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun deleteSongs(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id AND videoId = :videoId")
    suspend fun deleteSong(id: Long, videoId: String)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :id ORDER BY position")
    suspend fun songsOf(id: Long): List<PlaylistSongEntity>

    @Query("UPDATE playlist_songs SET position = :pos WHERE playlistId = :id AND videoId = :videoId")
    suspend fun setPosition(id: Long, videoId: String, pos: Int)

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun allPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId, position")
    suspend fun allEntries(): List<PlaylistSongEntity>
}

@Database(
    entities = [SongEntity::class, PlayEntity::class, PlaylistEntity::class, PlaylistSongEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class OlyunoDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
}

fun SongEntity.toSong() = SongItem(videoId, title, artist, durationSec, thumbnail)

private fun SongItem.toEntity() = SongEntity(videoId, title, artist, durationSec, thumbnail)

/** Satu baris riwayat: lagu dan kapan terakhir diputar (ms). */
data class HistoryEntry(val song: SongItem, val playedAt: Long)

class SongRepository(private val dao: SongDao) {
    val liked: Flow<List<SongItem>> = dao.liked().map { l -> l.map { it.toSong() } }
    val history: Flow<List<SongItem>> = dao.history().map { l -> l.map { it.toSong() } }
    val historyEntries: Flow<List<HistoryEntry>> =
        dao.history().map { l -> l.map { HistoryEntry(it.toSong(), it.lastPlayedAt) } }
    val mostPlayed: Flow<List<SongItem>> = dao.mostPlayed().map { l -> l.map { it.toSong() } }

    fun isLiked(videoId: String): Flow<Boolean> = dao.isLiked(videoId).map { it == true }

    /** Log putaran untuk halaman Statistik, terbaru di depan; hanya yang sejak [from] (ms). */
    fun plays(from: Long): Flow<List<PlayRow>> = dao.plays(from)

    suspend fun markPlayed(song: SongItem) {
        if (song.videoId.isBlank()) return
        val old = dao.get(song.videoId)
        val base = old ?: song.toEntity()
        dao.upsert(
            base.copy(
                title = song.title.ifBlank { base.title },
                artist = song.artist.ifBlank { base.artist },
                durationSec = if (song.durationSec > 0) song.durationSec else base.durationSec,
                thumbnail = song.thumbnail ?: base.thumbnail,
                lastPlayedAt = System.currentTimeMillis(),
                playCount = (old?.playCount ?: 0) + 1,
            ),
        )
        val ctx = PlayContext.current
        dao.insertPlay(
            PlayEntity(
                videoId = song.videoId,
                playedAt = System.currentTimeMillis(),
                ctxKind = ctx?.kind?.name,
                ctxTitle = ctx?.title,
                ctxBrowseId = ctx?.browseId,
                ctxParams = ctx?.params,
                ctxThumb = ctx?.thumbnail,
            ),
        )
    }

    suspend fun clearHistory() {
        dao.clearHistory()
        dao.clearPlays()
    }

    suspend fun removeFromHistory(videoId: String) {
        dao.removeFromHistory(videoId)
        dao.removePlays(videoId)
    }

    suspend fun exportPlays(): List<PlayEntity> = dao.allPlays()

    /** Gabungkan log putaran dari cadangan; putaran yang sama persis (lagu + waktu) tidak ditambah dua kali. */
    suspend fun mergePlays(items: List<PlayEntity>) {
        for (p in items) {
            if (dao.countPlay(p.videoId, p.playedAt) == 0) dao.insertPlay(p.copy(id = 0))
        }
    }

    /** Balik status suka; mengembalikan status barunya. */
    suspend fun toggleLike(song: SongItem): Boolean {
        val now = System.currentTimeMillis()
        val old = dao.get(song.videoId)
        return if (old == null) {
            dao.upsert(song.toEntity().copy(liked = true, likedAt = now))
            true
        } else {
            val newLiked = !old.liked
            dao.setLiked(song.videoId, newLiked, if (newLiked) now else 0L)
            newLiked
        }
    }

    suspend fun exportAll(): List<SongEntity> = dao.all()

    /**
     * Gabungkan lagu dari file cadangan. Tidak ada yang ditimpa begitu saja: suka kalau salah satunya suka,
     * waktu dan hitungan putar diambil yang terbesar, info lagu yang sudah ada dipertahankan.
     */
    suspend fun mergeImported(items: List<SongEntity>) {
        for (n in items) {
            val old = dao.get(n.videoId)
            if (old == null) {
                dao.upsert(n)
            } else {
                dao.upsert(
                    old.copy(
                        title = old.title.ifBlank { n.title },
                        artist = old.artist.ifBlank { n.artist },
                        durationSec = if (old.durationSec > 0) old.durationSec else n.durationSec,
                        thumbnail = old.thumbnail ?: n.thumbnail,
                        liked = old.liked || n.liked,
                        likedAt = maxOf(old.likedAt, n.likedAt),
                        lastPlayedAt = maxOf(old.lastPlayedAt, n.lastPlayedAt),
                        playCount = maxOf(old.playCount, n.playCount),
                    ),
                )
            }
        }
    }

    suspend fun likedSnapshot(): List<SongItem> = dao.likedNow().map { it.toSong() }

    /**
     * Terapkan daftar "Disukai" dari akun YouTube Music. [remote] terbaru di depan, jadi urutan di sini
     * mengikuti akun. [unlikeIds] = lagu yang hanya disukai di sini dan sudah dicabut di akun.
     */
    suspend fun applyRemoteLikes(remote: List<SongItem>, unlikeIds: Collection<String>) {
        val base = System.currentTimeMillis()
        remote.forEachIndexed { i, song ->
            val at = base - i * 1000L
            if (dao.get(song.videoId) == null) {
                dao.upsert(song.toEntity().copy(liked = true, likedAt = at))
            } else {
                dao.setLiked(song.videoId, true, at)
            }
        }
        unlikeIds.forEach { dao.setLiked(it, false, 0L) }
    }
}
