package app.tunes.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Bagian hari menurut jam DI PERANGKAT pengguna (zona waktu yang sedang dipakai perangkat), bukan zona tetap.
 * Satu sumber untuk semua fitur yang bergantung pada jam: sapaan Beranda, saran Olyuno AI, sapaan obrolan kosong.
 */
enum class DayPart { Pagi, Siang, Sore, Malam, LarutMalam }

/** 05.00-11.59 pagi, 12.00-14.59 siang, 15.00-17.59 sore, 18.00-23.59 malam, 00.00-04.59 larut malam. */
fun dayPartOf(nowMs: Long): DayPart {
    // Calendar.getInstance() memakai zona waktu default perangkat; Android memperbaruinya saat zona waktu diganti.
    val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> DayPart.Pagi
        in 12..14 -> DayPart.Siang
        in 15..17 -> DayPart.Sore
        in 18..23 -> DayPart.Malam
        else -> DayPart.LarutMalam
    }
}

/**
 * Waktu perangkat (ms) yang diperbarui otomatis: tiap menit berganti (ACTION_TIME_TICK) dan langsung saat pengguna
 * mengubah jam atau zona waktu (TIME_SET / TIMEZONE_CHANGED), jadi sapaan ikut berubah tanpa menunggu.
 */
@Composable
fun rememberDeviceNow(): State<Long> {
    val context = LocalContext.current
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                now.longValue = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        now.longValue = System.currentTimeMillis() // sinkron lagi kalau kembali dari latar
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return now
}
