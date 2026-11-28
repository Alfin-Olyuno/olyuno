package app.tunes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tautan playlist lokal -> playlist di akun YouTube Music (id lokal -> id playlist YouTube Music).
 * Ada = playlist itu sudah pernah dikirim ke akun, jadi "Tambahkan" berikutnya cukup menambah lagu yang belum ada
 * dan tidak membuat playlist kembar. Disimpan terpisah dari database Room (tanpa migrasi) dan dikosongkan saat keluar akun.
 */
class PlaylistLinkStore(context: Context) {
    private val prefs = context.getSharedPreferences("playlist_links", Context.MODE_PRIVATE)

    private val _links = MutableStateFlow(load())
    val links: StateFlow<Map<Long, String>> = _links.asStateFlow()

    private fun load(): Map<Long, String> =
        prefs.all.mapNotNull { (k, v) ->
            val id = k.toLongOrNull()
            val yt = (v as? String)?.takeIf { it.isNotBlank() }
            if (id != null && yt != null) id to yt else null
        }.toMap()

    fun get(localId: Long): String? = _links.value[localId]

    @Synchronized
    fun set(localId: Long, remoteId: String) {
        prefs.edit().putString(localId.toString(), remoteId).apply()
        _links.value = _links.value + (localId to remoteId)
    }

    @Synchronized
    fun remove(localId: Long) {
        if (localId.toString() !in prefs.all) return
        prefs.edit().remove(localId.toString()).apply()
        _links.value = _links.value - localId
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _links.value = emptyMap()
    }
}
