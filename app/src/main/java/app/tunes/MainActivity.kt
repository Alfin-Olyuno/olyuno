package app.tunes

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import app.tunes.ui.LaunchSplash
import app.tunes.ui.OlyunoRoot
import app.tunes.ui.theme.OlyunoTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* tidak ada aksi */ }

    /** ViewModel yang sama dengan yang dipakai OlyunoRoot (satu per aktivitas). */
    private val vm: MainViewModel by viewModels()

    /** True selama ada permintaan membuka layar pemutar (dari ketukan notifikasi). */
    private var openPlayerRequest by mutableStateOf(false)

    /** True selama ada permintaan membuka tab Cari (dari pintasan ikon). */
    private var openSearchRequest by mutableStateOf(false)

    /** Animasi logo saat aplikasi baru dibuka; tidak diulang setelah layar diputar / aktivitas dipulihkan. */
    private var showSplash by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showSplash = savedInstanceState == null
        openPlayerRequest = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true

        // Android 13+: tanpa izin ini notifikasi kontrol media tidak muncul.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Dibuka dari tautan YouTube / menu Bagikan. Tidak diulang saat aktivitas dipulihkan.
        if (savedInstanceState == null) {
            handleLink(intent)
            handleShortcut(intent)
        }

        setContent {
            OlyunoTheme {
                Box(Modifier.fillMaxSize()) {
                    OlyunoRoot(
                        openPlayerRequest = openPlayerRequest,
                        onOpenPlayerConsumed = { openPlayerRequest = false },
                        openSearchRequest = openSearchRequest,
                        onOpenSearchConsumed = { openSearchRequest = false },
                    )
                    if (showSplash) LaunchSplash(onFinished = { showSplash = false })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Bahasa perangkat bisa berubah saat aplikasi di latar; "Default sistem" harus ikut.
        app.tunes.i18n.I18n.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayerRequest = true
        handleLink(intent)
        handleShortcut(intent)
    }

    /** Pintasan tekan-lama di ikon aplikasi. */
    private fun handleShortcut(intent: Intent?) {
        when (intent?.action) {
            ACTION_PLAY_LIKED -> vm.playLikedFromShortcut(shuffle = false)
            ACTION_SHUFFLE_LIKED -> vm.playLikedFromShortcut(shuffle = true)
            ACTION_SEARCH -> openSearchRequest = true
            else -> return
        }
        setIntent(Intent(this, MainActivity::class.java))
    }

    /** Tautan lagu / playlist YouTube dari ACTION_VIEW atau teks ACTION_SEND, atau null. */
    private fun linkFrom(intent: Intent?): android.net.Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> app.tunes.data.SharedLinks.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        else -> null
    }

    private fun handleLink(intent: Intent?) {
        val uri = linkFrom(intent) ?: return
        vm.openSharedLink(uri)
        // Intent sudah dipakai: ganti yang kosong supaya tidak diproses lagi saat aktivitas kembali aktif.
        setIntent(Intent(this, MainActivity::class.java))
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "app.tunes.OPEN_PLAYER"
        const val ACTION_PLAY_LIKED = "app.tunes.action.PLAY_LIKED"
        const val ACTION_SHUFFLE_LIKED = "app.tunes.action.SHUFFLE_LIKED"
        const val ACTION_SEARCH = "app.tunes.action.SEARCH"
    }
}
