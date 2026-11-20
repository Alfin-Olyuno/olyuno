package app.tunes.player

import android.content.Context
import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Level band dalam milibel (1 dB = 100 mB). */
data class EqState(
    /** true kalau sudah tersambung ke pemutar (ada lagu yang pernah diputar). */
    val attached: Boolean = false,
    val enabled: Boolean = false,
    val minLevel: Int = -1500,
    val maxLevel: Int = 1500,
    val freqsHz: List<Int> = DEFAULT_FREQS,
    val levels: List<Int> = List(DEFAULT_FREQS.size) { 0 },
)

val DEFAULT_FREQS = listOf(60, 230, 910, 3600, 14000)

/**
 * Ekualiser sistem Android ([Equalizer]) yang dipasang ke audio session ExoPlayer.
 * Pengaturan disimpan; begitu service pemutar menyambungkan session, semuanya diterapkan.
 */
class EqualizerController(context: Context) {
    private val prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private var eq: Equalizer? = null

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<EqState> = _state.asStateFlow()

    private fun savedLevels(): List<Int> =
        (prefs.getString("levels", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }

    private fun initial(): EqState {
        val saved = savedLevels()
        val levels = if (saved.size == DEFAULT_FREQS.size) saved else List(DEFAULT_FREQS.size) { 0 }
        return EqState(enabled = prefs.getBoolean("enabled", false), levels = levels)
    }

    /** Dipanggil service pemutar setelah ExoPlayer dibuat. */
    @Synchronized
    fun attach(sessionId: Int) {
        release()
        try {
            val e = Equalizer(0, sessionId)
            val bands = e.numberOfBands.toInt()
            val range = e.bandLevelRange
            val min = range[0].toInt()
            val max = range[1].toInt()
            val freqs = List(bands) { e.getCenterFreq(it.toShort()) / 1000 }
            val saved = savedLevels()
            val levels = if (saved.size == bands) saved.map { it.coerceIn(min, max) } else List(bands) { 0 }
            levels.forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.toShort()) }
            val enabled = prefs.getBoolean("enabled", false)
            e.setEnabled(enabled)
            eq = e
            _state.value = EqState(true, enabled, min, max, freqs, levels)
        } catch (ex: Exception) {
            eq = null
            _state.update { it.copy(attached = false) }
        }
    }

    @Synchronized
    fun release() {
        try {
            eq?.release()
        } catch (ex: Exception) {
            // abaikan
        }
        eq = null
        _state.update { it.copy(attached = false) }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
        try {
            eq?.setEnabled(enabled)
        } catch (ex: Exception) {
            // abaikan
        }
        _state.update { it.copy(enabled = enabled) }
    }

    @Synchronized
    fun setBand(index: Int, level: Int) {
        val s = _state.value
        if (index !in s.levels.indices) return
        val v = level.coerceIn(s.minLevel, s.maxLevel)
        val levels = s.levels.toMutableList().also { it[index] = v }
        prefs.edit().putString("levels", levels.joinToString(",")).apply()
        try {
            eq?.setBandLevel(index.toShort(), v.toShort())
        } catch (ex: Exception) {
            // abaikan
        }
        _state.value = s.copy(levels = levels)
    }

    @Synchronized
    fun reset() {
        val s = _state.value
        val levels = List(s.levels.size) { 0 }
        prefs.edit().putString("levels", levels.joinToString(",")).apply()
        levels.forEachIndexed { i, l ->
            try {
                eq?.setBandLevel(i.toShort(), l.toShort())
            } catch (ex: Exception) {
                // abaikan
            }
        }
        _state.value = s.copy(levels = levels)
    }
}
