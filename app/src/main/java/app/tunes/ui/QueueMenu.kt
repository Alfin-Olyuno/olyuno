package app.tunes.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Radio
import app.tunes.innertube.SongItem
import app.tunes.innertube.isLocal
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.tunes.MainViewModel
import app.tunes.i18n.t

/**
 * Isi menu titik tiga untuk satu baris di antrean pemutar: jadikan berikutnya, naikkan / turunkan, atau hapus.
 * [index] = posisi lagu di antrean, [current] = posisi lagu yang sedang diputar, [size] = jumlah lagu.
 * Lagu yang sedang diputar tidak bisa dijadikan berikutnya atau dihapus dari sini.
 */
@Composable
fun QueueMenu(vm: MainViewModel, song: SongItem, index: Int, current: Int, size: Int, dismiss: () -> Unit) {
    if (index != current && index != current + 1) {
        DropdownMenuItem(
            text = { Text(t("Putar berikutnya")) },
            leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) },
            onClick = { vm.moveQueueItemNext(index); dismiss() },
        )
    }
    if (index > 0) {
        DropdownMenuItem(
            text = { Text(t("Naikkan")) },
            leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
            onClick = { vm.moveQueueItem(index, index - 1); dismiss() },
        )
    }
    if (index < size - 1) {
        DropdownMenuItem(
            text = { Text(t("Turunkan")) },
            leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
            onClick = { vm.moveQueueItem(index, index + 1); dismiss() },
        )
    }
    if (!song.isLocal) {
        DropdownMenuItem(
            text = { Text(t("Mulai radio")) },
            leadingIcon = { Icon(Icons.Rounded.Radio, null) },
            onClick = { vm.startRadio(song); dismiss() },
        )
    }
    if (index != current) {
        DropdownMenuItem(
            text = { Text(t("Hapus dari antrean")) },
            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
            onClick = { vm.removeQueueItem(index); dismiss() },
        )
    }
}
