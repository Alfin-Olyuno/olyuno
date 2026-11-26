package app.tunes.ui

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import app.tunes.MainViewModel
import app.tunes.data.PlayRow
import app.tunes.data.StreakStore
import app.tunes.data.toStats
import app.tunes.i18n.AppLanguage
import app.tunes.i18n.I18n
import app.tunes.i18n.t
import app.tunes.player.PlayerState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Pilihan di dropdown paling atas. "Semuanya" menampilkan baris periode di bawahnya. */
private val SCOPES = listOf("Semuanya", "Minggu", "Bulan", "Tahun")

/** Baris periode (hanya saat "Semuanya"); urutannya dari kiri ke kanan. */
private val RANGES = listOf("Minggu ini", "1 Bulan", "3 Bulan", "6 Bulan", "1 Tahun")

/** Pilihan jenis di dropdown tiap chip periode; 0 = tampilkan semuanya. */
private val KINDS = listOf("Semua", "Lagu", "Playlist", "Artis", "Album")

/** Jumlah baris maksimal per daftar (lagu / artis / album / playlist). */
private const val LIST_LIMIT = 10

private fun todayStart(): Calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

/**
 * Awal periode (ms). Semuanya tanpa periode terpilih = sejak awal (0).
 * Semuanya + periode = mundur dari hari ini (7 hari, 1 / 3 / 6 bulan, 1 tahun).
 * Minggu / Bulan / Tahun = sejak awal minggu (Senin) / bulan / tahun kalender yang sedang berjalan.
 */
private fun fromMillis(scope: Int, range: Int): Long {
    val cal = todayStart()
    when (scope) {
        1 -> {
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        2 -> cal.set(Calendar.DAY_OF_MONTH, 1)
        3 -> cal.set(Calendar.DAY_OF_YEAR, 1)
        else -> when (range) {
            0 -> cal.add(Calendar.DAY_OF_YEAR, -6)
            1 -> cal.add(Calendar.MONTH, -1)
            2 -> cal.add(Calendar.MONTH, -3)
            3 -> cal.add(Calendar.MONTH, -6)
            4 -> cal.add(Calendar.YEAR, -1)
            else -> return 0L
        }
    }
    return cal.timeInMillis
}

/**
 * Teks tombol periode di bawah dropdown cakupan. Minggu = "16 - 22 November" (Senin sampai Minggu, dua bulan bila
 * minggunya melintas bulan: "30 November - 6 Desember"); Bulan = "November"; Tahun = "2026".
 */
private fun scopeLabel(scope: Int): String {
    if (scope == 0) return t(SCOPES[0])
    val locale = if (I18n.effective == AppLanguage.English) Locale.ENGLISH else Locale.forLanguageTag("id-ID")
    val monthName = SimpleDateFormat("MMMM", locale)
    val start = Calendar.getInstance().apply { timeInMillis = fromMillis(scope, -1) }
    return when (scope) {
        1 -> {
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }
            val d1 = start.get(Calendar.DAY_OF_MONTH)
            val d2 = end.get(Calendar.DAY_OF_MONTH)
            if (start.get(Calendar.MONTH) == end.get(Calendar.MONTH)) {
                "$d1 - $d2 ${monthName.format(start.time)}"
            } else {
                "$d1 ${monthName.format(start.time)} - $d2 ${monthName.format(end.time)}"
            }
        }
        2 -> monthName.format(start.time)
        else -> start.get(Calendar.YEAR).toString()
    }
}

/**
 * Streak terpanjang dalam rentang hari [fromDay]..[toDay] (epoch day): hari terhitung menambah, hari yang
 * diselamatkan freeze menyambung tanpa menambah, hari kosong memutus.
 */
private fun longestRunBetween(days: Set<Long>, saved: Set<Long>, fromDay: Long, toDay: Long): Int {
    var best = 0
    var run = 0
    var d = fromDay
    while (d <= toDay) {
        if (d in days) {
            run++
            if (run > best) best = run
        } else if (d !in saved) {
            run = 0
        }
        d++
    }
    return best
}

/**
 * Statistik & Rekap: kartu Rekap di atas (bisa dibagikan sebagai gambar), lalu lagu, artis, album, dan playlist yang paling sering diputar. Dropdown di paling atas memilih
 * cakupan waktu; saat "Semuanya", baris periode di bawahnya (bisa digeser) mempersempitnya.
 */
@Composable
fun StatisticsPage(
    vm: MainViewModel,
    player: PlayerState,
    currentId: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    var scope by rememberSaveable { mutableIntStateOf(0) }
    // -1 = tidak ada periode dipilih (semua waktu).
    var range by rememberSaveable { mutableIntStateOf(-1) }
    // Jenis yang ditampilkan (indeks di KINDS), dipilih lewat dropdown chip periode.
    var kind by rememberSaveable { mutableIntStateOf(0) }

    val from = remember(scope, range) { fromMillis(scope, range) }
    val rows: List<PlayRow>? by remember(from) { vm.playRows(from).map<List<PlayRow>, List<PlayRow>?> { it } }
        .collectAsStateWithLifecycle(initialValue = null)
    val stats = remember(rows) { rows?.toStats(LIST_LIMIT) }
    val streak by vm.streak.collectAsStateWithLifecycle()
    val periodLabel = if (scope == 0) {
        if (range < 0) t(SCOPES[0]) else t(RANGES[range])
    } else {
        scopeLabel(scope)
    }

    SubPage(title = t("Statistik & Rekap"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            item(key = "scope") {
                ScopeDropdown(selected = scope, onSelect = { scope = it; range = -1; kind = 0 })
            }
            if (scope == 0) {
                item(key = "ranges") {
                    LazyRow(
                        modifier = Modifier.padding(top = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(RANGES) { i, name ->
                            ChipDropdown(
                                label = t(name),
                                selected = range == i,
                                options = KINDS,
                                current = if (range == i) kind else 0,
                                onSelect = { range = i; kind = it },
                            )
                        }
                    }
                }
            } else {
                // Minggu / Bulan / Tahun: satu tombol periode (tanggal / bulan / tahun) di posisi baris chip, dengan dropdown jenis yang sama.
                item(key = "period") {
                    ChipDropdown(
                        label = scopeLabel(scope),
                        selected = true,
                        options = KINDS,
                        current = kind,
                        onSelect = { kind = it },
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
            }

            val s = stats
            when {
                s == null -> Unit
                s.total == 0 -> item(key = "empty") {
                    Text(
                        text = t("Belum ada statistik untuk periode ini"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
                    )
                }
                else -> {
                    item(key = "rekap") {
                        val longest = if (from == 0L) streak.longest
                        else longestRunBetween(streak.days, streak.saved, StreakStore.epochDay(from), streak.todayEpochDay)
                        RekapCard(
                            data = RekapData(
                                periodLabel = periodLabel,
                                minutes = if (s.totalSec > 0) maxOf(1L, (s.totalSec + 30) / 60) else 0L,
                                songs = s.uniqueSongs,
                                activeDays = s.activeDays,
                                longestStreak = longest,
                                topSongs = s.songs.take(3).map { RekapEntry(it.song.title, it.song.artist, it.song.thumbnail) },
                                topArtists = s.artists.take(3).map { RekapEntry(it.name, null, it.thumbnail) },
                            ),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        )
                    }
                    item(key = "total") {
                        Text(
                            text = t("%d kali diputar", s.total),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
                        )
                    }

                    // Urutan: Lagu, Playlist, Artis, Album. Tiap daftar berjejer ke kanan dari yang paling sering diputar.
                    val showSongs = (kind == 0 || kind == 1) && s.songs.isNotEmpty()
                    val showPlaylists = (kind == 0 || kind == 2) && s.playlists.isNotEmpty()
                    val showArtists = (kind == 0 || kind == 3) && s.artists.isNotEmpty()
                    val showAlbums = (kind == 0 || kind == 4) && s.albums.isNotEmpty()
                    if (!showSongs && !showPlaylists && !showArtists && !showAlbums) {
                        item(key = "empty_kind") {
                            Text(
                                text = t("Belum ada statistik untuk periode ini"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
                            )
                        }
                    }
                    if (showSongs) {
                        item(key = "h_songs") { StatSectionTitle(t("Lagu teratas")) }
                        item(key = "r_songs") {
                            val queue = s.songs.map { it.song }
                            StatRowList {
                                itemsIndexed(s.songs, key = { _, it -> "s_" + it.song.videoId }) { i, row ->
                                    StatCard(
                                        thumbnail = row.song.thumbnail,
                                        title = row.song.title,
                                        subtitle = row.song.artist,
                                        count = t("%d kali diputar", row.count),
                                        highlight = row.song.videoId == currentId,
                                        onClick = { vm.playList(queue, i) },
                                    )
                                }
                            }
                        }
                    }

                    if (showPlaylists) {
                        item(key = "h_playlists") { StatSectionTitle(t("Playlist teratas")) }
                        item(key = "r_playlists") {
                            StatRowList {
                                itemsIndexed(s.playlists, key = { _, it -> "p_" + it.ref.key }) { _, row ->
                                    StatCard(
                                        thumbnail = row.thumbnail,
                                        title = row.ref.title,
                                        count = t("%d lagu diputar", row.count),
                                        onClick = { vm.openCollection(row.ref) },
                                    )
                                }
                            }
                        }
                    }

                    if (showArtists) {
                        item(key = "h_artists") { StatSectionTitle(t("Artis teratas")) }
                        item(key = "r_artists") {
                            StatRowList {
                                itemsIndexed(s.artists, key = { _, it -> "a_" + it.name }) { _, row ->
                                    StatCard(
                                        thumbnail = row.thumbnail,
                                        title = row.name,
                                        count = t("%d kali diputar", row.count),
                                        shape = CircleShape,
                                        centered = true,
                                        onClick = { vm.openArtist(row.name, row.thumbnail) },
                                    )
                                }
                            }
                        }
                    }

                    if (showAlbums) {
                        item(key = "h_albums") { StatSectionTitle(t("Album teratas")) }
                        item(key = "r_albums") {
                            StatRowList {
                                itemsIndexed(s.albums, key = { _, it -> "b_" + it.ref.key }) { _, row ->
                                    StatCard(
                                        thumbnail = row.thumbnail,
                                        title = row.ref.title,
                                        count = t("%d lagu diputar", row.count),
                                        onClick = { vm.openCollection(row.ref) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tombol "Semuanya ⌄": chip dengan panah di kanan; ketuk untuk membuka dropdown pilihan cakupan waktu. */
@Composable
private fun ScopeDropdown(selected: Int, onSelect: (Int) -> Unit) {
    ChipDropdown(
        label = t(SCOPES[selected]),
        selected = false,
        options = SCOPES,
        current = selected,
        onSelect = onSelect,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
}

/**
 * Chip yang membuka dropdown: teks + panah ke bawah (berputar saat terbuka). [options] = pilihan di dropdown,
 * [current] = indeks pilihan yang sedang aktif (ditebalkan), [selected] = chip diberi warna aksen.
 */
@Composable
private fun ChipDropdown(
    label: String,
    selected: Boolean,
    options: List<String>,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(targetValue = if (open) 180f else 0f, animationSpec = smoothSpec(spring()), label = "chipArrow")
    Box(modifier) {
        StatChip(
            label = label,
            selected = selected,
            onClick = { open = true },
            trailing = { tint ->
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(20.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
            },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = glassMenuColor(),
            border = glassEdge(),
        ) {
            options.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(t(name), fontWeight = if (i == current) FontWeight.SemiBold else null) },
                    onClick = {
                        open = false
                        onSelect(i)
                    },
                )
            }
        }
    }
}

/** Kotak kecil bersudut membulat (gaya chip genre di Beranda); [selected] memberinya warna aksen. */
@Composable
private fun StatChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable (tint: Color) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val inverted = playCircleColors()
    val container = if (selected) (inverted?.circle ?: colors.primaryContainer) else colors.surfaceContainerHigh
    val content = if (selected) (inverted?.icon ?: colors.onPrimaryContainer) else colors.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
        )
        trailing?.invoke(content)
    }
}

@Composable
private fun StatSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Baris kartu yang bisa digeser ke kanan (gaya "Paling sering diputar" di Beranda). */
@Composable
private fun StatRowList(content: LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Kartu 140dp: sampul persegi (atau bulat untuk artis), judul 2 baris, keterangan, dan jumlah putar. */
@Composable
private fun StatCard(
    thumbnail: String?,
    title: String,
    count: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    highlight: Boolean = false,
    shape: Shape = RoundedCornerShape(12.dp),
    centered: Boolean = false,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 10.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(url = thumbnail, modifier = Modifier.fillMaxWidth().aspectRatio(1f), shape = shape)
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = count,
            maxLines = 1,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
