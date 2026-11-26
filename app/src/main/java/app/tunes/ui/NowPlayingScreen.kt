package app.tunes.ui

import androidx.compose.ui.graphics.graphicsLayer
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.tunes.MainViewModel
import app.tunes.innertube.SongItem
import app.tunes.innertube.isLocal
import app.tunes.player.PlayerState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.tunes.i18n.t

@Composable
fun NowPlayingScreen(
    vm: MainViewModel,
    state: PlayerState,
    liked: Boolean,
    onClose: () -> Unit,
) {
    val song = state.current ?: return
    val songPalette = rememberSongPalette(song.thumbnail)
    val bgThumbnail by vm.bgThumbnail.collectAsStateWithLifecycle()
    val customBg by vm.playerBgColor.collectAsStateWithLifecycle()
    // Kustom warna background (hanya saat Background thumbnail mati): menggantikan warna dari cover lagu.
    val palette = customBg?.takeIf { !bgThumbnail }?.let { argb ->
        val top = Color(argb)
        val bottom = lerp(top, Color.Black, 0.5f)
        songPalette.copy(top = top, bottom = bottom, extra1 = top, extra2 = top, extra3 = top)
    } ?: songPalette
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    // Background tampilan lirik: nyala = tanpa panel; mati = panel warna di sekitar teks lirik (thumbnail tetap ada).
    val lyricsBg by vm.lyricsBg.collectAsStateWithLifecycle()
    val lyricsPanelCustom by vm.lyricsPanelCustom.collectAsStateWithLifecycle()
    val lyricsPanelPref by vm.lyricsPanelColor.collectAsStateWithLifecycle()
    // Otomatis: preset yang paling cocok dengan warna cover; berganti halus saat lagu berubah.
    val smoothNav = LocalSmoothAnim.current
    val autoPanel by animateColorAsState(autoLyricsPanel(songPalette.top), smoothSpec(tween<Color>(500)), label = "lyricsPanelAuto")
    val lyricsPanelColor = if (lyricsPanelCustom) Color(lyricsPanelPref) else autoPanel
    // Layar penuh lirik: kalau lama tidak disentuh, semua bagian selain panah & handle sheet disembunyikan.
    var lyricsImmersive by remember { mutableStateOf(false) }
    val idle = remember { IdleTracker() }
    LaunchedEffect(showLyrics) {
        if (showLyrics) {
            idle.touch()
            while (true) {
                delay(400)
                if (!lyricsImmersive && !idle.pressed &&
                    SystemClock.uptimeMillis() - idle.lastTouch >= LYRICS_IDLE_MS
                ) {
                    lyricsImmersive = true
                }
            }
        } else {
            lyricsImmersive = false
        }
    }
    // Tombol bilah bawah yang terakhir diketuk; sorotannya menetap sampai tombol lain diketuk.
    var selectedTab by rememberSaveable { mutableStateOf<NavTab?>(null) }

    // Seluruh layar pemutar berwarna sesuai lagu: teks putih, aksen terang dari cover.
    val base = MaterialTheme.colorScheme
    val scheme = base.copy(
        primary = palette.accent,
        onPrimary = palette.bottom,
        background = palette.bottom,
        surface = palette.bottom,
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = 0.72f),
        surfaceVariant = Color.White.copy(alpha = 0.12f),
        outlineVariant = Color.White.copy(alpha = 0.28f),
        secondaryContainer = Color.White.copy(alpha = 0.20f),
        onSecondaryContainer = Color.White,
        surfaceContainerHigh = lerp(palette.bottom, Color.White, 0.14f),
    )

    LightSystemBarIcons()
    BackHandler(enabled = showLyrics) {
        showLyrics = false
        selectedTab = null // halaman lirik ditutup, tidak ada tombol yang aktif lagi
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // Bagikan: judul, artis, dan tautan YouTube Music lewat menu berbagi bawaan Android.
    val shareSong = {
        val label = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" — ")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, song.title)
            putExtra(
                Intent.EXTRA_TEXT,
                if (song.videoId.startsWith(app.tunes.innertube.LOCAL_ID_PREFIX)) label
                else "$label\nhttps://music.youtube.com/watch?v=${song.videoId}",
            )
        }
        try {
            context.startActivity(Intent.createChooser(send, t("Bagikan lagu")))
        } catch (e: Exception) {
            Toast.makeText(context, t("Tidak ada aplikasi untuk berbagi"), Toast.LENGTH_SHORT).show()
        }
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val landscape = isLandscape()
    val queueIndex = if (landscape) QUEUE_HEADER_INDEX_LANDSCAPE else QUEUE_HEADER_INDEX

    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        Surface(Modifier.fillMaxSize(), color = palette.bottom, contentColor = Color.White) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Hanya mengamati sentuhan (tidak mengonsumsi): sentuhan/gulir apa pun memunculkan kembali kontrol.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                idle.pressed = event.changes.any { it.pressed }
                                idle.touch()
                                if (lyricsImmersive) lyricsImmersive = false
                            }
                        }
                    },
            ) {
                NowPlayingBackdrop(song.thumbnail, palette, bgThumbnail)

                // Latar tetap penuh layar; isi menjauhi lekuk kamera / bilah navigasi di sisi saat landscape.
                BoxWithConstraints(Modifier.fillMaxSize().safeHorizontalPadding()) {
                val paneWidth = maxWidth * LEFT_PANE

                AnimatedVisibility(
                    visible = !showLyrics,
                    enter = smoothEnter(fadeIn(tween(300))),
                    exit = smoothExit(fadeOut(tween(200))),
                ) {
                    PlayerPage(
                        vm = vm,
                        state = state,
                        song = song,
                        liked = liked,
                        onClose = onClose,
                        listState = listState,
                        navBottom = navBottom,
                    )
                }

                AnimatedVisibility(
                    visible = showLyrics,
                    enter = smoothEnter(fadeIn(tween(350)) + slideInVertically(tween(450)) { it / 14 }),
                    exit = smoothExit(fadeOut(tween(220))),
                ) {
                    LyricsPage(
                        vm = vm,
                        state = state,
                        song = song,
                        liked = liked,
                        onClose = onClose,
                        navBottom = navBottom,
                        immersive = lyricsImmersive,
                        panelColor = if (lyricsBg) null else lyricsPanelColor,
                    )
                }

                // Bilah tombol melayang di bawah: Lirik (kiri), Antrean, Bagikan.
                AnimatedVisibility(
                    visible = !(showLyrics && lyricsImmersive),
                    modifier = Modifier.align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter),
                    enter = smoothEnter(fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 2 }),
                    exit = smoothExit(fadeOut(tween(200)) + slideOutVertically(tween(250)) { it / 2 }),
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(bottom = navBottom + 12.dp)
                            .padding(horizontal = if (landscape) 12.dp else 20.dp)
                            // Landscape: bilah tombol hanya selebar kolom kiri, jadi tidak menutupi daftar / lirik di kanan.
                            .then(if (landscape) Modifier.width(paneWidth) else Modifier.fillMaxWidth())
                            .shadow(10.dp, RoundedCornerShape(32.dp))
                            .sheetDraggable(),
                        shape = RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PillButton(
                                LyricsBubbleIcon,
                                t("Lirik"),
                                selected = selectedTab == NavTab.Lyrics,
                                onClick = {
                                    selectedTab = NavTab.Lyrics
                                    showLyrics = true
                                },
                            )
                            PillButton(
                                Icons.Rounded.QueueMusic,
                                t("Antrean"),
                                selected = selectedTab == NavTab.Queue,
                                onClick = {
                                    selectedTab = NavTab.Queue
                                    if (showLyrics) {
                                        showLyrics = false
                                        scope.launch {
                                            delay(150) // tunggu daftar pemutar tampil lagi
                                            if (smoothNav) listState.animateScrollToItem(queueIndex) else listState.scrollToItem(queueIndex)
                                        }
                                    } else {
                                        scope.launch {
                                            if (smoothNav) listState.animateScrollToItem(queueIndex) else listState.scrollToItem(queueIndex)
                                        }
                                    }
                                },
                            )
                            PillButton(
                                Icons.Rounded.Share,
                                t("Bagikan"),
                                // Aksi sekali ketuk, bukan halaman: tab yang aktif tidak berubah.
                                selected = false,
                                onClick = shareSong,
                                pressAnimated = true,
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

private const val QUEUE_HEADER_INDEX = 5

/** Landscape: indeks judul "Antrean" bergeser satu karena sampul pindah ke kolom kiri (bukan item daftar). */
private const val QUEUE_HEADER_INDEX_LANDSCAPE = QUEUE_HEADER_INDEX - 1

/** Landscape: lebar kolom kiri (sampul / kontrol) sebagai pecahan lebar layar; sisanya kolom kanan (daftar / lirik). */
private const val LEFT_PANE = 0.46f

/** Berapa lama (ms) tanpa sentuhan sebelum halaman lirik masuk mode layar penuh. */
private const val LYRICS_IDLE_MS = 5_000L

/** Mencatat sentuhan terakhir tanpa memicu recomposition di setiap gerakan jari. */
private class IdleTracker {
    var lastTouch: Long = SystemClock.uptimeMillis()
    var pressed: Boolean = false
    fun touch() {
        lastTouch = SystemClock.uptimeMillis()
    }
}

/** Tombol di bilah bawah layar pemutar. */
private enum class NavTab { Lyrics, Queue, Share }

// ------------------------------------------------------------------ latar

/**
 * Latar dari lagu: gradien warna cover + cover itu sendiri yang dikecilkan lalu dibentangkan
 * (efek blur yang jalan di semua versi Android), ditutup gradien gelap supaya teks tetap terbaca.
 */
@Composable
private fun NowPlayingBackdrop(thumbnail: String?, palette: SongPalette, showThumbnail: Boolean) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(palette.top, palette.bottom))),
        )
        if (!showThumbnail) {
            // Tanpa thumbnail: beberapa bercak warna dari rona cover yang berbeda, dilebur jadi satu latar.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    fun glow(c: Color, cx: Float, cy: Float, r: Float) = drawRect(
                        Brush.radialGradient(
                            listOf(c.copy(alpha = 0.75f), Color.Transparent),
                            center = Offset(size.width * cx, size.height * cy),
                            radius = size.maxDimension * r,
                        ),
                    )
                    glow(palette.extra1, 0.15f, 0.20f, 0.55f)
                    glow(palette.extra2, 0.90f, 0.40f, 0.50f)
                    glow(palette.extra3, 0.30f, 0.75f, 0.50f)
                },
            )
        }
        Crossfade(targetState = if (showThumbnail) thumbnail else null, animationSpec = smoothSpec(tween(900)), label = "backdropArt") { url ->
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(url).size(40).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    alpha = 0.55f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.top.copy(alpha = 0.45f),
                            palette.bottom.copy(alpha = 0.92f),
                        ),
                    ),
                ),
        )
    }
}

// --------------------------------------------------------- halaman pemutar

@Composable
private fun PlayerPage(
    vm: MainViewModel,
    state: PlayerState,
    song: SongItem,
    liked: Boolean,
    onClose: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    navBottom: androidx.compose.ui.unit.Dp,
) {
    // Animasi smooth nyala: cover mengecil sedikit saat dijeda dan kembali penuh saat diputar. Mati: ukuran tetap.
    val smoothAnim = LocalSmoothAnim.current
    val coverScale = animateFloatAsState(
        targetValue = if (!smoothAnim || state.isPlaying) 1f else 0.92f,
        animationSpec = if (smoothAnim) androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 200f) else androidx.compose.animation.core.snap(),
        label = "coverScale",
    )
    // Menu halaman player: Timer tidur (tombol titik tiga di pojok kanan atas).
    val sleepState by vm.sleepTimer.collectAsStateWithLifecycle()
    val normalizeVolume by vm.normalizeVolume.collectAsStateWithLifecycle()
    var showPlayerMenu by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showClearQueue by remember { mutableStateOf(false) }
    // Status unduhan lagu ini untuk tombol "Unduh" di menu Pemutaran.
    val playerContext = LocalContext.current
    val songDownloaded by remember(song.videoId) { vm.isDownloaded(song.videoId) }
        .collectAsStateWithLifecycle(initialValue = false)
    val allDownloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val allDownloadFailed by vm.downloadFailed.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
    val landscape = isLandscape()
    val playerItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = t("Tutup"), tint = invertedIconColor() ?: LocalContentColor.current)
                }
                Text("Sedang diputar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { showPlayerMenu = true }) {
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = t("Pemutaran"), tint = invertedIconColor() ?: LocalContentColor.current)
                }
            }
        }

        // Portrait: sampul selebar layar di dalam daftar. Landscape: sampul pindah ke kolom kiri (di bawah).
        if (!landscape) item {
            Artwork(
                url = song.thumbnail,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 16.dp)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = coverScale.value
                        scaleY = coverScale.value
                    }
                    .shadow(14.dp, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SmoothIconButton(onClick = { vm.toggleLike(song) }) { LikedIcon(liked) }
            }
        }

        item { SeekSection(vm, state) }
        item { ControlsRow(vm, state) }
        // Volume perangkat: tepat di bawah tombol kontrol, selebar slider durasi tapi lebih sempit
        // di kiri-kanan supaya terbaca sebagai kontrol sekunder.
        item {
            val inv = invertedIconColor()
            if (inv != null) {
                DeviceVolumeSlider(Modifier.padding(start = 36.dp, end = 36.dp, top = 20.dp), tint = inv, accent = inv, solid = true)
            } else {
                DeviceVolumeSlider(Modifier.padding(start = 36.dp, end = 36.dp, top = 20.dp))
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    // 16dp = tepi kiri/kanan sampul di baris lagu di bawahnya, supaya satu garis lurus.
                    .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp),
            ) {
                Text(
                    t("Antrean"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .alignByBaseline(),
                )
                Text(
                    t("%d lagu", state.queue.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
                // Simpan: seluruh antrean jadi playlist (baru atau yang sudah ada).
                if (state.queue.isNotEmpty()) {
                    Text(
                        t("Simpan"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(start = 16.dp)
                            .clickable { vm.openPlaylistPicker(state.queue) },
                    )
                }
                // Bersihkan: hapus semua lagu setelah yang sedang diputar (hanya kalau memang ada).
                if (state.currentIndex in 0 until state.queue.lastIndex) {
                    Text(
                        t("Bersihkan"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(start = 16.dp)
                            .clickable { showClearQueue = true },
                    )
                }
            }
        }

        itemsIndexed(state.queue, key = { index, s -> "$index-${s.videoId}" }) { index, s ->
            SongRow(
                song = s,
                isCurrent = index == state.currentIndex,
                highlightCurrent = true,
                onClick = { vm.skipTo(index) },
                menu = { dismiss ->
                    QueueMenu(vm, song = s, index = index, current = state.currentIndex, size = state.queue.size, dismiss = dismiss)
                },
            )
        }
    }
    if (landscape) {
        // Landscape: sampul di kiri (sebesar yang muat di tinggi layar), daftar kontrol & antrean di kanan.
        Row(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(
                Modifier
                    .weight(LEFT_PANE)
                    .fillMaxHeight()
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 92.dp + navBottom),
                contentAlignment = Alignment.Center,
            ) {
                Artwork(
                    url = song.thumbnail,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .graphicsLayer {
                            scaleX = coverScale.value
                            scaleY = coverScale.value
                        }
                        .shadow(14.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f - LEFT_PANE).fillMaxHeight(),
                contentPadding = PaddingValues(start = 4.dp, end = 8.dp, bottom = 24.dp + navBottom),
                content = playerItems,
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 104.dp + navBottom),
            content = playerItems,
        )
    }
    if (showPlayerMenu) {
        PlayerMenuSheet(
            sleep = sleepState,
            onOpenSleepTimer = {
                showPlayerMenu = false
                showSleepSheet = true
            },
            downloaded = songDownloaded,
            downloadProgress = allDownloadProgress[song.videoId],
            downloadFailed = song.videoId in allDownloadFailed,
            isLocal = song.isLocal,
            onStartRadio = {
                showPlayerMenu = false
                vm.startRadio(song)
                // Pesan dari ViewModel tertutup layar pemutar, jadi kabarnya lewat Toast di sini.
                if (!song.isLocal) Toast.makeText(playerContext, t("Memulai radio dari \"%s\"", song.title), Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylist = {
                showPlayerMenu = false
                vm.openPlaylistPicker(listOf(song))
            },
            onDownload = {
                showPlayerMenu = false
                vm.download(song)
                // Pesan dari ViewModel tertutup layar pemutar, jadi kabarnya lewat Toast di sini.
                Toast.makeText(playerContext, t("Mengunduh \"%s\"", song.title), Toast.LENGTH_SHORT).show()
            },
            normalizeVolume = normalizeVolume,
            onNormalizeVolumeChange = vm::setNormalizeVolume,
            onDismiss = { showPlayerMenu = false },
        )
    }
    if (showClearQueue) {
        ConfirmDeleteDialog(
            title = t("Bersihkan antrean?"),
            message = t("Semua lagu setelah yang sedang diputar akan dihapus dari antrean."),
            onConfirm = {
                vm.clearUpcomingQueue()
                showClearQueue = false
            },
            onDismiss = { showClearQueue = false },
        )
    }
    if (showSleepSheet) {
        SleepTimerSheet(
            state = sleepState,
            onMinutes = vm::setSleepTimerMinutes,
            onTrackEnd = vm::setSleepTimerUntilTrackEnd,
            onCancel = vm::cancelSleepTimer,
            onDismiss = { showSleepSheet = false },
        )
    }
    }
}

// ------------------------------------------------------------ halaman lirik

@Composable
private fun LyricsPage(
    vm: MainViewModel,
    state: PlayerState,
    song: SongItem,
    liked: Boolean,
    onClose: () -> Unit,
    navBottom: androidx.compose.ui.unit.Dp,
    immersive: Boolean,
    panelColor: Color?,
) {
    // Hanya dikoleksi saat halaman lirik tampil, jadi lirik baru diunduh ketika benar-benar dibuka.
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    // Warna teks lirik (Pengaturan > Lirik): Ikuti sistem = putih seperti bawaan.
    val textMode by vm.lyricsTextMode.collectAsStateWithLifecycle()
    val textCustom by vm.lyricsTextCustom.collectAsStateWithLifecycle()
    val lyricsTextColor = when (textMode) {
        app.tunes.data.ColorMode.Dark -> Color.Black
        app.tunes.data.ColorMode.Custom -> Color(textCustom)
        else -> Color.White
    }
    // 1 = kontrol penuh tampil, 0 = layar penuh (hanya panah & handle sheet).
    val chrome by animateFloatAsState(if (immersive) 0f else 1f, smoothSpec(tween(300)), label = "lyricsChrome")
    val bottomGap by animateDpAsState(if (immersive) navBottom + 12.dp else 0.dp, smoothSpec(tween(300)), label = "lyricsGap")

    // Pilihan layanan lirik: teks "Lirik <layanan> ›" di bagian atas, ketuk untuk membuka sheet pilihan.
    val source by vm.lyricsSource.collectAsStateWithLifecycle()
    var showSourceSheet by remember { mutableStateOf(false) }
    // Ketersediaan lirik per layanan untuk lagu ini (true/false), diperiksa saat sheet dibuka.
    var availability by remember(song.videoId) {
        mutableStateOf<Map<app.tunes.lyrics.LyricsSource, app.tunes.lyrics.LyricsKind>>(emptyMap())
    }
    LaunchedEffect(showSourceSheet, song.videoId) {
        if (!showSourceSheet) return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            listOf(
                app.tunes.lyrics.LyricsSource.Lrclib,
                app.tunes.lyrics.LyricsSource.BetterLyrics,
                app.tunes.lyrics.LyricsSource.LyricsPlus,
                app.tunes.lyrics.LyricsSource.BiniLyrics,
            ).forEach { src ->
                launch {
                    vm.lyricsKind(song, src)?.let { k -> availability = availability + (src to k) }
                }
            }
        }
    }
    // Menu titik tiga: muat ulang, salin, mengikuti per lirik, terjemahan, bagikan.
    var showMenuSheet by remember { mutableStateOf(false) }
    // Cari lirik: sheet pencarian + permintaan gulir ke baris hasil yang dipilih.
    var showSearchSheet by remember { mutableStateOf(false) }
    var lyricsJump by remember(song.videoId) { mutableStateOf<LyricsJump?>(null) }
    // Status unduhan lagu ini untuk tombol "Unduh" di menu.
    val songDownloaded by remember(song.videoId) { vm.isDownloaded(song.videoId) }
        .collectAsStateWithLifecycle(initialValue = false)
    val allDownloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val allDownloadFailed by vm.downloadFailed.collectAsStateWithLifecycle()
    val followLine by vm.lyricsFollowLine.collectAsStateWithLifecycle()
    val keepAwake by vm.keepScreenAwake.collectAsStateWithLifecycle()
    val translateOn by vm.lyricsTranslate.collectAsStateWithLifecycle()
    val targetLang by vm.lyricsTargetLang.collectAsStateWithLifecycle()
    val translation by vm.lyricsTranslation.collectAsStateWithLifecycle()
    val translatedLines = (translation as? app.tunes.lyrics.TranslationState.Done)?.lines
    val lyricsContext = LocalContext.current
    // Teks lirik (+ terjemahan) untuk disalin / dibagikan; null kalau lirik belum siap.
    val readyText = {
        (lyrics as? app.tunes.lyrics.LyricsState.Ready)
            ?.let { lyricsAsText(it.lines, translatedLines) }
            ?.takeIf { it.isNotBlank() }
    }
    val sourceName = (lyrics as? app.tunes.lyrics.LyricsState.Ready)?.source
        ?: source.label.takeIf { source != app.tunes.lyrics.LyricsSource.Auto }
    if (showMenuSheet) {
        LyricsMenuSheet(
            followLine = followLine,
            onFollowLineChange = vm::setLyricsFollowLine,
            translate = translateOn,
            onTranslateChange = vm::setLyricsTranslate,
            targetLang = targetLang,
            onTargetLangChange = vm::setLyricsTargetLang,
            sourceLabel = sourceName ?: source.label,
            onOpenSource = {
                showMenuSheet = false
                showSourceSheet = true
            },
            keepAwake = keepAwake,
            onKeepAwakeChange = vm::setKeepScreenAwake,
            downloaded = songDownloaded,
            downloadProgress = allDownloadProgress[song.videoId],
            downloadFailed = song.videoId in allDownloadFailed,
            onDownload = {
                showMenuSheet = false
                vm.download(song)
                // Pesan dari ViewModel tertutup layar pemutar, jadi kabarnya lewat Toast di sini.
                Toast.makeText(lyricsContext, t("Mengunduh \"%s\"", song.title), Toast.LENGTH_SHORT).show()
            },
            onRefresh = {
                showMenuSheet = false
                vm.refreshLyrics()
            },
            onCopy = {
                showMenuSheet = false
                val text = readyText()
                if (text == null) {
                    Toast.makeText(lyricsContext, t("Lirik belum tersedia"), Toast.LENGTH_SHORT).show()
                } else {
                    copyLyrics(lyricsContext, text)
                }
            },
            onSearch = {
                showMenuSheet = false
                if (lyrics is app.tunes.lyrics.LyricsState.Ready) {
                    showSearchSheet = true
                } else {
                    Toast.makeText(lyricsContext, t("Lirik belum tersedia"), Toast.LENGTH_SHORT).show()
                }
            },
            onShare = {
                showMenuSheet = false
                val text = readyText()
                if (text == null) {
                    Toast.makeText(lyricsContext, t("Lirik belum tersedia"), Toast.LENGTH_SHORT).show()
                } else {
                    shareLyrics(lyricsContext, song.title, song.artist, text)
                }
            },
            onAnalyze = {
                showMenuSheet = false
                vm.requestLyricsAnalysis(song)
            },
            onDismiss = { showMenuSheet = false },
        )
    }
    if (showSearchSheet) {
        val ready = lyrics as? app.tunes.lyrics.LyricsState.Ready
        if (ready == null) {
            showSearchSheet = false
        } else {
            LyricsSearchSheet(
                lines = ready.lines,
                translations = translatedLines,
                synced = ready.synced,
                onPick = { index ->
                    showSearchSheet = false
                    val line = ready.lines.getOrNull(index)
                    // Lirik tersinkron: lagu loncat ke baris itu (sama seperti mengetuk barisnya); polos: hanya digulir.
                    if (ready.synced && line != null && line.timeMs >= 0) vm.seekTo(line.timeMs)
                    lyricsJump = LyricsJump(index, System.nanoTime())
                },
                onDismiss = { showSearchSheet = false },
            )
        }
    }
    if (showSourceSheet) {
        LyricsSourceSheet(
            current = source,
            availability = availability,
            onSelect = {
                vm.setLyricsSource(it)
                showSourceSheet = false
            },
            onDismiss = { showSourceSheet = false },
        )
    }

    // Potongan halaman lirik; portrait menyusunnya satu kolom, landscape dua kolom (kontrol kiri, lirik kanan).
    val topRow: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .align(Alignment.Center)
                    .padding(top = 6.dp)
                    .alpha(chrome)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = !immersive) { showSourceSheet = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (sourceName != null) t("Lirik %s", sourceName) else t("Lirik"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { showMenuSheet = true },
                enabled = !immersive,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .alpha(chrome),
            ) {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = t("Menu lirik"))
            }
        }
    }
    val headerBox: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .sheetDraggable(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = t("Tutup"))
                }
                Artwork(song.thumbnail, Modifier.size(44.dp).alpha(chrome), RoundedCornerShape(10.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .alpha(chrome),
                ) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SmoothIconButton(
                    onClick = { vm.toggleLike(song) },
                    enabled = !immersive,
                    modifier = Modifier.alpha(chrome),
                ) { LikedIcon(liked) }
            }
        }

    }
    val lyricsBox: @Composable (Modifier) -> Unit = { mod ->
        Box(
            mod
                .fillMaxWidth()
                // Panel warna hanya di sekitar area teks lirik, bukan seluruh layar.
                .then(
                    if (panelColor != null) {
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(panelColor.copy(alpha = 0.92f))
                    } else Modifier,
                ),
        ) {
            LyricsView(
                state = lyrics,
                songKey = song.videoId,
                isPlaying = state.isPlaying,
                positionProvider = vm::positionNow,
                onSeek = vm::seekTo,
                onRetry = vm::retryLyrics,
                modifier = Modifier.fillMaxSize(),
                follow = followLine,
                translations = translatedLines,
                textColor = lyricsTextColor,
                jump = lyricsJump,
                onJumpHandled = { lyricsJump = null },
            )
            // Status terjemahan: kecil di bawah area lirik, tidak menggeser baris lirik.
            val status = when (translation) {
                is app.tunes.lyrics.TranslationState.Loading -> t("Menerjemahkan…")
                is app.tunes.lyrics.TranslationState.Error -> t("Gagal menerjemahkan · ketuk untuk coba lagi")
                else -> null
            }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(enabled = translation is app.tunes.lyrics.TranslationState.Error) {
                            vm.retryTranslation()
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

    }
    val controlsBlock: @Composable () -> Unit = {
        // Area bawah tidak bisa di-scroll, jadi geserannya langsung menarik sheet.
        // Disembunyikan saat layar penuh supaya lirik mengisi layar.
        AnimatedVisibility(
            visible = !immersive,
            enter = smoothEnter(fadeIn(tween(250)) + expandVertically(tween(300))),
            exit = smoothExit(fadeOut(tween(200)) + shrinkVertically(tween(300))),
        ) {
            Column(Modifier.sheetDraggable()) {
                SeekSection(vm, state)
                ControlsRow(vm, state)
                // Ruang untuk bilah tombol melayang (tinggi 68dp + jarak).
                Spacer(Modifier.height(navBottom + 12.dp + 68.dp + 8.dp))
            }
        }
    }
    if (isLandscape()) {
        Row(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Column(Modifier.weight(LEFT_PANE).fillMaxHeight()) {
                headerBox()
                Spacer(Modifier.weight(1f))
                controlsBlock()
                Spacer(Modifier.height(bottomGap))
            }
            Column(Modifier.weight(1f - LEFT_PANE).fillMaxHeight()) {
                topRow()
                lyricsBox(Modifier.weight(1f))
                Spacer(Modifier.height(navBottom + 8.dp))
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            topRow()
            headerBox()
            lyricsBox(Modifier.weight(1f))
            controlsBlock()
            Spacer(Modifier.height(bottomGap))
        }
    }
}

// ---------------------------------------------------------- bagian bersama

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SeekSection(vm: MainViewModel, state: PlayerState) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L).toFloat()
    val inv = invertedIconColor()
    val accent = inv ?: MaterialTheme.colorScheme.primary
    val trackBase = inv ?: Color.White
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        GuardedSlider(
            value = (dragging ?: state.positionMs.toFloat()).coerceIn(0f, duration),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { vm.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration,
            // Garis tipis (4dp) dan penanda bulat kecil, bukan bentuk bawaan yang tebal dan tinggi.
            thumb = {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            },
            track = { sliderState ->
                val range = sliderState.valueRange
                val fraction = if (range.endInclusive > range.start) {
                    (sliderState.value - range.start) / (range.endInclusive - range.start)
                } else {
                    0f
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(trackBase.copy(alpha = if (inv != null) 0.35f else 0.24f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(accent),
                    )
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTime(dragging?.toLong() ?: state.positionMs),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ControlsRow(vm: MainViewModel, state: PlayerState) {
    // Warna tampilan Hitam = ikon putih, Putih = ikon hitam, Kustom = warna aksen; Ikuti sistem = bawaan (null).
    val inv = invertedIconColor()
    val base = inv ?: Color.White
    val active = inv ?: MaterialTheme.colorScheme.primary
    val inactive = base.copy(alpha = if (inv != null) 0.6f else 0.65f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmoothIconButton(onClick = vm::toggleShuffle) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = t("Acak"),
                tint = smoothTint(if (state.shuffle) active else inactive),
            )
        }
        SmoothIconButton(onClick = vm::previous) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = t("Sebelumnya"), tint = base, modifier = Modifier.size(36.dp))
        }
        // Tombol putar/jeda tanpa bulatan penuh; bulatan hanya tampak (memudar) saat ditekan, selain ripple bawaan.
        val playSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val playPressed by playSource.collectIsPressedAsState()
        val pressGlow by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (playPressed) 0.22f else 0f,
            animationSpec = smoothSpec(androidx.compose.animation.core.tween(if (playPressed) 90 else 260)),
            label = "playPressGlow",
        )
        IconButton(
            onClick = vm::togglePlay,
            interactionSource = playSource,
            modifier = Modifier
                .size(72.dp)
                .pressScale(playSource, 0.92f)
                .background(base.copy(alpha = pressGlow), CircleShape),
        ) {
            if (state.isBuffering) {
                ShimmerBox(Modifier.size(34.dp), CircleShape, base)
            } else {
                PlayPauseIcon(state.isPlaying, Modifier.size(48.dp), tint = base)
            }
        }
        SmoothIconButton(onClick = vm::next, enabled = state.hasNext) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = t("Berikutnya"),
                tint = if (state.hasNext) base else base.copy(alpha = 0.38f),
                modifier = Modifier.size(36.dp),
            )
        }
        SmoothIconButton(onClick = vm::cycleRepeat) {
            val one = state.repeatMode == Player.REPEAT_MODE_ONE
            androidx.compose.animation.Crossfade(
                targetState = one,
                animationSpec = if (LocalSmoothAnim.current) androidx.compose.animation.core.tween(160) else androidx.compose.animation.core.snap(),
                label = "repeatIcon",
            ) { isOne ->
                Icon(
                    if (isOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = t("Ulangi"),
                    tint = smoothTint(if (state.repeatMode == Player.REPEAT_MODE_OFF) inactive else active),
                )
            }
        }
    }
}

@Composable
private fun PillButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    /** Gerak tekan hanya untuk tombol aksi (Bagikan); tombol pembuka halaman (Lirik, Antrean) tanpa gerak. */
    pressAnimated: Boolean = false,
) {
    val inv = invertedIconColor()
    val container = if (selected) (if (inv != null) inv.copy(alpha = 0.16f) else MaterialTheme.colorScheme.secondaryContainer) else Color.Transparent
    val tint = if (inv != null) inv
    else if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .width(72.dp)
            .height(48.dp)
            .then(if (pressAnimated) Modifier.pressScale(source, 0.9f) else Modifier)
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .clickable(
                interactionSource = source,
                indication = androidx.compose.foundation.LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
    }
}

// ------------------------------------------------------------- status bar

/** Layar pemutar selalu gelap, jadi ikon status/navigasi harus terang walau tema aplikasi terang. */
@Composable
private fun LightSystemBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val prevStatus = controller.isAppearanceLightStatusBars
            val prevNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.isAppearanceLightStatusBars = prevStatus
                controller.isAppearanceLightNavigationBars = prevNav
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
