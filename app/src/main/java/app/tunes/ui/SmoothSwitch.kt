package app.tunes.ui

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Status toggle yang langsung bergerak saat disentuh. Pekerjaan berat (simpan pengaturan, recompose halaman,
 * pasang/lepas island, dll.) ditunda sebentar sampai animasi geser berjalan, supaya animasi tidak patah.
 * Setelah itu tampilan disamakan lagi dengan nilai sebenarnya, jadi toggle yang ditolak (mis. izin belum
 * diberikan) kembali ke posisi semula dan tidak pernah "nyangkut" di posisi salah.
 */
@Stable
class SmoothToggle internal constructor(
    initial: Boolean,
    private val scope: CoroutineScope,
    private val truth: State<Boolean>,
    private val onChange: State<(Boolean) -> Unit>,
) {
    /** Posisi yang ditampilkan (bisa mendahului nilai sebenarnya selama beberapa milidetik). */
    var shown by mutableStateOf(initial)
        private set

    private var job: Job? = null
    private var pending: Boolean? = null

    fun set(v: Boolean) {
        if (v == shown) return
        shown = v
        pending = v
        job?.cancel()
        job = scope.launch {
            delay(DEFER_MS)
            deliver()
            delay(SETTLE_MS)
            shown = truth.value
        }
    }

    fun flip() = set(!shown)

    /** Nilai sebenarnya berubah dari luar (mis. diubah halaman lain): ikuti, kecuali sedang menunggu. */
    internal fun follow(v: Boolean) {
        if (job?.isActive != true) shown = v
    }

    /** Kirim perubahan yang tertunda (juga dipanggil saat halaman ditutup supaya tidak ada yang hilang). */
    internal fun deliver() {
        val p = pending ?: return
        pending = null
        if (p != truth.value) onChange.value(p)
    }

    private companion object {
        const val DEFER_MS = 140L
        const val SETTLE_MS = 260L
    }
}

@Composable
fun rememberSmoothToggle(checked: Boolean, onChange: (Boolean) -> Unit): SmoothToggle {
    val truth = rememberUpdatedState(checked)
    val change = rememberUpdatedState(onChange)
    val scope = rememberCoroutineScope()
    val toggle = remember { SmoothToggle(checked, scope, truth, change) }
    LaunchedEffect(checked) { toggle.follow(checked) }
    DisposableEffect(toggle) { onDispose { toggle.deliver() } }
    return toggle
}

@Composable
fun SmoothSwitch(
    toggle: SmoothToggle,
    modifier: Modifier = Modifier,
    colors: SwitchColors = SwitchDefaults.colors(),
) {
    Switch(checked = toggle.shown, onCheckedChange = toggle::set, modifier = modifier, colors = colors)
}

@Composable
fun SmoothSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    colors: SwitchColors = SwitchDefaults.colors(),
) = SmoothSwitch(rememberSmoothToggle(checked, onCheckedChange), modifier, colors)

/**
 * Baris menu dengan toggle (checked + onChange): ketuk baris atau toggle, keduanya lewat jalur halus yang sama.
 * Tanpa checked/onChange, baris berperilaku seperti MenuRow biasa dengan [onClick] dan [trailing].
 */
@Composable
internal fun SwitchMenuRow(
    leading: @Composable () -> Unit,
    label: String,
    checked: Boolean? = null,
    onChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    description: String? = null,
    dimmed: Boolean = false,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    if (checked != null && onChange != null) {
        val toggle = rememberSmoothToggle(checked, onChange)
        MenuRow(
            leading = leading,
            label = label,
            onClick = toggle::flip,
            dimmed = dimmed,
            description = description,
        ) {
            Spacer(Modifier.width(12.dp))
            SmoothSwitch(toggle)
        }
    } else {
        MenuRow(
            leading = leading,
            label = label,
            onClick = onClick,
            dimmed = dimmed,
            description = description,
            trailing = trailing,
        )
    }
}
