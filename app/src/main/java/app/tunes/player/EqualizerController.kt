package app.tunes.player

import android.content.Context
import android.media.audiofx.BassBoost
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
    /** Penguat bass 0..1000 (0 = mati). */
    val bass: Int = 0,
    /** false kalau perangkat tidak mendukung penguat bass. */
    val bassSupported: Boolean = false,
    /** Id preset yang sedang dipakai ([EqPreset.id]); kosong = pengaturan manual. */
    val preset: String = "",
)

/**
 * Preset ekualiser. [curve] = penguatan dalam dB di titik-titik rata dari bass ke treble; dipetakan ke jumlah
 * band perangkat dengan interpolasi, jadi bekerja di perangkat dengan 5 band maupun lebih.
 */
enum class EqPreset(val id: String, val label: String, val curve: List<Float>) {
    Flat("flat", "Datar", listOf(0f, 0f, 0f, 0f, 0f)),
    Bass("bass", "Bass", listOf(5f, 3.5f, 0f, 0f, 0f)),
    Treble("treble", "Treble", listOf(0f, 0f, 0f, 3f, 5f)),
    Vocal("vocal", "Vokal", listOf(-2f, 0f, 3.5f, 3f, 0f)),
    Rock("rock", "Rock", listOf(4f, 2f, -1.5f, 2.5f, 4f)),
    Pop("pop", "Pop", listOf(-1f, 2.5f, 4f, 2f, -1f)),
    Electronic("electronic", "Elektronik", listOf(4.5f, 2f, 0f, 2.5f, 4f)),
    Acoustic("acoustic", "Akustik", listOf(3f, 2f, 1f, 2.5f, 3f)),
}

private fun interpolate(curve: List<Float>, bands: Int): List<Float> {
    if (bands <= 1) return List(bands) { curve.first() }
    return List(bands) { i ->
        val pos = i.toFloat() / (bands - 1) * (curve.size - 1)
        val lo = pos.toInt().coerceIn(0, curve.size - 1)
        val hi = (lo + 1).coerceAtMost(curve.size - 1)
        curve[lo] + (curve[hi] - curve[lo]) * (pos - lo)
    }
}

val DEFAULT_FREQS = listOf(60, 230, 910, 3600, 14000)

/**
 * Ekualiser sistem Android ([Equalizer]) yang dipasang ke audio session ExoPlayer.
 * Pengaturan disimpan; begitu service pemutar menyambungkan session, semuanya diterapkan.
 */
class EqualizerController(context: Context) {
    private val prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private var eq: Equalizer? = null
    private var bassBoost: BassBoost? = null

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<EqState> = _state.asStateFlow()

    private fun savedLevels(): List<Int> =
        (prefs.getString("levels", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }

    private fun initial(): EqState {
        val saved = savedLevels()
        val levels = if (saved.size == DEFAULT_FREQS.size) saved else List(DEFAULT_FREQS.size) { 0 }
        return EqState(
            enabled = prefs.getBoolean("enabled", false),
            levels = levels,
            bass = prefs.getInt("bass", 0).coerceIn(0, 1000),
            preset = prefs.getString("preset", "") ?: "",
        )
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
            val bass = prefs.getInt("bass", 0).coerceIn(0, 1000)
            var bassOk = false
            bassBoost = try {
                BassBoost(0, sessionId).also { b ->
                    bassOk = b.strengthSupported
                    if (bassOk) b.setStrength(bass.toShort())
                    b.setEnabled(enabled && bassOk && bass > 0)
                }
            } catch (ex: Exception) {
                null
            }
            _state.value = EqState(
                attached = true, enabled = enabled, minLevel = min, maxLevel = max, freqsHz = freqs, levels = levels,
                bass = bass, bassSupported = bassOk, preset = prefs.getString("preset", "") ?: "",
            )
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
        try {
            bassBoost?.release()
        } catch (ex: Exception) {
            // abaikan
        }
        bassBoost = null
        _state.update { it.copy(attached = false) }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
        try {
            eq?.setEnabled(enabled)
            bassBoost?.setEnabled(enabled && _state.value.bassSupported && _state.value.bass > 0)
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
        prefs.edit().putString("levels", levels.joinToString(",")).putString("preset", "").apply()
        try {
            eq?.setBandLevel(index.toShort(), v.toShort())
        } catch (ex: Exception) {
            // abaikan
        }
        _state.value = s.copy(levels = levels, preset = "")
    }

    /** Terapkan preset: tiap band diatur dari kurva preset, dibatasi rentang band perangkat. */
    @Synchronized
    fun applyPreset(preset: EqPreset) {
        val s = _state.value
        val gains = interpolate(preset.curve, s.levels.size)
        val levels = gains.map { (it * 100).toInt().coerceIn(s.minLevel, s.maxLevel) }
        prefs.edit().putString("levels", levels.joinToString(",")).putString("preset", preset.id).apply()
        levels.forEachIndexed { i, l ->
            try {
                eq?.setBandLevel(i.toShort(), l.toShort())
            } catch (ex: Exception) {
                // abaikan
            }
        }
        _state.value = s.copy(levels = levels, preset = preset.id)
    }

    /** Kekuatan penguat bass 0..1000. */
    @Synchronized
    fun setBass(strength: Int) {
        val v = strength.coerceIn(0, 1000)
        prefs.edit().putInt("bass", v).apply()
        val s = _state.value
        try {
            bassBoost?.let { b ->
                if (s.bassSupported) b.setStrength(v.toShort())
                b.setEnabled(s.enabled && s.bassSupported && v > 0)
            }
        } catch (ex: Exception) {
            // abaikan
        }
        _state.value = s.copy(bass = v)
    }

    @Synchronized
    fun reset() {
        applyPreset(EqPreset.Flat)
        setBass(0)
    }
}
