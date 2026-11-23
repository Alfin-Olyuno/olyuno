package app.tunes.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.ai.AiErrorKind
import app.tunes.data.AiModel
import app.tunes.data.BedahEntry
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

    var showSettings by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val imeUp = rememberImeVisible()
    // Back menutup panel riwayat dulu sebelum keluar halaman.
    androidx.activity.compose.BackHandler(enabled = drawerOpen) { drawerOpen = false }

    // Pesan baru, jawaban dimuat, atau galat: gulir ke bawah.
    LaunchedEffect(chat.size, chatBusy, chatError) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    val send = {
        val q = input.trim()
        if (q.isNotEmpty() && !chatBusy) {
            vm.sendMessage(q)
            input = ""
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
                    itemsIndexed(chat) { _, m ->
                        if (m.fromUser) UserMessage(m.text) else AiMessage(m.text)
                    }
                    if (chatBusy) item { TypingIndicator() }
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

/** Jawaban AI: teks polos selebar halaman (tanpa gelembung / kartu), format markdown ringan. */
@Composable
private fun AiMessage(text: String) {
    val rendered = remember(text) { renderMarkdown(text) }
    SelectionContainer {
        Text(
            rendered,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.12f),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun TypingIndicator() {
    ProvideShimmer {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).semantics { contentDescription = t("Memuat") },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ShimmerBox(Modifier.fillMaxWidth(0.85f).height(14.dp))
            ShimmerBox(Modifier.fillMaxWidth(0.95f).height(14.dp))
            ShimmerBox(Modifier.fillMaxWidth(0.5f).height(14.dp))
        }
    }
}

@Composable
private fun ErrorRow(kind: AiErrorKind, onRetry: () -> Unit, onSetKey: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            aiErrorText(kind),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (kind) {
                AiErrorKind.NoKey -> Button(onClick = onSetKey, modifier = Modifier.padding(start = 8.dp)) { Text(t("Atur kunci API")) }
                AiErrorKind.BadKey -> {
                    TextButton(onClick = onSetKey) { Text(t("Atur kunci API")) }
                    RetryTextButton(onClick = onRetry)
                }
                AiErrorKind.ModelNotFound -> {
                    TextButton(onClick = onSettings) { Text(t("Pengaturan Tanya AI")) }
                    RetryTextButton(onClick = onRetry)
                }
                else -> RetryTextButton(onClick = onRetry)
            }
        }
    }
}

// ---------------------------------------------------------------- markdown

private val HEADING = Regex("^#{1,6}\\s+(.*)")
private val BULLET = Regex("^\\s*[-*•]\\s+(.*)")

/** Markdown ringan: **tebal**, `kode`, judul (#) jadi tebal, daftar (- / *) jadi poin. Selebihnya teks apa adanya. */
private fun renderMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    val lines = src.trim().lines()
    lines.forEachIndexed { i, raw ->
        val line = raw.trimEnd()
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(heading.groupValues[1]) }
            bullet != null -> {
                append("•  ")
                appendInline(bullet.groupValues[1])
            }
            else -> appendInline(line)
        }
        if (i < lines.lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInline(s: String) {
    var i = 0
    while (i < s.length) {
        if (s.startsWith("**", i)) {
            val end = s.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (s[i] == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        append(s[i])
        i++
    }
}

// ------------------------------------------------------------ kolom input

/** Kolom input tetap di bawah halaman, placeholder "Tanya Olyuno". */
@Composable
private fun AskBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = !busy && value.isNotBlank()
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // Hanya garis tipis di tepi, tanpa latar pengisi.
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
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
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Send,
                contentDescription = t("Kirim"),
                modifier = Modifier.size(20.dp),
                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
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
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            DropdownMenuItem(
                text = { Text(t("Pengaturan Tanya AI")) },
                leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                onClick = {
                    open = false
                    onSettings()
                },
            )
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
