package app.tunes.ui

import android.os.SystemClock
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tunes.data.SleepTimerState
import app.tunes.i18n.t
import kotlinx.coroutines.delay

/** Pilihan durasi timer tidur (menit). */
private val SleepMinutes = listOf(15, 30, 45, 60)

/** Teks status timer yang diperbarui tiap detik: "Mati", "Akhir lagu", atau sisa waktu (m:ss / h:mm:ss). */
@Composable
internal fun sleepTimerLabel(state: SleepTimerState): String {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state) {
        while (state.endAtMs > 0L) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    return when {
        state.untilTrackEnd -> t("Akhir lagu")
        state.endAtMs > 0L -> formatRemaining(state.endAtMs - now)
        else -> t("Mati")
    }
}

private fun formatRemaining(ms: Long): String {
    val total = ((ms.coerceAtLeast(0L) + 999L) / 1000L).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Sheet pengatur timer tidur: pilihan 15 / 30 / 45 / 60 menit atau "Akhir lagu"; kalau timer sedang jalan,
 * ada status sisa waktu di atas dan "Matikan timer" di bawah. Memilih langsung memulai timer dan menutup sheet.
 * [forceDark] = bungkus dengan skema gelap (untuk halaman terang; sheet ini berteks putih seperti sheet lirik).
 */
@Composable
fun SleepTimerSheet(
    state: SleepTimerState,
    onMinutes: (Int) -> Unit,
    onTrackEnd: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    forceDark: Boolean = false,
) {
    if (forceDark) {
        MaterialTheme(colorScheme = darkColorScheme(), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            SleepTimerSheetContent(state, onMinutes, onTrackEnd, onCancel, onDismiss)
        }
    } else {
        SleepTimerSheetContent(state, onMinutes, onTrackEnd, onCancel, onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheetContent(
    state: SleepTimerState,
    onMinutes: (Int) -> Unit,
    onTrackEnd: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val label = sleepTimerLabel(state)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        scrimColor = ModalScrim,
        shape = ModalSheetShape,
        dragHandle = { ModalDragHandle() },
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetContainerColor(),
        contentColor = sheetContentColor(),
    ) {
        SheetThemed {
        Column(
            Modifier
                .fillMaxWidth()
                // Landscape: layar pendek, isi sheet harus bisa digulir.
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Text(
                t("Timer tidur"),
                color = sheetInk,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (state.active) {
                Text(
                    if (state.untilTrackEnd) t("Berhenti di akhir lagu") else t("Berhenti dalam %s", label),
                    color = sheetInk.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            SleepMinutes.forEach { m ->
                SleepOption(
                    label = t("%d menit", m),
                    selected = !state.untilTrackEnd && state.endAtMs > 0L && state.minutes == m,
                ) { onMinutes(m); onDismiss() }
            }
            SleepOption(label = t("Akhir lagu"), selected = state.untilTrackEnd) {
                onTrackEnd()
                onDismiss()
            }
            if (state.active) {
                Text(
                    t("Matikan timer"),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable { onCancel(); onDismiss() }
                        .padding(vertical = 14.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        }
    }
}

@Composable
private fun SleepOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .modalOption(selected, onClick)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = sheetInk,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = sheetInk.copy(alpha = 0.72f),
            ),
        )
    }
}
