package app.tunes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tunes.i18n.t
import app.tunes.innertube.SongItem

/** Keadaan pencarian di satu halaman daftar (Koleksi): terbuka atau tidak, dan kata yang diketik. */
class ListSearchState {
    var active by mutableStateOf(false)
    var query by mutableStateOf("")

    fun open() {
        active = true
    }

    fun close() {
        active = false
        query = ""
    }
}

@Composable
fun rememberListSearch(): ListSearchState = remember { ListSearchState() }

/** "Kembali" menutup pencarian dulu. Panggil setelah PageBackHandler halamannya supaya yang ini diutamakan. */
@Composable
fun ListSearchBackHandler(search: ListSearchState) {
    PageBackHandler(enabled = search.active) { search.close() }
}

/** Saring lagu menurut judul atau artis (tanpa peduli huruf besar); kata kosong = semua. */
fun List<SongItem>.filterBySearch(query: String): List<SongItem> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) }
}

/** Tombol bulat semi-transparan: sama dengan tombol Cari di halaman Playlist / Album. */
@Composable
fun SearchCircleButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = t("Cari"))
    }
}

/**
 * Bar atas halaman daftar dengan tombol Cari di kanan (posisi dan bentuk sama seperti halaman Playlist). Saat
 * pencarian dibuka, bar berubah jadi kolom cari bulat dengan panah kembali dan tombol hapus kata; tinggi bar tetap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableTopBar(
    title: String,
    onBack: () -> Unit,
    search: ListSearchState,
    canSearch: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(search.active) { if (search.active) focus.requestFocus() }
    TopAppBar(
        colors = translucentBarColors(),
        title = {
            if (search.active) {
                TextField(
                    value = search.query,
                    onValueChange = { search.query = it },
                    singleLine = true,
                    placeholder = { Text(t("Cari di sini")) },
                    leadingIcon = {
                        IconButton(onClick = search::close) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("Tutup pencarian"))
                        }
                    },
                    trailingIcon = {
                        if (search.query.isNotEmpty()) {
                            IconButton(onClick = { search.query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = t("Hapus"))
                            }
                        }
                    },
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            } else {
                Text(title)
            }
        },
        navigationIcon = {
            if (!search.active) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("Kembali"))
                }
            }
        },
        actions = {
            if (!search.active && canSearch) {
                SearchCircleButton(onClick = search::open)
                Spacer(Modifier.width(12.dp))
            }
        },
    )
}
