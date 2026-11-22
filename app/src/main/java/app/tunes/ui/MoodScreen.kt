package app.tunes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tunes.CollectionState
import app.tunes.MainViewModel
import app.tunes.i18n.t
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.SongItem
import app.tunes.player.PlayerState

// Ukuran kartu sama dengan "Sedang trending" di beranda: 2 baris, tumbuh ke kanan, bisa digeser.
private val MOOD_CARD_HEIGHT = 204.dp
private val MOOD_CARD_WIDTH = 140.dp

/**
 * Halaman genre cepat (Feel good, Sad, dst.): bagian "Lagu" lalu "Playlist", masing-masing 2 baris kartu
 * yang tumbuh ke kanan dan bisa digeser (seperti "Sedang trending" di beranda).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodScreen(
    vm: MainViewModel,
    ref: CollectionRef,
    state: CollectionState,
    player: PlayerState,
    currentId: String?,
    onOpenPlayer: () -> Unit,
) {
    PageBackHandler(onBack = vm::closeCollection)
    val bars = rememberBarsCollapse(countUnconsumed = true)

    PageSurface {
        // Bar judul agak tembus (sama seperti halaman Pengaturan dll.); isi digulir di belakangnya.
        MiniPlayerScaffold(
            topBar = {
                TopAppBar(
                    colors = translucentBarColors(),
                    title = { Text(ref.title) },
                    navigationIcon = {
                        IconButton(onClick = vm::closeCollection) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("Kembali"))
                        }
                    },
                )
            },
            bars = bars,
            vm = vm,
            player = player,
            onOpenPlayer = onOpenPlayer,
        ) {
            when (state) {
                CollectionState.Loading -> MoodSkeleton()

                is CollectionState.Error -> CenterMessageWithRetry(state.message, vm::retryCollection)

                is CollectionState.Loaded -> {
                    val songs = state.songs
                    val playlists = state.playlists
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = LocalTopInset.current,
                            bottom = 16.dp + LocalBottomInset.current,
                        ),
                    ) {
                        item(key = "h_songs") { MoodSectionTitle(t("Lagu")) }
                        item(key = "songs") {
                            MoodRows(songs.size) {
                                gridItems(songs, key = { "s_" + it.videoId }) { song ->
                                    Box(Modifier.width(MOOD_CARD_WIDTH)) {
                                        MoodSongCard(
                                            song = song,
                                            isCurrent = song.videoId == currentId,
                                            onClick = { vm.playList(songs, songs.indexOf(song).coerceAtLeast(0)) },
                                            vm = vm,
                                        )
                                    }
                                }
                            }
                        }
                        if (playlists.isNotEmpty()) {
                            item(key = "h_playlists") { MoodSectionTitle(t("Playlist")) }
                            item(key = "playlists") {
                                MoodRows(playlists.size) {
                                    gridItems(playlists, key = { "p_" + it.key }) { pl ->
                                        Box(Modifier.width(MOOD_CARD_WIDTH)) {
                                            MoodPlaylistCard(pl) { vm.openCollection(pl) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Dua baris kartu yang tumbuh ke kanan; digeser bila layar tidak cukup. */
@Composable
private fun MoodRows(count: Int, content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    // Jumlah baris mengikuti jumlah kartu (maks. 2): satu kartu = satu baris, tanpa ruang kosong.
    val rows = count.coerceIn(1, 2)
    LazyHorizontalGrid(
        rows = GridCells.Fixed(rows),
        modifier = Modifier.fillMaxWidth().height(MOOD_CARD_HEIGHT * rows + 8.dp * (rows - 1)),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun CenterMessageWithRetry(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(top = LocalTopInset.current),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val isEmptyNotice = message == t("Tidak ada lagu di sini")
        if (!isEmptyNotice) {
            Text(
                t("Gagal memuat Genre"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        RetryTextButton(onClick = onRetry)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MoodSongCard(
    song: SongItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    vm: MainViewModel,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        // Padding berada DI DALAM clip: kalau teks menempel ke tepi, sudut membulat bawah memotong
        // huruf pertama ("L" pada "Lagu" tinggal serpihan).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 10.dp),
        ) {
            Box {
                Artwork(
                    url = song.thumbnail,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    shape = RoundedCornerShape(12.dp),
                )
                // Ikon putar kecil di pojok kiri atas (seperti di halaman referensi).
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = song.title,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (song.artist.isBlank()) t("Lagu") else t("Lagu • %s", song.artist),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (menuOpen) {
            SongMenuSheet(song = song, onDismiss = { menuOpen = false }) { dismiss ->
                SongMenu(vm, song, dismiss)
            }
        }
    }
}

@Composable
private fun MoodPlaylistCard(ref: CollectionRef, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 10.dp),
    ) {
        Artwork(
            url = ref.thumbnail,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = ref.title,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = ref.subtitle?.takeIf { it.isNotBlank() } ?: t("Playlist"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Kerangka saat memuat: dua judul bagian, masing-masing 2 baris kartu, bentuknya sama dengan aslinya. */
@Composable
private fun MoodSkeleton() {
    ProvideShimmer {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = LocalTopInset.current)
                .semantics { contentDescription = t("Memuat") },
        ) {
            repeat(2) {
                ShimmerBox(
                    Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp).width(64.dp).height(16.dp),
                    RoundedCornerShape(8.dp),
                )
                repeat(2) {
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(3) {
                            Column(Modifier.width(124.dp).padding(bottom = 12.dp)) {
                                ShimmerBox(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(12.dp))
                                Spacer(Modifier.height(8.dp))
                                ShimmerBox(Modifier.fillMaxWidth(0.8f).height(13.dp), RoundedCornerShape(7.dp))
                                Spacer(Modifier.height(6.dp))
                                ShimmerBox(Modifier.fillMaxWidth(0.5f).height(11.dp), RoundedCornerShape(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
