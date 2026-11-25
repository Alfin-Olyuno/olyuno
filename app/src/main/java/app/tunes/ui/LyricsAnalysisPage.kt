package app.tunes.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.ai.AiErrorKind
import app.tunes.data.AiModel
import app.tunes.innertube.CollectionKind
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.SongItem
import app.tunes.ai.AiRef
import app.tunes.ai.EntityRef
import app.tunes.ai.SearchTopic
import app.tunes.ai.SearchTopics
import app.tunes.ai.SongRef
import app.tunes.ai.SongRefs
import app.tunes.data.BedahEntry
import app.tunes.data.ShimmerLevel
import app.tunes.i18n.t
import app.tunes.player.PlayerState

/**
 * Halaman "Tanya AI": obrolan bebas soal musik dengan AI (Gemini lewat kunci API milik pengguna).
 *
 * Saat dibuka halaman ini kosong: hanya panah + judul, tombol riwayat (hamburger) di kiri atas, dan kolom
 * "Tanya Olyuno" di bawah. Jawaban tampil seperti asisten AI pada umumnya (teks polos, bukan kartu).
 * Riwayat, pilihan model, dan kunci API Gemini ada di panel samping.
 */
@Composable
fun LyricsAnalysisPage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val chatBusy by vm.chatBusy.collectAsStateWithLifecycle()
    val chatError by vm.chatError.collectAsStateWithLifecycle()
    val convoId by vm.convoId.collectAsStateWithLifecycle()
    val history by vm.bedahHistory.collectAsStateWithLifecycle()
    val model by vm.aiPrefs.model.collectAsStateWithLifecycle()

    // Topik pertanyaan terakhir (lagu / playlist / artis) untuk teks indikator di samping titik menunggu.
    val lastQuestion = chat.lastOrNull()?.takeIf { it.fromUser }?.text
    val searchTopics = remember(lastQuestion) { SearchTopics.detect(lastQuestion) }

    var showSettings by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val imeUp = rememberImeVisible()
    // Back menutup panel riwayat dulu sebelum keluar halaman.
    androidx.activity.compose.BackHandler(enabled = drawerOpen) { drawerOpen = false }

    // Pesan baru, titik menunggu muncul, atau galat: gulir ke bawah.
    // Selama jawaban mengalir dan setelah selesai, tidak ada lompatan ke atas pesan: yang mengikuti ujung teks ada di bawah.
    var prevBusy by remember { mutableStateOf(false) }
    LaunchedEffect(chat.size, chatBusy, chatError) {
        val justFinished = prevBusy && !chatBusy
        prevBusy = chatBusy
        if (justFinished && chatError == null) return@LaunchedEffect
        if (chatBusy && chat.lastOrNull()?.fromUser == false) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }
    // Jawaban mengalir: ikuti ujung teks selama pengguna tidak sedang menggulir sendiri.
    val streamLen = chat.lastOrNull()?.takeIf { !it.fromUser }?.text?.length ?: 0
    LaunchedEffect(streamLen) {
        if (streamLen > 0 && chatBusy && !listState.isScrollInProgress) listState.scrollBy(100000f)
    }

    val send = {
        val q = input.trim()
        if (q.isNotEmpty() && !chatBusy) {
            vm.sendMessage(q)
            input = ""
        }
    }
    // Dikte suara: hasilnya masuk ke kolom input, atau langsung dikirim kalau pengguna menekan tombol kirim.
    val dictation = rememberDictation { text, sendNow ->
        val merged = (input.trimEnd() + " " + text).trim()
        if (sendNow && !chatBusy) {
            vm.sendMessage(merged)
            input = ""
        } else {
            input = merged
        }
    }
    // Setelah kunci disimpan, ulangi pertanyaan yang tadi gagal karena kunci.
    val onKeySaved = {
        val e = vm.chatError.value
        if (e == AiErrorKind.NoKey || e == AiErrorKind.BadKey) vm.retryAnswer()
    }

    SubPage(
        title = t("Tanya AI"),
        vm = vm,
        player = player,
        onBack = onBack,
        onOpenPlayer = onOpenPlayer,
        showTopBlur = false,
        showMiniPlayer = false, // kolom "Tanya Olyuno" yang tetap di bawah tidak boleh tertutup pemutar mini
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = LocalTopInset.current).imePadding()) {
                // Baris tetap di bawah panah kembali: hamburger (riwayat, kunci API) di kiri, tombol model di kanan.
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { drawerOpen = true }) {
                        Icon(Icons.Rounded.Menu, contentDescription = t("Riwayat chat"))
                    }
                    Spacer(Modifier.weight(1f))
                    ModelDropdown(
                        selected = model,
                        onSelect = { vm.aiPrefs.setModel(it) },
                        onSettings = { showSettings = true },
                    )
                }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(chat) { index, m ->
                        if (m.fromUser) {
                            UserMessage(m.text)
                        } else {
                            AiMessage(
                                text = m.text,
                                streaming = chatBusy && index == chat.lastIndex,
                                vm = vm,
                                currentId = player.current?.videoId,
                                isPlaying = player.isPlaying,
                                question = chat.getOrNull(index - 1)?.takeIf { it.fromUser }?.text,
                                onPlay = { song ->
                                    // Lagu yang sudah diputar tidak dimulai ulang; langsung buka pemutar.
                                    if (player.current?.videoId != song.videoId) vm.playSong(song)
                                    onOpenPlayer()
                                },
                                onOpenEntity = { ref -> vm.openCollection(ref) },
                            )
                        }
                    }
                    if (chatBusy && chat.lastOrNull()?.fromUser == true) item { TypingIndicator(searchTopics) }
                    chatError?.let { kind ->
                        item {
                            ErrorRow(
                                kind = kind,
                                onRetry = { vm.retryAnswer() },
                                onSetKey = { drawerOpen = true },
                                onSettings = { showSettings = true },
                            )
                        }
                    }
                }
                AskBar(
                    value = input,
                    onValueChange = { input = it },
                    busy = chatBusy,
                    onSend = send,
                    onStop = { vm.stopAnswer() },
                    dictation = dictation,
                    // Keyboard terbuka: rapat ke keyboard. Tertutup: di atas bar navigasi / pemutar mini.
                    modifier = Modifier.padding(top = 8.dp, bottom = if (imeUp) 8.dp else LocalBottomInset.current + 8.dp),
                )
            }
            HistoryDrawer(
                vm = vm,
                open = drawerOpen,
                entries = history,
                currentId = convoId,
                onClose = { drawerOpen = false },
                onNew = {
                    drawerOpen = false
                    vm.newChat()
                },
                onOpen = { e ->
                    drawerOpen = false
                    vm.openConversation(e)
                },
                onDelete = { id ->
                    if (convoId == id) vm.newChat()
                    vm.deleteBedahEntry(id)
                },
                onClearAll = {
                    vm.newChat()
                    vm.clearBedahHistory()
                },
                onKeySaved = onKeySaved,
            )
        }
    }

    if (showSettings) {
        AiSettingsDialog(vm = vm, onDismiss = { showSettings = false })
    }
}

private fun aiErrorText(kind: AiErrorKind): String = when (kind) {
    AiErrorKind.NoKey -> t("Kunci API belum diisi")
    AiErrorKind.BadKey -> t("Kunci API tidak valid atau tidak punya izin")
    AiErrorKind.ModelNotFound -> t("Model tidak ditemukan, cek ID model di pengaturan")
    AiErrorKind.Quota -> t("Batas pemakaian gratis habis, coba lagi nanti")
    AiErrorKind.Network -> t("Tidak ada koneksi internet")
    AiErrorKind.Blocked -> t("AI tidak bisa membahas lagu ini")
    AiErrorKind.Server, AiErrorKind.Parse -> t("Terjadi gangguan pada AI, coba lagi")
}

/** Galat jawaban AI: pesan + satu tombol yang pas (atur kunci, buka pengaturan, atau coba lagi). */
@Composable
private fun ErrorRow(
    kind: AiErrorKind,
    onRetry: () -> Unit,
    onSetKey: () -> Unit,
    onSettings: () -> Unit,
) {
    val label: String? = when (kind) {
        AiErrorKind.NoKey, AiErrorKind.BadKey -> t("Atur kunci API")
        AiErrorKind.ModelNotFound -> t("Pengaturan")
        AiErrorKind.Blocked -> null
        else -> t("Coba lagi")
    }
    val action: () -> Unit = when (kind) {
        AiErrorKind.NoKey, AiErrorKind.BadKey -> onSetKey
        AiErrorKind.ModelNotFound -> onSettings
        else -> onRetry
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(aiErrorText(kind), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (label != null) {
            TextButton(onClick = action, contentPadding = PaddingValues(0.dp)) { Text(label) }
        }
    }
}

/** true selama keyboard layar terbuka. */
@Composable
private fun rememberImeVisible(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

// ------------------------------------------------------------------ pesan

/** Pesan pengguna: gelembung di kanan. */
@Composable
private fun UserMessage(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(Alignment.End)
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/**
 * Jawaban AI: teks polos selebar halaman (tanpa gelembung / kartu), format markdown ringan.
 * Kalau AI membahas sesuatu (blok [[LAGU]] / [[ARTIS]] / [[ALBUM]] / [[PLAYLIST]] di akhir jawaban), di atas teks muncul
 * "Ringkasan >" yang tertutup; dibuka baru kartunya tampil. Yang ditampilkan hanya jenis yang disebut pengguna di pertanyaannya
 * (mis. "makna lagu ..." = kartu lagu saja); kalau pertanyaannya tidak menyebut jenis, jenis yang paling dulu dibahas AI.
 */
@Composable
private fun AiMessage(
    text: String,
    streaming: Boolean,
    vm: MainViewModel,
    currentId: String?,
    isPlaying: Boolean,
    question: String?,
    onPlay: (SongItem) -> Unit,
    onOpenEntity: (CollectionRef) -> Unit,
) {
    val parsed = remember(text, streaming) { SongRefs.parse(text, complete = !streaming) }
    val shown = remember(parsed, question) {
        val asked = SearchTopics.detect(question)
        val byAsked = parsed.items.filter { topicOfRef(it) in asked }
        when {
            byAsked.isNotEmpty() -> byAsked
            else -> parsed.items.firstOrNull()?.let { first -> parsed.items.filter { topicOfRef(it) == topicOfRef(first) } }.orEmpty()
        }
    }
    val songStates by vm.aiSongs.collectAsStateWithLifecycle()
    val entityStates by vm.aiEntities.collectAsStateWithLifecycle()
    // Cari lagu / artis / album / playlist-nya begitu daftarnya utuh (blok sudah ditutup atau jawaban selesai).
    LaunchedEffect(shown) {
        shown.forEach { r ->
            when (r) {
                is AiRef.Song -> vm.resolveAiSong(r.ref)
                is AiRef.Entity -> vm.resolveAiEntity(r.ref)
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (shown.isNotEmpty()) {
            SongSummary(shown, songStates, entityStates, currentId, isPlaying, onPlay, onOpenEntity)
            Spacer(Modifier.height(12.dp))
        }
        if (parsed.text.isNotBlank()) AiMarkdown(parsed.text, streaming)
    }
}

private val CARD_HEIGHT = 64.dp

private fun topicOf(kind: CollectionKind): SearchTopic = when (kind) {
    CollectionKind.ARTIST -> SearchTopic.Artist
    CollectionKind.ALBUM -> SearchTopic.Album
    CollectionKind.PLAYLIST -> SearchTopic.Playlist
    else -> SearchTopic.Song
}

private fun topicOfRef(r: AiRef): SearchTopic = when (r) {
    is AiRef.Song -> SearchTopic.Song
    is AiRef.Entity -> topicOf(r.ref.kind)
}

private fun kindLabel(kind: CollectionKind): String = when (kind) {
    CollectionKind.ARTIST -> t("Artis")
    CollectionKind.ALBUM -> t("Album")
    else -> t("Playlist")
}

/**
 * "Ringkasan >" (tertutup = hanya judul ini, tanpa kartu). Diketuk membentang jadi kartu lagu / artis / album / playlist
 * yang dibahas AI (urut seperti ditulis AI). Ketuk kartu lagu = putar dan buka pemutar; ketuk kartu artis / album / playlist = buka halamannya.
 */
@Composable
private fun SongSummary(
    refs: List<AiRef>,
    songStates: Map<String, MainViewModel.AiSongState>,
    entityStates: Map<String, MainViewModel.AiEntityState>,
    currentId: String?,
    isPlaying: Boolean,
    onPlay: (SongItem) -> Unit,
    onOpenEntity: (CollectionRef) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val smooth = LocalSmoothAnim.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = if (smooth) tween<Float>(180) else snap<Float>(),
        label = "summaryChevron",
    )
    val sizeAnim = if (smooth) Modifier.animateContentSize(tween(220)) else Modifier
    // Jenis yang kartunya masih dicari di YouTube Music; teksnya berganti kalau lebih dari satu jenis.
    val loadingTopics = refs.mapNotNull { r ->
        when (r) {
            is AiRef.Song -> {
                val st = songStates[r.ref.key]
                if (st == null || st is MainViewModel.AiSongState.Loading) SearchTopic.Song else null
            }
            is AiRef.Entity -> {
                val st = entityStates[r.ref.key]
                if (st == null || st is MainViewModel.AiEntityState.Loading) topicOf(r.ref.kind) else null
            }
        }
    }.distinct()
    Column(Modifier.fillMaxWidth().then(sizeAnim)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t("Ringkasan"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
            )
            if (loadingTopics.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                key(loadingTopics) { SearchingLabel(loadingTopics, MaterialTheme.typography.labelMedium) }
            }
            if (refs.size > 1) {
                Spacer(Modifier.weight(1f))
                Text(
                    if (refs.all { it is AiRef.Song }) t("%d lagu", refs.size) else t("%d hasil", refs.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                refs.forEach { r ->
                    RefCard(r, songStates, entityStates, currentId, isPlaying, Modifier.fillMaxWidth(), onPlay, onOpenEntity)
                }
            }
        }
    }
}

/** Memilih kartu yang pas untuk satu rujukan. */
@Composable
private fun RefCard(
    r: AiRef,
    songStates: Map<String, MainViewModel.AiSongState>,
    entityStates: Map<String, MainViewModel.AiEntityState>,
    currentId: String?,
    isPlaying: Boolean,
    modifier: Modifier,
    onPlay: (SongItem) -> Unit,
    onOpenEntity: (CollectionRef) -> Unit,
) {
    when (r) {
        is AiRef.Song -> {
            val st = songStates[r.ref.key]
            val song = (st as? MainViewModel.AiSongState.Found)?.song
            SongCard(r.ref, st, currentId, isPlaying, modifier, song?.let { s -> { onPlay(s) } })
        }
        is AiRef.Entity -> {
            val st = entityStates[r.ref.key]
            val found = (st as? MainViewModel.AiEntityState.Found)?.ref
            EntityCard(r.ref, st, modifier, found?.let { f -> { onOpenEntity(f) } })
        }
    }
}

/** Satu kartu lagu: sampul, judul, artis, ikon putar. [onClick] null = belum / tidak ketemu. */
@Composable
private fun SongCard(
    ref: SongRef,
    state: MainViewModel.AiSongState?,
    currentId: String?,
    isPlaying: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    val song = (state as? MainViewModel.AiSongState.Found)?.song
    val shape = RoundedCornerShape(18.dp)
    val missing = state is MainViewModel.AiSongState.Missing
    Row(
        modifier
            .height(CARD_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onClick != null && song != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (song != null) {
            Artwork(song.thumbnail, Modifier.size(48.dp), RoundedCornerShape(12.dp))
        } else {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song?.title ?: ref.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (missing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                song?.artist ?: ref.artist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (song != null) {
            // Lagu ini sedang diputar: ikonnya jadi jeda; dijeda atau lagu lain yang diputar: kembali ke putar.
            val nowPlaying = isPlaying && song.videoId == currentId
            Icon(
                if (nowPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (nowPlaying) t("Jeda") else t("Putar"),
                tint = if (song.videoId == currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp).size(24.dp),
            )
        }
    }
}

/**
 * Kartu artis (sampul bulat), album, atau playlist. Setelah ketemu di YouTube Music, yang tampil adalah judul aslinya;
 * ketuk = buka halaman aslinya. [onClick] null = belum / tidak ketemu.
 */
@Composable
private fun EntityCard(
    ref: EntityRef,
    state: MainViewModel.AiEntityState?,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    val found = (state as? MainViewModel.AiEntityState.Found)?.ref
    val missing = state is MainViewModel.AiEntityState.Missing
    val shape = RoundedCornerShape(18.dp)
    val thumbShape = if (ref.kind == CollectionKind.ARTIST) CircleShape else RoundedCornerShape(12.dp)
    val subtitle = found?.subtitle
        ?: listOfNotNull(kindLabel(ref.kind), ref.by.takeIf { it.isNotBlank() }).joinToString(" · ")
    Row(
        modifier
            .height(CARD_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onClick != null && found != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (found != null) {
            Artwork(found.thumbnail, Modifier.size(48.dp), thumbShape)
        } else {
            Box(Modifier.size(48.dp).clip(thumbShape).background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                found?.title ?: ref.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (missing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (found != null) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = t("Buka"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 2.dp).size(24.dp),
            )
        }
    }
}

/** Satu siklus penuh tiap gaya = 2400 ms; semua periode gaya membagi 2400, jadi putarannya tersambung mulus. */
private const val DOT_SEGMENT_MS = 2400f
private const val DOT_PATTERNS = 5
/** Lama peralihan halus antar gaya (di ujung tiap gaya). */
private const val DOT_BLEND_MS = 450f

/** Tampilan satu titik: [alpha], [scale], dan [lift] (dp, positif = naik). */
private class DotLook(val alpha: Float, val scale: Float, val lift: Float)

private fun smooth01(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** Denyut halus 0..1..0 per siklus (x = fase, boleh negatif / lebih dari 1). */
private fun wave01(x: Float): Float = 0.5f - 0.5f * cos(2f * PI.toFloat() * x)

private fun mix(a: Float, b: Float, w: Float): Float = a + (b - a) * w

/** Gerak titik ke-[i] (0..2) untuk gaya ke-[pattern] pada waktu [ms]. */
private fun dotLook(pattern: Int, ms: Float, i: Int): DotLook = when (pattern) {
    // 0 Gelombang: titik naik-turun bergantian dari kiri ke kanan.
    0 -> {
        val p = wave01(ms / 800f - i * 0.17f)
        DotLook(0.35f + 0.65f * p, 0.65f + 0.35f * p, 3.5f * p)
    }
    // 1 Mengetik: titik muncul satu per satu, lalu semuanya meredup bersama.
    1 -> {
        val x = (ms % 1200f) / 1200f
        val a = smooth01((x - i * 0.2f) / 0.14f) * (1f - smooth01((x - 0.84f) / 0.14f))
        DotLook(0.18f + 0.82f * a, 0.6f + 0.4f * a, 0f)
    }
    // 2 Sorot: terang berpindah dari satu titik ke titik berikutnya.
    2 -> {
        val w = wave01(ms / 1200f - i / 3f)
        val p = w * w * w
        DotLook(0.25f + 0.75f * p, 0.55f + 0.6f * p, 0f)
    }
    // 3 Napas: ketiganya membesar dan terang hampir serempak.
    3 -> {
        val p = wave01(ms / 1200f - i * 0.04f)
        DotLook(0.3f + 0.7f * p, 0.7f + 0.5f * p, 0f)
    }
    // 4 Ayun: naik-turun mengalir seperti gelombang sinus, kecerahan tetap.
    else -> {
        val sn = sin(2f * PI.toFloat() * (ms / 1200f - i * 0.22f))
        DotLook(0.85f, 1f, 2.5f * sn)
    }
}

/** Gaya aktif, dicampur halus ke gaya berikutnya di ujung segmennya supaya tidak ada lompatan. */
private fun blendedLook(ms: Float, i: Int): DotLook {
    val seg = (ms / DOT_SEGMENT_MS).toInt().coerceIn(0, DOT_PATTERNS - 1)
    val toEnd = (seg + 1) * DOT_SEGMENT_MS - ms
    val cur = dotLook(seg, ms, i)
    if (toEnd > DOT_BLEND_MS) return cur
    val nxt = dotLook((seg + 1) % DOT_PATTERNS, ms, i)
    val w = smooth01(1f - toEnd / DOT_BLEND_MS)
    return DotLook(mix(cur.alpha, nxt.alpha, w), mix(cur.scale, nxt.scale, w), mix(cur.lift, nxt.lift, w))
}

/**
 * Titik menunggu jawaban: tiga titik kecil berwarna aksen yang berganti-ganti gaya geraknya (gelombang, mengetik, sorot,
 * napas, ayun), berpindah antar gaya dengan mulus. Tampil sampai potongan pertama jawaban tiba.
 */
@Composable
private fun TypingIndicator(topics: List<SearchTopic> = emptyList()) {
    val transition = rememberInfiniteTransition(label = "typingDots")
    val total = DOT_SEGMENT_MS * DOT_PATTERNS
    val clock = transition.animateFloat(
        initialValue = 0f,
        targetValue = total,
        animationSpec = infiniteRepeatable(tween(total.toInt(), easing = LinearEasing)),
        label = "dotClock",
    )
    // Warna aksen tema yang sedang dipakai (ikut mode terang / gelap dan warna sistem), jadi selalu serasi dengan aplikasi.
    val color = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { contentDescription = t("Memuat") },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 3) {
            Box(
                Modifier
                    .size(6.dp)
                    // Waktu dibaca di dalam blok gambar: hanya menggambar ulang, tanpa menyusun ulang tiap frame.
                    .graphicsLayer {
                        val look = blendedLook(clock.value, i)
                        scaleX = look.scale
                        scaleY = look.scale
                        alpha = look.alpha
                        translationY = -look.lift * density
                    }
                    .background(color, CircleShape),
            )
        }
        if (topics.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            SearchingLabel(topics)
        }
    }
}

/** Satu sapuan kilau (kiri ke kanan) = 1600 ms. */
private const val SWEEP_MS = 1600

private fun searchLabel(topic: SearchTopic): String = when (topic) {
    SearchTopic.Song -> t("Mencari lagu")
    SearchTopic.Playlist -> t("Mencari playlist")
    SearchTopic.Artist -> t("Mencari artis")
    SearchTopic.Album -> t("Mencari album")
}

/**
 * Teks yang kilaunya menyapu dari kiri teks sampai kanan, lalu mengulang. Huruf digambar dulu, lalu gradien
 * (redup, terang, redup) menimpanya hanya di bagian huruf (BlendMode.SrcIn), jadi yang bergerak cuma warnanya.
 * [progress] dibaca saat menggambar, bukan saat menyusun, jadi tidak ada recompose tiap frame.
 */
@Composable
private fun ShimmerText(
    text: String,
    progress: () -> Float,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val scheme = MaterialTheme.colorScheme
    val level = LocalShimmerLevel.current
    if (level == ShimmerLevel.Off) {
        Text(text, style = style, color = scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        return
    }
    val base = scheme.onSurfaceVariant.copy(alpha = if (level == ShimmerLevel.Strong) 0.5f else 0.62f)
    val highlight = scheme.onSurface
    Text(
        text,
        style = style,
        // Hanya bentuk hurufnya yang dipakai; warna akhirnya berasal dari gradien di bawah.
        color = Color.Black,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val band = (size.width * 0.6f).coerceAtLeast(40f)
                val x = -band + progress() * (size.width + band)
                drawRect(
                    brush = Brush.horizontalGradient(colors = listOf(base, highlight, base), startX = x, endX = x + band),
                    blendMode = BlendMode.SrcIn,
                )
            },
    )
}

/**
 * Indikator di kanan titik menunggu: "Mencari lagu" / "Mencari playlist" / "Mencari artis" dengan kilau yang
 * menyapu kiri ke kanan. Kalau pertanyaannya menyentuh lebih dari satu hal, teksnya berganti tiap satu sapuan.
 */
@Composable
private fun SearchingLabel(topics: List<SearchTopic>, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val smooth = LocalSmoothAnim.current
    val n = topics.size
    val clock = rememberInfiniteTransition(label = "searchLabel").animateFloat(
        initialValue = 0f,
        targetValue = (SWEEP_MS * n).toFloat(),
        animationSpec = infiniteRepeatable(tween(SWEEP_MS * n, easing = LinearEasing)),
        label = "searchClock",
    )
    val index by remember(n) { derivedStateOf { (clock.value / SWEEP_MS).toInt().coerceIn(0, n - 1) } }
    AnimatedContent(
        targetState = topics[index.coerceIn(0, n - 1)],
        transitionSpec = {
            if (smooth) {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 }) togetherWith
                    (fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 2 })
            } else {
                EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = "searchLabelSwap",
    ) { topic ->
        ShimmerText(searchLabel(topic), progress = { (clock.value % SWEEP_MS) / SWEEP_MS }, style = style)
    }
}

// ---------------------------------------------------------------- markdown

private val HEADING = Regex("^#{1,6}\\s+(.*)")
private val BULLET = Regex("^(\\s*)[-*•]\\s+(.*)")
private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)")
private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val TABLE_SEP = Regex("^:?-{2,}:?$")
private val BULLET_GLYPHS = listOf("•", "◦", "▪")

/** Blok hasil penguraian jawaban AI. */
private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Bullet(val level: Int, val text: String) : MdBlock
    data class Numbered(val number: String, val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private fun indentLevel(ws: String): Int = if (ws.isEmpty()) 0 else if (ws.length < 6) 1 else 2

/**
 * Uraikan markdown ringan jadi blok: judul (#), paragraf, daftar berpoin / bernomor (dengan tingkat), kutipan (>),
 * blok kode (```), garis (---), dan tabel (|). Aman untuk teks yang masih mengalir (blok kode / tabel yang belum selesai).
 */
private fun parseBlocks(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.trim().lines()
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotEmpty()) {
            out += MdBlock.Para(para.toString())
            para.setLength(0)
        }
    }
    var i = 0
    while (i < lines.size) {
        val raw = lines[i].trimEnd()
        val t = raw.trim()
        val heading = HEADING.matchEntire(t)
        val bullet = BULLET.matchEntire(raw)
        val numbered = NUMBERED.matchEntire(raw)
        when {
            t.isEmpty() -> {
                flushPara()
                i++
            }
            t.startsWith("```") -> {
                flushPara()
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code += lines[i].trimEnd()
                    i++
                }
                i++ // lewati pagar penutup (atau lewat ujung teks kalau masih mengalir)
                out += MdBlock.Code(code.joinToString("\n"))
            }
            RULE.matches(t) -> {
                flushPara()
                out += MdBlock.Rule
                i++
            }
            heading != null -> {
                flushPara()
                out += MdBlock.Heading(t.takeWhile { it == '#' }.length, heading.groupValues[1].trim())
                i++
            }
            t.startsWith("|") -> {
                flushPara()
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                    if (!cells.all { TABLE_SEP.matches(it) }) rows += cells
                    i++
                }
                if (rows.isNotEmpty()) out += MdBlock.Table(rows)
            }
            t.startsWith(">") -> {
                flushPara()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    if (q.isNotEmpty()) q.append('\n')
                    q.append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                out += MdBlock.Quote(q.toString())
            }
            bullet != null -> {
                flushPara()
                out += MdBlock.Bullet(indentLevel(bullet.groupValues[1]), bullet.groupValues[2].trim())
                i++
            }
            numbered != null -> {
                flushPara()
                out += MdBlock.Numbered(numbered.groupValues[2], indentLevel(numbered.groupValues[1]), numbered.groupValues[3].trim())
                i++
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(t)
                i++
            }
        }
    }
    flushPara()
    return out
}

/**
 * Teks inline: ***tebal miring***, **tebal**, *miring*, `kode`, dan [label](url) (hanya label yang ditampilkan).
 * [streaming] = teks masih mengalir: penanda yang belum ditutup (** atau `) dianggap membuka gaya sampai ujung teks,
 * jadi tanda bintangnya tidak sempat tampil mentah lalu tiba-tiba berubah.
 */
private fun buildInline(s: String, codeBg: Color, streaming: Boolean): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        if (s.startsWith("***", i)) {
            val end = s.indexOf("***", i + 3)
            if (end > i + 3) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)) { append(s.substring(i + 3, end)) }
                i = end + 3
                continue
            }
        }
        if (s.startsWith("**", i)) {
            val end = s.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2, end)) }
                i = end + 2
                continue
            }
            if (end < 0 && streaming && i + 2 < s.length) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2)) }
                i = s.length
                continue
            }
        }
        if (s[i] == '*' && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != '*') {
            val end = s.indexOf('*', i + 1)
            if (end > i + 1 && !s[end - 1].isWhitespace()) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (s[i] == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }
                i = end + 1
                continue
            }
            if (end < 0 && streaming && i + 1 < s.length) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1)) }
                i = s.length
                continue
            }
        }
        if (s[i] == '[') {
            val mid = s.indexOf("](", i + 1)
            val endParen = if (mid > 0) s.indexOf(')', mid + 2) else -1
            if (mid > i + 1 && endParen > mid) {
                append(s.substring(i + 1, mid))
                i = endParen + 1
                continue
            }
        }
        append(s[i])
        i++
    }
}

@Composable
private fun rememberInline(text: String, streaming: Boolean): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    return remember(text, streaming, codeBg) { buildInline(text, codeBg, streaming) }
}

/**
 * Jawaban AI yang tertata: judul, paragraf pendek, daftar dengan indentasi menggantung, kutipan, kode, dan tabel,
 * dengan jarak antarblok yang konsisten. Satu SelectionContainer untuk semuanya, jadi teks bisa diblok lintas blok.
 */
@Composable
private fun AiMarkdown(text: String, streaming: Boolean) {
    val blocks = remember(text) { parseBlocks(text) }
    val scheme = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge.let { it.copy(lineHeight = it.lineHeight * 1.12f) }
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEachIndexed { idx, b ->
                // Hanya blok terakhir yang mungkin masih "setengah jadi" saat teks mengalir.
                val live = streaming && idx == blocks.lastIndex
                when (b) {
                    is MdBlock.Heading -> Text(
                        rememberInline(b.text, live),
                        style = (if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                            .copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                        modifier = Modifier.padding(top = if (idx == 0) 0.dp else 6.dp),
                    )
                    is MdBlock.Para -> Text(rememberInline(b.text, live), style = body, color = scheme.onSurface)
                    is MdBlock.Bullet -> Row(Modifier.fillMaxWidth().padding(start = 4.dp + 18.dp * b.level)) {
                        Text(
                            BULLET_GLYPHS[b.level.coerceIn(0, 2)],
                            style = body,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(rememberInline(b.text, live), style = body, color = scheme.onSurface, modifier = Modifier.weight(1f))
                    }
                    is MdBlock.Numbered -> Row(Modifier.fillMaxWidth().padding(start = 4.dp + 18.dp * b.level)) {
                        Text(
                            b.number + ".",
                            style = body.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.primary,
                            modifier = Modifier.width(28.dp),
                        )
                        Text(rememberInline(b.text, live), style = body, color = scheme.onSurface, modifier = Modifier.weight(1f))
                    }
                    is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(scheme.primary.copy(alpha = 0.45f)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            rememberInline(b.text, live),
                            style = body.copy(fontStyle = FontStyle.Italic),
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    is MdBlock.Code -> Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surfaceContainerHigh)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            b.text,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = scheme.onSurface,
                            softWrap = false,
                        )
                    }
                    is MdBlock.Table -> Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(12.dp)),
                    ) {
                        b.rows.forEachIndexed { r, cells ->
                            if (r > 0) HorizontalDivider(color = scheme.outlineVariant)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (r == 0) scheme.surfaceContainerHigh else Color.Transparent)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                cells.forEach { c ->
                                    Text(
                                        rememberInline(c, false),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = scheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                    MdBlock.Rule -> HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------ kolom input

/** Kolom input tetap di bawah halaman, placeholder "Tanya Olyuno". Mikrofon (dikte suara) di kiri tombol kirim / stop. */
@Composable
private fun AskBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    dictation: DictationState,
    modifier: Modifier = Modifier,
) {
    val canSend = !busy && value.isNotBlank()
    // Saat AI memproses / mengalirkan jawaban, tombol berubah jadi stop (selalu aktif).
    val active = busy || canSend
    val shape = RoundedCornerShape(28.dp)
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // Hanya garis tipis di tepi, tanpa latar pengisi.
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.animation.Crossfade(
            targetState = dictation.listening,
            animationSpec = tween(150),
            label = "askBarMode",
            modifier = Modifier.weight(1f),
        ) { voice ->
            if (voice) {
                // Mode dikte: batal, gelombang suara, selesai, kirim (kirim disembunyikan selama AI masih menjawab).
                VoiceRow(dictation, canSend = !busy)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(start = 14.dp, top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(t("Tanya Olyuno"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { onSend() }),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (dictation.available) {
                        SquircleButton(
                            Icons.Rounded.Mic,
                            t("Bicara"),
                            onClick = {
                                focus.clearFocus()
                                dictation.start()
                            },
                            accent = false,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    SquircleButton(
                        icon = if (busy) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                        description = if (busy) t("Berhenti") else t("Kirim"),
                        onClick = if (busy) onStop else onSend,
                        accent = true,
                        enabled = active,
                        iconSize = if (busy) 22 else 24,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ panel samping

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Panel dari kiri: obrolan baru, riwayat per hari, pilihan model, dan isian kunci API Gemini. */
@Composable
private fun HistoryDrawer(
    vm: MainViewModel,
    open: Boolean,
    entries: List<BedahEntry>,
    currentId: String?,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onOpen: (BedahEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onKeySaved: () -> Unit,
) {
    val imeUp = rememberImeVisible()
    val smooth = LocalSmoothAnim.current
    val scrim by animateFloatAsState(
        targetValue = if (open) 0.45f else 0f,
        animationSpec = if (smooth) tween<Float>(220) else snap<Float>(),
        label = "drawerScrim",
    )
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BedahEntry?>(null) }
    if (open || scrim > 0.001f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim))
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = onClose,
                ),
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = if (smooth) slideInHorizontally { -it } + fadeIn() else EnterTransition.None,
        exit = if (smooth) slideOutHorizontally { -it } + fadeOut() else ExitTransition.None,
    ) {
        val startToday = remember(open) {
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val today = t("Hari ini")
        val week = t("7 hari terakhir")
        val older = t("Lebih lama")
        val rows = remember(entries, startToday, today, week, older) {
            buildList<Any> {
                val groups = listOf(
                    today to { e: BedahEntry -> e.updatedAt >= startToday },
                    week to { e: BedahEntry -> e.updatedAt < startToday && e.updatedAt >= startToday - 6 * DAY_MS },
                    older to { e: BedahEntry -> e.updatedAt < startToday - 6 * DAY_MS },
                )
                groups.forEach { (label, match) ->
                    val g = entries.filter(match)
                    if (g.isNotEmpty()) { add(label); addAll(g) }
                }
            }
        }
        Column(
            Modifier
                .fillMaxHeight()
                .width(320.dp)
                .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                .background(glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh))
                .padding(top = LocalTopInset.current)
                .imePadding()
                .padding(bottom = if (imeUp) 0.dp else LocalBottomInset.current),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("Riwayat Tanya AI"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (rows.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = t("Hapus semua riwayat"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onNew)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(t("Obrolan baru"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (rows.isEmpty()) {
                Text(
                    t("Belum ada riwayat"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(rows, key = { if (it is BedahEntry) it.videoId else "h_$it" }) { r ->
                        if (r is BedahEntry) {
                            val sel = r.videoId == currentId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 1.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable { onOpen(r) }
                                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.title.ifBlank { t("Obrolan baru") }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sub = listOfNotNull(
                                        r.artist.takeIf { it.isNotBlank() },
                                        t("%d pesan", r.chat.size).takeIf { r.chat.isNotEmpty() },
                                    ).joinToString(" · ")
                                    if (sub.isNotEmpty()) {
                                        Text(
                                            sub,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(onClick = { pendingDelete = r }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = t("Hapus"), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            Text(
                                r.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
            ApiKeySection(vm = vm, onSaved = onKeySaved)
        }
    }
    if (confirmClear) {
        ConfirmDeleteDialog(
            title = t("Hapus semua riwayat?"),
            message = t("Semua obrolan akan dihapus dan tidak bisa dikembalikan."),
            confirmText = t("Hapus"),
            onConfirm = {
                confirmClear = false
                onClearAll()
            },
            onDismiss = { confirmClear = false },
        )
    }
    pendingDelete?.let { e ->
        ConfirmDeleteDialog(
            title = t("Hapus obrolan ini?"),
            message = t("\"%s\" akan dihapus dari riwayat.", e.title.ifBlank { t("Obrolan baru") }),
            confirmText = t("Hapus"),
            onConfirm = {
                pendingDelete = null
                onDelete(e.videoId)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Satu tombol "[nama model] >" di kanan atas; diketuk memunculkan dropdown pilihan model + pengaturan. */
@Composable
private fun ModelDropdown(selected: AiModel, onSelect: (AiModel) -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val smooth = LocalSmoothAnim.current
    val rotation by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = if (smooth) tween<Float>(180) else snap<Float>(),
        label = "modelChevron",
    )
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable { open = true }
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AiModel.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    trailingIcon = if (m == selected) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    } else null,
                    onClick = {
                        open = false
                        if (m != selected) onSelect(m)
                    },
                )
            }
        }
    }
}

/** Isian kunci API Gemini di dasar panel samping: status tersimpan, tempel / tampilkan, simpan, hapus. */
@Composable
private fun ApiKeySection(vm: MainViewModel, onSaved: () -> Unit) {
    val prefs = vm.aiPrefs
    val saved by prefs.apiKey.collectAsStateWithLifecycle()
    var key by remember(saved) { mutableStateOf(saved) }
    var show by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val save = {
        val k = key.trim()
        if (k.isNotEmpty() && k != saved) {
            prefs.setApiKey(k)
            onSaved()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Kunci API Gemini"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                if (saved.isNotEmpty()) t("Tersimpan") else t("Belum diisi"),
                style = MaterialTheme.typography.labelMedium,
                color = if (saved.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = { Text(t("Tempel kunci API di sini")) },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null)
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { save() }, enabled = key.isNotBlank() && key.trim() != saved) { Text(t("Simpan")) }
            if (saved.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    prefs.setApiKey("")
                    key = ""
                }) { Text(t("Hapus kunci"), color = MaterialTheme.colorScheme.error) }
            }
        }
        Text(
            t("Kunci hanya disimpan di perangkat ini."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }, contentPadding = PaddingValues(0.dp)) {
            Text(t("Cara mendapat kunci"))
        }
    }
}

/** Pengaturan lanjutan: ID model Gemini yang dipanggil (kunci API diisi di panel samping). */
@Composable
private fun AiSettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val prefs = vm.aiPrefs
    val ids by prefs.modelIds.collectAsStateWithLifecycle()
    val idFields = remember { AiModel.entries.associateWith { m -> mutableStateOf(ids[m] ?: m.defaultId) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Pengaturan Tanya AI")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    t("Tanya AI ditenagai Gemini dari Google. Judul, artis, dan lirik yang dianalisis dikirim ke Google. Di paket gratis, isi permintaan dapat dipakai Google untuk meningkatkan produknya."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AiModel.entries.forEach { m ->
                    val field = idFields.getValue(m)
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = { field.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(t("ID model %s", m.label)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AiModel.entries.forEach { m -> prefs.setModelId(m, idFields.getValue(m).value) }
                onDismiss()
            }) { Text(t("Simpan")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Batal")) } },
    )
}
