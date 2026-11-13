package cn.yingxia.player.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import cn.yingxia.player.core.*
import kotlinx.coroutines.flow.*
import java.io.IOException

private val Context.preferences by preferencesDataStore("preferences")
class SettingsRepository(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val speed = floatPreferencesKey("speed")
    private val hold = floatPreferencesKey("hold_speed")
    private val scale = floatPreferencesKey("subtitle_scale")
    private val sort = stringPreferencesKey("library_sort")
    private val sensitivity = floatPreferencesKey("gesture_sensitivity")
    private val touchGuard = booleanPreferencesKey("touch_guard")
    private val folders = stringSetPreferencesKey("storage_folders")
    private val davUrl = stringPreferencesKey("webdav_url")
    private val davUser = stringPreferencesKey("webdav_user")
    private val davPass = stringPreferencesKey("webdav_pass")
    val storageFolders: Flow<List<StorageFolder>> = context.preferences.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { p ->
        p[folders].orEmpty().mapNotNull { value -> runCatching {
            val row = org.json.JSONArray(value); StorageFolder(row.getString(0), row.getString(1))
        }.getOrNull() }.sortedBy { it.name }
    }
    suspend fun addFolder(folder: StorageFolder) { context.preferences.edit { p ->
        val old = p[folders].orEmpty().filterNot { runCatching { org.json.JSONArray(it).getString(0) == folder.uri }.getOrDefault(false) }
        p[folders] = (old + org.json.JSONArray(listOf(folder.uri, folder.name)).toString()).toSet()
    } }
    val settings: Flow<UserSettings> = context.preferences.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { p -> UserSettings(
        runCatching { ThemeMode.valueOf(p[theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        PlaybackRules.speed(p[speed] ?: 1f), if (p[hold] == 3f) 3f else 2f,
        (p[scale] ?: 1f).coerceIn(.75f, 1.5f),
        runCatching { SortOrder.valueOf(p[sort] ?: SortOrder.NEWEST.name) }.getOrDefault(SortOrder.NEWEST),
        gestureLevel(p[sensitivity] ?: 1f), p[touchGuard] ?: false) }
    suspend fun setTheme(value: ThemeMode) { context.preferences.edit { it[theme] = value.name } }
    suspend fun setSpeed(value: Float) { context.preferences.edit { it[speed] = PlaybackRules.speed(value) } }
    suspend fun setHoldSpeed(value: Float) { context.preferences.edit { it[hold] = if (value == 3f) 3f else 2f } }
    suspend fun setSubtitleScale(value: Float) { context.preferences.edit { it[scale] = value.coerceIn(.75f, 1.5f) } }
    suspend fun setSort(value: SortOrder) { context.preferences.edit { it[sort] = value.name } }
    suspend fun setGestureSensitivity(value: Float) { context.preferences.edit { it[sensitivity] = gestureLevel(value) } }
    suspend fun setTouchGuard(value: Boolean) { context.preferences.edit { it[touchGuard] = value } }
    suspend fun replacePlayback(settings: UserSettings) {
        setTheme(settings.theme); setSpeed(settings.speed); setHoldSpeed(settings.holdSpeed)
        setSubtitleScale(settings.subtitleScale); setSort(settings.sort)
        setGestureSensitivity(settings.gestureSensitivity); setTouchGuard(settings.touchGuard)
    }
    val webDav: Flow<WebDavConfig> = context.preferences.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { WebDavConfig(it[davUrl].orEmpty(), it[davUser].orEmpty(), it[davPass].orEmpty()) }
    suspend fun setWebDav(url: String, user: String, password: String) { context.preferences.edit {
        it[davUrl] = url.trim(); it[davUser] = user.trim(); it[davPass] = password
    } }
}

data class WebDavConfig(val url: String = "", val user: String = "", val password: String = "")
fun gestureLevel(value: Float) = when {
    value < 0.85f -> 0.7f
    value > 1.15f -> 1.4f
    else -> 1f
}
