package app.tunes.data

import android.content.Context
import app.tunes.innertube.AccountInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Menyimpan akun yang sedang masuk (nama, foto, cookie) di SharedPreferences. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(load())

    /** null = belum masuk. */
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    private fun load(): AccountInfo? {
        val name = prefs.getString("name", null) ?: return null
        return AccountInfo(name, prefs.getString("photo", null))
    }

    fun save(cookie: String, info: AccountInfo) {
        prefs.edit()
            .putString("cookie", cookie)
            .putString("name", info.name)
            .putString("photo", info.photoUrl)
            .apply()
        _account.value = info
    }

    fun cookie(): String? = prefs.getString("cookie", null)

    private val _autoSync = MutableStateFlow(prefs.getBoolean("autoSync", true))

    /** Otomatis sinkronisasi dengan akun (suka, riwayat putar). Mati = hanya lewat "Sinkronkan sekarang". */
    val autoSync: StateFlow<Boolean> = _autoSync.asStateFlow()

    fun setAutoSync(on: Boolean) {
        prefs.edit().putBoolean("autoSync", on).apply()
        _autoSync.value = on
    }

    /** Sudah pernah sinkron "Disukai" dengan akun ini? Sinkron pertama = gabung dua arah, setelahnya cermin akun. */
    var likesSynced: Boolean
        get() = prefs.getBoolean("likesSynced", false)
        set(v) {
            prefs.edit().putBoolean("likesSynced", v).apply()
        }

    // Perubahan suka yang belum sampai ke akun (offline / gagal), format "videoId:1" (suka) / "videoId:0" (batal).
    private fun pendingSet(): HashSet<String> = HashSet<String>(prefs.getStringSet("pending", null) ?: emptySet())

    @Synchronized
    fun pending(): Map<String, Boolean> = pendingSet().mapNotNull { s ->
        val i = s.lastIndexOf(':')
        if (i <= 0) null else s.substring(0, i) to (s.substring(i + 1) == "1")
    }.toMap()

    @Synchronized
    fun setPending(videoId: String, liked: Boolean) {
        val set = pendingSet()
        set.remove("$videoId:0")
        set.remove("$videoId:1")
        set.add("$videoId:${if (liked) 1 else 0}")
        prefs.edit().putStringSet("pending", set).apply()
    }

    /** Hapus hanya kalau isinya masih sama (pengguna bisa saja sudah menekan suka lagi). */
    @Synchronized
    fun clearPending(videoId: String, liked: Boolean) {
        val set = pendingSet()
        if (set.remove("$videoId:${if (liked) 1 else 0}")) {
            prefs.edit().putStringSet("pending", set).apply()
        }
    }

    fun clear() {
        // Pilihan sinkronisasi otomatis adalah pengaturan aplikasi, jadi tidak ikut terhapus saat keluar.
        prefs.edit().clear().putBoolean("autoSync", _autoSync.value).apply()
        _account.value = null
    }
}
