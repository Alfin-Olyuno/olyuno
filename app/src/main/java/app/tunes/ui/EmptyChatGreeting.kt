package app.tunes.ui

import app.tunes.i18n.t

/**
 * Sapaan di tengah layar saat obrolan Olyuno AI masih kosong. Tiap bagian hari ([DayPart], menurut jam perangkat)
 * punya beberapa kalimat sendiri, ditambah beberapa sapaan umum yang cocok kapan saja. Teks diterjemahkan lewat [t].
 */
private val GREETINGS: Map<DayPart?, List<String>> = mapOf(
    DayPart.Pagi to listOf(
        "Pagi! Mau dengerin apa?",
        "Mulai hari dengan lagu favorit?",
        "Udah sarapan? Yuk cari lagu teman sarapan",
        "Pagi yang pas buat lagu bagus",
        "Hai, semoga harimu menyenangkan. Mau musik apa?",
        "Butuh lagu penyemangat pagi?",
    ),
    DayPart.Siang to listOf(
        "Siang! Butuh lagu buat semangat?",
        "Lagi fokus kerja? Aku bisa carikan lagunya",
        "Jeda sebentar? Yuk cari lagu santai",
        "Siang-siang gini enaknya lagu apa ya?",
        "Halo, lagi cari lagu apa siang ini?",
        "Mau musik buat teman istirahat siang?",
    ),
    DayPart.Sore to listOf(
        "Sore! Waktunya lagu santai",
        "Mau ditemani lagu apa sore ini?",
        "Pulang kerja enaknya dengerin apa?",
        "Sore yang tenang, cocok buat lagu syahdu",
        "Hai, gimana harimu? Mau lagu apa?",
        "Yuk cari lagu buat menemani sore",
    ),
    DayPart.Malam to listOf(
        "Malam! Mau lagu apa buat menemani?",
        "Saatnya musik yang menenangkan",
        "Malam ini enaknya dengerin apa?",
        "Capek seharian? Yuk cari lagu tenang",
        "Hai, mau dengerin apa sebelum istirahat?",
        "Malam yang pas buat lagu favoritmu",
    ),
    DayPart.LarutMalam to listOf(
        "Belum tidur? Aku temani dengan lagu",
        "Larut malam begini, lagu apa yang pas?",
        "Masih terjaga? Yuk cari lagu pengantar tidur",
        "Tengah malam, waktunya lagu yang tenang",
        "Susah tidur? Coba lagu yang menenangkan",
        "Sepi-sepi gini, mau dengerin apa?",
    ),
    null to listOf(
        "Halo! Mau dengerin apa hari ini?",
        "Hai, ada lagu yang lagi kamu cari?",
        "Tanya apa saja soal musik",
        "Siap bantu cari lagu buat kamu",
    ),
)

/**
 * Pilih satu sapaan untuk [part] dari kumpulan khusus bagian hari itu + sapaan umum. [seed] tetap selama satu
 * obrolan, jadi sapaannya tidak berganti-ganti sendiri di layar; obrolan baru = seed baru = sapaan lain.
 */
fun emptyChatGreeting(part: DayPart, seed: Int): String {
    val pool = GREETINGS.getValue(part) + GREETINGS.getValue(null)
    return t(pool[Math.floorMod(seed, pool.size)])
}
