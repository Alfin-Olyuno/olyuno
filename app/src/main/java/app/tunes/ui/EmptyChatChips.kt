package app.tunes.ui

import app.tunes.i18n.t
import kotlin.random.Random

/**
 * Chip pertanyaan di obrolan Olyuno AI yang masih kosong. Sebagian chip mengikuti jam perangkat ([DayPart]), sebagian
 * lagi umum (tidak bergantung jam), jadi tidak semuanya berubah mengikuti waktu. Ketuk chip = pertanyaannya langsung dikirim.
 */
private val TIME_CHIPS: Map<DayPart, List<String>> = mapOf(
    DayPart.Pagi to listOf(
        "Lagu penyemangat buat pagi ini",
        "Musik santai teman sarapan",
        "Buatkan playlist buat mulai hari",
        "Lagu ceria biar semangat",
        "Lagu buat olahraga pagi",
    ),
    DayPart.Siang to listOf(
        "Lagu buat fokus kerja",
        "Lagu semangat biar nggak ngantuk",
        "Musik santai buat istirahat siang",
        "Lagu instrumental buat belajar",
        "Playlist teman makan siang",
    ),
    DayPart.Sore to listOf(
        "Lagu santai buat sore ini",
        "Lagu enak buat perjalanan pulang",
        "Lagu syahdu buat senja",
        "Playlist teman ngopi sore",
        "Lagu buat olahraga sore",
    ),
    DayPart.Malam to listOf(
        "Rekomendasikan lagu buat malam ini",
        "Lagu tenang buat malam ini",
        "Lagu akustik buat malam",
        "Lagu romantis buat malam",
        "Musik santai sebelum istirahat",
    ),
    DayPart.LarutMalam to listOf(
        "Lagu pengantar tidur",
        "Musik tenang biar cepat tidur",
        "Lagu buat teman begadang",
        "Lagu lo-fi buat malam sepi",
        "Lagu yang menenangkan pikiran",
    ),
)

private val GENERAL_CHIPS: List<String> = listOf(
    "Olyuno ada fitur apa saja?",
    "Carikan lagu galau yang enak didengar",
    "Lagu apa yang lagi hits sekarang?",
    "Buatkan playlist untuk santai di rumah",
    "Rekomendasikan artis baru yang mungkin kusukai",
    "Carikan lagu nostalgia yang enak didengar",
    "Carikan lagu akustik yang menenangkan",
    "Carikan lagu yang lagi viral",
)

/** Jumlah chip yang tampil. */
const val EMPTY_CHAT_CHIP_COUNT = 3

/**
 * Tiga chip: satu menurut bagian hari di perangkat ([part]) dan dua umum, urutannya diacak. [seed] tetap selama satu
 * obrolan (chip tidak berganti sendiri); obrolan baru = seed baru = pilihan lain.
 */
fun emptyChatChips(part: DayPart, seed: Int): List<String> {
    val rnd = Random(seed)
    val byTime = TIME_CHIPS.getValue(part).random(rnd)
    val general = GENERAL_CHIPS.shuffled(rnd).take(EMPTY_CHAT_CHIP_COUNT - 1)
    return (listOf(byTime) + general).shuffled(rnd).map { t(it) }
}
