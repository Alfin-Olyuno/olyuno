package app.tunes.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Slider yang tidak berubah kalau tersentuh sambil scroll atau diketuk tidak sengaja.
 * Nilai baru hanya diteruskan kalau sentuhan dimulai di dekat gagang (penanda) lalu digeser mendatar
 * dengan jelas (dua kali ambang sentuh, dan lebih mendatar daripada menegak). Ketukan, gerakan menegak
 * (scroll), sentuhan dua jari, dan sentuhan yang sudah diambil scroll tidak pernah mengubah nilai.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    thumb: (@Composable (SliderState) -> Unit)? = null,
    track: (@Composable (SliderState) -> Unit)? = null,
) {
    val slop = LocalViewConfiguration.current.touchSlop
    val currentValue = rememberUpdatedState(value)
    val currentRange = rememberUpdatedState(valueRange)
    // [0] = geseran sengaja sedang berlangsung (nilai boleh diteruskan).
    val armed = remember { booleanArrayOf(false) }

    val gate = Modifier.pointerInput(slop) {
        val grab = 24.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            armed[0] = false
            val r = currentRange.value
            val span = r.endInclusive - r.start
            val frac = if (span > 0f) ((currentValue.value - r.start) / span).coerceIn(0f, 1f) else 0f
            // Sentuhan jauh dari gagang: abaikan seluruh gestur ini.
            if (abs(down.position.x - frac * size.width) > grab) return@awaitEachGesture
            var total = Offset.Zero
            var aborted = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) break
                // Jari kedua menyentuh, atau induk (scroll) sudah mengambil gestur: batalkan, jangan ubah nilai.
                if (event.changes.size > 1 || (!armed[0] && change.isConsumed)) aborted = true
                total += change.positionChange()
                if (!armed[0] && !aborted) {
                    val ax = abs(total.x)
                    val ay = abs(total.y)
                    // Gerakan menegak lebih dulu = scroll: gestur ini tidak boleh menggeser slider.
                    if (ay > slop && ay >= ax) aborted = true
                    else if (ax > slop * 2f && ax > ay * 2f) armed[0] = true
                }
            }
        }
    }

    val change: (Float) -> Unit = { if (armed[0]) onValueChange(it) }
    val finished: () -> Unit = { if (armed[0]) onValueChangeFinished?.invoke() }
    if (thumb != null && track != null) {
        Slider(
            value = value,
            onValueChange = change,
            modifier = modifier.then(gate),
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = finished,
            thumb = thumb,
            track = track,
        )
    } else {
        Slider(
            value = value,
            onValueChange = change,
            modifier = modifier.then(gate),
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = finished,
        )
    }
}
