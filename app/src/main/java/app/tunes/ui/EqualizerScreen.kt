package app.tunes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.player.EqPreset
import app.tunes.player.EqState
import app.tunes.player.PlayerState
import kotlin.math.roundToInt
import app.tunes.i18n.t

private fun freqLabel(hz: Int): String =
    if (hz < 1000) "$hz Hz"
    else {
        val k = hz / 1000f
        if (k % 1f == 0f) "${k.toInt()} kHz" else "${(k * 10).roundToInt() / 10f} kHz"
    }

private fun dbLabel(milliBel: Int): String {
    val db = milliBel / 100f
    val v = (db * 10).roundToInt() / 10f
    return if (v > 0f) "+$v dB" else "$v dB"
}

/** Halaman Ekualiser: satu penggeser per frekuensi untuk menyesuaikan suara. */
@Composable
fun EqualizerPage(
    vm: MainViewModel,
    state: EqState,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    SubPage(title = t("Ekualiser"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(top = LocalTopInset.current, bottom = LocalBottomInset.current),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Aktifkan ekualiser", style = MaterialTheme.typography.titleMedium)
                SmoothSwitch(checked = state.enabled, onCheckedChange = vm::setEqEnabled)
            }
            if (!state.attached) {
                Text(
                    t("Putar lagu dulu untuk mendengar efeknya."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // Preset: satu ketukan mengatur semua band; menggeser band manual melepas pilihan preset.
            Text(
                t("Preset"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val chipInverted = playCircleColors()
                EqPreset.values().forEach { p ->
                    FilterChip(
                        selected = state.preset == p.id,
                        onClick = { vm.applyEqPreset(p) },
                        enabled = state.enabled,
                        label = { Text(t(p.label)) },
                        colors = if (chipInverted != null) {
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipInverted.circle,
                                selectedLabelColor = chipInverted.icon,
                            )
                        } else {
                            FilterChipDefaults.filterChipColors()
                        },
                    )
                }
            }
            state.levels.forEachIndexed { i, level ->
                val hz = state.freqsHz.getOrNull(i) ?: 0
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        freqLabel(hz),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(64.dp),
                    )
                    GuardedSlider(
                        value = level.toFloat(),
                        onValueChange = { vm.setEqBand(i, it.roundToInt()) },
                        valueRange = state.minLevel.toFloat()..state.maxLevel.toFloat(),
                        enabled = state.enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        dbLabel(level),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(64.dp),
                    )
                }
            }
            if (state.bassSupported) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t("Penguat bass"),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(96.dp),
                    )
                    GuardedSlider(
                        value = state.bass.toFloat(),
                        onValueChange = { vm.setEqBass(it.roundToInt()) },
                        valueRange = 0f..1000f,
                        enabled = state.enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${state.bass / 10}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(48.dp),
                    )
                }
            }
            TextButton(onClick = vm::resetEq, enabled = state.enabled) {
                Text("Atur ulang")
            }
        }
    }
}
