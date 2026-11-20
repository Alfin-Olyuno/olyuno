package app.tunes.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/*
 * Gaya Kaca untuk permukaan yang tampil di jendela sendiri (menu titik tiga, dialog, bottom sheet) dan
 * kartu menu. Gaya Biasa: semua fungsi di sini mengembalikan warna solid yang sama seperti semula.
 *
 * Dialog dan bottom sheet TIDAK mengaburkan isi di belakangnya: latarnya digelapkan sedikit (dim) seperti
 * aplikasi lain. Karena tidak ada blur di belakang wadah, wadah gaya Kaca dibuat hampir solid.
 */

/** Warna gelap tipis di belakang bottom sheet (scrim). */
val ModalScrim: Color = Color.Black.copy(alpha = 0.5f)

/** Sudut atas bottom sheet: sedikit lebih besar dari dialog supaya terasa lebih lembut. */
val ModalSheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

/** Pegangan tarik bottom sheet: pil tipis, warnanya mengikuti warna teks sheet. */
@Composable
fun ModalDragHandle() {
    Box(
        Modifier
            .padding(top = 12.dp, bottom = 8.dp)
            .size(width = 40.dp, height = 4.dp)
            .clip(CircleShape)
            .background(LocalContentColor.current.copy(alpha = 0.32f)),
    )
}

/**
 * Latar baris pilihan di dalam modal: kapsul bersudut 16dp; yang terpilih diberi rona warna utama tipis,
 * supaya pilihan aktif terlihat jelas tanpa hanya bergantung pada radio.
 */
@Composable
fun Modifier.modalOption(selected: Boolean, onClick: () -> Unit): Modifier {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = if (LocalSmoothAnim.current) tween(180) else snap(),
        label = "modalOption",
    )
    return this
        .clip(RoundedCornerShape(16.dp))
        .background(tint)
        .clickable(onClick = onClick)
}

/** Kepekatan gelap latar di belakang dialog (0..1). */
private const val DIALOG_DIM = 0.5f

/**
 * Warna wadah: [solid] untuk gaya Biasa; gaya Kaca = [solid] yang sedikit tembus ([plainAlpha]).
 * [blurAlpha] dipertahankan hanya demi kompatibilitas pemanggil; tidak dipakai lagi.
 */
@Composable
fun glassContainer(solid: Color, blurAlpha: Float = 0.80f, plainAlpha: Float = 0.94f): Color {
    if (!LocalGlass.current.enabled) return solid
    return solid.copy(alpha = plainAlpha)
}

/** Warna menu titik tiga: sama dengan bawaan Material, atau agak tembus di gaya Kaca. */
@Composable
fun glassMenuColor(): Color = glassContainer(MaterialTheme.colorScheme.surfaceContainer, 0.86f, 0.96f)

/** Garis tipis di tepi menu / kartu hanya di gaya Kaca (menegaskan tepi kaca); null di gaya Biasa. */
@Composable
fun glassEdge(): BorderStroke? =
    if (LocalGlass.current.enabled) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant) else null

/**
 * Panggil di dalam isi dialog: menggelapkan isi di belakang jendela dialog (tanpa blur).
 * Bottom sheet tidak perlu ini: sheet memakai scrim-nya sendiri ([ModalScrim]).
 */
@Composable
fun DialogDim() {
    val view = LocalView.current
    LaunchedEffect(view) {
        runCatching {
            val root = view.rootView
            val lp = root.layoutParams as? WindowManager.LayoutParams ?: return@runCatching
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            lp.dimAmount = DIALOG_DIM
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                lp.blurBehindRadius = 0
            }
            view.context.getSystemService(WindowManager::class.java).updateViewLayout(root, lp)
        }
    }
}
