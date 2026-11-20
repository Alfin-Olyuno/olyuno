package app.tunes.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.tunes.innertube.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.tunes.i18n.t

data class PlayerState(
    val current: SongItem? = null,
    /** true saat "ingin memutar" (termasuk sedang buffering). Dipakai untuk ikon play/pause. */
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<SongItem> = emptyList(),
    val currentIndex: Int = -1,
    val error: String? = null,
)

/** Jembatan UI <-> [PlaybackService] lewat MediaController. */
class PlayerConnection(
    private val context: Context,
    scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                val c = runCatching { future.get() }.getOrNull()
                if (c != null) {
                    controller = c
                    c.addListener(listener)
                    sync()
                    while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        // Posisi tidak punya event sendiri; poll ringan saat sedang bermain.
        scope.launch {
            while (isActive) {
                delay(500)
                val c = controller
                if (c != null && c.isPlaying) {
                    _state.update { it.copy(positionMs = c.currentPosition) }
                }
            }
        }
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private fun sync() {
        val c = controller ?: return
        val queue = List(c.mediaItemCount) { c.getMediaItemAt(it).toSongItem() }
        val index = c.currentMediaItemIndex
        val current = if (c.currentMediaItem != null) queue.getOrNull(index) else null
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: ((current?.durationSec ?: 0) * 1000L)
        val state = c.playbackState
        _state.value = PlayerState(
            current = current,
            isPlaying = c.playWhenReady && state != Player.STATE_IDLE && state != Player.STATE_ENDED,
            isBuffering = c.playWhenReady && state == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            currentIndex = index,
            error = c.playerError?.let { describeError(it) },
        )
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending.addLast(block)
    }

    // ------------------------------------------------------------ perintah

    fun play(songs: List<SongItem>, index: Int) = withController { c ->
        c.setMediaItems(songs.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    fun append(songs: List<SongItem>) = withController { c ->
        c.addMediaItems(songs.map { it.toMediaItem() })
    }

    fun playNext(song: SongItem) = withController { c ->
        if (c.mediaItemCount == 0) {
            c.setMediaItem(song.toMediaItem())
            c.prepare()
            c.play()
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
        }
    }

    fun togglePlay() = withController { c ->
        val active = c.playWhenReady &&
            c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED
        if (active) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }

    /** Langsung ke lagu sebelumnya di antrean (tidak mengulang lagu ini dari awal seperti [previous]). */
    fun previousItem() = withController { it.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index) }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Posisi terkini (diekstrapolasi MediaController), dibaca di main thread. Untuk sinkronisasi lirik. */
    fun positionNow(): Long = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun currentMediaId(): String? = controller?.currentMediaItem?.mediaId
    fun queueSize(): Int = controller?.mediaItemCount ?: 0
}


/** Tambahkan kode HTTP / pesan penyebab supaya tahu kenapa gagal (mis. 403 vs 416). */
private fun describeError(e: androidx.media3.common.PlaybackException): String {
    var c: Throwable? = e.cause
    var detail: String? = null
    while (c != null && detail == null) {
        detail = when (c) {
            is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException ->
                "HTTP ${c.responseCode}"
            is app.tunes.innertube.InnerTubeException -> c.message
            else -> "${c.javaClass.simpleName} ${c.message.orEmpty().take(60)}".trim()
        }
        c = c.cause
    }
    return t("Gagal memutar [b5] (%s)", "${e.errorCodeName}${detail?.let { ": $it" }.orEmpty()}")
}
