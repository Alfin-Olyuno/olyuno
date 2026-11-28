package app.tunes.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
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
 * Timer tidur, Unduh lagu yang sedang diputar, slider volume perangkat, Normalisasi volume, dan Jaga layar tetap hidup.
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
    /** Lagu perangkat: "Mulai radio" disembunyikan (butuh id YouTube). */
    isLocal: Boolean,
    onStartRadio: () -> Unit,
    onAddToPlaylist: () -> Unit,
    normalizeVolume: Boolean,
    onNormalizeVolumeChange: (Boolean) -> Unit,
    /** Sama dengan "Jaga layar tetap hidup" di menu lirik dan Pengaturan > Lirik (satu pengaturan yang sama). */
    keepAwake: Boolean,
    onKeepAwakeChange: (Boolean) -> Unit,
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
                if (!isLocal) {
                    SheetActionRow(t("Mulai radio"), Icons.Rounded.Radio, onStartRadio)
                }
                SheetActionRow(t("Tambah ke playlist"), Icons.Rounded.LibraryAdd, onAddToPlaylist)
                DeviceVolumeSlider(Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
                ToggleRow(t("Normalisasi volume"), normalizeVolume, onNormalizeVolumeChange)
                ToggleRow(t("Jaga layar tetap hidup"), keepAwake, onKeepAwakeChange)
            }
            Spacer(Modifier.height(12.dp))
        }
        }
    }
}

/** Baris aksi di sheet Pemutaran: label di kiri, ikon di kanan (gaya sama dengan baris Unduh). */
@Composable
private fun SheetActionRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .pressScale(source, 0.97f)
            .clip(RoundedCornerShape(16.dp))
            .pressClickable(source, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, color = sheetInk, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = sheetInk.copy(alpha = 0.72f))
    }
}
