package app.tunes.ui.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.tunes.data.AppFont

/** Font gaya iOS (variable font) di app/src/main/assets/fonts/ios_font.ttf; diunduh otomatis saat build (Inter), atau ganti dengan SF Pro. */
private const val IOS_FONT_ASSET = "ios_font.ttf"

/** Jarak ketebalan per langkah slider (langkah 4 = bawaan, jadi rentangnya -300..+300). */
private const val WEIGHT_PER_STEP = 75

private fun iosFamily(context: Context): FontFamily? {
    val has = runCatching { context.assets.list("fonts")?.contains(IOS_FONT_ASSET) == true }.getOrDefault(false)
    if (!has) return null
    return FontFamily(
        (1..9).map { w ->
            Font(
                path = "fonts/$IOS_FONT_ASSET",
                assetManager = context.assets,
                weight = FontWeight(w * 100),
                variationSettings = FontVariation.Settings(FontVariation.weight(w * 100)),
            )
        },
    )
}

/**
 * Tipografi seluruh aplikasi dari pilihan font dan ketebalan (0..8, 4 = bawaan).
 * Sistem = font bawaan HP. iOS = font di assets (Inter / SF Pro) kalau berkasnya ada; kalau tidak ada, font sans bawaan HP
 * dengan jarak huruf sedikit dirapatkan seperti iOS.
 */
fun buildTypography(context: Context, font: AppFont, weightStep: Int): Typography {
    val delta = (weightStep - 4) * WEIGHT_PER_STEP
    val family: FontFamily? = when (font) {
        AppFont.System -> null
        AppFont.Ios -> iosFamily(context) ?: FontFamily.SansSerif
    }
    val tighten = font == AppFont.Ios && iosFamily(context) == null

    fun TextStyle.tweak(): TextStyle {
        val w = ((fontWeight ?: FontWeight.Normal).weight + delta).coerceIn(100, 900)
        val spacing: TextUnit = if (tighten) (letterSpacing.value - 0.2f).sp else letterSpacing
        return copy(fontFamily = family ?: fontFamily, fontWeight = FontWeight(w), letterSpacing = spacing)
    }

    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.tweak(), displayMedium = d.displayMedium.tweak(), displaySmall = d.displaySmall.tweak(),
        headlineLarge = d.headlineLarge.tweak(), headlineMedium = d.headlineMedium.tweak(), headlineSmall = d.headlineSmall.tweak(),
        titleLarge = d.titleLarge.tweak(), titleMedium = d.titleMedium.tweak(), titleSmall = d.titleSmall.tweak(),
        bodyLarge = d.bodyLarge.tweak(), bodyMedium = d.bodyMedium.tweak(), bodySmall = d.bodySmall.tweak(),
        labelLarge = d.labelLarge.tweak(), labelMedium = d.labelMedium.tweak(), labelSmall = d.labelSmall.tweak(),
    )
}
