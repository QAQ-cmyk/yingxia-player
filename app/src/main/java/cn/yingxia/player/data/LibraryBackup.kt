package cn.yingxia.player.data

import cn.yingxia.player.core.*
import org.json.JSONArray
import org.json.JSONObject

data class LibraryBackupFile(val settings: UserSettings, val records: List<LibraryRecord>)
data class RestorePlan(val settings: UserSettings, val matched: List<LibraryRecord>, val skipped: Int)

object LibraryBackup {
    fun export(videos: List<VideoItem>, settings: UserSettings): String {
        val records = JSONArray()
        videos.filter { it.favorite || it.positionMs > 0 || it.lastPlayedAt > 0 || !it.displayName.isNullOrBlank() || it.rotation != 0 || it.mirrored || it.cropBars }
            .forEach { video ->
                records.put(JSONObject()
                    .put("id", video.id.value).put("uri", video.uri).put("name", video.name).put("sizeBytes", video.sizeBytes)
                    .put("favorite", video.favorite).put("positionMs", video.positionMs).put("lastPlayedAt", video.lastPlayedAt)
                    .put("displayName", video.displayName ?: "")
                    .put("rotation", video.rotation).put("mirrored", video.mirrored).put("cropBars", video.cropBars))
            }
        val prefs = JSONObject()
            .put("theme", settings.theme.name).put("speed", settings.speed.toDouble()).put("holdSpeed", settings.holdSpeed.toDouble())
            .put("subtitleScale", settings.subtitleScale.toDouble()).put("sort", settings.sort.name)
            .put("gestureSensitivity", settings.gestureSensitivity.toDouble()).put("touchGuard", settings.touchGuard)
        return JSONObject().put("kind", "yingxia-backup").put("version", 1).put("settings", prefs).put("records", records).toString()
    }

    fun parse(text: String): LibraryBackupFile {
        val root = JSONObject(text)
        if (root.optString("kind") != "yingxia-backup") throw IllegalArgumentException("这不是映匣的备份文件")
        val prefs = root.getJSONObject("settings")
        val settings = UserSettings(
            theme = runCatching { ThemeMode.valueOf(prefs.optString("theme", "SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM),
            speed = PlaybackRules.speed(prefs.optDouble("speed", 1.0).toFloat()),
            holdSpeed = if (prefs.optDouble("holdSpeed", 2.0).toFloat() == 3f) 3f else 2f,
            subtitleScale = prefs.optDouble("subtitleScale", 1.0).toFloat().coerceIn(.75f, 1.5f),
            sort = runCatching { SortOrder.valueOf(prefs.optString("sort", "NEWEST")) }.getOrDefault(SortOrder.NEWEST),
            gestureSensitivity = gestureLevel(prefs.optDouble("gestureSensitivity", 1.0).toFloat()),
            touchGuard = prefs.optBoolean("touchGuard", false),
        )
        val records = root.optJSONArray("records") ?: JSONArray()
        val rows = buildList {
            for (i in 0 until records.length()) {
                val row = records.getJSONObject(i)
                add(LibraryRecord(row.optString("id"), row.optString("uri"), row.optString("name"), row.optLong("sizeBytes"),
                    row.optBoolean("favorite"), row.optLong("positionMs"), row.optLong("lastPlayedAt"),
                    row.optString("displayName").ifBlank { null }, quarterTurn(row.optInt("rotation")),
                    row.optBoolean("mirrored"), row.optBoolean("cropBars")))
            }
        }
        return LibraryBackupFile(settings, rows)
    }

    fun plan(library: List<VideoItem>, file: LibraryBackupFile): RestorePlan {
        val matched = mutableListOf<LibraryRecord>()
        var skipped = 0
        file.records.forEach { row ->
            val hit = library.firstOrNull { it.uri == row.uri && row.uri.isNotBlank() }
                ?: library.firstOrNull { it.id.value == row.id && row.id.isNotBlank() }
                ?: library.filter { it.name == row.name && it.sizeBytes == row.sizeBytes && row.sizeBytes > 0 }.singleOrNull()
            if (hit == null) skipped++ else matched += row.copy(id = hit.id.value)
        }
        return RestorePlan(file.settings, matched, skipped)
    }
}
