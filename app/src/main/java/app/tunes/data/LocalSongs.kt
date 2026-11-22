package app.tunes.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import app.tunes.innertube.LOCAL_ID_PREFIX
import app.tunes.innertube.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.tunes.i18n.t

/** Membaca semua lagu musik di perangkat lewat MediaStore (perlu izin akses audio). */
class LocalSongs(private val context: Context) {

    suspend fun load(): List<SongItem> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )
        val out = ArrayList<SongItem>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val artist = c.getString(artistCol)
                    ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                    ?: t("Artis tidak dikenal")
                out.add(
                    SongItem(
                        videoId = "$LOCAL_ID_PREFIX$id",
                        title = c.getString(titleCol).orEmpty().ifBlank { t("Tanpa judul") },
                        artist = artist,
                        durationSec = (c.getLong(durCol) / 1000L).toInt(),
                        thumbnail = "content://media/external/audio/media/$id/albumart",
                    ),
                )
            }
        }
        out
    }
}
