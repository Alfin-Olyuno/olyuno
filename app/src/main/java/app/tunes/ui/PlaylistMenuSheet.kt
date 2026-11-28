package app.tunes.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.data.LocalPlaylist
import app.tunes.i18n.t
import kotlinx.coroutines.launch

/**
 * Menu titik tiga satu playlist di "Playlist saya", tampil sebagai bottom sheet (sama gayanya dengan menu lagu).
 * Isi: Putar, Acak, Tambah ke antrean, Ganti nama, bagian YouTube Music (kirim / perbarui / buka / bagikan tautan /
 * lepas tautan), lalu Hapus playlist. [ytId] != null = playlist ini sudah dikirim ke akun YouTube Music.
 * Mengirim ke YouTube Music butuh login: kalau belum masuk, yang muncul hanya pesan untuk masuk dulu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistMenuSheet(
    vm: MainViewModel,
    playlist: LocalPlaylist,
    ytId: String?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val songs = playlist.songs
    val link = ytId?.let { "https://music.youtube.com/playlist?list=" + it.removePrefix("VL") }.orEmpty()

    ModalBottomSheet(
        scrimColor = ModalScrim,
        shape = ModalSheetShape,
        dragHandle = { ModalDragHandle() },
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetContainerColor(),
        contentColor = sheetContentColor(),
    ) {
        SheetThemed {
            Column(
                Modifier
                    .fillMaxWidth()
                    // Landscape: layar pendek, isi sheet harus bisa digulir.
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlaylistCover(songs, Modifier.size(52.dp), RoundedCornerShape(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            playlist.name,
                            color = sheetInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            t("%d lagu", songs.size) + if (ytId != null) " · YouTube Music" else "",
                            color = sheetInk.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.padding(horizontal = 8.dp)) {
                    if (songs.isNotEmpty()) {
                        SheetMenuItem(t("Putar"), Icons.Rounded.PlayArrow) { vm.playList(songs, 0); dismiss() }
                        SheetMenuItem(t("Acak"), Icons.Rounded.Shuffle) { vm.playList(songs.shuffled(), 0); dismiss() }
                        SheetMenuItem(t("Tambah semua ke antrean"), Icons.Rounded.QueueMusic) {
                            vm.addAllToQueue(songs)
                            Toast.makeText(context, t("%d lagu ditambahkan ke antrean", songs.size), Toast.LENGTH_SHORT).show()
                            dismiss()
                        }
                    }
                    SheetMenuItem(t("Ganti nama"), Icons.Rounded.Edit) { onRename(); dismiss() }

                    if (ytId == null) {
                        SheetMenuItem(t("Tambahkan ke YouTube Music"), Icons.Rounded.CloudUpload) {
                            vm.pushPlaylistToYt(playlist); dismiss()
                        }
                    } else {
                        SheetMenuItem(t("Perbarui di YouTube Music"), Icons.Rounded.Sync) {
                            vm.pushPlaylistToYt(playlist); dismiss()
                        }
                        SheetMenuItem(t("Buka di YouTube Music"), Icons.Rounded.Link) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { Toast.makeText(context, t("Tidak ada aplikasi untuk membuka tautan"), Toast.LENGTH_SHORT).show() }
                            dismiss()
                        }
                        SheetMenuItem(t("Bagikan tautan"), Icons.Rounded.Share) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, link)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { Toast.makeText(context, t("Tidak ada aplikasi untuk berbagi"), Toast.LENGTH_SHORT).show() }
                            dismiss()
                        }
                        SheetMenuItem(t("Lepas tautan YouTube Music"), Icons.Rounded.LinkOff) {
                            vm.unlinkPlaylist(playlist.id); dismiss()
                        }
                    }
                    SheetMenuItem(t("Hapus playlist"), Icons.Rounded.Delete) { onDelete(); dismiss() }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
