package app.tunes.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** Keadaan streak untuk UI. Semua hari dinyatakan sebagai "epoch day" menurut zona waktu perangkat. */
data class StreakState(
    /** Streak yang ditampilkan sekarang (0 = tidak ada). */
    val current: Int = 0,
    val longest: Int = 0,
    val freezes: Int = 0,
    val countedToday: Boolean = false,
    val todayEpochDay: Long = 0L,
    /** Hari yang terhitung dan hari yang diselamatkan freeze. */
    val days: Set<Long> = emptySet(),
    val saved: Set<Long> = emptySet(),
    /** Total waktu dengar hari ini (ms), untuk bar progres jalur "total 5 menit". */
    val todayMs: Long = 0L,
    /** true = jam perangkat terdeteksi berubah tidak wajar; waktu dengar tidak dihitung. */
    val clockSuspicious: Boolean = false,
)

/** Peristiwa saat sebuah hari baru saja terhitung (dipakai untuk toast). */
sealed interface StreakEvent {
    data object Started : StreakEvent
    data class Up(val days: Int) : StreakEvent
    data object FreezeUsed : StreakEvent
    data class Record(val days: Int) : StreakEvent
}

/** Isi bagian streak di file cadangan. */
class StreakBackup(val days: List<Long>, val saved: List<Long>, val freezes: Int)

enum class StreakImportResult { Accepted, SkippedExisting, Empty }

/**
 * Streak mendengarkan. Aturan:
 * - Satu hari terhitung kalau (a) ada satu lagu yang didengar sampai 80% durasinya atau 2 menit (mana yang lebih
 *   dulu, minimal 30 detik), atau (b) total waktu dengar hari itu mencapai 5 menit. Yang dihitung waktu putar nyata,
 *   jadi loncat (seek) ke akhir lagu tidak menambah apa pun.
 * - Streak putus kalau satu hari penuh terlewat, kecuali ada freeze. Freeze bertambah 1 tiap kelipatan 7 hari streak
 *   (simpanan maksimal 2); satu hari bolong diselamatkan otomatis, dua hari berturut-turut tidak.
 * - Pergantian hari 00:00 waktu perangkat. Jam dinding dibandingkan dengan jam sejak HP menyala (tidak bisa
 *   diubah lewat pengaturan); kalau selisihnya melompat, waktu dengar tidak dihitung.
 * Disimpan lokal di SharedPreferences.
 */
class StreakStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("streak", Context.MODE_PRIVATE)
    private val bootCount: Int = runCatching {
        Settings.Global.getInt(context.applicationContext.contentResolver, "boot_count", -1)
    }.getOrDefault(-1)

    private val days = HashSet<Long>()
    private val saved = HashSet<Long>()
    private var freezes = 0
    private var longest = 0

    // Waktu dengar hari berjalan.
    private var todayKey = 0L
    private var todayMs = 0L
    private var songMs = 0L

    // Jangkar jam: pasangan (jam dinding, jam sejak menyala) yang dipercaya.
    private var anchorWall = 0L
    private var anchorElapsed = 0L
    private var anchorBoot = -1
    private var lastValidWall = 0L
    private var suspicious = false

    private var lastTickElapsed = 0L
    private var lastPersistElapsed = 0L
    private var lastPublishElapsed = 0L

    private val _state = MutableStateFlow(StreakState())
    val state: StateFlow<StreakState> = _state.asStateFlow()

    init {
        parseSet(prefs.getString(K_DAYS, null), days)
        parseSet(prefs.getString(K_SAVED, null), saved)
        freezes = prefs.getInt(K_FREEZES, 0).coerceIn(0, MAX_FREEZES)
        longest = maxOf(prefs.getInt(K_LONGEST, 0), longestRun())
        todayKey = prefs.getLong(K_TODAY_KEY, 0L)
        todayMs = prefs.getLong(K_TODAY_MS, 0L)
        anchorWall = prefs.getLong(K_ANCHOR_WALL, 0L)
        anchorElapsed = prefs.getLong(K_ANCHOR_ELAPSED, 0L)
        anchorBoot = prefs.getInt(K_ANCHOR_BOOT, -1)
        lastValidWall = prefs.getLong(K_LAST_VALID, 0L)
        publish(System.currentTimeMillis())
    }

    // ------------------------------------------------------------- penghitungan

    /** Perbarui pemeriksaan jam dan keadaan (dipanggil saat layar dibuka). */
    @Synchronized
    fun refresh() {
        val nowW = System.currentTimeMillis()
        val trusted = checkClock(nowW, SystemClock.elapsedRealtime())
        suspicious = !trusted
        publish(nowW)
    }

    /** Lagu berganti / diputar ulang dari awal: waktu dengar lagu dihitung dari nol. */
    @Synchronized
    fun onSongChanged() {
        songMs = 0L
    }

    /**
     * Dipanggil sekitar tiap detik oleh pemutar. [playing] = sedang benar-benar memutar, [durationMs] = durasi lagu
     * (0 kalau belum diketahui). Mengembalikan peristiwa kalau hari ini baru saja terhitung.
     */
    @Synchronized
    fun onTick(playing: Boolean, durationMs: Long): StreakEvent? {
        val nowE = SystemClock.elapsedRealtime()
        val nowW = System.currentTimeMillis()
        val dt = if (lastTickElapsed == 0L) 0L else (nowE - lastTickElapsed).coerceIn(0L, 2_000L)
        lastTickElapsed = nowE

        val trusted = checkClock(nowW, nowE)
        val changed = suspicious == trusted // berubah dari kondisi sebelumnya
        suspicious = !trusted
        if (!trusted) {
            if (changed) publish(nowW)
            return null
        }

        val today = epochDay(nowW)
        if (today != todayKey) {
            todayKey = today
            todayMs = 0L
            persistProgress()
        }
        if (!playing) {
            if (changed) publish(nowW)
            return null
        }

        songMs += dt
        todayMs += dt
        var event: StreakEvent? = null
        if (today !in days) {
            val need = songThresholdMs(durationMs)
            if (songMs >= need || todayMs >= DAY_TOTAL_MS) {
                event = markCounted(today)
                persistAll()
                publish(nowW)
                return event
            }
        }
        if (nowE - lastPersistElapsed >= PERSIST_EVERY_MS) persistProgress()
        if (changed || nowE - lastPublishElapsed >= PUBLISH_EVERY_MS) publish(nowW)
        return event
    }

    /** Simpan sisa waktu dengar hari ini (dipanggil saat layanan pemutar berhenti). */
    @Synchronized
    fun flush() = persistProgress()

    private fun songThresholdMs(durationMs: Long): Long {
        val byFraction = if (durationMs > 0) (durationMs * 0.8).toLong() else SONG_CAP_MS
        return maxOf(SONG_MIN_MS, minOf(byFraction, SONG_CAP_MS))
    }

    /** Tandai [today] terhitung; pakai freeze untuk menyelamatkan kemarin kalau perlu. */
    private fun markCounted(today: Long): StreakEvent {
        val prevLongest = longest
        var freezeUsed = false
        val y = today - 1
        val yActive = y in days || y in saved
        if (!yActive && freezes > 0 && ((y - 1) in days || (y - 1) in saved)) {
            saved += y
            freezes--
            freezeUsed = true
        }
        days += today
        val n = runLength(today)
        if (n > 0 && n % STREAK_PER_FREEZE == 0) freezes = minOf(MAX_FREEZES, freezes + 1)
        longest = maxOf(longest, n)
        return when {
            freezeUsed -> StreakEvent.FreezeUsed
            prevLongest >= 3 && n > prevLongest -> StreakEvent.Record(n)
            n <= 1 -> StreakEvent.Started
            else -> StreakEvent.Up(n)
        }
    }

    /** Panjang rangkaian yang berakhir di [end]: hari terhitung +1, hari freeze menyambung tanpa menambah. */
    private fun runLength(end: Long): Int {
        var d = end
        var n = 0
        while (true) {
            if (d in days) n++ else if (d !in saved) break
            d--
        }
        return n
    }

    private fun longestRun(): Int {
        var best = 0
        for (d in days) {
            if ((d + 1) in days || (d + 1) in saved) continue // bukan ujung rangkaian
            best = maxOf(best, runLength(d))
        }
        return best
    }

    private fun displayStreak(today: Long): Int = when {
        today in days -> runLength(today)
        (today - 1) in days || (today - 1) in saved -> runLength(today - 1)
        // Kemarin bolong tapi ada freeze: saat hari ini terhitung, freeze menyelamatkannya.
        freezes > 0 && (today - 2) in days -> runLength(today - 2)
        else -> 0
    }

    // ------------------------------------------------------------ pengaman jam

    /**
     * true = jam dinding bisa dipercaya. Di boot yang sama, jam dinding harus maju sejajar dengan jam sejak menyala;
     * kalau melompat lebih dari [CLOCK_TOLERANCE_MS] (atau mundur dari waktu valid terakhir), dianggap diubah.
     * Setelah restart, jam dinding hanya boleh tidak lebih mundur dari waktu valid terakhir.
     */
    private fun checkClock(nowW: Long, nowE: Long): Boolean {
        if (anchorWall == 0L) {
            setAnchor(nowW, nowE)
            return true
        }
        val rebooted = bootCount != anchorBoot || nowE < anchorElapsed
        val trusted = if (rebooted) {
            nowW >= lastValidWall - CLOCK_TOLERANCE_MS
        } else {
            val expected = anchorWall + (nowE - anchorElapsed)
            abs(nowW - expected) <= CLOCK_TOLERANCE_MS && nowW >= lastValidWall - CLOCK_TOLERANCE_MS
        }
        if (trusted) setAnchor(nowW, nowE)
        return trusted
    }

    private fun setAnchor(nowW: Long, nowE: Long) {
        anchorWall = nowW
        anchorElapsed = nowE
        anchorBoot = bootCount
        lastValidWall = maxOf(lastValidWall, nowW)
        if (nowE - lastPersistElapsed >= PERSIST_EVERY_MS || lastPersistElapsed == 0L) {
            prefs.edit()
                .putLong(K_ANCHOR_WALL, anchorWall)
                .putLong(K_ANCHOR_ELAPSED, anchorElapsed)
                .putInt(K_ANCHOR_BOOT, anchorBoot)
                .putLong(K_LAST_VALID, lastValidWall)
                .apply()
        }
    }

    // ---------------------------------------------------------------- cadangan

    /** Isi bagian streak untuk file cadangan; null kalau belum ada hari terhitung. */
    @Synchronized
    fun toBackup(): StreakBackup? =
        if (days.isEmpty()) null else StreakBackup(days.sorted(), saved.sorted(), freezes)

    /**
     * Gabungkan streak dari cadangan. Hanya diterima kalau streak lokal masih hampir kosong (pemulihan setelah
     * pasang ulang / pindah HP), supaya file milik orang lain tidak bisa dicampurkan ke streak yang sudah berjalan.
     */
    @Synchronized
    fun importBackup(b: StreakBackup): StreakImportResult {
        if (days.size >= IMPORT_ONLY_BELOW_DAYS) return StreakImportResult.SkippedExisting
        val maxDay = epochDay(if (lastValidWall > 0) lastValidWall else System.currentTimeMillis())
        val newDays = b.days.filter { it in MIN_EPOCH_DAY..maxDay }.take(MAX_DAYS)
        if (newDays.isEmpty()) return StreakImportResult.Empty
        days += newDays
        // Hari freeze hanya valid kalau besoknya terhitung.
        saved += b.saved.filter { it in MIN_EPOCH_DAY..maxDay && (it + 1) in days }
        freezes = minOf(MAX_FREEZES, maxOf(freezes, b.freezes.coerceAtLeast(0)))
        longest = maxOf(longest, longestRun())
        persistAll()
        publish(System.currentTimeMillis())
        return StreakImportResult.Accepted
    }

    // -------------------------------------------------------------- penyimpanan

    private fun persistProgress() {
        lastPersistElapsed = SystemClock.elapsedRealtime()
        prefs.edit().putLong(K_TODAY_KEY, todayKey).putLong(K_TODAY_MS, todayMs).apply()
    }

    private fun persistAll() {
        lastPersistElapsed = SystemClock.elapsedRealtime()
        prefs.edit()
            .putString(K_DAYS, days.sorted().joinToString(","))
            .putString(K_SAVED, saved.sorted().joinToString(","))
            .putInt(K_FREEZES, freezes)
            .putInt(K_LONGEST, longest)
            .putLong(K_TODAY_KEY, todayKey)
            .putLong(K_TODAY_MS, todayMs)
            .apply()
    }

    private fun publish(nowW: Long) {
        lastPublishElapsed = SystemClock.elapsedRealtime()
        val today = epochDay(nowW)
        _state.value = StreakState(
            current = displayStreak(today),
            longest = maxOf(longest, displayStreak(today)),
            freezes = freezes,
            countedToday = today in days,
            todayEpochDay = today,
            days = HashSet(days),
            saved = HashSet(saved),
            todayMs = if (todayKey == today) todayMs else 0L,
            clockSuspicious = suspicious,
        )
    }

    private fun parseSet(raw: String?, into: MutableSet<Long>) {
        if (raw.isNullOrBlank()) return
        raw.split(',').forEach { s -> s.trim().toLongOrNull()?.let { into += it } }
    }

    companion object {
        private const val K_DAYS = "days"
        private const val K_SAVED = "saved"
        private const val K_FREEZES = "freezes"
        private const val K_LONGEST = "longest"
        private const val K_TODAY_KEY = "today_key"
        private const val K_TODAY_MS = "today_ms"
        private const val K_ANCHOR_WALL = "anchor_wall"
        private const val K_ANCHOR_ELAPSED = "anchor_elapsed"
        private const val K_ANCHOR_BOOT = "anchor_boot"
        private const val K_LAST_VALID = "last_valid_wall"

        const val DAY_TOTAL_MS = 5 * 60_000L
        const val SONG_CAP_MS = 2 * 60_000L
        const val SONG_MIN_MS = 30_000L
        const val MAX_FREEZES = 2
        const val STREAK_PER_FREEZE = 7
        private const val CLOCK_TOLERANCE_MS = 5 * 60_000L
        private const val PERSIST_EVERY_MS = 15_000L
        private const val PUBLISH_EVERY_MS = 5_000L
        private const val IMPORT_ONLY_BELOW_DAYS = 3
        private const val MAX_DAYS = 4_000

        /** 2025-01-01: batas bawah hari yang masuk akal (sebelum Olyuno ada). */
        const val MIN_EPOCH_DAY = 20_089L

        /** Hari ke-berapa sejak 1970-01-01 menurut zona waktu perangkat. */
        fun epochDay(wallMs: Long): Long =
            Math.floorDiv(wallMs + TimeZone.getDefault().getOffset(wallMs), 86_400_000L)
    }
}

/**
 * Tanda tangan bagian streak di cadangan (HMAC-SHA256). Mencegah edit asal-asalan pada file; bukan pengaman
 * mutlak, karena kuncinya ada di dalam aplikasi.
 */
object StreakSigner {
    private val KEY = ("olyuno" + "-streak-" + "v1-" + "k7Qp2xZ9").toByteArray(Charsets.UTF_8)

    private fun canonical(b: StreakBackup): String =
        "olyuno-streak-v1|" + b.days.sorted().joinToString(",") + "|" +
            b.saved.sorted().joinToString(",") + "|" + b.freezes

    fun sign(b: StreakBackup): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(KEY, "HmacSHA256"))
        return mac.doFinal(canonical(b).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun verify(b: StreakBackup, sig: String): Boolean {
        val expected = sign(b)
        if (expected.length != sig.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor sig[i].code)
        return diff == 0
    }
}
