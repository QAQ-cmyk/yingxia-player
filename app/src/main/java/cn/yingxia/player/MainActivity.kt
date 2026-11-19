package cn.yingxia.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import cn.yingxia.player.core.PlayerCommand
import cn.yingxia.player.ui.YingXiaApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as YingXiaApplication).graph
    private val model: AppViewModel by viewModels { AppViewModel.Factory(graph) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) acceptShare(intent)
        setContent { YingXiaApp(model) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptShare(intent)
    }
    private fun acceptShare(incoming: Intent) {
        incoming.galleryShare(packageName)?.let { share ->
            if (incoming === intent) setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            model.receiveShare(share)
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
    override fun onStop() {
        if (!isChangingConfigurations) model.pauseSharePreparation()
        if (!isChangingConfigurations) graph.vault.lock()
        if (!isChangingConfigurations) graph.scope.launch { graph.player.execute(PlayerCommand.Pause) }
        super.onStop()
    }
    override fun onDestroy() {
        if (isFinishing) graph.vault.close()
        if (isFinishing) graph.scope.launch { graph.player.execute(PlayerCommand.Close) }
        super.onDestroy()
    }
}
