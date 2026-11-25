package app.tunes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import app.tunes.MainViewModel
import app.tunes.R
import app.tunes.i18n.t
import app.tunes.player.PlayerState
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Nama pembuat yang tampil di halaman Tentang Olyuno. */
private const val DEVELOPER = "PINZZ"

/** Info aplikasi & perangkat yang dibaca langsung dari sistem (bukan teks tetap). */
private data class AboutInfo(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val android: String,
    val device: String,
    val abi: String,
    val updated: String,
)

private fun readAboutInfo(context: Context): AboutInfo {
    val pi = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val model = Build.MODEL.orEmpty()
    val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    return AboutInfo(
        versionName = pi?.versionName ?: "-",
        versionCode = pi?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
        packageName = context.packageName,
        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim(),
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "-",
        updated = pi?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.lastUpdateTime)) } ?: "-",
    )
}

private fun copyText(context: Context, text: String, message: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Olyuno", text))
    // Android 13+ sudah menampilkan konfirmasi salin sendiri.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/**
 * Halaman Tentang Olyuno: logo + versi, pembuat (PINZZ), info teknis (versi, kode versi, paket, Android,
 * perangkat, arsitektur, terakhir diperbarui; ketuk satu baris untuk menyalin, atau salin semuanya).
 * Dibuka dari Pengaturan maupun tab Anda.
 */
@Composable
fun AboutPage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val context = LocalContext.current
    val info = remember { readAboutInfo(context) }
    SubPage(title = t("Tentang Olyuno"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            Hero(info.versionName)

            SectionTitle(t("Pembuat"))
            MenuGroup { DeveloperCard() }

            SectionTitle(t("Olyuno AI"))
            MenuGroup {
                Text(
                    t("Olyuno AI ditenagai Gemini dari Google. Pertanyaanmu, judul, artis, lirik yang dianalisis, dan hasil pencarian yang diminta Olyuno AI dikirim ke Google. Di paket gratis, isi permintaan dapat dipakai Google untuk meningkatkan produknya."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }

            SectionTitle(t("Info teknis"))
            MenuGroup {
                val copied = t("Disalin")
                InfoRow(Icons.Rounded.NewReleases, t("Versi"), info.versionName, copied)
                MenuDivider()
                InfoRow(Icons.Rounded.Apps, t("Kode versi"), info.versionCode.toString(), copied)
                MenuDivider()
                InfoRow(Icons.Rounded.Inventory2, t("Nama paket"), info.packageName, copied)
                MenuDivider()
                InfoRow(Icons.Rounded.Android, t("Versi Android"), info.android, copied)
                MenuDivider()
                InfoRow(Icons.Rounded.PhoneAndroid, t("Perangkat"), info.device, copied)
                MenuDivider()
                InfoRow(Icons.Rounded.Memory, t("Arsitektur"), info.abi, copied)
                MenuDivider()
                InfoRow(Icons.Rounded.Update, t("Terakhir diperbarui"), info.updated, copied)
                MenuDivider()
                MenuRow(
                    leading = { Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Salin semua info"),
                    description = t("Berguna saat melaporkan masalah."),
                    bold = true,
                    onClick = {
                        copyText(
                            context,
                            buildString {
                                appendLine("Olyuno ${info.versionName} (${info.versionCode})")
                                appendLine(info.packageName)
                                appendLine(info.android)
                                appendLine(info.device)
                                append(info.abi)
                            },
                            t("Info aplikasi disalin"),
                        )
                    },
                )
            }

            Footer()
        }
    }
}

/** Kepala halaman: logo Olyuno (tanpa latar), nama, semboyan singkat, dan label versi. */
@Composable
private fun Hero(versionName: String) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp, start = 24.dp, end = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Logo Olyuno saja (tanpa latar kotak): lingkaran "O" dengan bagian tengah gelap tetap utuh, sisi luarnya transparan.
        Image(
            painter = painterResource(R.drawable.ic_olyuno_logo),
            contentDescription = null,
            modifier = Modifier.size(104.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Olyuno", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        Surface(shape = CircleShape, color = scheme.primaryContainer) {
            Text(
                t("Versi %s", versionName),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun DeveloperCard() {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_pinzz_avatar),
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(DEVELOPER, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    t("Solo developer"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Baris info teknis: label, nilainya di bawah, ikon salin di kanan; diketuk = nilai disalin. */
@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, copiedMessage: String) {
    val context = LocalContext.current
    MenuRow(
        leading = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
        label = label,
        description = value,
        onClick = { copyText(context, value, copiedMessage) },
    ) {
        Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Footer() {
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            t("Dibuat oleh %s", DEVELOPER),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "© $year $DEVELOPER",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
