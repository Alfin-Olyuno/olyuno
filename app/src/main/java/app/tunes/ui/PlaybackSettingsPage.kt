package app.tunes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.data.AudioQuality
import app.tunes.i18n.t
import app.tunes.player.PlayerState

/** Halaman Pemutaran: pengaturan pemutaran dan audio (termasuk kualitas audio). */
@Composable
fun PlaybackSettingsPage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val autoplay by vm.autoplay.collectAsStateWithLifecycle()
    val resumeLast by vm.resumeLast.collectAsStateWithLifecycle()
    val sleep by vm.sleepTimer.collectAsStateWithLifecycle()
    val skipSilence by vm.skipSilence.collectAsStateWithLifecycle()
    val normalizeVolume by vm.normalizeVolume.collectAsStateWithLifecycle()
    val quality by vm.audioQuality.collectAsStateWithLifecycle()
    val dataSaver by vm.dataSaver.collectAsStateWithLifecycle()
    var showDataSaver by rememberSaveable { mutableStateOf(false) }
    var showQuality by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    SubPage(title = t("Pemutaran"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            SectionTitle(t("Pemutaran"))
            MenuGroup {
                SwitchMenuRow(
                    leading = {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(24.dp))
                    },
                    label = t("Putar otomatis"),
                    description = t("Saat antrean habis, lanjut memutar lagu serupa."),
                    checked = autoplay,
                    onChange = vm::setAutoplay,
                )
                MenuDivider()
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Lanjutkan terakhir diputar"),
                    description = t("Saat aplikasi dibuka lagi, antrean dan posisi lagu terakhir kembali seperti tadi."),
                    checked = resumeLast,
                    onChange = vm::setResumeLast,
                )
                MenuDivider()
                // Timer tidur: diketuk membuka sheet pengatur timer.
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Timer tidur"),
                    onClick = { showSleep = true },
                ) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        sleepTimerLabel(sleep),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Audio: kartu terpisah (beda jenis dengan Putar otomatis dan Timer tidur).
            SectionTitle(t("Audio"))
            MenuGroup {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                    DeviceVolumeSlider(
                        Modifier,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        t("Mengatur volume musik perangkat."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                MenuDivider()
                // Hemat data: menyalakan menampilkan modal berisi apa saja yang dimatikan; mematikan langsung.
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.DataSaverOn, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Hemat data"),
                    description = t("Menurunkan kualitas audio ke rendah dan mematikan fitur yang banyak memakai data, supaya kuota lebih awet."),
                    checked = dataSaver,
                    onChange = { on -> if (on) showDataSaver = true else vm.setDataSaver(false) },
                )
                MenuDivider()
                // Kualitas audio: diketuk membuka modal pilihan (Otomatis / Tinggi / Rendah).
                // Saat Hemat data nyala, kualitas dikunci Rendah dan baris ini tidak bisa diubah.
                MenuRow(
                    leading = { Icon(Icons.Rounded.HighQuality, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Kualitas audio"),
                    description = if (dataSaver) t("Dikunci Rendah oleh Hemat data") else t("Kualitas suara %s", qualityLabel(quality)),
                    dimmed = dataSaver,
                    onClick = if (dataSaver) null else ({ showQuality = true }),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MenuDivider()
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.FastForward, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Lewati bagian hening"),
                    description = t("Otomatis melompati bagian lagu yang sunyi."),
                    checked = skipSilence,
                    onChange = vm::setSkipSilence,
                )
                MenuDivider()
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Normalisasi volume"),
                    description = t("Menyamakan kerasnya tiap lagu agar tidak ada yang terlalu keras."),
                    checked = normalizeVolume,
                    onChange = vm::setNormalizeVolume,
                )
            }
        }
    }

    if (showDataSaver) {
        DataSaverDialog(
            onConfirm = {
                vm.setDataSaver(true)
                showDataSaver = false
            },
            onDismiss = { showDataSaver = false },
        )
    }

    if (showQuality) {
        AudioQualityDialog(
            selected = quality,
            onSelect = {
                vm.setAudioQuality(it)
                showQuality = false
            },
            onDismiss = { showQuality = false },
        )
    }

    if (showSleep) {
        SleepTimerSheet(
            state = sleep,
            onMinutes = vm::setSleepTimerMinutes,
            onTrackEnd = vm::setSleepTimerUntilTrackEnd,
            onCancel = vm::cancelSleepTimer,
            onDismiss = { showSleep = false },
            forceDark = true,
        )
    }
}

/** Nama pilihan kualitas audio (dipakai di baris halaman dan di modal). */
private fun qualityLabel(q: AudioQuality): String = when (q) {
    AudioQuality.Auto -> t("Otomatis")
    AudioQuality.High -> t("Tinggi")
    AudioQuality.Low -> t("Rendah")
}

/** Penjelasan singkat tiap pilihan kualitas audio. */
private fun qualityDescription(q: AudioQuality): String = when (q) {
    AudioQuality.Auto -> t("Kualitas suara disesuaikan otomatis: tinggi di Wi-Fi, rendah di data seluler.")
    AudioQuality.High -> t("Selalu memutar dengan kualitas suara tertinggi. Memakai lebih banyak data.")
    AudioQuality.Low -> t("Selalu memutar dengan kualitas suara rendah. Hemat data.")
}

/** Modal pilihan kualitas audio: Otomatis, lalu Tinggi, lalu Rendah. */
@Composable
private fun AudioQualityDialog(
    selected: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = glassContainer(scheme.surfaceContainerHigh),
        title = {
            DialogDim()
            Text(t("Kualitas audio"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                listOf(AudioQuality.Auto, AudioQuality.High, AudioQuality.Low).forEach { q ->
                    QualityOption(q, selected = q == selected) { onSelect(q) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("Batal")) }
        },
    )
}

@Composable
private fun QualityOption(quality: AudioQuality, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(qualityLabel(quality), style = MaterialTheme.typography.titleMedium)
            Text(
                qualityDescription(quality),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Modal konfirmasi Hemat data: menjelaskan apa saja yang dimatikan atau dibatasi. */
@Composable
private fun DataSaverDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = glassContainer(scheme.surfaceContainerHigh),
        title = {
            DialogDim()
            Text(t("Aktifkan Hemat data?"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text(
                    t("Yang dimatikan atau dibatasi:"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                listOf(
                    t("Kualitas audio dikunci ke Rendah (pilihanmu kembali saat Hemat data dimatikan)"),
                    t("Putar otomatis berhenti: tidak mengambil lagu serupa saat antrean habis"),
                    t("Terjemahan lirik dimatikan"),
                    t("Unduhan hanya lewat Wi-Fi, bukan data seluler"),
                ).forEach { line ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 10.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("Batal")) }
        },
        confirmButton = {
            Button(onClick = onConfirm, shape = RoundedCornerShape(50)) { Text(t("Aktifkan")) }
        },
    )
}
