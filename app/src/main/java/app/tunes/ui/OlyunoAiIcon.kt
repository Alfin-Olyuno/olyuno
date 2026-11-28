package app.tunes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Ikon Olyuno AI: gelembung obrolan berisi empat bar equalizer yang tepat di tengah rongganya.
 * Garis (bukan isian) dengan ujung membulat; warnanya mengikuti tint dari Icon.
 */
val OlyunoAiIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "OlyunoAi",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(
            "M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-6l-4 4v-4H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        ).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).addPath(
        // Pusat vertikal rongga gelembung = y 10,5; keempat bar berpusat di situ.
        pathData = PathParser().parsePathString(
            "M8 9.5v2M10.7 8v5M13.3 7v7M16 9v3",
        ).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()
}
