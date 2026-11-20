package app.tunes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.quickGenres
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.innertube.SongItem
import kotlinx.coroutines.delay
import java.util.Calendar
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign
import app.tunes.i18n.t

// ------------------------------------------------------------------ sapaan

/** Sapaan menurut jam di perangkat: 05.00 pagi, 12.00 siang, 15.00 sore, 18.00 malam (sampai 04.59). */
private fun greetingFor(nowMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    return when (cal.get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> t("Selamat pagi")
        in 12..14 -> t("Selamat siang")
        in 15..17 -> t("Selamat sore")
        else -> t("Selamat malam")
    }
}

// ----------------------------------------------------------------- layar

/** Jumlah lagu per kolom di "Pilihan cepat". */
private const val QUICK_PICKS_PER_COLUMN = 4

@Composable
fun HomeScreen(vm: MainViewModel, mostPlayed: List<SongItem>, currentId: String?) {
    // Cek ulang tiap menit supaya sapaan ikut berganti saat jam berubah.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val greeting = greetingFor(now)
    val quickPicks by vm.quickPicks.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val trending by vm.trending.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = LocalTabTopInset.current + 8.dp, bottom = 16.dp + LocalBottomInset.current),
    ) {
        item(key = "greeting") {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }

        // ---- Genre cepat: kotak kecil berjejer ke kanan, bisa digeser; ketuk membuka halaman genre.
        item(key = "mood_row") {
            LazyRow(
                modifier = Modifier.padding(top = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(quickGenres, key = { it.key }) { g ->
                    MoodChip(g, onClick = { vm.openCollection(g) })
                }
            }
        }

        // ---- Pilihan cepat: kolom isi 4 lagu (atas ke bawah), kolom berikutnya ke kanan; bisa digeser.
        item(key = "quick_header") { HomeSectionTitle(t("Pilihan cepat")) }
        item(key = "quick_row") {
            val picks = quickPicks
            when {
                picks == null -> QuickPicksSkeleton()
                picks.isEmpty() -> Text(
                    text = t("Pilihan cepat belum bisa dimuat. Periksa koneksi internetmu."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { vm.loadQuickPicks(force = true) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                else -> {
                    val columns = remember(picks) { picks.take(20).chunked(QUICK_PICKS_PER_COLUMN) }
                    // Lebar kolom ~ 88% layar supaya kolom berikutnya mengintip dan jelas bisa digeser.
                    // Landscape: dibatasi 420dp supaya baris lagu tidak melebar sepanjang layar.
                    val colWidth = minOf(LocalConfiguration.current.screenWidthDp * 0.88f, 420f).dp
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        items(columns.size, key = { columns[it].first().videoId }) { c ->
                            Column(Modifier.width(colWidth)) {
                                columns[c].forEachIndexed { r, song ->
                                    val index = c * QUICK_PICKS_PER_COLUMN + r
                                    SongRow(
                                        song = song,
                                        isCurrent = song.videoId == currentId,
                                        onClick = { vm.playList(picks, index) },
                                        menu = { dismiss -> SongMenu(vm, song, dismiss) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Paling sering diputar: disembunyikan sepenuhnya kalau belum ada data.
        if (mostPlayed.isNotEmpty()) {
            item(key = "most_header") { HomeSectionTitle(t("Paling sering diputar")) }
            item(key = "most_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(mostPlayed, key = { it.videoId }) { song ->
                        Box(Modifier.width(140.dp)) {
                            HomeSongCard(
                                song = song,
                                isCurrent = song.videoId == currentId,
                                onClick = { vm.playList(mostPlayed, mostPlayed.indexOf(song).coerceAtLeast(0)) },
                                vm = vm,
                            )
                        }
                    }
                }
            }
        }

        // ---- Sedang trending: kartu lagu yang bisa digeser ke kanan, tepat di bawah "Paling sering diputar".
        item(key = "trending_header") { HomeSectionTitle(t("Sedang trending")) }
        item(key = "trending_row") {
            val list = trending
            when {
                list == null -> TrendingSkeleton()
                list.isEmpty() -> Text(
                    text = t("Lagu trending belum bisa dimuat. Ketuk untuk coba lagi."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { vm.loadTrending(force = true) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                else -> LazyHorizontalGrid(
                    // 2 baris lagu, tumbuh ke kanan; digeser kalau tidak muat.
                    rows = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().height(TRENDING_CARD_HEIGHT * 2 + 8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(list, key = { "t_" + it.videoId }) { song ->
                        Box(Modifier.width(140.dp)) {
                            HomeSongCard(
                                song = song,
                                isCurrent = song.videoId == currentId,
                                onClick = { vm.playList(list, list.indexOf(song).coerceAtLeast(0)) },
                                vm = vm,
                            )
                        }
                    }
                }
            }
        }

        // ---- Genre: ketuk untuk membuka halaman penuh berisi daftar lagunya.
        item(key = "genre_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                HomeSectionTitle(t("Genre"))
                SmoothIconButton(onClick = vm::shuffleGenres, modifier = Modifier.padding(end = 8.dp, top = 12.dp)) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = t("Acak genre"))
                }
            }
        }
        item(key = "genre_grid") {
            val list = genres
            if (list == null) {
                GenreSkeleton()
            } else {
                // 3 baris, tumbuh ke kanan; digeser kalau tidak muat.
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(GENRE_CHIP_HEIGHT * 3 + 8.dp * 2),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(list, key = { it.key }) { g ->
                        GenreChip(g, onClick = { vm.openCollection(g) })
                    }
                }
            }
        }
    }
}

/** Tinggi satu kartu lagu (sampul 124dp + judul 2 baris + artis + padding). */
private val TRENDING_CARD_HEIGHT = 204.dp
private val GENRE_CHIP_HEIGHT = 52.dp
// Semua chip genre berukuran sama persis (tidak mengikuti panjang teks).
private val GENRE_CHIP_WIDTH = 132.dp

@Composable
private fun HomeSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun GenreChip(genre: CollectionRef, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .size(width = GENRE_CHIP_WIDTH, height = GENRE_CHIP_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = t(genre.title),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** Kotak kecil bersudut membulat (bukan lancip) untuk genre cepat. */
@Composable
private fun MoodChip(genre: CollectionRef, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = t(genre.title),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun QuickPicksSkeleton() {
    ProvideShimmer {
        Column { repeat(QUICK_PICKS_PER_COLUMN) { SongRowSkeleton(it) } }
    }
}

@Composable
private fun TrendingSkeleton() {
    ProvideShimmer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) {
                Row(
                    Modifier.padding(horizontal = 20.dp).height(TRENDING_CARD_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(3) {
                        Column(Modifier.width(124.dp).padding(top = 6.dp)) {
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

@Composable
private fun GenreSkeleton() {
    ProvideShimmer {
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(4) { ShimmerBox(Modifier.size(width = GENRE_CHIP_WIDTH, height = GENRE_CHIP_HEIGHT), RoundedCornerShape(14.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSongCard(
    song: SongItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    vm: MainViewModel,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
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
            Artwork(
                url = song.thumbnail,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shape = RoundedCornerShape(12.dp),
            )
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
                text = song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
