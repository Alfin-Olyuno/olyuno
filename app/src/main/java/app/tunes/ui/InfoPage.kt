package app.tunes.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.data.INFO_MESSAGES
import app.tunes.data.InfoBlock
import app.tunes.data.InfoMessage
import app.tunes.player.PlayerState
import app.tunes.i18n.t

/** Halaman Informasi: daftar pesan (yang belum dibuka diberi titik), ketuk untuk membaca isinya. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoPage(
    readIds: Set<String>,
    onMarkRead: (String) -> Unit,
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = INFO_MESSAGES.firstOrNull { it.id == openId }
    // Pesan ditandai terbaca begitu dibuka.
    LaunchedEffect(open?.id) { open?.let { onMarkRead(it.id) } }
    PageBackHandler { if (open != null) openId = null else onBack() }
    val bars = rememberBarsCollapse(countUnconsumed = true)
    // Buka / tutup satu pesan: pemutar mini kembali membesar.
    LaunchedEffect(open?.id) { bars.expand() }

    PageSurface {
        MiniPlayerScaffold(
            topBar = {
                TopAppBar(
                    colors = translucentBarColors(),
                    title = { Text("Informasi") },
                    navigationIcon = {
                        IconButton(onClick = { if (open != null) openId = null else onBack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("Kembali"))
                        }
                    },
                )
            },
            bars = bars,
            vm = vm,
            player = player,
            onOpenPlayer = onOpenPlayer,
        ) {
                if (open == null) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = LocalTopInset.current, bottom = LocalBottomInset.current),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(INFO_MESSAGES, key = { it.id }) { msg ->
                            InfoRow(msg, unread = msg.id !in readIds, onClick = { openId = msg.id })
                        }
                    }
                } else {
                    InfoDetail(open)
                }
        }
    }
}

@Composable
private fun InfoRow(msg: InfoMessage, unread: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                t(msg.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                t(msg.preview),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unread) {
            Box(
                Modifier
                    .padding(start = 12.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun InfoDetail(msg: InfoMessage) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(top = LocalTopInset.current, bottom = LocalBottomInset.current),
    ) {
        Text(t(msg.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        for (block in msg.blocks) {
            when (block) {
                is InfoBlock.Heading -> {
                    Spacer(Modifier.height(8.dp))
                    Text(t(block.text), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                }
                is InfoBlock.Paragraph -> {
                    Text(t(block.text), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                }
                is InfoBlock.Bullet -> {
                    Row(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 10.dp))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(t(block.label)) }
                                append(": ")
                                append(t(block.text))
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
