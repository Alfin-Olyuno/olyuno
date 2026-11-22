package app.tunes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.data.ColorMode
import app.tunes.data.UiStyle
import app.tunes.player.PlayerState
import app.tunes.i18n.t

/**
 * Halaman Tampilan: teks "Tampilan app" di paling atas, lalu tombol "Warna tampilan" (gaya sama dengan
 * tombol "Pilih bahasa" di layar lirik) yang membuka bottom sheet pilihan warna.
 */
@Composable
fun AppearancePage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenDynamicIsland: () -> Unit,
) {
    val mode by vm.colorMode.collectAsStateWithLifecycle()
    val custom by vm.customColor.collectAsStateWithLifecycle()
    val style by vm.uiStyle.collectAsStateWithLifecycle()
    val smoothAnim by vm.smoothAnim.collectAsStateWithLifecycle()
    val highRefresh by vm.highRefresh.collectAsStateWithLifecycle()
    val batterySaver by vm.batterySaver.collectAsStateWithLifecycle()
    var showBatteryDialog by rememberSaveable { mutableStateOf(false) }
    val bgThumbnail by vm.bgThumbnail.collectAsStateWithLifecycle()
    val playerBg by vm.playerBgColor.collectAsStateWithLifecycle()
    val lyricsBg by vm.lyricsBg.collectAsStateWithLifecycle()
    val lyricsPanel by vm.lyricsPanelColor.collectAsStateWithLifecycle()
    val lyricsPanelCustom by vm.lyricsPanelCustom.collectAsStateWithLifecycle()
    var showLyricsPanelSheet by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var showPlayerBgDialog by rememberSaveable { mutableStateOf(false) }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var showStyleSheet by rememberSaveable { mutableStateOf(false) }

    SubPage(title = t("Tampilan"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            SectionTitle(t("Tampilan app"))
            MenuGroup {
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.Palette, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Warna tampilan"),
                    onClick = { showSheet = true },
                ) { ValueText(t(mode.label)) }
                MenuDivider()
                MenuRow(
                    leading = { Icon(Icons.Rounded.Brush, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Gaya tampilan"),
                    onClick = { showStyleSheet = true },
                ) { ValueText(t(style.label)) }
                MenuDivider()
                // Animasi smooth [toggle]: mati = bilah navigasi & pemutar mini tidak beranimasi / mengecil.
                MenuRow(
                    leading = { Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Animasi smooth"),
                    checked = smoothAnim,
                    onChange = vm::setSmoothAnim,
                )
                MenuDivider()
                MenuItem(Icons.Rounded.Palette, t("Tema"), onOpenTheme)
                MenuDivider()
                // Refresh rate tinggi [toggle]: nyala = layar dikunci di refresh rate maksimum HP.
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Refresh rate tinggi"),
                    checked = highRefresh,
                    onChange = vm::setHighRefresh,
                )
                MenuDivider()
                MenuItemWithDescription(
                    icon = Icons.Rounded.Smartphone,
                    label = t("Dynamic Island"),
                    description = t("Info lagu melayang di atas layar"),
                    onClick = onOpenDynamicIsland,
                )
            }

            // Bagian terpisah (ada jarak) untuk tampilan layar pemutar lagu.
            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Tampilan player"))
            MenuGroup {
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Background thumbnail"),
                    checked = bgThumbnail,
                    onChange = vm::setBgThumbnail,
                )
                MenuDivider()
                // Kustom warna background: hanya bisa dipakai saat Background thumbnail mati (abu-abu + toast kalau nyala).
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.FormatColorFill, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Kustom warna background"),
                    dimmed = bgThumbnail,
                    onClick = {
                        if (bgThumbnail) {
                            android.widget.Toast.makeText(context, t("Matikan Background thumbnail"), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            showPlayerBgDialog = true
                        }
                    },
                ) {
                    Spacer(Modifier.width(8.dp))
                    val c = playerBg
                    if (c != null) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                    } else {
                        ValueText("Otomatis")
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Bagian terpisah (ada jarak) untuk tampilan halaman lirik di layar pemutar.
            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Tampilan lirik"))
            MenuGroup {
                // Background tampilan lirik [toggle]: nyala = latar thumbnail; mati = panel warna di sekitar teks lirik.
                MenuRow(
                    leading = { Icon(Icons.Rounded.Lyrics, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Background tampilan lirik"),
                    checked = lyricsBg,
                    onChange = vm::setLyricsBg,
                )
                MenuDivider()
                // Kustom warna tampilan lirik >: hanya bisa dipakai saat toggle di atas mati (abu-abu + toast kalau nyala).
                MenuRow(
                    leading = { Icon(Icons.Rounded.FormatColorFill, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Kustom warna tampilan lirik"),
                    dimmed = lyricsBg,
                    onClick = {
                        if (lyricsBg) {
                            android.widget.Toast.makeText(context, t("Matikan Background tampilan lirik"), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            showLyricsPanelSheet = true
                        }
                    },
                ) {
                    Spacer(Modifier.width(8.dp))
                    if (lyricsPanelCustom) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(lyricsPanel))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                    } else {
                        ValueText("Otomatis")
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Bagian terpisah (ada jarak) untuk hemat baterai.
            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Baterai"))
            MenuGroup {
                // Penghemat baterai [toggle]: nyala harus lewat modal peringatan; mati langsung mengembalikan ke bawaan.
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.BatterySaver, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Penghemat baterai"),
                    description = t("Kurangi efek visual dan animasi untuk menghemat daya."),
                    checked = batterySaver,
                    onChange = { on -> if (on) showBatteryDialog = true else vm.setBatterySaver(false) },
                )
            }
        }
    }

    if (showBatteryDialog) {
        BatterySaverDialog(
            onConfirm = {
                vm.setBatterySaver(true)
                showBatteryDialog = false
            },
            onDismiss = { showBatteryDialog = false },
        )
    }

    if (showLyricsPanelSheet) {
        LyricsColorDialog(
            custom = lyricsPanelCustom,
            color = lyricsPanel,
            onCustom = vm::setLyricsPanelCustom,
            onColor = vm::setLyricsPanelColor,
            onReset = vm::resetLyricsPanel,
            onDismiss = { showLyricsPanelSheet = false },
        )
    }

    if (showStyleSheet) {
        StyleDialog(
            current = style,
            onSelect = vm::setUiStyle,
            onDismiss = { showStyleSheet = false },
        )
    }

    if (showPlayerBgDialog) {
        PlayerBgDialog(
            color = playerBg,
            onChange = vm::setPlayerBgColor,
            onDismiss = { showPlayerBgDialog = false },
        )
    }

    if (showSheet) {
        ColorModeDialog(
            mode = mode,
            custom = custom,
            onMode = vm::setColorMode,
            onCustom = vm::setCustomColor,
            onDismiss = { showSheet = false },
        )
    }
}

/**
 * Modal "Warna tampilan" (gaya sama dengan modal "Warna Dynamic Island"): Ikuti sistem (bawaan), Putih, Hitam,
 * Kustom. Kalau Kustom dipilih, modal melebar (animateContentSize) dan menampilkan pemilih warna.
 */
@Composable
private fun ColorModeDialog(
    mode: ColorMode,
    custom: Int,
    onMode: (ColorMode) -> Unit,
    onCustom: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = { Text(t("Warna tampilan")) },
        text = {
            DialogDim()
            Column(
                Modifier
                    .smoothContentSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                DialogChoiceRow(t("Ikuti sistem"), mode == ColorMode.System) { onMode(ColorMode.System) }
                DialogChoiceRow(t("Putih"), mode == ColorMode.Light) { onMode(ColorMode.Light) }
                DialogChoiceRow(t("Hitam"), mode == ColorMode.Dark) { onMode(ColorMode.Dark) }
                DialogChoiceRow(t("Kustom"), mode == ColorMode.Custom) { onMode(ColorMode.Custom) }
                if (mode == ColorMode.Custom) {
                    CustomColorPicker(
                        argb = custom,
                        onChange = onCustom,
                        swatchSize = 32.dp,
                        horizontalPadding = 0.dp,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Tutup")) } },
    )
}

/** Satu pilihan di dalam modal: label, deskripsi kecil di bawahnya (kalau ada), dan tombol radio di kanan. */
@Composable
internal fun DialogChoiceRow(
    label: String,
    selected: Boolean,
    description: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .modalOption(selected, onClick)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

private val Presets = listOf(
    0xFF1F2A44, 0xFF2E1F3D, 0xFF1F3D2E, 0xFF3D1F24, 0xFFFFF4E0, 0xFFE3F2FD, 0xFFFCE4EC,
).map { it.toInt() }

/** Pemilih warna: pratinjau, warna siap pakai, dan slider Rona / Kejenuhan / Kecerahan. */
@Composable
internal fun CustomColorPicker(
    argb: Int,
    onChange: (Int, Boolean) -> Unit,
    presets: List<Int> = Presets,
    swatchSize: androidx.compose.ui.unit.Dp = 40.dp,
    maxValue: Float = 1f,
    horizontalPadding: androidx.compose.ui.unit.Dp = 24.dp,
    title: String? = t("Warna kustom"),
) {
    val init = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    var h by remember { mutableFloatStateOf(init[0]) }
    var s by remember { mutableFloatStateOf(init[1]) }
    var v by remember { mutableFloatStateOf(init[2].coerceAtMost(maxValue)) }

    fun emit(persist: Boolean) = onChange(Color.hsv(h, s, v).toArgb(), persist)

    Column(Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }
        // Warna siap pakai: 40dp (lebih mudah diketuk), tersebar rata selebar sheet; yang aktif diberi cincin aksen.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val current = Color.hsv(h, s, v).toArgb()
            presets.forEach { c ->
                val selected = isNear(current, c)
                Box(
                    Modifier
                        .size(swatchSize)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                        .clickable {
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(c, hsv)
                            h = hsv[0]; s = hsv[1]; v = hsv[2].coerceAtMost(maxValue)
                            emit(true)
                        },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        PickerSlider(t("Rona"), h, 0f..360f, { h = it; emit(false) }) { emit(true) }
        PickerSlider(t("Kejenuhan"), s, 0f..1f, { s = it; emit(false) }) { emit(true) }
        PickerSlider(t("Kecerahan"), v, 0f..maxValue, { v = it; emit(false) }) { emit(true) }
    }
}

@Composable
private fun PickerSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(92.dp))
        GuardedSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onFinished,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Warna siap pakai untuk latar layar pemutar: cukup gelap supaya teks putih tetap terbaca. */
private val PlayerBgPresets = listOf(
    0xFF5B3FA6, 0xFF1F4E79, 0xFF0F6B5C, 0xFF8A2A2A, 0xFFB35A1F, 0xFFA23A6B, 0xFF2B2B2F,
).map { it.toInt() }

/** Kecerahan tertinggi yang diizinkan untuk latar pemutar (di atas ini teks putih sulit dibaca). */
private const val PlayerBgMaxValue = 0.7f

/**
 * Modal pemilih warna kustom yang dipakai bersama oleh "Kustom warna background" dan "Warna tampilan lirik":
 * pratinjau di atas, lalu warna siap pakai dan slider Rona / Kejenuhan / Kecerahan. "Atur ulang" mengembalikan
 * Di bagian bawah ada baris "Otomatis" dengan toggle: nyala = warna dari cover lagu (pemilih warna disembunyikan),
 * mati = warna kustom. [onReset] (opsional) menambah tombol "Atur ulang"; "Selesai" menutup modal.
 */
@Composable
private fun ColorCustomDialog(
    title: String,
    color: Int?,
    fallback: Int,
    presets: List<Int>,
    maxValue: Float,
    onChange: (Int, Boolean) -> Unit,
    onAutoChange: (Boolean) -> Unit,
    onReset: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = { Text(title) },
        text = {
            DialogDim()
            Column(
                Modifier
                    .smoothContentSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(shape)
                        .background(
                            if (color != null) {
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(Color(color), androidx.compose.ui.graphics.lerp(Color(color), Color.Black, 0.5f)),
                                )
                            } else {
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainer),
                                )
                            },
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (color == null) {
                        Text(
                            t("Otomatis dari cover lagu"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SmoothVisibility(visible = color != null) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        CustomColorPicker(
                            argb = color ?: fallback,
                            onChange = onChange,
                            presets = presets,
                            swatchSize = 32.dp,
                            maxValue = maxValue,
                            horizontalPadding = 0.dp,
                            title = null,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val auto = rememberSmoothToggle(color == null, onAutoChange)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = auto::flip)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t("Otomatis"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    SmoothSwitch(auto)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Selesai")) } },
        dismissButton = if (onReset != null) {
            { TextButton(onClick = onReset) { Text(t("Atur ulang")) } }
        } else null,
    )
}

/** Modal "Kustom warna background" (warna latar layar pemutar). */
@Composable
private fun PlayerBgDialog(
    color: Int?,
    onChange: (Int?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ColorCustomDialog(
        title = t("Kustom warna background"),
        color = color,
        fallback = PlayerBgPresets.first(),
        presets = PlayerBgPresets,
        maxValue = PlayerBgMaxValue,
        onChange = { argb, persist -> onChange(argb, persist) },
        onAutoChange = { auto -> onChange(if (auto) null else PlayerBgPresets.first(), true) },
        onReset = null,
        onDismiss = onDismiss,
    )
}

/** Modal "Gaya tampilan" (gaya sama dengan modal "Warna Dynamic Island"): Biasa atau Kaca, dengan deskripsi dan radio. */
@Composable
private fun StyleDialog(
    current: UiStyle,
    onSelect: (UiStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = { Text(t("Gaya tampilan")) },
        text = {
            DialogDim()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                UiStyle.entries.forEach { option ->
                    DialogChoiceRow(
                        label = t(option.label),
                        selected = option == current,
                        description = t(option.description),
                    ) { onSelect(option) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Tutup")) } },
    )
}

/** Dua warna dianggap sama kalau tiap kanal RGB selisih paling banyak 2 (hasil bolak-balik HSV bisa bergeser 1). */
private fun isNear(a: Int, b: Int): Boolean =
    listOf(16, 8, 0).all { shift -> kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= 2 }

/** Nilai pilihan di sisi kanan baris (mis. "Kaca"). */
@Composable
internal fun ValueText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
}

/** Warna siap pakai di pemilih kustom panel lirik: cukup gelap supaya teks putih tetap terbaca. */
private val LyricsPresets = listOf(0, 3, 5, 8, 10, 12, 14).map { LyricsPanelPresets[it] }

/** Kecerahan tertinggi untuk panel lirik (di atas ini teks putih sulit dibaca). */
private const val LyricsPanelMaxValue = 0.7f

/**
 * Modal "Warna tampilan lirik" (sama dengan modal "Kustom warna background"): pratinjau + pemilih warna.
 * Menggeser slider atau memilih warna otomatis menjadikan warna Kustom; "Atur ulang" mengembalikan ke Otomatis
 * (sesuai cover lagu) dan warna kustom ke bawaan.
 */
@Composable
private fun LyricsColorDialog(
    custom: Boolean,
    color: Int,
    onCustom: (Boolean) -> Unit,
    onColor: (Int, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ColorCustomDialog(
        title = t("Warna tampilan lirik"),
        color = if (custom) color else null,
        fallback = color,
        presets = LyricsPresets,
        maxValue = LyricsPanelMaxValue,
        onChange = { argb, persist ->
            if (!custom) onCustom(true)
            onColor(argb, persist)
        },
        onAutoChange = { auto ->
            if (auto) {
                onCustom(false)
            } else {
                onColor(color, true)
                onCustom(true)
            }
        },
        onReset = { onReset(); onDismiss() },
        onDismiss = onDismiss,
    )
}

/**
 * Modal peringatan sebelum Penghemat baterai dinyalakan: daftar fitur yang dimatikan beserta dampaknya,
 * lalu tombol Batal dan Oke. Tanpa ikon.
 */
@Composable
private fun BatterySaverDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val items = listOf(
        t("Refresh rate tinggi") to t("Layar memakai refresh rate standar sistem, gerakan terasa kurang mulus."),
        t("Animasi smooth") to t("Bilah navigasi dan pemutar mini tidak lagi beranimasi."),
        t("Gaya tampilan Kaca") to t("Diganti Biasa: tanpa efek blur dan transparan."),
        t("Background thumbnail") to t("Latar layar pemutar berupa gradien warna, bukan gambar kabur."),
        t("Kilau skeleton") to t("Dimatikan: kerangka saat memuat tampil diam."),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = { Text(t("Aktifkan Penghemat baterai?")) },
        text = {
            DialogDim()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(t("Fitur yang dimatikan:"), style = MaterialTheme.typography.bodyMedium)
                items.forEach { (name, effect) ->
                    Spacer(Modifier.height(10.dp))
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        effect,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    t("Saat dimatikan, semua pengaturan di atas kembali ke bawaan."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Batal")) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(t("Oke")) } },
    )
}
