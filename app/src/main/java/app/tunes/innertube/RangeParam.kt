package app.tunes.innertube

/**
 * Tambahkan `range=N-(clen-1)` ke URL googlevideo (panjang file dari `clen` di URL atau fragment
 * `#clen=`). Fragment dibuang dari hasil. Dipakai sebagai satu-satunya penanda rentang; jangan
 * kirim header `Range` bersamaan, karena server menghitungnya relatif terhadap parameter ini.
 */
fun withRangeParam(url: String, start: Long): String {
    val base = url.substringBefore('#')
    if (base.contains("&range=") || base.contains("?range=")) return base
    val clen = Regex("[?&#]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return base
    if (clen <= 0 || start >= clen) return base
    return "$base&range=$start-${clen - 1}"
}
