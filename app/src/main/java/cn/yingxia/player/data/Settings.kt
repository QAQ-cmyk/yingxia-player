package cn.yingxia.player.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import cn.yingxia.player.core.*
import kotlinx.coroutines.flow.*
import java.io.IOException

private val Context.preferences by preferencesDataStore("preferences")

data class VaultBackupConfig(
    val treeUri: String = "",
    val label: String = "",
    val autoSync: Boolean = false,
    val lastSyncAt: Long = 0L,
) {
    val configured: Boolean get() = treeUri.isNotBlank()
}

class SettingsRepository(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val speed = floatPreferencesKey("speed")
    private val hold = floatPreferencesKey("hold_speed")
    private val sort = stringPreferencesKey("library_sort")
    private val touchGuard = booleanPreferencesKey("touch_guard")
    private val folders = stringSetPreferencesKey("storage_folders")
    private val vaultBackupUri = stringPreferencesKey("vault_backup_uri")
    private val vaultBackupLabel = stringPreferencesKey("vault_backup_label")
    private val vaultBackupAuto = booleanPreferencesKey("vault_backup_auto")
    private val vaultBackupSyncAt = longPreferencesKey("vault_backup_sync_at")

    val vaultBackup: Flow<VaultBackupConfig> = context.preferences.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { p ->
        VaultBackupConfig(
            treeUri = p[vaultBackupUri].orEmpty(),
            label = p[vaultBackupLabel].orEmpty(),
            autoSync = p[vaultBackupAuto] ?: false,
            lastSyncAt = p[vaultBackupSyncAt] ?: 0L,
        )
    }

    suspend fun setVaultBackupLocation(uri: String, label: String) {
        context.preferences.edit {
            it[vaultBackupUri] = uri
            it[vaultBackupLabel] = label
        }
    }

    suspend fun setVaultBackupAutoSync(enabled: Boolean) {
        context.preferences.edit { it[vaultBackupAuto] = enabled }
    }

    suspend fun setVaultBackupLastSyncAt(timestampMs: Long) {
        context.preferences.edit { it[vaultBackupSyncAt] = timestampMs }
    }

    suspend fun clearVaultBackupLocation() {
        context.preferences.edit {
            it.remove(vaultBackupUri)
            it.remove(vaultBackupLabel)
            it[vaultBackupAuto] = false
            it.remove(vaultBackupSyncAt)
        }
    }
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
        PlaybackRules.speed(p[speed] ?: 1f), PlaybackRules.holdSpeed(p[hold] ?: 2f),
        runCatching { SortOrder.valueOf(p[sort] ?: SortOrder.NEWEST.name) }.getOrDefault(SortOrder.NEWEST),
        p[touchGuard] ?: false) }
    suspend fun setTheme(value: ThemeMode) { context.preferences.edit { it[theme] = value.name } }
    suspend fun setSpeed(value: Float) { context.preferences.edit { it[speed] = PlaybackRules.speed(value) } }
    suspend fun setHoldSpeed(value: Float) { context.preferences.edit { it[hold] = PlaybackRules.holdSpeed(value) } }
    suspend fun setSort(value: SortOrder) { context.preferences.edit { it[sort] = value.name } }
    suspend fun setTouchGuard(value: Boolean) { context.preferences.edit { it[touchGuard] = value } }
    suspend fun replacePlayback(settings: UserSettings) {
        setTheme(settings.theme); setSpeed(settings.speed); setHoldSpeed(settings.holdSpeed)
        setSort(settings.sort); setTouchGuard(settings.touchGuard)
    }
    /** Drops credentials and toggles removed in 1.5.0 so they are not left in DataStore. */
    suspend fun clearRetiredPreferences() { context.preferences.edit {
        it.remove(stringPreferencesKey("webdav_url"))
        it.remove(stringPreferencesKey("webdav_user"))
        it.remove(stringPreferencesKey("webdav_pass"))
        it.remove(floatPreferencesKey("gesture_sensitivity"))
        it.remove(booleanPreferencesKey("clarity"))
        it.remove(booleanPreferencesKey("audio_enhance"))
        it.remove(booleanPreferencesKey("upscale_4k"))
        it.remove(floatPreferencesKey("subtitle_scale"))
    } }
}
