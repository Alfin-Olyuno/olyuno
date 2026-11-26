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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Refresh
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.tunes.MixStatus
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.ui.graphics.Brush
import app.tunes.innertube.AccountInfo
import app.tunes.innertube.MixPlaylist
import app.tunes.innertube.NewRelease
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
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tunes.MainViewModel
import app.tunes.innertube.SongItem
import kotlinx.coroutines.delay
import java.util.Calendar
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign
import app.tunes.i18n.t
import app.tunes.data.StatArtist

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
fun HomeScreen(
    vm: MainViewModel,
    mostPlayed: List<SongItem>,
    currentId: String?,
    onOpenLiked: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenStreak: () -> Unit = {},
) {
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
    val moods by vm.moods.collectAsStateWithLifecycle()
    val similar by vm.similar.collectAsStateWithLifecycle()
    val replayAll by vm.replay.collectAsStateWithLifecycle()
    val newReleases by vm.newReleases.collectAsStateWithLifecycle()
    // Yang sudah tampil di "Paling sering diputar" tidak diulang di "Dengarkan lagi".
    val replay = remember(replayAll, mostPlayed) {
        val shown = mostPlayed.mapTo(HashSet()) { it.videoId }
        replayAll.filter { it.videoId !in shown }
    }
    val trending by vm.trending.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val topArtists by vm.topArtists.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val mixOwner by vm.mixOwner.collectAsStateWithLifecycle()
    val mixPlaylists by vm.mixPlaylists.collectAsStateWithLifecycle()
    val mixStatus by vm.mixStatus.collectAsStateWithLifecycle()
    val likedSongs by vm.liked.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    // Kembali ke aplikasi / Beranda: Mix dimuat ulang kalau sudah lama atau sebelumnya gagal.
    LifecycleResumeEffect(account?.name) {
        vm.refreshMix()
        vm.refreshStreak()
        onPauseOrDispose { }
    }

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

        // ---- Streak: kartu kecil tepat di bawah sapaan; ketuk membuka halaman detail.
        item(key = "streak") { StreakHomeCard(streak, onClick = onOpenStreak) }

        // ---- Genre cepat: kotak kecil berjejer ke kanan, bisa digeser; ketuk membuka halaman genre.
        item(key = "mood_row") {
            LazyRow(
                modifier = Modifier.padding(top = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(moods, key = { it.key }) { g ->
                    MoodChip(g, onClick = { vm.openCollection(g) })
                }
            }
        }

        // ---- Lanjutkan: lagu yang terakhir diputar, 2 baris tumbuh ke kanan seperti "Sedang trending".
        // Disembunyikan sepenuhnya kalau belum ada riwayat putar.
        if (recent.isNotEmpty()) {
            item(key = "continue_header") { HomeSectionTitle(t("Lanjutkan")) }
            item(key = "continue_row") {
                val list = remember(recent) { recent.take(20) }
                // Baris mengikuti jumlah lagu (maks. 2): satu lagu = satu baris, tanpa ruang kosong di bawahnya.
                val rowCount = list.size.coerceIn(1, 2)
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(rowCount),
                    modifier = Modifier.fillMaxWidth().height(gridHeight(rowCount, TRENDING_CARD_HEIGHT)),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(list, key = { "c_" + it.videoId }) { song ->
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

        // ---- Pilihan cepat: kolom isi 4 lagu (atas ke bawah), kolom berikutnya ke kanan; bisa digeser.
        item(key = "quick_header") { HomeSectionTitle(t("Pilihan cepat")) }
        item(key = "quick_row") {
            val picks = quickPicks
            when {
                picks == null -> QuickPicksSkeleton()
                picks.isEmpty() -> HomeRetry(
                    text = t("Pilihan cepat gagal dimuat. Ketuk untuk coba lagi."),
                    onClick = { vm.loadQuickPicks(force = true) },
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

        // ---- Mix untukmu: hanya kalau sudah masuk akun. Header akun (ketuk = buka Koleksi), lalu kartu persegi
        // berjejer ke kanan: Disukai dulu, lalu Mix asli YouTube Music, lalu playlist buatan akun (sampul asli).
        account?.let { acc ->
            item(key = "mix_header") {
                // Nama + foto dari header rak Mix di YouTube Music kalau sudah terbaca, selain itu dari data akun.
                val shown = AccountInfo(
                    name = mixOwner?.name?.takeIf { it.isNotBlank() } ?: acc.name,
                    photoUrl = mixOwner?.photoUrl ?: acc.photoUrl,
                )
                MixHeader(shown, onClick = onOpenAccount)
            }
            item(key = "mix_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "mix_liked") { MixLikedCard(likedCount = likedSongs.size, onClick = onOpenLiked) }
                    items(mixPlaylists, key = { "mix_" + it.ref.key }) { mix ->
                        MixPlaylistCard(mix, accountName = acc.name, onClick = { vm.openCollection(mix.ref) })
                    }
                    // Belum ada isi: kerangka saat memuat, atau kartu "Coba lagi" kalau gagal.
                    if (mixPlaylists.isEmpty()) {
                        if (mixStatus == MixStatus.Error) {
                            item(key = "mix_error") { MixErrorCard(onRetry = vm::retryMix) }
                        } else if (mixStatus == MixStatus.Loading) {
                            items(3, key = { "mix_skel_$it" }) { MixSkeletonCard() }
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

        // ---- Dengarkan lagi: lagu yang dulu sering diputar tapi sudah lama tidak (lihat [replay] di atas).
        // Disembunyikan sepenuhnya kalau tidak ada yang memenuhi syarat.
        if (replay.isNotEmpty()) {
            item(key = "replay_header") { HomeSectionTitle(t("Dengarkan lagi")) }
            item(key = "replay_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(replay, key = { "r_" + it.videoId }) { song ->
                        Box(Modifier.width(140.dp)) {
                            HomeSongCard(
                                song = song,
                                isCurrent = song.videoId == currentId,
                                onClick = { vm.playList(replay, replay.indexOf(song).coerceAtLeast(0)) },
                                vm = vm,
                            )
                        }
                    }
                }
            }
        }

        // ---- Andalanmu: artis yang paling sering kamu putar, avatar bulat berjejer ke kanan.
        // Ketuk membuka halaman artis itu (foto + lagunya). Disembunyikan sepenuhnya kalau belum ada riwayat putar.
        if (topArtists.isNotEmpty()) {
            item(key = "anchor_header") { HomeSectionTitle(t("Andalanmu")) }
            item(key = "anchor_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(topArtists, key = { "a_" + it.name }) { artist ->
                        AnchorArtistCard(
                            artist = artist,
                            onClick = {
                                vm.openArtist(artist.name, artist.thumbnail)
                            },
                        )
                    }
                }
            }
        }

        // ---- Rilis baru: album / single / EP tahun ini dari artis yang paling sering kamu putar.
        // Skeleton shimmer selama memuat; disembunyikan sepenuhnya kalau belum ada riwayat putar / tidak ada rilisan baru.
        if (newReleases?.isEmpty() != true) {
            item(key = "new_header") { HomeSectionTitle(t("Rilis baru")) }
            item(key = "new_row") {
                val list = newReleases
                if (list == null) {
                    NewReleasesSkeleton()
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { "n_" + it.ref.browseId }) { release ->
                            Box(Modifier.width(140.dp)) {
                                NewReleaseCard(release, onClick = { vm.openCollection(release.ref) })
                            }
                        }
                    }
                }
            }
        }

        // ---- Mirip dengan [artis]: sampai 3 bagian berurutan. Tiap bagian = label kecil "Mirip dengan", nama artis
        // yang sering kamu dengar di bawahnya (ketuk = buka halaman artis), lalu artis-artis yang mirip berjejer ke kanan.
        // Disembunyikan sepenuhnya kalau belum ada riwayat putar / belum ada hasil.
        // Selama masih memuat (null) tampil satu bagian skeleton shimmer dengan bentuk yang sama.
        if (similar == null) {
            item(key = "sim_skeleton") { SimilarSkeleton() }
        }
        similar?.forEachIndexed { idx, sim ->
            item(key = "sim_header_$idx") {
                MixHeader(
                    account = AccountInfo(name = sim.artist, photoUrl = sim.artistThumbnail),
                    label = t("Mirip dengan"),
                    onClick = { vm.openCollection(sim.artistRef) },
                )
            }
            item(key = "sim_row_$idx") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sim.similar, key = { "sim_${idx}_" + it.key }) { ref ->
                        SimilarArtistCard(ref, onClick = { vm.openCollection(ref) })
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
                list.isEmpty() -> HomeRetry(
                    text = t("Sedang trending gagal dimuat. Ketuk untuk coba lagi."),
                    onClick = { vm.loadTrending(force = true) },
                )
                else -> LazyHorizontalGrid(
                    // Maks. 2 baris lagu, tumbuh ke kanan; digeser kalau tidak muat. Lagu sedikit = baris lebih sedikit.
                    rows = GridCells.Fixed(list.size.coerceIn(1, 2)),
                    modifier = Modifier.fillMaxWidth().height(gridHeight(list.size.coerceIn(1, 2), TRENDING_CARD_HEIGHT)),
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
            HomeSectionTitle(t("Genre"))
        }
        item(key = "genre_grid") {
            val list = genres
            if (list == null) {
                GenreSkeleton()
            } else {
                // 3 baris, tumbuh ke kanan; digeser kalau tidak muat.
                val genreRows = list.size.coerceIn(1, 3)
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(genreRows),
                    modifier = Modifier.fillMaxWidth().height(gridHeight(genreRows, GENRE_CHIP_HEIGHT)),
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

/** Tinggi grid horizontal dengan [rows] baris setinggi [cell] dan jarak antarbaris [gap]. */
private fun gridHeight(rows: Int, cell: androidx.compose.ui.unit.Dp, gap: androidx.compose.ui.unit.Dp = 8.dp) =
    cell * rows + gap * (rows - 1)

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

private val MIX_CARD_SIZE = 156.dp

/**
 * Gaya tinggi baris untuk teks Mix: tanpa pemangkasan ruang atas/bawah baris (Trim.None) dan teks di tengah baris.
 * Bawaan Compose memangkas ascent baris pertama, sehingga bagian atas huruf besar, angka, dan "p" ikut terpotong
 * di teks satu baris yang dipangkas (maxLines + ellipsis) pada font aplikasi.
 */
private val MixLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * Header bergaya "Mix untukmu": foto (atau huruf pertama), label kecil ([label]), nama tebal di bawahnya, panah di kanan.
 * Dipakai juga oleh "Mirip dengan [artis]".
 */
@Composable
private fun MixHeader(account: AccountInfo, onClick: () -> Unit, label: String = t("Mix untukmu")) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account.photoUrl != null) {
            Artwork(url = account.photoUrl, modifier = Modifier.size(44.dp), shape = CircleShape)
        } else {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = account.name.take(1).lowercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(lineHeight = 24.sp, lineHeightStyle = MixLineHeight),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = account.name,
                style = MaterialTheme.typography.titleLarge.copy(lineHeight = 34.sp, lineHeightStyle = MixLineHeight),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MixCardText(title: String, subtitle: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(lineHeight = 26.sp, lineHeightStyle = MixLineHeight),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
    )
    // lineHeight dilonggarkan: bawaan labelMedium (16sp) terlalu rapat untuk font aplikasi, sehingga bagian atas
    // huruf besar / "p" terpotong saat teks dipangkas (maxLines + ellipsis memotong ke batas barisnya).
    Text(
        text = subtitle,
        style = MaterialTheme.typography.labelMedium.copy(lineHeight = 24.sp, lineHeightStyle = MixLineHeight),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // Padding bawah + samping: kartu dipotong sudut membulat 16dp (untuk ripple), tanpa ini huruf paling kiri
        // di baris terbawah ikut terpotong sudut itu.
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 10.dp),
    )
}

/** Kartu "Disukai": gradien ungu ke pink dengan ikon jempol. */
@Composable
private fun MixLikedCard(likedCount: Int, onClick: () -> Unit) {
    Column(Modifier.width(MIX_CARD_SIZE).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(MIX_CARD_SIZE)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF8E6CF7), Color(0xFFFF5FA8)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ThumbUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(72.dp))
        }
        MixCardText(t("Disukai"), if (likedCount > 0) t("%d lagu", likedCount) else t("Belum ada lagu"))
    }
}

/** Kerangka kartu Mix saat daftar dari YouTube Music masih dimuat. */
@Composable
private fun MixSkeletonCard() {
    ProvideShimmer {
        Column(Modifier.width(MIX_CARD_SIZE)) {
            ShimmerBox(Modifier.size(MIX_CARD_SIZE), RoundedCornerShape(16.dp))
            Spacer(Modifier.height(10.dp))
            ShimmerBox(Modifier.fillMaxWidth(0.7f).height(14.dp), RoundedCornerShape(7.dp))
            Spacer(Modifier.height(8.dp))
            ShimmerBox(Modifier.fillMaxWidth(0.5f).height(12.dp), RoundedCornerShape(6.dp))
        }
    }
}

/** Kartu saat Mix gagal dimuat: ketuk untuk mencoba lagi. */
@Composable
private fun MixErrorCard(onRetry: () -> Unit) {
    Column(Modifier.width(MIX_CARD_SIZE).clip(RoundedCornerShape(16.dp)).clickable(onClick = onRetry)) {
        Column(
            modifier = Modifier
                .size(MIX_CARD_SIZE)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MixCardText(t("Mix gagal dimuat"), t("Ketuk untuk coba lagi"))
    }
}

/** Kartu Mix / playlist akun: sampul asli dari YouTube Music, keterangan aslinya (mis. "Playlist • pinzz • 8 lagu"). */
@Composable
private fun MixPlaylistCard(mix: MixPlaylist, accountName: String, onClick: () -> Unit) {
    Column(Modifier.width(MIX_CARD_SIZE).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Artwork(
            url = mix.ref.thumbnail,
            modifier = Modifier.size(MIX_CARD_SIZE),
            shape = RoundedCornerShape(16.dp),
        )
        MixCardText(mix.ref.title, mix.ref.subtitle ?: accountName)
    }
}

/** Satu artis di "Andalanmu": avatar bulat, nama, dan jumlah putaran. */
@Composable
private fun AnchorArtistCard(artist: StatArtist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(
            url = artist.thumbnail,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = CircleShape,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = t("%d kali diputar", artist.count),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Satu artis mirip di "Mirip dengan": avatar bulat + nama (dan keterangan dari YouTube Music kalau ada). */
@Composable
private fun SimilarArtistCard(ref: CollectionRef, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(
            url = ref.thumbnail,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = CircleShape,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = ref.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        ref.subtitle?.let { sub ->
            Text(
                text = sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
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

/** Kerangka "Rilis baru" saat memuat: satu baris kartu persegi + judul dua baris + artis, sama ukurannya dengan [NewReleaseCard]. */
@Composable
private fun NewReleasesSkeleton() {
    ProvideShimmer {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            repeat(3) {
                Column(Modifier.width(124.dp).padding(top = 6.dp)) {
                    ShimmerBox(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(Modifier.fillMaxWidth(0.85f).height(13.dp), RoundedCornerShape(7.dp))
                    Spacer(Modifier.height(6.dp))
                    ShimmerBox(Modifier.fillMaxWidth(0.6f).height(13.dp), RoundedCornerShape(7.dp))
                    Spacer(Modifier.height(6.dp))
                    ShimmerBox(Modifier.fillMaxWidth(0.5f).height(11.dp), RoundedCornerShape(6.dp))
                }
            }
        }
    }
}

/** Satu rilisan di "Rilis baru": sampul persegi, judul, lalu "Jenis • Artis". Ketuk membuka halaman albumnya. */
@Composable
private fun NewReleaseCard(release: NewRelease, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 10.dp),
    ) {
        Artwork(
            url = release.ref.thumbnail,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = release.ref.title,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = release.type + " • " + release.artist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Kerangka "Mirip dengan [artis]" saat memuat: header (avatar + dua baris) dan deretan avatar bulat, sama ukurannya dengan aslinya. */
@Composable
private fun SimilarSkeleton() {
    ProvideShimmer {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(Modifier.size(44.dp), CircleShape)
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShimmerBox(Modifier.width(72.dp).height(11.dp), RoundedCornerShape(6.dp))
                    ShimmerBox(Modifier.width(140.dp).height(18.dp), RoundedCornerShape(9.dp))
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(4) {
                    Column(
                        modifier = Modifier
                            .width(112.dp)
                            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ShimmerBox(Modifier.fillMaxWidth().aspectRatio(1f), CircleShape)
                        Spacer(Modifier.height(8.dp))
                        ShimmerBox(Modifier.fillMaxWidth(0.75f).height(13.dp), RoundedCornerShape(7.dp))
                        Spacer(Modifier.height(6.dp))
                        ShimmerBox(Modifier.fillMaxWidth(0.5f).height(11.dp), RoundedCornerShape(6.dp))
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
        if (menuOpen) {
            SongMenuSheet(song = song, onDismiss = { menuOpen = false }) { dismiss ->
                SongMenu(vm, song, dismiss)
            }
        }
    }
}


/** Pesan gagal muat di beranda: teks + ikon panah melingkar; seluruh baris bisa diketuk untuk coba lagi. */
@Composable
private fun HomeRetry(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = t("Coba lagi"),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }
}
