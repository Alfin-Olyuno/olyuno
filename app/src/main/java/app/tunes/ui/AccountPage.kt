package app.tunes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.i18n.t
import app.tunes.innertube.AccountTab
import app.tunes.player.PlayerState

/**
 * Halaman "Akun": perpustakaan akun YouTube Music dalam empat tab (Playlist, Artis, Album, Podcast).
 * Barisnya geser ke samping kalau layar tidak cukup; isi setiap tab diambil dari YouTube Music lewat InnerTube
 * (dimuat saat tab pertama kali dibuka). Ketuk satu baris = buka halaman artis / album / playlist / podcast itu.
 */
@Composable
fun AccountPage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenLiked: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val tabs = listOf(
        AccountTab.PLAYLIST to t("Playlist"),
        AccountTab.ARTIST to t("Artis"),
        AccountTab.ALBUM to t("Album"),
        AccountTab.PODCAST to t("Podcast"),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val lib by vm.accountLib.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val tab = tabs[selected].first
    LaunchedEffect(tab) { vm.loadAccountTab(tab) }

    SubPage(title = t("Akun"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(Modifier.fillMaxSize().padding(top = LocalTopInset.current)) {
            ScrollableTabRow(
                selectedTabIndex = selected,
                edgePadding = 8.dp,
                containerColor = Color.Transparent,
                divider = {},
            ) {
                tabs.forEachIndexed { i, (_, label) ->
                    Tab(selected = selected == i, onClick = { selected = i }, text = { Text(label, maxLines = 1) })
                }
            }

            val state = lib[tab]
            val list = state?.items
            when {
                list == null && state?.error == true -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            t("Gagal memuat dari YouTube Music"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = { vm.loadAccountTab(tab, force = true) }) { Text(t("Coba lagi")) }
                    }
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomInset.current),
                ) {
                    if (tab == AccountTab.PLAYLIST) {
                        item(key = "liked") { LikedRow(count = liked.size, onClick = onOpenLiked) }
                    }
                    items(list, key = { it.key }) { ref -> EntityRow(ref, onClick = { vm.openCollection(ref) }) }
                    if (list.isEmpty() && tab != AccountTab.PLAYLIST) {
                        item(key = "empty") {
                            Text(
                                text = when (tab) {
                                    AccountTab.ARTIST -> t("Belum ada artis di akun ini")
                                    AccountTab.ALBUM -> t("Belum ada album di akun ini")
                                    else -> t("Belum ada podcast di akun ini")
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Baris "Disukai" di atas tab Playlist: sama seperti kartu Disukai di Beranda (gradien ungu ke pink + jempol). */
@Composable
private fun LikedRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF8E6CF7), Color(0xFFFF5FA8)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ThumbUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t("Disukai"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                t("%d lagu", count),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
