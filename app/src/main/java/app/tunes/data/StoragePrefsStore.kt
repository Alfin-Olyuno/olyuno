package app.tunes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pengaturan penyimpanan: batas ukuran cache audio streaming (MB). Bawaan 512 MB. */
class StoragePrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE)

    private val _audioCacheLimitMb = MutableStateFlow(
        prefs.getInt(KEY_LIMIT, DEFAULT_LIMIT_MB).let { if (it in LIMIT_OPTIONS_MB) it else DEFAULT_LIMIT_MB },
    )
    val audioCacheLimitMb: StateFlow<Int> = _audioCacheLimitMb.asStateFlow()

    fun setAudioCacheLimitMb(mb: Int) {
        prefs.edit().putInt(KEY_LIMIT, mb).apply()
        _audioCacheLimitMb.value = mb
    }

    companion object {
        private const val KEY_LIMIT = "audio_cache_limit_mb"
        const val DEFAULT_LIMIT_MB = 512
        val LIMIT_OPTIONS_MB = listOf(128, 256, 512, 1024, 2048)
    }
}
