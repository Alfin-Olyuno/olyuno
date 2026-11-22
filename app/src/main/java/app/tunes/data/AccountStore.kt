package app.tunes.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.tunes.innertube.AccountInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Enkripsi cookie dengan kunci AES-GCM yang disimpan di Android Keystore (kunci tidak bisa diekspor dari
 * perangkat). Hasil: Base64(IV 12 byte + ciphertext). Mengembalikan null kalau gagal, jangan melempar.
 */
private object CookieCipher {
    private const val ALIAS = "olyuno_cookie_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String? = try {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    fun decrypt(encoded: String): String? = try {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) {
            null
        } else {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }
}

/** Menyimpan akun yang sedang masuk (nama, foto, cookie) di SharedPreferences; cookie dienkripsi lewat Keystore. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(load())

    /** null = belum masuk. */
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    private fun load(): AccountInfo? {
        val name = prefs.getString("name", null) ?: return null
        return AccountInfo(name, prefs.getString("photo", null))
    }

    @Synchronized
    fun save(cookie: String, info: AccountInfo) {
        val enc = CookieCipher.encrypt(cookie)
        val e = prefs.edit()
        // Kalau Keystore gagal (jarang), tetap simpan agar login tidak rusak; akan dienkripsi saat dibaca berikutnya.
        if (enc != null) e.putString(KEY_COOKIE_ENC, enc).remove(KEY_COOKIE) else e.putString(KEY_COOKIE, cookie).remove(KEY_COOKIE_ENC)
        e.putString("name", info.name)
            .putString("photo", info.photoUrl)
            .apply()
        _account.value = info
    }

    /**
     * Cookie login. Cookie lama yang tersimpan polos (versi sebelumnya) dimigrasikan ke bentuk terenkripsi.
     * Kalau cookie terenkripsi tidak bisa dibuka (kunci Keystore hilang), sesi dianggap berakhir: akun dikeluarkan.
     */
    @Synchronized
    fun cookie(): String? {
        prefs.getString(KEY_COOKIE_ENC, null)?.let { enc ->
            val plain = CookieCipher.decrypt(enc)
            if (plain == null) clear()
            return plain
        }
        val plain = prefs.getString(KEY_COOKIE, null) ?: return null
        CookieCipher.encrypt(plain)?.let { enc ->
            prefs.edit().putString(KEY_COOKIE_ENC, enc).remove(KEY_COOKIE).apply()
        }
        return plain
    }

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

    @Synchronized
    fun clear() {
        // Pilihan sinkronisasi otomatis adalah pengaturan aplikasi, jadi tidak ikut terhapus saat keluar.
        prefs.edit().clear().putBoolean("autoSync", _autoSync.value).apply()
        _account.value = null
    }

    private companion object {
        const val KEY_COOKIE = "cookie"
        const val KEY_COOKIE_ENC = "cookie_enc"
    }
}
