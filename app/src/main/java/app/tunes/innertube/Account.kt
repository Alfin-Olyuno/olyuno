package app.tunes.innertube

import kotlinx.serialization.json.JsonArray
import java.security.MessageDigest

/** Nama dan foto akun Google yang sedang masuk. */
data class AccountInfo(val name: String, val photoUrl: String?)

private fun sha1Hex(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** Header login (cookie + SAPISIDHASH) untuk request yang bertindak atas nama akun. Null kalau cookie tidak lengkap. */
internal fun authHeaders(cookie: String): Map<String, String>? {
    val sapisid = Regex("(?:^|;\\s*)SAPISID=([^;]+)").find(cookie)?.groupValues?.get(1)
        ?: Regex("(?:^|;\\s*)__Secure-3PAPISID=([^;]+)").find(cookie)?.groupValues?.get(1)
        ?: return null
    val origin = "https://music.youtube.com"
    val ts = System.currentTimeMillis() / 1000
    return mapOf(
        "Cookie" to cookie,
        "Authorization" to "SAPISIDHASH ${ts}_${sha1Hex("$ts $sapisid $origin")}",
        "X-Goog-AuthUser" to "0",
        "X-Origin" to origin,
    )
}

/**
 * Ambil nama dan foto profil dari akun yang cookie-nya diberikan (hasil login lewat WebView).
 * Mengembalikan null kalau cookie tidak valid / sudah kedaluwarsa.
 */
suspend fun InnerTube.accountInfo(cookie: String): AccountInfo? {
    val headers = authHeaders(cookie) ?: return null
    val root = post(Clients.WEB_REMIX, "account/account_menu", headers) { }

    val header = root.findAll("activeAccountHeaderRenderer").firstOrNull() ?: return null
    val name = header.path("accountName", "runs").objects().joinToString("") { it["text"].text.orEmpty() }
    if (name.isBlank()) return null
    var photo = (header.path("accountPhoto", "thumbnails") as? JsonArray)?.lastOrNull().path("url").text
    if (photo != null) {
        if (photo.startsWith("//")) photo = "https:$photo"
        photo = photo.replace(Regex("=s\\d+"), "=s256")
    }
    return AccountInfo(name, photo)
}
