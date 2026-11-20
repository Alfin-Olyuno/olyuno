package app.tunes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Satu bagian isi pesan informasi. */
sealed interface InfoBlock {
    data class Heading(val text: String) : InfoBlock
    data class Paragraph(val text: String) : InfoBlock
    /** Butir daftar; [label] ditebalkan, [text] menjelaskan. */
    data class Bullet(val label: String, val text: String) : InfoBlock
}

data class InfoMessage(
    val id: String,
    val title: String,
    /** Cuplikan satu baris di daftar. */
    val preview: String,
    val blocks: List<InfoBlock>,
)

/**
 * Daftar pesan informasi (terbaru paling atas). Untuk menambah pengumuman baru cukup tambahkan satu
 * [InfoMessage] dengan id baru di paling atas; pengguna akan melihat tanda angka sampai membukanya.
 */
val INFO_MESSAGES: List<InfoMessage> = listOf(
    InfoMessage(
        id = "welcome",
        title = "Selamat Datang di Olyuno!",
        preview = "Terima kasih telah memilih Olyuno sebagai aplikasi musikmu.",
        blocks = listOf(
            InfoBlock.Paragraph("Halo, Olyners!"),
            InfoBlock.Paragraph("Terima kasih telah memilih Olyuno sebagai aplikasi musikmu."),
            InfoBlock.Paragraph("Olyuno hadir untuk memberikan pengalaman mendengarkan musik yang sederhana, nyaman, dan mudah digunakan."),
            InfoBlock.Heading("Fitur Olyuno"),
            InfoBlock.Bullet("Temukan", "Jelajahi berbagai pilihan musik."),
            InfoBlock.Bullet("Pencarian", "Cari lagu dan artis favoritmu."),
            InfoBlock.Bullet("Koleksi", "Kelola lagu pilihanmu."),
            InfoBlock.Bullet("Disukai", "Simpan lagu favorit."),
            InfoBlock.Bullet("Riwayat", "Temukan kembali lagu yang pernah diputar."),
            InfoBlock.Heading("Terus Berkembang"),
            InfoBlock.Paragraph("Olyuno akan terus diperbarui dan dikembangkan untuk meningkatkan kualitas serta kenyamanan penggunaan."),
            InfoBlock.Paragraph("Jika menemukan kendala atau memiliki saran, kami terbuka terhadap masukanmu."),
            InfoBlock.Paragraph("Terima kasih telah menjadi bagian dari Olyuno."),
            InfoBlock.Paragraph("Selamat menikmati musik!"),
            InfoBlock.Paragraph("Dari Olyuno."),
        ),
    ),
)

/** Menyimpan id pesan informasi yang sudah dibuka (SharedPreferences). Pesan baru otomatis berstatus belum dibaca. */
class InfoStore(context: Context) {
    private val prefs = context.getSharedPreferences("info", Context.MODE_PRIVATE)

    private val _read = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val readIds: StateFlow<Set<String>> = _read.asStateFlow()

    fun markRead(id: String) {
        val cur = _read.value
        if (id in cur) return
        val next = cur + id
        prefs.edit().putStringSet(KEY, next).apply()
        _read.value = next
    }

    private companion object {
        const val KEY = "read_ids"
    }
}
