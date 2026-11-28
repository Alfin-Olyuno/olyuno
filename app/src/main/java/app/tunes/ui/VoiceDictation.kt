package app.tunes.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.tunes.i18n.t

/** Cara dikte diakhiri: [Keep] teks masuk ke kolom, [Send] teks langsung dikirim, [Cancel] dibuang. */
internal enum class DictationEnd { Keep, Send, Cancel }

private const val WAVE_BARS = 24

/**
 * Status dikte suara untuk kolom "Tanya Olyuno": mendengarkan atau tidak, tinggi batang gelombang suara
 * (dari volume mikrofon), dan teks sementara yang sudah dikenali.
 */
@Stable
internal class DictationState(val available: Boolean) {
    var listening by mutableStateOf(false)
    var partial by mutableStateOf("")
    /** Riwayat volume terbaru (0..1); yang paling baru di ujung kanan. */
    val levels = mutableStateListOf<Float>().also { l -> repeat(WAVE_BARS) { l.add(0f) } }

    internal var end = DictationEnd.Keep
    internal var onStart: () -> Unit = {}
    internal var onFinish: (Boolean) -> Unit = {}
    internal var onCancel: () -> Unit = {}

    fun start() = onStart()
    /** Berhenti mendengarkan; [sendNow] true = hasilnya langsung dikirim, false = masuk ke kolom input. */
    fun finish(sendNow: Boolean) = onFinish(sendNow)
    fun cancel() = onCancel()

    internal fun clear() {
        listening = false
        partial = ""
        for (i in levels.indices) levels[i] = 0f
    }
}

/**
 * Pengenal suara bawaan perangkat untuk mendikte pertanyaan. [onText] dipanggil dengan teks hasil dikenali
 * dan apakah langsung dikirim. Bahasa mengikuti pilihan di halaman "Cari lagu" (kosong = ikut HP).
 */
@Composable
internal fun rememberDictation(onText: (text: String, sendNow: Boolean) -> Unit): DictationState {
    val context = LocalContext.current
    val onTextNow by rememberUpdatedState(onText)
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val state = remember { DictationState(available) }
    val recognizer = remember { if (available) SpeechRecognizer.createSpeechRecognizer(context) else null }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onEndOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    // rmsdB kira-kira -2..10; jadikan 0..1 lalu geser riwayat ke kiri.
                    val lv = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    if (state.levels.isNotEmpty()) state.levels.removeAt(0)
                    state.levels.add(lv)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) state.partial = text
                }

                override fun onResults(results: Bundle?) {
                    val end = state.end
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    val text = heard.ifEmpty { state.partial.trim() }
                    state.clear()
                    if (end == DictationEnd.Cancel) return
                    if (text.isEmpty()) toast(t("Tidak terdengar jelas, coba lagi")) else onTextNow(text, end == DictationEnd.Send)
                }

                override fun onError(error: Int) {
                    val end = state.end
                    val text = state.partial.trim()
                    state.clear()
                    if (end == DictationEnd.Cancel) return
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            // Sudah ada teks sementara: pakai itu daripada dibuang.
                            if (text.isNotEmpty()) onTextNow(text, end == DictationEnd.Send) else toast(t("Tidak terdengar jelas, coba lagi"))
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> toast(t("Tidak ada koneksi internet"))
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> toast(t("Izin mikrofon diperlukan"))
                        else -> toast(t("Pengenalan suara gagal, coba lagi"))
                    }
                }
            },
        )
        onDispose {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
        }
    }

    fun startListening() {
        val r = recognizer ?: return
        val lang = context.getSharedPreferences("find_song", Context.MODE_PRIVATE).getString("lang", "") ?: ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (lang.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }
        state.clear()
        state.end = DictationEnd.Keep
        state.listening = true
        runCatching { r.startListening(intent) }.onFailure {
            state.clear()
            toast(t("Pengenalan suara gagal, coba lagi"))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else toast(t("Izin mikrofon diperlukan"))
    }

    state.onStart = {
        if (!state.listening) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    state.onFinish = { sendNow ->
        if (state.listening) {
            state.end = if (sendNow) DictationEnd.Send else DictationEnd.Keep
            runCatching { recognizer?.stopListening() }
        }
    }
    state.onCancel = {
        state.end = DictationEnd.Cancel
        runCatching { recognizer?.cancel() }
        state.clear()
    }
    return state
}

/**
 * Tombol kotak sudut bulat (40dp) untuk kolom Olyuno AI. [accent] true dan [enabled] = gradien warna primer;
 * selain itu latar netral tipis.
 */
@Composable
internal fun SquircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    accent: Boolean,
    enabled: Boolean = true,
    iconSize: Int = 22,
) {
    val scheme = MaterialTheme.colorScheme
    // Padat (tidak tembus): dilapis di atas warna latar supaya isi chat di belakang tidak terlihat lewat tombol.
    val neutral = scheme.onSurface.copy(alpha = 0.08f).compositeOver(scheme.background)
    val bg = if (accent && enabled) {
        Brush.verticalGradient(listOf(scheme.primary, lerp(scheme.primary, Color.Black, 0.22f)))
    } else {
        Brush.verticalGradient(listOf(neutral, neutral))
    }
    val tint = when {
        accent && enabled -> scheme.onPrimary
        enabled -> scheme.onSurface
        else -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    Crossfade(
        targetState = icon,
        animationSpec = tween(150),
        label = "squircleIcon",
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
    ) { ic ->
        androidx.compose.foundation.layout.Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(ic, contentDescription = description, modifier = Modifier.size(iconSize.dp), tint = tint)
        }
    }
}

/** Isi kolom saat mendikte: batal, gelombang suara + teks sementara, selesai, dan kirim. */
@Composable
internal fun VoiceRow(dictation: DictationState, canSend: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SquircleButton(Icons.Rounded.Close, t("Batal"), onClick = { dictation.cancel() }, accent = false)
        Column(
            Modifier.weight(1f).padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VoiceWave(dictation.levels, Modifier.fillMaxWidth().height(24.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                text = dictation.partial.ifBlank { t("Mendengarkan…") },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Selesai: teks masuk ke kolom supaya bisa dicek / diedit dulu.
        SquircleButton(Icons.Rounded.Check, t("Selesai"), onClick = { dictation.finish(false) }, accent = false)
        if (canSend) {
            Spacer(Modifier.width(6.dp))
            SquircleButton(Icons.Rounded.ArrowUpward, t("Kirim"), onClick = { dictation.finish(true) }, accent = true, iconSize = 24)
        }
    }
}

/** Gelombang suara: batang kapsul bergeser ke kiri, yang lama makin pudar. Hening = deretan titik. */
@Composable
private fun VoiceWave(levels: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val n = levels.size
        if (n == 0) return@Canvas
        val gap = 3.dp.toPx()
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        for (i in 0 until n) {
            val h = maxOf(bw, levels[i] * size.height)
            val a = 0.3f + 0.7f * (i / (n - 1).coerceAtLeast(1).toFloat())
            drawRoundRect(
                color = color.copy(alpha = a),
                topLeft = Offset(i * (bw + gap), (size.height - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f),
            )
        }
    }
}
