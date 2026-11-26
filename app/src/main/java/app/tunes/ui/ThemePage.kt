package app.tunes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.data.AppFont
import app.tunes.data.ShimmerLevel
import app.tunes.player.PlayerState
import app.tunes.i18n.t

/**
 * Halaman Tema (dibuka dari Tampilan): dua kartu besar putih.
 * 1) "Kilau memuat": kekuatan kilau skeleton saat memuat (Mati / Redup / Terang), dipilih lewat pratinjau.
 * 2) "Font": Sistem / iOS, slider Ketebalan, dan tombol Atur ulang font.
 */
@Composable
fun ThemePage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val shimmer by vm.shimmerLevel.collectAsStateWithLifecycle()
    val font by vm.appFont.collectAsStateWithLifecycle()
    val weight by vm.fontWeight.collectAsStateWithLifecycle()

    SubPage(title = t("Tema"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            // ---- Kilau memuat
            ThemeCard {
                CardTitle(t("Kilau memuat"))
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShimmerLevel.entries.forEach { level ->
                        Column(
                            Modifier.weight(1f).clickable { vm.setShimmerLevel(level) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ShimmerPreview(level, selected = level == shimmer)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                t(level.label),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (level == shimmer) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            // ---- Font
            ThemeCard {
                CardTitle(t("Font"))
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppFont.entries.forEach { option ->
                        FontChip(t(option.label), selected = option == font) { vm.setAppFont(option) }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t("Ketebalan"),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when {
                            weight < 4 -> t("Tipis")
                            weight > 4 -> t("Tebal")
                            else -> t("Default")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 9 posisi (0..8); tengah = Default.
                GuardedSlider(
                    value = weight.toFloat(),
                    onValueChange = { vm.setFontWeight(it.toInt()) },
                    valueRange = 0f..8f,
                    steps = 7,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        t("Tipis"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        t("Tebal"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = vm::resetFont,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Atur ulang font", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Kartu putih besar pembungkus satu bagian (sudut 28dp, sama dengan kartu menu lain). */
@Composable
private fun ThemeCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(20.dp),
    ) { content() }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/**
 * Pratinjau satu pilihan kilau: layar mini berisi baris skeleton. Mati = abu-abu rata; Redup / Terang =
 * semburat kilau di bagian atas dengan kekuatan berbeda. Yang dipilih diberi bingkai tebal.
 */
@Composable
private fun ShimmerPreview(level: ShimmerLevel, selected: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    val ink = MaterialTheme.colorScheme.onSurface
    val glowAlpha = when (level) {
        ShimmerLevel.Off -> 0f
        ShimmerLevel.Soft -> 0.14f
        ShimmerLevel.Strong -> 0.30f
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.62f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                    0.55f to androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            .border(
                if (selected) BorderStroke(2.5.dp, ink) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape,
            )
            .padding(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(44.dp).height(8.dp).clip(CircleShape).background(ink.copy(alpha = 0.8f)))
            repeat(4) { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(ink.copy(alpha = 0.10f)))
                    Spacer(Modifier.width(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width((44 - i * 4).dp).height(5.dp).clip(CircleShape).background(ink.copy(alpha = 0.45f)))
                        Box(Modifier.width((28 - i * 2).dp).height(4.dp).clip(CircleShape).background(ink.copy(alpha = 0.22f)))
                    }
                }
            }
        }
    }
}

/** Pilihan font berbentuk pil; yang dipilih diberi bingkai tebal. */
@Composable
private fun FontChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (selected) Modifier.border(BorderStroke(2.dp, ink), CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
