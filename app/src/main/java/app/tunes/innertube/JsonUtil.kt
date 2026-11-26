package app.tunes.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Cari semua objek dengan key [key] di seluruh pohon JSON (respons InnerTube dalam sekali). */
internal fun JsonElement.findAll(key: String): List<JsonObject> {
    val out = ArrayList<JsonObject>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) ->
                if (k == key && v is JsonObject) out.add(v)
                walk(v)
            }
            is JsonArray -> e.forEach { walk(it) }
            else -> Unit
        }
    }
    walk(this)
    return out
}

/** Telusuri path campuran: String untuk key objek, Int untuk index array. */
internal fun JsonElement?.path(vararg keys: Any): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) {
        cur = when (k) {
            is String -> (cur as? JsonObject)?.get(k)
            is Int -> (cur as? JsonArray)?.getOrNull(k)
            else -> null
        }
        if (cur == null) return null
    }
    return cur
}

internal val JsonElement?.text: String?
    get() = (this as? JsonPrimitive)?.contentOrNull

internal val JsonElement?.int: Int?
    get() = (this as? JsonPrimitive)?.intOrNull

internal fun JsonElement?.objects(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** "3:45" -> 225, "1:02:03" -> 3723, selain itu null. */
internal fun parseDuration(s: String): Int? {
    val p = s.trim().split(":")
    if (p.size !in 2..3 || p.any { it.toIntOrNull() == null }) return null
    return p.fold(0) { acc, v -> acc * 60 + v.toInt() }
}

/** Thumbnail YouTube Music berukuran kecil (=w60-h60...) dinaikkan ke resolusi lebih besar. */
internal fun String.hiRes(size: Int = 544): String =
    replace(Regex("=w\\d+-h\\d+.*$"), "=w$size-h$size-l90-rj")
