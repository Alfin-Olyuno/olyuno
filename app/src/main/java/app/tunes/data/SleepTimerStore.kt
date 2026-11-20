package app.tunes.data

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Timer tidur. [endAtMs] memakai jam [SystemClock.elapsedRealtime] (0 = tidak ada timer hitung mundur),
 * [minutes] = pilihan menit yang dipakai (untuk menandai pilihan di sheet), [untilTrackEnd] = berhenti di akhir lagu.
 */
data class SleepTimerState(
    val endAtMs: Long = 0L,
    val minutes: Int = 0,
    val untilTrackEnd: Boolean = false,
) {
    val active: Boolean get() = untilTrackEnd || endAtMs > 0L
}

/** Penyimpan status timer tidur (hanya di memori; yang menjalankannya adalah PlaybackService). */
class SleepTimerStore {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    fun startMinutes(minutes: Int) {
        _state.value = SleepTimerState(
            endAtMs = SystemClock.elapsedRealtime() + minutes * 60_000L,
            minutes = minutes,
        )
    }

    fun startUntilTrackEnd() {
        _state.value = SleepTimerState(untilTrackEnd = true)
    }

    fun cancel() {
        _state.value = SleepTimerState()
    }
}
