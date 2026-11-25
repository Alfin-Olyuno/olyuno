package app.tunes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tunes.data.Backup
import app.tunes.data.BackupData
import app.tunes.data.BackupFormatException
import app.tunes.data.DownloadEntry
import app.tunes.data.DownloadedCollection
import app.tunes.data.HistoryEntry
import app.tunes.data.StatArtist
import app.tunes.data.toStats
import app.tunes.innertube.AccountInfo
import app.tunes.innertube.CollectionKind
import app.tunes.innertube.CollectionRef
import app.tunes.innertube.SearchResults
import app.tunes.innertube.SongItem
import app.tunes.innertube.MixPlaylist
import app.tunes.innertube.AccountTab
import app.tunes.innertube.accountInfo
import app.tunes.innertube.isAuthError
import app.tunes.innertube.fallbackGenres
import app.tunes.innertube.genres
import app.tunes.innertube.isLocal
import app.tunes.innertube.isLocalId
import app.tunes.innertube.CollectionData
import app.tunes.innertube.CollectionInfo
import app.tunes.innertube.loadCollection
import app.tunes.innertube.quickPicks
import app.tunes.innertube.trendingSongs
import app.tunes.innertube.searchAll
import app.tunes.innertube.searchSuggestions
import kotlinx.coroutines.delay
import app.tunes.lyrics.LyricsSource
import app.tunes.lyrics.kindOrNull
import app.tunes.lyrics.LyricsState
import app.tunes.lyrics.TranslationState
import app.tunes.player.EqState
import app.tunes.player.PlayerConnection
import app.tunes.player.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import app.tunes.i18n.t
import app.tunes.i18n.userMessage

sealed interface DeviceSongsState {
    data object Loading : DeviceSongsState
    data object Error : DeviceSongsState
    data class Loaded(val songs: List<SongItem>) : DeviceSongsState
}

sealed interface CollectionState {
    data object Loading : CollectionState
    data class Error(val message: String) : CollectionState
    data class Loaded(
        val songs: List<SongItem>,
        val info: CollectionInfo = CollectionInfo(),
        val playlists: List<CollectionRef> = emptyList(),
    ) : CollectionState
}

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Error(val message: String) : SearchState
    data class Results(val results: SearchResults) : SearchState
}

// Cari lagu: berapa kandidat teratas yang dicocokkan ke lirik asli, dari sumber mana saja, dan batasnya.
private const val FIND_LYRIC_CANDIDATES = 10
private const val FIND_LYRIC_PARALLEL = 20
private const val FIND_LYRIC_TIMEOUT_MS = 4_500L
private val FIND_LYRIC_SOURCES = listOf(
    LyricsSource.Lrclib,
    LyricsSource.LyricsPlus,
    LyricsSource.BetterLyrics,
    LyricsSource.BiniLyrics,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as OlyunoApp).container
    private val innerTube = container.innerTube
    private val repo = container.songs

    private val connection = PlayerConnection(app, viewModelScope)
    val player: StateFlow<PlayerState> = connection.state

    // ---------------------------------------------------------------- search
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _search = MutableStateFlow<SearchState>(SearchState.Idle)
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private var searchJob: Job? = null

    // Saran pencarian saat mengetik (dari YouTube Music).
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()
    private var suggestJob: Job? = null
    private val suggestCache = HashMap<String, List<String>>()

    private fun updateSuggestions(q: String) {
        suggestJob?.cancel()
        val t = q.trim()
        if (t.isEmpty()) {
            _suggestions.value = emptyList()
            return
        }
        suggestCache[t.lowercase()]?.let {
            _suggestions.value = it
            return
        }
        suggestJob = viewModelScope.launch {
            // Tunggu sebentar: tidak mengirim permintaan untuk tiap huruf yang diketik cepat.
            delay(250)
            val list = try {
                innerTube.searchSuggestions(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (list.isNotEmpty()) suggestCache[t.lowercase()] = list
            _suggestions.value = list
        }
    }

    fun onQueryChange(q: String) {
        _query.value = q
        updateSuggestions(q)
        // Kolom dikosongkan -> kembali ke tampilan awal (riwayat terakhir diputar).
        if (q.isBlank()) {
            searchJob?.cancel()
            _search.value = SearchState.Idle
        }
    }

    fun submitSearch() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        container.searchHistory.add(q)
        runSearch(q)
    }

    private fun runSearch(q: String) {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _search.value = SearchState.Loading
            _search.value = try {
                val results = innerTube.searchAll(q)
                if (results.isEmpty) SearchState.Error(t("Tidak ada hasil untuk \"%s\"", q))
                else SearchState.Results(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SearchState.Error(e.userMessage("Gagal mencari"))
            }
        }
    }

    /** Riwayat kata kunci pencarian, terbaru di depan. */
    val searchHistory: StateFlow<List<String>> = container.searchHistory.items

    /** Ketuk salah satu riwayat: isi kolom lalu langsung cari. */
    fun searchFromHistory(q: String) {
        _query.value = q
        submitSearch()
    }

    fun removeSearchHistory(q: String) = container.searchHistory.remove(q)

    // ------------------------------------------------ Cari lagu (lewat suara)
    // Terpisah dari state pencarian biasa supaya kolom Cari di tab Cari tidak ikut berubah.
    private val _findState = MutableStateFlow<SearchState>(SearchState.Idle)
    val findState: StateFlow<SearchState> = _findState.asStateFlow()
    private var findJob: Job? = null

    /**
     * Cari lagu dari teks hasil kenali suara. [alternatives] = tebakan lain dari pengenal suara (kalau ada).
     * Langkah: cari dengan beberapa versi kueri lalu gabungkan peringkatnya, buang cover / karaoke / remix
     * dari atas, lalu cocokkan teks dengan lirik asli kandidat teratas supaya salah dengar kecil tetap ketemu.
     */
    fun findSongByText(q: String, alternatives: List<String> = emptyList()) {
        val text = q.trim()
        if (text.isEmpty()) return
        findJob?.cancel()
        findJob = viewModelScope.launch {
            _findState.value = SearchState.Loading
            _findState.value = try {
                val songs = rankFindResults(text, alternatives)
                if (songs.isEmpty()) SearchState.Error(t("Tidak ada hasil untuk \"%s\"", text))
                else SearchState.Results(SearchResults(emptyList(), songs, emptyList(), emptyList(), emptyList()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SearchState.Error(e.userMessage("Gagal mencari"))
            }
        }
    }

    private suspend fun rankFindResults(primary: String, alternatives: List<String>): List<SongItem> = coroutineScope {
        val heards = (listOf(primary) + alternatives.map { it.trim() })
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(3)
        val queries = buildList {
            add(primary)
            add("$primary lirik")
            heards.drop(1).forEach { add(it) }
        }.distinct().take(4)

        // 1) Beberapa kueri sekaligus; lagu yang muncul tinggi di beberapa kueri mendapat skor lebih besar.
        val outcomes = queries.map { q -> async { runCatching { innerTube.searchAll(q) } } }.awaitAll()
        if (outcomes.all { it.isFailure }) throw outcomes.first().exceptionOrNull() ?: java.io.IOException("search failed")
        val fused = LinkedHashMap<String, Double>()
        val byId = HashMap<String, SongItem>()
        val heardLower = primary.lowercase()
        outcomes.forEach { outcome ->
            val r = outcome.getOrNull() ?: return@forEach
            val list = (r.top.filterIsInstance<app.tunes.innertube.SearchHit.Track>().map { it.song } + r.songs)
                .distinctBy { it.videoId }
            list.take(12).forEachIndexed { rank, song ->
                byId.putIfAbsent(song.videoId, song)
                fused[song.videoId] = (fused[song.videoId] ?: 0.0) + 1.0 / (rank + 3)
            }
        }
        if (byId.isEmpty()) return@coroutineScope emptyList()
        // Versi cover / karaoke / remix / live diturunkan, kecuali memang itu yang diucapkan.
        val noise = listOf("cover", "karaoke", "instrumental", "remix", "live", "sped up", "slowed", "nightcore", "8d", "reaction")
        val maxFused = fused.values.maxOrNull() ?: 1.0
        val base = fused.mapValues { (id, v) ->
            val title = byId[id]!!.title.lowercase()
            val penalty = if (noise.any { it in title && it !in heardLower }) 0.6 else 1.0
            v / maxFused * penalty
        }
        val ordered = base.entries.sortedByDescending { it.value }.map { it.key }

        // 2) Cocokkan dengan lirik asli 10 kandidat teratas, dari semua sumber lirik sekaligus (LRCLIB, Lyrics Plus,
        //    Better Lyrics, BiniLyrics). Nilai tertinggi dari sumber mana pun dipakai, jadi lagu yang liriknya
        //    cuma ada di satu sumber tetap terdeteksi. Semaphore membatasi permintaan jaringan yang berjalan bersamaan.
        val top = ordered.take(FIND_LYRIC_CANDIDATES)
        val gate = Semaphore(FIND_LYRIC_PARALLEL)
        val sims = top.map { id ->
            async { id to bestLyricSimilarity(heards, byId[id]!!, gate) }
        }.awaitAll().toMap()
        val scored = top.sortedByDescending { id ->
            val sim = sims[id]
            0.4 * (base[id] ?: 0.0) + (if (sim != null) 0.6 * sim else 0.2)
        }
        (scored + ordered.drop(FIND_LYRIC_CANDIDATES)).map { byId[it]!! }
    }

    /** Kemiripan tertinggi dengan lirik [song] di antara semua sumber lirik; null kalau tak satu pun punya liriknya. */
    private suspend fun bestLyricSimilarity(heards: List<String>, song: SongItem, gate: Semaphore): Double? = coroutineScope {
        FIND_LYRIC_SOURCES.map { source ->
            async {
                gate.withPermit {
                    withTimeoutOrNull(FIND_LYRIC_TIMEOUT_MS) { lyricSimilarity(heards, song, source) }
                }
            }
        }.awaitAll().filterNotNull().maxOrNull()
    }

    /** Seberapa mirip teks yang terdengar dengan lirik lagu (0..1), atau null kalau lirik tidak ada. */
    private suspend fun lyricSimilarity(heards: List<String>, song: SongItem, source: LyricsSource): Double? {
        val state = container.lyrics.load(song, source)
        val ready = state as? LyricsState.Ready ?: return null
        val lyricTokens = tokenize(ready.lines.joinToString(" ") { it.text })
        if (lyricTokens.isEmpty()) return null
        val set = lyricTokens.toHashSet()
        val bigrams = lyricTokens.zipWithNext { a, b -> "$a $b" }.toHashSet()
        return heards.maxOf { h ->
            val ht = tokenize(h)
            if (ht.isEmpty()) return@maxOf 0.0
            val uni = ht.count { w -> w in set || (w.length >= 4 && set.any { near(it, w) }) }.toDouble() / ht.size
            if (ht.size < 2) uni
            else {
                val bi = ht.zipWithNext { a, b -> "$a $b" }
                val biCov = bi.count { it in bigrams }.toDouble() / bi.size
                0.6 * biCov + 0.4 * uni
            }
        }
    }

    private fun tokenize(x: String): List<String> =
        x.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Kata dianggap sama kalau beda paling banyak satu huruf (salah dengar kecil). */
    private fun near(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1 || a.length < 4) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    // ------------------------------------------------ Tanya AI
    /** Pesan obrolan Tanya AI. fromUser=false = jawaban AI. */
    data class ChatMessage(val fromUser: Boolean, val text: String)

    val aiPrefs get() = container.aiPrefs
    /** Riwayat percakapan Tanya AI (terbaru di atas). */
    val bedahHistory: StateFlow<List<app.tunes.data.BedahEntry>> get() = container.bedahHistory.entries

    /** Hasil pencarian lagu yang disebut AI di jawabannya (kunci = [app.tunes.ai.SongRef.key]). */
    sealed interface AiSongState {
        data object Loading : AiSongState
        data class Found(val song: SongItem) : AiSongState
        data object Missing : AiSongState
    }

    private val _aiSongs = MutableStateFlow<Map<String, AiSongState>>(emptyMap())
    val aiSongs: StateFlow<Map<String, AiSongState>> = _aiSongs.asStateFlow()

    /** Cari lagu yang disebut AI di YouTube Music. Aman dipanggil berulang: yang sudah ketemu / sedang dicari dilewati. */
    fun resolveAiSong(ref: app.tunes.ai.SongRef) {
        val key = ref.key
        val cur = _aiSongs.value[key]
        if (cur is AiSongState.Found || cur is AiSongState.Loading) return
        _aiSongs.update { it + (key to AiSongState.Loading) }
        viewModelScope.launch {
            val state: AiSongState = try {
                val results = innerTube.search(ref.title + " " + ref.artist)
                val t = ref.title.trim().lowercase()
                val best = results.firstOrNull { it.title.lowercase().contains(t) } ?: results.firstOrNull()
                if (best != null) AiSongState.Found(best) else AiSongState.Missing
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AiSongState.Missing
            }
            _aiSongs.update { it + (key to state) }
        }
    }

    /** Hasil pencarian artis / album / playlist yang disebut AI (kunci = [app.tunes.ai.EntityRef.key]). */
    sealed interface AiEntityState {
        data object Loading : AiEntityState
        data class Found(val ref: CollectionRef) : AiEntityState
        data object Missing : AiEntityState
    }

    private val _aiEntities = MutableStateFlow<Map<String, AiEntityState>>(emptyMap())
    val aiEntities: StateFlow<Map<String, AiEntityState>> = _aiEntities.asStateFlow()

    /**
     * Cari artis / album / playlist yang disebut AI di YouTube Music supaya kartunya bisa dibuka ke halaman aslinya.
     * Artis dan playlist boleh jatuh ke hasil pertama sejenis; album harus mirip judulnya (album salah lebih buruk daripada kosong).
     */
    fun resolveAiEntity(ref: app.tunes.ai.EntityRef) {
        val key = ref.key
        val cur = _aiEntities.value[key]
        if (cur is AiEntityState.Found || cur is AiEntityState.Loading) return
        _aiEntities.update { it + (key to AiEntityState.Loading) }
        viewModelScope.launch {
            val state: AiEntityState = try {
                val q = if (ref.kind == CollectionKind.ARTIST) ref.title else (ref.title + " " + ref.by).trim()
                val res = innerTube.searchAll(q)
                val pool = (res.top.filterIsInstance<app.tunes.innertube.SearchHit.Entity>().map { it.ref } + res.artists + res.albums + res.playlists)
                    .filter { it.kind == ref.kind }
                    .distinctBy { it.key }
                val t = ref.title.trim().lowercase()
                val byName = pool.firstOrNull { val n = it.title.trim().lowercase(); n.contains(t) || t.contains(n) }
                val best = byName ?: if (ref.kind == CollectionKind.ALBUM) null else pool.firstOrNull()
                if (best != null) AiEntityState.Found(best) else AiEntityState.Missing
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AiEntityState.Missing
            }
            _aiEntities.update { it + (key to state) }
        }
    }

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()
    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy.asStateFlow()
    private val _chatError = MutableStateFlow<app.tunes.ai.AiErrorKind?>(null)
    val chatError: StateFlow<app.tunes.ai.AiErrorKind?> = _chatError.asStateFlow()
    /** ID percakapan yang sedang dibuka; null = obrolan baru yang belum ada pesan. */
    private val _convoId = MutableStateFlow<String?>(null)
    val convoId: StateFlow<String?> = _convoId.asStateFlow()
    private var chatJob: Job? = null
    /** Penanda jawaban terbaru: job lama yang dibatalkan tidak boleh mematikan status sibuk jawaban yang lebih baru. */
    private var answerGen = 0

    /** Permintaan dari luar halaman (menu lirik di layar pemutar): OlyunoRoot membuka halaman, lalu lagunya ditanyakan. */
    private val _lyricsAnalysisRequest = MutableStateFlow<SongItem?>(null)
    val lyricsAnalysisRequest: StateFlow<SongItem?> = _lyricsAnalysisRequest.asStateFlow()
    fun requestLyricsAnalysis(song: SongItem) { _lyricsAnalysisRequest.value = song }
    fun consumeLyricsAnalysisRequest() { _lyricsAnalysisRequest.value = null }

    private fun answerLang(): String =
        if (app.tunes.i18n.I18n.effective == app.tunes.i18n.AppLanguage.English) "English" else "Bahasa Indonesia"

    /** Obrolan baru dengan pertanyaan pembuka tentang [song] (dari menu lirik di layar pemutar). */
    fun askAboutPlayingSong(song: SongItem) {
        newChat()
        sendMessage(
            if (app.tunes.i18n.I18n.effective == app.tunes.i18n.AppLanguage.English) {
                "Explain the meaning of \"${song.title}\" by ${song.artist}."
            } else {
                "Jelaskan makna lagu \"${song.title}\" oleh ${song.artist}."
            },
        )
    }

    /** Kirim pertanyaan; percakapan dibuat otomatis pada pesan pertama. */
    fun sendMessage(text: String) {
        val q = text.trim()
        if (q.isEmpty() || _chatBusy.value) return
        if (_convoId.value == null) _convoId.value = java.util.UUID.randomUUID().toString()
        _chat.value = _chat.value + ChatMessage(true, q)
        saveConversation()
        answer()
    }

    /** Ulangi jawaban untuk pertanyaan terakhir yang gagal (mis. setelah kunci API diisi). */
    fun retryAnswer() {
        if (_chatBusy.value || _chat.value.lastOrNull()?.fromUser != true) return
        answer()
    }

    private fun answer() {
        val all = _chat.value
        val q = all.lastOrNull()?.takeIf { it.fromUser }?.text ?: return
        val history = all.dropLast(1).map { it.fromUser to it.text }
        val model = aiPrefs.model.value
        _chatError.value = null
        _chatBusy.value = true
        chatJob?.cancel()
        val gen = ++answerGen
        chatJob = viewModelScope.launch {
            // Jawaban dialirkan: pesan AI dibuat saat potongan pertama tiba, lalu teksnya diperbarui terus.
            // Sebelum itu (menunggu), UI menampilkan titik animasi.
            var streaming = false
            fun dropPartial() {
                if (streaming) {
                    val cur = _chat.value
                    if (cur.lastOrNull()?.fromUser == false) _chat.value = cur.dropLast(1)
                    streaming = false
                }
            }
            try {
                val reply = container.lyricsAnalyzer.chatStream(
                    aiPrefs.apiKey.value, aiPrefs.idOf(model), history, q, answerLang(),
                ) { partial ->
                    if (!isActive) return@chatStream // dibatalkan (obrolan diganti): jangan tulis ke obrolan lain
                    val cur = _chat.value
                    _chat.value = if (streaming && cur.lastOrNull()?.fromUser == false) {
                        cur.dropLast(1) + ChatMessage(false, partial)
                    } else {
                        streaming = true
                        cur + ChatMessage(false, partial)
                    }
                }
                val cur = _chat.value
                _chat.value = if (streaming && cur.lastOrNull()?.fromUser == false) {
                    cur.dropLast(1) + ChatMessage(false, reply)
                } else {
                    cur + ChatMessage(false, reply)
                }
                saveConversation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: app.tunes.ai.AiException) {
                // Galat di tengah jalan: buang jawaban setengah jadi supaya "Coba lagi" bisa mengulang pertanyaannya.
                dropPartial()
                _chatError.value = e.kind
            } catch (e: Exception) {
                dropPartial()
                _chatError.value = app.tunes.ai.AiErrorKind.Server
            } finally {
                if (gen == answerGen) _chatBusy.value = false
            }
        }
    }

    /**
     * Hentikan jawaban yang sedang diproses / dialirkan (tombol stop). Teks yang sudah tampil dipertahankan dan
     * disimpan; kalau belum ada potongan jawaban sama sekali, pertanyaan tetap ada dan bisa dikirim ulang.
     */
    fun stopAnswer() {
        if (!_chatBusy.value) return
        answerGen++
        chatJob?.cancel()
        _chatBusy.value = false
        saveConversation()
    }

    private fun saveConversation() {
        val id = _convoId.value ?: return
        val msgs = _chat.value
        val first = msgs.firstOrNull { it.fromUser }?.text?.replace('\n', ' ')?.trim().orEmpty()
        val entry = app.tunes.data.BedahEntry(
            id, first.take(80), "", 0, null,
            aiPrefs.model.value.name, System.currentTimeMillis(), msgs.map { it.fromUser to it.text },
        )
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { container.bedahHistory.upsert(entry) }
    }

    /** Mulai obrolan kosong. */
    fun newChat() {
        chatJob?.cancel()
        _convoId.value = null
        _chat.value = emptyList()
        _chatBusy.value = false
        _chatError.value = null
    }

    /** Buka percakapan dari riwayat. */
    fun openConversation(e: app.tunes.data.BedahEntry) {
        chatJob?.cancel()
        _convoId.value = e.videoId
        _chat.value = e.chat.map { ChatMessage(it.first, it.second) }
        _chatBusy.value = false
        _chatError.value = null
    }

    fun deleteBedahEntry(id: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { container.bedahHistory.remove(id) }
    }

    fun clearBedahHistory() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { container.bedahHistory.clear() }
    }

    fun clearFind() {
        findJob?.cancel()
        _findState.value = SearchState.Idle
    }
    fun clearSearchHistory() = container.searchHistory.clear()

    // ---- Penyimpanan (halaman Pengaturan > Penyimpanan)
    val storage get() = container.storage
    val audioCacheLimitMb: StateFlow<Int> = container.storagePrefs.audioCacheLimitMb
    val downloadCollections = container.downloads.collections

    // ------------------------------------------------------- Pilihan cepat
    private val _quickPicks = MutableStateFlow<List<SongItem>?>(null)

    /** null = sedang memuat; kosong = gagal / tidak ada. */
    val quickPicks: StateFlow<List<SongItem>?> = _quickPicks.asStateFlow()

    private var quickJob: Job? = null

    fun loadQuickPicks(force: Boolean = false) {
        if (!force && !_quickPicks.value.isNullOrEmpty()) return
        quickJob?.cancel()
        quickJob = viewModelScope.launch {
            _quickPicks.value = null
            // Benih acak dari 5 lagu terfavorit supaya tiap dibuka agak berbeda.
            val seed = repo.mostPlayed.first().filter { !it.videoId.isLocalId() }.take(5).randomOrNull()?.videoId
            _quickPicks.value = try {
                innerTube.quickPicks(seed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // ------------------------------------------------------ Sedang trending
    private val _trending = MutableStateFlow<List<SongItem>?>(null)

    /** null = sedang memuat; kosong = gagal / tidak ada. */
    val trending: StateFlow<List<SongItem>?> = _trending.asStateFlow()

    private var trendingJob: Job? = null

    fun loadTrending(force: Boolean = false) {
        if (!force && !_trending.value.isNullOrEmpty()) return
        trendingJob?.cancel()
        trendingJob = viewModelScope.launch {
            _trending.value = null
            _trending.value = try {
                innerTube.trendingSongs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // --------------------------------------------------------------- Genre
    private val _genres = MutableStateFlow<List<CollectionRef>?>(null)

    /** null = sedang memuat. Selalu terisi setelahnya (cadangan lokal bila jaringan gagal). */
    val genres: StateFlow<List<CollectionRef>?> = _genres.asStateFlow()

    private var genresJob: Job? = null

    /** [force] = muat ulang dari awal (tampil kerangka dulu), urutan diacak lagi. */
    fun loadGenres(force: Boolean = false) {
        if (!force && _genres.value != null) return
        genresJob?.cancel()
        genresJob = viewModelScope.launch {
            if (force) _genres.value = null
            _genres.value = try {
                innerTube.genres().ifEmpty { fallbackGenres }.shuffled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallbackGenres.shuffled()
            }
        }
    }

    /** Tombol muat ulang di Beranda: semua bagian dimuat ulang (genre ikut diacak). */
    fun refreshHome() {
        loadQuickPicks(force = true)
        loadTrending(force = true)
        loadGenres(force = true)
        loadMixPlaylists()
        refreshStreak()
        refreshRecent()
    }

    /** Tombol muat ulang di Cari: hasil pencarian yang sedang tampil dicari ulang; kalau tidak ada, riwayat akun dimuat ulang. */
    fun refreshSearch() {
        val q = _query.value.trim()
        if (q.isNotEmpty() && _search.value !is SearchState.Idle) runSearch(q) else refreshRecent()
    }

    // ------------------------------------------------------- lagu perangkat
    private val _deviceSongs = MutableStateFlow<DeviceSongsState>(DeviceSongsState.Loading)
    val deviceSongs: StateFlow<DeviceSongsState> = _deviceSongs.asStateFlow()
    private var deviceJob: Job? = null

    /** Muat ulang daftar lagu di perangkat (dipanggil setelah izin diberikan / halaman dibuka). */
    fun loadDeviceSongs() {
        deviceJob?.cancel()
        deviceJob = viewModelScope.launch {
            try {
                _deviceSongs.value = DeviceSongsState.Loaded(container.localSongs.load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Daftar lama (kalau ada) tetap dipakai.
                if (_deviceSongs.value !is DeviceSongsState.Loaded) _deviceSongs.value = DeviceSongsState.Error
            }
        }
    }

    // ------------------------------------------- halaman koleksi (genre dll)
    private val _openCollection = MutableStateFlow<CollectionRef?>(null)
    val openCollection: StateFlow<CollectionRef?> = _openCollection.asStateFlow()

    private val _collection = MutableStateFlow<CollectionState>(CollectionState.Loading)
    val collection: StateFlow<CollectionState> = _collection.asStateFlow()

    private val collectionCache = HashMap<String, CollectionData>()
    private var collectionJob: Job? = null

    /** Kunci paket unduhan yang sedang dibuka dari halaman Diunduh (null = koleksi online biasa). */
    private var downloadedKey: String? = null

    /** Halaman genre cepat yang jadi asal playlist yang sedang dibuka, supaya Kembali pulang ke genre itu. */
    private var moodParent: CollectionRef? = null

    fun openCollection(ref: CollectionRef) {
        val current = _openCollection.value
        moodParent = if (current?.kind == CollectionKind.MOOD && ref.kind != CollectionKind.MOOD) current else null
        downloadedKey = null
        _openCollection.value = ref
        loadCollection(ref)
    }

    /**
     * Buka halaman artis dari namanya saja (mis. kartu "Andalanmu" / Statistik). Halaman langsung terbuka dengan
     * [thumbnail] sementara, lalu halaman artis aslinya dicari dan dimuat (foto dan lagu-lagu artis itu).
     */
    fun openArtist(name: String, thumbnail: String? = null) {
        openCollection(CollectionRef(CollectionKind.ARTIST, name, thumbnail = thumbnail))
    }

    /** Buka paket playlist/album yang sudah diunduh: tampilan sama, isinya dari penyimpanan (tanpa internet). */
    fun openDownloadedCollection(dc: DownloadedCollection) {
        collectionJob?.cancel()
        downloadedKey = dc.ref.key
        _openCollection.value = dc.ref
        _collection.value = downloadedState(dc)
    }

    /** Hanya lagu yang sudah selesai diunduh, urutan playlist asli dipertahankan. */
    private fun downloadedState(dc: DownloadedCollection): CollectionState {
        val done = container.downloads.entries.value.filter { it.done }.map { it.song.videoId }.toSet()
        val songs = dc.songs.filter { it.videoId in done }
        return if (songs.isEmpty()) CollectionState.Error(t("Belum ada lagu yang selesai diunduh"))
        else CollectionState.Loaded(songs, dc.info)
    }

    private fun refreshDownloadedCollection() {
        val key = downloadedKey ?: return
        val dc = container.downloads.collections.value.firstOrNull { it.ref.key == key }
        if (dc == null) {
            // Paket dihapus saat halamannya terbuka.
            closeCollection()
            return
        }
        _collection.value = downloadedState(dc)
    }

    fun retryCollection() {
        val key = downloadedKey
        if (key != null) {
            refreshDownloadedCollection()
            return
        }
        _openCollection.value?.let { loadCollection(it) }
    }

    private fun loadCollection(ref: CollectionRef) {
        collectionJob?.cancel()
        collectionCache[ref.key]?.let {
            _collection.value = CollectionState.Loaded(it.songs, it.info, it.playlists)
            return
        }
        _collection.value = CollectionState.Loading
        collectionJob = viewModelScope.launch {
            _collection.value = try {
                val data = if (ref.kind == CollectionKind.MIX) {
                    val songs = container.sync.fetchMixSongs(ref.browseId.orEmpty()).orEmpty()
                    app.tunes.innertube.CollectionData(songs, CollectionInfo(thumbnail = ref.thumbnail))
                } else {
                    innerTube.loadCollection(ref, container.account.cookie())
                }
                if (data.songs.isEmpty()) {
                    CollectionState.Error(t("Tidak ada lagu di sini"))
                } else {
                    // Mix pribadi berubah-ubah, jadi tidak disimpan: dibuka lagi = isi terbaru dari akun.
                    if (ref.kind != CollectionKind.MIX) collectionCache[ref.key] = data
                    CollectionState.Loaded(data.songs, data.info, data.playlists)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CollectionState.Error(e.userMessage("Gagal memuat"))
            }
        }
    }

    fun closeCollection() {
        collectionJob?.cancel()
        downloadedKey = null
        val parent = moodParent
        moodParent = null
        if (parent != null) {
            // Dari playlist di halaman genre: kembali ke genre itu (isinya sudah tersimpan di cache).
            _openCollection.value = parent
            loadCollection(parent)
            return
        }
        _openCollection.value = null
    }

    // --------------------------------------------------------------- library
    val liked: StateFlow<List<SongItem>> =
        repo.liked.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<SongItem>> =
        repo.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lagu yang paling sering diputar (urut dari jumlah putar terbanyak). */
    val mostPlayed: StateFlow<List<SongItem>> =
        repo.mostPlayed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentLiked: StateFlow<Boolean> = player
        .map { it.current?.videoId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(false) else repo.isLiked(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun isLiked(videoId: String): kotlinx.coroutines.flow.Flow<Boolean> = repo.isLiked(videoId)

    fun clearHistory() {
        viewModelScope.launch { repo.clearHistory() }
    }

    /** Hapus satu lagu dari "Terakhir diputar" (tetap tersimpan kalau sudah disukai). */
    fun removeFromHistory(song: SongItem) {
        viewModelScope.launch { repo.removeFromHistory(song.videoId) }
    }

    fun toggleLike(song: SongItem) {
        viewModelScope.launch {
            val nowLiked = repo.toggleLike(song)
            // Sudah masuk: suka ikut tersimpan di akun YouTube Music (tanpa login tidak melakukan apa-apa).
            try {
                container.sync.pushLike(song.videoId, nowLiked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportSyncError(e, t("Belum tersimpan ke YouTube Music, akan dicoba lagi"))
            }
        }
    }

    /** Artis yang paling sering diputar (seluruh waktu), untuk bagian "Andalanmu" di Beranda. */
    val topArtists: StateFlow<List<StatArtist>> =
        repo.plays(0L).map { rows -> rows.toStats(12).artists }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Log putaran sejak [from] (ms), terbaru di depan, untuk halaman Statistik. */
    fun playRows(from: Long) = repo.plays(from)

    /** Riwayat lengkap beserta waktu putarnya (untuk halaman Riwayat lagu). */
    val historyEntries: StateFlow<List<HistoryEntry>> =
        repo.historyEntries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ----------------------------------------------------------------- akun
    /** null = belum masuk. */
    val account: StateFlow<AccountInfo?> = container.account.account

    /** Dipanggil setelah login di WebView selesai; [done] menerima true kalau akun berhasil dibaca. */
    fun completeLogin(cookie: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val info = try {
                innerTube.accountInfo(cookie)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (info != null) {
                container.account.save(cookie, info)
                autoSyncNow()
            }
            done(info != null)
        }
    }

    /** Pesan singkat untuk Snackbar (mis. sesi habis). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Playlist akun untuk "Mix untukmu" di Beranda; kosong = belum masuk / belum dimuat. */
    private val _mixPlaylists = MutableStateFlow<List<MixPlaylist>>(emptyList())
    val mixPlaylists: StateFlow<List<MixPlaylist>> = _mixPlaylists.asStateFlow()

    /** Status pemuatan "Mix untukmu": dipakai Beranda untuk kerangka (Loading) dan tombol coba lagi (Error). */
    private val _mixStatus = MutableStateFlow(MixStatus.Idle)
    val mixStatus: StateFlow<MixStatus> = _mixStatus.asStateFlow()

    /** Pemilik rak "Mix" di beranda YouTube Music (nama + foto akun); null = belum terbaca, pakai data akun biasa. */
    private val _mixOwner = MutableStateFlow<AccountInfo?>(null)
    val mixOwner: StateFlow<AccountInfo?> = _mixOwner.asStateFlow()

    private var mixJob: Job? = null
    private var mixLoadedAt = 0L

    // ------------------------------------------------- halaman "Akun" (perpustakaan akun)
    /** Isi satu tab halaman Akun. [items] null = belum pernah dimuat. */
    data class AccountLibState(val items: List<CollectionRef>? = null, val loading: Boolean = false, val error: Boolean = false)

    private val _accountLib = MutableStateFlow<Map<AccountTab, AccountLibState>>(emptyMap())
    val accountLib: StateFlow<Map<AccountTab, AccountLibState>> = _accountLib.asStateFlow()
    private val accountLibJobs = HashMap<AccountTab, Job>()

    /** Muat satu tab halaman Akun dari YouTube Music. Tanpa [force], tab yang sudah termuat tidak diambil lagi. */
    fun loadAccountTab(tab: AccountTab, force: Boolean = false) {
        if (container.account.cookie() == null) return
        val cur = _accountLib.value[tab]
        if (cur?.loading == true) return
        if (!force && cur?.items != null && !cur.error) return
        accountLibJobs[tab]?.cancel()
        _accountLib.value = _accountLib.value + (tab to AccountLibState(items = cur?.items, loading = true))
        accountLibJobs[tab] = viewModelScope.launch {
            try {
                val list = container.sync.fetchAccountTab(tab)
                _accountLib.value = _accountLib.value + (tab to AccountLibState(items = list.orEmpty()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _accountLib.value = _accountLib.value + (tab to AccountLibState(items = cur?.items, error = true))
                if (e.isAuthError()) _messages.tryEmit(t("Sesi YouTube Music habis, masuk lagi lewat tab Anda"))
            }
        }
    }

    /**
     * Muat Mix & playlist akun dari YouTube Music. Daftar lama tetap tampil selama memuat atau kalau gagal.
     * Gagal (bukan masalah sesi) dicoba sekali lagi setelah 4 detik; sesi habis memberi tahu pengguna.
     */
    private fun loadMixPlaylists() {
        if (container.account.cookie() == null) return
        if (mixJob?.isActive == true) return
        mixJob = viewModelScope.launch {
            _mixStatus.value = MixStatus.Loading
            var error: Exception? = null
            for (attempt in 0..1) {
                try {
                    val feed = container.sync.fetchMixPlaylists()
                    if (feed != null) {
                        _mixPlaylists.value = feed.items
                        // Nama + foto akun persis dari header rak Mix di YouTube Music (kalau ada).
                        feed.owner?.let { _mixOwner.value = it }
                        mixLoadedAt = android.os.SystemClock.elapsedRealtime()
                    }
                    error = null
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e
                    if (e.isAuthError()) break
                    if (attempt == 0) delay(4_000)
                }
            }
            val err = error
            if (err == null) {
                _mixStatus.value = MixStatus.Ready
            } else {
                _mixStatus.value = MixStatus.Error
                if (err.isAuthError()) _messages.tryEmit(t("Sesi YouTube Music habis, masuk lagi lewat tab Anda"))
            }
        }
    }

    /** Dipanggil Beranda tiap kembali ke aplikasi: muat ulang kalau sudah > 5 menit, atau sebelumnya gagal. */
    fun refreshMix() {
        if (container.account.cookie() == null) return
        val stale = android.os.SystemClock.elapsedRealtime() - mixLoadedAt > 5 * 60_000L || mixLoadedAt == 0L
        if (stale || _mixStatus.value == MixStatus.Error) loadMixPlaylists()
    }

    /** Tombol "Coba lagi" di kartu Mix yang gagal dimuat. */
    fun retryMix() = loadMixPlaylists()

    private val _remoteHistory = MutableStateFlow<List<SongItem>?>(null)

    /** "Terakhir dilihat": riwayat akun YouTube Music kalau sudah masuk dan berhasil dimuat, selain itu riwayat lokal. */
    val recent: StateFlow<List<SongItem>> = combine(history, _remoteHistory, account) { local, remote, acc ->
        if (acc != null && !remote.isNullOrEmpty()) remote else local
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ------------------------------------------------------------- unduhan
    val downloads: StateFlow<List<DownloadEntry>> = container.downloads.entries
    val downloadProgress: StateFlow<Map<String, Float>> = container.downloads.progress
    val downloadFailed: StateFlow<Set<String>> = container.downloads.failed

    fun isDownloaded(videoId: String): kotlinx.coroutines.flow.Flow<Boolean> =
        container.downloads.entries
            .map { list -> list.any { it.done && it.song.videoId == videoId } }
            .distinctUntilChanged()

    fun download(song: SongItem) {
        if (downloadBlockedByDataSaver()) return
        container.downloads.enqueue(song)
        _messages.tryEmit(t("Mengunduh \"%s\"", song.title))
    }

    /** Unduh semua lagu di [songs] (yang sudah selesai dilewati); antreannya ditangani SongDownloads. */
    fun downloadAll(songs: List<SongItem>) {
        if (downloadBlockedByDataSaver()) return
        val done = container.downloads.entries.value.filter { it.done }.map { it.song.videoId }.toSet()
        val todo = songs.filter { it.videoId !in done }
        if (todo.isEmpty()) {
            _messages.tryEmit(t("Semua lagu sudah diunduh"))
            return
        }
        todo.forEach { container.downloads.enqueue(it) }
        _messages.tryEmit(t("Mengunduh %d lagu", todo.size))
    }

    /** Paket playlist / album yang diunduh. */
    val downloadedCollections: StateFlow<List<DownloadedCollection>> = container.downloads.collections

    /**
     * Unduh playlist / album sebagai satu paket (satu baris di halaman Diunduh). Jenis lain (artis, genre,
     * dsb.) tetap diunduh sebagai lagu-lagu biasa.
     */
    fun downloadCollection(ref: CollectionRef, info: CollectionInfo, songs: List<SongItem>) {
        if (downloadBlockedByDataSaver()) return
        if (ref.kind != CollectionKind.PLAYLIST && ref.kind != CollectionKind.ALBUM) {
            downloadAll(songs)
            return
        }
        val done = container.downloads.entries.value.filter { it.done }.map { it.song.videoId }.toSet()
        val remote = songs.filter { !it.isLocal }
        val existing = container.downloads.collections.value.firstOrNull { it.ref.key == ref.key }
        if (existing != null && remote.all { it.videoId in done }) {
            _messages.tryEmit(t("\"%s\" sudah diunduh", ref.title))
            return
        }
        container.downloads.enqueueCollection(ref, info, remote)
        _messages.tryEmit(t("Mengunduh \"%s\"", ref.title))
    }

    fun removeCollectionDownload(key: String) = container.downloads.removeCollection(key)

    fun retryCollectionDownload(key: String) = container.downloads.retryCollection(key)

    fun removeDownload(song: SongItem) = container.downloads.removeSong(song.videoId)

    // ------------------------------------------------ playlist lokal
    val localPlaylists: StateFlow<List<app.tunes.data.LocalPlaylist>> = container.playlists.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lagu yang sedang dipilih untuk dimasukkan ke playlist (null = pemilih tertutup). Pemilihnya digambar di OlyunoRoot. */
    private val _playlistPicker = MutableStateFlow<List<SongItem>?>(null)
    val playlistPicker: StateFlow<List<SongItem>?> = _playlistPicker.asStateFlow()

    fun openPlaylistPicker(songs: List<SongItem>) {
        if (songs.isNotEmpty()) _playlistPicker.value = songs
    }

    fun closePlaylistPicker() {
        _playlistPicker.value = null
    }

    fun createPlaylist(name: String, songs: List<SongItem> = emptyList()) {
        val n = name.trim().take(60)
        if (n.isEmpty()) return
        viewModelScope.launch {
            container.playlists.create(n, songs)
            _messages.tryEmit(t("Playlist \"%s\" dibuat", n))
        }
    }

    fun addToPlaylist(playlist: app.tunes.data.LocalPlaylist, songs: List<SongItem>) {
        viewModelScope.launch {
            val added = container.playlists.addSongs(playlist.id, songs)
            _messages.tryEmit(
                if (added > 0) t("Ditambahkan ke \"%s\"", playlist.name) else t("Sudah ada di \"%s\"", playlist.name),
            )
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { container.playlists.rename(id, name.trim().take(60)) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { container.playlists.delete(id) }
    }

    fun removeFromPlaylist(id: Long, videoId: String) {
        viewModelScope.launch { container.playlists.removeSong(id, videoId) }
    }

    fun moveInPlaylist(id: Long, videoId: String, delta: Int) {
        viewModelScope.launch { container.playlists.moveSong(id, videoId, delta) }
    }

    // ------------------------------------------------ cadangan (ekspor / impor)
    /** Tulis semua data lokal ke file pilihan pengguna. Hasilnya diumumkan lewat [messages]. */
    fun exportTo(uri: android.net.Uri, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            var ok = false
            val msg = try {
                val data = BackupData(repo.exportAll(), container.searchHistory.items.value, repo.exportPlays(), container.playlists.exportAll(), container.streak.toBackup())
                val json = Backup.toJson(data)
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw java.io.IOException(t("File tujuan tidak bisa dibuka"))
                    out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                ok = true
                t("Cadangan tersimpan: %d lagu, %d pencarian", data.songs.size, data.searches.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                t("Gagal mengekspor: %s", e.userMessage("kesalahan tidak dikenal"))
            }
            _messages.tryEmit(msg)
            onDone(ok)
        }
    }

    /** Gabungkan file cadangan ke data lokal. File yang tidak valid ditolak tanpa mengubah apa pun. */
    fun importFrom(uri: android.net.Uri) {
        viewModelScope.launch {
            val msg = try {
                val text = withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException(t("File tidak bisa dibuka"))
                    input.use { stream ->
                        // Baca bertahap dengan batas ukuran supaya file raksasa tidak menghabiskan memori.
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8 * 1024)
                        var total = 0L
                        while (true) {
                            val n = stream.read(chunk)
                            if (n < 0) break
                            total += n
                            if (total > Backup.MAX_FILE_BYTES) throw BackupFormatException(t("File cadangan terlalu besar"))
                            buf.write(chunk, 0, n)
                        }
                        String(buf.toByteArray(), Charsets.UTF_8)
                    }
                }
                val data = Backup.parse(text)
                repo.mergeImported(data.songs)
                repo.mergePlays(data.plays)
                container.playlists.mergeImported(data.playlists)
                container.searchHistory.mergeIn(data.searches)
                // Streak: hanya diterima kalau streak lokal hampir kosong (pemulihan), dan tanda tangannya cocok.
                if (data.streakRejected) {
                    _messages.tryEmit(t("Data streak tidak valid, dilewati."))
                } else {
                    data.streak?.let { b ->
                        if (container.streak.importBackup(b) == app.tunes.data.StreakImportResult.SkippedExisting) {
                            _messages.tryEmit(t("Streak lokal sudah berjalan, data streak di cadangan dilewati."))
                        }
                    }
                }
                // Sudah masuk: suka dari cadangan ikut dikirim ke akun (diantre, lalu disinkronkan).
                if (container.account.cookie() != null) {
                    data.songs.filter { it.liked }.forEach { container.account.setPending(it.videoId, true) }
                    autoSyncNow()
                }
                t("Berhasil: %d lagu, %d pencarian", data.songs.size, data.searches.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupFormatException) {
                t("Gagal mengimpor: %s", e.userMessage("file tidak bisa dibaca"))
            } catch (e: Exception) {
                t("Gagal mengimpor: file tidak bisa dibaca")
            }
            _messages.tryEmit(msg)
        }
    }

    private fun reportSyncError(e: Exception, fallback: String) {
        _messages.tryEmit(
            if (e.isAuthError()) t("Sesi YouTube Music habis, masuk lagi lewat tab Anda") else fallback,
        )
    }

    private var syncJob: Job? = null

    /** Sinkron Disukai + riwayat dengan akun. Tanpa login tidak melakukan apa-apa. */
    fun syncNow() {
        if (container.account.cookie() == null) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            try {
                container.sync.syncLikes()
                _remoteHistory.value = container.sync.fetchHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportSyncError(e, t("Gagal sinkron dengan YouTube Music"))
            }
            loadMixPlaylists()
        }
    }

    /** Sinkron otomatis (saat aplikasi dibuka, setelah masuk / impor): hanya kalau toggle-nya menyala. */
    private fun autoSyncNow() {
        if (container.account.autoSync.value) syncNow()
        else if (container.account.cookie() != null) loadMixPlaylists() // Mix tetap dimuat walau sinkron otomatis mati
    }

    /** Otomatis sinkronisasi dengan akun (toggle di Pengaturan). */
    val autoSync: StateFlow<Boolean> = container.account.autoSync

    fun setAutoSync(on: Boolean) {
        container.account.setAutoSync(on)
        if (on) {
            syncNow()
        } else {
            // Kembali ke riwayat lokal; riwayat akun baru dimuat lagi lewat sinkronisasi manual.
            syncJob?.cancel()
            _remoteHistory.value = null
        }
    }

    // ------------------------------------------------------------ tampilan
    val colorMode: StateFlow<app.tunes.data.ColorMode> = container.appearance.mode
    val customColor: StateFlow<Int> = container.appearance.custom
    val uiStyle: StateFlow<app.tunes.data.UiStyle> = container.appearance.style
    fun setUiStyle(style: app.tunes.data.UiStyle) = container.appearance.setStyle(style)
    val smoothAnim: StateFlow<Boolean> = container.appearance.smoothAnim

    /** Streak mendengarkan (lihat StreakStore). */
    val streak: StateFlow<app.tunes.data.StreakState> = container.streak.state
    fun refreshStreak() = container.streak.refresh()
    val bgThumbnail: StateFlow<Boolean> = container.appearance.bgThumbnail
    fun setBgThumbnail(enabled: Boolean) = container.appearance.setBgThumbnail(enabled)
    val playerBgColor: StateFlow<Int?> = container.appearance.playerBg
    fun setPlayerBgColor(argb: Int?, persist: Boolean) = container.appearance.setPlayerBg(argb, persist)
    val lyricsBg: StateFlow<Boolean> = container.appearance.lyricsBg
    fun setLyricsBg(enabled: Boolean) = container.appearance.setLyricsBg(enabled)
    val lyricsPanelCustom: StateFlow<Boolean> = container.appearance.lyricsPanelCustom
    fun setLyricsPanelCustom(custom: Boolean) = container.appearance.setLyricsPanelCustom(custom)
    val lyricsPanelColor: StateFlow<Int> = container.appearance.lyricsPanel
    fun setLyricsPanelColor(argb: Int, persist: Boolean) = container.appearance.setLyricsPanel(argb, persist)
    fun resetLyricsPanel() = container.appearance.resetLyricsPanel()
    val lyricsTextMode: StateFlow<app.tunes.data.ColorMode> = container.appearance.lyricsTextMode
    fun setLyricsTextMode(mode: app.tunes.data.ColorMode) = container.appearance.setLyricsTextMode(mode)
    val lyricsTextCustom: StateFlow<Int> = container.appearance.lyricsTextCustom
    fun setLyricsTextCustom(argb: Int, persist: Boolean) = container.appearance.setLyricsTextCustom(argb, persist)
    val highRefresh: StateFlow<Boolean> = container.appearance.highRefresh
    fun setHighRefresh(enabled: Boolean) = container.appearance.setHighRefresh(enabled)
    val shimmerLevel: StateFlow<app.tunes.data.ShimmerLevel> = container.appearance.shimmer
    fun setShimmerLevel(level: app.tunes.data.ShimmerLevel) = container.appearance.setShimmer(level)
    val appFont: StateFlow<app.tunes.data.AppFont> = container.appearance.font
    fun setAppFont(font: app.tunes.data.AppFont) = container.appearance.setFont(font)
    val fontWeight: StateFlow<Int> = container.appearance.weight
    fun setFontWeight(step: Int) = container.appearance.setWeight(step)
    fun resetFont() = container.appearance.resetFont()
    fun setSmoothAnim(enabled: Boolean) = container.appearance.setSmoothAnim(enabled)
    val batterySaver: StateFlow<Boolean> = container.appearance.batterySaver
    fun setBatterySaver(enabled: Boolean) = container.appearance.setBatterySaver(enabled)
    fun setColorMode(mode: app.tunes.data.ColorMode) = container.appearance.setMode(mode)
    fun setCustomColor(argb: Int, persist: Boolean) = container.appearance.setCustom(argb, persist)

    /** Muat ulang riwayat dari akun (dipanggil saat halaman Terakhir dilihat dibuka). */
    fun refreshRecent() {
        if (container.account.cookie() == null || !container.account.autoSync.value) return
        viewModelScope.launch {
            try {
                _remoteHistory.value = container.sync.fetchHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // tetap tampilkan yang ada
            }
        }
    }

    fun logout() {
        syncJob?.cancel()
        _remoteHistory.value = null
        mixJob?.cancel()
        _mixPlaylists.value = emptyList()
        _mixOwner.value = null
        collectionCache.clear()
        accountLibJobs.values.forEach { it.cancel() }
        accountLibJobs.clear()
        _accountLib.value = emptyMap()
        _mixStatus.value = MixStatus.Idle
        mixLoadedAt = 0L
        container.account.clear()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
    }

    // ------------------------------------------------------------ ekualiser
    val equalizer: StateFlow<EqState> = container.equalizer.state
    fun setEqEnabled(enabled: Boolean) = container.equalizer.setEnabled(enabled)
    fun setEqBand(index: Int, level: Int) = container.equalizer.setBand(index, level)
    fun resetEq() = container.equalizer.reset()
    fun applyEqPreset(preset: app.tunes.player.EqPreset) = container.equalizer.applyPreset(preset)
    fun setEqBass(strength: Int) = container.equalizer.setBass(strength)

    // ------------------------------------------------------------ informasi
    /** Id pesan informasi yang sudah dibuka pengguna. */
    val infoReadIds: StateFlow<Set<String>> = container.info.readIds

    fun markInfoRead(id: String) = container.info.markRead(id)

    // ---------------------------------------------------------------- lirik
    private val lyricsRetry = MutableStateFlow(0)

    /** Layanan lirik pilihan pengguna (Otomatis / LRCLIB / Better Lyrics / BiniLyrics). */
    val lyricsSource: StateFlow<LyricsSource> = container.lyricsSource.source

    fun setLyricsSource(source: LyricsSource) = container.lyricsSource.set(source)

    /**
     * Jenis lirik yang dimiliki layanan untuk lagu ini: None = tidak ada, Word / Line / Plain = per kata / per baris /
     * tidak sinkron, null = gagal dicek (mis. tanpa jaringan) atau instrumental.
     */
    suspend fun lyricsKind(song: SongItem, source: LyricsSource): app.tunes.lyrics.LyricsKind? =
        container.lyrics.load(song, source).kindOrNull()

    /** Lirik lagu yang sedang diputar; baru diunduh saat ada yang mengoleksi (layar lirik terbuka). */
    val lyrics: StateFlow<LyricsState> = combine(
        player.map { it.current }.distinctUntilChanged { a, b -> a?.videoId == b?.videoId },
        lyricsRetry,
        lyricsSource,
    ) { song, _, source -> song to source }
        .flatMapLatest { (song, source) ->
            if (song == null) {
                flowOf<LyricsState>(LyricsState.NotFound)
            } else {
                flow<LyricsState> {
                    val cached = container.lyrics.cached(song.videoId, source)
                    if (cached != null) {
                        emit(cached)
                    } else {
                        emit(LyricsState.Loading)
                        emit(container.lyrics.load(song, source))
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(60_000), LyricsState.Loading)

    fun retryLyrics() {
        lyricsRetry.value += 1
    }

    /** Muat ulang lirik lagu ini dari jaringan (cache dibuang; layar menampilkan skeleton dulu). */
    fun refreshLyrics() {
        val song = player.value.current ?: return
        val source = lyricsSource.value
        viewModelScope.launch(Dispatchers.IO) {
            container.lyrics.invalidate(song.videoId, source)
            lyricsRetry.value += 1
        }
    }

    // ------------------------------------------------ pengaturan & terjemahan lirik
    val lyricsFollowLine: StateFlow<Boolean> = container.lyricsPrefs.followLine
    val lyricsTranslate: StateFlow<Boolean> = container.lyricsPrefs.translate
    val lyricsTargetLang: StateFlow<String> = container.lyricsPrefs.target

    /** Putar otomatis: antrean streaming habis -> lanjut ke lagu serupa (radio). */
    val autoplay: StateFlow<Boolean> = container.playbackPrefs.autoplay
    fun setAutoplay(on: Boolean) = container.playbackPrefs.setAutoplay(on)

    /** Hemat data: kualitas audio Rendah, tanpa Putar otomatis & terjemahan lirik, unduh hanya lewat Wi-Fi. */
    val dataSaver: StateFlow<Boolean> = container.playbackPrefs.dataSaver
    fun setDataSaver(on: Boolean) = container.playbackPrefs.setDataSaver(on)

    /** true (dan memberi tahu pengguna) kalau Hemat data aktif dan perangkat memakai data seluler / berkuota. */
    private fun downloadBlockedByDataSaver(): Boolean {
        val saver = container.playbackPrefs.dataSaver.value
        if (!saver && !container.playbackPrefs.wifiOnlyDownloads.value) return false
        val metered = try {
            val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.isActiveNetworkMetered
        } catch (e: Exception) {
            false
        }
        if (metered) {
            _messages.tryEmit(
                if (saver) t("Hemat data aktif: unduhan hanya lewat Wi-Fi")
                else t("Unduh hanya lewat Wi-Fi aktif: sambungkan ke Wi-Fi dulu"),
            )
        }
        return metered
    }

    /** Lanjut otomatis saat headset tersambung lagi. */
    val resumeOnHeadset: StateFlow<Boolean> = container.playbackPrefs.resumeOnHeadset
    fun setResumeOnHeadset(on: Boolean) = container.playbackPrefs.setResumeOnHeadset(on)

    /** Kecepatan pemutaran (0.5x-2x) dan Jaga nada. */
    val playbackSpeed: StateFlow<Float> = container.playbackPrefs.speed
    fun setPlaybackSpeed(v: Float) = container.playbackPrefs.setSpeed(v)
    val keepPitch: StateFlow<Boolean> = container.playbackPrefs.keepPitch
    fun setKeepPitch(on: Boolean) = container.playbackPrefs.setKeepPitch(on)

    /** Timer tidur memudarkan volume sebelum jeda. */
    val sleepFade: StateFlow<Boolean> = container.playbackPrefs.sleepFade
    fun setSleepFade(on: Boolean) = container.playbackPrefs.setSleepFade(on)

    /** Unduh hanya lewat Wi-Fi, terpisah dari Hemat data. */
    val wifiOnlyDownloads: StateFlow<Boolean> = container.playbackPrefs.wifiOnlyDownloads
    fun setWifiOnlyDownloads(on: Boolean) = container.playbackPrefs.setWifiOnlyDownloads(on)

    /** Notifikasi unduhan gagal / selesai (halaman Notifikasi). */
    val notifyDownloadFailed: StateFlow<Boolean> = container.notificationPrefs.downloadFailed
    fun setNotifyDownloadFailed(on: Boolean) = container.notificationPrefs.setDownloadFailed(on)
    val notifyDownloadDone: StateFlow<Boolean> = container.notificationPrefs.downloadDone
    fun setNotifyDownloadDone(on: Boolean) = container.notificationPrefs.setDownloadDone(on)

    /** Pengingat streak: saklar dan waktunya (menit sejak 00:00). */
    val notifyStreak: StateFlow<Boolean> = container.notificationPrefs.streakReminder
    fun setNotifyStreak(on: Boolean) = container.notificationPrefs.setStreakReminder(on)
    val streakReminderMinute: StateFlow<Int> = container.notificationPrefs.reminderMinuteOfDay
    fun setStreakReminderTime(hour: Int, minute: Int) = container.notificationPrefs.setReminderTime(hour, minute)
    val notifyStreakLast: StateFlow<Boolean> = container.notificationPrefs.lastReminder
    fun setNotifyStreakLast(on: Boolean) = container.notificationPrefs.setLastReminder(on)
    val streakLastMinute: StateFlow<Int> = container.notificationPrefs.lastReminderMinuteOfDay
    fun setStreakLastTime(hour: Int, minute: Int) = container.notificationPrefs.setLastReminderTime(hour, minute)

    /** Lanjutkan terakhir diputar: antrean & posisi lagu dipulihkan saat aplikasi dibuka lagi. */
    val resumeLast: StateFlow<Boolean> = container.playbackPrefs.resumeLast
    fun setResumeLast(on: Boolean) = container.playbackPrefs.setResumeLast(on)

    /** Lewati bagian hening di dalam lagu (bawaan mati). */
    val skipSilence: StateFlow<Boolean> = container.playbackPrefs.skipSilence
    fun setSkipSilence(on: Boolean) = container.playbackPrefs.setSkipSilence(on)

    /** Normalisasi volume: lagu yang terlalu keras diturunkan supaya kerasnya seragam (bawaan nyala). */
    val normalizeVolume: StateFlow<Boolean> = container.playbackPrefs.normalize
    fun setNormalizeVolume(on: Boolean) = container.playbackPrefs.setNormalize(on)

    val keepScreenAwake: StateFlow<Boolean> = container.lyricsPrefs.keepAwake
    fun setKeepScreenAwake(on: Boolean) = container.lyricsPrefs.setKeepAwake(on)
    fun setLyricsFollowLine(on: Boolean) = container.lyricsPrefs.setFollowLine(on)

    // ---------------------------------------------------------- Dynamic Island
    val islandEnabled: StateFlow<Boolean> = container.islandPrefs.enabled
    fun setIslandEnabled(on: Boolean) = container.islandPrefs.setEnabled(on)
    val islandStyle: StateFlow<app.tunes.data.IslandStyle> = container.islandPrefs.style
    fun setIslandStyle(style: app.tunes.data.IslandStyle) = container.islandPrefs.setStyle(style)
    val islandColor: StateFlow<app.tunes.data.IslandColor> = container.islandPrefs.color
    fun setIslandColor(c: app.tunes.data.IslandColor) = container.islandPrefs.setColor(c)
    val islandCustomColor: StateFlow<Int> = container.islandPrefs.custom
    fun setIslandCustomColor(argb: Int, persist: Boolean) = container.islandPrefs.setCustom(argb, persist)
    val islandShape: StateFlow<app.tunes.data.IslandShape> = container.islandPrefs.shape
    fun setIslandShape(s: app.tunes.data.IslandShape) = container.islandPrefs.setShape(s)
    val islandCardWidth: StateFlow<Float> = container.islandPrefs.cardWidth
    fun setIslandCardWidth(v: Float, persist: Boolean) = container.islandPrefs.setCardWidth(v, persist)
    val islandTextScale: StateFlow<Float> = container.islandPrefs.textScale
    fun setIslandTextScale(v: Float, persist: Boolean) = container.islandPrefs.setTextScale(v, persist)
    val islandButtonStyle: StateFlow<app.tunes.data.IslandButtonStyle> = container.islandPrefs.buttonStyle
    fun setIslandButtonStyle(s: app.tunes.data.IslandButtonStyle) = container.islandPrefs.setButtonStyle(s)
    val islandShowButtons: StateFlow<Boolean> = container.islandPrefs.showButtons
    fun setIslandShowButtons(on: Boolean) = container.islandPrefs.setShowButtons(on)
    val islandShowArtist: StateFlow<Boolean> = container.islandPrefs.showArtist
    fun setIslandShowArtist(on: Boolean) = container.islandPrefs.setShowArtist(on)
    val islandShowTitle: StateFlow<Boolean> = container.islandPrefs.showTitle
    fun setIslandShowTitle(on: Boolean) = container.islandPrefs.setShowTitle(on)
    val islandOnlyWhenPlaying: StateFlow<Boolean> = container.islandPrefs.onlyWhenPlaying
    fun setIslandOnlyWhenPlaying(on: Boolean) = container.islandPrefs.setOnlyWhenPlaying(on)
    val islandPauseHideSec: StateFlow<Float> = container.islandPrefs.pauseHideSec
    fun setIslandPauseHideSec(v: Float, persist: Boolean) = container.islandPrefs.setPauseHideSec(v, persist)
    val islandCollapseSec: StateFlow<Float> = container.islandPrefs.collapseSec
    fun setIslandCollapseSec(v: Float, persist: Boolean) = container.islandPrefs.setCollapseSec(v, persist)
    val islandCorner: StateFlow<Float> = container.islandPrefs.corner
    fun setIslandCorner(v: Float, persist: Boolean) = container.islandPrefs.setCorner(v, persist)
    val islandOpacity: StateFlow<Float> = container.islandPrefs.opacity
    fun setIslandOpacity(v: Float, persist: Boolean) = container.islandPrefs.setOpacity(v, persist)

    // ------------------------------------------------------------ kualitas audio
    val audioQuality: StateFlow<app.tunes.data.AudioQuality> = container.playbackPrefs.quality
    fun setAudioQuality(q: app.tunes.data.AudioQuality) = container.playbackPrefs.setQuality(q)

    // ------------------------------------------------------------ timer tidur
    val sleepTimer: StateFlow<app.tunes.data.SleepTimerState> = container.sleepTimer.state
    fun setSleepTimerMinutes(minutes: Int) = container.sleepTimer.startMinutes(minutes)
    fun setSleepTimerUntilTrackEnd() = container.sleepTimer.startUntilTrackEnd()
    fun cancelSleepTimer() = container.sleepTimer.cancel()

    fun setLyricsTranslate(on: Boolean) = container.lyricsPrefs.setTranslate(on)
    fun setLyricsTargetLang(code: String) = container.lyricsPrefs.setTarget(code)

    private val translateRetry = MutableStateFlow(0)
    fun retryTranslation() {
        translateRetry.value += 1
    }

    val lyricsTranslation: StateFlow<TranslationState> = combine(
        lyrics, lyricsTranslate, lyricsTargetLang, translateRetry, dataSaver,
    ) { ly, on, lang, _, saver -> Triple(ly, on && !saver, lang) }
        .flatMapLatest { (ly, on, lang) ->
            if (!on || ly !is LyricsState.Ready) {
                flowOf<TranslationState>(TranslationState.Off)
            } else {
                flow<TranslationState> {
                    emit(TranslationState.Loading)
                    try {
                        emit(TranslationState.Done(container.translations.translate(ly.lines.map { it.text }, lang)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emit(TranslationState.Error)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(60_000), TranslationState.Off)

    /** Posisi putar terkini (ms), dipanggil dari UI di main thread. */
    fun positionNow(): Long = connection.positionNow()

    // -------------------------------------------------------------- playback
    /** Putar satu lagu, lalu sambung dengan "radio" YouTube Music sebagai antrean otomatis. */
    fun playSong(song: SongItem) {
        app.tunes.data.PlayContext.current = null
        connection.play(listOf(song), 0)
        if (song.isLocal) return
        viewModelScope.launch {
            val radio = try {
                innerTube.radio(song.videoId).filter { it.videoId != song.videoId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            // Hanya tambahkan kalau user belum pindah lagu / mengubah antrean.
            if (radio.isNotEmpty() && connection.currentMediaId() == song.videoId && connection.queueSize() == 1) {
                connection.append(radio)
            }
        }
    }

    /**
     * "Mulai radio" dari satu lagu: antrean diganti lagu serupa dari YouTube Music. Kalau lagunya sedang diputar,
     * ia tetap berbunyi tanpa mulai ulang dan hanya sisa antreannya yang diganti radio; kalau bukan, lagunya
     * diputar dari awal diikuti radio.
     */
    fun startRadio(song: SongItem) {
        if (song.isLocal) {
            _messages.tryEmit(t("Radio hanya tersedia untuk lagu YouTube Music"))
            return
        }
        viewModelScope.launch {
            _messages.tryEmit(t("Memulai radio dari \"%s\"", song.title))
            val radio = try {
                innerTube.radio(song.videoId).filter { it.videoId != song.videoId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(t("Gagal memulai radio: %s", e.userMessage("kesalahan tidak dikenal")))
                return@launch
            }
            if (radio.isEmpty()) {
                _messages.tryEmit(t("Radio tidak tersedia untuk lagu ini"))
                return@launch
            }
            if (connection.currentMediaId() == song.videoId) {
                connection.clearUpcoming()
                connection.append(radio)
            } else {
                app.tunes.data.PlayContext.current = null
                connection.play(listOf(song) + radio, 0)
            }
        }
    }

    /** Pintasan ikon "Putar Disukai" / "Acak Disukai": langsung memutar semua lagu yang disukai. */
    fun playLikedFromShortcut(shuffle: Boolean) {
        viewModelScope.launch {
            val songs = repo.liked.first()
            if (songs.isEmpty()) {
                _messages.tryEmit(t("Belum ada lagu yang disukai"))
                return@launch
            }
            app.tunes.data.PlayContext.current = null
            connection.play(if (shuffle) songs.shuffled() else songs, 0)
        }
    }

    /**
     * Tautan YouTube / YouTube Music yang dibuka di Olyuno (ketuk tautan atau menu Bagikan): lagu diputar dan
     * disambung radio, tautan playlist diputar dari awal.
     */
    fun openSharedLink(uri: android.net.Uri) {
        val videoId = app.tunes.data.SharedLinks.videoId(uri)
        val listId = app.tunes.data.SharedLinks.playlistId(uri)
        if (videoId == null && listId == null) {
            _messages.tryEmit(t("Tautan ini tidak bisa dibuka di Olyuno"))
            return
        }
        viewModelScope.launch {
            _messages.tryEmit(t("Membuka tautan…"))
            try {
                if (videoId != null) {
                    // Info lagu (judul, artis, sampul) diambil dari panel "berikutnya"; lagu asal ada di urutan pertama.
                    val seed = innerTube.radio(videoId).firstOrNull { it.videoId == videoId }
                    if (seed == null) _messages.tryEmit(t("Lagu dari tautan tidak ditemukan")) else playSong(seed)
                } else {
                    val songs = innerTube.playlistQueue(listId!!)
                    if (songs.isEmpty()) {
                        _messages.tryEmit(t("Playlist dari tautan tidak ditemukan"))
                    } else {
                        app.tunes.data.PlayContext.current = null
                        connection.play(songs, 0)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(t("Gagal membuka tautan: %s", e.userMessage("kesalahan tidak dikenal")))
            }
        }
    }

    fun playList(songs: List<SongItem>, index: Int) {
        // Putaran dari halaman album / playlist dicatat bersama koleksinya (untuk statistik album & playlist).
        app.tunes.data.PlayContext.current = _openCollection.value
            ?.takeIf { it.kind == CollectionKind.ALBUM || it.kind == CollectionKind.PLAYLIST }
        connection.play(songs, index)
    }
    fun playNext(song: SongItem) = connection.playNext(song)
    fun addToQueue(song: SongItem) = connection.append(listOf(song))
    fun addAllToQueue(songs: List<SongItem>) = connection.append(songs)
    fun moveQueueItem(from: Int, to: Int) = connection.moveQueueItem(from, to)
    fun moveQueueItemNext(index: Int) = connection.moveQueueItemNext(index)
    fun removeQueueItem(index: Int) = connection.removeQueueItem(index)
    fun clearUpcomingQueue() = connection.clearUpcoming()

    fun togglePlay() = connection.togglePlay()
    fun next() = connection.next()
    fun previous() = connection.previous()
    fun previousItem() = connection.previousItem()
    fun seekTo(ms: Long) = connection.seekTo(ms)
    fun skipTo(index: Int) = connection.skipTo(index)
    fun toggleShuffle() = connection.toggleShuffle()
    fun cycleRepeat() = connection.cycleRepeat()

    // Harus paling akhir: semua properti di atas sudah terinisialisasi.
    init {
        loadQuickPicks()
        loadTrending()
        loadGenres()
        autoSyncNow()
        container.downloadNotifier
        container.downloads.resumePending()
        // Halaman paket unduhan yang sedang terbuka ikut diperbarui saat lagunya selesai diunduh / dihapus.
        viewModelScope.launch {
            combine(container.downloads.entries, container.downloads.collections) { _, _ -> }
                .collect { refreshDownloadedCollection() }
        }
    }

    override fun onCleared() {
        connection.release()
        super.onCleared()
    }
}

/** Status pemuatan "Mix untukmu" di Beranda. */
enum class MixStatus { Idle, Loading, Ready, Error }
