package app.tunes.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunes.MainViewModel
import app.tunes.data.StreakState
import app.tunes.data.StreakStore
import app.tunes.i18n.AppLanguage
import app.tunes.i18n.I18n
import app.tunes.i18n.t
import app.tunes.player.PlayerState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val FlameOrange = Color(0xFFFF8A1F)
private val IceBlue = Color(0xFF6CC4F2)

/** Kartu pembungkus: gaya sama dengan kartu menu (solid, atau agak tembus di gaya Kaca). */
@Composable
private fun Modifier.streakCard(corner: Int = 24): Modifier {
    val shape = RoundedCornerShape(corner.dp)
    return this
        .clip(shape)
        .background(glassContainer(MaterialTheme.colorScheme.surfaceContainerHigh))
        .then(
            if (LocalGlass.current.enabled) Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            else Modifier,
        )
}

/** Api streak: lihat [FlameLogo]. Animasinya ikut pengaturan animasi smooth. */
@Composable
private fun Flame(on: Boolean, size: Int) {
    FlameLogo(on = on, size = size.dp, animated = LocalSmoothAnim.current)
}

/**
 * Kartu streak di Beranda (di bawah sapaan): api + angka streak, dan 7 titik untuk 7 hari terakhir.
 * Ketuk untuk membuka halaman detail.
 */
@Composable
fun StreakHomeCard(state: StreakState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .streakCard()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Flame(on = state.countedToday, size = 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (state.current > 0) "${state.current} ${t("hari beruntun")}" else t("Streak"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    state.current == 0 -> t("Mulai streak dengan mendengarkan musik")
                    state.countedToday -> t("Hari ini sudah terhitung")
                    else -> t("Dengarkan musik hari ini untuk menjaga streak")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (offset in 6 downTo 0) {
                val day = state.todayEpochDay - offset
                val counted = day in state.days
                val saved = day in state.saved
                val isToday = offset == 0
                val dot = Modifier.size(8.dp).clip(CircleShape)
                Box(
                    when {
                        counted -> dot.background(FlameOrange)
                        saved -> dot.background(IceBlue)
                        isToday -> dot.border(1.5.dp, FlameOrange, CircleShape)
                        else -> dot.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                    },
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

/** Halaman detail streak: angka besar, rekor, freeze, progres hari ini, dan kalender bulanan. */
@Composable
fun StreakPage(
    vm: MainViewModel,
    player: PlayerState,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val s by vm.streak.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshStreak() }
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }

    SubPage(title = t("Streak"), vm = vm, player = player, onBack = onBack, onOpenPlayer = onOpenPlayer) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = LocalTopInset.current, bottom = 16.dp + LocalBottomInset.current),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "hero") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Flame(on = s.countedToday, size = 112)
                    Text(
                        text = s.current.toString(),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (s.current > 0) FlameOrange else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        t("hari beruntun"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "stats") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatTile(t("Rekor terpanjang"), "${s.longest} ${t("hari")}", Modifier.weight(1f))
                    StatTile(t("Freeze"), "${s.freezes} / ${StreakStore.MAX_FREEZES}", Modifier.weight(1f)) {
                        FreezeLogo(size = 40.dp, animated = LocalSmoothAnim.current)
                    }
                }
            }

            item(key = "today") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).streakCard().padding(16.dp),
                ) {
                    Text(t("Hari ini"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (s.countedToday) {
                        Text(t("Hari ini sudah terhitung."), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LinearProgressIndicator(
                            progress = { (s.todayMs.toFloat() / StreakStore.DAY_TOTAL_MS).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = FlameOrange,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            t("Hari ini: %s / %s", formatMs(s.todayMs), formatMs(StreakStore.DAY_TOTAL_MS)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t("Dengarkan 1 lagu sampai 80% (atau 2 menit), atau total 5 menit dalam sehari."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.clockSuspicious) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                t("Jam perangkat berubah tidak wajar, hari ini belum dihitung."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item(key = "calendar") {
                StreakCalendar(s, monthOffset, onMonthOffset = { monthOffset = it })
            }

            item(key = "freezeInfo") {
                Text(
                    t("Freeze menyelamatkan streak saat 1 hari terlewat. Dapat 1 tiap 7 hari streak (maksimal 2)."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null) {
    Row(modifier.streakCard().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        if (icon != null) icon()
    }
}

@Composable
private fun StreakCalendar(s: StreakState, monthOffset: Int, onMonthOffset: (Int) -> Unit) {
    val locale = if (I18n.effective == AppLanguage.English) Locale.ENGLISH else Locale("id", "ID")
    val today = LocalDate.ofEpochDay(s.todayEpochDay)
    val month = YearMonth.from(today).plusMonths(monthOffset.toLong())
    val earliest = (s.days + s.saved).minOrNull()?.let { YearMonth.from(LocalDate.ofEpochDay(it)) } ?: YearMonth.from(today)
    val canPrev = month.isAfter(earliest)
    val canNext = monthOffset < 0
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1 // Senin = kolom pertama
    val length = month.lengthOfMonth()
    val rows = (lead + length + 6) / 7

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).streakCard().padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonthOffset(monthOffset - 1) }, enabled = canPrev) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = null)
            }
            Text(
                "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = { onMonthOffset(monthOffset + 1) }, enabled = canNext) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (dow in DayOfWeek.values()) {
                Text(
                    dow.getDisplayName(TextStyle.SHORT, locale),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                for (c in 0 until 7) {
                    val dayOfMonth = r * 7 + c - lead + 1
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (dayOfMonth in 1..length) {
                            DayCell(
                                dayOfMonth = dayOfMonth,
                                epochDay = first.toEpochDay() + dayOfMonth - 1,
                                s = s,
                            )
                        } else {
                            Spacer(Modifier.size(36.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(FlameOrange))
            Spacer(Modifier.width(6.dp))
            Text(t("Terhitung"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            FreezeLogo(size = 16.dp, animated = false)
            Spacer(Modifier.width(6.dp))
            Text(t("Diselamatkan freeze"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayCell(dayOfMonth: Int, epochDay: Long, s: StreakState) {
    val counted = epochDay in s.days
    val saved = epochDay in s.saved
    val isToday = epochDay == s.todayEpochDay
    val future = epochDay > s.todayEpochDay
    val base = Modifier.size(36.dp).clip(CircleShape)
    val mod = when {
        counted -> base.background(FlameOrange)
        saved -> base.background(IceBlue.copy(alpha = 0.3f))
        isToday -> base.border(1.5.dp, FlameOrange, CircleShape)
        else -> base
    }
    Box(mod, contentAlignment = Alignment.Center) {
        if (saved && !counted) {
            FreezeLogo(size = 26.dp, animated = LocalSmoothAnim.current)
        } else {
            Text(
                dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (counted || isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    counted -> Color.White
                    future -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
