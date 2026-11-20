package app.tunes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Ikon tombol Lirik: gelembung percakapan yang proporsional (tidak gepeng) berisi dua baris teks
 * dan not musik. Warnanya mengikuti tint dari Icon, jadi cukup dipakai seperti ikon bawaan.
 */
val LyricsBubbleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LyricsBubble",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(
            "M6 3 H18 A3 3 0 0 1 21 6 V15 A3 3 0 0 1 18 18 H12.2 L8.9 21.1 Q8.2 21.7 8.2 20.8 V18 H6 A3 3 0 0 1 3 15 V6 A3 3 0 0 1 6 3 Z M7.8 8 H10 A0.8 0.8 0 0 1 10 9.6 H7.8 A0.8 0.8 0 0 1 7.8 8 Z M7.8 11.4 H9.4 A0.8 0.8 0 0 1 9.4 13 H7.8 A0.8 0.8 0 0 1 7.8 11.4 Z M14.3 6.4 H15.8 C15.9 7.7 16.7 8.3 17.7 8.9 V10.5 C16.8 10.1 16.2 9.7 15.8 9.2 V12.8 A1.8 1.8 0 1 1 14.3 11.025 Z",
        ).toNodes(),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color.Black),
    ).build()
}
