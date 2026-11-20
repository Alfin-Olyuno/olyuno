package app.tunes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.data.ColorMode
import app.tunes.i18n.t
import app.tunes.lyrics.Languages
import app.tunes.lyrics.LyricsSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import app.tunes.player.PlayerState

/**
 * Halaman Lirik (dari Pengaturan): teks "Lirik" di paling atas, lalu tombol "Warna teks lirik >" yang membuka
 * bottom sheet pilihan warna teks lirik (gaya sama dengan sheet "Warna tampilan").
 */
@Composable
fun LyricsSettingsPage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val mode by vm.lyricsTextMode.collectAsStateWithLifecycle()
    val custom by vm.lyricsTextCustom.collectAsStateWithLifecycle()
    // Sama dengan toggle "Mengikuti per lirik" di menu titik tiga layar lirik (satu pengaturan yang sama).
    val followLine by vm.lyricsFollowLine.collectAsStateWithLifecycle()
    val translate by vm.lyricsTranslate.collectAsStateWithLifecycle()
    val targetLang by vm.lyricsTargetLang.collectAsStateWithLifecycle()
    var langMenu by remember { mutableStateOf(false) }
    // Sumber lirik: pilihan & sheet yang sama dengan di layar lirik (Otomatis / LRCLIB / Better Lyrics / BiniLyrics).
    val source by vm.lyricsSource.collectAsStateWithLifecycle()
    // Sama dengan "Jaga layar tetap hidup" di menu titik tiga layar lirik (satu pengaturan yang sama).
    val keepAwake by vm.keepScreenAwake.collectAsStateWithLifecycle()
    var showSourceSheet by rememberSaveable { mutableStateOf(false) }
    val currentSong = player.current
    var availability by remember(currentSong?.videoId) { mutableStateOf<Map<LyricsSource, Boolean>>(emptyMap()) }
    LaunchedEffect(showSourceSheet, currentSong?.videoId) {
        val song = currentSong ?: return@LaunchedEffect
        if (!showSourceSheet) return@LaunchedEffect
        coroutineScope {
            listOf(LyricsSource.Lrclib, LyricsSource.BetterLyrics, LyricsSource.LyricsPlus, LyricsSource.BiniLyrics, LyricsSource.Paxsenix).forEach { src ->
                launch {
                    vm.lyricsAvailable(song, src)?.let { ok -> availability = availability + (src to ok) }
                }
            }
        }
    }
    var showSheet by rememberSaveable { mutableStateOf(false) }

    SubPage(title = t("Lirik"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            SectionTitle(t("Lirik"))
            MenuGroup {
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.FormatColorText, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Warna teks lirik"),
                    onClick = { showSheet = true },
                ) {
                    Spacer(Modifier.width(8.dp))
                    if (mode == ColorMode.Custom) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(custom))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                    } else {
                        Text(
                            t(mode.label),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MenuDivider()
                MenuRow(
                    leading = { Icon(Icons.Rounded.VerticalAlignCenter, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Mengikuti per lirik"),
                    checked = followLine,
                    onChange = vm::setLyricsFollowLine,
                )
                MenuDivider()
                // Sama dengan "Terjemahkan lirik" + "Pilih bahasa" di menu titik tiga layar lirik:
                // nyala = baris "Pilih bahasa" muncul di bawahnya, mati = tersembunyi.
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Terjemahkan lirik"),
                    checked = translate,
                    onChange = vm::setLyricsTranslate,
                )
                SmoothVisibility(visible = translate) {
                    Column {
                        MenuDivider()
                        Box(Modifier.fillMaxWidth()) {
                            SwitchMenuRow(
                                leading = { Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(24.dp)) },
                                label = t("Pilih bahasa"),
                                onClick = { langMenu = true },
                            ) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    Languages.nameOf(targetLang),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Icon(
                                    Icons.Rounded.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = langMenu,
                                onDismissRequest = { langMenu = false },
                                modifier = Modifier.heightIn(max = 320.dp),
                                shape = RoundedCornerShape(20.dp),
                            ) {
                                Languages.all.forEach { lang ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                lang.name,
                                                fontWeight = if (lang.code == targetLang) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        trailingIcon = {
                                            if (lang.code == targetLang) {
                                                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        onClick = {
                                            vm.setLyricsTargetLang(lang.code)
                                            langMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                MenuDivider()
                MenuRow(
                    leading = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Sumber lirik"),
                    onClick = { showSourceSheet = true },
                ) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t(source.label),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MenuDivider()
                MenuRow(
                    leading = { Icon(Icons.Rounded.LightMode, contentDescription = null, modifier = Modifier.size(24.dp)) },
                                label = t("Jaga layar tetap hidup"),
                                checked = keepAwake,
                                onChange = vm::setKeepScreenAwake,
                            )
            }
        }
    }

    if (showSourceSheet) {
        // Sheet ini berteks putih (dirancang untuk layar pemutar yang gelap), jadi dibungkus skema gelap
        // supaya tetap terbaca di halaman Pengaturan yang terang.
        MaterialTheme(colorScheme = darkColorScheme(), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
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
    }

    if (showSheet) {
        LyricsTextColorDialog(
            mode = mode,
            custom = custom,
            onMode = vm::setLyricsTextMode,
            onCustom = vm::setLyricsTextCustom,
            onDismiss = { showSheet = false },
        )
    }
}

/** Warna siap pakai untuk teks lirik (terang, supaya terbaca di atas latar pemutar yang gelap). */
private val LyricsTextPresets = listOf(
    0xFFFFFFFF, 0xFFFFE082, 0xFFFFAB91, 0xFFF48FB1, 0xFFCE93D8, 0xFF90CAF9, 0xFFA5D6A7,
).map { it.toInt() }

/**
 * Modal "Warna teks lirik" (gaya sama dengan modal "Warna Dynamic Island"): Ikuti sistem (seperti bawaan),
 * Putih, Hitam, Kustom. Kalau Kustom dipilih, modal melebar (animateContentSize) dan menampilkan pemilih warna.
 */
@Composable
private fun LyricsTextColorDialog(
    mode: ColorMode,
    custom: Int,
    onMode: (ColorMode) -> Unit,
    onCustom: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh),
        title = { Text(t("Warna teks lirik")) },
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
                        presets = LyricsTextPresets,
                        swatchSize = 32.dp,
                        horizontalPadding = 0.dp,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Tutup")) } },
    )
}
