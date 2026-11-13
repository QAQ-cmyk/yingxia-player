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
import cn.yingxia.player.data.*
import cn.yingxia.player.features.*
import cn.yingxia.player.playback.*
import kotlinx.coroutines.*

class YingXiaApplication : Application(), ImageLoaderFactory {
    val graph by lazy { AppGraph(this) }
    override fun newImageLoader() = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .diskCache(null).crossfade(true).build()
}
class AppGraph(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database = Room.databaseBuilder(application, VideoDatabase::class.java, "yingxia.db").addMigrations(MIGRATION_1_2).build()
    val repository = LocalVideoRepository(application, database)
    val settings = SettingsRepository(application)
    val files = LocalFileManager(application)
    val player = Media3Controller(application, repository, settings, scope)
    val subtitles = LocalSubtitleProvider(application)
    val loop = LoopFeature(player)
    val timer = SleepTimerFeature(player, scope)
    val bookmarks = BookmarkFeature(player, repository)
    val features = FeatureRegistry(player, scope, listOf(SubtitleFeature(), loop, bookmarks, timer))
    private var refreshJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshJob?.cancel()
            refreshJob = scope.launch { delay(450); repository.refresh() }
        }
    }
    init { application.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer) }
}
