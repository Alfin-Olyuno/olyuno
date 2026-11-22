package app.tunes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.data.LocalPlaylist
import app.tunes.i18n.t
import app.tunes.innertube.SongItem
import app.tunes.player.PlayerState

// ------------------------------------------------------------ dialog nama

/** Dialog satu kolom untuk nama playlist (buat baru / ganti nama). */
@Composable
private fun PlaylistNameDialog(
    title: String,
    confirmText: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = {
            DialogDim()
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                singleLine = true,
                label = { Text(t("Nama playlist")) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Batal")) } },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(50),
            ) { Text(confirmText) }
        },
    )
}

// ------------------------------------------------------------ pemilih playlist

/**
 * Pemilih "Tambah ke playlist". Terbuka bila [MainViewModel.playlistPicker] berisi lagu: pilih playlist yang ada atau
 * buat yang baru (langsung berisi lagu itu). Digambar sekali di OlyunoRoot supaya bisa dipanggil dari menu lagu mana pun.
 */
@Composable
fun PlaylistPickerHost(vm: MainViewModel) {
    val songs by vm.playlistPicker.collectAsStateWithLifecycle()
    val picked = songs ?: return
    val playlists by vm.localPlaylists.collectAsStateWithLifecycle()
    var creating by remember(picked) { mutableStateOf(false) }

    if (creating || playlists.isEmpty()) {
        PlaylistNameDialog(
            title = t("Playlist baru"),
            confirmText = t("Buat"),
            initial = "",
            onConfirm = { name ->
                vm.createPlaylist(name, picked)
                vm.closePlaylistPicker()
            },
            onDismiss = vm::closePlaylistPicker,
        )
        return
    }
    AlertDialog(
        onDismissRequest = vm::closePlaylistPicker,
        shape = RoundedCornerShape(24.dp),
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = {
            DialogDim()
            Text(t("Tambah ke playlist"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                PickerRow(icon = Icons.Rounded.Add, label = t("Playlist baru"), sub = null) { creating = true }
                playlists.forEach { pl ->
                    PickerRow(
                        icon = Icons.Rounded.QueueMusic,
                        label = pl.name,
                        sub = t("%d lagu", pl.songs.size),
                    ) {
                        vm.addToPlaylist(pl, picked)
                        vm.closePlaylistPicker()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::closePlaylistPicker) { Text(t("Batal")) } },
    )
}

@Composable
private fun PickerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, sub: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------ halaman daftar playlist

/** Halaman "Playlist saya": buat playlist baru, buka, ganti nama, atau hapus. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LocalPlaylistsPage(
    vm: MainViewModel,
    playlists: List<LocalPlaylist>,
    player: PlayerState,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    PageBackHandler(onBack = onBack)
    val search = rememberListSearch()
    ListSearchBackHandler(search)
    val shown = remember(playlists, search.query) {
        val q = search.query.trim()
        if (q.isEmpty()) playlists else playlists.filter { it.name.contains(q, ignoreCase = true) }
    }
    val bars = rememberBarsCollapse(countUnconsumed = true)
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LocalPlaylist?>(null) }
    var deleting by remember { mutableStateOf<LocalPlaylist?>(null) }

    PageSurface {
        MiniPlayerScaffold(
            topBar = {
                SearchableTopBar(
                    title = t("Playlist saya"),
                    onBack = onBack,
                    search = search,
                    canSearch = playlists.isNotEmpty(),
                )
            },
            bars = bars,
            vm = vm,
            player = player,
            onOpenPlayer = onOpenPlayer,
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = LocalTopInset.current, bottom = LocalBottomInset.current),
            ) {
                item(key = "create") {
                    LibraryMenuRow(Icons.Rounded.Add, t("Playlist baru"), onClick = { showCreate = true })
                }
                if (playlists.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            t("Belum ada playlist. Buat satu, lalu tambahkan lagu lewat menu titik tiga."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
                if (playlists.isNotEmpty() && shown.isEmpty()) {
                    item(key = "noresult") {
                        Text(
                            t("Tidak ada hasil"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
                items(shown, key = { it.id }) { pl ->
                    PlaylistRow(
                        modifier = smoothItem(),
                        playlist = pl,
                        onClick = { onOpen(pl.id) },
                        onRename = { renaming = pl },
                        onDelete = { deleting = pl },
                    )
                }
            }
        }
    }

    if (showCreate) {
        PlaylistNameDialog(
            title = t("Playlist baru"),
            confirmText = t("Buat"),
            initial = "",
            onConfirm = {
                vm.createPlaylist(it)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
    renaming?.let { pl ->
        PlaylistNameDialog(
            title = t("Ganti nama"),
            confirmText = t("Simpan"),
            initial = pl.name,
            onConfirm = {
                vm.renamePlaylist(pl.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { pl ->
        ConfirmDeleteDialog(
            title = t("Hapus playlist?"),
            message = t("\"%s\" akan dihapus. Lagunya sendiri tidak ikut terhapus.", pl.name),
            onConfirm = {
                vm.deletePlaylist(pl.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun PlaylistRow(
    modifier: Modifier,
    playlist: LocalPlaylist,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(playlist.songs.firstOrNull()?.thumbnail, Modifier.size(52.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                t("%d lagu", playlist.songs.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.foundation.layout.Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = t("Menu"))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = glassMenuColor(),
                border = glassEdge(),
            ) {
                DropdownMenuItem(
                    text = { Text(t("Ganti nama")) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(t("Hapus playlist")) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

// ------------------------------------------------------------ halaman isi playlist

/** Isi satu playlist: Putar / Acak, lalu daftar lagu dengan menu (naikkan, turunkan, hapus dari playlist). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LocalPlaylistDetailPage(
    vm: MainViewModel,
    playlist: LocalPlaylist?,
    player: PlayerState,
    currentId: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    PageBackHandler(onBack = onBack)
    val search = rememberListSearch()
    ListSearchBackHandler(search)
    val bars = rememberBarsCollapse(countUnconsumed = true)
    // Playlist dihapus saat halaman ini terbuka: kembali ke daftar.
    LaunchedEffect(playlist == null) { if (playlist == null) onBack() }
    val songs: List<SongItem> = playlist?.songs.orEmpty()
    val shown = remember(songs, search.query) { songs.filterBySearch(search.query) }
    val filtering = search.query.isNotBlank()

    PageSurface {
        MiniPlayerScaffold(
            topBar = {
                SearchableTopBar(
                    title = playlist?.name.orEmpty(),
                    onBack = onBack,
                    search = search,
                    canSearch = songs.isNotEmpty(),
                )
            },
            bars = bars,
            vm = vm,
            player = player,
            onOpenPlayer = onOpenPlayer,
        ) {
            if (playlist == null) return@MiniPlayerScaffold
            if (songs.isEmpty()) {
                CenterMessage(t("Playlist ini masih kosong. Tambahkan lagu lewat menu titik tiga."))
            } else if (shown.isEmpty()) {
                CenterMessage(t("Tidak ada hasil"))
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = LocalTopInset.current, bottom = LocalBottomInset.current),
                ) {
                    item(key = "actions") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = { vm.playList(songs, 0) },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(t("Putar"))
                            }
                            FilledTonalButton(
                                onClick = { vm.playList(songs.shuffled(), 0) },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(t("Acak"))
                            }
                        }
                    }
                    itemsIndexed(shown, key = { _, s -> s.videoId }) { index, song ->
                        SongRow(
                            modifier = smoothItem(),
                            song = song,
                            isCurrent = song.videoId == currentId,
                            onClick = { vm.playList(shown, index) },
                            menu = { dismiss ->
                                SongMenu(vm, song, dismiss)
                                if (!filtering && index > 0) {
                                    DropdownMenuItem(
                                        text = { Text(t("Naikkan")) },
                                        leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
                                        onClick = { vm.moveInPlaylist(playlist.id, song.videoId, -1); dismiss() },
                                    )
                                }
                                if (!filtering && index < songs.lastIndex) {
                                    DropdownMenuItem(
                                        text = { Text(t("Turunkan")) },
                                        leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
                                        onClick = { vm.moveInPlaylist(playlist.id, song.videoId, 1); dismiss() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(t("Hapus dari playlist")) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { vm.removeFromPlaylist(playlist.id, song.videoId); dismiss() },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
