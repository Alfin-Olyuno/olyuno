package app.tunes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.player.PlayerState
import app.tunes.i18n.t

/** Tinggi area pemutar mini di halaman penuh: atas 4dp + isi 60dp + bawah 10dp (sama dengan CollapsingMiniPlayer). */
private val MINI_AREA = 74.dp

/** Tinggi bar judul yang melayang (status bar + bar) di halaman penuh; isi memakainya sebagai padding atas supaya bisa digulir di belakang bar. 0 bila halaman tidak punya bar judul melayang. */
val LocalTopInset = compositionLocalOf { 0.dp }

/**
 * Bar judul tanpa latar sama sekali: isi halaman terlihat langsung di belakang panah dan judul, dan
 * tampil di atas blur berwarna atas-layar ([TopScreenBlurForBar]) yang memudar mulus (tanpa tepi keras).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun translucentBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)

/**
 * Kerangka halaman penuh: [topBar] di atas, isi di bawahnya, dan pemutar mini melayang di atas isi.
 * Isi menjangkau sampai ke tepi bawah layar (tidak berhenti di atas pemutar mini), jadi di sekitar
 * pemutar mini tidak ada latar tersendiri: daftar terlihat di belakangnya (di gaya Kaca di-blur).
 * Ruang untuk ujung daftar diberikan lewat [LocalBottomInset]; daftar / kolom di dalam [content]
 * memakainya sebagai padding bawah supaya item terakhir tidak tertutup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayerScaffold(
    /** null = tanpa bar judul (mis. halaman dengan gambar hero). Bila ada, bar melayang agak tembus di atas isi. */
    topBar: (@Composable () -> Unit)?,
    bars: BarsCollapseState,
    vm: MainViewModel,
    player: PlayerState,
    onOpenPlayer: () -> Unit,
    content: @Composable () -> Unit,
) {
    val backdrop = rememberGlassBackdrop()
    val base = LocalGlass.current
    val glass = remember(base.enabled, backdrop) { GlassConfig(base.enabled, backdrop) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val inset = navBottom + if (player.current != null) MINI_AREA else 0.dp

    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var barHeight by remember { mutableStateOf(statusTop + 64.dp) }

    Scaffold(
        topBar = {},
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { padding ->
        CompositionLocalProvider(LocalGlass provides glass) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .padding(top = if (topBar == null) padding.calculateTopPadding() else 0.dp)
                        .fillMaxSize()
                        .nestedScroll(bars)
                        .then(if (glass.enabled || topBlurSupported()) Modifier.glassSource(backdrop) else Modifier),
                ) {
                    CompositionLocalProvider(
                        LocalBottomInset provides inset,
                        LocalTopInset provides (if (topBar != null) barHeight else 0.dp),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            content()
                        }
                    }
                }
                // Blur berwarna di atas layar: isi yang digulir ke atas dikaburkan di belakang status bar / bar judul.
                if (topBar != null) {
                    TopScreenBlurForBar(backdrop = backdrop, barHeight = barHeight, modifier = Modifier.align(Alignment.TopCenter))
                } else {
                    TopScreenBlur(backdrop = backdrop, modifier = Modifier.align(Alignment.TopCenter))
                }
                if (topBar != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                    ) { topBar() }
                }
                CollapsingMiniPlayer(
                    player = player,
                    collapsed = bars.collapsed,
                    onOpenPlayer = onOpenPlayer,
                    onToggle = vm::togglePlay,
                    onNext = vm::next,
                    onPrevious = vm::previousItem,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
        }
    }
}

/** Halaman penuh dengan panah kembali di kiri atas, judul, dan mini player di bawah. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPage(
    title: String,
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    content: @Composable () -> Unit,
) {
    PageBackHandler(onBack = onBack)
    // Scroll ke bawah: pemutar mini mengecil; scroll ke atas: membesar lagi.
    val bars = rememberBarsCollapse(countUnconsumed = true)
    PageSurface {
        MiniPlayerScaffold(
            topBar = {
                TopAppBar(
                    colors = translucentBarColors(),
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("Kembali"))
                        }
                    },
                )
            },
            bars = bars,
            vm = vm,
            player = player,
            onOpenPlayer = onOpenPlayer,
            content = content,
        )
    }
}
