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

    /** True selama ada permintaan membuka layar pemutar (dari ketukan notifikasi). */
    private var openPlayerRequest by mutableStateOf(false)

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

        setContent {
            OlyunoTheme {
                Box(Modifier.fillMaxSize()) {
                    OlyunoRoot(
                        openPlayerRequest = openPlayerRequest,
                        onOpenPlayerConsumed = { openPlayerRequest = false },
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
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "app.tunes.OPEN_PLAYER"
    }
}
