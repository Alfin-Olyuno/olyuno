package app.tunes.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.data.IslandColor
import app.tunes.data.IslandButtonStyle
import app.tunes.data.IslandPrefsStore
import app.tunes.data.IslandShape
import app.tunes.data.IslandStyle
import kotlin.math.roundToInt
import androidx.compose.ui.draw.alpha
import android.widget.Toast
import app.tunes.i18n.t
import app.tunes.player.PlayerState

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/**
 * Buka layar izin "Tampilkan di atas aplikasi lain". Urutan percobaan supaya jalan di berbagai versi Android / ROM:
 * 1) langsung ke halaman izin aplikasi ini, 2) daftar izin umum (sebagian ROM menolak URI paket),
 * 3) detail aplikasi (izin bisa diatur dari sana). Android < 6 tidak butuh izin ini sama sekali.
 */
private fun openOverlaySettings(context: Context) {
    val pkg = Uri.parse("package:" + context.packageName)
    val attempts = listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // coba cara berikutnya
        }
    }
}

/** Baris izin: lingkaran silang merah (belum) / centang hijau (sudah), deskripsi status, panah di kanan. */
@Composable
private fun OverlayPermissionRow(granted: Boolean, onClick: () -> Unit) {
    val badge by animateColorAsState(
        if (granted) Color(0xFF2FB36B) else MaterialTheme.colorScheme.error,
        tween(250),
        label = "overlayPermBadge",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(badge),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = granted, label = "overlayPermIcon") { ok ->
                Icon(
                    if (ok) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(t("Izinkan tampil di atas aplikasi lain"), style = MaterialTheme.typography.titleMedium)
            Text(
                if (granted) t("Diizinkan") else t("Belum diizinkan"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Halaman Dynamic Island: teks "Dynamic Island" di paling atas, lalu "Aktifkan Dynamic Island" [toggle].
 * Menyalakan toggle membuka izin sistem "Tampilkan di atas aplikasi lain". Selama izin belum diberikan,
 * toggle tetap mati; begitu izin diberikan dan pengguna kembali ke aplikasi, toggle menyala.
 */
@Composable
fun DynamicIslandPage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val context = LocalContext.current
    val enabledPref by vm.islandEnabled.collectAsStateWithLifecycle()
    val islandStyle by vm.islandStyle.collectAsStateWithLifecycle()
    val islandColor by vm.islandColor.collectAsStateWithLifecycle()
    val islandCustom by vm.islandCustomColor.collectAsStateWithLifecycle()
    val islandCorner by vm.islandCorner.collectAsStateWithLifecycle()
    val islandOpacity by vm.islandOpacity.collectAsStateWithLifecycle()
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var showColor by rememberSaveable { mutableStateOf(false) }
    var showShape by rememberSaveable { mutableStateOf(false) }
    var showButtons by rememberSaveable { mutableStateOf(false) }
    val islandButtonStyle by vm.islandButtonStyle.collectAsStateWithLifecycle()
    val islandPauseHideSec by vm.islandPauseHideSec.collectAsStateWithLifecycle()
    val islandCollapseSec by vm.islandCollapseSec.collectAsStateWithLifecycle()
    val islandOnlyWhenPlaying by vm.islandOnlyWhenPlaying.collectAsStateWithLifecycle()
    val islandShowButtons by vm.islandShowButtons.collectAsStateWithLifecycle()
    val islandShowArtist by vm.islandShowArtist.collectAsStateWithLifecycle()
    val islandShowTitle by vm.islandShowTitle.collectAsStateWithLifecycle()
    val islandShape by vm.islandShape.collectAsStateWithLifecycle()
    val islandCardWidth by vm.islandCardWidth.collectAsStateWithLifecycle()
    val islandTextScale by vm.islandTextScale.collectAsStateWithLifecycle()
    // Lebar terbesar yang muat di layar (kartu besar menyisakan 12dp di tiap sisi, maks 400dp).
    val maxCardW = minOf(IslandPrefsStore.MAX_CARD_WIDTH, (LocalConfiguration.current.screenWidthDp - 24).toFloat())
    val minCardW = IslandPrefsStore.MIN_CARD_WIDTH.coerceAtMost(maxCardW)
    val cardWidthDp = islandCardWidth.coerceIn(minCardW, maxCardW)
    var granted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // true = pengguna baru menekan toggle dan dikirim ke pengaturan izin; menunggu hasilnya saat kembali.
    var pendingEnable by rememberSaveable { mutableStateOf(false) }

    val owner = context.findLifecycleOwner()
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = Settings.canDrawOverlays(context)
                if (granted && pendingEnable) vm.setIslandEnabled(true)
                // Izin dicabut dari pengaturan sistem: toggle ikut mati.
                if (!granted && vm.islandEnabled.value) vm.setIslandEnabled(false)
                pendingEnable = false
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    val checked = enabledPref && granted

    fun onToggle(want: Boolean) {
        when {
            !want -> vm.setIslandEnabled(false)
            Settings.canDrawOverlays(context) -> vm.setIslandEnabled(true)
            else -> {
                // Belum diizinkan: buka halaman izin; toggle tetap mati sampai izin diberikan.
                pendingEnable = true
                openOverlaySettings(context)
            }
        }
    }

    SubPage(title = t("Dynamic Island"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            SectionTitle(t("Dynamic Island"))
            MenuGroup {
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.Layers, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Aktifkan Dynamic Island"),
                    checked = checked,
                    onChange = ::onToggle,
                )
                MenuDivider()
                OverlayPermissionRow(granted = granted, onClick = { openOverlaySettings(context) })
            }
            // Pilihan gaya: iPhone (bawaan) / Kaca / Biasa. Berlaku langsung untuk Dynamic Island yang sedang tampil.
            IslandStyleCard(current = islandStyle, onSelect = vm::setIslandStyle)

            // Jarak + "Preview": tombol membuka modal berisi pratinjau island kecil & besar yang hidup.
            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Preview"))
            MenuGroup {
                MenuItem(Icons.Rounded.Visibility, t("Preview Dynamic Island"), { showPreview = true })
            }

            // Jarak + "Tampilan Dynamic Island": pengaturan warna kartu island.
            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Tampilan Dynamic Island"))
            MenuGroup {
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.Palette, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Warna kartu"),
                    onClick = { showColor = true },
                ) {
                    ValueText(t(islandColor.label))
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MenuDivider()
                // Bentuk Dynamic Island >: modal pilihan Persegi membulat / Bulat (kartu kecil).
                MenuRow(
                    leading = { Icon(Icons.Rounded.Category, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Bentuk kartu"),
                    onClick = { showShape = true },
                ) {
                    ValueText(t(islandShape.label))
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MenuDivider()
                // Kelengkungan sudut: 0..100%, 100% = sudut bawaan gaya. Berlaku langsung ke island yang sedang tampil.
                IslandSliderRow(
                    icon = Icons.Rounded.RoundedCorner,
                    label = t("Kelengkungan sudut"),
                    value = islandCorner,
                    range = 0f..1f,
                    valueText = "${(islandCorner * 100f).roundToInt()}%",
                    startText = "0%",
                    endText = "100%",
                    changed = islandCorner < 0.995f,
                    onChange = { vm.setIslandCorner(it, false) },
                    onFinished = { vm.setIslandCorner(vm.islandCorner.value, true) },
                    onReset = { vm.setIslandCorner(1f, true) },
                )
                MenuDivider()
                // Opasitas: 30..100%, 100% = tidak tembus.
                IslandSliderRow(
                    icon = Icons.Rounded.Opacity,
                    label = t("Opasitas"),
                    value = islandOpacity,
                    range = IslandPrefsStore.MIN_OPACITY..1f,
                    valueText = "${(islandOpacity * 100f).roundToInt()}%",
                    startText = "${(IslandPrefsStore.MIN_OPACITY * 100f).roundToInt()}%",
                    endText = "100%",
                    changed = islandOpacity < 0.995f,
                    onChange = { vm.setIslandOpacity(it, false) },
                    onFinished = { vm.setIslandOpacity(vm.islandOpacity.value, true) },
                    onReset = { vm.setIslandOpacity(1f, true) },
                )
            }

            // Jarak + "Kartu Dynamic Island": ukuran kartu besar (versi 2).
            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Kartu Dynamic Island"))
            MenuGroup {
                // Lebar kartu: mengubah kebesaran kartu besar (tinggi, teks, tombol ikut sebanding). Bawaan = terbesar
                // yang muat di layar (maks 400dp).
                IslandSliderRow(
                    icon = Icons.Rounded.AspectRatio,
                    label = t("Lebar kartu"),
                    value = cardWidthDp,
                    range = minCardW..maxCardW,
                    valueText = "${cardWidthDp.roundToInt()} dp",
                    startText = "${minCardW.roundToInt()} dp",
                    endText = "${maxCardW.roundToInt()} dp",
                    changed = cardWidthDp < maxCardW - 0.5f,
                    onChange = { vm.setIslandCardWidth(it, false) },
                    onFinished = { vm.setIslandCardWidth(vm.islandCardWidth.value, true) },
                    onReset = { vm.setIslandCardWidth(IslandPrefsStore.MAX_CARD_WIDTH, true) },
                )
                MenuDivider()
                // Ukuran teks: 80..130%, 100% = ukuran bawaan. Berlaku untuk judul, artis, dan waktu di island.
                IslandSliderRow(
                    icon = Icons.Rounded.TextFields,
                    label = t("Ukuran teks"),
                    value = islandTextScale,
                    range = IslandPrefsStore.MIN_TEXT_SCALE..IslandPrefsStore.MAX_TEXT_SCALE,
                    valueText = "${(islandTextScale * 100f).roundToInt()}%",
                    startText = "${(IslandPrefsStore.MIN_TEXT_SCALE * 100f).roundToInt()}%",
                    endText = "${(IslandPrefsStore.MAX_TEXT_SCALE * 100f).roundToInt()}%",
                    changed = kotlin.math.abs(islandTextScale - 1f) > 0.005f,
                    onChange = { vm.setIslandTextScale(it, false) },
                    onFinished = { vm.setIslandTextScale(vm.islandTextScale.value, true) },
                    onReset = { vm.setIslandTextScale(1f, true) },
                )
                MenuDivider()
                // Gaya tombol >: modal pilihan Terisi / Garis tepi (hanya untuk Dynamic Island).
                MenuRow(
                    leading = { Icon(Icons.Rounded.PlayCircle, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Gaya tombol"),
                    onClick = { showButtons = true },
                ) {
                    ValueText(t(islandButtonStyle.label))
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MenuDivider()
                // Tampilkan tombol: mati = kartu besar tanpa tombol sebelumnya/putar/berikutnya.
                MenuRow(
                    leading = { Icon(Icons.Rounded.TouchApp, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Tampilkan tombol"),
                    checked = islandShowButtons,
                    onChange = vm::setIslandShowButtons,
                )
                MenuDivider()
                // Tampilkan nama artis: mati = nama artis tidak tampil di kartu besar.
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Tampilkan nama artis"),
                    checked = islandShowArtist,
                    onChange = vm::setIslandShowArtist,
                )
                MenuDivider()
                // Tampilkan nama lagu: mati = judul tidak tampil di kartu kecil maupun besar.
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Tampilkan nama lagu"),
                    checked = islandShowTitle,
                    onChange = vm::setIslandShowTitle,
                )
            }

            // Jarak + "Perilaku kartu": kapan kartu muncul.
            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Perilaku kartu"))
            MenuGroup {
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.PlayCircle, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Tampilkan kartu hanya saat diputar"),
                    description = t("Kartu langsung disembunyikan ketika pemutaran dijeda"),
                    checked = islandOnlyWhenPlaying,
                    onChange = vm::setIslandOnlyWhenPlaying,
                )
                MenuDivider()
                // Sembunyikan setelah dijeda N detik (0..300, bawaan 30). Abu-abu bila "hanya saat diputar" mati.
                IslandSliderRow(
                    icon = Icons.Rounded.Timer,
                    label = t("Kartu disembunyikan setelah dijeda selama"),
                    value = islandPauseHideSec,
                    range = 0f..IslandPrefsStore.MAX_PAUSE_HIDE_SEC,
                    valueText = if (islandPauseHideSec.roundToInt() == 0) t("Langsung") else "${islandPauseHideSec.roundToInt()} ${t("detik")}",
                    startText = t("Langsung"),
                    endText = "${IslandPrefsStore.MAX_PAUSE_HIDE_SEC.roundToInt()} ${t("detik")}",
                    changed = islandPauseHideSec.roundToInt() != IslandPrefsStore.DEFAULT_PAUSE_HIDE_SEC.roundToInt(),
                    onChange = { vm.setIslandPauseHideSec(it, false) },
                    onFinished = { vm.setIslandPauseHideSec(vm.islandPauseHideSec.value, true) },
                    onReset = { vm.setIslandPauseHideSec(IslandPrefsStore.DEFAULT_PAUSE_HIDE_SEC, true) },
                    enabled = islandOnlyWhenPlaying,
                    onDisabledClick = {
                        Toast.makeText(context, t("Aktifkan dulu \"Tampilkan kartu hanya saat diputar\""), Toast.LENGTH_SHORT).show()
                    },
                )
                MenuDivider()
                // Kartu mengecil otomatis setelah N detik (1..15, bawaan 5).
                IslandSliderRow(
                    icon = Icons.Rounded.Timer,
                    label = t("Kartu mengecil otomatis setelah"),
                    value = islandCollapseSec,
                    range = IslandPrefsStore.MIN_COLLAPSE_SEC..IslandPrefsStore.MAX_COLLAPSE_SEC,
                    valueText = "${islandCollapseSec.roundToInt()} ${t("detik")}",
                    startText = "${IslandPrefsStore.MIN_COLLAPSE_SEC.roundToInt()} ${t("detik")}",
                    endText = "${IslandPrefsStore.MAX_COLLAPSE_SEC.roundToInt()} ${t("detik")}",
                    changed = islandCollapseSec.roundToInt() != IslandPrefsStore.DEFAULT_COLLAPSE_SEC.roundToInt(),
                    onChange = { vm.setIslandCollapseSec(it, false) },
                    onFinished = { vm.setIslandCollapseSec(vm.islandCollapseSec.value, true) },
                    onReset = { vm.setIslandCollapseSec(IslandPrefsStore.DEFAULT_COLLAPSE_SEC, true) },
                )
            }
        }
    }

    if (showPreview) {
        IslandPreviewDialog(
            style = islandStyle,
            color = islandColor,
            custom = islandCustom,
            corner = islandCorner,
            shape = islandShape,
            bigScale = cardWidthDp / maxCardW,
            textScale = islandTextScale,
            buttonStyle = islandButtonStyle,
            showButtons = islandShowButtons,
            showArtist = islandShowArtist,
            showTitle = islandShowTitle,
            opacity = islandOpacity,
            onDismiss = { showPreview = false },
        )
    }

    if (showButtons) {
        IslandButtonStyleDialog(
            current = islandButtonStyle,
            onSelect = vm::setIslandButtonStyle,
            onDismiss = { showButtons = false },
        )
    }

    if (showShape) {
        IslandShapeDialog(
            current = islandShape,
            onSelect = vm::setIslandShape,
            onDismiss = { showShape = false },
        )
    }

    if (showColor) {
        IslandColorDialog(
            color = islandColor,
            custom = islandCustom,
            onColor = vm::setIslandColor,
            onCustom = vm::setIslandCustomColor,
            onDismiss = { showColor = false },
        )
    }
}

/** Modal "Gaya tombol": Terisi (bawaan) dan Garis tepi, masing-masing dengan deskripsi dan radio. */
@Composable
private fun IslandButtonStyleDialog(
    current: IslandButtonStyle,
    onSelect: (IslandButtonStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = { Text(t("Gaya tombol")) },
        text = {
            DialogDim()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (style in IslandButtonStyle.entries) {
                    DialogChoiceRow(
                        label = t(style.label),
                        selected = style == current,
                        description = t(style.description),
                    ) { onSelect(style) }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(t("Tutup")) } },
    )
}

/** Modal "Bentuk Dynamic Island": Persegi membulat (bawaan) dan Bulat, masing-masing dengan deskripsi dan radio. */
@Composable
private fun IslandShapeDialog(
    current: IslandShape,
    onSelect: (IslandShape) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = { Text(t("Bentuk kartu")) },
        text = {
            DialogDim()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (shape in IslandShape.entries) {
                    DialogChoiceRow(
                        label = t(shape.label),
                        selected = shape == current,
                        description = t(shape.description),
                    ) { onSelect(shape) }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(t("Tutup")) } },
    )
}

/**
 * Modal "Warna Dynamic Island": Default (hitam, seperti iPhone), Putih (teks hitam), Kustom. Memilih Kustom
 * melebarkan modal (animateContentSize) dan menampilkan pemilih warna yang sama dengan sheet "Warna tampilan".
 */
@Composable
private fun IslandColorDialog(
    color: IslandColor,
    custom: Int,
    onColor: (IslandColor) -> Unit,
    onCustom: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = { Text(t("Warna kartu")) },
        text = {
            DialogDim()
            Column(
                Modifier
                    .smoothContentSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                for (c in IslandColor.entries) {
                    IslandColorRow(
                        label = t(c.label),
                        description = if (c == IslandColor.Custom) null else t(c.description),
                        selected = c == color,
                        onClick = { onColor(c) },
                    )
                }
                if (color == IslandColor.Custom) {
                    CustomColorPicker(
                        argb = custom,
                        onChange = onCustom,
                        swatchSize = 32.dp,
                        horizontalPadding = 0.dp,
                    )
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(t("Tutup")) } },
    )
}

/**
 * Baris pengaturan dengan slider: ikon dalam kotak tonal, label, pil nilai ([valueText]) di kanan, lalu slider
 * selebar baris dengan batas bawah / atas ([startText] / [endText]) di bawahnya. Kalau [changed] (nilai bukan
 * bawaan), pil menampilkan ikon atur ulang dan bisa diketuk untuk mengembalikan ke bawaan.
 */
@Composable
private fun IslandSliderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    startText: String,
    endText: String,
    changed: Boolean,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    onReset: () -> Unit,
    enabled: Boolean = true,
    onDisabledClick: () -> Unit = {},
) {
    // Nonaktif: abu-abu, dan ketukan di mana saja pada baris memanggil [onDisabledClick].
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier
                else Modifier.alpha(0.38f).clickable(onClick = onDisabledClick),
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(enabled = changed && enabled, onClick = onReset)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(valueText, style = MaterialTheme.typography.labelLarge)
                SmoothVisibility(visible = changed) {
                    Icon(
                        Icons.Rounded.RestartAlt,
                        contentDescription = t("Atur ulang"),
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        GuardedSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onFinished,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                startText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                endText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Satu pilihan warna: label, deskripsi kecil di bawahnya (kalau ada), dan tombol radio di kanan. */
@Composable
private fun IslandColorRow(label: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
    }
}

/** Kartu "gaya tampilan" Dynamic Island: judul + deskripsi gaya terpilih, lalu baris chip yang bisa digeser. */
@Composable
private fun IslandStyleCard(current: IslandStyle, onSelect: (IslandStyle) -> Unit) {
    MenuGroup {
        Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp)) {
            Crossfade(targetState = current, animationSpec = tween(200), label = "islandStyleText") { st ->
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(t(st.label), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        t(st.description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                for (st in IslandStyle.entries) {
                    StyleChip(label = t(st.label), selected = st == current, onClick = { onSelect(st) })
                }
            }
        }
    }
}

/** Satu chip pilihan: yang aktif putih (permukaan) dengan bayangan lembut, yang lain abu-abu. */
@Composable
private fun StyleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHighest,
        tween(220),
        label = "chipBg",
    )
    val textColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(220),
        label = "chipText",
    )
    val elevation by animateDpAsState(if (selected) 6.dp else 0.dp, tween(220), label = "chipElev")
    Box(
        Modifier
            .shadow(elevation, CircleShape, clip = false)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = textColor)
    }
}

/** Baris menu dengan deskripsi singkat di bawah label dan panah di kanan (dipakai di halaman Tampilan). */
@Composable
internal fun MenuItemWithDescription(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
