package app.tunes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tunes.MainViewModel
import app.tunes.i18n.AppLanguage
import app.tunes.i18n.I18n
import app.tunes.i18n.t
import app.tunes.player.PlayerState

/**
 * Halaman Bahasa: Default sistem (ikut bahasa perangkat: Indonesia -> Indonesia, selain itu -> English),
 * Bahasa Indonesia, dan English. Pilihan langsung berlaku di seluruh aplikasi dan tersimpan.
 */
@Composable
fun LanguagePage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val choice = I18n.choice
    SubPage(title = t("Bahasa"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
        ) {
            SectionTitle(t("Bahasa app"))
            MenuGroup {
                LanguageRow(
                    label = t("Default sistem"),
                    description = t("Mengikuti bahasa perangkat. Bahasa selain Indonesia memakai English."),
                    selected = choice == AppLanguage.System,
                ) { I18n.chooseLanguage(AppLanguage.System) }
                MenuDivider()
                // Nama bahasa memakai nama aslinya, tidak diterjemahkan.
                LanguageRow(
                    label = "Bahasa Indonesia",
                    selected = choice == AppLanguage.Indonesian,
                ) { I18n.chooseLanguage(AppLanguage.Indonesian) }
                MenuDivider()
                LanguageRow(
                    label = "English",
                    selected = choice == AppLanguage.English,
                ) { I18n.chooseLanguage(AppLanguage.English) }
            }
        }
    }
}

/** Satu pilihan bahasa dengan tombol radio di kanan (gaya sama dengan pilihan Warna tampilan). */
@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    description: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}
