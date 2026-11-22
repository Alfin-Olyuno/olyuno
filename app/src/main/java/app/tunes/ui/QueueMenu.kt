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
        SheetMenuItem(t("Putar berikutnya"), Icons.Rounded.PlaylistAdd) { vm.moveQueueItemNext(index); dismiss() }
    }
    if (index > 0) {
        SheetMenuItem(t("Naikkan"), Icons.Rounded.ArrowUpward) { vm.moveQueueItem(index, index - 1); dismiss() }
    }
    if (index < size - 1) {
        SheetMenuItem(t("Turunkan"), Icons.Rounded.ArrowDownward) { vm.moveQueueItem(index, index + 1); dismiss() }
    }
    if (!song.isLocal) {
        SheetMenuItem(t("Mulai radio"), Icons.Rounded.Radio) { vm.startRadio(song); dismiss() }
    }
    if (index != current) {
        SheetMenuItem(t("Hapus dari antrean"), Icons.Rounded.Delete) { vm.removeQueueItem(index); dismiss() }
    }
}
