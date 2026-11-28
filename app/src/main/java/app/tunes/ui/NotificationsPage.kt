package app.tunes.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.i18n.t
import app.tunes.player.PlayerState

private fun Context.findOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/** Buka pengaturan notifikasi aplikasi di Android (per channel: suara, getar, layar kunci). */
private fun openAppNotificationSettings(context: Context) {
    val attempts = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // coba cara berikutnya
        }
    }
}

/** Baris izin: lingkaran silang merah (belum) / centang hijau (sudah), seperti di halaman Dynamic Island. */
@Composable
private fun NotificationPermissionRow(granted: Boolean, onClick: () -> Unit) {
    val badge by animateColorAsState(
        if (granted) Color(0xFF2FB36B) else MaterialTheme.colorScheme.error,
        tween(250),
        label = "notifPermBadge",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(badge),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = granted, label = "notifPermIcon") { ok ->
                Icon(
                    if (ok) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(t("Izinkan notifikasi"), style = MaterialTheme.typography.titleMedium)
            Text(
                if (granted) t("Diizinkan") else t("Belum diizinkan"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Halaman Notifikasi: status izin (silang merah / centang hijau), saklar Unduhan gagal & Unduhan selesai,
 * keterangan notifikasi yang selalu tampil (progres unduhan, kontrol pemutar), dan pintasan ke pengaturan
 * notifikasi Android.
 */
@Composable
fun NotificationsPage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val context = LocalContext.current
    val failedOn by vm.notifyDownloadFailed.collectAsStateWithLifecycle()
    val doneOn by vm.notifyDownloadDone.collectAsStateWithLifecycle()
    val announceOn by vm.notifyAnnouncements.collectAsStateWithLifecycle()
    val streakOn by vm.notifyStreak.collectAsStateWithLifecycle()
    val reminderMin by vm.streakReminderMinute.collectAsStateWithLifecycle()
    val lastOn by vm.notifyStreakLast.collectAsStateWithLifecycle()
    val lastMin by vm.streakLastMinute.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }

    // Diperiksa ulang tiap kembali ke aplikasi (izin bisa diubah dari pengaturan sistem).
    val owner = context.findOwner()
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    var requestedAt by remember { mutableStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = NotificationManagerCompat.from(context).areNotificationsEnabled()
        // Dialog izin tidak muncul (sudah ditolak berkali-kali): hasilnya datang seketika -> buka pengaturan sistem.
        if (!ok && SystemClock.elapsedRealtime() - requestedAt < 400) openAppNotificationSettings(context)
    }

    fun onPermissionClick() {
        when {
            granted -> openAppNotificationSettings(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                requestedAt = SystemClock.elapsedRealtime()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> openAppNotificationSettings(context)
        }
    }

    SubPage(title = t("Notifikasi"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            SectionTitle(t("Notifikasi"))
            MenuGroup {
                NotificationPermissionRow(granted = granted, onClick = ::onPermissionClick)
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Pengumuman"))
            MenuGroup {
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.Mail, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Pengumuman baru"),
                    description = t("Diberi tahu saat ada pengumuman baru dari Olyuno."),
                    checked = announceOn,
                    onChange = vm::setNotifyAnnouncements,
                    dimmed = !granted,
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Streak"))
            MenuGroup {
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Pengingat streak"),
                    description = t("Diingatkan kalau streak hari ini belum terhitung."),
                    checked = streakOn,
                    onChange = vm::setNotifyStreak,
                    dimmed = !granted,
                )
                MenuDivider()
                MenuRow(
                    leading = { Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Waktu pengingat"),
                    onClick = if (streakOn) {
                        {
                            android.app.TimePickerDialog(
                                context,
                                { _, h, m -> vm.setStreakReminderTime(h, m) },
                                reminderMin / 60,
                                reminderMin % 60,
                                true,
                            ).show()
                        }
                    } else null,
                    dimmed = !streakOn || !granted,
                ) {
                    Text(
                        String.format(java.util.Locale.ROOT, "%02d:%02d", reminderMin / 60, reminderMin % 60),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MenuDivider()
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.NightsStay, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Pengingat terakhir"),
                    description = t("Sekali lagi menjelang pergantian hari kalau streak masih belum terhitung."),
                    checked = lastOn,
                    onChange = vm::setNotifyStreakLast,
                    dimmed = !granted,
                )
                MenuDivider()
                MenuRow(
                    leading = { Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Waktu pengingat terakhir"),
                    onClick = if (lastOn) {
                        {
                            android.app.TimePickerDialog(
                                context,
                                { _, h, m -> vm.setStreakLastTime(h, m) },
                                lastMin / 60,
                                lastMin % 60,
                                true,
                            ).show()
                        }
                    } else null,
                    dimmed = !lastOn || !granted,
                ) {
                    Text(
                        String.format(java.util.Locale.ROOT, "%02d:%02d", lastMin / 60, lastMin % 60),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Unduhan"))
            MenuGroup {
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Unduhan gagal"),
                    description = t("Saat ada lagu yang gagal diunduh, lengkap dengan tombol ulangi."),
                    checked = failedOn,
                    onChange = vm::setNotifyDownloadFailed,
                    dimmed = !granted,
                )
                MenuDivider()
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.TaskAlt, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Unduhan selesai"),
                    description = t("Saat playlist atau lagu selesai diunduh."),
                    checked = doneOn,
                    onChange = vm::setNotifyDownloadDone,
                    dimmed = !granted,
                )
                MenuDivider()
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.Downloading, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Progres unduhan"),
                    description = t("Selalu tampil selama mengunduh."),
                    dimmed = !granted,
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle(t("Pemutaran"))
            MenuGroup {
                SwitchMenuRow(
                    leading = { Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = t("Kontrol musik"),
                    description = t("Selalu tampil saat musik diputar."),
                    dimmed = !granted,
                )
            }

            Spacer(Modifier.height(8.dp))
            MenuGroup {
                MenuItem(Icons.Rounded.Settings, t("Pengaturan notifikasi aplikasi"), { openAppNotificationSettings(context) })
            }
        }
    }
}
