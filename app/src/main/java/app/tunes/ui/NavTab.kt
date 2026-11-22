package app.tunes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Satu tab di bilah bawah. Tab aktif dibungkus kapsul berwarna yang memuat ikon DAN nama tabnya
 * (bukan hanya ikon). Kapsul membesar dari tengah dengan sedikit membal saat tab dipilih dan menyusut
 * saat ditinggalkan; ikon sedikit membesar; warna ikon dan nama berubah halus. Semua gerak lewat
 * `graphicsLayer`, jadi tidak ada layout ulang selama animasi.
 *
 * [badgeCount] > 0 menampilkan lencana angka di ikon (mis. pesan Informasi yang belum dibuka).
 */
@Composable
fun NavTabItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
) {
    val colors = MaterialTheme.colorScheme
    // Kapsul abu-abu transparan; ikon + nama tab terpilih: Hitam = putih, Putih = hitam, Kustom = warna aksen (null = Ikuti sistem, bawaan).
    val inverted = playCircleColors()
    // Animasi smooth mati: kapsul, ikon, dan warna langsung berpindah tanpa gerak.
    val smooth = LocalSmoothAnim.current
    val pill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (smooth) spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "navPill",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 0.95f,
        animationSpec = if (smooth) spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "navIconScale",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) (inverted?.circle ?: colors.onSecondaryContainer) else colors.onSurfaceVariant,
        animationSpec = if (smooth) tween(180) else snap(),
        label = "navTabColor",
    )

    Box(
        modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Kapsul penanda: membungkus ikon + nama tab.
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 10.dp)
                .graphicsLayer {
                    val sc = (0.5f + 0.5f * pill).coerceAtLeast(0f)
                    scaleX = sc
                    scaleY = sc
                    alpha = pill.coerceIn(0f, 1f)
                }
                .background(if (inverted != null) colors.onSurface.copy(alpha = 0.16f) else colors.secondaryContainer, RoundedCornerShape(30.dp)),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BadgedBox(badge = { if (badgeCount > 0) Badge { Text(badgeCount.toString()) } }) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
