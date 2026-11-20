package app.tunes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.innertube.SongItem
import app.tunes.i18n.t

/** Daftar lagu sederhana, dipakai untuk "Disukai" dan "Riwayat". */
@Composable
fun SongListScreen(
    title: String,
    songs: List<SongItem>,
    emptyText: String,
    vm: MainViewModel,
    currentId: String?,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        if (songs.isEmpty()) {
            CenterMessage(emptyText)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LocalBottomInset.current),
            ) {
                itemsIndexed(songs, key = { _, s -> s.videoId }) { index, song ->
                    SongRow(
                        modifier = smoothItem(),
                        song = song,
                        isCurrent = song.videoId == currentId,
                        onClick = { vm.playList(songs, index) },
                        menu = { dismiss -> SongMenu(vm, song, dismiss) },
                    )
                }
            }
        }
    }
}

/** Halaman tab Koleksi: judul, lalu daftar menu polos (Terakhir dilihat, Disukai, Diunduh, Lagu perangkat). */
@Composable
fun CollectionHomeScreen(
    onOpenRecent: () -> Unit,
    onOpenLiked: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenDeviceSongs: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(top = LocalTabTopInset.current)) {
        Text(
            text = t("Koleksi"),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        // Daftar polos: ikon tanpa lingkaran / kartu, label, dan panah di kanan; latar halaman sendiri.
        androidx.compose.foundation.layout.Column(Modifier.padding(top = 8.dp)) {
            MenuItem(androidx.compose.material.icons.Icons.Rounded.History, t("Terakhir dilihat"), onOpenRecent)
            MenuItem(androidx.compose.material.icons.Icons.Rounded.Favorite, t("Disukai"), onOpenLiked)
            MenuItem(androidx.compose.material.icons.Icons.Rounded.Download, t("Diunduh"), onOpenDownloads)
            MenuItem(androidx.compose.material.icons.Icons.Rounded.PhoneAndroid, t("Lagu perangkat"), onOpenDeviceSongs)
        }
    }
}

@Composable
fun LibraryMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(44.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Halaman penuh berisi daftar lagu (Terakhir dilihat / Disukai) dengan panah kembali di kiri atas. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SongListPage(
    title: String,
    songs: List<SongItem>,
    emptyText: String,
    vm: MainViewModel,
    player: app.tunes.player.PlayerState,
    currentId: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    PageBackHandler(onBack = onBack)
    val bars = rememberBarsCollapse(countUnconsumed = true)
    PageSurface {
        MiniPlayerScaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(
                    colors = translucentBarColors(),
                    title = { Text(title) },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = onBack) {
                            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("Kembali"))
                        }
                    },
                )
            },
            bars = bars,
            vm = vm,
            player = player,
            onOpenPlayer = onOpenPlayer,
        ) {
                if (songs.isEmpty()) {
                    CenterMessage(emptyText)
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = LocalTopInset.current, bottom = LocalBottomInset.current),
                    ) {
                        itemsIndexed(songs, key = { _, s -> s.videoId }) { index, song ->
                            SongRow(
                                modifier = smoothItem(),
                                song = song,
                                isCurrent = song.videoId == currentId,
                                onClick = { vm.playList(songs, index) },
                                menu = { dismiss -> SongMenu(vm, song, dismiss) },
                            )
                        }
                    }
                }
        }
    }
}
