package app.tunes.ui

import androidx.compose.ui.graphics.Color

/**
 * Pilihan warna panel di sekitar teks lirik (dipakai saat "Background tampilan lirik" mati).
 * Semuanya cukup gelap supaya teks putih tetap terbaca. Urutan: merah → jingga → kuning → hijau → biru → ungu → pink → netral.
 */
internal val LyricsPanelPresets: List<Int> = listOf(
    0xFF8A2A2A, 0xFFA33B2E, 0xFFB35A1F, 0xFF9A6A12, 0xFF7A7A1F, 0xFF4F7A1F,
    0xFF1F6B3A, 0xFF0F6B5C, 0xFF0F6B7A, 0xFF1F4E79, 0xFF2B3F9E, 0xFF4B3FA6,
    0xFF6A3FA6, 0xFF8A3FA0, 0xFFA23A6B, 0xFF7A2B4A, 0xFF4A3A2E, 0xFF2B2B2F,
).map { it.toInt() }

/** Pilihan otomatis: preset yang paling dekat dengan warna utama cover lagu (jarak RGB terkecil). */
internal fun autoLyricsPanel(cover: Color): Color {
    fun dist(c: Int): Float {
        val dr = cover.red - ((c shr 16) and 0xFF) / 255f
        val dg = cover.green - ((c shr 8) and 0xFF) / 255f
        val db = cover.blue - (c and 0xFF) / 255f
        return dr * dr + dg * dg + db * db
    }
    return Color(LyricsPanelPresets.minBy { dist(it) })
}
