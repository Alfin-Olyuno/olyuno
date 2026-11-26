package app.tunes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tunes.CollectionState
import app.tunes.MainViewModel
import app.tunes.innertube.CollectionInfo
import app.tunes.innertube.CollectionKind
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.SongItem
import app.tunes.player.PlayerState
import app.tunes.i18n.t

/**
 * Halaman penuh untuk satu koleksi (genre, artis, album, playlist). Bagian atas berupa "hero":
 * gambar besar yang memudar ke latar, lalu nama, pemilik, jumlah lagu • durasi, deskripsi, dan tombol
 * Putar. Panah kembali dan pencarian melayang di atas gambar. Mini player tetap tampil di bawah.
 */
@Composable
fun CollectionScreen(
    vm: MainViewModel,
    ref: CollectionRef,
    state: CollectionState,
    player: PlayerState,
    currentId: String?,
    onOpenPlayer: () -> Unit,
) {
    PageBackHandler(onBack = vm::closeCollection)
    var searching by remember(ref.key) { mutableStateOf(false) }
    var query by remember(ref.key) { mutableStateOf("") }
    // Didaftarkan setelah yang di atas, jadi saat mencari "kembali" menutup pencarian dulu.
    PageBackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    val bars = rememberBarsCollapse(countUnconsumed = true)
    // Playlist / album yang sudah diunduh sebagai paket: menu berubah jadi "Hapus unduhan".
    val packs by vm.downloadedCollections.collectAsStateWithLifecycle()
    val packDownloaded = packs.any { it.ref.key == ref.key }

    // Surface menahan sentuhan supaya tidak tembus ke layar di belakangnya.
    PageSurface {
        // topBar kosong: gambar hero naik sampai ke bawah status bar.
        MiniPlayerScaffold(
            topBar = null,
            bars = bars,
            vm = vm,
            player = player,
            onOpenPlayer = onOpenPlayer,
        ) {
            Box(Modifier.fillMaxSize()) {
                when (state) {
                    CollectionState.Loading -> Column(Modifier.fillMaxSize()) {
                        CollectionHeader(ref = ref, info = null, songs = null, onPlay = {}, onShuffle = {}, onAddAll = {}, onDownload = {}, downloaded = false, onRemoveDownload = {})
                        SongListSkeleton(modifier = Modifier.weight(1f))
                    }

                    is CollectionState.Error -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val isEmptyNotice = state.message == t("Tidak ada lagu di sini") ||
                            state.message == t("Belum ada lagu yang selesai diunduh")
                        if (!isEmptyNotice) {
                            Text(
                                t("Gagal memuat Koleksi"),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                        }
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        RetryTextButton(onClick = vm::retryCollection)
                    }

                    is CollectionState.Loaded -> {
                        val songs = state.songs
                        val shown = remember(songs, query) {
                            val q = query.trim()
                            if (q.isEmpty()) songs
                            else songs.filter { it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) }
                        }
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = LocalBottomInset.current),
                        ) {
                            item(key = "header") {
                                CollectionHeader(
                                    ref = ref,
                                    info = state.info,
                                    songs = songs,
                                    onPlay = { vm.playList(songs, 0) },
                                    onShuffle = { vm.playList(songs.shuffled(), 0) },
                                    onAddAll = { vm.addAllToQueue(songs) },
                                    onDownload = { vm.downloadCollection(ref, state.info, songs) },
                                    downloaded = packDownloaded,
                                    onRemoveDownload = { vm.removeCollectionDownload(ref.key) },
                                )
                            }
                            itemsIndexed(shown, key = { _, s -> s.videoId }) { index, song ->
                                SongRow(
                                    modifier = smoothItem(),
                                    song = song,
                                    isCurrent = song.videoId == currentId,
                                    onClick = { vm.playList(shown, index) },
                                    menu = { dismiss -> SongMenu(vm, song, dismiss) },
                                )
                            }
                        }
                    }
                }

                // Tombol melayang di atas gambar (tetap di tempat saat daftar di-scroll).
                val focus = remember { FocusRequester() }
                LaunchedEffect(searching) { if (searching) focus.requestFocus() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Cari di sini") },
                            leadingIcon = {
                                IconButton(onClick = { searching = false; query = "" }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("Tutup pencarian"))
                                }
                            },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Rounded.Close, contentDescription = t("Hapus"))
                                    }
                                }
                            },
                            shape = CircleShape,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.weight(1f).focusRequester(focus),
                        )
                    } else {
                        HeroIconButton(Icons.AutoMirrored.Rounded.ArrowBack, t("Kembali"), vm::closeCollection)
                        Spacer(Modifier.weight(1f))
                        if (state is CollectionState.Loaded) {
                            HeroIconButton(Icons.Rounded.Search, t("Cari")) { searching = true }
                        }
                    }
                }
            }
        }
    }
}

/** Tombol bulat semi-transparan supaya ikon tetap terbaca di atas gambar apa pun. */
@Composable
private fun HeroIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description)
    }
}

/**
 * Kepala halaman: gambar besar (memudar ke latar di bagian bawah), lalu teks dan tombol yang
 * ditarik naik sedikit supaya judul menimpa bagian bawah gambar. [info] / [songs] null = masih memuat.
 */
@Composable
private fun CollectionHeader(
    ref: CollectionRef,
    info: CollectionInfo?,
    songs: List<SongItem>?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddAll: () -> Unit,
    onDownload: () -> Unit,
    downloaded: Boolean,
    onRemoveDownload: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    val thumb = info?.thumbnail ?: ref.thumbnail
    // Genre / suasana / hasil kata kunci tidak punya gambar sendiri: tanpa hero (blok warna kosong yang besar),
    // judul langsung di bawah tombol Kembali / Cari. Hero tetap untuk Artis, Album, Playlist, dan Mix.
    val compact = ref.kind == app.tunes.innertube.CollectionKind.GENRE ||
        ref.kind == app.tunes.innertube.CollectionKind.MOOD ||
        ref.kind == app.tunes.innertube.CollectionKind.QUERY
    Column(Modifier.fillMaxWidth()) {
        if (compact) {
            // Ruang untuk tombol Kembali / Cari yang melayang di atas (44dp + jarak atas dan bawah 8dp) + sedikit napas.
            Spacer(Modifier.fillMaxWidth().statusBarsPadding().height(68.dp))
        } else Box(Modifier.fillMaxWidth().aspectRatio(if (thumb != null) 1f else 1.7f)) {
            if (thumb != null) {
                Artwork(thumb, Modifier.fillMaxSize(), RectangleShape)
            } else {
                // Genre / kata kunci tidak punya gambar: pakai warna aksen dari YouTube Music kalau ada.
                val tint = ref.accent?.let { Color(it.toInt()).copy(alpha = 1f) } ?: MaterialTheme.colorScheme.primaryContainer
                Box(Modifier.fillMaxSize().background(tint))
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to bg)),
            )
        }

        Column(
            modifier = (
                if (compact) {
                    Modifier
                } else {
                    Modifier.layout { measurable, constraints ->
                        // Tarik ke atas 64dp: tinggi berkurang, posisi naik, sehingga menimpa gambar.
                        val placeable = measurable.measure(constraints)
                        val pull = 64.dp.roundToPx()
                        layout(placeable.width, (placeable.height - pull).coerceAtLeast(0)) {
                            placeable.placeRelative(0, -pull)
                        }
                    }
                }
                )
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = ref.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                ),
            )

            info?.owner?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (songs != null) {
                val totalSec = songs.sumOf { it.durationSec }
                val summary = buildString {
                    append(t("%d lagu", songs.size))
                    if (totalSec > 0) append(" • ${formatTime(totalSec * 1000L)}")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            info?.description?.let { desc ->
                var expanded by remember(desc) { mutableStateOf(false) }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = desc,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val playSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Button(
                    onClick = onPlay,
                    enabled = songs != null,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = bg,
                    ),
                    interactionSource = playSource,
                    modifier = Modifier.weight(1f).height(56.dp).pressScale(playSource, 0.96f),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Putar", style = MaterialTheme.typography.titleMedium)
                }

                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    val moreSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .pressScale(moreSource, 0.92f)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .pressClickable(moreSource, enabled = songs != null) { menuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = t("Menu"))
                    }
                    DropdownMenu(
    expanded = menuOpen,
    onDismissRequest = { menuOpen = false },
    containerColor = glassMenuColor(),
    border = glassEdge(),
) {
                        DropdownMenuItem(
                            text = { Text("Putar acak") },
                            leadingIcon = { Icon(Icons.Rounded.Shuffle, null) },
                            onClick = { onShuffle(); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Tambah semua ke antrean") },
                            leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) },
                            onClick = { onAddAll(); menuOpen = false },
                        )
                        if (downloaded) {
                            DropdownMenuItem(
                                text = { Text("Hapus unduhan") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { onRemoveDownload(); menuOpen = false },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Unduh") },
                                leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                onClick = { onDownload(); menuOpen = false },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Baris artis / album / playlist di hasil pencarian. Artis berbentuk lingkaran. */
@Composable
fun EntityRow(ref: CollectionRef, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            ref.thumbnail,
            Modifier.size(52.dp),
            if (ref.kind == CollectionKind.ARTIST) CircleShape else RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = ref.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            ref.subtitle?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
