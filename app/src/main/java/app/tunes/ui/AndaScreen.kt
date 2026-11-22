package app.tunes.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tunes.innertube.AccountInfo
import app.tunes.i18n.t

/**
 * Halaman tab Anda. Paling atas bagian "Akun Anda": "Masuk dengan Google" (belum login) atau nama akun + "Keluar".
 * Di bawahnya: menu dalam tiga kartu (Riwayat+Statistik+Ekualiser, Ekspor+Impor, Pengaturan+Informasi). Keluar juga ada di Pengaturan.
 */
@Composable
fun AndaScreen(
    account: AccountInfo?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    infoUnread: Int,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = LocalTabTopInset.current, bottom = LocalBottomInset.current),
    ) {
        // Akun Anda: belum login = "Masuk dengan Google"; sudah login = nama akun + "Keluar".
        SectionTitle(t("Akun Anda"))
        MenuGroup {
            if (account == null) {
                MenuItem(Icons.Rounded.AccountCircle, t("Masuk dengan Google"), onLogin)
            } else {
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
                MenuItem(Icons.AutoMirrored.Rounded.Logout, t("Keluar"), onLogout)
            }
        }
        // Menu dipisah per kelompok, tiap kelompok satu kartu (gaya sama dengan halaman Pengaturan).
        MenuGroup {
            MenuItem(Icons.Rounded.History, t("Riwayat lagu"), onOpenHistory)
            MenuDivider()
            MenuItem(Icons.Rounded.Insights, t("Statistik"), onOpenStats)
            MenuDivider()
            MenuItem(Icons.Rounded.Equalizer, t("Ekualiser"), onOpenEqualizer)
        }
        MenuGroup {
            MenuItem(Icons.Rounded.FileUpload, t("Ekspor data"), onExport)
            MenuDivider()
            MenuItem(Icons.Rounded.FileDownload, t("Impor data"), onImport)
        }
        MenuGroup {
            MenuItem(Icons.Rounded.Settings, t("Pengaturan"), onOpenSettings)
            MenuDivider()
            MenuItem(Icons.Rounded.Mail, t("Informasi"), onOpenInfo, badge = infoUnread)
            MenuDivider()
            MenuItem(Icons.Rounded.Info, t("Tentang Olyuno"), onOpenAbout)
        }
    }
}

/** Judul bagian di atas kartu (mis. "Tema"): abu-abu, ukuran sedang, sejajar dengan isi kartu. */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Kartu pembungkus satu kelompok menu. */
@Composable
internal fun MenuGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            // Gaya Kaca: kartu agak tembus dengan garis tipis di tepinya; gaya Biasa: solid seperti semula.
            .background(glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh))
            .then(
                if (LocalGlass.current.enabled) {
                    Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

@Composable
internal fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

/**
 * Satu baris menu: [leading] di kiri, label, lalu [trailing] di kanan. Tinggi minimumnya seragam supaya
 * baris dengan panah, toggle, atau foto akun sama tingginya; [onClick] null = baris hanya informasi.
 */
@Composable
internal fun MenuRow(
    leading: @Composable () -> Unit,
    label: String,
    onClick: (() -> Unit)? = null,
    bold: Boolean = false,
    dimmed: Boolean = false,
    description: String? = null,
    checked: Boolean? = null,
    onChange: ((Boolean) -> Unit)? = null,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    // Dengan checked + onChange baris ini menjadi baris toggle (ketuk baris atau toggle, jalur halus yang sama).
    if (checked != null && onChange != null) {
        SwitchMenuRow(
            leading = leading,
            label = label,
            checked = checked,
            onChange = onChange,
            description = description,
            dimmed = dimmed,
        )
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .then(if (dimmed) Modifier.alpha(0.38f) else Modifier)
                .heightIn(min = 60.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (bold) FontWeight.SemiBold else null,
                )
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing()
        }
    }
}

/** Menu biasa: ikon polos di kiri, label, (badge angka), panah di kanan. */
@Composable
internal fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit, badge: Int = 0) {
    MenuRow(
        leading = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        },
        label = label,
        onClick = onClick,
    ) {
        if (badge > 0) {
            Badge(Modifier.padding(end = 10.dp)) { Text(badge.toString()) }
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
