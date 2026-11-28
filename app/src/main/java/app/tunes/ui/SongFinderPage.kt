package app.tunes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.SearchState
import app.tunes.i18n.t
import app.tunes.innertube.SearchHit
import app.tunes.innertube.SongItem
import app.tunes.player.PlayerState

/**
 * Halaman "Cari lagu": tombol mikrofon di tengah. Ketuk, lalu nyanyikan / ucapkan potongan lirik (atau judul);
 * suaranya diubah jadi teks oleh pengenal suara bawaan perangkat, lalu dipakai mencari lagu di YouTube Music.
 * Ketuk lagi untuk mengakhiri lebih cepat. Hasil berupa daftar lagu yang bisa langsung diputar.
 */
@Composable
fun SongFinderPage(
    vm: MainViewModel,
    player: PlayerState,
    currentId: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val context = LocalContext.current
    val findState by vm.findState.collectAsStateWithLifecycle()

    var listening by remember { mutableStateOf(false) }
    var heardText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    // Bahasa pengenal suara: "" = otomatis (ikut HP), atau kode seperti "id-ID" / "en-US". Disimpan antar-sesi.
    val prefs = remember { context.getSharedPreferences("find_song", android.content.Context.MODE_PRIVATE) }
    var lang by remember { mutableStateOf(prefs.getString("lang", "") ?: "") }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val recognizer = remember { if (available) SpeechRecognizer.createSpeechRecognizer(context) else null }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onEndOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    // rmsdB kira-kira -2..10; jadikan 0..1 untuk denyut lingkaran.
                    level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) heardText = text
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    level = 0f
                    // Pengenal suara memberi beberapa tebakan; yang pertama dipakai utama, sisanya membantu kalau salah dengar.
                    val all = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    val text = all.firstOrNull().orEmpty()
                    if (text.isEmpty()) {
                        message = t("Tidak terdengar jelas, coba lagi")
                    } else {
                        heardText = text
                        message = null
                        vm.findSongByText(text, all.drop(1))
                    }
                }

                override fun onError(error: Int) {
                    listening = false
                    level = 0f
                    message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> t("Tidak terdengar jelas, coba lagi")
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> t("Tidak ada koneksi internet")
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> t("Izin mikrofon diperlukan untuk mencari lagu dengan suara")
                        else -> t("Pengenalan suara gagal, coba lagi")
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
        heardText = ""
        message = null
        vm.clearFind()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (lang.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }
        listening = true
        runCatching { r.startListening(intent) }.onFailure {
            listening = false
            message = t("Pengenalan suara gagal, coba lagi")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else message = t("Izin mikrofon diperlukan untuk mencari lagu dengan suara")
    }

    val onMicClick: () -> Unit = {
        when {
            !available -> message = t("Pengenal suara tidak tersedia di perangkat ini")
            listening -> runCatching { recognizer?.stopListening() }
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startListening()
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun submitEdit() {
        editing = false
        val q = draft.trim()
        if (q.isNotEmpty()) {
            heardText = q
            vm.findSongByText(q)
        }
    }
    val chooseLang: (String) -> Unit = { code ->
        lang = code
        prefs.edit().putString("lang", code).apply()
    }

    SubPage(
        title = t("Cari lagu"),
        vm = vm,
        player = player,
        onBack = onBack,
        onOpenPlayer = onOpenPlayer,
    ) {
        val hasSearch = findState !is SearchState.Idle
        if (!hasSearch) {
            // Tampilan awal: mikrofon besar di tengah halaman.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = LocalTopInset.current, bottom = LocalBottomInset.current)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MicButton(listening = listening, level = level, size = 112.dp, onClick = onMicClick)
                Spacer(Modifier.height(40.dp))
                Text(
                    text = when {
                        listening -> t("Mendengarkan…")
                        message != null -> message!!
                        else -> t("Ketuk mikrofon")
                    },
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                // Teks kecil di bawahnya hanya muncul kalau ada isinya (hasil dengar sementara).
                val sub = when {
                    listening -> heardText.takeIf { it.isNotBlank() }
                    else -> null
                }
                if (sub != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(24.dp))
                LanguageChips(selected = lang, onSelect = chooseLang)
            }
        } else {
            // Setelah suara dikenali: mikrofon mengecil di atas, hasil lagu di bawahnya.
            Column(Modifier.fillMaxSize().padding(top = LocalTopInset.current)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MicButton(listening = listening, level = level, size = 56.dp, onClick = onMicClick)
                    if (editing) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submitEdit() }),
                            trailingIcon = {
                                IconButton(onClick = { submitEdit() }) {
                                    Icon(Icons.Rounded.Search, contentDescription = t("Cari lagu"))
                                }
                            },
                        )
                    } else {
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !listening) {
                                    draft = heardText
                                    editing = true
                                },
                        ) {
                            Text(
                                text = if (listening) t("Mendengarkan…") else t("Terdengar") + " · " + t("ketuk untuk mengedit"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = heardText,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                LanguageChips(selected = lang, onSelect = chooseLang, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                when (val s = findState) {
                    SearchState.Idle -> Unit
                    SearchState.Loading -> SongListSkeleton()
                    is SearchState.Error -> CenterMessage(s.message)
                    is SearchState.Results -> {
                        val songs: List<SongItem> = (
                            s.results.top.filterIsInstance<SearchHit.Track>().map { it.song } + s.results.songs
                            ).distinctBy { it.videoId }
                        if (songs.isEmpty()) {
                            CenterMessage(t("Tidak ada lagu yang cocok"))
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = LocalBottomInset.current),
                            ) {
                                items(songs, key = { "find_${it.videoId}" }) { song ->
                                    SongRow(
                                        song = song,
                                        isCurrent = song.videoId == currentId,
                                        onClick = { vm.playSong(song) },
                                        menu = { dismiss -> SongMenu(vm, song, dismiss) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tombol mikrofon bulat; saat mendengarkan: ikon berubah jadi "berhenti" dan lingkaran di belakangnya berdenyut mengikuti volume suara. */
@Composable
private fun MicButton(listening: Boolean, level: Float, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "mic_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val smoothLevel by animateFloatAsState(level, tween(120), label = "level")
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.size(size * 1.9f), contentAlignment = Alignment.Center) {
        if (listening) {
            // Cincin yang melebar dan memudar, plus lingkaran yang membesar mengikuti volume suara.
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer {
                        val sc = 1f + 0.8f * pulse
                        scaleX = sc
                        scaleY = sc
                        alpha = 0.35f * (1f - pulse)
                    }
                    .background(color, CircleShape),
            )
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer {
                        val sc = 1.05f + 0.35f * smoothLevel
                        scaleX = sc
                        scaleY = sc
                        alpha = 0.22f
                    }
                    .background(color, CircleShape),
            )
        }
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = t("Mikrofon"),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

/** Pilihan bahasa pengenal suara: satu tombol "Otomatis >" yang membuka dropdown (Otomatis, Indonesia, English). */
@Composable
private fun LanguageChips(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf("" to t("Otomatis"), "id-ID" to "Indonesia", "en-US" to "English")
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected } ?: options.first()
    val arrow by animateFloatAsState(if (open) 90f else 0f, tween(180), label = "lang_arrow")
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { open = true }
                .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current.second,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = arrow },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (code, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = {
                        if (code == selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(code)
                    },
                )
            }
        }
    }
}
