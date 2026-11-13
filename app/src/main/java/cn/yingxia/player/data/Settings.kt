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
    private val desktop = booleanPreferencesKey("web_desktop")
    private val ads = booleanPreferencesKey("web_block_ads")
    private val folders = stringSetPreferencesKey("storage_folders")
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
        (p[scale] ?: 1f).coerceIn(.75f, 1.5f), p[desktop] ?: false, p[ads] ?: true) }
    suspend fun setWebDesktop(value: Boolean) { context.preferences.edit { it[desktop] = value } }
    suspend fun setBlockWebAds(value: Boolean) { context.preferences.edit { it[ads] = value } }
    suspend fun setTheme(value: ThemeMode) { context.preferences.edit { it[theme] = value.name } }
    suspend fun setSpeed(value: Float) { context.preferences.edit { it[speed] = PlaybackRules.speed(value) } }
    suspend fun setHoldSpeed(value: Float) { context.preferences.edit { it[hold] = if (value == 3f) 3f else 2f } }
    suspend fun setSubtitleScale(value: Float) { context.preferences.edit { it[scale] = value.coerceIn(.75f, 1.5f) } }
}
