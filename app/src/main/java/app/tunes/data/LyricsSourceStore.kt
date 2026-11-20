package app.tunes.data

import android.content.Context
import app.tunes.lyrics.LyricsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Layanan lirik pilihan pengguna (default: Otomatis), disimpan di SharedPreferences. */
class LyricsSourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("lyrics_source", Context.MODE_PRIVATE)

    private val _source = MutableStateFlow(LyricsSource.fromKey(prefs.getString(KEY, null)))
    val source: StateFlow<LyricsSource> = _source.asStateFlow()

    fun set(source: LyricsSource) {
        prefs.edit().putString(KEY, source.key).apply()
        _source.value = source
    }

    private companion object {
        const val KEY = "source"
    }
}
