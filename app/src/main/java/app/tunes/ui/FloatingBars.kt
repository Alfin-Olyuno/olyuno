package app.tunes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.tunes.player.PlayerState
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.tunes.i18n.t

/**
 * Status mengecil / membesarnya bilah bawah, digerakkan oleh scroll isi halaman.
 *
 * Dipasang lewat `Modifier.nestedScroll(state)` pada isi halaman. Hanya gerakan scroll yang benar-benar
 * menggeser daftar yang dihitung (consumed), jadi halaman pendek yang tidak bisa di-scroll tidak membuat
 * bilah mengecil. Ada ambang kecil supaya sentuhan tipis tidak membuat bilah berkedip, dan ambang
 * membesar lebih kecil dari ambang mengecil supaya bilah cepat kembali saat scroll ke atas.
 */
@Stable
class BarsCollapseState(
    private val collapseAtPx: Float,
    private val expandAtPx: Float,
    // Halaman tanpa bilah tab (halaman penuh) sering pendek / tidak bisa di-scroll. Kalau true, gerakan jari
    // yang tidak terpakai isi halaman (sudah mentok atau halamannya muat satu layar) tetap dihitung.
    private val countUnconsumed: Boolean = false,
) : NestedScrollConnection {
    var collapsed by mutableStateOf(false)
        private set
    private var acc = 0f

    fun expand() {
        collapsed = false
        acc = 0f
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val dy = if (countUnconsumed) consumed.y + available.y else consumed.y
        if (dy == 0f) return Offset.Zero
        // Ganti arah = hitung ulang dari nol.
        if (acc != 0f && (dy < 0f) != (acc < 0f)) acc = 0f
        acc += dy
        if (acc <= -collapseAtPx) collapsed = true else if (acc >= expandAtPx) collapsed = false
        return Offset.Zero
    }
}

@Composable
fun rememberBarsCollapse(countUnconsumed: Boolean = false): BarsCollapseState {
    val density = LocalDensity.current.density
    return remember(density, countUnconsumed) {
        BarsCollapseState(collapseAtPx = 24f * density, expandAtPx = 12f * density, countUnconsumed = countUnconsumed)
    }
}

private val BarShape = RoundedCornerShape(28.dp)

// Ukuran (dp). Tinggi area bilah dibuat tetap = ukuran saat membesar, jadi isi halaman tidak bergeser
// saat bilah mengecil; hanya pemutar mini yang muncul / hilang yang mengubah tingginya (dengan animasi).
private const val NAV_H = 80
private const val MINI_H = 60
private const val DOT = 56
private const val SIDE = 12
private const val BOTTOM = 8
private const val GAP = 8

private fun lerpI(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()

/**
 * Latar bilah: kaca (blur) kalau gaya Kaca, selain itu warna solid; keduanya dengan garis tipis.
 * [motion] = nilai animasi tambahan yang menggerakkan bilah lewat `graphicsLayer` (di luar tarikan layar pemutar,
 * yang sudah otomatis ikut), supaya blur di dalam bilah digambar ulang selama bilah bergerak.
 */
@Composable
private fun Modifier.barSurface(
    shape: Shape,
    solid: Color,
    tintAlpha: Float,
    config: GlassConfig,
    motion: () -> Float = { 0f },
): Modifier {
    val sheet = LocalPlayerSheet.current
    val base = if (config.enabled) {
        glass(
            shape, glassBase(solid).copy(alpha = BAR_GLASS_ALPHA), config, minAlpha = BAR_GLASS_ALPHA,
            motion = { (sheet?.progress?.value ?: 0f) + motion() },
        )
    } else clip(shape).background(solid)
    return base.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
}

// Seberapa jauh isi pil memudar saat digeser tanpa lagu tetangga untuk ditampilkan (pecahan dari lebar pil).
private const val SWIPE_OUT = 0.6f

/**
 * Isi pil pemutar mini: versi penuh dan versi ringkas saling memudar mengikuti [progress]
 * (0 = penuh, 1 = mengecil). Yang tak terlihat dilepas dari komposisi supaya tidak menangkap sentuhan.
 *
 * Bisa digeser untuk ganti lagu seperti carousel: geser ke KIRI = lagu berikutnya, geser ke KANAN = lagu
 * sebelumnya. Selama digeser, lagu tetangga ikut meluncur masuk dari sisi berlawanan, jadi tidak ada momen
 * kosong. Dilepas cukup jauh (atau dengan lemparan cepat), kartu meluncur tuntas, lagu diganti, dan kartu
 * tetangga tepat menjadi lagu sekarang. Arah yang tidak ada lagunya terasa tertahan lalu memantul kembali.
 * Seluruh gerakan lewat `graphicsLayer`, tanpa layout ulang.
 */
@Composable
private fun MiniPillContent(
    player: PlayerState,
    song: app.tunes.innertube.SongItem,
    progress: Animatable<Float, AnimationVector1D>,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val showFull by remember { derivedStateOf { progress.value < 0.999f } }
    val showDot by remember { derivedStateOf { progress.value > 0.001f } }

    val smooth = LocalSmoothAnim.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    // Arah yang sedang digeser: -1 = kiri (berikutnya), 1 = kanan (sebelumnya), 0 = tidak ada.
    var swipeDir by remember { mutableIntStateOf(0) }
    // Arah swipe yang sudah dikomit dan sedang menunggu lagu berganti (0 = tidak ada), dan lagu asalnya.
    var committed by remember { mutableIntStateOf(0) }
    var committedFrom by remember { mutableStateOf("") }
    val canNext by rememberUpdatedState(player.hasNext)
    val canPrev by rememberUpdatedState(player.hasPrevious)
    val nextAction by rememberUpdatedState(onNext)
    val prevAction by rememberUpdatedState(onPrevious)
    val currentId by rememberUpdatedState(song.videoId)
    val maxResistPx = with(LocalDensity.current) { 24.dp.toPx() }
    val flingPx = with(LocalDensity.current) { 700.dp.toPx() }

    // Lagu sudah berganti setelah swipe dikomit: tetangga tadi sekarang jadi lagu sekarang di posisi 0.
    val stale = committed != 0 && song.videoId != committedFrom
    LaunchedEffect(song.videoId) {
        if (committed != 0 || swipeDir != 0 || offsetX.value != 0f) {
            offsetX.snapTo(0f)
            committed = 0
            swipeDir = 0
        }
    }

    // Lagu tetangga yang ikut terlihat saat digeser (tidak dipakai kalau shuffle: urutan acak tak sama dengan antrean).
    val neighbor: app.tunes.innertube.SongItem? = when {
        stale || player.shuffle || showDot -> null
        swipeDir < 0 && canNext -> player.queue.getOrNull(player.currentIndex + 1)
        swipeDir > 0 && canPrev -> player.queue.getOrNull(player.currentIndex - 1)
        else -> null
    }

    fun springBack() {
        scope.launch {
            if (smooth) offsetX.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
            else offsetX.snapTo(0f)
            if (committed == 0) swipeDir = 0
        }
    }

    fun commit(dir: Int) {
        scope.launch {
            if (!smooth) {
                offsetX.snapTo(0f)
                swipeDir = 0
                if (dir < 0) nextAction() else prevAction()
                return@launch
            }
            committed = dir
            committedFrom = currentId
            offsetX.animateTo(dir * widthPx, tween(200, easing = FastOutSlowInEasing))
            if (dir < 0) nextAction() else prevAction()
            // Pengaman: kalau lagu tak kunjung berganti (mis. gagal), kembalikan kartu ke tempatnya.
            delay(1200)
            if (committed != 0) {
                committed = 0
                offsetX.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                swipeDir = 0
            }
        }
    }

    fun settle(velocity: Float) {
        val x = offsetX.value
        val threshold = widthPx * 0.25f
        val goLeft = (x < -threshold || (velocity < -flingPx && x < 0f)) && canNext
        val goRight = (x > threshold || (velocity > flingPx && x > 0f)) && canPrev
        when {
            goLeft -> commit(-1)
            goRight -> commit(1)
            else -> springBack()
        }
    }

    val dragState = rememberDraggableState { dx ->
        if (committed == 0) {
            val x = offsetX.value
            val wanted = x + dx
            // Kiri butuh lagu berikutnya, kanan butuh lagu sebelumnya; kalau tidak ada, geseran tertahan.
            val blocked = (wanted < 0f && !canNext) || (wanted > 0f && !canPrev)
            val target = if (blocked) (x + dx * 0.3f).coerceIn(-maxResistPx, maxResistPx) else wanted
            val dir = if (target < 0f) -1 else if (target > 0f) 1 else 0
            if (dir != 0 && dir != swipeDir) swipeDir = dir
            scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetX.snapTo(target) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { if (committed == 0) offsetX.stop() },
                onDragStopped = { velocity -> if (committed == 0) settle(velocity) },
            )
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val x = if (stale) 0f else offsetX.value
                    translationX = x
                    alpha = when {
                        stale -> 1f
                        neighbor != null -> (1f - 0.45f * abs(x) / size.width).coerceIn(0f, 1f)
                        else -> (1f - abs(x) / (size.width * SWIPE_OUT)).coerceIn(0f, 1f)
                    }
                },
        ) {
            if (showFull) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .requiredHeight(MINI_H.dp)
                        .align(Alignment.Center)
                        .graphicsLayer { alpha = (1f - progress.value * 2.4f).coerceIn(0f, 1f) },
                ) { MiniPlayerBody(player, song, onToggle, onNext) }
            }
            if (showDot) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = (progress.value * 2.4f - 1.4f).coerceIn(0f, 1f) },
                ) { MiniPlayerCompact(player, song, onToggle) }
            }
        }
        // Lagu tetangga: meluncur masuk dari sisi berlawanan mengikuti geseran, berhenti di posisi 0.
        if (neighbor != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .requiredHeight(MINI_H.dp)
                    .align(Alignment.Center)
                    .graphicsLayer {
                        val w = size.width
                        translationX = offsetX.value + if (swipeDir < 0) w else -w
                        alpha = (0.55f + 0.45f * abs(offsetX.value) / w).coerceIn(0f, 1f)
                    },
            ) { MiniPlayerBody(player, neighbor, {}, {}, showProgress = false) }
        }
    }
}

// Jarak pemutar mini di halaman penuh: sama dengan kartu MiniPlayer biasa (atas 4dp, bawah 10dp).
private const val PAGE_TOP = 4
private const val PAGE_BOTTOM = 10

/** Selisih tinggi pemutar mini di bilah utama (di atas bilah tab) dan di halaman penuh (tanpa bilah tab). */
private val TAB_RISE = (BOTTOM + NAV_H + GAP - PAGE_BOTTOM).dp

/**
 * Pemutar mini untuk halaman penuh (halaman tanpa bilah tab). Pemutar ini tidak pernah mengecil: tetap utuh
 * walau isi halaman di-scroll ke atas atau ke bawah, dan walau animasi smooth menyala. Mengecil hanya
 * berlaku di [FloatingBars], saat pemutar mini dan bilah tab sama-sama tampil. Tinggi areanya tetap, jadi
 * isi halaman tidak bergeser.
 */
@Composable
fun CollapsingMiniPlayer(
    player: PlayerState,
    collapsed: Boolean,
    onOpenPlayer: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = player.current
    val glass = LocalGlass.current
    val density = LocalDensity.current

    val smooth = LocalSmoothAnim.current
    // Pemutar mini tanpa bilah tab di sampingnya tidak pernah mengecil: tetap utuh walau halaman di-scroll
    // (mengecil hanya berlaku saat pemutar mini dan bilah tab sama-sama ada, lihat [FloatingBars]).
    val shrink = false
    val progress = remember { Animatable(0f) }
    LaunchedEffect(shrink, smooth) {
        val target = if (shrink) 1f else 0f
        if (smooth) progress.animateTo(target, tween(320, easing = FastOutSlowInEasing))
        else progress.snapTo(target)
    }
    // Halaman baru dibuka dari tab dasar (bilah tab hilang): pemutar mini mulai dari posisinya di atas bilah tab
    // lalu meluncur turun ke posisi halaman. Nilai awal sudah benar sejak frame pertama, jadi tidak berkedip.
    val fromTabs = LocalMiniFromTabs.current
    val tabRise = remember { Animatable(if (fromTabs && smooth) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (tabRise.value > 0f) tabRise.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
    }
    val areaHeight by animateDpAsState(
        targetValue = if (song != null) (PAGE_TOP + MINI_H + PAGE_BOTTOM).dp else 0.dp,
        animationSpec = if (smooth) tween(260, easing = FastOutSlowInEasing) else snap(),
        label = "pageMiniHeight",
    )

    val side = with(density) { SIDE.dp.roundToPx() }
    val bottom = with(density) { PAGE_BOTTOM.dp.roundToPx() }
    val gap = with(density) { GAP.dp.roundToPx() }
    val miniH = with(density) { MINI_H.dp.roundToPx() }
    val dot = with(density) { DOT.dp.roundToPx() }

    Box(modifier.fillMaxWidth().height(areaHeight)) {
        if (song != null) {
            // Pembungkus diam yang menangkap tarikan; kartunya sendiri ikut bergerak mengikuti jari (followPlayerSheet).
            Box(
                Modifier
                    .layout { measurable, c ->
                        val p = progress.value
                        val full = c.maxWidth - 2 * side
                        // Lebar akhir sama dengan pil di bilah utama, jadi posisinya konsisten di semua halaman.
                        val w = lerpI(full, full - dot - gap, p)
                        val h = lerpI(miniH, dot, p)
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(c.maxWidth, c.maxHeight) { placeable.place(side, c.maxHeight - bottom - h) }
                    }
                    .graphicsLayer { translationY = -tabRise.value * TAB_RISE.toPx() }
                    .dragToOpenPlayer(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .followPlayerSheet(-1f)
                        .barSurface(
                            BarShape, MaterialTheme.colorScheme.surfaceContainerHigh, 0.55f, glass,
                            motion = { tabRise.value },
                        )
                        .clickable(onClick = onOpenPlayer),
                ) {
                    MiniPillContent(player, song, progress, onToggle, onNext, onPrevious)
                }
            }
        }
    }
}

/**
 * Bilah bawah melayang. Saat [collapsed] dan ada lagu (pemutar mini + bilah tab sama-sama tampil): bilah navigasi mengecil jadi bulatan di kanan bawah yang hanya
 * menampilkan ikon tab aktif (ketuk = membesar lagi), dan pemutar mini (kalau ada lagu) turun menjadi
 * pil di kiri bawah, di samping bulatan itu.
 *
 * Satu nilai progres (0 = penuh, 1 = mengecil) menggerakkan ukuran dan posisi lewat fase layout / draw
 * saja, tanpa rekomposisi per frame. Isi penuh dan isi ringkas saling memudar; yang tak terlihat dilepas
 * dari komposisi supaya tidak menangkap sentuhan.
 */
@Composable
fun FloatingBars(
    player: PlayerState,
    collapsed: Boolean,
    selectedTab: Int,
    tabs: List<Pair<ImageVector, String>>,
    infoUnread: Int,
    onTab: (Int) -> Unit,
    onExpand: () -> Unit,
    onOpenPlayer: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    /** Ada halaman penuh yang menutupi bilah ini (bilah tab tidak terlihat). */
    pageOpen: Boolean = false,
) {
    val song = player.current
    val glass = LocalGlass.current
    val density = LocalDensity.current

    val smooth = LocalSmoothAnim.current
    // Mengecil hanya kalau pemutar mini DAN bilah tab sama-sama ada. Tanpa pemutar mini, bilah tab tetap
    // utuh walau halaman di-scroll. Animasi smooth mati = juga tidak pernah mengecil.
    val shrink = collapsed && smooth && song != null
    val progress = remember { Animatable(if (shrink) 1f else 0f) }
    LaunchedEffect(shrink, smooth) {
        val target = if (shrink) 1f else 0f
        if (smooth) progress.animateTo(target, tween(320, easing = FastOutSlowInEasing))
        else progress.snapTo(target)
    }
    val showFull by remember { derivedStateOf { progress.value < 0.999f } }
    val showDot by remember { derivedStateOf { progress.value > 0.001f } }
    // Halaman penuh ditutup (bilah tab muncul lagi): pemutar mini mulai dari posisinya di halaman (lebih rendah)
    // lalu meluncur naik ke atas bilah tab, keluar dari balik bilah. Saat halaman terbuka nilainya langsung 1
    // (tersembunyi di balik halaman), jadi tidak ada animasi saat membuka.
    val pageDrop by animateFloatAsState(
        targetValue = if (pageOpen) 1f else 0f,
        animationSpec = if (pageOpen || !smooth) snap() else tween(320, easing = FastOutSlowInEasing),
        label = "pageDrop",
    )
    val barHeight by animateDpAsState(
        targetValue = if (song != null) (BOTTOM + NAV_H + GAP + MINI_H).dp else (BOTTOM + NAV_H).dp,
        animationSpec = if (smooth) tween(260, easing = FastOutSlowInEasing) else snap(),
        label = "barHeight",
    )

    val side = with(density) { SIDE.dp.roundToPx() }
    val bottom = with(density) { BOTTOM.dp.roundToPx() }
    val gap = with(density) { GAP.dp.roundToPx() }
    val navH = with(density) { NAV_H.dp.roundToPx() }
    val miniH = with(density) { MINI_H.dp.roundToPx() }
    val dot = with(density) { DOT.dp.roundToPx() }

    Box(modifier.fillMaxWidth().height(barHeight)) {
        if (song != null) {
            // Pembungkus diam yang menangkap tarikan; kartunya sendiri ikut bergerak mengikuti jari (followPlayerSheet).
            Box(
                Modifier
                    .layout { measurable, c ->
                        val p = progress.value
                        val full = c.maxWidth - 2 * side
                        val w = lerpI(full, full - dot - gap, p)
                        val h = lerpI(miniH, dot, p)
                        val bottomY = lerpI(bottom + navH + gap, bottom, p)
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(c.maxWidth, c.maxHeight) { placeable.place(side, c.maxHeight - bottomY - h) }
                    }
                    .graphicsLayer { translationY = pageDrop * (1f - progress.value) * TAB_RISE.toPx() }
                    .dragToOpenPlayer(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .followPlayerSheet(-1f)
                        .barSurface(
                            BarShape, MaterialTheme.colorScheme.surfaceContainerHigh, 0.55f, glass,
                            motion = { pageDrop * (1f - progress.value) + progress.value },
                        )
                        .clickable(onClick = onOpenPlayer),
                ) {
                    MiniPillContent(player, song, progress, onToggle, onNext, onPrevious)
                }
            }
        }

        Box(
            Modifier
                .layout { measurable, c ->
                    val p = progress.value
                    val w = lerpI(c.maxWidth - 2 * side, dot, p)
                    val h = lerpI(navH, dot, p)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(c.maxWidth, c.maxHeight) {
                        placeable.place(c.maxWidth - side - w, c.maxHeight - bottom - h)
                    }
                }
                .followPlayerSheet(1f)
                .barSurface(
                    BarShape, MaterialTheme.colorScheme.surfaceContainer, 0.6f, glass,
                    motion = { progress.value },
                ),
        ) {
            if (showFull) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .requiredHeight(NAV_H.dp)
                        .align(Alignment.Center)
                        .graphicsLayer { alpha = (1f - progress.value * 2.4f).coerceIn(0f, 1f) },
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        tabs.forEachIndexed { index, (icon, label) ->
                            NavTabItem(
                                selected = selectedTab == index,
                                icon = icon,
                                label = label,
                                onClick = { onTab(index) },
                                modifier = Modifier.weight(1f),
                                // Tab Anda memuat menu Informasi: tampilkan jumlah pesan yang belum dibuka.
                                badgeCount = if (index == 3) infoUnread else 0,
                            )
                        }
                    }
                }
            }
            if (showDot) {
                val (icon, label) = tabs[selectedTab]
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = (progress.value * 2.4f - 1.4f).coerceIn(0f, 1f) }
                        .clickable(onClick = onExpand),
                    contentAlignment = Alignment.Center,
                ) {
                    BadgedBox(badge = {
                        if (selectedTab == 3 && infoUnread > 0) Badge { Text(infoUnread.toString()) }
                    }) {
                        Icon(icon, contentDescription = t("Buka bilah navigasi (%s)", label), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private const val FAB = 48

/**
 * Tombol bulat "muat ulang" (ikon panah menyilang) di atas kanan pemutar mini / bilah tab.
 *
 * [visible] false (isi halaman di-scroll ke bawah) = tombol meluncur turun keluar layar sambil memudar;
 * true (scroll ke atas) = naik lagi. Saat benar-benar tersembunyi tombol dilepas dari komposisi supaya
 * tidak menangkap sentuhan. [bottomInset] = tinggi bilah bawah (isi Scaffold), jadi tombol selalu tepat di
 * atas pemutar mini, juga saat pemutar mini muncul / hilang. Ikut turun / memudar saat layar pemutar ditarik.
 * Animasi mati kalau pengaturan "Animasi smooth" mati.
 */
@Composable
fun RefreshFab(
    visible: Boolean,
    bottomInset: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val smooth = LocalSmoothAnim.current
    val glass = LocalGlass.current
    val shown by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (smooth) tween(if (visible) 280 else 220, easing = FastOutSlowInEasing) else snap(),
        label = "refreshFab",
    )
    val spin = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    if (shown <= 0.001f) return

    Box(
        modifier
            .padding(end = SIDE.dp, bottom = bottomInset + GAP.dp)
            .size(FAB.dp)
            .graphicsLayer {
                // Hilang = meluncur ke bawah (melewati bilah di bawahnya) + memudar.
                translationY = (1f - shown) * (FAB.dp.toPx() + (GAP + 12).dp.toPx())
                alpha = shown
            }
            .followPlayerSheet(-1f)
            .barSurface(CircleShape, MaterialTheme.colorScheme.surfaceContainerHigh, 0.55f, glass, motion = { shown })
            .clip(CircleShape)
            .clickable {
                onClick()
                scope.launch {
                    spin.snapTo(0f)
                    if (smooth) spin.animateTo(360f, tween(520, easing = FastOutSlowInEasing))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Shuffle,
            contentDescription = t("Muat ulang"),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer { rotationZ = spin.value },
        )
    }
}
