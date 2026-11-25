package app.tunes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pilihan model untuk Olyuno AI. [label] = nama tampilan di aplikasi, [defaultId] = ID model Gemini yang dipanggil
 * (bisa diganti pengguna di pengaturan karena daftar model gratis Google sering berubah).
 */
enum class AiModel(val label: String, val defaultId: String) {
    Olyuno35("Olyuno 3.5", "gemini-3.5-flash"),
    Olyuno38("Olyuno 3.8", "gemini-3.8-flash"),
    Olyuno4("Olyuno 4", "gemini-4-hydrogen"),
}

/**
 * Pengaturan Olyuno AI: ID perangkat acak (untuk pembatas laju di server), model terpilih, dan ID model.
 * Kunci API Gemini TIDAK ada di aplikasi: disimpan di server Olyuno dan dikelola lewat APK admin.
 * Sengaja TIDAK diekspor di cadangan (Backup.kt) dan tidak pernah dicatat di log.
 */
class AiPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

    /** ID acak per pemasangan; hanya dipakai server untuk membatasi laju, tidak terkait identitas pengguna. */
    val deviceId: String = (prefs.getString(DEVICE, null)?.takeIf { it.length in 16..64 } ?: newDeviceId().also {
        prefs.edit().putString(DEVICE, it).apply()
    }).also {
        // Bersihkan sisa kunci API dari versi lama (kalau ada).
        if (prefs.contains(KEY)) prefs.edit().remove(KEY).apply()
    }

    private val _model = MutableStateFlow(
        runCatching { AiModel.valueOf(prefs.getString(MODEL, null) ?: "") }.getOrDefault(AiModel.Olyuno38),
    )
    val model: StateFlow<AiModel> = _model.asStateFlow()

    private val _modelIds = MutableStateFlow(AiModel.entries.associateWith { m -> prefs.getString(ID_PREFIX + m.name, null)?.takeIf { it.isNotBlank() } ?: m.defaultId })
    val modelIds: StateFlow<Map<AiModel, String>> = _modelIds.asStateFlow()

    fun idOf(model: AiModel): String = _modelIds.value[model] ?: model.defaultId

    fun setModel(model: AiModel) {
        prefs.edit().putString(MODEL, model.name).apply()
        _model.value = model
    }

    fun setModelId(model: AiModel, id: String) {
        val v = id.trim().ifEmpty { model.defaultId }
        prefs.edit().putString(ID_PREFIX + model.name, v).apply()
        _modelIds.value = _modelIds.value + (model to v)
    }

    private companion object {
        const val KEY = "gemini_key"
        const val DEVICE = "device_id"
        fun newDeviceId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
        const val MODEL = "model"
        const val ID_PREFIX = "model_id_"
    }
}
