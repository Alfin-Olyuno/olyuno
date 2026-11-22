package app.tunes.data

import app.tunes.innertube.SongItem
import org.json.JSONArray
import org.json.JSONObject
import app.tunes.i18n.t

/** Isi file cadangan: semua lagu di database lokal (suka, riwayat, hitungan putar) + riwayat pencarian + log putaran (statistik). */
data class BackupData(
    val songs: List<SongEntity>,
    val searches: List<String>,
    val plays: List<PlayEntity> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    /** Streak (opsional; cadangan lama tidak punya). */
    val streak: StreakBackup? = null,
    /** true = file punya bagian streak tapi tanda tangannya tidak cocok / isinya tidak valid. */
    val streakRejected: Boolean = false,
)

/** File yang dipilih bukan cadangan Olyuno yang valid. */
class BackupFormatException(message: String) : Exception(message)

/** Baca-tulis format JSON cadangan. Murni data, tanpa Android UI / jaringan. */
object Backup {
    private const val APP = "olyuno"
    private const val VERSION = 1

    fun toJson(data: BackupData): String {
        val songs = JSONArray()
        data.songs.forEach { s ->
            songs.put(
                JSONObject()
                    .put("videoId", s.videoId)
                    .put("title", s.title)
                    .put("artist", s.artist)
                    .put("durationSec", s.durationSec)
                    .put("thumbnail", s.thumbnail ?: JSONObject.NULL)
                    .put("liked", s.liked)
                    .put("likedAt", s.likedAt)
                    .put("lastPlayedAt", s.lastPlayedAt)
                    .put("playCount", s.playCount),
            )
        }
        val plays = JSONArray()
        data.plays.forEach { p ->
            plays.put(
                JSONObject()
                    .put("videoId", p.videoId)
                    .put("playedAt", p.playedAt)
                    .put("ctxKind", p.ctxKind ?: JSONObject.NULL)
                    .put("ctxTitle", p.ctxTitle ?: JSONObject.NULL)
                    .put("ctxBrowseId", p.ctxBrowseId ?: JSONObject.NULL)
                    .put("ctxParams", p.ctxParams ?: JSONObject.NULL)
                    .put("ctxThumb", p.ctxThumb ?: JSONObject.NULL),
            )
        }
        val playlists = JSONArray()
        data.playlists.forEach { p ->
            val items = JSONArray()
            p.songs.forEach { s ->
                items.put(
                    JSONObject()
                        .put("videoId", s.videoId)
                        .put("title", s.title)
                        .put("artist", s.artist)
                        .put("durationSec", s.durationSec)
                        .put("thumbnail", s.thumbnail ?: JSONObject.NULL),
                )
            }
            playlists.put(JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("songs", items))
        }
        val streakJson = data.streak?.let { b ->
            JSONObject()
                .put("days", JSONArray(b.days))
                .put("saved", JSONArray(b.saved))
                .put("freezes", b.freezes)
                .put("sig", StreakSigner.sign(b))
        }
        return JSONObject()
            .put("app", APP)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("songs", songs)
            .put("searchHistory", JSONArray(data.searches))
            .put("plays", plays)
            .put("playlists", playlists)
            .apply { if (streakJson != null) put("streak", streakJson) }
            .toString()
    }

    /** Melempar [BackupFormatException] kalau bukan cadangan Olyuno; tidak menyentuh data apa pun. */
    fun parse(text: String): BackupData {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BackupFormatException(t("Bukan file cadangan Olyuno"))
        }
        if (root.optString("app") != APP) throw BackupFormatException(t("Bukan file cadangan Olyuno"))
        if (root.optInt("version", 0) > VERSION) {
            throw BackupFormatException(t("Cadangan dibuat dengan versi Olyuno yang lebih baru"))
        }
        val arr = root.optJSONArray("songs") ?: throw BackupFormatException(t("Isi cadangan tidak lengkap"))

        val songs = ArrayList<SongEntity>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("videoId").trim()
            if (id.isEmpty()) continue
            songs += SongEntity(
                videoId = id,
                title = o.optString("title"),
                artist = o.optString("artist"),
                durationSec = o.optInt("durationSec", 0).coerceAtLeast(0),
                thumbnail = if (o.isNull("thumbnail")) null else o.optString("thumbnail").ifBlank { null },
                liked = o.optBoolean("liked", false),
                likedAt = o.optLong("likedAt", 0).coerceAtLeast(0),
                lastPlayedAt = o.optLong("lastPlayedAt", 0).coerceAtLeast(0),
                playCount = o.optInt("playCount", 0).coerceAtLeast(0),
            )
        }

        val sa = root.optJSONArray("searchHistory")
        val searches = if (sa == null) emptyList() else List(sa.length()) { sa.optString(it) }.filter { it.isNotBlank() }
        // Log putaran bersifat opsional: cadangan lama tidak punya.
        val pa = root.optJSONArray("plays")
        val plays = ArrayList<PlayEntity>()
        if (pa != null) {
            for (i in 0 until pa.length()) {
                val o = pa.optJSONObject(i) ?: continue
                val id = o.optString("videoId").trim()
                val at = o.optLong("playedAt", 0)
                if (id.isEmpty() || at <= 0) continue
                fun str(key: String): String? = if (o.isNull(key)) null else o.optString(key).ifBlank { null }
                plays += PlayEntity(
                    videoId = id,
                    playedAt = at,
                    ctxKind = str("ctxKind"),
                    ctxTitle = str("ctxTitle"),
                    ctxBrowseId = str("ctxBrowseId"),
                    ctxParams = str("ctxParams"),
                    ctxThumb = str("ctxThumb"),
                )
            }
        }
        // Playlist lokal juga opsional: cadangan lama tidak punya.
        val pl = root.optJSONArray("playlists")
        val playlists = ArrayList<BackupPlaylist>()
        if (pl != null) {
            for (i in 0 until pl.length()) {
                val o = pl.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isEmpty()) continue
                val sa2 = o.optJSONArray("songs")
                val items = ArrayList<SongItem>()
                if (sa2 != null) {
                    for (k in 0 until sa2.length()) {
                        val so = sa2.optJSONObject(k) ?: continue
                        val id = so.optString("videoId").trim()
                        if (id.isEmpty()) continue
                        items += SongItem(
                            videoId = id,
                            title = so.optString("title"),
                            artist = so.optString("artist"),
                            durationSec = so.optInt("durationSec", 0).coerceAtLeast(0),
                            thumbnail = if (so.isNull("thumbnail")) null else so.optString("thumbnail").ifBlank { null },
                        )
                    }
                }
                playlists += BackupPlaylist(name, o.optLong("createdAt", 0).coerceAtLeast(0), items)
            }
        }
        // Streak juga opsional. Tanpa tanda tangan yang cocok, bagian ini ditolak (data lain tetap diimpor).
        var streak: StreakBackup? = null
        var rejected = false
        root.optJSONObject("streak")?.let { so ->
            val sig = so.optString("sig")
            val d = so.optJSONArray("days")
            val sv = so.optJSONArray("saved")
            if (d == null || sig.isBlank()) {
                rejected = true
            } else {
                val b = StreakBackup(
                    days = List(d.length()) { d.optLong(it, -1L) },
                    saved = if (sv == null) emptyList() else List(sv.length()) { sv.optLong(it, -1L) },
                    freezes = so.optInt("freezes", 0),
                )
                if (StreakSigner.verify(b, sig)) streak = b else rejected = true
            }
        }
        return BackupData(songs, searches, plays, playlists, streak, rejected)
    }
}
