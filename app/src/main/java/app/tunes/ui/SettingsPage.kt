package app.tunes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.innertube.AccountInfo
import app.tunes.player.PlayerState
import app.tunes.i18n.t

/**
 * Halaman Pengaturan. Keluar = logout akun; toggle = Otomatis sinkronisasi dengan akun (tersimpan);
 * Tampilan membuka halaman Warna tampilan / Gaya tampilan; Lirik membuka halaman Warna teks lirik.
 *
 * Semua baris memakai [MenuRow] seperti menu di tab Anda, jadi tinggi, jarak, dan posisi label seragam.
 * Ikon memakai ulang yang sudah ada di aplikasi: Sync & Logout (halaman Anda), LyricsBubbleIcon (tombol lirik
 * di pemutar), foto akun (Artwork).
 */
@Composable
fun SettingsPage(
    account: AccountInfo?,
    autoSync: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAbout: () -> Unit,
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    SubPage(title = t("Pengaturan"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            // Satu kelompok = satu kartu dengan garis tipis di antara barisnya; beda jenis = kartu terpisah
            // dengan jarak (dari padding atas MenuGroup).
            if (account != null) {
                MenuGroup {
                    MenuRow(
                        // Foto 32dp di slot 24dp (requiredSize) supaya label sejajar dengan baris di bawahnya.
                        leading = {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                Artwork(url = account.photoUrl, modifier = Modifier.requiredSize(32.dp), shape = CircleShape)
                            }
                        },
                        label = account.name,
                        onClick = null,
                        bold = true,
                    )
                    MenuDivider()
                    SwitchMenuRow(
                        leading = {
                            Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(24.dp))
                        },
                        label = t("Otomatis sinkronisasi dengan akun"),
                        checked = autoSync,
                        onChange = onAutoSyncChange,
                    )
                    MenuDivider()
                    MenuItem(Icons.AutoMirrored.Rounded.Logout, t("Keluar"), onLogout)
                }
            }
            // Umum: tampilan & bahasa aplikasi.
            MenuGroup {
                MenuItem(Icons.Rounded.Palette, t("Tampilan"), onOpenAppearance)
                MenuDivider()
                MenuItem(Icons.Rounded.Language, t("Bahasa"), onOpenLanguage)
                MenuDivider()
                MenuItem(Icons.Rounded.Storage, t("Penyimpanan"), onOpenStorage)
                MenuDivider()
                MenuItem(Icons.Rounded.Notifications, t("Notifikasi"), onOpenNotifications)
            }
            // Pemutar: perilaku pemutaran (termasuk audio) & lirik.
            MenuGroup {
                MenuItem(Icons.Rounded.QueueMusic, t("Pemutaran"), onOpenPlayback)
                MenuDivider()
                MenuItem(LyricsBubbleIcon, t("Lirik"), onOpenLyrics)
            }
            // Tentang: info aplikasi & pembuat.
            MenuGroup {
                MenuItem(Icons.Rounded.Info, t("Tentang Olyuno"), onOpenAbout)
            }
        }
    }
}
