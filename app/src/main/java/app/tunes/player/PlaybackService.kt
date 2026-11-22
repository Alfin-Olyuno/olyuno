package app.tunes.player

import app.tunes.i18n.t
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.tunes.MainActivity
import app.tunes.OlyunoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var island: IslandOverlay? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Timer tidur: dijalankan di thread utama karena memegang pemutar.
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // true setelah timer tidur "Akhir lagu" menjeda pemutar, supaya autoplay tidak melanjutkan lagi.
    private var sleepStopped = false

    // Retry otomatis kalau URL ternyata kedaluwarsa / ditolak (mis. HTTP 403).
    private var retryId: String? = null
    private var retryCount = 0

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var stallCheck: Runnable
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // Sisa antrean streaming yang terputus karena offline; dilanjutkan setelah online lagi.
    private var pendingResume: List<androidx.media3.common.MediaItem> = emptyList()

    // Lagu unduhan pengganti yang ditambahkan otomatis (dibuang lagi kalau antrean asli dilanjutkan).
    private var fallbackIds: Set<String> = emptySet()

    // true sebentar saat antrean terakhir dipulihkan: bukan pemutaran baru, jadi jangan dicatat ke riwayat.
    private var restoring = false
    private lateinit var resumeTick: Runnable

    // true selama service sendiri mengubah antrean (supaya tidak dikira pengguna memulai antrean baru).
    private var selfEdit = false

    override fun onCreate() {
        super.onCreate()
        val container = (application as OlyunoApp).container

        // Cache dipakai bersama pengunduh (lihat MediaCaches): lagu yang sudah diunduh diputar dari sana.
        val caches = container.caches
        val cached = caches.playbackFactory()

        // Buffer besar (hingga 3 menit): gangguan jaringan beberapa detik tidak terasa, dan lagu
        // berikutnya di antrean ikut terisi lebih dulu sehingga skip nyaris instan.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 60_000,
                /* maxBufferMs = */ 180_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Jalur audio dipasangi pengukur level asli (AudioLevels) untuk gelombang di Dynamic Island.
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(
                        arrayOf<androidx.media3.common.audio.AudioProcessor>(
                            androidx.media3.exoplayer.audio.TeeAudioProcessor(container.audioLevels),
                        ),
                    )
                    .build()
        }

        val player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, cached)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Cadangan stream progresif (itag 18) berisi video; kita hanya butuh audionya.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        // Normalisasi volume: turunkan volume pemutar untuk lagu yang lebih keras dari patokan YouTube.
        // Hanya menurunkan (seperti YouTube), tidak pernah menaikkan, jadi tidak ada risiko distorsi.
        val applyNormalization = {
            val id = player.currentMediaItem?.mediaId
            val db = if (id != null && container.playbackPrefs.normalize.value) container.playbackPrefs.loudness(id) else null
            player.volume = if (db != null && db > 0f) Math.pow(10.0, -db / 20.0).toFloat().coerceIn(0.25f, 1f) else 1f
        }

        // Ekualiser dipasang ke audio session pemutar ini.
        container.equalizer.attach(player.audioSessionId)

        stallCheck = object : Runnable {
            override fun run() {
                if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return
                val id = player.currentMediaItem?.mediaId
                if (id != null && isOffline() && !isPlayableOffline(id, downloadedIds(container.downloads))) {
                    // Lagu streaming sudah berhenti sendiri karena offline: baru sekarang pindah ke unduhan.
                    switchToDownloadedAfterCurrent(player, container.downloads, includeCurrent = true)
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                } else {
                    // Masih macet tapi jaringan ada / lagunya unduhan: cek lagi nanti, mungkin jaringan putus belakangan.
                    mainHandler.postDelayed(this, STALL_RECHECK_MS)
                }
            }
        }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Streak: waktu dengar lagu dihitung dari nol untuk lagu baru / putar ulang.
                container.streak.onSongChanged()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && !selfEdit) {
                    // Pengguna memulai antrean lain: sisa antrean lama tidak perlu dilanjutkan.
                    pendingResume = emptyList()
                    fallbackIds = emptySet()
                }
                applyNormalization()
                if (mediaItem == null || restoring) return
                val song = mediaItem.toSongItem()
                scope.launch { container.songs.markPlayed(song) }
                if (!song.videoId.startsWith(app.tunes.innertube.LOCAL_ID_PREFIX)) {
                    scope.launch { container.sync.registerPlay(song.videoId) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Lagu sudah berjalan normal: jatah retry diisi ulang untuk gangguan berikutnya.
                if (playbackState == Player.STATE_READY) {
                    retryCount = 0
                    // Kekerasan lagu baru diketahui setelah stream-nya diambil; sekarang sudah pasti ada.
                    applyNormalization()
                }
                // Lagu streaming macet (buffer habis): beri jeda dulu, jangan langsung ganti ke unduhan.
                mainHandler.removeCallbacks(stallCheck)
                if (playbackState == Player.STATE_BUFFERING) mainHandler.postDelayed(stallCheck, STALL_GRACE_MS)
                if (playbackState == Player.STATE_ENDED && !sleepStopped) {
                    if (!loopDownloadedQueueIfOffline(player, container.downloads)) {
                        // Offline: ke lagu unduhan kalau ada. Online: autoplay (radio).
                        if (isOffline()) switchToDownloadedAfterCurrent(player, container.downloads)
                        else autoplayRadio(player, container)
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    sleepStopped = false
                } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                    container.sleepTimer.state.value.untilTrackEnd
                ) {
                    // Timer tidur "Akhir lagu": lagu selesai dan pemutar sudah dijeda; timer selesai.
                    sleepStopped = true
                    container.sleepTimer.cancel()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val id = player.currentMediaItem?.mediaId ?: return
                if (isOffline() && !isPlayableOffline(id, downloadedIds(container.downloads))) {
                    // Lagu streaming terputus karena offline: percuma di-retry, pindah ke lagu unduhan.
                    switchToDownloadedAfterCurrent(player, container.downloads, includeCurrent = true)
                    if (player.hasNextMediaItem()) {
                        retryCount = 0
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                    return
                }
                if (id != retryId) {
                    retryId = id
                    retryCount = 0
                }
                if (retryCount++ < 3) {
                    // Retry yang sebenarnya sudah dilakukan di ChunkedYoutubeDataSource; ini jaring kedua.
                    container.streamResolver.invalidate(id)
                    runCatching { caches.playbackCache.removeResource("tunes://$id") }
                    player.prepare()
                } else if (player.hasNextMediaItem()) {
                    // Lagu ini benar-benar tidak bisa diputar: lanjut ke berikutnya, jangan berhenti.
                    retryCount = 0
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // Ketuk notifikasi -> langsung buka layar "Sedang diputar".
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
        registerOnlineWatcher(player)
        setupResumeLast(player, container)

        // Dynamic Island: kartu lagu melayang; nyala/mati mengikuti pengaturan, muncul hanya saat Olyuno tidak dibuka.
        island = IslandOverlay(
            this, player, mainScope, container.islandPrefs.enabled, (application as OlyunoApp).appVisible,
            container.islandPrefs.style,
            container.islandPrefs.color,
            container.islandPrefs.custom,
            container.islandPrefs.corner,
            container.islandPrefs.opacity,
            container.islandPrefs.shape,
            container.islandPrefs.cardWidth,
            container.islandPrefs.textScale,
            container.islandPrefs.buttonStyle,
            container.islandPrefs.showButtons,
            container.islandPrefs.showArtist,
            container.islandPrefs.showTitle,
            container.islandPrefs.onlyWhenPlaying,
            container.islandPrefs.collapseSec,
            container.islandPrefs.pauseHideSec,
            audioLevels = container.audioLevels,
            isDark = {
                val sysDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                when (container.appearance.mode.value) {
                    app.tunes.data.ColorMode.System -> sysDark
                    app.tunes.data.ColorMode.Light -> false
                    app.tunes.data.ColorMode.Dark -> true
                    app.tunes.data.ColorMode.Custom ->
                        androidx.core.graphics.ColorUtils.calculateLuminance(container.appearance.custom.value) < 0.5
                }
            },
        )
            .also { it.start() }

        // Kualitas audio: stream di cache dibuang (kecuali lagu yang sedang diputar) supaya lagu berikutnya
        // memakai kualitas baru; yang sedang diputar tidak berganti format di tengah lagu.
        mainScope.launch {
            container.playbackPrefs.quality.drop(1).collect {
                container.streamResolver.dropExcept(player.currentMediaItem?.mediaId)
            }
        }

        // Hemat data dinyalakan/dimatikan: kualitas efektif berubah, stream di cache dibuang seperti di atas.
        mainScope.launch {
            container.playbackPrefs.dataSaver.drop(1).collect {
                container.streamResolver.dropExcept(player.currentMediaItem?.mediaId)
            }
        }

        // Lewati bagian hening & Normalisasi volume mengikuti pengaturan langsung.
        mainScope.launch {
            container.playbackPrefs.skipSilence.collect { player.skipSilenceEnabled = it }
        }
        mainScope.launch {
            container.playbackPrefs.normalize.collect { applyNormalization() }
        }

        // Kecepatan pemutaran + Jaga nada: berlaku langsung dan tetap untuk lagu-lagu berikutnya.
        mainScope.launch {
            kotlinx.coroutines.flow.combine(
                container.playbackPrefs.speed,
                container.playbackPrefs.keepPitch,
            ) { speed, keep -> speed to keep }.collect { (speed, keep) ->
                player.playbackParameters = androidx.media3.common.PlaybackParameters(speed, if (keep) 1f else speed)
            }
        }

        // Timer tidur: "Akhir lagu" = jeda otomatis di akhir lagu; menit = jeda saat waktunya habis.
        mainScope.launch {
            container.sleepTimer.state.collectLatest { st ->
                player.setPauseAtEndOfMediaItems(st.untilTrackEnd)
                if (st.endAtMs > 0L) {
                    val wait = st.endAtMs - android.os.SystemClock.elapsedRealtime()
                    if (wait > 0L) {
                        // Pudarkan volume ~8 detik terakhir (kalau diaktifkan dan sedang memutar), lalu jeda.
                        val fadeMs = if (container.playbackPrefs.sleepFade.value) minOf(8_000L, wait) else 0L
                        delay(wait - fadeMs)
                        if (fadeMs > 0L && player.isPlaying) {
                            val base = player.volume
                            try {
                                val steps = 20
                                for (i in 1..steps) {
                                    player.volume = base * (1f - i / steps.toFloat())
                                    delay(fadeMs / steps)
                                }
                                player.pause()
                            } finally {
                                player.volume = base
                            }
                        } else {
                            delay(fadeMs)
                            player.pause()
                        }
                    } else {
                        player.pause()
                    }
                    container.sleepTimer.cancel()
                }
            }
        }
    }

    // ------------------------------------------------ lanjutkan terakhir diputar

    /** Simpan antrean + posisi supaya bisa dipulihkan saat aplikasi dibuka lagi. Dipanggil di thread utama. */
    private fun saveResume(player: Player, container: app.tunes.AppContainer, withQueue: Boolean) {
        if (restoring || !container.playbackPrefs.resumeLast.value) return
        val n = player.mediaItemCount
        if (n == 0) return
        val index = player.currentMediaItemIndex
        val pos = player.currentPosition.coerceAtLeast(0L)
        if (withQueue) {
            val queue = List(n) { player.getMediaItemAt(it).toSongItem() }
            container.playbackPrefs.saveResumeQueue(queue, index, pos, player.shuffleModeEnabled, player.repeatMode)
        } else {
            container.playbackPrefs.saveResumePosition(index, pos, player.shuffleModeEnabled, player.repeatMode)
        }
    }

    private fun setupResumeLast(player: Player, container: app.tunes.AppContainer) {
        val prefs = container.playbackPrefs

        // Pulihkan antrean terakhir dalam keadaan jeda (tidak langsung bunyi, tidak mengambil stream dulu).
        if (prefs.resumeLast.value && player.mediaItemCount == 0) {
            prefs.loadResume()?.let { st ->
                restoring = true
                selfEdit = true
                try {
                    var pos = st.positionMs
                    val durSec = st.queue[st.index].durationSec
                    // Posisi sudah di ujung lagu: mulai dari awal saja.
                    if (durSec > 0 && pos >= durSec * 1000L - 1_000L) pos = 0L
                    player.setMediaItems(st.queue.map { it.toMediaItem() }, st.index, pos)
                    player.shuffleModeEnabled = st.shuffle
                    player.repeatMode = st.repeatMode
                    player.playWhenReady = false
                } finally {
                    selfEdit = false
                }
                mainHandler.postDelayed({ restoring = false }, 1_000L)
            }
        }

        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveResume(player, container, true)
            }

            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) {
                    // Antrean sangat panjang hanya disimpan sebagian (sekitar lagu aktif): perbarui jendelanya.
                    saveResume(p, container, withQueue = p.mediaItemCount > 300 && events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION))
                }
            }
        })

        // Posisi lagu disimpan berkala selama memutar.
        resumeTick = object : Runnable {
            override fun run() {
                if (player.isPlaying) saveResume(player, container, false)
                mainHandler.postDelayed(this, RESUME_SAVE_MS)
            }
        }
        mainHandler.postDelayed(resumeTick, RESUME_SAVE_MS)

        // Streak: tiap detik catat waktu putar nyata; toast muncul sekali sehari saat hari ini terhitung.
        val streakTick = object : Runnable {
            override fun run() {
                val dur = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 } ?: 0L
                val event = runCatching { container.streak.onTick(player.isPlaying, dur) }.getOrNull()
                if (event != null) showStreakToast(event)
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        mainHandler.postDelayed(streakTick, 1_000L)

        // Lanjut saat headset tersambung: hanya kalau musik terhenti otomatis karena headset terputus (bukan dijeda
        // pengguna) dan belum lewat 10 menit. Ditandai dari alasan jeda "audio becoming noisy".
        var autoPausedAt = 0L
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                autoPausedAt = if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                    android.os.SystemClock.elapsedRealtime()
                } else {
                    0L
                }
            }
        })
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
                // Dipanggil juga sekali saat didaftarkan (untuk perangkat yang sudah ada): autoPausedAt masih 0, jadi diabaikan.
                if (autoPausedAt == 0L || !prefs.resumeOnHeadset.value) return
                if (addedDevices.none { it.isSink && isHeadsetType(it.type) }) return
                val fresh = android.os.SystemClock.elapsedRealtime() - autoPausedAt <= HEADSET_RESUME_WINDOW_MS
                autoPausedAt = 0L
                if (!fresh || player.playWhenReady || player.mediaItemCount == 0) return
                // Beri jeda singkat supaya jalur audio ke headset siap sebelum mulai bunyi.
                mainHandler.postDelayed({
                    if (!player.playWhenReady && player.mediaItemCount > 0) player.play()
                }, 800L)
            }
        }
        audioManager.registerAudioDeviceCallback(callback, mainHandler)
        deviceCallback = callback

        // Dimatikan: hapus simpanan. Dinyalakan lagi: simpan antrean yang sedang ada.
        mainScope.launch {
            prefs.resumeLast.drop(1).collect { on -> if (on) saveResume(player, container, true) }
        }
    }

    /**
     * Antrean selesai dan perangkat offline: lagu di antrean yang sudah diunduh (playlist/album unduhan,
     * atau daftar lagu unduhan) diputar ulang dari titik acak (urutan tetap melingkar, jadi semua lagu
     * terputar satu putaran penuh) alih-alih berhenti. Mengembalikan true kalau berhasil memutar ulang.
     * Kalau online, perilaku lama dipertahankan (berhenti di akhir antrean).
     */
    private fun loopDownloadedQueueIfOffline(player: Player, downloads: SongDownloads): Boolean {
        val n = player.mediaItemCount
        if (n == 0 || !isOffline()) return false
        val done = downloadedIds(downloads)
        // Lagu streaming yang tersisa di antrean tidak bisa diputar offline: hanya yang sudah diunduh diulang.
        val items = List(n) { player.getMediaItemAt(it) }.filter { isPlayableOffline(it.mediaId, done) }
        if (items.isEmpty()) return false
        val lastId = player.currentMediaItem?.mediaId
        val m = items.size
        // Jangan mulai dari lagu yang barusan selesai (kecuali cuma ada satu lagu).
        val start = if (m > 1) (0 until m).filter { items[it].mediaId != lastId }.ifEmpty { listOf(0) }.random() else 0
        val rotated = items.drop(start) + items.take(start)
        selfEdit = true
        try {
            player.setMediaItems(rotated, 0, 0L)
        } finally {
            selfEdit = false
        }
        player.prepare()
        player.play()
        return true
    }

    /**
     * Putar otomatis: antrean streaming habis dan online -> ambil radio dari lagu terakhir lalu lanjut diputar
     * (seperti Autoplay Spotify). Tidak berlaku untuk antrean yang semuanya sudah diunduh, dan bisa dimatikan
     * di Pengaturan. Kalau gagal / tidak ada hasil, berhenti seperti biasa.
     */
    private fun autoplayRadio(player: Player, container: app.tunes.AppContainer) {
        // Hemat data: tidak mengambil lagu serupa (radio) saat antrean habis.
        if (!container.playbackPrefs.autoplay.value || container.playbackPrefs.dataSaver.value || isOffline()) return
        val n = player.mediaItemCount
        if (n == 0) return
        val items = List(n) { player.getMediaItemAt(it) }
        val lastId = items.last().mediaId
        if (lastId.startsWith(app.tunes.innertube.LOCAL_ID_PREFIX)) return
        val done = container.downloads.entries.value.filter { it.done }.map { it.song.videoId }.toSet()
        if (items.all { it.mediaId.startsWith(app.tunes.innertube.LOCAL_ID_PREFIX) || it.mediaId in done }) return
        val inQueue = items.map { it.mediaId }.toSet()
        scope.launch {
            val radio = try {
                container.innerTube.radio(lastId).filter { it.videoId !in inQueue }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (radio.isEmpty()) return@launch
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                // Pengguna belum mengubah antrean / memulai lagu lain selama radio dimuat.
                if (player.playbackState == Player.STATE_ENDED && player.mediaItemCount == n) {
                    player.addMediaItems(radio.map { it.toMediaItem() })
                    player.seekTo(n, 0L)
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    private companion object {
        /** Lama menunggu lagu streaming yang macet sebelum dianggap berhenti dan diganti ke unduhan. */
        const val STALL_GRACE_MS = 10_000L
        const val STALL_RECHECK_MS = 5_000L
        const val RESUME_SAVE_MS = 5_000L

        /** Setelah headset terputus, musik hanya lanjut otomatis kalau headset tersambung lagi dalam waktu ini. */
        const val HEADSET_RESUME_WINDOW_MS = 10 * 60_000L
    }

    private fun downloadedIds(downloads: SongDownloads): Set<String> =
        downloads.entries.value.filter { it.done }.map { it.song.videoId }.toSet()

    private fun isPlayableOffline(id: String, done: Set<String>): Boolean =
        id.startsWith(app.tunes.innertube.LOCAL_ID_PREFIX) || id in done

    /**
     * Dipanggil hanya setelah lagu streaming benar-benar berhenti sendiri karena offline (macet melewati
     * jeda, error, atau antrean habis), bukan saat jaringan baru putus. Lagu streaming berikutnya di antrean
     * dibuang, lalu lanjut ke lagu yang sudah diunduh (urutan acak). Belum ada unduhan: tidak mengubah apa pun.
     *
     * Bagian yang dibuang (dan lagu yang terputus kalau [includeCurrent]) diingat di [pendingResume] supaya
     * bisa dilanjutkan lagi oleh [resumeOriginalQueue] saat online kembali.
     */
    private fun switchToDownloadedAfterCurrent(player: Player, downloads: SongDownloads, includeCurrent: Boolean = false) {
        val n = player.mediaItemCount
        if (n == 0 || !isOffline()) return
        val entries = downloads.entries.value.filter { it.done }
        if (entries.isEmpty()) return
        val done = entries.map { it.song.videoId }.toSet()
        val cur = player.currentMediaItemIndex
        val curItem = player.currentMediaItem
        val curId = curItem?.mediaId
        val wasEnded = player.playbackState == Player.STATE_ENDED
        selfEdit = true
        try {
            val removed = ArrayDeque<androidx.media3.common.MediaItem>()
            for (i in n - 1 downTo cur + 1) {
                val item = player.getMediaItemAt(i)
                if (!isPlayableOffline(item.mediaId, done)) {
                    removed.addFirst(item)
                    player.removeMediaItem(i)
                }
            }
            val lost = buildList {
                if (includeCurrent && curItem != null && !isPlayableOffline(curItem.mediaId, done)) add(curItem)
                addAll(removed)
            }
            val known = pendingResume.map { it.mediaId }.toSet()
            pendingResume = pendingResume + lost.filter { it.mediaId !in known }

            // Masih ada lagu unduhan berikutnya di antrean: biarkan.
            if (player.mediaItemCount > cur + 1) return
            // Lagu sekarang sudah unduhan dan antrean habis: putar ulang acak ditangani saat antrean selesai.
            if (curId != null && isPlayableOffline(curId, done) && !wasEnded) return
            val inQueue = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }.toSet()
            val next = entries.map { it.song }
                .filter { it.videoId != curId && it.videoId !in inQueue }
                .distinctBy { it.videoId }
                .shuffled()
            if (next.isEmpty()) return
            val first = player.mediaItemCount
            player.addMediaItems(next.map { it.toMediaItem() })
            fallbackIds = fallbackIds + next.map { it.videoId }
            if (wasEnded) {
                player.seekTo(first, 0L)
                player.prepare()
                player.play()
            }
        } finally {
            selfEdit = false
        }
    }

    /**
     * Online lagi: lagu unduhan yang sedang diputar dibiarkan selesai, lalu lanjut ke bagian antrean streaming
     * yang tadi terputus (lagu yang berhenti, lalu sisa playlist / album / antrean). Lagu unduhan pengganti
     * yang belum diputar dibuang supaya tidak menyela.
     */
    private fun resumeOriginalQueue(player: Player) {
        if (pendingResume.isEmpty() || isOffline()) return
        val n = player.mediaItemCount
        if (n == 0) {
            pendingResume = emptyList()
            fallbackIds = emptySet()
            return
        }
        selfEdit = true
        try {
            val cur = player.currentMediaItemIndex
            val ended = player.playbackState == Player.STATE_ENDED
            for (i in n - 1 downTo cur + 1) {
                if (player.getMediaItemAt(i).mediaId in fallbackIds) player.removeMediaItem(i)
            }
            val insertAt = cur + 1
            player.addMediaItems(insertAt, pendingResume)
            if (ended) {
                player.seekTo(insertAt, 0L)
                player.prepare()
                player.play()
            }
        } finally {
            selfEdit = false
        }
        pendingResume = emptyList()
        fallbackIds = emptySet()
    }

    private fun registerOnlineWatcher(player: Player) {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Jeda singkat supaya koneksi sempat stabil sebelum antrean lama disambung.
                mainHandler.postDelayed({ resumeOriginalQueue(player) }, 1_500)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb, mainHandler)
            netCallback = cb
        } catch (e: Exception) {
            // tanpa pemantau: antrean lama hanya dilanjutkan lewat pemutaran baru
        }
    }

    private fun isOffline(): Boolean = try {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        island?.onConfigurationChanged()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Aplikasi digeser dari recent apps: hentikan musik dan hilangkan notifikasinya. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Simpan dulu sebelum antrean dikosongkan, supaya bisa dilanjutkan saat aplikasi dibuka lagi.
        session?.player?.let { saveResume(it, (application as OlyunoApp).container, true) }
        session?.player?.run {
            stop()
            clearMediaItems()
        }
        stopSelf()
    }

    private var deviceCallback: android.media.AudioDeviceCallback? = null

    private fun isHeadsetType(type: Int): Boolean = when (type) {
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> android.os.Build.VERSION.SDK_INT >= 31 &&
            (type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET || type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

    /** Toast streak, gaya sama dengan toast \"Kamu kembali online.\" (toast sistem, singkat, satu kalimat). */
    private fun showStreakToast(event: app.tunes.data.StreakEvent) {
        val text = when (event) {
            is app.tunes.data.StreakEvent.Started -> t("Streak dimulai.")
            is app.tunes.data.StreakEvent.Up -> t("Streak %d hari.", event.days)
            is app.tunes.data.StreakEvent.FreezeUsed -> t("Freeze terpakai, streak kamu aman.")
            is app.tunes.data.StreakEvent.Record -> t("Rekor streak baru: %d hari.", event.days)
        }
        android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        deviceCallback?.let {
            runCatching {
                (getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
                    .unregisterAudioDeviceCallback(it)
            }
        }
        deviceCallback = null
        netCallback?.let {
            runCatching {
                (getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        netCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        island?.release()
        island = null
        session?.player?.let { saveResume(it, (application as OlyunoApp).container, true) }
        runCatching { (application as OlyunoApp).container.streak.flush() }
        mainScope.cancel()
        (application as OlyunoApp).container.sleepTimer.cancel()
        (application as OlyunoApp).container.equalizer.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
