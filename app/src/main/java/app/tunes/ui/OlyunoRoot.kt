package app.tunes.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tunes.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.tunes.i18n.t

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OlyunoRoot(
    openPlayerRequest: Boolean = false,
    onOpenPlayerConsumed: () -> Unit = {},
    openSearchRequest: Boolean = false,
    onOpenSearchConsumed: () -> Unit = {},
    vm: MainViewModel = viewModel(),
) {
    val player by vm.player.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val deviceSongs by vm.deviceSongs.collectAsStateWithLifecycle()
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val downloadFailed by vm.downloadFailed.collectAsStateWithLifecycle()
    val downloadedCollections by vm.downloadedCollections.collectAsStateWithLifecycle()
    val mostPlayed by vm.mostPlayed.collectAsStateWithLifecycle()
    val currentLiked by vm.currentLiked.collectAsStateWithLifecycle()
    val openCollection by vm.openCollection.collectAsStateWithLifecycle()
    val collectionState by vm.collection.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val autoSync by vm.autoSync.collectAsStateWithLifecycle()
    val historyEntries by vm.historyEntries.collectAsStateWithLifecycle()
    val eqState by vm.equalizer.collectAsStateWithLifecycle()
    // "Jaga layar tetap hidup" (menu lirik): layar tidak mati selama aplikasi terbuka.
    val keepAwake by vm.keepScreenAwake.collectAsStateWithLifecycle()
    val rootView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(keepAwake, rootView) {
        rootView.keepScreenOn = keepAwake
        onDispose { rootView.keepScreenOn = false }
    }
    OfflineToastEffect()
    val infoRead by vm.infoReadIds.collectAsStateWithLifecycle()
    val infoUnread = app.tunes.data.INFO_MESSAGES.count { it.id !in infoRead }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Halaman penuh dari tab Koleksi: -1 tidak ada, 0 Terakhir dilihat, 1 Disukai.
    var libraryPage by rememberSaveable { mutableIntStateOf(-1) }
    // libraryPage 4 = daftar Playlist saya, 5 = isi satu playlist (id-nya di sini).
    var localPlaylistId by rememberSaveable { mutableLongStateOf(-1L) }
    val localPlaylists by vm.localPlaylists.collectAsStateWithLifecycle()
    // Halaman penuh dari tab Anda: -1 tidak ada, 0 Ekualiser, 1 Riwayat lagu, 2 Informasi, 3 Pengaturan, 4 Tampilan, 11 Statistik, 14 Streak.
    var andaPage by rememberSaveable { mutableIntStateOf(-1) }
    // Halaman Tentang Olyuno (andaPage == 12) bisa dibuka dari tab Anda atau Pengaturan; ini halaman tujuan saat kembali (-1 atau 3).
    var aboutBackTo by rememberSaveable { mutableIntStateOf(-1) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    // Layar pemutar: progres 0..1 yang bisa ditarik dari pemutar mini mana pun (lihat PlayerSheet.kt).
    val sheetScope = rememberCoroutineScope()
    val playerSheet = remember { PlayerSheetState(sheetScope) }
    playerSheet.onSetOpen = { showPlayer = it }
    run {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
        // 1:1 dengan jari: layar pemutar naik / turun sejauh jari bergeser (jarak penuh = tinggi layar).
        playerSheet.rangePx = with(density) { screenH.dp.toPx() }
    }
    LaunchedEffect(showPlayer) { playerSheet.animateTo(showPlayer) }
    val snackbar = remember { SnackbarHostState() }
    val currentId = player.current?.videoId

    // Dari ketukan notifikasi: buka layar pemutar begitu lagu sudah terhubung.
    LaunchedEffect(openPlayerRequest, currentId) {
        if (openPlayerRequest && currentId != null) {
            showPlayer = true
            onOpenPlayerConsumed()
        }
    }
    // Pintasan ikon "Cari": tutup halaman yang terbuka, lalu ke tab Cari.
    LaunchedEffect(openSearchRequest) {
        if (openSearchRequest) {
            showPlayer = false
            libraryPage = -1
            andaPage = -1
            showLogin = false
            vm.closeCollection()
            tab = 1
            onOpenSearchConsumed()
        }
    }
    // Permintaan basi (tidak ada lagu) dibuang supaya tidak membuka pemutar belakangan.
    LaunchedEffect(openPlayerRequest) {
        if (openPlayerRequest) {
            delay(3_000)
            onOpenPlayerConsumed()
        }
    }

    // Cadangan: pemilih file bawaan Android (tanpa izin penyimpanan).
    var showLogoutConfirm by rememberSaveable { mutableStateOf(false) }
    var logoutAfterExport by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val thenLogout = logoutAfterExport
        logoutAfterExport = false
        if (uri != null) {
            // Keluar hanya setelah cadangan benar-benar tersimpan; kalau gagal, tetap masuk.
            vm.exportTo(uri) { ok -> if (ok && thenLogout) { vm.logout(); andaPage = -1 } }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importFrom(uri) }

    // Pesan dari sinkron akun (mis. sesi habis).
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    // Buka "Terakhir dilihat": muat ulang riwayat dari akun (kalau sudah masuk).
    LaunchedEffect(libraryPage) {
        if (libraryPage == 0) vm.refreshRecent()
    }

    // Tutup layar pemutar kalau tidak ada lagu lagi.
    LaunchedEffect(currentId) {
        if (currentId == null) showPlayer = false
    }

    // Error sesaat (saat auto-retry di service) diabaikan; baru tampil kalau bertahan 1,5 detik.
    LaunchedEffect(player.error) {
        val message = player.error
        if (message != null) {
            delay(1_500)
            snackbar.showSnackbar(message)
        }
    }

    // Gaya Kaca: isi halaman direkam supaya bilah di bawahnya bisa menampilkannya dengan blur.
    val backdrop = rememberGlassBackdrop()
    val barsCollapse = rememberBarsCollapse()
    // Pindah tab: bilah kembali membesar.
    LaunchedEffect(tab) { barsCollapse.expand() }
    val glass = LocalGlass.current.let { remember(it.enabled, backdrop) { GlassConfig(it.enabled, backdrop) } }

    // Tab dasar tertutup kalau ada halaman penuh di atasnya: bergeser tipis + meredup, dan tidak menerima sentuhan.
    val baseCovered = openCollection != null || libraryPage >= 0 || andaPage >= 0 || showLogin
    // Halaman penuh pertama baru dibuka dari tab dasar: pemutar mini di halaman itu meluncur turun dari posisi di atas bilah tab.
    val prevCovered = remember { booleanArrayOf(baseCovered) }
    val miniFromTabs = baseCovered && !prevCovered[0] && !barsCollapse.collapsed
    androidx.compose.runtime.SideEffect { prevCovered[0] = baseCovered }

    // Latar akar berwarna sama dengan latar halaman: celah di tepi saat halaman bergeser tidak menampakkan jendela.
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPlayerSheet provides playerSheet,
        LocalMiniFromTabs provides miniFromTabs,
    ) {
    Box(Modifier.fillMaxSize().pageBackdrop()) {
        // Landscape: sisi kiri/kanan menjauhi lekuk kamera & bilah navigasi sisi; di portrait tidak ada efeknya.
        Box(Modifier.fillMaxSize().safeHorizontalPadding()) {
        HighRefreshKeeper(enabled = vm.highRefresh.collectAsStateWithLifecycle().value)
        androidx.compose.runtime.CompositionLocalProvider(LocalGlass provides glass) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                // Bilah bawah melayang; mengecil jadi bulatan di kanan bawah saat isi di-scroll ke bawah.
                FloatingBars(
                    modifier = Modifier.navigationBarsPadding(),
                    pageOpen = baseCovered,
                    player = player,
                    collapsed = barsCollapse.collapsed,
                    selectedTab = tab,
                    tabs = listOf(
                        Icons.Rounded.Home to t("Beranda"),
                        Icons.Rounded.Search to t("Cari"),
                        Icons.Rounded.LibraryMusic to t("Koleksi"),
                        Icons.Rounded.Person to t("Anda"),
                    ),
                    infoUnread = infoUnread,
                    onTab = { tab = it },
                    onExpand = barsCollapse::expand,
                    onOpenPlayer = { showPlayer = true },
                    onToggle = vm::togglePlay,
                    onNext = vm::next,
                    onPrevious = vm::previousItem,
                )
            },
        ) { padding ->
            // Isi menjangkau sampai ke belakang mini player & bilah navigasi (latar di situ transparan);
            // ruang bawahnya diberikan lewat LocalBottomInset supaya ujung daftar tetap bisa terbaca.
            // Isi tab memanjang sampai ke belakang status bar (tanpa padding atas) supaya blur atas-layar
            // menampakkan isi yang digulir; ruang atasnya diberikan lewat LocalTabTopInset.
            Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .nestedScroll(barsCollapse)
                    .then(if (glass.enabled || topBlurSupported()) Modifier.glassSource(backdrop) else Modifier),
            ) {
              androidx.compose.runtime.CompositionLocalProvider(
                LocalBottomInset provides padding.calculateBottomPadding(),
                LocalTabTopInset provides padding.calculateTopPadding(),
              ) {
                TabEnter(tab) {
                when (tab) {
                    0 -> HomeScreen(vm, mostPlayed, currentId, onOpenLiked = { libraryPage = 1 }, onOpenLibrary = { tab = 2 }, onOpenStreak = { andaPage = 14 })
                    1 -> SearchScreen(vm, currentId)
                    2 -> CollectionHomeScreen(
                        onOpenRecent = { libraryPage = 0 },
                        onOpenLiked = { libraryPage = 1 },
                        onOpenDownloads = { libraryPage = 2 },
                        onOpenDeviceSongs = { libraryPage = 3 },
                        onOpenPlaylists = { libraryPage = 4 },
                    )
                    else -> AndaScreen(
                        account = account,
                        onLogin = { showLogin = true },
                        onLogout = { showLogoutConfirm = true },
                        onExport = {
                            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                            exportLauncher.launch("olyuno-cadangan-$day.json")
                        },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onOpenEqualizer = { andaPage = 0 },
                        onOpenHistory = { andaPage = 1 },
                        onOpenStats = { andaPage = 11 },
                        onOpenStreak = { andaPage = 14 },
                        onOpenInfo = { andaPage = 2 },
                        onOpenSettings = { andaPage = 3 },
                        onOpenAbout = {
                            aboutBackTo = -1
                            andaPage = 12
                        },
                        infoUnread = infoUnread,
                    )
                }
                }
              }
            }
            TopScreenBlur(backdrop = backdrop, modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter))
            // Tombol muat ulang di atas kanan pemutar mini (hanya Beranda & Cari): turun hilang saat scroll ke bawah, naik lagi saat scroll ke atas.
            RefreshFab(
                visible = (tab == 0 || tab == 1) && !barsCollapse.collapsed && !baseCovered,
                bottomInset = padding.calculateBottomPadding(),
                onClick = { if (tab == 0) vm.refreshHome() else vm.refreshSearch() },
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd),
            )
            }
        }
        // Penghalang sentuhan: selama halaman penuh terbuka, tab di belakangnya tidak bisa terketuk.
        if (baseCovered) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
        }
        }

        val heldLibraryPage = remember { mutableIntStateOf(0) }
        if (libraryPage >= 0) heldLibraryPage.intValue = libraryPage

        PageTransition(visible = libraryPage in 0..1) {
            SongListPage(
                title = if (heldLibraryPage.intValue == 0) t("Terakhir dilihat") else t("Disukai"),
                songs = if (heldLibraryPage.intValue == 0) recent else liked,
                emptyText = if (heldLibraryPage.intValue == 0) t("Belum ada riwayat putar") else t("Belum ada lagu yang disukai"),
                vm = vm,
                player = player,
                currentId = currentId,
                onBack = { libraryPage = -1 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = libraryPage == 2, covered = openCollection != null) {
            DownloadsPage(
                vm = vm,
                entries = downloads,
                collections = downloadedCollections,
                progress = downloadProgress,
                failed = downloadFailed,
                player = player,
                currentId = currentId,
                onBack = { libraryPage = -1 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = libraryPage == 4 || libraryPage == 5, covered = libraryPage == 5 || openCollection != null) {
            // Daftar tetap ada di belakang isi playlist, jadi "kembali" dari isi memperlihatkannya tanpa memuat ulang.
            LocalPlaylistsPage(
                vm = vm,
                playlists = localPlaylists,
                player = player,
                onBack = { libraryPage = -1 },
                onOpen = {
                    localPlaylistId = it
                    libraryPage = 5
                },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = libraryPage == 5, covered = openCollection != null) {
            LocalPlaylistDetailPage(
                vm = vm,
                playlist = localPlaylists.firstOrNull { it.id == localPlaylistId },
                player = player,
                currentId = currentId,
                onBack = { libraryPage = 4 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = libraryPage == 3) {
            DeviceSongsPage(
                vm = vm,
                state = deviceSongs,
                player = player,
                currentId = currentId,
                onBack = { libraryPage = -1 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage == 0) {
            EqualizerPage(
                vm = vm,
                state = eqState,
                player = player,
                onBack = { andaPage = -1 },
                onOpenPlayer = { showPlayer = true },
            )
        }
        PageTransition(visible = andaPage == 1) {
            SongHistoryPage(
                vm = vm,
                entries = historyEntries,
                player = player,
                onBack = { andaPage = -1 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        // Statistik: tertutup saat halaman album / playlist / artis dibuka dari daftarnya.
        PageTransition(visible = andaPage == 11, covered = openCollection != null) {
            StatisticsPage(
                vm = vm,
                player = player,
                currentId = currentId,
                onBack = { andaPage = -1 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        // Streak: dibuka dari kartu di Beranda atau menu di tab Anda.
        PageTransition(visible = andaPage == 14) {
            StreakPage(
                vm = vm,
                player = player,
                onBack = { andaPage = -1 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage == 2) {
            InfoPage(
                readIds = infoRead,
                onMarkRead = vm::markInfoRead,
                vm = vm,
                player = player,
                onBack = { andaPage = -1 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage in 3..10 || andaPage == 13 || (andaPage == 12 && aboutBackTo == 3), covered = andaPage >= 4) {
            SettingsPage(
                account = account,
                autoSync = autoSync,
                onAutoSyncChange = vm::setAutoSync,
                onLogout = { showLogoutConfirm = true },
                onOpenAppearance = { andaPage = 4 },
                onOpenLanguage = { andaPage = 6 },
                onOpenPlayback = { andaPage = 8 },
                onOpenLyrics = { andaPage = 7 },
                onOpenStorage = { andaPage = 10 },
                onOpenNotifications = { andaPage = 13 },
                onOpenAbout = {
                    aboutBackTo = 3
                    andaPage = 12
                },
                vm = vm,
                player = player,
                onBack = { andaPage = -1 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage in 4..5 || andaPage == 9, covered = andaPage == 5 || andaPage == 9) {
            AppearancePage(
                vm = vm,
                player = player,
                onBack = { andaPage = 3 },
                onOpenPlayer = { showPlayer = true },
                onOpenTheme = { andaPage = 5 },
                onOpenDynamicIsland = { andaPage = 9 },
            )
        }

        PageTransition(visible = andaPage == 9) {
            DynamicIslandPage(
                vm = vm,
                player = player,
                onBack = { andaPage = 4 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage == 6) {
            LanguagePage(
                vm = vm,
                player = player,
                onBack = { andaPage = 3 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage == 8) {
            PlaybackSettingsPage(
                vm = vm,
                player = player,
                onBack = { andaPage = 3 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage == 12) {
            AboutPage(
                vm = vm,
                player = player,
                onBack = { andaPage = aboutBackTo },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage == 10) {
            StoragePage(
                vm = vm,
                player = player,
                onBack = { andaPage = 3 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage == 13) {
            NotificationsPage(
                vm = vm,
                player = player,
                onBack = { andaPage = 3 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage == 7) {
            LyricsSettingsPage(
                vm = vm,
                player = player,
                onBack = { andaPage = 3 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        PageTransition(visible = andaPage == 5) {
            ThemePage(
                vm = vm,
                player = player,
                onBack = { andaPage = 4 },
                onOpenPlayer = { showPlayer = true },
            )
        }

        // Halaman penuh genre / artis / album / playlist; di atas halaman lain (termasuk Diunduh, tempat paket playlist dibuka) tetapi di bawah layar pemutar.
        // Isi terakhir ditahan supaya halaman tetap utuh selama animasi keluar.
        val heldCollection = remember { mutableStateOf(openCollection) }
        val heldCollectionState = remember { mutableStateOf(collectionState) }
        if (openCollection != null) {
            heldCollection.value = openCollection
            heldCollectionState.value = collectionState
        }
        PageTransition(visible = openCollection != null) {
            heldCollection.value?.let { ref ->
                if (ref.kind == app.tunes.innertube.CollectionKind.MOOD) {
                    MoodScreen(
                        vm = vm,
                        ref = ref,
                        state = heldCollectionState.value,
                        player = player,
                        currentId = currentId,
                        onOpenPlayer = { showPlayer = true },
                    )
                } else {
                    CollectionScreen(
                        vm = vm,
                        ref = ref,
                        state = heldCollectionState.value,
                        player = player,
                        currentId = currentId,
                        onOpenPlayer = { showPlayer = true },
                    )
                }
            }
        }

        // Pemilih "Tambah ke playlist" (dibuka dari menu lagu mana pun).
        PlaylistPickerHost(vm)

        if (showLogoutConfirm) {
            AlertDialog(
                onDismissRequest = { showLogoutConfirm = false },
                title = { Text(t("Keluar dari akun?")) },
                text = {
                    Text(
                        t("Setelah keluar, data yang belum disinkronkan bisa hilang dari perangkat ini. Simpan dulu data Olyuno kamu supaya aman."),
                        modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    )
                },
                confirmButton = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = {
                                showLogoutConfirm = false
                                logoutAfterExport = true
                                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                                exportLauncher.launch("olyuno-cadangan-$day.json")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(t("Ekspor data & keluar")) }
                        TextButton(
                            onClick = {
                                showLogoutConfirm = false
                                vm.logout()
                                andaPage = -1
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(t("Keluar tanpa ekspor")) }
                        TextButton(
                            onClick = { showLogoutConfirm = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(t("Batal")) }
                    }
                },
            )
        }

        PageTransition(visible = showLogin) {
            LoginScreen(
                onCookie = { cookie ->
                    vm.completeLogin(cookie) { ok ->
                        showLogin = false
                        android.widget.Toast.makeText(
                            context,
                            if (ok) t("Berhasil masuk") else t("Gagal masuk, coba lagi"),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onBack = { showLogin = false },
            )
        }

        }

        // Layar pemutar: sheet buatan sendiri yang progresnya (0..1) mengikuti jari. Ditarik naik dari pemutar mini
        // (dalam bentuk apa pun) atau diketuk: tampil utuh tapi transparan lalu makin jelas sampai biasa.
        // Ditarik turun dari mana saja / tombol Kembali menutupnya dengan efek berbalik; setelah tertutup
        // komposisinya dibuang, sehingga dibuka lagi selalu mulus.
        val sheetVisible by remember { androidx.compose.runtime.derivedStateOf { playerSheet.progress.value > 0.001f } }
        if (sheetVisible && player.current != null) {
            PlayerSheetHost(sheet = playerSheet, onDismiss = { showPlayer = false }) {
                NowPlayingScreen(
                    vm = vm,
                    state = player,
                    liked = currentLiked,
                    onClose = { showPlayer = false },
                )
            }
        }
    }
    }
}
