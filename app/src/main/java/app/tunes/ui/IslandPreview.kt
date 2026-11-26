package app.tunes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.tunes.data.IslandColor
import app.tunes.data.IslandButtonStyle
import app.tunes.data.IslandShape
import app.tunes.data.IslandStyle
import app.tunes.i18n.t
import app.tunes.player.Look
import app.tunes.player.islandBackground
import app.tunes.player.islandLook
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Modal "Preview Dynamic Island": dua kotak bersudut membulat. Atas = island kecil, bawah = island besar,
 * keduanya hidup (equalizer menari, seek bar berjalan) dan mengikuti gaya yang sedang dipilih.
 * Selalu memakai lagu contoh: judul "Judul lagu" dan sampul buatan sendiri.
 */
@Composable
internal fun IslandPreviewDialog(
    style: IslandStyle,
    color: IslandColor,
    custom: Int,
    corner: Float = 1f,
    shape: IslandShape = IslandShape.Rounded,
    bigScale: Float = 1f,
    textScale: Float = 1f,
    buttonStyle: IslandButtonStyle = IslandButtonStyle.Filled,
    showButtons: Boolean = true,
    showArtist: Boolean = true,
    showTitle: Boolean = true,
    opacity: Float = 1f,
    onDismiss: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val look = remember(style, color, custom, dark, corner) { islandLook(style, color, custom, dark, true, corner) }

    // Pratinjau selalu memakai lagu contoh (judul tetap + sampul buatan sendiri), bukan lagu yang sedang diputar.
    val palette = remember { SongPalette(top = Color(0xFF6A3FB5), bottom = Color(0xFF1B1038), accent = Color(0xFFFF9BD0)) }
    val (topInt, bottomInt) = remember(look, palette) { islandBackground(look, palette) }
    val raw = palette.accent
    val accent = if (look.lightSurface) androidx.compose.ui.graphics.lerp(raw, Color.Black, 0.5f) else raw

    // Waktu demo yang berputar 0 -> 3:30 terus-menerus.
    val demo = rememberInfiniteTransition(label = "islandDemo")
    val progress by demo.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "demoProgress",
    )

    val data = PreviewData(
        title = t("Judul lagu"),
        artist = t("Nama artis"),
        playing = true,
        progress = progress,
        durationMs = 210_000L,
        look = look,
        top = Color(topInt),
        bottom = Color(bottomInt),
        accent = accent,
        opacity = opacity,
        circle = shape == IslandShape.Circle,
        bigScale = bigScale,
        textScale = textScale,
        outlineButtons = buttonStyle == IslandButtonStyle.Outline,
        showButtons = showButtons,
        showArtist = showArtist,
        showTitle = showTitle,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 12.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = { Text(t("Preview Dynamic Island")) },
        text = {
            DialogDim()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StageLabel(t("Kartu kecil"))
                PreviewStage(Modifier.height(200.dp), glow = null, statusLine = true) { IslandMini(data) }
                Spacer(Modifier.height(8.dp))
                StageLabel(t("Kartu diperluas"))
                PreviewStage(Modifier, glow = data.accent, statusLine = false) { IslandBig(data) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Tutup")) } },
    )
}

@Composable
private fun StageLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

private class PreviewData(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val progress: Float,
    val durationMs: Long,
    val look: Look,
    val top: Color,
    val bottom: Color,
    val accent: Color,
    val opacity: Float = 1f,
    val circle: Boolean = false,
    val bigScale: Float = 1f,
    val textScale: Float = 1f,
    val outlineButtons: Boolean = false,
    val showButtons: Boolean = true,
    val showArtist: Boolean = true,
    val showTitle: Boolean = true,
)

/**
 * Kotak bersudut membulat berlatar gelap tempat island ditaruh. [statusLine] = garis tipis seperti batas
 * status bar (kartu kecil); [glow] = pendar warna cover di sekeliling kartu (kartu diperluas).
 */
@Composable
private fun PreviewStage(modifier: Modifier, glow: Color?, statusLine: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3A4458), Color(0xFF11131A)))),
        contentAlignment = if (statusLine) Alignment.TopCenter else Alignment.Center,
    ) {
        if (glow != null) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.radialGradient(listOf(glow.copy(alpha = 0.38f), Color.Transparent)),
                ),
            )
        }
        if (statusLine) {
            Box(
                Modifier
                    .padding(top = 34.dp, start = 32.dp, end = 32.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.10f)),
            )
        }
        Box(
            Modifier.padding(horizontal = 18.dp, vertical = if (statusLine) 44.dp else 22.dp),
            contentAlignment = Alignment.TopCenter,
        ) { content() }
    }
}

@Composable
private fun islandSurface(d: PreviewData, radiusDp: Float): Modifier {
    val shape = RoundedCornerShape(radiusDp.dp)
    return Modifier
        .alpha(d.opacity)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(d.top, d.bottom)))
        .border(1.dp, Color(d.look.stroke), shape)
}

@Composable
private fun IslandMini(d: PreviewData) {
    if (d.circle) {
        // Bentuk Bulat: lingkaran 40dp, hanya logo lagu di tengah.
        Box(
            Modifier
                .size(40.dp)
                .then(islandSurface(d, 20f)),
            contentAlignment = Alignment.Center,
        ) { PreviewArt(d, 28, 14) }
        return
    }
    Row(
        Modifier
            .width(196.dp)
            .height(40.dp)
            .then(islandSurface(d, d.look.miniR))
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewArt(d, 24, 7)
        if (d.showTitle) {
            Text(
                d.title,
                color = Color(d.look.main),
                fontSize = (12f * d.textScale).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Bars(d.playing, d.accent, Modifier.size(16.dp, 14.dp))
    }
}

@Composable
private fun IslandBig(d: PreviewData) {
    val main = Color(d.look.main)
    Column(
        Modifier
            .graphicsLayer(scaleX = d.bigScale, scaleY = d.bigScale)
            .widthIn(max = 400.dp)
            .fillMaxWidth()
            .height(if (d.showButtons) 184.dp else 132.dp)
            .then(islandSurface(d, d.look.bigR))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PreviewArt(d, 52, 12)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
                if (d.showTitle) {
                    Text(d.title, color = main, fontSize = (16f * d.textScale).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (d.showArtist) {
                    Text(d.artist, color = Color(d.look.sub), fontSize = (13f * d.textScale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Bars(d.playing, d.accent, Modifier.size(20.dp, 18.dp))
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val h = 4.dp.toPx()
            val cy = size.height / 2
            drawRoundRect(Color(d.look.track), Offset(0f, cy - h / 2), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(d.accent, Offset(0f, cy - h / 2), Size(size.width * d.progress, h), CornerRadius(h / 2))
        }
        Row(Modifier.fillMaxWidth()) {
            val timeColor = Color(d.look.time)
            Text(fmt((d.progress * d.durationMs).toLong()), color = timeColor, fontSize = (11f * d.textScale).sp)
            Spacer(Modifier.weight(1f))
            Text(fmt(d.durationMs), color = timeColor, fontSize = (11f * d.textScale).sp)
        }
        if (d.showButtons) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val o = d.outlineButtons
            Icon(
                if (o) Icons.Outlined.SkipPrevious else Icons.Rounded.SkipPrevious,
                null, tint = Color(d.look.icon), modifier = Modifier.size(32.dp),
            )
            Box(
                Modifier
                    .padding(horizontal = 22.dp)
                    .size(48.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        o && d.playing -> Icons.Outlined.Pause
                        o -> Icons.Outlined.PlayArrow
                        d.playing -> Icons.Rounded.Pause
                        else -> Icons.Rounded.PlayArrow
                    },
                    null,
                    tint = Color(d.look.icon),
                    modifier = Modifier.size(28.dp),
                )
            }
            Icon(
                if (o) Icons.Outlined.SkipNext else Icons.Rounded.SkipNext,
                null, tint = Color(d.look.icon), modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** Sampul lagu contoh buatan sendiri: gradien senja, matahari, dan not musik. */
@Composable
private fun PreviewArt(d: PreviewData, sizeDp: Int, radiusDp: Int) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(radiusDp.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3B2A8F), Color(0xFFC2379D), Color(0xFFFF8A5B)))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(sizeDp.dp)) {
            drawCircle(Color(0xFFFFD27A).copy(alpha = 0.9f), radius = size.minDimension * 0.24f, center = Offset(size.width * 0.5f, size.height * 0.62f))
            drawRoundRect(Color(0xFF1B1038).copy(alpha = 0.55f), Offset(0f, size.height * 0.72f), Size(size.width, size.height * 0.28f))
        }
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size((sizeDp * 0.42f).dp),
        )
    }
}

/** Empat batang equalizer yang menari saat [playing]; diam rendah saat dijeda (sama seperti di island asli). */
@Composable
private fun Bars(playing: Boolean, color: Color, modifier: Modifier) {
    val tr = rememberInfiniteTransition(label = "bars")
    val t by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "barsT")
    Canvas(modifier) {
        val n = 4
        val gap = size.width * 0.12f
        val barW = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val level = if (playing) 0.3f + 0.7f * abs(sin(2.0 * PI * (t + i * 0.21))).toFloat() else 0.22f
            val h = size.height * level
            drawRoundRect(color, Offset(i * (barW + gap), (size.height - h) / 2f), Size(barW, h), CornerRadius(barW / 2))
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
