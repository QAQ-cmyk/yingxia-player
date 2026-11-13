package cn.yingxia.player

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
        setContent { YingXiaApp(model) }
    }
    override fun onResume() { super.onResume(); model.refresh() }
    override fun onStop() {
        if (!isChangingConfigurations) graph.scope.launch { graph.player.execute(PlayerCommand.Pause) }
        super.onStop()
    }
    override fun onDestroy() {
        if (isFinishing) graph.scope.launch { graph.player.execute(PlayerCommand.Close) }
        super.onDestroy()
    }
}
