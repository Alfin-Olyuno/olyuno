package app.tunes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tunes.innertube.SongItem
import kotlinx.coroutines.launch

/**
 * Menu titik tiga untuk satu lagu, tampil sebagai bottom sheet (bukan dropdown). Kepala sheet menampilkan
 * sampul, judul, dan artis lagu; di bawahnya isi menu ([content]) berupa deretan [SheetMenuItem].
 * [content] menerima `dismiss` yang menutup sheet dengan animasi geser turun lalu memanggil [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuSheet(
    song: SongItem,
    onDismiss: () -> Unit,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
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
                    Artwork(song.thumbnail, Modifier.size(52.dp), RoundedCornerShape(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            color = sheetInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (song.artist.isNotBlank()) {
                            Text(
                                song.artist,
                                color = sheetInk.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.padding(horizontal = 8.dp)) {
                    content(dismiss)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Satu baris aksi di [SongMenuSheet]: label di kiri, ikon di kanan (gaya sama dengan sheet Pemutaran). */
@Composable
fun SheetMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(source, 0.97f)
            .clip(RoundedCornerShape(16.dp))
            .pressClickable(source, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = sheetInk, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(icon, contentDescription = null, tint = sheetInk.copy(alpha = 0.72f))
    }
}
