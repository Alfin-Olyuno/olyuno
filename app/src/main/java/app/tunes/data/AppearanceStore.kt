package app.tunes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pilihan warna tampilan aplikasi. [System] = ikut pengaturan HP (belum memilih apa pun). */
enum class ColorMode(val label: String) {
    System("Ikuti sistem"),
    Light("Putih"),
    Dark("Hitam"),
    Custom("Kustom"),
}

/** Gaya tampilan: Biasa (solid, seperti sebelumnya) atau Kaca (bilah & kontrol transparan dengan blur). */
enum class UiStyle(val label: String, val description: String) {
    Normal("Biasa", "Bilah navigasi dan pemutar mini berlatar solid."),
    Glass("Kaca", "Konten tampak samar di balik bilah navigasi, seperti kaca buram."),
}

/** Kekuatan kilau skeleton saat memuat: Mati (diam), Redup (seperti sebelumnya), Terang (lebih mencolok). */
enum class ShimmerLevel(val label: String) {
    Off("Mati"),
    Soft("Redup"),
    Strong("Terang"),
}

/** Pilihan font: Sistem (bawaan HP) atau iOS (gaya SF Pro). */
enum class AppFont(val label: String) {
    System("Sistem"),
    Ios("iOS"),
}

/** Menyimpan pilihan Warna tampilan: mode (Putih / Hitam / Kustom) dan warna kustomnya. */
class AppearanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readMode())
    val mode: StateFlow<ColorMode> = _mode.asStateFlow()

    /** Warna kustom (ARGB). Dipakai sebagai warna latar kalau mode = Kustom. */
    private val _custom = MutableStateFlow(prefs.getInt(CUSTOM, DEFAULT_CUSTOM))
    val custom: StateFlow<Int> = _custom.asStateFlow()

    /** Gaya tampilan; bawaannya Kaca. */
    private val _style = MutableStateFlow(
        runCatching { UiStyle.valueOf(prefs.getString(STYLE, null) ?: "") }.getOrDefault(UiStyle.Glass),
    )
    val style: StateFlow<UiStyle> = _style.asStateFlow()

    /** Animasi smooth bilah navigasi & pemutar mini; bawaannya nyala (seperti tampilan sebelumnya). */
    private val _smoothAnim = MutableStateFlow(prefs.getBoolean(SMOOTH_ANIM, true))
    val smoothAnim: StateFlow<Boolean> = _smoothAnim.asStateFlow()

    fun setSmoothAnim(enabled: Boolean) {
        prefs.edit().putBoolean(SMOOTH_ANIM, enabled).apply()
        _smoothAnim.value = enabled
    }

    /** Refresh rate tinggi: layar dikunci di refresh rate tertinggi HP; mati = atur otomatis oleh sistem. */
    private val _highRefresh = MutableStateFlow(prefs.getBoolean(HIGH_REFRESH, true))
    val highRefresh: StateFlow<Boolean> = _highRefresh.asStateFlow()

    fun setHighRefresh(enabled: Boolean) {
        prefs.edit().putBoolean(HIGH_REFRESH, enabled).apply()
        _highRefresh.value = enabled
    }

    /** Latar layar pemutar: nyala = gambar thumbnail lagu dikaburkan; mati = hanya gradien beberapa warna. */
    private val _bgThumbnail = MutableStateFlow(prefs.getBoolean(BG_THUMBNAIL, true))
    val bgThumbnail: StateFlow<Boolean> = _bgThumbnail.asStateFlow()

    fun setBgThumbnail(enabled: Boolean) {
        prefs.edit().putBoolean(BG_THUMBNAIL, enabled).apply()
        _bgThumbnail.value = enabled
    }

    /** Warna latar layar pemutar (ARGB) saat Background thumbnail mati; null = otomatis dari warna cover lagu. */
    private val _playerBg = MutableStateFlow<Int?>(if (prefs.contains(PLAYER_BG)) prefs.getInt(PLAYER_BG, 0) else null)
    val playerBg: StateFlow<Int?> = _playerBg.asStateFlow()

    /** [persist] false = hanya pratinjau langsung (saat slider digeser); [argb] null = kembali ke otomatis. */
    fun setPlayerBg(argb: Int?, persist: Boolean = true) {
        if (persist) {
            prefs.edit().apply { if (argb == null) remove(PLAYER_BG) else putInt(PLAYER_BG, argb) }.apply()
        }
        _playerBg.value = argb
    }

    /** Latar halaman lirik: nyala = thumbnail lagu seperti biasa; mati = panel warna di sekitar teks lirik. */
    private val _lyricsBg = MutableStateFlow(prefs.getBoolean(LYRICS_BG, true))
    val lyricsBg: StateFlow<Boolean> = _lyricsBg.asStateFlow()

    fun setLyricsBg(enabled: Boolean) {
        prefs.edit().putBoolean(LYRICS_BG, enabled).apply()
        _lyricsBg.value = enabled
    }

    /** Warna panel lirik: false = Otomatis (dipilih yang cocok dengan cover), true = Kustom. Bawaannya Otomatis. */
    private val _lyricsPanelCustom = MutableStateFlow(prefs.getBoolean(LYRICS_PANEL_CUSTOM, prefs.contains(LYRICS_PANEL)))
    val lyricsPanelCustom: StateFlow<Boolean> = _lyricsPanelCustom.asStateFlow()

    fun setLyricsPanelCustom(custom: Boolean) {
        prefs.edit().putBoolean(LYRICS_PANEL_CUSTOM, custom).apply()
        _lyricsPanelCustom.value = custom
    }

    /** Warna kustom panel lirik (ARGB), dipakai kalau pilihan = Kustom. */
    private val _lyricsPanel = MutableStateFlow(prefs.getInt(LYRICS_PANEL, DEFAULT_LYRICS_PANEL))
    val lyricsPanel: StateFlow<Int> = _lyricsPanel.asStateFlow()

    /** [persist] false = hanya pratinjau langsung (saat slider digeser). */
    fun setLyricsPanel(argb: Int, persist: Boolean = true) {
        if (persist) prefs.edit().putInt(LYRICS_PANEL, argb).apply()
        _lyricsPanel.value = argb
    }

    /** Atur ulang: warna kustom kembali ke bawaan dan pilihan kembali ke Otomatis. */
    fun resetLyricsPanel() {
        prefs.edit().remove(LYRICS_PANEL).putBoolean(LYRICS_PANEL_CUSTOM, false).apply()
        _lyricsPanel.value = DEFAULT_LYRICS_PANEL
        _lyricsPanelCustom.value = false
    }

    /** Warna teks lirik: System = seperti bawaan (putih), Light = putih, Dark = hitam, Custom = [lyricsTextCustom]. */
    private val _lyricsTextMode = MutableStateFlow(
        runCatching { ColorMode.valueOf(prefs.getString(LYRICS_TEXT_MODE, null) ?: "") }.getOrDefault(ColorMode.System),
    )
    val lyricsTextMode: StateFlow<ColorMode> = _lyricsTextMode.asStateFlow()

    fun setLyricsTextMode(mode: ColorMode) {
        prefs.edit().putString(LYRICS_TEXT_MODE, mode.name).apply()
        _lyricsTextMode.value = mode
    }

    /** Warna kustom teks lirik (ARGB), dipakai kalau mode = Kustom. */
    private val _lyricsTextCustom = MutableStateFlow(prefs.getInt(LYRICS_TEXT_CUSTOM, DEFAULT_LYRICS_TEXT))
    val lyricsTextCustom: StateFlow<Int> = _lyricsTextCustom.asStateFlow()

    /** [persist] false = hanya pratinjau langsung (saat slider digeser). */
    fun setLyricsTextCustom(argb: Int, persist: Boolean = true) {
        if (persist) prefs.edit().putInt(LYRICS_TEXT_CUSTOM, argb).apply()
        _lyricsTextCustom.value = argb
    }

    private val _shimmer = MutableStateFlow(
        runCatching { ShimmerLevel.valueOf(prefs.getString(SHIMMER, null) ?: "") }.getOrDefault(ShimmerLevel.Soft),
    )
    val shimmer: StateFlow<ShimmerLevel> = _shimmer.asStateFlow()

    fun setShimmer(level: ShimmerLevel) {
        prefs.edit().putString(SHIMMER, level.name).apply()
        _shimmer.value = level
    }

    private val _font = MutableStateFlow(
        runCatching { AppFont.valueOf(prefs.getString(FONT, null) ?: "") }.getOrDefault(AppFont.System),
    )
    val font: StateFlow<AppFont> = _font.asStateFlow()

    fun setFont(font: AppFont) {
        prefs.edit().putString(FONT, font.name).apply()
        _font.value = font
    }

    /** Ketebalan font: 0..8, tengah (4) = bawaan. */
    private val _weight = MutableStateFlow(prefs.getInt(WEIGHT, DEFAULT_WEIGHT).coerceIn(0, 8))
    val weight: StateFlow<Int> = _weight.asStateFlow()

    fun setWeight(step: Int) {
        val v = step.coerceIn(0, 8)
        prefs.edit().putInt(WEIGHT, v).apply()
        _weight.value = v
    }

    fun resetFont() {
        setFont(AppFont.System)
        setWeight(DEFAULT_WEIGHT)
    }

    fun setStyle(style: UiStyle) {
        prefs.edit().putString(STYLE, style.name).apply()
        _style.value = style
    }

    /**
     * Penghemat baterai; bawaannya mati. Nyala = refresh rate tinggi, animasi smooth, gaya Kaca, kilau skeleton,
     * dan Background thumbnail dimatikan. Mati = kelima pengaturan itu kembali ke nilai bawaan.
     */
    private val _batterySaver = MutableStateFlow(prefs.getBoolean(BATTERY_SAVER, false))
    val batterySaver: StateFlow<Boolean> = _batterySaver.asStateFlow()

    fun setBatterySaver(enabled: Boolean) {
        prefs.edit().putBoolean(BATTERY_SAVER, enabled).apply()
        _batterySaver.value = enabled
        if (enabled) {
            setHighRefresh(false)
            setSmoothAnim(false)
            setStyle(UiStyle.Normal)
            setShimmer(ShimmerLevel.Off)
            setBgThumbnail(false)
        } else {
            setHighRefresh(true)
            setSmoothAnim(true)
            setStyle(UiStyle.Glass)
            setShimmer(ShimmerLevel.Soft)
            setBgThumbnail(true)
        }
    }

    private fun readMode(): ColorMode =
        runCatching { ColorMode.valueOf(prefs.getString(MODE, null) ?: "") }.getOrDefault(ColorMode.System)

    fun setMode(mode: ColorMode) {
        prefs.edit().putString(MODE, mode.name).apply()
        _mode.value = mode
    }

    /** [persist] false = hanya pratinjau langsung (saat slider digeser), true = ikut disimpan. */
    fun setCustom(argb: Int, persist: Boolean = true) {
        if (persist) prefs.edit().putInt(CUSTOM, argb).apply()
        _custom.value = argb
    }

    private companion object {
        const val MODE = "mode"
        const val CUSTOM = "custom"
        const val STYLE = "style"
        const val SMOOTH_ANIM = "smooth_anim"
        const val SHIMMER = "shimmer"
        const val BG_THUMBNAIL = "bg_thumbnail"
        const val HIGH_REFRESH = "high_refresh"
        const val BATTERY_SAVER = "battery_saver"
        const val PLAYER_BG = "player_bg"
        const val LYRICS_BG = "lyrics_bg"
        const val LYRICS_PANEL = "lyrics_panel"
        const val LYRICS_PANEL_CUSTOM = "lyrics_panel_custom"
        val DEFAULT_LYRICS_PANEL = 0xFF1F4E79.toInt()
        const val LYRICS_TEXT_MODE = "lyrics_text_mode"
        const val LYRICS_TEXT_CUSTOM = "lyrics_text_custom"
        val DEFAULT_LYRICS_TEXT = 0xFFFFE082.toInt()
        const val FONT = "font"
        const val WEIGHT = "font_weight"
        const val DEFAULT_WEIGHT = 4
        val DEFAULT_CUSTOM = 0xFF1F2A44.toInt()
    }
}
