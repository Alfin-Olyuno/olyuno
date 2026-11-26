package app.tunes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pilihan notifikasi unduhan. Notifikasi progres tidak bisa dimatikan di sini: itu notifikasi foreground
 * service yang menjaga unduhan tetap berjalan.
 */
class NotificationPrefsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)

    /** Beri tahu saat unduhan gagal. Bawaan nyala. */
    private val _downloadFailed = MutableStateFlow(prefs.getBoolean(DOWNLOAD_FAILED, true))
    val downloadFailed: StateFlow<Boolean> = _downloadFailed.asStateFlow()

    fun setDownloadFailed(on: Boolean) {
        prefs.edit().putBoolean(DOWNLOAD_FAILED, on).apply()
        _downloadFailed.value = on
    }

    /** Beri tahu saat unduhan selesai ("12 lagu siap offline"). Bawaan mati. */
    private val _downloadDone = MutableStateFlow(prefs.getBoolean(DOWNLOAD_DONE, false))
    val downloadDone: StateFlow<Boolean> = _downloadDone.asStateFlow()

    fun setDownloadDone(on: Boolean) {
        prefs.edit().putBoolean(DOWNLOAD_DONE, on).apply()
        _downloadDone.value = on
    }

    /** Beri tahu saat ada pengumuman baru dari Olyuno. Bawaan nyala. */
    private val _announcements = MutableStateFlow(prefs.getBoolean(ANNOUNCEMENTS, true))
    val announcements: StateFlow<Boolean> = _announcements.asStateFlow()

    fun setAnnouncements(on: Boolean) {
        prefs.edit().putBoolean(ANNOUNCEMENTS, on).apply()
        _announcements.value = on
    }

    /** Pengingat streak: notifikasi sore kalau streak hari ini belum terhitung. Bawaan nyala, pukul 20:00. */
    private val _streakReminder = MutableStateFlow(prefs.getBoolean(STREAK_REMINDER, true))
    val streakReminder: StateFlow<Boolean> = _streakReminder.asStateFlow()

    fun setStreakReminder(on: Boolean) {
        prefs.edit().putBoolean(STREAK_REMINDER, on).apply()
        _streakReminder.value = on
        app.tunes.player.StreakReminder.schedule(appContext, this)
    }

    private val _reminderMinuteOfDay = MutableStateFlow(prefs.getInt(REMINDER_TIME, 20 * 60))
    /** Waktu pengingat, menit sejak 00:00. */
    val reminderMinuteOfDay: StateFlow<Int> = _reminderMinuteOfDay.asStateFlow()

    fun setReminderTime(hour: Int, minute: Int) {
        val v = (hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59))
        prefs.edit().putInt(REMINDER_TIME, v).apply()
        _reminderMinuteOfDay.value = v
        app.tunes.player.StreakReminder.schedule(appContext, this)
    }

    /** Pengingat terakhir menjelang pergantian hari: saklar sendiri, bawaan nyala pukul 23:30. */
    private val _lastReminder = MutableStateFlow(prefs.getBoolean(LAST_REMINDER, true))
    val lastReminder: StateFlow<Boolean> = _lastReminder.asStateFlow()

    fun setLastReminder(on: Boolean) {
        prefs.edit().putBoolean(LAST_REMINDER, on).apply()
        _lastReminder.value = on
        app.tunes.player.StreakReminder.schedule(appContext, this)
    }

    private val _lastReminderMinuteOfDay = MutableStateFlow(prefs.getInt(LAST_REMINDER_TIME, 23 * 60 + 30).coerceIn(LAST_MIN, LAST_MAX))
    /** Waktu pengingat terakhir, menit sejak 00:00 (20:00 sampai 23:55). */
    val lastReminderMinuteOfDay: StateFlow<Int> = _lastReminderMinuteOfDay.asStateFlow()

    fun setLastReminderTime(hour: Int, minute: Int) {
        val v = (hour * 60 + minute).coerceIn(LAST_MIN, LAST_MAX)
        prefs.edit().putInt(LAST_REMINDER_TIME, v).apply()
        _lastReminderMinuteOfDay.value = v
        app.tunes.player.StreakReminder.schedule(appContext, this)
    }

    private companion object {
        const val LAST_MIN = 20 * 60
        const val LAST_MAX = 23 * 60 + 55
        const val LAST_REMINDER = "streak_last_reminder"
        const val LAST_REMINDER_TIME = "streak_last_reminder_time"
        const val STREAK_REMINDER = "streak_reminder"
        const val REMINDER_TIME = "streak_reminder_time"
        const val ANNOUNCEMENTS = "announcements"
        const val DOWNLOAD_FAILED = "download_failed"
        const val DOWNLOAD_DONE = "download_done"
    }
}
