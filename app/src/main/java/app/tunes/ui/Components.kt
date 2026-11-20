package app.tunes.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.innertube.SongItem
import app.tunes.innertube.isLocal
import app.tunes.player.PlayerState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import app.tunes.i18n.t

/** Tinggi bagian bawah yang tertutup mini player + bilah navigasi; daftar memberi ruang ini di ujungnya. */
val LocalBottomInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
) {
    val context = LocalContext.current
    var loading by remember(url) { mutableStateOf(url != null) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { s ->
                    loading = s is AsyncImagePainter.State.Loading || s is AsyncImagePainter.State.Empty
                },
            )
            // Sampul belum selesai dimuat: tampilkan kotak shimmer di atasnya.
            if (loading) Box(Modifier.fillMaxSize().shimmer())
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null)
        }
    }
}

@Composable
fun SongRow(
    modifier: Modifier = Modifier,
    song: SongItem,
    onClick: () -> Unit,
    isCurrent: Boolean = false,
    /** Beri latar warna aksen (dari cover lagu) 75% transparan pada lagu yang sedang diputar (dipakai di antrean). */
    highlightCurrent: Boolean = false,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isCurrent && highlightCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.thumbnail, Modifier.size(52.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            val sub = buildString {
                append(song.artist)
                if (song.durationSec > 0) {
                    if (isNotEmpty()) append(" • ")
                    append(formatTime(song.durationSec * 1000L))
                }
            }
            Text(
                text = sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (menu != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = t("Menu"))
                }
                DropdownMenu(
    expanded = menuOpen,
    onDismissRequest = { menuOpen = false },
    containerColor = glassMenuColor(),
    border = glassEdge(),
) {
                    menu { menuOpen = false }
                }
            }
        }
    }
}

/** Isi menu titik tiga standar untuk satu lagu. */
@Composable
fun SongMenu(vm: MainViewModel, song: SongItem, dismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text("Putar berikutnya") },
        leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) },
        onClick = { vm.playNext(song); dismiss() },
    )
    DropdownMenuItem(
        text = { Text("Tambah ke antrean") },
        leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) },
        onClick = { vm.addToQueue(song); dismiss() },
    )
    val liked by remember(song.videoId) { vm.isLiked(song.videoId) }
        .collectAsStateWithLifecycle(initialValue = false)
    DropdownMenuItem(
        text = { Text(if (liked) t("Batal suka") else t("Suka")) },
        leadingIcon = { Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) },
        onClick = { vm.toggleLike(song); dismiss() },
    )
    if (!song.isLocal) {
        val downloaded by remember(song.videoId) { vm.isDownloaded(song.videoId) }
            .collectAsStateWithLifecycle(initialValue = false)
        DropdownMenuItem(
            text = { Text(if (downloaded) t("Hapus unduhan") else t("Unduh")) },
            leadingIcon = { Icon(if (downloaded) Icons.Rounded.Delete else Icons.Rounded.Download, null) },
            onClick = {
                if (downloaded) vm.removeDownload(song) else vm.download(song)
                dismiss()
            },
        )
    }
}

@Composable
fun MiniPlayer(
    state: PlayerState,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    val song = state.current ?: return
    val glass = LocalGlass.current
    // Kartu melayang: ada jarak ke tepi dan ke bilah navigasi di bawahnya.
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        // Kaca: latar transparan; blur + lapisan tipis digambar oleh Modifier.glass.
        color = if (glass.enabled) androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
            .glass(
                RoundedCornerShape(24.dp),
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
                glass,
            ),
    ) {
        MiniPlayerBody(state, song, onToggle, onNext)
    }
}

/**
 * Isi pemutar mini ukuran penuh: sampul, judul / artis, jeda & berikutnya, dengan garis tipis di tepi atas
 * yang menandakan sejauh mana lagu sudah diputar ([showProgress] = false untuk kartu lagu tetangga saat digeser,
 * karena posisinya bukan milik lagu itu). Tingginya 60dp, sama dengan MINI_H di FloatingBars.
 */
@Composable
internal fun MiniPlayerBody(
    state: PlayerState,
    song: SongItem,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    showProgress: Boolean = true,
) {
    Box(Modifier.fillMaxWidth().height(60.dp)) {
    if (showProgress) {
        // Dibuat ulang tiap ganti lagu supaya garis langsung mulai dari kosong (tidak menyapu mundur).
        key(song.videoId) {
            MiniProgressLine(state, Modifier.align(Alignment.TopCenter).padding(horizontal = 26.dp))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(song.thumbnail, Modifier.size(40.dp), RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PlayPauseButton(state, onToggle)
        SmoothIconButton(onClick = onNext, enabled = state.hasNext) {
            Icon(Icons.Rounded.SkipNext, contentDescription = t("Berikutnya"))
        }
    }
    }
}

/** Garis tipis (2dp) penanda posisi lagu: latar redup, isi warna utama; bergerak halus di antara pembaruan posisi. */
@Composable
private fun MiniProgressLine(state: PlayerState, modifier: Modifier = Modifier) {
    val target = if (state.durationMs > 0L) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (state.isPlaying) 500 else 150, easing = LinearEasing),
        label = "miniProgress",
    )
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fill = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(track, size = size, cornerRadius = r)
        if (fraction > 0f) drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = r)
    }
}

/** Pemutar mini ringkas (bilah mengecil): sampul, judul / artis, dan satu tombol jeda / putar. */
@Composable
internal fun MiniPlayerCompact(state: PlayerState, song: SongItem, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Artwork(song.thumbnail, Modifier.size(40.dp), RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PlayPauseButton(state, onToggle)
    }
}

@Composable
private fun PlayPauseButton(state: PlayerState, onToggle: () -> Unit) {
    SmoothIconButton(onClick = onToggle) {
        if (state.isBuffering) {
            ShimmerBox(Modifier.size(24.dp), CircleShape)
        } else {
            PlayPauseIcon(state.isPlaying)
        }
    }
}

/**
 * Ikon putar / jeda. Animasi smooth nyala: bergantian memudar (180 ms); mati: langsung berganti.
 */
@Composable
fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier) {
    val smooth = LocalSmoothAnim.current
    Crossfade(
        targetState = playing,
        animationSpec = if (smooth) tween(180) else snap(),
        label = "playPauseIcon",
    ) { p ->
        Icon(
            if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (p) t("Jeda") else t("Putar"),
            modifier = modifier,
        )
    }
}

/**
 * Modifier item daftar: saat lagu ditambah, dihapus, atau berpindah urutan, baris bergeser halus.
 * Animasi smooth mati: baris langsung berubah.
 */
@Composable
fun LazyItemScope.smoothItem(): Modifier =
    if (LocalSmoothAnim.current) Modifier.animateItem() else Modifier

@Composable
fun LikedIcon(liked: Boolean) {
    // Animasi smooth nyala: ikon sedikit membesar lalu kembali saat baru disukai. Tidak bergerak saat pertama tampil.
    val smooth = LocalSmoothAnim.current
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(liked) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        if (smooth && liked) {
            scale.animateTo(1.3f, tween(90))
            scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        } else {
            scale.snapTo(1f)
        }
    }
    Icon(
        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        contentDescription = t("Suka"),
        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}
