package cn.yingxia.player

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.room.Room
import cn.yingxia.player.core.LibraryCheck
import cn.yingxia.player.core.MediaSource
import cn.yingxia.player.data.*
import cn.yingxia.player.features.*
import cn.yingxia.player.playback.*
import cn.yingxia.player.scan.ReadableVideoCheck
import cn.yingxia.player.scan.Sha256Check
import cn.yingxia.player.sync.SyncExtensionBridge
import cn.yingxia.player.sync.createFlavorSyncBridge
import cn.yingxia.player.ui.PlayerTool
import cn.yingxia.player.ui.yingxiaTools
import kotlinx.coroutines.*

class YingXiaApplication : Application() {
    val graph by lazy { AppGraph(this) }
}

class AppGraph(application: Application) {
    val appContext: android.content.Context = application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database = Room.databaseBuilder(application, VideoDatabase::class.java, "yingxia.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    val settings = SettingsRepository(application)
    val files = LocalFileManager(application)
    val sync: SyncExtensionBridge = createFlavorSyncBridge(this)
    /** 额外的视频来源：offline 变体恒为空列表（零联网）；sync 变体挂载 Ktor 私有流媒体源。 */
    val extraSources: List<MediaSource> = sync.extraMediaSources
    val repository = LocalVideoRepository(application, database, extraSources)
    val player = Media3Controller(application, repository, settings, scope, sync::customExoMediaSource)
    val vault = cn.yingxia.player.vault.VaultCoordinator(application, repository, files, player, scope, settings)
    val loop = LoopFeature(player) { AppLocale.str(application, R.string.loop_error_set_a_first) }
    val timer = SleepTimerFeature(player, scope)
    val bookmarks = BookmarkFeature(player, repository)
    val tools: List<PlayerTool> = yingxiaTools(loop, bookmarks, timer)
    val features = FeatureRegistry(player, scope, tools.map { it.feature })
    val checks: List<LibraryCheck> = listOf(ReadableVideoCheck(application), Sha256Check(application))
    init { scope.launch { settings.clearRetiredPreferences() } }
    private var refreshJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshJob?.cancel()
            refreshJob = scope.launch { delay(450); repository.refresh() }
        }
    }
    init { application.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer) }
}
