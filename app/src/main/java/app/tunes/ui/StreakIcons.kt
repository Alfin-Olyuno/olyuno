package app.tunes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/*
 * Logo streak (api) dan freeze (kristal es) yang digambar sendiri di Canvas, koordinat 0..100.
 * Semua gerakan memakai kelipatan bulat dari satu putaran (sin(a * n)), jadi loop-nya mulus tanpa lompatan.
 */

private const val TAU = 6.2831855f
private val FlameTop = Color(0xFFFFB02E)
private val FlameMid = Color(0xFFFF7A1A)
private val FlameBase = Color(0xFFE5391B)
private val Ice = Color(0xFF6CC4F2)

/** Satu putaran 0..1 yang berulang terus; dibaca di blok gambar supaya hanya Canvas yang digambar ulang. */
@Composable
private fun rememberLoop(durationMs: Int, enabled: Boolean): State<Float> =
    if (enabled) {
        rememberInfiniteTransition(label = "logoLoop")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart), label = "t")
    } else {
        remember { mutableFloatStateOf(0f) }
    }

private val FlamePath = Path().apply {
    moveTo(50f, 4f)
    cubicTo(52f, 22f, 80f, 36f, 82f, 64f)
    cubicTo(83f, 82f, 69f, 95f, 50f, 95f)
    cubicTo(31f, 95f, 17f, 82f, 18f, 64f)
    cubicTo(18.5f, 52f, 26f, 44f, 32f, 36f)
    cubicTo(34f, 46f, 38f, 50f, 42f, 52f)
    cubicTo(40f, 36f, 44f, 18f, 50f, 4f)
    close()
}

/**
 * Api streak. [on] = hari ini terhitung: tiga lapis api (merah-oranye, kuning, inti putih-kuning) yang meliuk
 * dan berkedip sebagai satu kesatuan, cahaya di belakangnya berdenyut, dan percikan naik (hanya di ukuran besar). Mati = api abu redup, diam.
 */
@Composable
fun FlameLogo(on: Boolean, size: Dp, animated: Boolean = true, modifier: Modifier = Modifier) {
    val t = rememberLoop(2600, on && animated)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val sparks = size >= 48.dp
    Canvas(modifier.size(size)) {
        scale(this.size.minDimension / 100f, Offset.Zero) {
            val base = Offset(50f, 95f)
            if (!on) {
                drawPath(FlamePath, dim)
                withTransform({ scale(0.55f, 0.55f, base) }) { drawPath(FlamePath, dim.copy(alpha = dim.alpha * 0.5f)) }
                return@scale
            }
            val a = t.value * TAU
            val pulse = 0.5f + 0.5f * sin(a)
            val glowC = Offset(50f, 64f)
            drawCircle(
                Brush.radialGradient(listOf(Color(0x66FF8A1F), Color.Transparent), glowC, 48f),
                radius = 48f, center = glowC, alpha = 0.7f + 0.3f * pulse,
            )
            // Satu gerakan untuk seluruh api: ketiga lapis ikut meliuk dan berkedip bersama, jadi terlihat satu api utuh.
            val rot = 3f * sin(a * 2f)
            val sy = 1f + 0.045f * sin(a * 3f)
            withTransform({ rotate(rot, base); scale(1f, sy, base) }) {
                drawPath(
                    FlamePath,
                    Brush.verticalGradient(0f to FlameTop, 0.45f to FlameMid, 1f to FlameBase, startY = 4f, endY = 95f),
                )
                withTransform({ scale(0.74f, 0.74f, base) }) {
                    drawPath(
                        FlamePath,
                        Brush.verticalGradient(0f to Color(0xFFFFD05A), 1f to Color(0xFFFF9A2E), startY = 4f, endY = 95f),
                    )
                }
                withTransform({ scale(0.46f, 0.46f, base) }) {
                    drawPath(
                        FlamePath,
                        Brush.verticalGradient(0f to Color(0xFFFFF6CF), 1f to Color(0xFFFFD54F), startY = 4f, endY = 95f),
                    )
                }
            }
            if (sparks) {
                for (i in 0..2) {
                    val p = (t.value + i / 3f) % 1f
                    val x = 50f + (i - 1) * 13f + 6f * sin((p * 2f + i) * TAU)
                    val y = 58f - 54f * p
                    drawCircle(Color(0xFFFFD25E), 2.2f * (1f - 0.5f * p), Offset(x, y), alpha = sin(p * PI.toFloat()))
                }
            }
        }
    }
}

/**
 * Freeze: kristal es enam lengan dengan cabang, gradasi putih-biru. Pelan berputar bolak-balik, "bernapas",
 * cahaya biru berdenyut, dan tiga kilau kecil berkelip bergantian. [animated] = false menggambarnya diam.
 */
@Composable
fun FreezeLogo(size: Dp, animated: Boolean = true, modifier: Modifier = Modifier) {
    val t = rememberLoop(5200, animated)
    val twinkle = size >= 24.dp
    Canvas(modifier.size(size)) {
        scale(this.size.minDimension / 100f, Offset.Zero) {
            val a = t.value * TAU
            val c = Offset(50f, 50f)
            val pulse = 0.5f + 0.5f * sin(a * 2f)
            drawCircle(
                Brush.radialGradient(listOf(Color(0x556CC4F2), Color.Transparent), c, 48f),
                radius = 48f, center = c, alpha = 0.6f + 0.4f * pulse,
            )
            val arm = Brush.verticalGradient(0f to Color(0xFFEAFBFF), 1f to Ice, startY = 10f, endY = 50f)
            val breathe = 1f + 0.04f * sin(a * 2f)
            withTransform({ rotate(10f * sin(a), c); scale(breathe, breathe, c) }) {
                for (k in 0 until 6) {
                    rotate(60f * k, c) {
                        drawLine(arm, Offset(50f, 50f), Offset(50f, 10f), 5f, StrokeCap.Round)
                        drawLine(arm, Offset(50f, 33f), Offset(41f, 23f), 4f, StrokeCap.Round)
                        drawLine(arm, Offset(50f, 33f), Offset(59f, 23f), 4f, StrokeCap.Round)
                        drawLine(arm, Offset(50f, 22f), Offset(45.5f, 16.5f), 3f, StrokeCap.Round)
                        drawLine(arm, Offset(50f, 22f), Offset(54.5f, 16.5f), 3f, StrokeCap.Round)
                    }
                }
                drawCircle(Color(0xFFEAFBFF), 6f, c)
                drawCircle(Ice, 3f, c)
            }
            if (twinkle) {
                val spots = listOf(Offset(82f, 20f) to 0f, Offset(18f, 72f) to 2.1f, Offset(78f, 80f) to 4.2f)
                for ((p, ph) in spots) {
                    val s = sin(a + ph)
                    val al = max(0f, s) * max(0f, s)
                    val r = 3f + 4f * al
                    drawLine(Color.White, Offset(p.x - r, p.y), Offset(p.x + r, p.y), 1.6f, StrokeCap.Round, alpha = al)
                    drawLine(Color.White, Offset(p.x, p.y - r), Offset(p.x, p.y + r), 1.6f, StrokeCap.Round, alpha = al)
                }
            }
        }
    }
}
