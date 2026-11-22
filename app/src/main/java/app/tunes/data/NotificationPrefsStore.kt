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

    private companion object {
        const val DOWNLOAD_FAILED = "download_failed"
        const val DOWNLOAD_DONE = "download_done"
    }
}
