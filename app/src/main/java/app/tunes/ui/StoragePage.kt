package app.tunes.ui

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.data.StoragePrefsStore
import app.tunes.data.StorageUsage
import app.tunes.i18n.t
import app.tunes.player.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun sizeText(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))

private fun limitLabel(mb: Int): String = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

/**
 * Halaman Penyimpanan: ringkasan pemakaian Olyuno (unduhan, cache audio, lirik & terjemahan, gambar, data
 * aplikasi) + ruang kosong perangkat; hapus cache per jenis atau sekaligus; batas cache audio; hapus semua
 * unduhan; hapus riwayat pencarian / putar. Angka diukur langsung dari penyimpanan dan diperbarui otomatis.
 */
@Composable
fun StoragePage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = vm.storage
    val limitMb by vm.audioCacheLimitMb.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val collections by vm.downloadCollections.collectAsStateWithLifecycle()
    val searches by vm.searchHistory.collectAsStateWithLifecycle()

    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    // Ukur sekarang, lalu ulangi tiap beberapa detik (unduhan / streaming terus mengubah angkanya).
    LaunchedEffect(tick) {
        while (true) {
            val u = runCatching { storage.measure() }.getOrNull()
            if (u != null) usage = u
            delay(4000)
        }
    }

    var busy by remember { mutableStateOf<String?>(null) }
    fun run(key: String, message: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = key
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // gagal sebagian: pengukuran ulang di bawah menampilkan keadaan sebenarnya
            }
            delay(400)
            busy = null
            tick++
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    var showLimit by rememberSaveable { mutableStateOf(false) }
    var showDeleteDownloads by rememberSaveable { mutableStateOf(false) }
    var showDeleteHistory by rememberSaveable { mutableStateOf(false) }

    val u = usage
    val songCount = downloads.count { it.done }
    val packCount = collections.size
    val hasDownloads = downloads.isNotEmpty() || collections.isNotEmpty() || (u?.downloads ?: 0L) > 0L

    SubPage(title = t("Penyimpanan"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            MenuGroup { UsageSummary(u) }

            SectionTitle(t("Cache"))
            MenuGroup {
                CacheRow(
                    icon = Icons.Rounded.MusicNote,
                    label = t("Cache audio"),
                    description = t("Lagu yang sudah diputar lewat streaming, supaya cepat diputar lagi."),
                    bytes = u?.audioCache ?: 0L,
                    loading = busy == "audio",
                    enabled = busy == null,
                ) { run("audio", t("Cache audio dihapus")) { storage.clearAudioCache() } }
                MenuDivider()
                CacheRow(
                    icon = LyricsBubbleIcon,
                    label = t("Lirik & terjemahan"),
                    description = t("Lirik dan terjemahan yang pernah dimuat."),
                    bytes = u?.lyricsCache ?: 0L,
                    loading = busy == "lyrics",
                    enabled = busy == null,
                ) { run("lyrics", t("Cache lirik dihapus")) { storage.clearLyricsCache() } }
                MenuDivider()
                CacheRow(
                    icon = Icons.Rounded.Image,
                    label = t("Gambar"),
                    description = t("Sampul lagu, album, dan foto artis."),
                    bytes = u?.imageCache ?: 0L,
                    loading = busy == "images",
                    enabled = busy == null,
                ) { run("images", t("Cache gambar dihapus")) { storage.clearImageCache() } }
                MenuDivider()
                val cacheBytes = u?.cache ?: 0L
                MenuRow(
                    leading = { Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Hapus semua cache"),
                    description = t("Unduhan, riwayat, dan pengaturan tidak ikut terhapus."),
                    bold = true,
                    dimmed = cacheBytes <= 0L && busy != "all",
                    onClick = if (cacheBytes > 0L && busy == null) ({ run("all", t("Semua cache dihapus")) { storage.clearAllCache() } }) else null,
                ) {
                    if (busy == "all") BusySpinner() else Text(
                        sizeText(context, cacheBytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            SectionTitle(t("Batas cache audio"))
            MenuGroup {
                MenuRow(
                    leading = { Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Ukuran maksimum cache audio"),
                    description = t("Kalau melebihi batas, lagu yang paling lama tidak diputar dihapus otomatis."),
                    onClick = { showLimit = true },
                ) {
                    Text(
                        limitLabel(limitMb),
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

            SectionTitle(t("Unduhan"))
            MenuGroup {
                MenuRow(
                    leading = { Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Lagu diunduh"),
                    description = when {
                        songCount == 0 && packCount == 0 -> t("Belum ada unduhan")
                        packCount == 0 -> t("%d lagu", songCount)
                        else -> t("%d lagu", songCount) + " • " + t("%d paket", packCount)
                    },
                    onClick = null,
                ) {
                    Text(
                        sizeText(context, u?.downloads ?: 0L),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                MenuDivider()
                MenuRow(
                    leading = {
                        Icon(
                            Icons.Rounded.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    label = t("Hapus semua unduhan"),
                    description = t("Menghapus lagu dan paket yang diunduh dari perangkat."),
                    dimmed = !hasDownloads,
                    onClick = if (hasDownloads && busy == null) ({ showDeleteDownloads = true }) else null,
                ) {
                    if (busy == "downloads") BusySpinner()
                }
            }

            SectionTitle(t("Riwayat"))
            MenuGroup {
                MenuRow(
                    leading = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Hapus riwayat pencarian"),
                    description = if (searches.isEmpty()) t("Belum ada riwayat pencarian") else t("%d pencarian tersimpan", searches.size),
                    dimmed = searches.isEmpty(),
                    onClick = if (searches.isNotEmpty() && busy == null) ({
                        run("search", t("Riwayat pencarian dihapus")) { vm.clearSearchHistory() }
                    }) else null,
                ) {
                    if (searches.isNotEmpty()) TrashHint()
                }
                MenuDivider()
                MenuRow(
                    leading = { Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Hapus riwayat putar"),
                    description = t("Daftar Terakhir dilihat, jumlah putar, dan statistik."),
                    onClick = if (busy == null) ({ showDeleteHistory = true }) else null,
                ) {
                    if (busy == "history") BusySpinner() else TrashHint()
                }
            }
        }
    }

    if (showLimit) {
        CacheLimitDialog(
            selected = limitMb,
            onSelect = { mb ->
                showLimit = false
                if (mb != limitMb) {
                    scope.launch {
                        runCatching { storage.setAudioCacheLimit(mb) }
                        tick++
                        Toast.makeText(context, t("Batas cache audio: %s", limitLabel(mb)), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showLimit = false },
        )
    }

    if (showDeleteDownloads) {
        ConfirmDeleteDialog(
            title = t("Hapus semua unduhan?"),
            message = t("Semua lagu dan paket yang diunduh akan dihapus dari perangkat. Unduh lagi untuk memutarnya offline."),
            onConfirm = {
                showDeleteDownloads = false
                run("downloads", t("Semua unduhan dihapus")) { storage.removeAllDownloads() }
            },
            onDismiss = { showDeleteDownloads = false },
        )
    }

    if (showDeleteHistory) {
        ConfirmDeleteDialog(
            title = t("Hapus riwayat putar?"),
            message = t("Daftar Terakhir dilihat, jumlah putar, dan statistik akan dihapus. Lagu yang disukai tetap ada."),
            onConfirm = {
                showDeleteHistory = false
                run("history", t("Riwayat putar dihapus")) { vm.clearHistory() }
            },
            onDismiss = { showDeleteHistory = false },
        )
    }
}

/** Satu baris cache: ikon, nama, keterangan, ukuran + ikon sampah di kanan; diketuk = hapus. */
@Composable
private fun CacheRow(
    icon: ImageVector,
    label: String,
    description: String,
    bytes: Long,
    loading: Boolean,
    enabled: Boolean,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    MenuRow(
        leading = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
        label = label,
        description = description,
        dimmed = bytes <= 0L && !loading,
        onClick = if (bytes > 0L && enabled) onClear else null,
    ) {
        if (loading) {
            BusySpinner()
        } else {
            Text(
                sizeText(context, bytes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (bytes > 0L) {
                Spacer(Modifier.width(6.dp))
                TrashHint()
            }
        }
    }
}

@Composable
private fun TrashHint() {
    Icon(
        Icons.Rounded.DeleteOutline,
        contentDescription = t("Hapus"),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun BusySpinner() {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
}

/** Ringkasan: total dipakai, bar bersegmen per jenis, legenda berukuran, dan ruang kosong perangkat. */
@Composable
private fun UsageSummary(usage: StorageUsage?) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val parts: List<Triple<String, Long, Color>> = listOf(
        Triple(t("Unduhan"), usage?.downloads ?: 0L, scheme.primary),
        Triple(t("Cache audio"), usage?.audioCache ?: 0L, scheme.tertiary),
        Triple(t("Lirik & terjemahan"), usage?.lyricsCache ?: 0L, scheme.secondary),
        Triple(t("Gambar"), usage?.imageCache ?: 0L, scheme.primary.copy(alpha = 0.45f)),
        Triple(t("Data aplikasi"), usage?.appData ?: 0L, scheme.outline),
    )
    val total = parts.sumOf { it.second }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text(
            if (usage == null) "…" else sizeText(context, total),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            t("dipakai oleh Olyuno"),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.surfaceVariant),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (total > 0L) {
                parts.filter { it.second > 0L }.forEach { (_, bytes, color) ->
                    Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(color))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        parts.forEach { (label, bytes, color) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    if (usage == null) "…" else sizeText(context, bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (usage != null && usage.deviceTotal > 0L) {
            Spacer(Modifier.height(10.dp))
            Text(
                t("Ruang kosong di perangkat: %s dari %s", sizeText(context, usage.deviceFree), sizeText(context, usage.deviceTotal)),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Modal pilihan batas cache audio (gaya sama dengan modal pilihan lain di Pengaturan). */
@Composable
private fun CacheLimitDialog(selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = { Text(t("Batas cache audio")) },
        text = {
            DialogDim()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    t("Kalau melebihi batas, lagu yang paling lama tidak diputar dihapus otomatis."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                StoragePrefsStore.LIMIT_OPTIONS_MB.forEach { mb ->
                    DialogChoiceRow(limitLabel(mb), mb == selected) { onSelect(mb) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Tutup")) } },
    )
}
