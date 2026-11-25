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
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
                    title = { Text(t("Informasi")) },
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
                        contentPadding = PaddingValues(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
                    ) {
                        item {
                            MenuGroup {
                                INFO_MESSAGES.forEachIndexed { i, msg ->
                                    if (i > 0) MenuDivider()
                                    InfoRow(msg, unread = msg.id !in readIds, onClick = { openId = msg.id })
                                }
                            }
                        }
                    }
                } else {
                    InfoDetail(open)
                }
        }
    }
}

/** Kotak ikon bersudut membulat: berwarna aksen kalau pesan belum dibuka, netral kalau sudah. */
@Composable
private fun InfoTile(icon: ImageVector, accent: Boolean, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(if (accent) scheme.primaryContainer else scheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (accent) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun InfoRow(msg: InfoMessage, unread: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfoTile(Icons.Rounded.Mail, accent = unread)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                t(msg.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
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
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Ikon untuk butir fitur menurut labelnya; label lain jatuh ke ikon centang. */
private fun bulletIcon(label: String): ImageVector = when (label.trim().lowercase()) {
    "temukan" -> Icons.Rounded.Explore
    "pencarian" -> Icons.Rounded.Search
    "koleksi" -> Icons.Rounded.LibraryMusic
    "disukai" -> Icons.Rounded.Favorite
    "riwayat" -> Icons.Rounded.History
    else -> Icons.Rounded.Check
}

@Composable
private fun InfoDetail(msg: InfoMessage) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = LocalTopInset.current, bottom = 24.dp + LocalBottomInset.current),
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp)) {
            InfoTile(Icons.Rounded.Mail, accent = true, size = 52.dp)
            Spacer(Modifier.height(16.dp))
            Text(t(msg.title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        var i = 0
        while (i < msg.blocks.size) {
            when (val block = msg.blocks[i]) {
                is InfoBlock.Heading -> {
                    SectionTitle(t(block.text))
                    i++
                }
                is InfoBlock.Paragraph -> {
                    Text(
                        t(block.text),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                    )
                    i++
                }
                is InfoBlock.Bullet -> {
                    // Butir yang berurutan dikumpulkan jadi satu kartu.
                    val group = ArrayList<InfoBlock.Bullet>()
                    while (i < msg.blocks.size && msg.blocks[i] is InfoBlock.Bullet) {
                        group += msg.blocks[i] as InfoBlock.Bullet
                        i++
                    }
                    MenuGroup {
                        group.forEachIndexed { k, b ->
                            if (k > 0) MenuDivider()
                            MenuRow(
                                leading = {
                                    Icon(bulletIcon(b.label), contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                },
                                label = t(b.label),
                                description = t(b.text),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Text(
            t("Dari Olyuno"),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp),
        )
    }
}
