package app.tunes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.SearchState
import app.tunes.innertube.SEARCH_SUGGESTION_COUNT
import app.tunes.innertube.SearchHit
import app.tunes.innertube.SongItem
import app.tunes.i18n.t

/** Jumlah riwayat pencarian yang tampil saat kolom cari tidak sedang difokus. */
private const val COLLAPSED_SEARCH_HISTORY = 5

@Composable
fun SearchScreen(vm: MainViewModel, currentId: String?) {
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.search.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val searchHistory by vm.searchHistory.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var confirmClear by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<SongItem?>(null) }
    var focused by remember { mutableStateOf(false) }

    // Tombol kembali setelah keyboard tertutup: lepas fokus supaya "Terakhir diputar" muncul lagi.
    BackHandler(enabled = focused) { focus.clearFocus() }

    if (confirmClear) {
        ConfirmDeleteDialog(
            title = t("Hapus riwayat?"),
            message = t("Semua lagu di Terakhir diputar akan dihapus."),
            onConfirm = {
                confirmClear = false
                vm.clearHistory()
            },
            onDismiss = { confirmClear = false },
        )
    }

    pendingRemove?.let { song ->
        ConfirmDeleteDialog(
            title = t("Hapus riwayat ini?"),
            message = t("\"%s\" akan dihapus dari Terakhir diputar.", song.title),
            onConfirm = {
                pendingRemove = null
                vm.removeFromHistory(song)
            },
            onDismiss = { pendingRemove = null },
        )
    }

    Column(Modifier.fillMaxSize().padding(top = LocalTabTopInset.current)) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .onFocusChanged { focused = it.isFocused },
            placeholder = { Text("Cari lagu, artis…") },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = t("Hapus"))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                vm.submitSearch()
            }),
        )

        val typing = focused && query.isNotBlank()
        // Sudah mulai mengetik: tampilkan saran pencarian (maks. 5). Gagal / offline: cocokkan dengan riwayat pencarian.
        if (typing) {
            val q = query.trim()
            val shown = remember(suggestions, searchHistory, q) {
                suggestions.ifEmpty {
                    searchHistory.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }
                }.take(SEARCH_SUGGESTION_COUNT)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
                items(shown, key = { "suggest_$it" }) { text ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                focus.clearFocus()
                                vm.searchFromHistory(text)
                            }
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        // Panah: isi kolom cari dengan saran ini tanpa langsung mencari (untuk diedit lagi).
                        IconButton(onClick = { vm.onQueryChange("$text ") }) {
                            Icon(Icons.Rounded.NorthWest, contentDescription = t("Pakai saran ini"))
                        }
                    }
                }
            }
        } else when (val s = state) {
            SearchState.Idle -> when {
                // Sedang mengetik / mengetuk kolom cari: hanya riwayat pencarian.
                focused -> if (searchHistory.isEmpty()) {
                    CenterMessage(t("Belum ada riwayat pencarian"))
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
                        searchHistorySection(
                            entries = searchHistory,
                            onClick = { q ->
                                focus.clearFocus()
                                vm.searchFromHistory(q)
                            },
                            onRemove = vm::removeSearchHistory,
                            onClearAll = vm::clearSearchHistory,
                        )
                    }
                }

                searchHistory.isEmpty() && history.isEmpty() -> CenterMessage(t("Cari musik apa saja"))

                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
                    if (searchHistory.isNotEmpty()) {
                        searchHistorySection(
                            entries = searchHistory.take(COLLAPSED_SEARCH_HISTORY),
                            onClick = vm::searchFromHistory,
                            onRemove = vm::removeSearchHistory,
                            onClearAll = vm::clearSearchHistory,
                        )
                    }
                    if (history.isNotEmpty()) {
                        item(key = "recent_header") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = t("Terakhir diputar"),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { confirmClear = true }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = t("Hapus riwayat"))
                                }
                            }
                        }
                        item(key = "recent_row") {
                            // Terbaru di kiri, makin ke kanan makin lama; digeser kalau tidak muat.
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(history, key = { _, song -> song.videoId }) { index, song ->
                                    RecentSongCard(
                                        song = song,
                                        isCurrent = song.videoId == currentId,
                                        onClick = { vm.playList(history, index) },
                                        onRequestDelete = { pendingRemove = song },
                                        vm = vm,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SearchState.Loading -> SongListSkeleton()
            is SearchState.Error -> CenterMessage(s.message)
            is SearchState.Results -> {
                val r = s.results
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
                    // Teratas
                    if (r.top.isNotEmpty()) {
                        item(key = "h_top") { ResultHeader(t("Teratas")) }
                        items(r.top, key = { "top_${it.key}" }) { hit ->
                            when (hit) {
                                is SearchHit.Track -> SongRow(
                                    song = hit.song,
                                    isCurrent = hit.song.videoId == currentId,
                                    onClick = { vm.playSong(hit.song) },
                                    menu = { dismiss -> SongMenu(vm, hit.song, dismiss) },
                                )
                                is SearchHit.Entity -> EntityRow(hit.ref) { vm.openCollection(hit.ref) }
                            }
                        }
                    }
                    // Lainnya (lagu)
                    if (r.songs.isNotEmpty()) {
                        item(key = "h_songs") { ResultHeader(t("Lainnya")) }
                        items(r.songs, key = { "song_${it.videoId}" }) { song ->
                            SongRow(
                                song = song,
                                isCurrent = song.videoId == currentId,
                                onClick = { vm.playSong(song) },
                                menu = { dismiss -> SongMenu(vm, song, dismiss) },
                            )
                        }
                    }
                    // Artis
                    if (r.artists.isNotEmpty()) {
                        item(key = "h_artists") { ResultHeader(t("Artis")) }
                        items(r.artists, key = { "artist_${it.browseId}" }) { ref ->
                            EntityRow(ref) { vm.openCollection(ref) }
                        }
                    }
                    // Playlist
                    if (r.playlists.isNotEmpty()) {
                        item(key = "h_playlists") { ResultHeader(t("Playlist")) }
                        items(r.playlists, key = { "playlist_${it.browseId}" }) { ref ->
                            EntityRow(ref) { vm.openCollection(ref) }
                        }
                    }
                    // Album
                    if (r.albums.isNotEmpty()) {
                        item(key = "h_albums") { ResultHeader(t("Album")) }
                        items(r.albums, key = { "album_${it.browseId}" }) { ref ->
                            EntityRow(ref) { vm.openCollection(ref) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Header "Riwayat pencarian" + daftar kata kunci, dari atas (terbaru) ke bawah (terlama). */
private fun androidx.compose.foundation.lazy.LazyListScope.searchHistorySection(
    entries: List<String>,
    onClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    item(key = "search_header") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = t("Riwayat pencarian"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearAll) { Text("Hapus semua") }
        }
    }
    items(entries, key = { "search_$it" }) { q ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onClick(q) }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = q,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onRemove(q) }) {
                Icon(Icons.Rounded.Close, contentDescription = t("Hapus dari riwayat"))
            }
        }
    }
}

/**
 * Kartu lagu untuk deretan horizontal "Terakhir diputar": sampul di atas, judul (2 baris) & artis di bawah.
 * Isi kartu diberi jarak 6dp dari tepi supaya teks tidak terpotong sudut membulat; tombol titik tiga
 * melayang di pojok sampul sehingga judul bisa memakai seluruh lebar kartu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentSongCard(
    song: SongItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
    vm: MainViewModel,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 10.dp),
    ) {
        Box {
            Artwork(
                song.thumbnail,
                Modifier.fillMaxWidth().aspectRatio(1f),
                RoundedCornerShape(14.dp),
            )
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = t("Menu"),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
    expanded = menuOpen,
    onDismissRequest = { menuOpen = false },
    containerColor = glassMenuColor(),
    border = glassEdge(),
) {
                    SongMenu(vm, song) { menuOpen = false }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = song.title,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = song.artist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(top = LocalTopInset.current).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
