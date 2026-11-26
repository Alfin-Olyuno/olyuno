package app.tunes.data

import android.content.Context
import app.tunes.lyrics.Languages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pengaturan layar lirik: mengikuti per lirik, terjemahan (nyala/mati) dan bahasa tujuannya. */
class LyricsPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lyrics_prefs", Context.MODE_PRIVATE)

    private val _followLine = MutableStateFlow(prefs.getBoolean(FOLLOW, true))
    val followLine: StateFlow<Boolean> = _followLine.asStateFlow()

    private val _translate = MutableStateFlow(prefs.getBoolean(TRANSLATE, false))
    val translate: StateFlow<Boolean> = _translate.asStateFlow()

    private val _target = MutableStateFlow(prefs.getString(TARGET, null) ?: Languages.defaultCode())
    val target: StateFlow<String> = _target.asStateFlow()

    // Jaga layar tetap hidup selama aplikasi terbuka; mati = mengikuti pengaturan layar sistem.
    private val _keepAwake = MutableStateFlow(prefs.getBoolean(KEEP_AWAKE, false))
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()

    fun setKeepAwake(on: Boolean) {
        prefs.edit().putBoolean(KEEP_AWAKE, on).apply()
        _keepAwake.value = on
    }

    fun setFollowLine(on: Boolean) {
        prefs.edit().putBoolean(FOLLOW, on).apply()
        _followLine.value = on
    }

    fun setTranslate(on: Boolean) {
        prefs.edit().putBoolean(TRANSLATE, on).apply()
        _translate.value = on
    }

    fun setTarget(code: String) {
        prefs.edit().putString(TARGET, code).apply()
        _target.value = code
    }

    private companion object {
        const val FOLLOW = "follow_line"
        const val TRANSLATE = "translate"
        const val TARGET = "target_lang"
        const val KEEP_AWAKE = "keep_awake"
    }
}
