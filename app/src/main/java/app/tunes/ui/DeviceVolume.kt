package app.tunes.ui

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeMute
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tunes.i18n.t

/**
 * Volume perangkat (aliran musik) sebagai pecahan 0..1. Ikut berubah kalau pengguna menekan tombol
 * volume fisik, dan menulis balik ke [AudioManager] kalau digeser lewat [set].
 */
internal class DeviceVolume(private val audio: AudioManager, private val state: androidx.compose.runtime.MutableFloatState) {
    val fraction: State<Float> get() = state

    fun refresh() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        state.floatValue = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun set(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val level = Math.round(fraction.coerceIn(0f, 1f) * max)
        // Tanpa FLAG_SHOW_UI: panel volume sistem tidak perlu muncul, slider kita sendiri sudah menunjukkannya.
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
        state.floatValue = level.toFloat() / max
    }
}

@Composable
internal fun rememberDeviceVolume(): DeviceVolume {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val volume = remember { DeviceVolume(audio, mutableFloatStateOf(0f)).also { it.refresh() } }
    DisposableEffect(volume) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = volume.refresh()
        }
        // Perubahan volume (tombol fisik, panel sistem) tercatat di Settings.System.
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        volume.refresh()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return volume
}

/**
 * Slider volume perangkat: ikon speaker kecil di kiri (berubah sesuai level) dan besar di kanan,
 * garis tipis dengan penanda bulat kecil, gayanya sama dengan slider durasi lagu.
 */
@Composable
internal fun DeviceVolumeSlider(
    modifier: Modifier = Modifier,
    tint: Color = sheetInk,
    accent: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = 20.dp,
    /** true = ikon penuh (tanpa pudar) dan garis lebih pekat, dipakai saat warna ikon dibalik (Putih/Hitam/Kustom). */
    solid: Boolean = false,
) {
    val volume = rememberDeviceVolume()
    val value by volume.fraction
    val leading: ImageVector = when {
        value <= 0f -> Icons.Rounded.VolumeMute
        else -> Icons.Rounded.VolumeDown
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(leading, contentDescription = t("Volume perangkat"), tint = tint.copy(alpha = if (solid) 1f else 0.7f), modifier = Modifier.size(iconSize))
        GuardedSlider(
            value = value,
            onValueChange = volume::set,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            thumb = {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            },
            track = { sliderState ->
                val range = sliderState.valueRange
                val fraction = if (range.endInclusive > range.start) {
                    (sliderState.value - range.start) / (range.endInclusive - range.start)
                } else {
                    0f
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = if (solid) 0.35f else 0.24f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(accent),
                    )
                }
            },
        )
        Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = tint.copy(alpha = if (solid) 1f else 0.7f), modifier = Modifier.size(iconSize))
    }
}
