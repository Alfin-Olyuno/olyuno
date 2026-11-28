package app.tunes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import app.tunes.lyrics.Languages
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tunes.i18n.t

/**
 * Menu titik tiga di layar lirik: [Muat ulang] [Salin] di atas, garis pemisah, lalu pengaturan
 * "Mengikuti per lirik", "Terjemahkan lirik", dan "Bagikan lirik". Sementara baru tampilan;
 * fungsinya dihubungkan lewat callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsMenuSheet(
    followLine: Boolean,
    onFollowLineChange: (Boolean) -> Unit,
    translate: Boolean,
    onTranslateChange: (Boolean) -> Unit,
    targetLang: String,
    onTargetLangChange: (String) -> Unit,
    sourceLabel: String,
    onOpenSource: () -> Unit,
    keepAwake: Boolean,
    onKeepAwakeChange: (Boolean) -> Unit,
    downloaded: Boolean,
    downloadProgress: Float?,
    downloadFailed: Boolean,
    onDownload: () -> Unit,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onSearch: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        scrimColor = ModalScrim,
        shape = ModalSheetShape,
        dragHandle = { ModalDragHandle() },
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Sama dengan sheet pilihan layanan: lebih terang dari latar lagu, teks putih.
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
                .padding(horizontal = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(Icons.Rounded.Refresh, t("Muat ulang"), onRefresh, Modifier.weight(1f))
                ActionTile(Icons.Rounded.ContentCopy, t("Salin lirik"), onCopy, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(thickness = 1.dp, color = sheetInk.copy(alpha = 0.14f))
            Spacer(Modifier.height(4.dp))

            SearchLyricsButton(onSearch)
            ToggleRow(t("Mengikuti per lirik"), followLine, onFollowLineChange)
            ToggleRow(t("Terjemahkan lirik"), translate, onTranslateChange)
            SmoothVisibility(visible = translate) {
                LanguagePicker(targetLang, onTargetLangChange)
            }
            SourceButton(t(sourceLabel), onOpenSource)
            ToggleRow(t("Jaga layar tetap hidup"), keepAwake, onKeepAwakeChange)
            DownloadButton(downloaded, downloadProgress, downloadFailed, onDownload)
            val shareSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressScale(shareSource, 0.97f)
                    .clip(RoundedCornerShape(16.dp))
                    .pressClickable(shareSource, onClick = onShare)
                    .padding(horizontal = 4.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t("Bagikan lirik"),
                    color = sheetInk,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.Share, contentDescription = null, tint = sheetInk, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        }
    }
}

/**
 * Tombol "Unduh": mengunduh lagu yang sedang diputar. Setelah selesai lagunya muncul di halaman Diunduh.
 * Status di kanan: ikon unduh (belum), persen (sedang berjalan), "Gagal" (bisa diketuk lagi), atau
 * "Sudah diunduh" (tidak bisa diketuk lagi).
 */
@Composable
internal fun DownloadButton(downloaded: Boolean, progress: Float?, failed: Boolean, onClick: () -> Unit) {
    val busy = progress != null
    val enabled = !downloaded && !busy
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(source, 0.97f)
            .clip(RoundedCornerShape(16.dp))
            .pressClickable(source, enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            t("Unduh"),
            color = sheetInk,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        when {
            downloaded -> {
                Text(
                    t("Sudah diunduh"),
                    color = sheetInk.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Check, contentDescription = null, tint = sheetInk.copy(alpha = 0.72f))
            }
            busy -> {
                Text(
                    t("Mengunduh %d%%", ((progress ?: 0f) * 100).toInt()),
                    color = sheetInk.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            failed -> {
                Text(
                    t("Gagal, ketuk untuk coba lagi"),
                    color = sheetInk.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> Icon(Icons.Rounded.Download, contentDescription = null, tint = sheetInk.copy(alpha = 0.72f))
        }
    }
}

/** Baris "Sumber lirik" (tanpa kotak dan tanpa panah, seperti baris toggle) dengan nama layanan saat ini; diketuk membuka sheet pilihan layanan lirik. */
@Composable
private fun SourceButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            t("Sumber lirik"),
            color = sheetInk,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            label,
            color = sheetInk.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Tombol "Timer tidur" dengan status saat ini (Mati / sisa waktu / Akhir lagu); diketuk membuka sheet timer tidur. */
@Composable
internal fun SleepTimerButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            t("Timer tidur"),
            color = sheetInk,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            label,
            color = sheetInk.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Tombol "Pilih bahasa" yang membuka dropdown bahasa tujuan terjemahan. */
@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { expanded = true }
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                t("Pilih bahasa"),
                color = sheetInk,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                Languages.nameOf(selected),
                color = sheetInk.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = glassContainer(
                if (sheetIsLight()) lerp(Color.White, MaterialTheme.colorScheme.primary, 0.07f)
                else lerp(MaterialTheme.colorScheme.surfaceContainerHigh, Color.White, 0.12f),
                0.86f, 0.96f,
            ),
            border = glassEdge(),
        ) {
            Languages.all.forEach { lang ->
                DropdownMenuItem(
                    text = {
                        Text(
                            lang.name,
                            color = sheetInk,
                            fontWeight = if (lang.code == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = {
                        if (lang.code == selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        onSelect(lang.code)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier
            .pressScale(source, 0.95f)
            .clip(RoundedCornerShape(22.dp))
            .background(sheetInk.copy(alpha = 0.12f))
            .pressClickable(source, onClick = onClick)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = sheetInk, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, color = sheetInk, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val toggle = rememberSmoothToggle(checked, onChange)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = toggle::flip)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = sheetInk, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        SmoothSwitch(
            toggle = toggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = sheetInk.copy(alpha = 0.8f),
                uncheckedTrackColor = sheetInk.copy(alpha = 0.12f),
                uncheckedBorderColor = sheetInk.copy(alpha = 0.4f),
            ),
        )
    }
}

/** Baris "Cari lirik": membuka sheet pencarian di dalam lirik lagu yang sedang diputar. */
@Composable
internal fun SearchLyricsButton(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            t("Cari lirik"),
            color = sheetInk,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.Search, contentDescription = null, tint = sheetInk, modifier = Modifier.size(24.dp))
    }
}
