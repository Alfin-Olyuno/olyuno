package app.tunes.innertube

import kotlinx.serialization.json.JsonPrimitive

/**
 * Konfigurasi client InnerTube.
 *
 * Kalau pemutaran tiba-tiba gagal (playabilityStatus bukan OK / HTTP 4xx), biasanya yang perlu
 * diperbarui hanya file ini: clientVersion dan userAgent. Cek nilai terbaru di proyek seperti
 * yt-dlp, NewPipeExtractor, atau Metrolist.
 */
data class ClientConfig(
    val name: String,
    val version: String,
    val id: Int,
    val userAgent: String,
    val host: String = "www.youtube.com",
    val extra: Map<String, JsonPrimitive> = emptyMap(),
)

object Clients {

    /** Dipakai untuk search / next (metadata saja, bukan streaming). */
    val WEB_REMIX = ClientConfig(
        name = "WEB_REMIX",
        version = "1.20250310.01.00",
        id = 67,
        userAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
        host = "music.youtube.com",
    )

    /**
     * Jalur utama untuk URL audio. Per catatan yt-dlp, client ini tidak butuh PO Token untuk
     * streaming; batasannya: video "made for kids" tidak tersedia.
     */
    val ANDROID_VR = ClientConfig(
        name = "ANDROID_VR",
        version = "1.61.48",
        id = 28,
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 " +
            "(Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
        extra = mapOf(
            "androidSdkVersion" to JsonPrimitive(32),
            "osName" to JsonPrimitive("Android"),
            "osVersion" to JsonPrimitive("12"),
            "deviceMake" to JsonPrimitive("Oculus"),
            "deviceModel" to JsonPrimitive("Quest 3"),
        ),
    )

    /** Cadangan. Bisa butuh PO Token, jadi sering gagal; dibiarkan sebagai fallback murah. */
    val IOS = ClientConfig(
        name = "IOS",
        version = "20.10.4",
        id = 5,
        userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        extra = mapOf(
            "deviceMake" to JsonPrimitive("Apple"),
            "deviceModel" to JsonPrimitive("iPhone16,2"),
            "osName" to JsonPrimitive("iPhone"),
            "osVersion" to JsonPrimitive("18.3.2.22D82"),
        ),
    )

    /**
     * Client visionOS (dipakai NewPipe/Spotube sebagai jalan keluar dari 403 SABR di ANDROID/IOS).
     * Nilai versi belum diverifikasi di perangkat; kalau ditolak, resolver lanjut ke client berikutnya.
     */
    val VISIONOS = ClientConfig(
        name = "VISIONOS",
        version = "0.1",
        id = 101,
        userAgent = "com.google.ios.youtube/20.10.4 (RealityDevice14,1; U; CPU iOS 17_0 like Mac OS X;)",
        host = "youtubei.googleapis.com",
        extra = mapOf(
            "deviceMake" to JsonPrimitive("Apple"),
            "deviceModel" to JsonPrimitive("RealityDevice14,1"),
            "osName" to JsonPrimitive("visionOS"),
            "osVersion" to JsonPrimitive("1.3.21O771"),
        ),
    )

    /** Urutan percobaan saat mengambil URL stream. Tambah client baru di sini. */
    val PLAYER_CHAIN = listOf(VISIONOS, ANDROID_VR, IOS)
}
