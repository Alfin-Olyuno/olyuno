package app.tunes.player

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.tunes.MainActivity
import app.tunes.OlyunoApp
import app.tunes.R
import app.tunes.data.NotificationPrefsStore
import app.tunes.data.StreakState
import app.tunes.i18n.t
import java.util.Calendar

/**
 * Pengingat streak: satu alarm harian (tidak presisi, jadi tidak butuh izin alarm tepat) pada jam pilihan user.
 * Saat berbunyi, notifikasi hanya tampil kalau streak sedang berjalan dan hari ini belum terhitung.
 */
object StreakReminder {
    internal const val ACTION_FIRE = "app.tunes.action.STREAK_REMINDER"
    internal const val ACTION_LAST = "app.tunes.action.STREAK_LAST_CHANCE"
    internal const val ACTION_SNOOZE = "app.tunes.action.STREAK_SNOOZE"
    internal const val ACTION_SNOOZED = "app.tunes.action.STREAK_SNOOZED"
    private const val SNOOZE_MS = 60 * 60_000L
    private const val CHANNEL = "streak_reminder"
    private const val NOTIF_ID = 4101
    private const val REQ = 4101
    private const val REQ_LAST = 4102
    private const val REQ_SNOOZE = 4103
    private const val REQ_SNOOZED = 4104
    private const val FULL_MS = 5 * 60_000L

    private fun alarmIntent(context: Context, last: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (last) REQ_LAST else REQ,
        Intent(context, StreakReminderReceiver::class.java).setAction(if (last) ACTION_LAST else ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun nextTrigger(minuteOfDay: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis() + 1_000L) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    /**
     * Pasang (atau batalkan) alarm berikutnya, masing-masing sesuai saklarnya: pengingat di jam pilihan user dan
     * pengingat terakhir menjelang pergantian hari (dilewati kalau jaraknya kurang dari 1 jam dari pengingat pertama).
     */
    fun schedule(context: Context, prefs: NotificationPrefsStore) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(context)
        val piLast = alarmIntent(context, last = true)
        val minute = prefs.reminderMinuteOfDay.value
        val lastMinute = prefs.lastReminderMinuteOfDay.value
        val evening = prefs.streakReminder.value
        if (evening) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger(minute), pi) }
        } else {
            am.cancel(pi)
        }
        val lastOk = prefs.lastReminder.value && !(evening && kotlin.math.abs(lastMinute - minute) < 60)
        if (lastOk) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger(lastMinute), piLast) }
        } else {
            am.cancel(piLast)
        }
    }

    private fun snoozePending(context: Context, request: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        request,
        Intent(context, StreakReminderReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** true kalau satu jam dari sekarang masih di hari yang sama (tunda tidak melewati tengah malam). */
    private fun canSnooze(): Boolean {
        val now = Calendar.getInstance()
        val later = Calendar.getInstance().apply { timeInMillis = now.timeInMillis + SNOOZE_MS }
        return now.get(Calendar.DAY_OF_YEAR) == later.get(Calendar.DAY_OF_YEAR)
    }

    /** Tombol "Tunda 1 jam": tutup notifikasi dan bunyikan lagi satu jam kemudian (kalau belum lewat tengah malam). */
    internal fun snooze(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        if (!canSnooze()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + SNOOZE_MS,
                snoozePending(context, REQ_SNOOZED, ACTION_SNOOZED),
            )
        }
    }

    internal fun fire(context: Context, last: Boolean = false, snoozed: Boolean = false) {
        val container = (context.applicationContext as OlyunoApp).container
        val prefs = container.notificationPrefs
        schedule(context, prefs) // rantai alarm hari berikutnya dulu, supaya tetap jalan walau notifikasi dilewati
        if (!(if (snoozed) prefs.streakReminder.value || prefs.lastReminder.value else if (last) prefs.lastReminder.value else prefs.streakReminder.value)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        container.streak.refresh()
        val s = container.streak.state.value
        if (s.current <= 0 || s.countedToday || s.clockSuspicious) return
        val (title, text) = if (last) lastChanceMessage(s) else message(s)
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            REQ,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_streak)
            .setColor(0xFFFF7A1A.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .apply {
                if (canSnooze()) addAction(0, t("Tunda 1 jam"), snoozePending(context, REQ_SNOOZE, ACTION_SNOOZE))
            }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) }
    }

    /** Pengingat terakhir menjelang pergantian hari. */
    internal fun lastChanceMessage(s: StreakState): Pair<String, String> =
        t("Nyalakan streak hari ini") to t("Hari hampir berganti. Putar satu lagu sekarang supaya streak %d harimu tidak putus.", s.current)

    /**
     * Teks notifikasi. Prioritas: hampir mencapai kelipatan 7 hari > sudah mulai mendengar hari ini >
     * freeze siaga (hari genap) > variasi umum yang berganti tiap hari.
     */
    internal fun message(s: StreakState): Pair<String, String> {
        val n = s.current
        val next = n + 1
        return when {
            next % 7 == 0 ->
                t("Sedikit lagi %d hari beruntun", next) to t("Dengarkan musik hari ini untuk mencapai %d hari.", next)
            s.todayMs >= 60_000L -> {
                val left = ((FULL_MS - s.todayMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                t("Tinggal sedikit lagi") to t("Kurang sekitar %d menit lagi untuk menjaga streak %d harimu.", left, n)
            }
            s.freezes > 0 && s.todayEpochDay % 2L == 0L ->
                t("Streak %d hari belum aman", n) to t("Freeze-mu siaga, tapi lebih seru kalau tidak perlu dipakai. Putar satu lagu sekarang.")
            else -> when ((s.todayEpochDay % 3L).toInt()) {
                0 -> t("Streak %d hari menunggumu", n) to t("Putar satu lagu sampai habis supaya apinya tidak padam.")
                1 -> t("Jangan biarkan apinya padam") to t("Dengarkan musik 5 menit hari ini untuk menjaga streak %d harimu.", n)
                else -> t("Waktunya satu lagu") to t("Streak %d harimu belum terhitung hari ini.", n)
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel(CHANNEL, t("Pengingat streak"), NotificationManager.IMPORTANCE_DEFAULT))
    }
}

/** Menerima alarm harian, dan memasang ulang alarm setelah HP restart, aplikasi diperbarui, atau jam/zona waktu berubah. */
class StreakReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == StreakReminder.ACTION_FIRE || action == StreakReminder.ACTION_LAST) {
            StreakReminder.fire(context, last = action == StreakReminder.ACTION_LAST)
        } else if (action == StreakReminder.ACTION_SNOOZE) {
            StreakReminder.snooze(context)
        } else if (action == StreakReminder.ACTION_SNOOZED) {
            StreakReminder.fire(context, snoozed = true)
        } else {
            val prefs = (context.applicationContext as OlyunoApp).container.notificationPrefs
            StreakReminder.schedule(context, prefs)
        }
    }
}
