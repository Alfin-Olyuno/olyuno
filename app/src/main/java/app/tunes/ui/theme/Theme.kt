package app.tunes.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Display
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.tunes.OlyunoApp
import app.tunes.data.ColorMode
import app.tunes.data.UiStyle
import app.tunes.ui.GlassConfig
import app.tunes.ui.LocalGlass
import app.tunes.ui.LocalShimmerLevel
import app.tunes.ui.LocalSmoothAnim
import app.tunes.ui.LocalTopTint
import androidx.compose.runtime.CompositionLocalProvider

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC9BFFF),
    secondary = Color(0xFFCAC4D0),
    background = Color(0xFF121014),
    surface = Color(0xFF121014),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6C4CF1),
    secondary = Color(0xFF625B71),
)

/** Semua sudut dibulatkan lebih besar (menu, snackbar, kartu, dialog, dll). */
private val OlyunoShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val Ink = Color(0xFF1B1B1F)
private val Paper = Color(0xFFE6E6EA)

/**
 * Mengganti warna latar skema dengan [bg]; permukaan kartu / menu / sheet diturunkan dari [bg]
 * (digeser ke putih kalau latarnya gelap, ke hitam kalau terang) supaya tetap terbaca bertingkat.
 * Warna aksen (primary dll) tetap dari skema dasar.
 */
private fun ColorScheme.withBackground(
    bg: Color,
    onBg: Color,
    dark: Boolean,
    toward: Color = if (dark) Color.White else Color.Black,
    strength: Float = 1f,
): ColorScheme {
    fun step(f: Float) = lerp(bg, toward, f * strength)
    return copy(
        background = bg,
        onBackground = onBg,
        surface = bg,
        onSurface = onBg,
        surfaceVariant = step(0.10f),
        surfaceDim = bg,
        surfaceBright = step(0.16f),
        surfaceContainerLowest = bg,
        surfaceContainerLow = step(0.04f),
        surfaceContainer = step(0.07f),
        surfaceContainerHigh = step(0.10f),
        surfaceContainerHighest = step(0.13f),
    )
}


private fun contrast(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
}

/** Menggeser [c] ke putih (latar gelap) / hitam (latar terang) sampai kontrasnya ke [bg] cukup untuk komponen UI. */
private fun ensureContrast(c: Color, bg: Color, dark: Boolean, min: Float = 3.5f): Color {
    var out = c
    val toward = if (dark) Color.White else Color.Black
    var i = 0
    while (contrast(out, bg) < min && i < 12) {
        out = lerp(out, toward, 0.12f)
        i++
    }
    return out
}

/** Warna teks yang paling terbaca di atas [bg]. */
private fun readableOn(bg: Color): Color = if (contrast(bg, Paper) >= contrast(bg, Ink)) Paper else Ink

/** Aksen selaras latar: warna sama dengan latar (kalau berwarna), cukup terang / gelap supaya kontras. */
private fun accentFor(bg: Color, dark: Boolean, fallback: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(bg.toArgb(), hsv)
    val raw = if (hsv[1] < 0.15f) fallback
    else if (dark) Color.hsv(hsv[0], 0.45f, 0.95f)
    else Color.hsv(hsv[0], 0.85f, 0.42f)
    return ensureContrast(raw, bg, dark, min = 4.5f)
}

/**
 * Untuk mode Hitam dan Kustom: aksen, tombol, penanda tab terpilih, toggle, teks abu-abu, garis, dan
 * snackbar ikut diturunkan dari latar yang dipilih (bukan warna wallpaper), dengan kontras teks dijaga.
 */
private fun ColorScheme.tunedTo(bg: Color, onBg: Color, dark: Boolean, accentFallback: Color): ColorScheme {
    val accent = accentFor(bg, dark, accentFallback)
    val onAccent = readableOn(accent)
    val container = lerp(bg, accent, if (dark) 0.30f else 0.22f)
    val onContainer = readableOn(container)
    val secondary = ensureContrast(lerp(accent, onBg, 0.35f), bg, dark, min = 4.5f)
    return copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = secondary,
        onSecondary = readableOn(secondary),
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = secondary,
        onTertiary = readableOn(secondary),
        tertiaryContainer = container,
        onTertiaryContainer = onContainer,
        surfaceTint = accent,
        onSurfaceVariant = ensureContrast(lerp(onBg, bg, 0.22f), bg, dark, min = 6f),
        outline = ensureContrast(lerp(onBg, bg, 0.50f), bg, dark, min = 3f),
        outlineVariant = lerp(onBg, bg, 0.68f),
        inverseSurface = onBg,
        inverseOnSurface = bg,
        inversePrimary = accent,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun applyRefreshRate(window: Window, display: Display?, high: Boolean) {
    val attrs = window.attributes
    val best = if (high && display != null) {
        val cur = display.mode
        display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }
    } else null
    if (best != null) {
        attrs.preferredDisplayModeId = best.modeId
        attrs.preferredRefreshRate = best.refreshRate
    } else {
        attrs.preferredDisplayModeId = 0
        attrs.preferredRefreshRate = 0f
    }
    window.attributes = attrs
}

/** Skema terang (putih) untuk bottom sheet saat Warna tampilan = Ikuti sistem; selaras wallpaper (Material You). */
@Composable
fun systemLightScheme(): ColorScheme {
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(context) else LightScheme
    }
}

@Composable
fun OlyunoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val appearance = remember(context) { (context.applicationContext as OlyunoApp).container.appearance }
    val mode by appearance.mode.collectAsState()
    val custom by appearance.custom.collectAsState()
    val uiStyle by appearance.style.collectAsState()
    val smoothAnim by appearance.smoothAnim.collectAsState()
    val shimmer by appearance.shimmer.collectAsState()
    val appFont by appearance.font.collectAsState()
    val fontWeight by appearance.weight.collectAsState()
    val highRefresh by appearance.highRefresh.collectAsState()
    val typography = remember(appFont, fontWeight) { buildTypography(context, appFont, fontWeight) }
    val systemDark = isSystemInDarkTheme()

    val dark = when (mode) {
        ColorMode.System -> systemDark
        ColorMode.Light -> false
        ColorMode.Dark -> true
        // Terang / gelap ditentukan oleh teks mana yang lebih terbaca di atas warna kustom (bukan batas kecerahan tetap).
        ColorMode.Custom -> readableOn(Color(custom)) == Paper
    }
    val base = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    // Hitam & Kustom: skema dasarnya statis (bukan warna wallpaper), supaya tidak ada satu pun warna "nyasar"
    // dari wallpaper (aksen, error, container) yang tidak nyambung dengan latar pilihan pengguna.
    val fixedBase = if (dark) DarkScheme else LightScheme
    // Aksen netral bila latarnya tidak berwarna (hitam / abu-abu): putih keabuan di latar gelap, arang di latar terang.
    val neutralAccent = if (dark) Color(0xFFD7D7DE) else Color(0xFF3A3A42)
    val scheme = when (mode) {
        ColorMode.System -> base
        // Putih tidak polos: latar putih bernuansa tipis warna aksen, kartu / menu / sheet bertingkat
        // dengan semburat warna yang sama (bukan abu-abu datar).
        ColorMode.Light -> base.withBackground(
            bg = lerp(Color.White, base.primary, 0.035f),
            onBg = Ink,
            dark = false,
            toward = base.primary,
            strength = 0.75f,
        )
        ColorMode.Dark -> fixedBase.withBackground(Color.Black, Paper, dark = true, strength = 1.7f)
            .tunedTo(Color.Black, Paper, dark = true, accentFallback = neutralAccent)
        ColorMode.Custom -> {
            val bg = Color(custom)
            val onBg = if (dark) Paper else Ink
            fixedBase.withBackground(bg, onBg, dark = dark, strength = if (dark) 1.7f else 1.3f)
                .tunedTo(bg, onBg, dark, accentFallback = neutralAccent)
        }
    }

    // Refresh rate tinggi: kunci layar di refresh rate tertinggi yang didukung HP (resolusi tetap); mati = bawaan sistem.
    val refreshView = LocalView.current
    if (!refreshView.isInEditMode) {
        SideEffect {
            val window = refreshView.context.findActivity()?.window ?: return@SideEffect
            applyRefreshRate(window, refreshView.display, highRefresh)
        }
    }

    // Ikon status bar & bilah navigasi mengikuti terang/gelapnya latar yang dipilih (bukan sistem).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            // Latar jendela = warna latar yang dipilih: tanpa ini jendela memakai warna tema sistem (putih / abu-abu)
            // dan tampak di tepi, saat transisi halaman, dan saat keyboard muncul, jadi Hitam / Kustom terasa tidak nyambung.
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(scheme.background.toArgb()))
            // Bilah status & navigasi benar-benar transparan (tanpa scrim abu-abu bawaan navigasi 3 tombol).
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        }
    }

    // Gaya Kaca/Biasa untuk seluruh aplikasi; latar yang di-blur (backdrop) diisi oleh layar yang punya bilah.
    val glass = remember(uiStyle) { GlassConfig(enabled = uiStyle == UiStyle.Glass, backdrop = null) }
    // Ikuti sistem: setengah bagian atas layar bersemburat warna wallpaper (Material You) dan memudar ke warna latar
    // (putih di mode terang). Mode lain tetap satu warna polos.
    val topTint = if (mode == ColorMode.System) lerp(scheme.background, scheme.primaryContainer, 0.9f) else Color.Unspecified
    CompositionLocalProvider(
        LocalTopTint provides topTint,
        LocalGlass provides glass,
        LocalSmoothAnim provides smoothAnim,
        LocalShimmerLevel provides shimmer,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = OlyunoShapes, content = content)
    }
}
