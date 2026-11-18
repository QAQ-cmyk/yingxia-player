package cn.yingxia.player

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import cn.yingxia.player.core.LibraryCheck
import cn.yingxia.player.core.MediaSource
import cn.yingxia.player.data.*
import cn.yingxia.player.features.*
import cn.yingxia.player.playback.*
import cn.yingxia.player.scan.ReadableVideoCheck
import cn.yingxia.player.scan.Sha256Check
import cn.yingxia.player.ui.PlayerTool
import cn.yingxia.player.ui.yingxiaTools
import kotlinx.coroutines.*

class YingXiaApplication : Application(), ImageLoaderFactory {
    val graph by lazy { AppGraph(this) }
    override fun newImageLoader() = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .diskCache(null).crossfade(true).build()
}
class AppGraph(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database = Room.databaseBuilder(application, VideoDatabase::class.java, "yingxia.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    /** 额外的本机视频来源。默认没有。不要在实现里访问网络。 */
    val extraSources: List<MediaSource> = emptyList()
    val repository = LocalVideoRepository(application, database, extraSources)
    val settings = SettingsRepository(application)
    val files = LocalFileManager(application)
    val player = Media3Controller(application, repository, settings, scope)
    val vault = cn.yingxia.player.vault.VaultCoordinator(application, repository, files, player, scope, settings)
    val loop = LoopFeature(player)
    val timer = SleepTimerFeature(player, scope)
    val bookmarks = BookmarkFeature(player, repository)
    /** 播放工具。新增一项就会出现在播放工具列表。 */
    val tools: List<PlayerTool> = yingxiaTools(loop, bookmarks, timer)
    val features = FeatureRegistry(player, scope, tools.map { it.feature })
    /** 本机检查。新增一项会在「检查视频」里执行。 */
    val checks: List<LibraryCheck> = listOf(Sha256Check(), ReadableVideoCheck(application))
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
