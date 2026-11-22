package app.tunes.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tunes.lyrics.LyricsSource
import app.tunes.i18n.t

/**
 * Sheet pendek untuk memilih layanan lirik. Awalnya hanya "Otomatis" + "Lainnya ›"; setelah "Lainnya"
 * diketuk, sheet melebar secukupnya dan menampilkan LRCLIB, Better Lyrics, Lyrics Plus, dan BiniLyrics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSourceSheet(
    current: LyricsSource,
    availability: Map<LyricsSource, Boolean>,
    onSelect: (LyricsSource) -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(current != LyricsSource.Auto) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        scrimColor = ModalScrim,
        shape = ModalSheetShape,
        dragHandle = { ModalDragHandle() },
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Layar pemutar memakai skema warna gelap dengan teks putih; sheet harus ikut, kalau tidak
        // teks putih jatuh di atas latar terang bawaan dan tidak terbaca.
        containerColor = sheetContainerColor(),
        contentColor = sheetContentColor(),
    ) {
        SheetThemed {
        Column(
            Modifier
                .fillMaxWidth()
                // Landscape: layar pendek, isi sheet harus bisa digulir.
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .navigationBarsPadding()
                .smoothContentSize(),
        ) {
            Text(
                t("Lirik"),
                color = sheetInk,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            SourceRow(LyricsSource.Auto, current, availability, onSelect)
            if (!expanded) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t("Lainnya"),
                        color = sheetInk,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SourceRow(LyricsSource.Lrclib, current, availability, onSelect)
                SourceRow(LyricsSource.BetterLyrics, current, availability, onSelect)
                SourceRow(LyricsSource.LyricsPlus, current, availability, onSelect)
                SourceRow(LyricsSource.BiniLyrics, current, availability, onSelect)
            }
            Spacer(Modifier.height(12.dp))
        }
        }
    }
}

@Composable
private fun SourceRow(
    source: LyricsSource,
    current: LyricsSource,
    availability: Map<LyricsSource, Boolean>,
    onSelect: (LyricsSource) -> Unit,
) {
    val selected = source == current
    val unavailable = availability[source] == false
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(source) }
            .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t(source.label), color = sheetInk, style = MaterialTheme.typography.bodyLarge)
            if (unavailable) {
                Text(
                    t("Lirik tidak tersedia"),
                    style = MaterialTheme.typography.bodySmall,
                    color = sheetInk.copy(alpha = 0.72f),
                )
            } else {
                Text(
                    t(source.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = sheetInk.copy(alpha = 0.72f),
                )
            }
        }
        RadioButton(
            selected = selected,
            onClick = { onSelect(source) },
            colors = RadioButtonDefaults.colors(
                selectedColor = accent,
                unselectedColor = sheetInk.copy(alpha = 0.72f),
            ),
        )
    }
}

/** Handle sheet yang dipakai semua sheet di layar lirik (putih transparan di atas latar gelap). */
@Composable
internal fun LyricsSheetHandle() {
    Box(
        Modifier
            .padding(vertical = 12.dp)
            .width(36.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(sheetInk.copy(alpha = 0.4f)),
    )
}
