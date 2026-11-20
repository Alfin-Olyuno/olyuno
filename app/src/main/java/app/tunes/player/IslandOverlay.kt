package app.tunes.player

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.media3.common.C
import androidx.media3.common.Player
import app.tunes.MainActivity
import app.tunes.R
import app.tunes.data.IslandButtonStyle
import app.tunes.data.IslandColor
import app.tunes.data.IslandShape
import app.tunes.data.IslandStyle
import app.tunes.ui.PaletteExtractor
import app.tunes.ui.SongPalette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Dynamic Island: kartu lagu yang melayang di atas layar (di atas aplikasi lain).
 *
 *  - Mini: kotak bersudut membulat berisi cover, judul, dan equalizer mini.
 *  - Ketuk mini -> membesar (animasi per-frame: lebar, tinggi, sudut) menjadi pemutar penuh:
 *    cover, judul/artis, seek bar yang bisa digeser, waktu, sebelumnya / putar-jeda / berikutnya.
 *  - Tarik kartu besar ke bawah (seperti bottom sheet) -> masuk ke layar pemutar di aplikasi Olyuno.
 *  - Geser kartu besar ke atas / kiri / kanan -> mengecil.
 *  - Membesar lalu mengecil lagi kalau: pengguna menyentuh / scroll di mana pun di luar kartu
 *    (lewat FLAG_WATCH_OUTSIDE_TOUCH), layar mati, atau 5 detik tanpa sentuhan sama sekali.
 *  - Muncul selama fitur dinyalakan, ada lagu di antrean, dan Olyuno sendiri tidak sedang dibuka.
 *    Dijeda pun kartu tetap tampil; hanya hilang kalau dimatikan dari pengaturan Olyuno
 *    (atau antrean kosong, mis. aplikasi digeser dari recent apps).
 *
 * Semua berjalan di thread utama (pemutar ExoPlayer juga dibuat di thread utama).
 */
class IslandOverlay(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
    private val enabled: StateFlow<Boolean>,
    private val appVisible: StateFlow<Boolean>,
    private val style: StateFlow<IslandStyle>,
    private val color: StateFlow<IslandColor>,
    private val customColor: StateFlow<Int>,
    /** Kelengkungan sudut 0f..1f (1f = bawaan gaya). */
    private val corner: StateFlow<Float>,
    /** Opasitas kartu (1f = tidak tembus). */
    private val opacity: StateFlow<Float>,
    /** Bentuk kartu kecil: persegi membulat atau bulat (hanya logo). */
    private val shape: StateFlow<IslandShape>,
    /** Lebar kartu besar (dp); seluruh kartu besar diskalakan sebanding (tinggi, teks, tombol ikut). */
    private val cardWidth: StateFlow<Float>,
    /** Skala ukuran teks (1f = bawaan) untuk judul, artis, dan waktu. */
    private val textScale: StateFlow<Float>,
    /** Gaya tombol kontrol: Terisi (bawaan) atau Garis tepi. */
    private val buttonStyle: StateFlow<IslandButtonStyle>,
    /** Tampilkan tombol kontrol di kartu besar (mati = kartu tanpa tombol dan lebih pendek). */
    private val showButtons: StateFlow<Boolean>,
    /** Tampilkan nama artis di kartu besar. */
    private val showArtist: StateFlow<Boolean>,
    /** Tampilkan nama lagu di kartu kecil dan besar. */
    private val showTitle: StateFlow<Boolean>,
    /** Kartu hanya tampil saat lagu diputar; disembunyikan begitu dijeda. */
    private val onlyWhenPlaying: StateFlow<Boolean>,
    /** Detik sampai kartu besar mengecil sendiri (bawaan 5). */
    private val collapseSec: StateFlow<Float>,
    /** Detik setelah dijeda sampai kartu disembunyikan (0 = langsung); dipakai bila [onlyWhenPlaying] nyala. */
    private val pauseHideSec: StateFlow<Float>,
    /** True kalau tampilan aplikasi sedang gelap (dipakai gaya Kaca & Biasa untuk memilih warna teks). */
    private val isDark: () -> Boolean,
    /** Level suara asli lagu; menggerakkan batang equalizer. */
    private val audioLevels: AudioLevels,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density get() = context.resources.displayMetrics.density

    // ----------------------------------------------------------------- state
    private var allowed = false

    private var root: IslandRoot? = null
    private var exiting = false
    private val attached get() = root != null && !exiting

    private var expanded = false

    /** Id lagu saat pengguna menyapu kartu kecil ke atas: kartu disembunyikan sampai lagu berganti. */
    private var swipeDismissedFor: String? = null
    private var fraction = 0f
    private var expandAnim: ValueAnimator? = null

    /** Tambahan tinggi (px) saat kartu ditarik ke bawah; kartu meregang mengikuti jari. */
    private var pull = 0f
    private var pullAnim: ValueAnimator? = null
    private var handle: View? = null

    // ----------------------------------------------------------------- ukuran
    private var miniW = 0
    private var miniH = 0
    private var expW = 0
    private var expH = 0

    /** Ukuran dasar kartu besar (tata letak asli) dan skala yang dipilih pengguna (<= 1). */
    private var baseW = 0
    private var baseH = 0
    private var sizeScale = 1f
    private var topOffset = 0

    // ----------------------------------------------------------------- view
    private var card: IslandCard? = null
    private var mini: LinearLayout? = null
    private var big: LinearLayout? = null
    private var miniArt: ImageView? = null
    private var bigArt: ImageView? = null
    private var miniTitle: TextView? = null
    private var bigTitle: TextView? = null
    private var bigArtist: TextView? = null
    private var miniEq: EqualizerView? = null
    private var bigEq: EqualizerView? = null
    private var seek: SeekBarView? = null
    private var elapsed: TextView? = null
    private var total: TextView? = null
    private var playBtn: ImageView? = null
    private var prevBtn: ImageView? = null
    private var nextBtn: ImageView? = null
    private var controls: LinearLayout? = null
    private var look: Look = Look.IPhone(0f, 0f)
    private var miniR = 0f
    private var bigR = 0f
    private var shownPlaying: Boolean? = null
    private var artUrl: String? = null
    private var palette: SongPalette = SongPalette.Default

    /**
     * Semua kode island berjalan di thread utama proses yang SAMA dengan aplikasi, jadi satu exception di sini
     * (jendela sudah dilepas, view belum siap, dll.) akan mematikan seluruh Olyuno. Bungkus tiap pintu masuk:
     * kalau ada yang gagal, island dilepas diam-diam dan aplikasi tetap hidup.
     */
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { removeNow() }
        }
    }

    private val collapseRunnable = Runnable { safe { collapse() } }

    /** Kartu sudah disembunyikan karena dijeda terlalu lama; pengatur waktunya sedang berjalan. */
    private var pauseHidden = false
    private var pausePending = false
    private val pauseHideRunnable = Runnable {
        safe {
            pausePending = false
            pauseHidden = true
            refresh()
        }
    }

    /** Mulai / batalkan hitungan mundur sembunyi-setelah-dijeda sesuai status pemutaran terkini. */
    private fun updatePauseHide() {
        if (!onlyWhenPlaying.value || isActive()) {
            handler.removeCallbacks(pauseHideRunnable)
            pausePending = false
            pauseHidden = false
            return
        }
        if (pauseHidden || pausePending) return
        val ms = (pauseHideSec.value * 1000f).toLong()
        if (ms <= 0L) {
            pauseHidden = true
        } else {
            pausePending = true
            handler.postDelayed(pauseHideRunnable, ms)
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!expanded || !attached) return
            safe {
                updateProgress()
                handler.postDelayed(this, 250)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = safe { refresh() }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            safe { collapse(animate = false) }
        }
    }

    // =================================================================== siklus hidup

    fun start() {
        player.addListener(listener)
        ContextCompat.registerReceiver(
            context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            combine(enabled, appVisible) { e, visible -> e && !visible }.collect {
                safe {
                    allowed = it
                    // Olyuno dibuka / island dimatikan: lepas jendela saat itu juga (tanpa animasi keluar yang masih
                    // berjalan saat aktivitas sedang dimulai); animasi hanya dipakai untuk sebab lain (mis. dijeda).
                    if (!it && root != null) removeNow() else refresh()
                }
            }
        }
        // Pengaturan "hanya saat diputar" berubah: pasang / lepas kartu langsung.
        scope.launch { onlyWhenPlaying.collect { safe { refresh() } } }
        // Lama jeda diubah: hitung ulang dari awal.
        scope.launch {
            pauseHideSec.collect {
                safe {
                    handler.removeCallbacks(pauseHideRunnable)
                    pausePending = false
                    refresh()
                }
            }
        }
        scope.launch {
            combine(
                combine(style, color, customColor, corner, opacity) { _, _, _, _, _ -> },
                combine(shape, cardWidth, textScale, buttonStyle) { _, _, _, _ -> },
                combine(showButtons, showArtist, showTitle) { _, _, _ -> },
            ) { _, _, _ -> }.collect {
                safe {
                    if (root != null) {
                        computeSizes()
                        applySizes()
                        applyMiniShape()
                        applyContentVisibility()
                        applyStyle()
                        applyFraction(fraction)
                        applyWindowBlur()
                    }
                }
            }
        }
        refresh()
    }

    fun release() {
        player.removeListener(listener)
        runCatching { context.unregisterReceiver(screenOff) }
        handler.removeCallbacksAndMessages(null)
        removeNow()
    }

    /** Layar diputar / ukuran berubah: hitung ulang ukuran kartu. */
    fun onConfigurationChanged() = safe {
        val r = root ?: return@safe
        computeSizes()
        applySizes()
        applyStyle()
        applyFraction(fraction)
        val lp = r.layoutParams as? WindowManager.LayoutParams ?: return@safe
        lp.y = topOffset
        runCatching { wm.updateViewLayout(r, lp) }
    }

    // =================================================================== reaksi pemutar

    private fun isActive(): Boolean =
        player.playWhenReady && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED

    private fun refresh() {
        updatePauseHide()
        val current = player.currentMediaItem
        if (swipeDismissedFor != null && current?.mediaId != swipeDismissedFor) swipeDismissedFor = null
        val show = allowed && current != null && swipeDismissedFor == null &&
            (!onlyWhenPlaying.value || isActive() || !pauseHidden)
        if (show) {
            if (!attached) attach()
            if (attached) bind()
        } else if (attached) {
            detach()
        }
    }

    // =================================================================== pasang / lepas jendela

    private fun attach() {
        val existing = root
        if (existing != null) {
            // Sedang animasi keluar tapi diminta tampil lagi: batalkan keluarnya.
            if (exiting) {
                // exiting dimatikan SEBELUM cancel(): withEndAction ikut jalan saat dibatalkan dan tidak boleh melepas jendela.
                exiting = false
                existing.animate().cancel()
                existing.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(200).start()
            }
            return
        }
        if (!Settings.canDrawOverlays(context)) return

        computeSizes()
        val r = buildViews()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topOffset
        }
        try {
            wm.addView(r, lp)
        } catch (e: Exception) {
            return
        }
        root = r
        exiting = false
        expanded = false
        fraction = 0f
        applyWindowBlur()
        shownPlaying = null
        artUrl = null
        applyFraction(0f)

        // Animasi masuk: turun dari atas sambil membesar dengan sedikit pantulan.
        r.pivotX = miniW / 2f
        r.pivotY = 0f
        r.alpha = 0f
        r.scaleX = 0.7f
        r.scaleY = 0.7f
        r.translationY = -dp(14f).toFloat()
        r.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(380).setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    private fun detach() {
        val r = root ?: return
        handler.removeCallbacks(collapseRunnable)
        handler.removeCallbacks(ticker)
        expandAnim?.cancel()
        // Batalkan animasi lama dulu (sebelum exiting = true) supaya end action-nya tidak ikut melepas jendela.
        r.animate().cancel()
        exiting = true
        r.pivotX = r.width / 2f
        r.pivotY = 0f
        r.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).translationY(-dp(10f).toFloat())
            .setDuration(220).setInterpolator(AccelerateInterpolator())
            .withEndAction { safe { if (exiting && root === r) removeNow() } }
            .start()
    }

    private fun removeNow() {
        expandAnim?.cancel()
        expandAnim = null
        handler.removeCallbacks(collapseRunnable)
        handler.removeCallbacks(ticker)
        // Ambil & kosongkan root + exiting dulu: cancel() memanggil withEndAction, yang tidak boleh masuk lagi ke sini.
        val old = root
        root = null
        exiting = false
        old?.let {
            runCatching { it.animate().cancel() }
            runCatching { wm.removeViewImmediate(it) }
        }
        card = null
        mini = null
        big = null
        miniArt = null
        bigArt = null
        miniTitle = null
        bigTitle = null
        bigArtist = null
        controls = null
        miniEq = null
        bigEq = null
        seek = null
        elapsed = null
        total = null
        playBtn = null
        prevBtn = null
        nextBtn = null
        handle = null
        pullAnim?.cancel()
        pull = 0f
        exiting = false
        expanded = false
        fraction = 0f
    }

    // =================================================================== bangun tampilan

    private fun computeSizes() {
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        baseW = minOf(screenW - dp(24), dp(400))
        // Tanpa tombol kontrol (baris 52dp) kartu besar lebih pendek.
        baseH = if (showButtons.value) dp(184) else dp(132)
        val wantW = dp(cardWidth.value).coerceAtLeast(dp(240)).coerceAtMost(baseW)
        sizeScale = wantW.toFloat() / baseW
        expW = wantW
        expH = (baseH * sizeScale).toInt()
        miniH = dp(40)
        miniW = if (isCircle()) miniH else minOf(dp(196), expW)
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24)
        topOffset = statusBar + dp(6)
    }

    private fun buildViews(): IslandRoot {
        val c = context
        val r = IslandRoot(c)
        val cd = IslandCard(c)

        // ---- mini
        val mi = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
        }
        val mArt = ImageView(c).apply {
            artStyle(dp(7).toFloat())
            // Bentuk Bulat: logo ikut dipotong bulat; Persegi membulat: sudut 7dp seperti biasa.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = if (isCircle()) minOf(view.width, view.height) / 2f else dp(7).toFloat()
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
        }
        mi.addView(mArt, LinearLayout.LayoutParams(dp(24), dp(24)))
        val mTitle = TextView(c).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        mi.addView(
            mTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            },
        )
        val mEq = EqualizerView(c, audioLevels)
        mi.addView(mEq, LinearLayout.LayoutParams(dp(16), dp(14)))

        // ---- besar
        val bg = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bArt = ImageView(c).apply {
            artStyle(dp(12).toFloat())
            setOnClickListener { openApp() }
        }
        head.addView(bArt, LinearLayout.LayoutParams(dp(52), dp(52)))
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { openApp() }
        }
        val bTitle = TextView(c).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
        }
        val bArtist = TextView(c).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(bTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        texts.addView(bArtist, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(10)
            },
        )
        val bEq = EqualizerView(c, audioLevels)
        head.addView(bEq, LinearLayout.LayoutParams(dp(20), dp(18)))
        bg.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val sk = SeekBarView(c).apply {
            onDrag = { f -> elapsed?.text = fmt((f * durationMs()).toLong()) }
            onSeek = { f ->
                val d = durationMs()
                if (d > 0) player.seekTo((f * d).toLong())
            }
        }
        bg.addView(
            sk,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)).apply { topMargin = dp(8) },
        )

        val timesRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val el = TextView(c).apply { styleTime() }
        val tt = TextView(c).apply { styleTime() }
        timesRow.addView(el, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        timesRow.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        timesRow.addView(tt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bg.addView(timesRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val controls = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = ImageView(c).apply {
            setImageResource(R.drawable.ic_island_prev)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            pressFeedback()
            setOnClickListener { player.seekToPrevious() }
        }
        val play = ImageView(c).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            pressFeedback()
            setOnClickListener { togglePlay() }
        }
        val next = ImageView(c).apply {
            setImageResource(R.drawable.ic_island_next)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            pressFeedback()
            setOnClickListener { player.seekToNextMediaItem() }
        }
        controls.addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)))
        controls.addView(
            play,
            LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginStart = dp(22)
                marginEnd = dp(22)
            },
        )
        controls.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)))
        bg.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        this.controls = controls

        cd.addView(mi)
        cd.addView(bg)
        // Garis pegangan seperti bottom sheet: tanda bahwa kartu besar bisa ditarik ke bawah.
        val hd = View(c).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2f).toFloat()
                setColor(0x59FFFFFF)
            }
        }
        cd.addView(
            hd,
            FrameLayout.LayoutParams(dp(36), dp(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) },
        )
        handle = hd
        cd.dragEnabled = { expanded }
        cd.onSwipeAway = { collapse() }
        // Kartu kecil disapu kasar: ke atas = tutup island, ke bawah = buka kartu besar.
        val closeIsland = {
            safe {
                swipeDismissedFor = player.currentMediaItem?.mediaId
                refresh()
            }
        }
        cd.onMiniFlingUp = closeIsland
        // Kartu besar disapu kasar ke atas: langsung tutup island (sapuan pelan hanya mengecilkan).
        cd.onBigFlingUp = closeIsland
        cd.onMiniFlingDown = { safe { expand() } }
        cd.onPull = { dy ->
            pullAnim?.cancel()
            pull = (dy * 0.45f).coerceIn(0f, dp(70f).toFloat())
            applyFraction(fraction)
        }
        cd.onPullEnd = { dy ->
            if (dy >= dp(70f)) openApp() else releasePull()
        }
        cd.setOnClickListener { if (!expanded) expand() }
        r.addView(cd)

        r.onOutside = { if (expanded) collapse() }
        r.onInteract = { if (expanded) scheduleCollapse() }

        card = cd
        mini = mi
        big = bg
        miniArt = mArt
        bigArt = bArt
        miniTitle = mTitle
        bigTitle = bTitle
        bigArtist = bArtist
        miniEq = mEq
        bigEq = bEq
        seek = sk
        elapsed = el
        total = tt
        playBtn = play
        prevBtn = prev
        nextBtn = next
        applySizes()
        applyMiniShape()
        applyContentVisibility()
        applyStyle()
        return r
    }

    /** Ukuran teks bawaan (sp) dikalikan skala pilihan: judul mini 12, judul besar 16, artis 13, waktu 11. */
    private fun applyTextSize() {
        val s = textScale.value
        miniTitle?.textSize = 12f * s
        bigTitle?.textSize = 16f * s
        bigArtist?.textSize = 13f * s
        elapsed?.textSize = 11f * s
        total?.textSize = 11f * s
    }

    /** Ikon putar / jeda sesuai gaya tombol aktif. */
    private fun playRes(active: Boolean): Int {
        val outline = buttonStyle.value == IslandButtonStyle.Outline
        return when {
            active && outline -> R.drawable.ic_island_pause_outline
            active -> R.drawable.ic_island_pause
            outline -> R.drawable.ic_island_play_outline
            else -> R.drawable.ic_island_play
        }
    }

    private fun isCircle() = shape.value == IslandShape.Circle

    /** Bentuk Bulat: hanya logo di tengah (judul & equalizer disembunyikan); Persegi membulat: tata letak biasa. */
    private fun applyMiniShape() {
        val circle = isCircle()
        mini?.apply {
            gravity = if (circle) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(if (circle) 0 else dp(8), 0, if (circle) 0 else dp(12), 0)
        }
        // Nama lagu mati: sembunyikan tapi tetap makan ruang supaya equalizer tetap di kanan.
        miniTitle?.visibility = if (circle) View.GONE else if (showTitle.value) View.VISIBLE else View.INVISIBLE
        miniEq?.visibility = if (circle) View.GONE else View.VISIBLE
        val artSize = dp(if (circle) 28 else 24)
        miniArt?.layoutParams = LinearLayout.LayoutParams(artSize, artSize)
        miniArt?.invalidateOutline()
    }

    /** Tombol kontrol, nama artis, dan nama lagu (kartu besar) mengikuti pengaturan tampil/sembunyi. */
    private fun applyContentVisibility() {
        controls?.visibility = if (showButtons.value) View.VISIBLE else View.GONE
        bigArtist?.visibility = if (showArtist.value) View.VISIBLE else View.GONE
        bigTitle?.visibility = if (showTitle.value) View.VISIBLE else View.GONE
    }

    private fun applySizes() {
        val cd = card ?: return
        mini?.layoutParams = FrameLayout.LayoutParams(miniW, miniH, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        // Tata letak kartu besar tetap berukuran dasar, lalu diskalakan dari tengah-atas supaya semua isinya ikut.
        big?.layoutParams = FrameLayout.LayoutParams(baseW, baseH, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        big?.pivotX = baseW / 2f
        big?.pivotY = 0f
        big?.scaleX = sizeScale
        big?.scaleY = sizeScale
        cd.layoutParams = FrameLayout.LayoutParams(miniW, miniH, Gravity.CENTER_HORIZONTAL)
    }

    private fun ImageView.artStyle(radius: Float) {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0x24FFFFFF)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    private fun TextView.styleTime() {
        setTextColor(0x99FFFFFF.toInt())
        textSize = 11f
        maxLines = 1
    }

    /** Tombol mengecil sedikit saat ditekan (tidak menelan klik). */
    private fun View.pressFeedback() {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    // =================================================================== animasi besar <-> kecil

    private fun expand() {
        if (expanded || !attached) return
        expanded = true
        pullAnim?.cancel()
        pull = 0f
        updateProgress()
        animateTo(1f, 460, OvershootInterpolator(0.85f)) { restartMarquee() }
        scheduleCollapse()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 250)
    }

    private fun collapse(animate: Boolean = true) {
        if (!expanded) return
        expanded = false
        handler.removeCallbacks(collapseRunnable)
        handler.removeCallbacks(ticker)
        if (animate) {
            animateTo(0f, 320, PathInterpolator(0.3f, 0f, 0.1f, 1f)) { pull = 0f }
        } else {
            expandAnim?.cancel()
            pull = 0f
            applyFraction(0f)
        }
    }

    private fun scheduleCollapse() {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, (collapseSec.value * 1000f).toLong())
    }

    private fun animateTo(target: Float, durationMs: Long, interpolator: TimeInterpolator, onEnd: (() -> Unit)?) {
        expandAnim?.cancel()
        val a = ValueAnimator.ofFloat(fraction, target)
        a.duration = durationMs
        a.interpolator = interpolator
        a.addUpdateListener { safe { applyFraction(it.animatedValue as Float) } }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!cancelled) safe { onEnd?.invoke() }
            }
        })
        expandAnim = a
        a.start()
    }

    /** f = 0 mini, f = 1 besar (boleh sedikit lebih dari 1 saat pantulan). */
    private fun applyFraction(f: Float) {
        fraction = f
        val cd = card ?: return
        val lp = cd.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = lerp(miniW.toFloat(), expW.toFloat(), f).toInt().coerceAtLeast(dp(40))
        lp.height = (lerp(miniH.toFloat(), expH.toFloat(), f) + pull * f.coerceIn(0f, 1f)).toInt().coerceAtLeast(dp(24))
        cd.layoutParams = lp
        cd.radius = lerp(miniR, bigR, f.coerceIn(0f, 1f))

        val a1 = (1f - f * 3.2f).coerceIn(0f, 1f)
        mini?.alpha = a1
        mini?.visibility = if (a1 <= 0f) View.GONE else View.VISIBLE
        val a2 = ((f - 0.3f) / 0.5f).coerceIn(0f, 1f)
        big?.alpha = a2
        big?.visibility = if (a2 <= 0f) View.GONE else View.VISIBLE
        handle?.alpha = a2
        handle?.visibility = if (a2 <= 0f) View.GONE else View.VISIBLE
    }

    /** Lepas tarikan yang kurang jauh: kartu kembali ke tinggi semula. */
    private fun releasePull() {
        pullAnim?.cancel()
        val a = ValueAnimator.ofFloat(pull, 0f)
        a.duration = 260
        a.interpolator = OvershootInterpolator(1.1f)
        a.addUpdateListener {
            safe {
                pull = (it.animatedValue as Float).coerceAtLeast(0f)
                applyFraction(fraction)
            }
        }
        pullAnim = a
        a.start()
    }

    private fun restartMarquee() {
        val t = bigTitle ?: return
        t.isSelected = false
        t.isSelected = true
    }

    // =================================================================== data lagu

    private fun bind() {
        val item = player.currentMediaItem ?: return
        val md = item.mediaMetadata
        val title = md.title?.toString().orEmpty()
        val artist = md.artist?.toString().orEmpty()
        if (miniTitle?.text?.toString() != title) {
            miniTitle?.text = title
            bigTitle?.text = title
            if (expanded) restartMarquee()
        }
        if (bigArtist?.text?.toString() != artist) bigArtist?.text = artist
        loadArt(md.artworkUri?.toString())

        val active = isActive()
        miniEq?.playing = active
        bigEq?.playing = active
        if (shownPlaying != active) {
            shownPlaying = active
            playBtn?.setImageResource(playRes(active))
            playBtn?.let { it.scaleX = 0.8f; it.scaleY = 0.8f; it.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }
        }
        if (expanded) updateProgress() else total?.text = fmt(durationMs())
    }

    private fun durationMs(): Long {
        val d = player.duration
        if (d != C.TIME_UNSET && d > 0) return d
        return player.currentMediaItem?.mediaMetadata?.durationMs ?: 0L
    }

    private fun updateProgress() {
        val dur = durationMs()
        val pos = player.currentPosition.coerceAtLeast(0L).let { if (dur > 0) it.coerceAtMost(dur) else it }
        val sk = seek ?: return
        if (!sk.dragging) {
            sk.progress = if (dur > 0) pos.toFloat() / dur else 0f
            elapsed?.text = fmt(pos)
        }
        total?.text = fmt(dur)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun loadArt(url: String?) {
        if (url == artUrl && miniArt != null) return
        artUrl = url
        for (iv in listOfNotNull(miniArt, bigArt)) {
            if (url == null) {
                iv.setImageDrawable(null)
            } else {
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context).data(url).size(200).crossfade(true).target(iv).build(),
                )
            }
        }
        scope.launch {
            val p = runCatching { PaletteExtractor.load(context, url) }.getOrNull()
            if (artUrl == url && root != null) {
                safe {
                    palette = p ?: SongPalette.Default
                    applyPalette(palette)
                }
            }
        }
    }

    /** Terapkan gaya aktif (iPhone / Kaca / Biasa): warna latar, teks, ikon, sudut. */
    private fun applyStyle() {
        val dark = isDark()
        val l = islandLook(style.value, color.value, customColor.value, dark, blurActive(), density * corner.value)
        look = l
        miniR = if (isCircle()) miniH / 2f else l.miniR
        bigR = l.bigR
        miniTitle?.setTextColor(l.main)
        bigTitle?.setTextColor(l.main)
        bigArtist?.setTextColor(l.sub)
        elapsed?.setTextColor(l.time)
        total?.setTextColor(l.time)
        val outline = buttonStyle.value == IslandButtonStyle.Outline
        prevBtn?.setImageResource(if (outline) R.drawable.ic_island_prev_outline else R.drawable.ic_island_prev)
        nextBtn?.setImageResource(if (outline) R.drawable.ic_island_next_outline else R.drawable.ic_island_next)
        playBtn?.setImageResource(playRes(shownPlaying ?: isActive()))
        prevBtn?.setColorFilter(l.icon)
        nextBtn?.setColorFilter(l.icon)
        // Tanpa latar bulat di semua gaya: ikon putar memakai warna ikon biasa.
        (playBtn?.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
        playBtn?.setColorFilter(l.icon)
        seek?.track = l.track
        handle?.background = GradientDrawable().apply {
            cornerRadius = dp(2f).toFloat()
            setColor(l.handle)
        }
        card?.setStrokeColor(l.stroke)
        card?.alpha = opacity.value
        applyTextSize()
        applyPalette(palette)
    }

    /** Latar kartu mengikuti gaya; aksen seek bar & equalizer dari warna cover. */
    private fun applyPalette(p: SongPalette) {
        val l = look
        val (top, bottom) = islandBackground(l, p)
        card?.setColors(top, bottom)
        val raw = p.accent.toArgb()
        val accent = if (l.lightSurface) ColorUtils.blendARGB(raw, Color.BLACK, 0.5f) else raw
        seek?.accent = accent
        miniEq?.color = accent
        bigEq?.color = accent
    }

    /** True kalau gaya Kaca aktif dan sistem mengizinkan blur antar jendela (Android 12+, bukan hemat daya). */
    private fun blurActive(): Boolean =
        style.value == IslandStyle.Glass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)

    /** Gaya Kaca: minta sistem mengaburkan isi di belakang jendela island. */
    private fun applyWindowBlur() {
        val r = root ?: return
        val lp = r.layoutParams as? WindowManager.LayoutParams ?: return
        val on = blurActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) lp.blurBehindRadius = if (on) dp(28) else 0
        lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        else lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        runCatching { wm.updateViewLayout(r, lp) }
    }

    // =================================================================== aksi

    private fun togglePlay() {
        if (isActive()) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    private fun openApp() {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        runCatching { context.startActivity(i) }
        collapse(animate = false)
    }

    // =================================================================== util

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
}

// ======================================================================= gaya

/** Warna & bentuk kartu untuk satu gaya. [lightSurface] = permukaan terang (teks gelap). */
internal sealed class Look(
    val main: Int,
    val sub: Int,
    val time: Int,
    val icon: Int,
    val playBg: Int,
    val playIcon: Int,
    val track: Int,
    val handle: Int,
    val stroke: Int,
    val miniR: Float,
    val bigR: Float,
    val lightSurface: Boolean,
) {
    class IPhone(miniR: Float, bigR: Float) : Look(
        Color.WHITE, 0xB3FFFFFF.toInt(), 0x99FFFFFF.toInt(), Color.WHITE, Color.WHITE, 0xFF111111.toInt(),
        0x38FFFFFF, 0x59FFFFFF, 0x24FFFFFF, miniR, bigR, false,
    )

    class Glass(val dark: Boolean, val blur: Boolean, miniR: Float, bigR: Float) : Look(
        if (dark) Color.WHITE else 0xFF111114.toInt(),
        if (dark) 0xCCFFFFFF.toInt() else 0xB3111114.toInt(),
        if (dark) 0xA6FFFFFF.toInt() else 0x99111114.toInt(),
        if (dark) Color.WHITE else 0xFF111114.toInt(),
        if (dark) 0xF2FFFFFF.toInt() else 0xFF111114.toInt(),
        if (dark) 0xFF111111.toInt() else Color.WHITE,
        if (dark) 0x40FFFFFF else 0x2E000000,
        if (dark) 0x66FFFFFF else 0x4D000000,
        if (dark) 0x40FFFFFF else 0x99FFFFFF.toInt(),
        miniR, bigR, !dark,
    )

    /** Warna dasar sendiri ([base], mis. Putih / Kustom); teks hitam atau putih mengikuti terang-gelapnya. */
    class Tint(val base: Int, val glass: Boolean, val blur: Boolean, miniR: Float, bigR: Float) : Look(
        if (isLightColor(base)) 0xFF111114.toInt() else Color.WHITE,
        if (isLightColor(base)) 0xB3111114.toInt() else 0xB3FFFFFF.toInt(),
        if (isLightColor(base)) 0x99111114.toInt() else 0x99FFFFFF.toInt(),
        if (isLightColor(base)) 0xFF111114.toInt() else Color.WHITE,
        if (isLightColor(base)) 0xFF111114.toInt() else Color.WHITE,
        if (isLightColor(base)) Color.WHITE else 0xFF111111.toInt(),
        if (isLightColor(base)) 0x2E000000 else 0x38FFFFFF,
        if (isLightColor(base)) 0x40000000 else 0x59FFFFFF,
        if (isLightColor(base)) 0x1F000000 else 0x24FFFFFF,
        miniR, bigR, isLightColor(base),
    )

    class Normal(val dark: Boolean, miniR: Float, bigR: Float) : Look(
        if (dark) Color.WHITE else 0xFF111114.toInt(),
        if (dark) 0xB3FFFFFF.toInt() else 0xFF5C5C66.toInt(),
        if (dark) 0x99FFFFFF.toInt() else 0xFF7A7A85.toInt(),
        if (dark) Color.WHITE else 0xFF111114.toInt(),
        if (dark) Color.WHITE else 0xFF111114.toInt(),
        if (dark) 0xFF111111.toInt() else Color.WHITE,
        if (dark) 0x38FFFFFF else 0x2E000000,
        if (dark) 0x59FFFFFF else 0x40000000,
        if (dark) 0x1FFFFFFF else 0x14000000,
        miniR, bigR, !dark,
    )
}

/** Warna atas & bawah latar kartu untuk gaya [l] dan palet cover [p]. Dipakai overlay dan pratinjau di pengaturan. */
internal fun islandBackground(l: Look, p: SongPalette): Pair<Int, Int> = when (l) {
    is Look.IPhone -> Pair(
        ColorUtils.blendARGB(p.top.toArgb(), Color.BLACK, 0.45f),
        ColorUtils.blendARGB(p.bottom.toArgb(), Color.BLACK, 0.7f),
    )
    is Look.Glass -> {
        val base = if (l.dark) Color.BLACK else Color.WHITE
        val mix = if (l.dark) 0.55f else 0.78f
        val a = if (l.dark) (if (l.blur) 0x8C else 0xE0) else (if (l.blur) 0x99 else 0xEB)
        Pair(
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(p.top.toArgb(), base, mix), a),
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(p.bottom.toArgb(), base, mix + 0.1f), a),
        )
    }
    is Look.Normal -> Pair(
        if (l.dark) 0xFF2E2E34.toInt() else 0xFFF6F6FA.toInt(),
        if (l.dark) 0xFF26262B.toInt() else 0xFFEBEBF0.toInt(),
    )
    is Look.Tint -> {
        val a = if (l.glass) (if (l.blur) 0xA6 else 0xEB) else 0xFF
        Pair(
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(l.base, Color.WHITE, 0.05f), a),
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(l.base, Color.BLACK, 0.08f), a),
        )
    }
}

/**
 * Bentuk [Look] dari gaya + warna pilihan. Default = warna bawaan gaya; Putih / Kustom = warna dasar sendiri
 * dengan teks hitam atau putih (otomatis dari terang-gelapnya warna). [scale] mengalikan radius sudut
 * (kepadatan layar untuk overlay, 1f untuk pratinjau Compose yang memakai dp).
 */
internal fun islandLook(
    style: IslandStyle,
    color: IslandColor,
    custom: Int,
    dark: Boolean,
    blur: Boolean,
    scale: Float,
): Look {
    val (mr, br) = when (style) {
        IslandStyle.IPhone -> 14f to 28f
        IslandStyle.Glass -> 20f to 30f
        IslandStyle.Normal -> 12f to 20f
    }
    val miniR = mr * scale
    val bigR = br * scale
    val base = when (color) {
        IslandColor.Default -> null
        IslandColor.White -> Color.WHITE
        IslandColor.Custom -> custom or 0xFF000000.toInt()
    }
    if (base != null) return Look.Tint(base, style == IslandStyle.Glass, blur && style == IslandStyle.Glass, miniR, bigR)
    return when (style) {
        IslandStyle.IPhone -> Look.IPhone(miniR, bigR)
        IslandStyle.Glass -> Look.Glass(dark, blur, miniR, bigR)
        IslandStyle.Normal -> Look.Normal(dark, miniR, bigR)
    }
}

private fun isLightColor(c: Int) = ColorUtils.calculateLuminance(c) > 0.5

// ======================================================================= view khusus

/** Akar jendela: meneruskan "sentuhan di luar kartu" dan "sentuhan pada kartu". */
private class IslandRoot(ctx: Context) : FrameLayout(ctx) {
    var onOutside: (() -> Unit)? = null
    var onInteract: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return true
        }
        onInteract?.invoke()
        return super.dispatchTouchEvent(ev)
    }
}

/** Kartu hitam-kebiruan bersudut membulat; sudut dianimasikan lewat [radius]. */
private class IslandCard(ctx: Context) : FrameLayout(ctx) {
    private val strokeW = (ctx.resources.displayMetrics.density + 0.5f).toInt()
    private val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setColors(intArrayOf(0xFF1A1A1F.toInt(), 0xFF050506.toInt()))
        setStroke(strokeW, 0x24FFFFFF)
    }

    fun setStrokeColor(c: Int) {
        bg.setStroke(strokeW, c)
        invalidate()
    }

    var radius: Float = 0f
        set(v) {
            field = v
            bg.cornerRadius = v
            invalidateOutline()
        }

    // Tarik ke bawah: dihitung dari sentuhan pertama; hanya aktif saat kartu besar.
    var dragEnabled: () -> Boolean = { false }
    var onPull: ((Float) -> Unit)? = null
    var onPullEnd: ((Float) -> Unit)? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var pulling = false

    /** Dipanggil saat kartu besar digeser ke atas / kiri / kanan: kartu mengecil. */
    var onSwipeAway: (() -> Unit)? = null
    private var dismissed = false

    /** Kartu kecil disapu cepat (kasar) ke atas / ke bawah. */
    var onMiniFlingUp: (() -> Unit)? = null
    var onMiniFlingDown: (() -> Unit)? = null
    private var miniSwiping = false

    /** Kartu besar disapu cepat ke atas: selain mengecil, island ditutup. */
    var onBigFlingUp: (() -> Unit)? = null
    private var bigSwiped = false
    private var velocity: VelocityTracker? = null
    private val density = ctx.resources.displayMetrics.density

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            miniSwiping = false
            bigSwiped = false
        }
        velocity?.addMovement(ev)
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if ((miniSwiping || bigSwiped) && ev.actionMasked == MotionEvent.ACTION_UP) {
                val vt = velocity
                vt?.computeCurrentVelocity(1000)
                val vy = (vt?.yVelocity ?: 0f) / density // dp/detik
                val dy = (ev.y - downY) / density // dp
                // "Lumayan kasar": jarak minimal ~20dp dan kecepatan minimal ~900dp/detik.
                if (abs(dy) >= 20f && abs(vy) >= 900f) {
                    if (bigSwiped) {
                        if (dy < 0f && vy < 0f) onBigFlingUp?.invoke()
                    } else if (dy < 0f && vy < 0f) onMiniFlingUp?.invoke()
                    else if (dy > 0f && vy > 0f) onMiniFlingDown?.invoke()
                }
            }
            miniSwiping = false
            bigSwiped = false
            velocity?.recycle()
            velocity = null
        }
        return handled
    }

    /** 0 = belum jelas, 1 = tarik ke bawah, 2 = geser ke atas / samping. */
    private fun classify(e: MotionEvent): Int {
        val dx = e.x - downX
        val dy = e.y - downY
        if (!dragEnabled()) {
            // Kartu kecil: sapuan vertikal ditangkap di sini (tidak jadi ketukan).
            return if (abs(dy) > slop && abs(dy) > abs(dx)) 3 else 0
        }
        return when {
            dy > slop && dy > abs(dx) -> 1
            (-dy > slop && -dy > abs(dx)) || (abs(dx) > slop && abs(dx) > abs(dy)) -> 2
            else -> 0
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                pulling = false
                dismissed = false
            }
            MotionEvent.ACTION_MOVE -> if (!pulling && !dismissed && !miniSwiping) {
                when (classify(e)) {
                    1 -> pulling = true
                    2 -> {
                        dismissed = true
                        bigSwiped = true
                        onSwipeAway?.invoke()
                    }
                    3 -> miniSwiping = true
                }
            }
        }
        return pulling || dismissed || miniSwiping
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                pulling = false
                dismissed = false
            }
            MotionEvent.ACTION_MOVE -> if (!pulling && !dismissed && !miniSwiping) {
                val k = classify(e)
                if (k != 0) {
                    // Batalkan "klik" yang sedang menunggu supaya gerakan ini tidak ikut jadi ketukan.
                    val c = MotionEvent.obtain(e)
                    c.action = MotionEvent.ACTION_CANCEL
                    super.onTouchEvent(c)
                    c.recycle()
                    when (k) {
                        1 -> pulling = true
                        3 -> miniSwiping = true
                        else -> {
                            dismissed = true
                            bigSwiped = true
                            onSwipeAway?.invoke()
                        }
                    }
                }
            }
        }
        if (miniSwiping) return true
        if (dismissed) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) dismissed = false
            return true
        }
        if (pulling) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> onPull?.invoke(e.y - downY)
                MotionEvent.ACTION_UP -> {
                    pulling = false
                    onPullEnd?.invoke(e.y - downY)
                }
                MotionEvent.ACTION_CANCEL -> {
                    pulling = false
                    onPullEnd?.invoke(0f)
                }
            }
            return true
        }
        return super.onTouchEvent(e)
    }

    init {
        background = bg
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    fun setColors(top: Int, bottom: Int) {
        bg.setColors(intArrayOf(top, bottom))
        invalidate()
    }
}

/**
 * Empat batang yang bergerak mengikuti suara lagu yang SEBENARNYA: tiap batang = satu pita frekuensi
 * (bass -> treble) dari [AudioLevels]; makin keras bagian itu di lagu, makin tinggi batangnya.
 * Diam rendah saat dijeda.
 */
private class EqualizerView(ctx: Context, private val levels: AudioLevels) : View(ctx) {
    var color: Int = Color.WHITE
        set(v) {
            field = v
            paint.color = v
            invalidate()
        }
    var playing: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            if (v && isAttachedToWindow) anim.start() else if (!v) {
                anim.cancel()
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private val target = FloatArray(AudioLevels.BANDS)
    private val shown = FloatArray(AudioLevels.BANDS)

    // Hanya sebagai "detak" per-frame untuk menggambar ulang; tinggi batang murni dari level suara asli.
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val n = AudioLevels.BANDS
        val gap = width * 0.12f
        val barW = (width - gap * (n - 1)) / n
        // Level nyata saat ini (0 kalau belum ada data / hening), dihaluskan: naik cepat, turun pelan.
        if (playing) {
            if (!levels.read(android.os.SystemClock.uptimeMillis(), target)) target.fill(0f)
        } else {
            target.fill(0f)
        }
        for (i in 0 until n) {
            val k = if (target[i] > shown[i]) 0.55f else 0.16f
            shown[i] += (target[i] - shown[i]) * k
            val level = if (playing) 0.16f + 0.84f * shown[i] else 0.22f
            val h = height * level
            val x = i * (barW + gap)
            rect.set(x, (height - h) / 2f, x + barW, (height + h) / 2f)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }
}

/** Seek bar tipis yang bisa digeser; titik muncul saat disentuh. */
private class SeekBarView(ctx: Context) : View(ctx) {
    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var accent: Int = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }
    var dragging = false
        private set
    var onSeek: ((Float) -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null

    private val d = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var grabbed = false
    var track: Int = 0x38FFFFFF
        set(v) {
            field = v
            trackPaint.color = v
            invalidate()
        }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = (if (dragging) 6f else 4f) * d
        val cy = height / 2f
        rect.set(0f, cy - h / 2, width.toFloat(), cy + h / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, trackPaint)
        fill.color = accent
        val x = width * progress
        rect.set(0f, cy - h / 2, x, cy + h / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        if (dragging) canvas.drawCircle(x, cy, 6f * d, fill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // Sentuhan yang mulai di seek bar milik seek bar: kartu besar tidak boleh ikut menangkap geseran
            // mendatar dan mengecil (itu hanya untuk geseran di luar seek bar). Ketukan biasa tetap tidak
            // memindahkan lagu; harus digeser mendatar.
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                grabbed = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && grabbed) {
                    val dx = kotlin.math.abs(e.x - downX)
                    val dy = kotlin.math.abs(e.y - downY)
                    if (dy > slop && dy >= dx) {
                        // Gerakan menegak lebih dulu: bukan geseran seek. Serahkan ke kartu (tarik ke bawah / ke atas).
                        grabbed = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                    } else if (dx > slop && dx > dy) {
                        dragging = true
                        invalidate()
                    }
                }
                if (dragging) move(e.x)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    progress = (e.x / width).coerceIn(0f, 1f)
                    onSeek?.invoke(progress)
                }
                grabbed = false
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                grabbed = false
                invalidate()
            }
        }
        return true
    }

    private fun move(x: Float) {
        if (width <= 0) return
        progress = (x / width).coerceIn(0f, 1f)
        onDrag?.invoke(progress)
    }
}
