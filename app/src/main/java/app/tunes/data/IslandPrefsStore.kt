package app.tunes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Gaya tampilan Dynamic Island: iPhone (bawaan, gradien warna cover), Kaca (tembus + blur), Biasa (solid). */
enum class IslandStyle(val label: String, val description: String) {
    IPhone("iPhone", "Kartu hitam dengan warna dari cover lagu, seperti di iPhone."),
    Glass("Kaca", "Kartu tembus pandang dengan efek blur."),
    Normal("Biasa", "Kartu polos yang mengikuti warna aplikasi."),
}

/** Warna kartu Dynamic Island: Default (ikut gaya, hitam seperti iPhone), Putih (teks hitam), atau Kustom. */
enum class IslandColor(val label: String, val description: String) {
    Default("Default", "Hitam, seperti di iPhone"),
    White("Putih", "Kartu putih dengan teks hitam"),
    Custom("Kustom", "Pilih warna sendiri"),
}

/** Bentuk Dynamic Island kecil: Persegi membulat (bawaan) atau Bulat (hanya logo). Kartu besar selalu sama. */
enum class IslandShape(val label: String, val description: String) {
    Rounded("Persegi membulat", "Persegi panjang dengan sudut membulat (bawaan)."),
    Circle("Bulat", "Bulat, hanya menampilkan logo lagu. Kartu besar tidak berubah."),
}

/** Gaya tombol kontrol di Dynamic Island: Terisi (bawaan, tombol putar berlatar bulat) atau Garis tepi (hanya garis ikon). */
enum class IslandButtonStyle(val label: String, val description: String) {
    Filled("Terisi", "Ikon padat, tombol putar berlatar bulat (bawaan)."),
    Outline("Garis tepi", "Ikon hanya berupa garis tepi, tanpa latar bulat."),
}

/** Pengaturan Dynamic Island. Nyala hanya bisa kalau izin "Tampilkan di atas aplikasi lain" sudah diberikan. */
class IslandPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("island_prefs", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(ENABLED, on).apply()
        _enabled.value = on
    }

    private val _style = MutableStateFlow(
        runCatching { IslandStyle.valueOf(prefs.getString(STYLE, null) ?: "") }.getOrDefault(IslandStyle.IPhone),
    )
    val style: StateFlow<IslandStyle> = _style.asStateFlow()

    fun setStyle(style: IslandStyle) {
        prefs.edit().putString(STYLE, style.name).apply()
        _style.value = style
    }

    private val _color = MutableStateFlow(
        runCatching { IslandColor.valueOf(prefs.getString(COLOR, null) ?: "") }.getOrDefault(IslandColor.Default),
    )
    val color: StateFlow<IslandColor> = _color.asStateFlow()

    fun setColor(c: IslandColor) {
        prefs.edit().putString(COLOR, c.name).apply()
        _color.value = c
    }

    /** Warna kartu (ARGB) saat [color] = Kustom. */
    private val _custom = MutableStateFlow(prefs.getInt(CUSTOM, DEFAULT_CUSTOM))
    val custom: StateFlow<Int> = _custom.asStateFlow()

    fun setCustom(argb: Int, persist: Boolean = true) {
        if (persist) prefs.edit().putInt(CUSTOM, argb).apply()
        _custom.value = argb
    }

    private val _shape = MutableStateFlow(
        runCatching { IslandShape.valueOf(prefs.getString(SHAPE, null) ?: "") }.getOrDefault(IslandShape.Rounded),
    )
    val shape: StateFlow<IslandShape> = _shape.asStateFlow()

    fun setShape(shape: IslandShape) {
        prefs.edit().putString(SHAPE, shape.name).apply()
        _shape.value = shape
    }

    /** Kelengkungan sudut kartu: 0f..1f, 1f (100%) = sudut bawaan gaya. */
    private val _corner = MutableStateFlow(prefs.getFloat(CORNER, 1f).coerceIn(0f, 1f))
    val corner: StateFlow<Float> = _corner.asStateFlow()

    /** [persist] false = hanya pratinjau langsung (saat slider digeser). */
    fun setCorner(v: Float, persist: Boolean = true) {
        val value = v.coerceIn(0f, 1f)
        if (persist) prefs.edit().putFloat(CORNER, value).apply()
        _corner.value = value
    }

    /** Opasitas kartu: [MIN_OPACITY]..1f, 1f (100%) = tidak tembus. */
    private val _opacity = MutableStateFlow(prefs.getFloat(OPACITY, 1f).coerceIn(MIN_OPACITY, 1f))
    val opacity: StateFlow<Float> = _opacity.asStateFlow()

    fun setOpacity(v: Float, persist: Boolean = true) {
        val value = v.coerceIn(MIN_OPACITY, 1f)
        if (persist) prefs.edit().putFloat(OPACITY, value).apply()
        _opacity.value = value
    }

    /** Lebar kartu besar dalam dp ([MIN_CARD_WIDTH]..[MAX_CARD_WIDTH]); seluruh kartu ikut diskalakan. Bawaan = terbesar. */
    private val _cardWidth = MutableStateFlow(prefs.getFloat(CARD_WIDTH, MAX_CARD_WIDTH).coerceIn(MIN_CARD_WIDTH, MAX_CARD_WIDTH))
    val cardWidth: StateFlow<Float> = _cardWidth.asStateFlow()

    fun setCardWidth(v: Float, persist: Boolean = true) {
        val value = v.coerceIn(MIN_CARD_WIDTH, MAX_CARD_WIDTH)
        if (persist) prefs.edit().putFloat(CARD_WIDTH, value).apply()
        _cardWidth.value = value
    }

    /** Skala ukuran teks island: [MIN_TEXT_SCALE]..[MAX_TEXT_SCALE], 1f (100%) = ukuran bawaan. */
    private val _textScale = MutableStateFlow(prefs.getFloat(TEXT_SCALE, 1f).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE))
    val textScale: StateFlow<Float> = _textScale.asStateFlow()

    fun setTextScale(v: Float, persist: Boolean = true) {
        val value = v.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        if (persist) prefs.edit().putFloat(TEXT_SCALE, value).apply()
        _textScale.value = value
    }

    private val _buttonStyle = MutableStateFlow(
        runCatching { IslandButtonStyle.valueOf(prefs.getString(BUTTON_STYLE, null) ?: "") }.getOrDefault(IslandButtonStyle.Filled),
    )
    val buttonStyle: StateFlow<IslandButtonStyle> = _buttonStyle.asStateFlow()

    fun setButtonStyle(style: IslandButtonStyle) {
        prefs.edit().putString(BUTTON_STYLE, style.name).apply()
        _buttonStyle.value = style
    }

    /** Tampilkan tombol kontrol (sebelumnya/putar/berikutnya) di kartu besar. Bawaan: ya. */
    private val _showButtons = MutableStateFlow(prefs.getBoolean(SHOW_BUTTONS, true))
    val showButtons: StateFlow<Boolean> = _showButtons.asStateFlow()

    fun setShowButtons(on: Boolean) {
        prefs.edit().putBoolean(SHOW_BUTTONS, on).apply()
        _showButtons.value = on
    }

    /** Tampilkan nama artis di kartu besar. Bawaan: ya. */
    private val _showArtist = MutableStateFlow(prefs.getBoolean(SHOW_ARTIST, true))
    val showArtist: StateFlow<Boolean> = _showArtist.asStateFlow()

    fun setShowArtist(on: Boolean) {
        prefs.edit().putBoolean(SHOW_ARTIST, on).apply()
        _showArtist.value = on
    }

    /** Tampilkan nama lagu di kartu kecil dan kartu besar. Bawaan: ya. */
    private val _showTitle = MutableStateFlow(prefs.getBoolean(SHOW_TITLE, true))
    val showTitle: StateFlow<Boolean> = _showTitle.asStateFlow()

    fun setShowTitle(on: Boolean) {
        prefs.edit().putBoolean(SHOW_TITLE, on).apply()
        _showTitle.value = on
    }

    /** Kartu hanya tampil saat lagu sedang diputar (disembunyikan saat dijeda). Bawaan: mati. */
    private val _onlyWhenPlaying = MutableStateFlow(prefs.getBoolean(ONLY_WHEN_PLAYING, false))
    val onlyWhenPlaying: StateFlow<Boolean> = _onlyWhenPlaying.asStateFlow()

    fun setOnlyWhenPlaying(on: Boolean) {
        prefs.edit().putBoolean(ONLY_WHEN_PLAYING, on).apply()
        _onlyWhenPlaying.value = on
    }

    /** Detik sebelum kartu besar mengecil otomatis: [MIN_COLLAPSE_SEC]..[MAX_COLLAPSE_SEC], bawaan 5. */
    private val _collapseSec = MutableStateFlow(
        prefs.getFloat(COLLAPSE_SEC, DEFAULT_COLLAPSE_SEC).roundToInt().toFloat().coerceIn(MIN_COLLAPSE_SEC, MAX_COLLAPSE_SEC),
    )
    val collapseSec: StateFlow<Float> = _collapseSec.asStateFlow()

    /** Dibulatkan ke detik penuh. [persist] false = hanya pratinjau nilai saat slider digeser. */
    fun setCollapseSec(v: Float, persist: Boolean = true) {
        val value = v.roundToInt().toFloat().coerceIn(MIN_COLLAPSE_SEC, MAX_COLLAPSE_SEC)
        if (persist) prefs.edit().putFloat(COLLAPSE_SEC, value).apply()
        _collapseSec.value = value
    }

    /** Detik setelah dijeda sampai kartu disembunyikan (0..[MAX_PAUSE_HIDE_SEC], langkah 5), bawaan 30. Hanya berlaku saat "hanya saat diputar" nyala. */
    private val _pauseHideSec = MutableStateFlow(
        (prefs.getFloat(PAUSE_HIDE_SEC, DEFAULT_PAUSE_HIDE_SEC) / 5f).roundToInt().times(5f).coerceIn(0f, MAX_PAUSE_HIDE_SEC),
    )
    val pauseHideSec: StateFlow<Float> = _pauseHideSec.asStateFlow()

    fun setPauseHideSec(v: Float, persist: Boolean = true) {
        val value = (v / 5f).roundToInt().times(5f).coerceIn(0f, MAX_PAUSE_HIDE_SEC)
        if (persist) prefs.edit().putFloat(PAUSE_HIDE_SEC, value).apply()
        _pauseHideSec.value = value
    }

    companion object {
        const val MAX_PAUSE_HIDE_SEC = 300f
        const val DEFAULT_PAUSE_HIDE_SEC = 30f
        private const val PAUSE_HIDE_SEC = "pause_hide_sec"
        const val MIN_COLLAPSE_SEC = 1f
        const val MAX_COLLAPSE_SEC = 15f
        const val DEFAULT_COLLAPSE_SEC = 5f
        private const val COLLAPSE_SEC = "collapse_sec"
        private const val ONLY_WHEN_PLAYING = "only_when_playing"
        private const val SHOW_BUTTONS = "show_buttons"
        private const val SHOW_ARTIST = "show_artist"
        private const val SHOW_TITLE = "show_title"
        private const val BUTTON_STYLE = "button_style"
        const val MIN_TEXT_SCALE = 0.8f
        const val MAX_TEXT_SCALE = 1.3f
        private const val TEXT_SCALE = "text_scale"
        const val MIN_CARD_WIDTH = 240f
        const val MAX_CARD_WIDTH = 400f
        private const val CARD_WIDTH = "card_width"
        /** Batas bawah opasitas supaya kartu tidak hilang sama sekali. */
        const val MIN_OPACITY = 0.3f
        private const val COLOR = "color"
        private const val CORNER = "corner"
        private const val CUSTOM = "custom_color"
        private const val DEFAULT_CUSTOM = 0xFF1F2A44.toInt()
        private const val ENABLED = "enabled"
        private const val OPACITY = "opacity"
        private const val SHAPE = "shape"
        private const val STYLE = "style"
    }
}
