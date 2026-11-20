package app.tunes.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.data.HistoryEntry
import app.tunes.innertube.SongItem
import app.tunes.player.PlayerState
import java.util.Calendar
import app.tunes.i18n.t

private const val DAY_MS = 24L * 60 * 60 * 1000

private enum class Bucket(val label: String) {
    Today("Hari ini"),
    Yesterday("Kemarin"),
    Week("Minggu lalu"),
    Month("Bulan lalu"),
    Year("Tahun lalu"),
}

/** Hari ini, kemarin, 2-7 hari lalu, 8-30 hari lalu, lebih lama dari itu. */
private fun bucketFor(playedAt: Long, startOfToday: Long): Bucket = when {
    playedAt >= startOfToday -> Bucket.Today
    playedAt >= startOfToday - DAY_MS -> Bucket.Yesterday
    playedAt >= startOfToday - 7 * DAY_MS -> Bucket.Week
    playedAt >= startOfToday - 30 * DAY_MS -> Bucket.Month
    else -> Bucket.Year
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/**
 * Riwayat lagu: lagu yang sedang diputar di bagian "Sekarang", sisanya dikelompokkan per waktu.
 * Daftarnya hidup: setiap lagu baru diputar, riwayat ikut berubah.
 */
@Composable
fun SongHistoryPage(
    vm: MainViewModel,
    entries: List<HistoryEntry>,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val now = player.current
    val currentId = now?.videoId

    val groups: List<Pair<Bucket, List<SongItem>>> = remember(entries, currentId) {
        val start = startOfToday()
        entries
            .filter { it.song.videoId != currentId }
            .groupBy({ bucketFor(it.playedAt, start) }, { it.song })
            .toList()
            .sortedBy { it.first.ordinal }
    }
    // Urutan putar: lagu sekarang dulu, lalu seluruh riwayat sesuai urutan tampil.
    val flat: List<SongItem> = remember(groups, currentId) {
        listOfNotNull(now) + groups.flatMap { it.second }
    }

    SubPage(title = t("Riwayat lagu"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        if (flat.isEmpty()) {
            CenterMessage(t("Belum ada riwayat putar"))
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
            ) {
                if (now != null) {
                    item(key = "h_now") { SectionLabel(t("Sekarang")) }
                    item(key = "now_${now.videoId}") {
                        SongRow(
                            song = now,
                            isCurrent = true,
                            onClick = onOpenPlayer,
                            menu = { dismiss -> SongMenu(vm, now, dismiss) },
                        )
                    }
                }
                groups.forEach { (bucket, songs) ->
                    item(key = "h_${bucket.name}") { SectionLabel(t(bucket.label)) }
                    items(songs, key = { "s_${it.videoId}" }) { song ->
                        SongRow(
                            song = song,
                            isCurrent = false,
                            onClick = { vm.playList(flat, flat.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)) },
                            menu = { dismiss -> SongMenu(vm, song, dismiss) },
                        )
                    }
                }
            }
        }
    }
}
