package app.tunes.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tunes.data.SleepTimerState
import app.tunes.i18n.t

/**
 * Menu singkat di halaman player (tombol titik tiga di sebelah "Sedang diputar"):
 * Timer tidur, Unduh lagu yang sedang diputar, slider volume perangkat, dan Normalisasi volume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerMenuSheet(
    sleep: SleepTimerState,
    onOpenSleepTimer: () -> Unit,
    downloaded: Boolean,
    downloadProgress: Float?,
    downloadFailed: Boolean,
    onDownload: () -> Unit,
    normalizeVolume: Boolean,
    onNormalizeVolumeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
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
                .navigationBarsPadding()
                .padding(horizontal = 12.dp),
        ) {
            Text(
                t("Pemutaran"),
                color = sheetInk,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 8.dp)) {
                SleepTimerButton(sleepTimerLabel(sleep), onOpenSleepTimer)
                DownloadButton(downloaded, downloadProgress, downloadFailed, onDownload)
                DeviceVolumeSlider(Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
                ToggleRow(t("Normalisasi volume"), normalizeVolume, onNormalizeVolumeChange)
            }
            Spacer(Modifier.height(12.dp))
        }
        }
    }
}
