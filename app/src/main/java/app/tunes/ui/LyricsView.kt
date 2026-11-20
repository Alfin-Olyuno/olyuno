package app.tunes.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tunes.lyrics.LrcParser
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import app.tunes.lyrics.LyricLine
import app.tunes.lyrics.LyricsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlin.math.abs
import app.tunes.i18n.t

/** Warna teks lirik (bawaan putih); diisi oleh [LyricsView] dari pengaturan Lirik > Warna teks lirik. */
private val LocalLyricsTextColor = compositionLocalOf { Color.White }

/** Baris dianggap aktif sedikit lebih awal supaya animasi sorotan selesai tepat saat penyanyi mulai. */
private const val LEAD_MS = 250L

/** Baris aktif dipusatkan di sekitar 36% tinggi area lirik (agak ke atas, seperti Apple Music / Spotify). */
private const val CENTER_FRACTION = 0.36f

/** Lama tanpa sentuhan sebelum layar kembali ke lirik yang sedang disinkronkan. */
private const val HOLD_MS = 3_000L

/** Kekakuan pegas pengikut gulir (rad/detik, redaman kritis): makin besar makin cepat menyusul. */
private const val FOLLOW_OMEGA = 9f

/** Sama, tapi untuk perjalanan kembali setelah pengguna selesai menggulir: lebih lembut & panjang. */
private const val RETURN_OMEGA = 5.5f

/**
 * Tampilan lirik: tersinkron (baris aktif disorot + scroll halus otomatis, ketuk baris untuk loncat ke
 * bagian itu) atau, kalau hanya ada lirik polos, daftar biasa yang bisa digulir.
 * Warna teks dari [textColor] (bawaan putih); latar disediakan oleh pemanggil (warna & cover lagu).
 */
@Composable
fun LyricsView(
    state: LyricsState,
    songKey: String,
    isPlaying: Boolean,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    follow: Boolean = true,
    translations: List<String>? = null,
    textColor: Color = Color.White,
    jump: LyricsJump? = null,
    onJumpHandled: () -> Unit = {},
) {
    CompositionLocalProvider(LocalLyricsTextColor provides textColor) {
    Crossfade(targetState = state, animationSpec = smoothSpec(tween(350)), modifier = modifier, label = "lyricsState") { s ->
        when (s) {
            LyricsState.Loading -> LyricsSkeleton()
            LyricsState.NotFound -> LyricsMessage(
                Icons.Rounded.Lyrics, t("Lirik belum tersedia"), t("Belum ada lirik untuk lagu ini."),
            )
            LyricsState.Instrumental -> LyricsMessage(
                Icons.Rounded.MusicNote, t("Instrumental"), t("Lagu ini tidak memiliki lirik."),
            )
            is LyricsState.Error -> LyricsMessage(
                Icons.Rounded.CloudOff, t("Gagal memuat lirik"), s.message,
            ) {
                FilledTonalButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White,
                    ),
                ) { Text("Coba lagi") }
            }
            is LyricsState.Ready ->
                if (s.synced) SyncedLyrics(s.lines, songKey, isPlaying, positionProvider, onSeek, follow, translations, jump, onJumpHandled)
                else PlainLyrics(s.lines, songKey, translations, jump, onJumpHandled)
        }
    }
    }
}

// ---------------------------------------------------------------- tersinkron

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    songKey: String,
    isPlaying: Boolean,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    follow: Boolean,
    translations: List<String>?,
    jump: LyricsJump?,
    onJumpHandled: () -> Unit,
) {
    val listState = rememberLazyListState()
    val followNow by rememberUpdatedState(follow)
    val playing by rememberUpdatedState(isPlaying)
    val provider by rememberUpdatedState(positionProvider)

    // Posisi dibaca setiap frame langsung dari player (jauh lebih rapat daripada polling 500 ms di state UI).
    // Nilainya hanya dibaca di dalam derivedStateOf, jadi komposisi baru berulang saat baris aktif berganti.
    var positionMs by remember(songKey) { mutableLongStateOf(positionProvider()) }
    LaunchedEffect(songKey) {
        while (isActive) {
            positionMs = provider()
            if (playing) withFrameNanos { } else delay(250L)
        }
    }
    val active by remember(lines) {
        derivedStateOf { LrcParser.activeIndex(lines, positionMs + LEAD_MS) }
    }

    // Pengguna bebas menggulir kapan saja: selama jari menyentuh daftar (dan selama gulir lanjutan/fling),
    // auto-scroll berhenti. Setelah HOLD_MS (3 detik) tanpa sentuhan, layar kembali ke lirik yang sedang
    // disinkronkan. Berlaku sama baik di mode biasa maupun layar penuh.
    var touching by remember { mutableStateOf(false) }
    var userHold by remember { mutableStateOf(false) }
    LaunchedEffect(touching) {
        if (touching) {
            userHold = true
        } else {
            delay(80) // beri waktu fling dimulai
            snapshotFlow { listState.isScrollInProgress }.first { !it } // tunggu fling selesai
            delay(HOLD_MS)
            userHold = false
        }
    }

    // Hasil Cari lirik: baris yang dipilih dibawa ke titik target. Kalau "Mengikuti per lirik" nyala, pengikut gulir
    // di bawah yang meluncur ke baris itu setelah lagu loncat; kalau mati, digulirkan langsung di sini.
    LaunchedEffect(jump) {
        val j = jump ?: return@LaunchedEffect
        userHold = false
        if (!followNow) {
            val h = listState.layoutInfo.viewportSize.height
            listState.animateScrollToItem(j.index + 1, -(h * CENTER_FRACTION - 80f).toInt().coerceAtLeast(0))
        }
        onJumpHandled()
    }

    // Pengikut gulir per frame berbasis pegas teredam kritis (posisi + kecepatan). Gerakan mulai pelan,
    // melaju, lalu berhenti halus tanpa overshoot. Saat baris berganti kecepatan terus berlanjut (tidak ada
    // animasi yang dibatalkan), jadi tidak ada patah. Setelah pengguna selesai menggulir, layar meluncur
    // kembali ke baris aktif dengan pegas yang sama (tidak lagi melompat), sejauh apa pun jaraknya.
    LaunchedEffect(lines, songKey) {
        var last = 0L
        var snapNext = true
        var vel = 0f
        var wasHold = false
        var returning = false
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            last = now
            // "Mengikuti per lirik" mati: layar tidak mengikuti. Begitu dinyalakan lagi, layar meluncur
            // halus kembali ke baris yang sedang disinkronkan (sama seperti setelah pengguna menggulir).
            if (!followNow) {
                snapNext = false
                wasHold = true
                vel = 0f
                continue
            }
            if (userHold) {
                snapNext = false
                wasHold = true
                vel = 0f
                continue
            }
            if (wasHold) {
                wasHold = false
                returning = true
            }

            // Jarak (px) yang masih harus digulir agar baris aktif berada di titik target.
            val info0 = listState.layoutInfo
            val visible = info0.visibleItemsInfo
            val a = active
            val delta: Float
            if (a < 0) {
                // Intro: kembali ke atas.
                if (listState.firstVisibleItemIndex > 0) {
                    val first = visible.firstOrNull()
                    val avg = visible.map { it.size }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
                    delta = if (first == null) 0f else -(first.offset.let { -it } + (first.index) * avg)
                } else {
                    delta = -listState.firstVisibleItemScrollOffset.toFloat()
                }
            } else {
                val index = a + 1 // +1 karena item pertama adalah spacer atas
                val info = visible.firstOrNull { it.index == index }
                val target = info0.viewportSize.height * CENTER_FRACTION
                if (info != null) {
                    delta = info.offset + info.size / 2f - target
                } else if (visible.isEmpty() || snapNext) {
                    // Pertama kali tampil: langsung pasang tanpa animasi.
                    safeScroll { listState.scrollToItem(index) }
                    snapNext = true
                    vel = 0f
                    continue
                } else {
                    // Item di luar layar (pengguna menggulir jauh / seek jauh): perkirakan jaraknya lalu
                    // tetap meluncur mulus; begitu item terlihat, jarak dihitung persis.
                    val avg = visible.map { it.size }.average().toFloat()
                    val lastV = visible.last()
                    val firstV = visible.first()
                    delta = if (index > lastV.index) {
                        lastV.offset + lastV.size + (index - lastV.index - 1) * avg + avg / 2f - target
                    } else {
                        firstV.offset - (firstV.index - index) * avg + avg / 2f - target
                    }
                }
            }

            if (snapNext) {
                // Pemasangan awal (mis. ganti lagu): tanpa animasi.
                if (abs(delta) > 0.5f) safeScroll { listState.scrollBy(delta) }
                snapNext = false
                vel = 0f
                continue
            }

            if (abs(delta) < 0.5f && abs(vel) < 8f) {
                vel = 0f
                returning = false
                continue
            }
            val omega = if (returning) RETURN_OMEGA else FOLLOW_OMEGA
            vel += (omega * omega * delta - 2f * omega * vel) * dt
            val step = vel * dt
            var consumed = step
            safeScroll { consumed = listState.scrollBy(step) }
            // Mentok di ujung daftar / gulir dibatalkan: jangan simpan kecepatan semu.
            if (abs(step) > 0.5f && abs(consumed) < abs(step) * 0.25f) vel = 0f
            if (returning && abs(delta) < 40f) returning = false
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Hanya mengamati (tidak mengonsumsi) sentuhan, jadi gulir & ketuk baris tetap normal.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        touching = event.changes.any { it.pressed }
                    }
                }
            },
    ) {
        val h = maxHeight
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(),
        ) {
            item { Spacer(Modifier.height(h * CENTER_FRACTION)) }
            itemsIndexed(lines) { index, line ->
                LyricRow(
                    line = line,
                    positionMs = { positionMs },
                    translation = translations?.getOrNull(index),
                    uniform = !follow,
                    distance = abs(index - active),
                    isActive = index == active,
                    blurEnabled = !userHold,
                    onClick = {
                        userHold = false
                        onSeek(line.timeMs)
                    },
                )
            }
            item { Spacer(Modifier.height(h * (1f - CENTER_FRACTION))) }
        }
    }
}

@Composable
private fun LyricRow(
    line: LyricLine,
    positionMs: () -> Long,
    translation: String?,
    uniform: Boolean,
    distance: Int,
    isActive: Boolean,
    blurEnabled: Boolean,
    onClick: () -> Unit,
) {
    // Nilai animasi hanya dibaca di dalam graphicsLayer (fase gambar): tidak ada rekomposisi/layout ulang
    // per frame, jadi gulir tetap ringan walau banyak baris berubah sekaligus.
    val alpha by animateFloatAsState(
        targetValue = when {
            uniform -> 1f // tidak mengikuti: semua baris putih penuh
            isActive -> 1f
            distance == 1 -> 0.52f
            distance == 2 -> 0.40f
            else -> 0.32f
        },
        animationSpec = smoothSpec(tween(450)),
        label = "lyricAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (isActive || uniform) 1f else 0.91f,
        animationSpec = smoothSpec(tween(450, easing = FastOutSlowInEasing)),
        label = "lyricScale",
    )
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blurPx by animateFloatAsState(
        targetValue = if (blurEnabled && canBlur && !isActive && !uniform) distance.coerceAtMost(4) * 1.1f else 0f,
        animationSpec = smoothSpec(tween(450)),
        label = "lyricBlur",
    )

    val text = line.text
    val gap = text.isEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                if (canBlur && blurPx > 0.05f) {
                    val r = blurPx * density
                    renderEffect = BlurEffect(r, r, TileMode.Decal)
                } else {
                    renderEffect = null
                }
            }
            .padding(horizontal = 24.dp, vertical = 11.dp),
    ) {
        if (!gap && !uniform && line.words.isNotEmpty()) {
            // Ada waktu per kata / suku kata: sorotan menyapu huruf demi huruf mengikuti nyanyian.
            KaraokeText(line = line, isActive = isActive, positionMs = positionMs, color = LocalLyricsTextColor.current)
        } else {
            Text(
                text = if (gap) "• • •" else text,
                color = LocalLyricsTextColor.current,
                fontSize = if (gap) 22.sp else 30.sp,
                lineHeight = if (gap) 30.sp else 38.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        // Terjemahan tampil tepat di bawah barisnya (seperti Spotify): lebih kecil dan sedikit redup.
        if (!gap && !translation.isNullOrBlank()) {
            Text(
                text = translation,
                color = LocalLyricsTextColor.current.copy(alpha = 0.78f),
                fontSize = 19.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Kecerahan huruf yang belum dinyanyikan, dibanding yang sudah disorot (1,0). */
private const val UNSUNG_ALPHA = 0.5f

/**
 * Waktu tiap huruf dari waktu per kata / suku kata: huruf-huruf satu potongan membagi durasinya sama rata,
 * jadi sorotan merambat huruf demi huruf (tidak lompat per kata).
 */
private class CharTiming(line: LyricLine) {
    val start = LongArray(line.text.length)
    val end = LongArray(line.text.length)
    val firstStart: Long = line.words.first().startMs
    val lastEnd: Long

    init {
        var i = 0
        var tail = firstStart
        for (w in line.words) {
            val n = w.text.length
            val d = (w.endMs - w.startMs).coerceAtLeast(0L)
            for (k in 0 until n) {
                if (i >= start.size) break
                start[i] = w.startMs + d * k / n
                end[i] = w.startMs + d * (k + 1) / n
                i++
            }
            if (w.endMs > tail) tail = w.endMs
        }
        while (i < start.size) { // jaga-jaga kalau teks lebih panjang dari gabungan potongan
            start[i] = tail
            end[i] = tail
            i++
        }
        lastEnd = tail
    }
}

/**
 * Teks baris dengan sorotan per huruf. Seluruh gambar dilakukan di fase draw: posisi lagu dibaca di dalam
 * lambda draw, jadi 60 kali per detik hanya menggambar ulang (tanpa rekomposisi / ukur ulang teks).
 *
 * Huruf yang belum dinyanyikan redup ([UNSUNG_ALPHA]); tiap huruf terisi dari kiri ke kanan sebesar
 * kemajuan waktunya. Di luar rentang nyanyian (baris belum mulai dan bukan baris aktif, atau sudah selesai)
 * teks digambar penuh seperti baris biasa.
 */
@Composable
private fun KaraokeText(
    line: LyricLine,
    isActive: Boolean,
    positionMs: () -> Long,
    color: Color,
) {
    val timing = remember(line) { CharTiming(line) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val dim = color.copy(alpha = color.alpha * UNSUNG_ALPHA)
    Text(
        text = line.text,
        // Teks asli tidak digambar (transparan): kedua lapisan, redup dan terang, digambar manual di bawah.
        color = Color.Transparent,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithContent {
            val l = layout ?: return@drawWithContent
            val pos = positionMs()
            val singing = pos in timing.firstStart until timing.lastEnd || (isActive && pos < timing.firstStart)
            if (!singing) {
                drawText(l, color = color)
                return@drawWithContent
            }
            drawText(l, color = dim)
            val path = Path()
            val text = line.text
            for (i in text.indices) {
                if (text[i].isWhitespace()) continue
                val s = timing.start[i]
                val e = timing.end[i]
                val p = if (e > s) ((pos - s).toFloat() / (e - s)).coerceIn(0f, 1f) else if (pos >= s) 1f else 0f
                if (p <= 0f) continue
                val b = l.getBoundingBox(i)
                path.addRect(Rect(b.left, b.top, b.left + b.width * p, b.bottom))
            }
            if (!path.isEmpty) clipPath(path) { drawText(l, color = color) }
        },
    )
}

/**
 * Gulir dari pengikut otomatis bisa "kalah" dari gulir pengguna (prioritas lebih tinggi) dan Compose melempar
 * CancellationException ke pemanggil. Itu tidak boleh mematikan pengikut selamanya, jadi ditelan di sini
 * (kecuali coroutine-nya sendiri memang dibatalkan).
 */
private suspend fun safeScroll(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}

/** Pudarkan tepi atas/bawah daftar supaya baris menghilang halus, bukan terpotong. */
private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.07f to Color.Black,
                0.90f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

// -------------------------------------------------------------------- polos

@Composable
private fun PlainLyrics(
    lines: List<LyricLine>,
    songKey: String,
    translations: List<String>?,
    jump: LyricsJump?,
    onJumpHandled: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(songKey) { listState.scrollToItem(0) }
    // Hasil Cari lirik: gulir ke baris yang dipilih (+1 karena item pertama adalah keterangan di atas).
    LaunchedEffect(jump) {
        val j = jump ?: return@LaunchedEffect
        listState.animateScrollToItem((j.index + 1).coerceIn(0, lines.size))
        onJumpHandled()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .fadingEdges(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
    ) {
        item {
            Text(
                t("Lirik ini belum punya sinkronisasi waktu"),
                style = MaterialTheme.typography.labelMedium,
                color = LocalLyricsTextColor.current.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 18.dp),
            )
        }
        itemsIndexed(lines) { index, line ->
            if (line.text.isEmpty()) {
                Spacer(Modifier.height(18.dp))
            } else {
                Column(Modifier.padding(vertical = 3.dp)) {
                Text(
                    line.text,
                    color = LocalLyricsTextColor.current.copy(alpha = 0.92f),
                    fontSize = 21.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val tr = translations?.getOrNull(index)
                if (!tr.isNullOrBlank()) {
                    Text(
                        tr,
                        color = LocalLyricsTextColor.current.copy(alpha = 0.7f),
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

// ------------------------------------------------------------------- pesan

@Composable
private fun LyricsMessage(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = LocalLyricsTextColor.current.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = LocalLyricsTextColor.current,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalLyricsTextColor.current.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}
