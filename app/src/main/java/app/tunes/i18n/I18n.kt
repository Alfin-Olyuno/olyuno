package app.tunes.i18n

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Pilihan bahasa aplikasi. [System] = mengikuti bahasa perangkat. */
enum class AppLanguage { System, Indonesian, English }

/**
 * i18n aplikasi. Bahasa sumber = Indonesia: kode menulis teks Indonesia lewat [t], dan kalau bahasa efektif
 * English, teks dicari di [EnglishStrings]. Pilihan "Default sistem": perangkat berbahasa Indonesia -> Indonesia,
 * bahasa lain apa pun -> English.
 *
 * Bahasa efektif disimpan di state Compose, jadi setiap composable yang memanggil [t] otomatis tergambar ulang
 * begitu bahasa diganti (tanpa restart).
 */
object I18n {
    private const val PREFS = "language"
    private const val KEY = "choice"

    private var prefs: SharedPreferences? = null

    var choice by mutableStateOf(AppLanguage.System)
        private set

    /** Bahasa yang benar-benar dipakai: selalu Indonesian atau English. */
    var effective by mutableStateOf(AppLanguage.Indonesian)
        private set

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        choice = runCatching { AppLanguage.valueOf(p.getString(KEY, null) ?: "") }.getOrDefault(AppLanguage.System)
        refresh()
    }

    fun chooseLanguage(language: AppLanguage) {
        prefs?.edit()?.putString(KEY, language.name)?.apply()
        choice = language
        refresh()
    }

    /** Hitung ulang bahasa efektif (dipanggil juga saat aplikasi dibuka lagi, kalau bahasa perangkat berubah). */
    fun refresh() {
        effective = when (choice) {
            AppLanguage.Indonesian -> AppLanguage.Indonesian
            AppLanguage.English -> AppLanguage.English
            AppLanguage.System -> if (deviceIsIndonesian()) AppLanguage.Indonesian else AppLanguage.English
        }
    }

    /** Android lama melaporkan Indonesia sebagai "in", yang baru "id". */
    private fun deviceIsIndonesian(): Boolean {
        val lang = runCatching { Resources.getSystem().configuration.locales.get(0).language }
            .getOrDefault(Locale.getDefault().language)
        return lang == "in" || lang == "id"
    }

    fun translate(id: String): String =
        if (effective == AppLanguage.English) EnglishStrings[id] ?: id else id
}

/** Terjemahkan teks Indonesia [id] ke bahasa aplikasi saat ini. */
fun t(id: String): String = I18n.translate(id)

/** Seperti [t], lalu mengisi %s / %d dengan [args]. */
fun t(id: String, vararg args: Any?): String = String.format(Locale.ROOT, I18n.translate(id), *args)
