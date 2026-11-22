package app.tunes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Gerak tombol yang halus: saat ditekan tombol mengecil sedikit, lalu kembali dengan pegas lembut
 * (sedikit memantul, tidak berlebihan). Hanya aktif kalau "Animasi smooth" nyala; mati = tidak ada gerak.
 *
 * Pasang SEBELUM clip / background supaya latar dan ripple ikut mengecil bersama isinya.
 * [source] harus sama dengan interactionSource tombolnya.
 */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressedScale: Float = 0.9f): Modifier {
    val smooth = LocalSmoothAnim.current
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) tween(90, easing = FastOutSlowInEasing)
        else spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "buttonPressScale",
    )
    if (!smooth) return this
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** IconButton biasa + gerak tekan [pressScale]. */
@Composable
fun SmoothIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressedScale: Float = 0.86f,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.pressScale(source, pressedScale),
        enabled = enabled,
        interactionSource = source,
        content = content,
    )
}

/** Warna ikon yang berpindah halus (mis. acak / ulangi menyala-mati). Animasi smooth mati: langsung berganti. */
@Composable
fun smoothTint(target: Color): Color {
    val smooth = LocalSmoothAnim.current
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = if (smooth) tween(220, easing = FastOutSlowInEasing) else snap(),
        label = "buttonTint",
    )
    return color
}

/** clickable yang memakai [source] bersama [pressScale] (ripple bawaan tetap ada). */
@Composable
fun Modifier.pressClickable(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = source,
    indication = LocalIndication.current,
    enabled = enabled,
    onClick = onClick,
)

/**
 * Perubahan ukuran yang beranimasi hanya kalau "Animasi smooth" nyala; mati = ukuran langsung berubah.
 * Dipakai di modal / sheet yang isinya memanjang-memendek (mis. saat toggle Otomatis diubah).
 */
@Composable
fun Modifier.smoothContentSize(): Modifier =
    if (LocalSmoothAnim.current) this.animateContentSize() else this

/**
 * Muncul / hilang dengan animasi hanya kalau "Animasi smooth" nyala; mati = langsung tampil atau hilang.
 */
@Composable
fun SmoothVisibility(visible: Boolean, content: @Composable () -> Unit) {
    if (LocalSmoothAnim.current) {
        AnimatedVisibility(visible = visible) { content() }
    } else if (visible) {
        content()
    }
}

/**
 * Animasi dekoratif: memakai [spec] kalau "Animasi smooth" nyala, mati = langsung berganti (snap).
 * Dipakai untuk transisi yang hanya hiasan (pergantian warna, fade halaman, sorotan lirik). Animasi yang
 * fungsional (bilah progres, geser sheet pemutar, gulir otomatis lirik, kerangka shimmer loading) tidak memakainya.
 */
@Composable
fun <T> smoothSpec(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (LocalSmoothAnim.current) spec else snap()

/** Seperti [smoothSpec] untuk AnimatedVisibility: mati = muncul / hilang seketika. */
@Composable
fun smoothEnter(enter: EnterTransition): EnterTransition =
    if (LocalSmoothAnim.current) enter else EnterTransition.None

@Composable
fun smoothExit(exit: ExitTransition): ExitTransition =
    if (LocalSmoothAnim.current) exit else ExitTransition.None
