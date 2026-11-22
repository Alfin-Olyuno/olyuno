package app.tunes.player

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.tunes.MainActivity
import app.tunes.OlyunoApp
import app.tunes.R
import app.tunes.data.NotificationPrefsStore
import app.tunes.i18n.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/** Isi notifikasi progres. */
data class DownloadSummary(val title: String, val text: String?, val pct: Int)

/**
 * Notifikasi unduhan.
 *
 * Progres: SATU notifikasi ringkasan yang dipegang [DownloadService] (foreground service, jadi unduhan tetap
 * jalan walau aplikasi ditutup / musik tidak diputar).
 *  - Satu playlist/album atau satu lagu: "[nama] • [persen]%"
 *  - Beberapa sekaligus: "Mengunduh [n] unduhan • [persen]%" + daftar namanya.
 *  - Tombol "Batal" menghentikan semua unduhan yang belum selesai.
 *
 * Gagal: satu notifikasi per playlist/lagu setelah semua yang lain di unit itu selesai dicoba, dengan tombol
 * "Ulangi unduhan".
 */
class DownloadNotifier(
    context: Context,
    private val downloads: SongDownloads,
    private val prefs: NotificationPrefsStore,
) {
    private val app = context.applicationContext
    private val nm = NotificationManagerCompat.from(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _summary = MutableStateFlow<DownloadSummary?>(null)

    /** null = tidak ada unduhan yang berjalan. Diamati oleh [DownloadService]. */
    val summary: StateFlow<DownloadSummary?> = _summary.asStateFlow()

    /** Notifikasi "gagal" yang sedang tampil: kunci unit -> jumlah lagu gagal. */
    private val shownFailed = HashMap<String, Int>()

    /** Unit yang ikut dihitung di persen ringkasan selama gelombang unduhan ini (reset kalau semuanya selesai). */
    private val batch = LinkedHashMap<String, Item>()

    @Volatile
    private var serviceActive = false

    /** true kalau service gagal dijalankan dan ringkasan diposting sebagai notifikasi biasa. */
    private var fallbackShown = false
    private var started = false

    private sealed interface Item {
        val key: String
        val name: String
        val songIds: List<String>
    }

    private class Pack(override val key: String, override val name: String, override val songIds: List<String>) : Item
    private class Single(override val key: String, override val name: String, override val songIds: List<String>) : Item

    fun start() {
        if (started) return
        started = true
        ensureChannels()
        scope.launch {
            combine(
                combine(downloads.entries, downloads.collections, downloads.progress, downloads.failed, downloads.online) { _, _, _, _, _ -> },
                prefs.downloadFailed,
            ) { _, _ -> }
                .conflate()
                .collect {
                    render()
                    // Batasi ~2x/detik; yang menumpuk digabung oleh conflate().
                    delay(500)
                }
        }
    }

    @Synchronized
    private fun render() {
        val entries = downloads.entries.value.associateBy { it.song.videoId }
        val progress = downloads.progress.value
        val failed = downloads.failed.value

        // Unit: tiap paket playlist/album, lalu lagu satuan yang bukan bagian paket mana pun.
        val units = ArrayList<Item>()
        val inPack = HashSet<String>()
        for (c in downloads.collections.value) {
            val ids = c.songs.map { it.videoId }.filter { it in entries }
            if (ids.isEmpty()) continue
            inPack += ids
            units += Pack("c_${c.ref.key}", c.ref.title, ids)
        }
        for (e in entries.values) {
            if (e.song.videoId in inPack) continue
            units += Single("s_${e.song.videoId}", e.song.title, listOf(e.song.videoId))
        }

        fun isPending(id: String) = entries[id]?.done != true && id !in failed

        val pendingUnits = units.filter { u -> u.songIds.any(::isPending) }
        val failedUnits = HashMap<String, Int>()
        for (u in units) {
            if (u in pendingUnits) continue
            val n = u.songIds.count { it in failed && entries[it]?.done != true }
            if (n > 0) failedUnits[u.key] = n
        }

        // --- Ringkasan progres ---
        if (pendingUnits.isEmpty()) {
            // Gelombang unduhan selesai: umumkan yang tuntas seluruhnya (yang dibatalkan / dihapus tidak ikut).
            if (batch.isNotEmpty()) {
                val finished = batch.values.mapNotNull { b ->
                    val u = units.firstOrNull { it.key == b.key } ?: return@mapNotNull null
                    if (u.songIds.all { entries[it]?.done == true }) u else null
                }
                if (finished.isNotEmpty() && prefs.downloadDone.value) postDone(finished)
            }
            batch.clear()
        } else {
            val alive = units.associateBy { it.key }
            batch.keys.retainAll(alive.keys)
            for (u in pendingUnits) batch[u.key] = u
            // Persen mengikuti semua lagu di gelombang ini (yang gagal tidak dihitung), jadi tidak mundur
            // saat satu unit selesai dan keluar dari daftar "sedang berjalan".
            val ids = batch.values.flatMap { it.songIds }.distinct().filter { it !in failed }
            val sum = ids.sumOf { id -> if (entries[id]?.done == true) 1.0 else (progress[id] ?: 0f).toDouble() }
            val pct = if (ids.isEmpty()) 0 else ((sum / ids.size) * 100).toInt().coerceIn(0, 100)
            // Tanpa internet: unduhan dijeda, notifikasi tetap ada dengan teks "Menunggu internet…".
            val waitText = if (!downloads.online.value) t("Menunggu internet…") else null
            val s = if (pendingUnits.size == 1) {
                val u = pendingUnits[0]
                val text = if (u is Pack && u.songIds.size > 1) {
                    // Playlist/album: "5/12 lagu • judul yang sedang diunduh".
                    val done = u.songIds.count { entries[it]?.done == true }
                    val total = u.songIds.size
                    if (waitText != null) {
                        t("Menunggu internet… • %d/%d lagu", done, total)
                    } else {
                        val current = u.songIds.firstOrNull { it in progress && entries[it]?.done != true }
                            ?: u.songIds.firstOrNull(::isPending)
                        val title = current?.let { entries[it]?.song?.title }
                        if (title.isNullOrBlank()) t("%d/%d lagu", done, total)
                        else t("%d/%d lagu • %s", done, total, title)
                    }
                } else waitText
                DownloadSummary("${u.name} • $pct%", text, pct)
            } else {
                DownloadSummary(
                    t("Mengunduh %d unduhan • %d%%", pendingUnits.size, pct),
                    waitText ?: (pendingUnits.take(3).joinToString(", ") { it.name } +
                        if (pendingUnits.size > 3) " +${pendingUnits.size - 3}" else ""),
                    pct,
                )
            }
            _summary.value = s
        }
        if (pendingUnits.isEmpty()) _summary.value = null
        syncService()

        // --- Notifikasi gagal (bisa dimatikan di halaman Notifikasi) ---
        val showFailed = prefs.downloadFailed.value
        for ((key, n) in failedUnits) {
            if (!showFailed) break
            if (shownFailed[key] == n) continue
            val u = units.first { it.key == key }
            shownFailed[key] = n
            postFailed(u, n)
        }
        val stale = shownFailed.keys.filter { !showFailed || it !in failedUnits }
        for (k in stale) {
            shownFailed.remove(k)
            runCatching { nm.cancel(failedId(k)) }
        }
    }

    /** Pastikan service hidup selama ada unduhan; kalau tidak boleh dijalankan, pakai notifikasi biasa. */
    private fun syncService() {
        val s = _summary.value
        if (s == null) {
            if (fallbackShown) {
                fallbackShown = false
                runCatching { nm.cancel(SUMMARY_ID) }
            }
            return
        }
        if (serviceActive) return
        try {
            serviceActive = true
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (e: Exception) {
            // Mis. dijalankan dari latar belakang tanpa izin: tetap tampilkan progres, tanpa jaminan jalan terus.
            serviceActive = false
            if (canNotify()) {
                fallbackShown = true
                try {
                    nm.notify(SUMMARY_ID, buildSummary(s))
                } catch (_: SecurityException) {
                }
            }
        }
    }

    /** Dipanggil [DownloadService.onDestroy]; cek ulang kalau ada unduhan baru yang masuk saat service berhenti. */
    internal fun onServiceStopped() {
        serviceActive = false
        scope.launch { render() }
    }

    internal fun onServiceStarted() {
        serviceActive = true
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return nm.areNotificationsEnabled()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app,
        0,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, key: String?, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        app,
        requestCode,
        Intent(app, DownloadActionReceiver::class.java).setAction(action).apply {
            if (key != null) putExtra(DownloadActionReceiver.EXTRA_KEY, key)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Notifikasi progres (juga dipakai sebagai notifikasi foreground service). */
    internal fun buildSummary(s: DownloadSummary?): Notification {
        val b = NotificationCompat.Builder(app, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notif_download)
            .setContentTitle(s?.title ?: t("Mengunduh…"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, t("Batalkan"), action(DownloadActionReceiver.ACTION_CANCEL, null, 1))
        if (s != null) {
            b.setProgress(100, s.pct, false)
            s.text?.let { b.setContentText(it) }
        } else {
            b.setProgress(100, 0, true)
        }
        return b.build()
    }

    private fun postDone(finished: List<Item>) {
        if (!canNotify()) return
        val text = if (finished.size == 1) {
            val u = finished[0]
            if (u is Pack) t("%s • %d lagu sudah diunduh", u.name, u.songIds.size) else t("\"%s\" sudah diunduh", u.name)
        } else {
            t("%d unduhan selesai", finished.size)
        }
        val n = NotificationCompat.Builder(app, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notif_download)
            .setContentTitle(t("Unduhan selesai"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp())
            .build()
        try {
            nm.notify(DONE_ID, n)
        } catch (_: SecurityException) {
        }
    }

    private fun postFailed(u: Item, failedCount: Int) {
        if (!canNotify()) return
        val text = if (u is Pack) t("%s • %d lagu", u.name, failedCount) else u.name
        val n = NotificationCompat.Builder(app, CHANNEL_FAILED)
            .setSmallIcon(R.drawable.ic_notif_download)
            .setContentTitle(t("Unduhan gagal"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openApp())
            .addAction(0, t("Ulangi unduhan"), action(DownloadActionReceiver.ACTION_RETRY, u.key, failedId(u.key)))
            .build()
        try {
            nm.notify(failedId(u.key), n)
        } catch (_: SecurityException) {
            // izin notifikasi dicabut di tengah jalan
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, t("Progres unduhan"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, t("Unduhan selesai"), NotificationManager.IMPORTANCE_DEFAULT),
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_FAILED, t("Unduhan gagal"), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Ulangi unduhan yang gagal untuk satu unit ("c_<kunci paket>" atau "s_<videoId>"). */
    internal fun retry(key: String) {
        when {
            key.startsWith("c_") -> downloads.retryCollection(key.removePrefix("c_"))
            key.startsWith("s_") -> {
                val id = key.removePrefix("s_")
                downloads.entries.value.firstOrNull { it.song.videoId == id }?.let { downloads.enqueue(it.song) }
            }
        }
    }

    internal fun cancelAll() = downloads.cancelPending()

    private fun failedId(key: String): Int = FAILED_BASE + (key.hashCode() and 0x0FFFFFFF)

    companion object {
        const val CHANNEL_PROGRESS = "download_progress"
        const val CHANNEL_FAILED = "download_failed"
        const val CHANNEL_DONE = "download_done"
        private const val DONE_ID = 0x3FFF_0002
        const val SUMMARY_ID = 0x3FFF_0001
        private const val FAILED_BASE = 0x4000_0000
    }
}

/**
 * Foreground service (tipe dataSync) yang menjaga unduhan tetap hidup. Nyala selama ada unduhan, berhenti
 * sendiri begitu antrean kosong. Notifikasinya = ringkasan progres dari [DownloadNotifier].
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifier = (application as OlyunoApp).container.downloadNotifier
        notifier.onServiceStarted()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, DownloadNotifier.SUMMARY_ID, notifier.buildSummary(notifier.summary.value), type)
        if (job == null) {
            job = scope.launch {
                notifier.summary.collect { s ->
                    if (s == null) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        NotificationManagerCompat.from(this@DownloadService).apply {
                            try {
                                notify(DownloadNotifier.SUMMARY_ID, notifier.buildSummary(s))
                            } catch (_: SecurityException) {
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: batas waktu foreground service dataSync; berhenti dengan rapi, sisa dilanjutkan saat app dibuka. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        (application as OlyunoApp).container.downloadNotifier.onServiceStopped()
        super.onDestroy()
    }
}

/** Tombol di notifikasi unduhan: "Ulangi unduhan" (gagal) dan "Batalkan" (progres). */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notifier = (context.applicationContext as OlyunoApp).container.downloadNotifier
        when (intent.action) {
            ACTION_RETRY -> intent.getStringExtra(EXTRA_KEY)?.let(notifier::retry)
            ACTION_CANCEL -> notifier.cancelAll()
        }
    }

    companion object {
        const val ACTION_RETRY = "app.tunes.DOWNLOAD_RETRY"
        const val ACTION_CANCEL = "app.tunes.DOWNLOAD_CANCEL"
        const val EXTRA_KEY = "key"
    }
}
