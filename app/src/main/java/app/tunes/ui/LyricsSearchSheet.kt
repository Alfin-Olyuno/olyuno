package app.tunes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tunes.i18n.t
import app.tunes.lyrics.LyricLine
import kotlinx.coroutines.delay

/**
 * Permintaan untuk membawa tampilan lirik ke baris [index] (dari hasil pencarian). [token] dibuat baru
 * setiap kali supaya memilih baris yang sama dua kali tetap memicu gulir.
 */
data class LyricsJump(val index: Int, val token: Long)

/** Indeks baris lirik (atau terjemahannya) yang memuat [query], tanpa membedakan huruf besar-kecil. */
internal fun searchLyricLines(lines: List<LyricLine>, translations: List<String>?, query: String): List<Int> {
    val q = query.trim().replace(Regex("""\s+"""), " ")
    if (q.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    for ((i, line) in lines.withIndex()) {
        if (line.text.isBlank()) continue
        val tr = translations?.getOrNull(i).orEmpty()
        if (line.text.contains(q, ignoreCase = true) || tr.contains(q, ignoreCase = true)) out += i
    }
    return out
}

private fun highlighted(text: String, q: String, color: Color): AnnotatedString = buildAnnotatedString {
    if (q.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var i = 0
    while (true) {
        val j = text.indexOf(q, i, ignoreCase = true)
        if (j < 0) {
            append(text.substring(i))
            break
        }
        append(text.substring(i, j))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(text.substring(j, j + q.length)) }
        i = j + q.length
    }
}

/**
 * Modal "Cari lirik": ketik bagian lirik, hasilnya daftar baris yang memuat tulisan itu (kata yang cocok
 * ditebalkan). Memilih satu hasil memanggil [onPick] dengan indeks barisnya.
 *
 * Ukuran modal TETAP (tidak ikut membesar saat hasil banyak): judul + kolom cari menempel di atas,
 * hanya daftar hasil yang bisa digulir.
 */
@Composable
fun LyricsSearchSheet(
    lines: List<LyricLine>,
    translations: List<String>?,
    synced: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().replace(Regex("""\s+"""), " ")
    val matches = remember(lines, translations, q) { searchLyricLines(lines, translations, q) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogDim()
        SheetThemed {
            LaunchedEffect(Unit) {
                delay(150)
                runCatching { focus.requestFocus() }
            }
            Surface(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    // Tinggi tetap 460dp, tapi tidak melebihi 90% tinggi layar (landscape / layar pendek).
                    .height(minOf(460f, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.9f).dp),
                shape = RoundedCornerShape(28.dp),
                color = sheetContainerColor(),
                contentColor = sheetContentColor(),
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp)) {
                    // ---- Bagian atas: tetap, tidak ikut tergulir ----
                    Text(
                        t("Cari lirik"),
                        color = sheetInk,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                        placeholder = { Text(t("Ketik bagian lirik…")) },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = t("Hapus"))
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = sheetInk,
                            unfocusedTextColor = sheetInk,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    )
                    Spacer(Modifier.height(8.dp))
                    // ---- Bagian bawah: mengisi sisa tinggi, hanya daftar ini yang digulir ----
                    when {
                        q.isEmpty() -> Hint(t("Ketik kata atau kalimat dari lirik yang ingin dicari."))
                        matches.isEmpty() -> Hint(t("Tidak ditemukan"))
                        else -> {
                            Text(
                                if (matches.size == 1) t("1 hasil") else t("%d hasil", matches.size),
                                color = sheetInk.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                            val accent = MaterialTheme.colorScheme.primary
                            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                                items(matches, key = { it }) { index ->
                                    val line = lines[index]
                                    val tr = translations?.getOrNull(index)?.takeIf { it.isNotBlank() && !it.equals(line.text, true) }
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onPick(index) }
                                            .padding(horizontal = 4.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        if (synced && line.timeMs >= 0) {
                                            Text(
                                                formatTime(line.timeMs),
                                                color = sheetInk.copy(alpha = 0.6f),
                                                style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.width(40.dp).padding(top = 3.dp),
                                            )
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                highlighted(line.text, q, accent),
                                                color = sheetInk,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            if (tr != null) {
                                                Text(
                                                    highlighted(tr, q, accent),
                                                    color = sheetInk.copy(alpha = 0.72f),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(top = 2.dp),
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
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = sheetInk.copy(alpha = 0.72f),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
    )
}
