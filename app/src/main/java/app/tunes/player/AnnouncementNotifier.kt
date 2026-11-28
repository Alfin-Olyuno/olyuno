package app.tunes.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tunes.MainActivity
import app.tunes.OlyunoApp
import app.tunes.R
import app.tunes.data.InfoMessage
import app.tunes.i18n.t
import java.util.concurrent.TimeUnit

/**
 * Notifikasi pengumuman baru. Tanpa Firebase: pekerja latar belakang (WorkManager) memeriksa server tiap ~15 menit
 * (batas terkecil Android) dan memberi notifikasi untuk pengumuman yang belum pernah dilihat. Jadi notifikasi bisa
 * terlambat beberapa menit, dan lebih lama lagi kalau HP sedang hemat daya.
 */
object AnnouncementNotifier {
    private const val WORK_NAME = "announcement_check"
    private const val CHANNEL = "announcements_v2"
    private const val OLD_CHANNEL = "announcements"
    private const val MAX_SHOWN = 3

    /** Jadwalkan pemeriksaan berkala (aman dipanggil berulang: yang sudah terjadwal dipertahankan). */
    fun schedule(context: Context) {
        // Kanal dibuat sejak awal supaya "Pengumuman" langsung terlihat di pengaturan notifikasi sistem.
        ensureChannel(context)
        val req = PeriodicWorkRequestBuilder<AnnouncementWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Kanal lama (kepentingan sedang, tanpa pop-up) diganti kanal baru yang muncul sebagai pop-up; kepentingan kanal tidak bisa diubah setelah dibuat.
        mgr.deleteNotificationChannel(OLD_CHANNEL)
        mgr.createNotificationChannel(NotificationChannel(CHANNEL, t("Pengumuman"), NotificationManager.IMPORTANCE_HIGH))
    }

    /**
     * Satu notifikasi per pengumuman (maksimal 3 sekaligus); ketuk membuka halaman Informasi. Mengembalikan id pengumuman
     * yang benar-benar berhasil ditampilkan; sisanya (terpotong batas 3 atau gagal) dibiarkan belum ditandai supaya
     * dicoba lagi pada pemeriksaan berikutnya.
     */
    fun post(context: Context, messages: List<InfoMessage>): List<String> {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return emptyList()
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            4201,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_INFO, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val shown = ArrayList<String>()
        for (m in messages.take(MAX_SHOWN)) {
            val text = m.shownPreview()
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif_announce)
                .setContentTitle(m.shownTitle())
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            if (runCatching { NotificationManagerCompat.from(context).notify(m.id.hashCode(), n) }.isSuccess) shown += m.id
        }
        return shown
    }
}

class AnnouncementWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? OlyunoApp ?: return Result.success()
        val container = app.container
        val refreshed = container.info.refresh()
        val on = container.notificationPrefs.announcements.value
        // Saklar mati: semua ditandai sudah ditangani, supaya menyalakannya nanti tidak memunculkan pengumuman lama.
        // Saklar nyala: yang baru ditandai hanya setelah notifikasinya benar-benar tampil.
        val fresh = container.info.takeUnnotified(markFresh = !on)
        if (on && fresh.isNotEmpty()) {
            container.info.markNotified(AnnouncementNotifier.post(applicationContext, fresh))
        }
        // Jaringan gagal sesaat: coba lagi sebentar lagi, jangan menunggu 15 menit berikutnya.
        return if (!refreshed && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
