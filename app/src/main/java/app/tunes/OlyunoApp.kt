package app.tunes

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Context
import androidx.room.Room
import app.tunes.data.AccountStore
import app.tunes.data.AppearanceStore
import app.tunes.data.DownloadStore
import app.tunes.data.InfoStore
import app.tunes.data.LyricsPrefsStore
import app.tunes.data.LyricsSourceStore
import app.tunes.data.SearchHistoryStore
import app.tunes.data.SongRepository
import app.tunes.data.YtSync
import app.tunes.data.OlyunoDatabase
import app.tunes.innertube.InnerTube
import app.tunes.innertube.StreamResolver
import app.tunes.lyrics.LyricsRepository
import app.tunes.lyrics.TranslationRepository
import app.tunes.player.EqualizerController
import app.tunes.player.MediaCaches
import app.tunes.player.SongDownloads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Wiring manual (tanpa Hilt) supaya build tetap sederhana. */
class AppContainer(context: Context) {
    val innerTube = InnerTube()
    val playbackPrefs = app.tunes.data.PlaybackPrefsStore(context)
    val streamResolver = StreamResolver(innerTube, onLoudness = playbackPrefs::saveLoudness) { effectiveQuality(context.applicationContext) }

    private val db = Room.databaseBuilder(context, OlyunoDatabase::class.java, "tunes.db")
        .addMigrations(app.tunes.data.MIGRATION_1_2, app.tunes.data.MIGRATION_2_3)
        .build()
    val songs = SongRepository(db.songDao())
    val playlists = app.tunes.data.PlaylistRepository(db)
    val localSongs = app.tunes.data.LocalSongs(context)
    val lyrics = LyricsRepository(context.cacheDir)
    val lyricsSource = LyricsSourceStore(context)
    val lyricsPrefs = LyricsPrefsStore(context)
    val sleepTimer = app.tunes.data.SleepTimerStore()
    val streak = app.tunes.data.StreakStore(context)
    val islandPrefs = app.tunes.data.IslandPrefsStore(context)
    val storagePrefs = app.tunes.data.StoragePrefsStore(context)
    val notificationPrefs = app.tunes.data.NotificationPrefsStore(context)
    val translations = TranslationRepository(context.cacheDir)
    val info = InfoStore(context)
    val searchHistory = SearchHistoryStore(context)
    val account = AccountStore(context)
    val appearance = AppearanceStore(context)
    val sync = YtSync(innerTube, account, songs)
    val equalizer = EqualizerController(context)
    /** Level suara asli lagu (dari jalur audio pemutar) untuk gelombang di Dynamic Island. */
    val audioLevels = app.tunes.player.AudioLevels()

    private val appContext = context.applicationContext

    /**
     * Kualitas yang benar-benar dipakai. Otomatis: jaringan berkuota (data seluler/hotspot) -> Rendah,
     * selain itu (Wi-Fi/ethernet) -> Tinggi; kalau status jaringan tak terbaca, Tinggi.
     */
    private fun effectiveQuality(ctx: Context): app.tunes.data.AudioQuality =
        if (playbackPrefs.dataSaver.value) app.tunes.data.AudioQuality.Low
        else when (val q = playbackPrefs.quality.value) {
            app.tunes.data.AudioQuality.Auto -> try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                if (cm.isActiveNetworkMetered) app.tunes.data.AudioQuality.Low else app.tunes.data.AudioQuality.High
            } catch (e: Exception) {
                app.tunes.data.AudioQuality.High
            }
            else -> q
        }

    // Dibuat saat pertama dipakai (bukan saat aplikasi dibuka) supaya startup tetap cepat.
    private val cachesLazy = lazy { MediaCaches(appContext, streamResolver, storagePrefs.audioCacheLimitMb.value.toLong() * 1024 * 1024) }
    val caches: MediaCaches get() = cachesLazy.value
    val storage: app.tunes.data.StorageManager by lazy { app.tunes.data.StorageManager(appContext, this) }
    val downloads: SongDownloads by lazy {
        SongDownloads(
            appContext,
            DownloadStore(appContext),
            app.tunes.data.DownloadedCollectionStore(appContext),
            java.io.File(appContext.filesDir, "download_thumbs"),
            cachesLazy,
        )
    }

    /** Notifikasi progres & gagal unduhan; mulai mengamati unduhan begitu dibuat (lihat MainViewModel.resumePending). */
    val downloadNotifier: app.tunes.player.DownloadNotifier by lazy {
        app.tunes.player.DownloadNotifier(appContext, downloads, notificationPrefs).also { it.start() }
    }
}

class OlyunoApp : Application() {
    lateinit var container: AppContainer
        private set

    /** true selama ada layar Olyuno yang tampil. Dynamic Island disembunyikan saat aplikasi sendiri dibuka. */
    private val _appVisible = MutableStateFlow(false)
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        app.tunes.i18n.I18n.init(this)
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                _appVisible.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                // Rotasi layar = stop lalu start lagi: jangan berkedip memunculkan island.
                if (startedActivities == 0 && !activity.isChangingConfigurations) _appVisible.value = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
