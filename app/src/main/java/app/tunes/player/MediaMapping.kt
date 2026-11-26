package app.tunes.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.tunes.innertube.SongItem
import app.tunes.innertube.isLocal

/**
 * MediaItem memakai URI palsu "tunes://<videoId>". URL asli di-resolve lazy saat dibuka
 * oleh [ChunkedYoutubeDataSource], jadi antrean panjang tidak memicu ratusan request sekaligus.
 */
fun SongItem.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(videoId)
        // Lagu perangkat: URI MediaStore asli, diputar langsung (tanpa cache / YouTube).
        .setUri(if (isLocal) "content://media/external/audio/media/${videoId.substringAfter(':')}" else "tunes://$videoId")
        .apply { if (!isLocal) setCustomCacheKey(videoId) }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(thumbnail?.let { Uri.parse(it) })
                .setDurationMs(if (durationSec > 0) durationSec * 1000L else null)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

fun MediaItem.toSongItem(): SongItem =
    SongItem(
        videoId = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        durationSec = ((mediaMetadata.durationMs ?: 0L) / 1000L).toInt(),
        thumbnail = mediaMetadata.artworkUri?.toString(),
    )
