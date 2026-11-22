package app.tunes.data

import android.content.Context
import app.tunes.innertube.SongItem
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pilihan kualitas audio. [Auto] = disesuaikan otomatis dengan jaringan (Wi-Fi/tanpa batas kuota = tinggi,
 * data seluler/berkuota = rendah); [High] = selalu bitrate tertinggi; [Low] = selalu bitrate terendah.
 */
enum class AudioQuality { Auto, High, Low }

/** Pengaturan pemutaran: Putar otomatis (lanjut ke lagu serupa saat antrean streaming habis). */
class PlaybackPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)

    private val _autoplay = MutableStateFlow(prefs.getBoolean(AUTOPLAY, true))
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()

    fun setAutoplay(on: Boolean) {
        prefs.edit().putBoolean(AUTOPLAY, on).apply()
        _autoplay.value = on
    }

    /** Kualitas audio streaming (Otomatis / Tinggi / Rendah). Bawaannya Otomatis. */
    private val _quality = MutableStateFlow(
        runCatching { AudioQuality.valueOf(prefs.getString(QUALITY, null) ?: "") }.getOrDefault(AudioQuality.Auto),
    )
    val quality: StateFlow<AudioQuality> = _quality.asStateFlow()

    fun setQuality(q: AudioQuality) {
        prefs.edit().putString(QUALITY, q.name).apply()
        _quality.value = q
    }

    /** Lewati bagian hening: bawaan mati. */
    private val _skipSilence = MutableStateFlow(prefs.getBoolean(SKIP_SILENCE, false))
    val skipSilence: StateFlow<Boolean> = _skipSilence.asStateFlow()

    fun setSkipSilence(on: Boolean) {
        prefs.edit().putBoolean(SKIP_SILENCE, on).apply()
        _skipSilence.value = on
    }

    /** Normalisasi volume: bawaan nyala (hanya menurunkan lagu yang terlalu keras, tidak pernah menaikkan). */
    private val _normalize = MutableStateFlow(prefs.getBoolean(NORMALIZE, true))
    val normalize: StateFlow<Boolean> = _normalize.asStateFlow()

    fun setNormalize(on: Boolean) {
        prefs.edit().putBoolean(NORMALIZE, on).apply()
        _normalize.value = on
    }

    /**
     * Hemat data: kualitas audio dikunci Rendah, Putar otomatis berhenti, terjemahan lirik mati, dan unduhan
     * hanya lewat Wi-Fi. Pilihan pengguna di pengaturan lain tidak diubah, jadi kembali begitu dimatikan.
     */
    private val _dataSaver = MutableStateFlow(prefs.getBoolean(DATA_SAVER, false))
    val dataSaver: StateFlow<Boolean> = _dataSaver.asStateFlow()

    fun setDataSaver(on: Boolean) {
        prefs.edit().putBoolean(DATA_SAVER, on).apply()
        _dataSaver.value = on
    }

    /** Lanjutkan terakhir diputar: saat aplikasi dibuka lagi, antrean dan posisi lagu kembali seperti tadi. Bawaan nyala. */
    private val _resumeLast = MutableStateFlow(prefs.getBoolean(RESUME_LAST, true))
    val resumeLast: StateFlow<Boolean> = _resumeLast.asStateFlow()

    fun setResumeLast(on: Boolean) {
        prefs.edit().putBoolean(RESUME_LAST, on).apply()
        _resumeLast.value = on
        if (!on) clearResume()
    }

    /** Antrean terakhir + posisi, disimpan terpisah dari pengaturan di atas. */
    class ResumeState(
        val queue: List<SongItem>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    private val resumePrefs = context.getSharedPreferences("resume_state", Context.MODE_PRIVATE)

    /** Simpan antrean lengkap (dipanggil saat antrean berubah). */
    fun saveResumeQueue(queue: List<SongItem>, index: Int, positionMs: Long, shuffle: Boolean, repeatMode: Int) {
        val arr = JSONArray()
        // Batasi ukuran supaya penyimpanan tetap ringan: ambil jendela di sekitar lagu yang sedang diputar.
        val from = (index - MAX_RESUME_QUEUE / 4).coerceAtLeast(0)
        val to = (from + MAX_RESUME_QUEUE).coerceAtMost(queue.size)
        for (i in from until to) {
            val s = queue[i]
            arr.put(
                JSONObject().put("v", s.videoId).put("t", s.title).put("a", s.artist)
                    .put("d", s.durationSec).put("th", s.thumbnail ?: JSONObject.NULL),
            )
        }
        resumePrefs.edit()
            .putString(R_QUEUE, arr.toString())
            .putInt(R_FROM, from)
            .putInt(R_INDEX, index - from)
            .putLong(R_POS, positionMs)
            .putBoolean(R_SHUFFLE, shuffle)
            .putInt(R_REPEAT, repeatMode)
            .apply()
    }

    /** Simpan posisi saja (murah, dipanggil berkala saat memutar). */
    fun saveResumePosition(index: Int, positionMs: Long, shuffle: Boolean, repeatMode: Int) {
        if (!resumePrefs.contains(R_QUEUE)) return
        val from = resumePrefs.getInt(R_FROM, 0)
        resumePrefs.edit()
            .putInt(R_INDEX, index - from)
            .putLong(R_POS, positionMs)
            .putBoolean(R_SHUFFLE, shuffle)
            .putInt(R_REPEAT, repeatMode)
            .apply()
    }

    fun loadResume(): ResumeState? = runCatching {
        val raw = resumePrefs.getString(R_QUEUE, null) ?: return null
        val arr = JSONArray(raw)
        val queue = List(arr.length()) {
            val o = arr.getJSONObject(it)
            SongItem(
                videoId = o.getString("v"),
                title = o.optString("t"),
                artist = o.optString("a"),
                durationSec = o.optInt("d"),
                thumbnail = if (o.isNull("th")) null else o.optString("th"),
            )
        }
        if (queue.isEmpty()) return null
        ResumeState(
            queue = queue,
            index = resumePrefs.getInt(R_INDEX, 0).coerceIn(0, queue.lastIndex),
            positionMs = resumePrefs.getLong(R_POS, 0L).coerceAtLeast(0L),
            shuffle = resumePrefs.getBoolean(R_SHUFFLE, false),
            repeatMode = resumePrefs.getInt(R_REPEAT, 0),
        )
    }.getOrNull()

    fun clearResume() {
        resumePrefs.edit().clear().apply()
    }

    // Kekerasan tiap lagu (dB dari YouTube) disimpan supaya lagu unduhan/cache yang diputar tanpa
    // mengambil stream lagi tetap ternormalisasi.
    private val loudnessPrefs = context.getSharedPreferences("loudness_db", Context.MODE_PRIVATE)

    fun loudness(videoId: String): Float? =
        if (loudnessPrefs.contains(videoId)) loudnessPrefs.getFloat(videoId, 0f) else null

    fun saveLoudness(videoId: String, db: Float) {
        val edit = loudnessPrefs.edit()
        // Batasi ukuran: kalau sudah sangat banyak, mulai dari awal (nilainya mudah didapat lagi).
        if (loudnessPrefs.all.size > MAX_LOUDNESS_ENTRIES) edit.clear()
        edit.putFloat(videoId, db).apply()
    }

    private companion object {
        const val QUALITY = "audio_quality"
        const val AUTOPLAY = "autoplay"
        const val SKIP_SILENCE = "skip_silence"
        const val NORMALIZE = "normalize_volume"
        const val MAX_LOUDNESS_ENTRIES = 3000
        const val RESUME_LAST = "resume_last"
        const val DATA_SAVER = "data_saver"
        const val MAX_RESUME_QUEUE = 300
        const val R_QUEUE = "queue"
        const val R_FROM = "from"
        const val R_INDEX = "index"
        const val R_POS = "pos"
        const val R_SHUFFLE = "shuffle"
        const val R_REPEAT = "repeat"
    }
}
