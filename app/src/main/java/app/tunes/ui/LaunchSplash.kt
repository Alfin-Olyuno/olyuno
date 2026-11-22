package app.tunes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tunes.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Latar splash putih; sama dengan windowSplashScreenBackground di themes.xml supaya menyambung dengan splash sistem. */
private val SplashBackground = Color.White
/** Warna teks "Olyuno": biru tua yang sama dengan latar ikon aplikasi. */
private val SplashText = Color(0xFF010D2D)

private const val APP_NAME = "Olyuno"

/** Ukuran sisi gambar logo (piksel); batang di bawah dinyatakan dalam pecahan dari ukuran ini. */
private const val ART = 264f

/**
 * Satu batang gelombang: gambarnya serta kotak batangnya (x0..x1 dan y0..y1, pecahan dari sisi gambar)
 * yang dipakai untuk menggambarnya dari atas ke bawah.
 */
private class WaveBar(val res: Int, val x0: Float, val x1: Float, val y0: Float, val y1: Float)

private val WaveBars = listOf(
    WaveBar(R.drawable.ic_olyuno_bar1_open, 131f / ART, 147f / ART, 107f / ART, 163f / ART),
    WaveBar(R.drawable.ic_olyuno_bar2_open, 151f / ART, 170f / ART, 94f / ART, 174f / ART),
    WaveBar(R.drawable.ic_olyuno_bar3_open, 174f / ART, 191f / ART, 117f / ART, 155f / ART),
)

/**
 * Animasi logo Olyuno saat aplikasi baru dibuka (hanya dari awal, bukan saat layar diputar):
 * 1) bunderan logo langsung muncul (memudar masuk cepat, tanpa animasi menggambar), tanpa cahaya maupun cakram biru tua di dalamnya (memakai gambar *_open yang transparan);
 * 2) tombol play muncul dengan lembut (memudar masuk sambil membesar sedikit);
 * 3) tiga garis gelombang suara digambar satu per satu dari atas ke bawah;
 * 4) teks "Olyuno" tersingkap dari kiri ke kanan dengan tepi lembut sambil meluncur masuk dan memudar, sampai terlihat penuh;
 * 5) layar memudar dan membuka aplikasi.
 * Latar putih dan semua animasi dibuat cepat; total sekitar 1,5 detik. Selama tampil, ketukan tidak
 * diteruskan ke layar di belakangnya.
 */
@Composable
fun LaunchSplash(onFinished: () -> Unit) {
    val ringAlpha = remember { Animatable(0f) }
    val groupScale = remember { Animatable(0.9f) }
    val playAlpha = remember { Animatable(0f) }
    val playScale = remember { Animatable(0.88f) }
    val barDraw = remember { List(WaveBars.size) { Animatable(0f) } }
    val textReveal = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { ringAlpha.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
            launch { groupScale.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
            launch {
                delay(150)
                launch { playAlpha.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
                playScale.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
            }
            barDraw.forEachIndexed { i, anim ->
                launch {
                    delay(300L + i * 110L)
                    anim.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                }
            }
            // Teks "Olyuno": terbuka dari kiri ke kanan sampai terlihat penuh.
            launch {
                delay(500)
                textReveal.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
            }
        }
        delay(250)
        exit.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(SplashBackground)
            // Menelan ketukan selama splash tampil.
            .pointerInput(Unit) {},
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(120.dp)
                .graphicsLayer {
                    val s = groupScale.value * (1f + 0.12f * exit.value)
                    scaleX = s
                    scaleY = s
                },
            contentAlignment = Alignment.Center,
        ) {
            // Bunderan: langsung muncul penuh (memudar masuk cepat), tanpa animasi menggambar.
            Image(
                painter = painterResource(R.drawable.ic_olyuno_ring_open),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = ringAlpha.value },
            )
            // Tombol play: memudar masuk dengan lembut.
            Image(
                painter = painterResource(R.drawable.ic_olyuno_play_open),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = playAlpha.value
                        scaleX = playScale.value
                        scaleY = playScale.value
                    },
            )
            // Gelombang: tiap garis digambar dari atas ke bawah, satu per satu (tanpa gerak membal).
            WaveBars.forEachIndexed { i, bar ->
                Image(
                    painter = painterResource(bar.res),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            val p = barDraw[i].value
                            if (p >= 0.999f) {
                                drawContent()
                            } else if (p > 0f) {
                                // Kotak yang membuka dari atas batang ke bawah; ujung bawahnya dibulatkan seperti ujung pena.
                                val w = size.width
                                val left = (bar.x0 - 0.008f) * w
                                val right = (bar.x1 + 0.008f) * w
                                val bottom = (bar.y0 + (bar.y1 + 0.012f - bar.y0) * p) * w
                                val cap = CornerRadius((right - left) / 2f)
                                val path = Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            left, 0f, right, bottom,
                                            topLeftCornerRadius = CornerRadius.Zero,
                                            topRightCornerRadius = CornerRadius.Zero,
                                            bottomRightCornerRadius = cap,
                                            bottomLeftCornerRadius = cap,
                                        ),
                                    )
                                }
                                clipPath(path) { this@drawWithContent.drawContent() }
                            }
                        },
                )
            }
        }
        // Teks "Olyuno" di bawah logo: tersingkap dari kiri ke kanan dengan tepi lembut (feather),
        // sambil meluncur dari kiri dan memudar masuk, sampai terlihat penuh.
        Text(
            text = APP_NAME,
            color = SplashText,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    translationY = 60.dp.toPx() + 30.dp.toPx()
                    translationX = -(1f - textReveal.value) * 20.dp.toPx()
                    alpha = (textReveal.value * 2.5f).coerceIn(0f, 1f)
                }
                .drawWithContent {
                    drawContent()
                    val feather = 36.dp.toPx()
                    val edge = (size.width + feather) * textReveal.value
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startX = edge - feather,
                            endX = edge,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
    }
}
