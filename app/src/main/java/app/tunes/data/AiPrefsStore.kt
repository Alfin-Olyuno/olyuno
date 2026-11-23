package app.tunes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pilihan model untuk Tanya AI. [label] = nama tampilan di aplikasi, [defaultId] = ID model Gemini yang dipanggil
 * (bisa diganti pengguna di pengaturan karena daftar model gratis Google sering berubah).
 */
enum class AiModel(val label: String, val defaultId: String) {
    Olyuno38("Olyuno 3.8", "gemini-3.8-flash"),
    Olyuno4("Olyuno 4", "gemini-4-hydrogen"),
}

/**
 * Pengaturan Tanya AI: kunci API Gemini milik pengguna, model terpilih, dan ID model.
 * Sengaja TIDAK diekspor di cadangan (Backup.kt) dan tidak pernah dicatat di log.
 */
class AiPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY, "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(
        runCatching { AiModel.valueOf(prefs.getString(MODEL, null) ?: "") }.getOrDefault(AiModel.Olyuno38),
    )
    val model: StateFlow<AiModel> = _model.asStateFlow()

    private val _modelIds = MutableStateFlow(AiModel.entries.associateWith { m -> prefs.getString(ID_PREFIX + m.name, null)?.takeIf { it.isNotBlank() } ?: m.defaultId })
    val modelIds: StateFlow<Map<AiModel, String>> = _modelIds.asStateFlow()

    fun idOf(model: AiModel): String = _modelIds.value[model] ?: model.defaultId

    fun setApiKey(key: String) {
        val k = key.trim()
        prefs.edit().putString(KEY, k).apply()
        _apiKey.value = k
    }

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
        const val MODEL = "model"
        const val ID_PREFIX = "model_id_"
    }
}
