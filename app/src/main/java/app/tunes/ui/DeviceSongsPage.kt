package app.tunes.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.tunes.DeviceSongsState
import app.tunes.MainViewModel
import app.tunes.player.PlayerState
import app.tunes.i18n.t

private val AudioPermission: String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, AudioPermission) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Halaman penuh "Lagu perangkat". Belum ada izin: tampil ajakan + tombol "Izinkan". Sudah diizinkan:
 * semua lagu di perangkat dimuat (kerangka shimmer selama memuat) dan bisa langsung diputar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSongsPage(
    vm: MainViewModel,
    state: DeviceSongsState,
    player: PlayerState,
    currentId: String?,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    PageBackHandler(onBack = onBack)
    val search = rememberListSearch()
    ListSearchBackHandler(search)
    val context = LocalContext.current
    val bars = rememberBarsCollapse(countUnconsumed = true)

    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    var askedBefore by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        askedBefore = true
    }
    // Kembali dari pengaturan aplikasi: cek ulang izinnya.
    LifecycleResumeEffect(Unit) {
        granted = hasAudioPermission(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(granted) { if (granted) vm.loadDeviceSongs() }

    // Sudah pernah ditolak dan sistem tidak mau menampilkan dialog lagi: arahkan ke pengaturan aplikasi.
    val activity = context.findActivity()
    val canAskAgain = !askedBefore ||
        (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, AudioPermission))

    PageSurface {
        MiniPlayerScaffold(
            topBar = {
                SearchableTopBar(
                    title = "Lagu perangkat",
                    onBack = onBack,
                    search = search,
                    canSearch = granted && state is DeviceSongsState.Loaded && state.songs.isNotEmpty(),
                )
            },
            bars = bars,
            vm = vm,
            player = player,
            onOpenPlayer = onOpenPlayer,
        ) {
            if (!granted) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        t("Izinkan akses lagu di perangkat"),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (!canAskAgain) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t("Izin ditolak. Ketuk tombol di bawah untuk membuka pengaturan aplikasi."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    // Kotak dengan sudut membulat sedang (bukan lancip, bukan pil).
                    Button(
                        onClick = {
                            if (canAskAgain) {
                                launcher.launch(AudioPermission)
                            } else {
                                try {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        ),
                                    )
                                } catch (e: Exception) {
                                    // tidak ada layar pengaturan: abaikan
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("Izinkan")
                    }
                }
            } else {
                when (state) {
                    DeviceSongsState.Loading -> SongListSkeleton()

                    DeviceSongsState.Error -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            t("Gagal memuat Lagu di Perangkat"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RetryTextButton(onClick = vm::loadDeviceSongs)
                    }

                    is DeviceSongsState.Loaded -> {
                        val songs = state.songs.filterBySearch(search.query)
                        if (state.songs.isEmpty()) {
                            CenterMessage(t("Tidak ada lagu di perangkat"))
                        } else if (songs.isEmpty()) {
                            CenterMessage(t("Tidak ada hasil"))
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = LocalTopInset.current, bottom = LocalBottomInset.current),
                            ) {
                                itemsIndexed(songs, key = { _, s -> s.videoId }) { index, song ->
                                    SongRow(
                                        modifier = smoothItem(),
                                        song = song,
                                        isCurrent = song.videoId == currentId,
                                        onClick = { vm.playList(songs, index) },
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
