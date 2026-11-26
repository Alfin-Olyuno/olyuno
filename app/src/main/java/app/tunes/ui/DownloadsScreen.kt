package app.tunes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.data.DownloadEntry
import app.tunes.data.DownloadedCollection
import app.tunes.innertube.CollectionKind
import app.tunes.player.PlayerState
import app.tunes.i18n.t

/**
 * Halaman penuh "Diunduh": yang sedang diunduh / antre / gagal di atas, lalu paket playlist / album
 * (satu baris per paket: sampul + nama), lalu lagu yang diunduh sendiri. Semuanya bisa diputar tanpa internet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsPage(
    vm: MainViewModel,
    entries: List<DownloadEntry>,
    collections: List<DownloadedCollection>,
    progress: Map<String, Float>,
    failed: Set<String>,
    player: PlayerState,
    currentId: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    PageBackHandler(onBack = onBack)
    val search = rememberListSearch()
    ListSearchBackHandler(search)
    val bars = rememberBarsCollapse(countUnconsumed = true)
    // Lagu yang hanya bagian dari paket tidak ditampilkan sendiri; paketnya yang tampil.
    val allPending = entries.filter { !it.done && it.standalone }
    val allDone = entries.filter { it.done && it.standalone }.sortedByDescending { it.addedAt }.map { it.song }
    val allPacks = collections.sortedByDescending { it.addedAt }
    val q = search.query.trim()
    // Pencarian menyaring lagu (judul / artis) dan paket (nama playlist atau album).
    val pending = if (q.isEmpty()) allPending else allPending.filter { listOf(it.song).filterBySearch(q).isNotEmpty() }
    val done = allDone.filterBySearch(q)
    val packs = if (q.isEmpty()) allPacks else allPacks.filter { it.ref.title.contains(q, ignoreCase = true) }
    val byId = entries.associateBy { it.song.videoId }

    PageSurface {
        MiniPlayerScaffold(
            topBar = {
                SearchableTopBar(
                    title = "Diunduh",
                    onBack = onBack,
                    search = search,
                    canSearch = allPending.isNotEmpty() || allPacks.isNotEmpty() || allDone.isNotEmpty(),
                )
            },
            bars = bars,
            vm = vm,
            player = player,
            onOpenPlayer = onOpenPlayer,
        ) {
                if (allPending.isEmpty() && allPacks.isEmpty() && allDone.isEmpty()) {
                    CenterMessage(t("Belum ada lagu yang diunduh"))
                } else if (pending.isEmpty() && packs.isEmpty() && done.isEmpty()) {
                    CenterMessage(t("Tidak ada hasil"))
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = LocalTopInset.current, bottom = LocalBottomInset.current),
                    ) {
                        items(pending, key = { "p_" + it.song.videoId }) { e ->
                            PendingDownloadRow(
                                entry = e,
                                progress = progress[e.song.videoId],
                                failed = e.song.videoId in failed,
                                onRetry = { vm.download(e.song) },
                                onCancel = { vm.removeDownload(e.song) },
                            )
                        }
                        items(packs, key = { "c_" + it.ref.key }) { c ->
                            val doneCount = c.songs.count { byId[it.videoId]?.done == true }
                            val total = c.songs.size
                            val sizeBytes = c.songs.sumOf { s -> byId[s.videoId]?.takeIf { it.done }?.sizeBytes ?: 0L }
                            val partial = c.songs.sumOf { s ->
                                if (byId[s.videoId]?.done == true) 0.0 else (progress[s.videoId] ?: 0f).toDouble()
                            }.toFloat()
                            DownloadedCollectionRow(
                                collection = c,
                                doneCount = doneCount,
                                total = total,
                                sizeBytes = sizeBytes,
                                progress = if (total > 0) ((doneCount + partial) / total).coerceIn(0f, 1f) else 0f,
                                anyFailed = c.songs.any { it.videoId in failed },
                                onOpen = { vm.openDownloadedCollection(c) },
                                onRetry = { vm.retryCollectionDownload(c.ref.key) },
                                onRemove = { vm.removeCollectionDownload(c.ref.key) },
                            )
                        }
                        itemsIndexed(done, key = { _, s -> "d_" + s.videoId }) { index, song ->
                            SongRow(
                                modifier = smoothItem(),
                                song = song,
                                isCurrent = song.videoId == currentId,
                                onClick = { vm.playList(done, index) },
                                menu = { dismiss -> SongMenu(vm, song, dismiss) },
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun PendingDownloadRow(
    entry: DownloadEntry,
    progress: Float?,
    failed: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val status = when {
        failed -> t("Gagal, ketuk untuk ulangi")
        progress != null && progress > 0f -> t("Mengunduh %d%%", (progress * 100).toInt())
        progress != null -> t("Mengunduh…")
        else -> t("Menunggu…")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = failed, onClick = onRetry)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = status,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!failed && progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp),
                )
            }
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, contentDescription = t("Batalkan unduhan"))
        }
    }
}

/** Satu paket playlist / album: sampul + nama; ketuk untuk membuka halaman playlist-nya. */
@Composable
private fun DownloadedCollectionRow(
    collection: DownloadedCollection,
    doneCount: Int,
    total: Int,
    sizeBytes: Long,
    progress: Float,
    anyFailed: Boolean,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val complete = total > 0 && doneCount >= total
    val kind = if (collection.ref.kind == CollectionKind.ALBUM) t("Album") else t("Playlist")
    val status = when {
        complete -> t("%s • %d lagu", t(kind), total) + if (sizeBytes > 0) " • ${formatBytes(sizeBytes)}" else ""
        anyFailed -> t("%d/%d • Gagal, buka menu untuk ulangi", doneCount, total)
        else -> t("Mengunduh %d/%d", doneCount, total)
    }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = doneCount > 0, onClick = onOpen)
            .padding(start = 8.dp, end = 0.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            collection.info.thumbnail ?: collection.ref.thumbnail,
            Modifier.size(52.dp),
            RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = collection.ref.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = status,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (anyFailed && !complete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!complete && !anyFailed) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp),
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = t("Menu"))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = glassMenuColor(),
                border = glassEdge(),
            ) {
                if (!complete) {
                    DropdownMenuItem(
                        text = { Text("Ulangi unduhan") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                        onClick = { onRetry(); menuOpen = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Hapus unduhan") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { onRemove(); menuOpen = false },
                )
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024))
    else -> "${b / 1024} KB"
}
