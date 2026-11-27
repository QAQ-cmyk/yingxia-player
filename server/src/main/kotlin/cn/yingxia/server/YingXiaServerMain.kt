package cn.yingxia.server

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.io.File

fun main() {
    val port = System.getenv("YINGXIA_PORT")?.toIntOrNull() ?: 8443
    val host = System.getenv("YINGXIA_HOST") ?: "0.0.0.0"
    val mediaDir = File(System.getenv("YINGXIA_MEDIA_DIR") ?: "./media")
    val dataDir = File(System.getenv("YINGXIA_DATA_DIR") ?: "./yingxia-server-data")
    val token = System.getenv("YINGXIA_BEARER_TOKEN")
        ?: error("Set YINGXIA_BEARER_TOKEN environment variable (16+ characters) before starting YingXia Server.")

    embeddedServer(Netty, port = port, host = host) {
        configureYingXiaServer(
            YingXiaServerConfig(
                mediaDir = mediaDir,
                dataDir = dataDir,
                bearerToken = token,
            )
        )
    }.start(wait = true)
}
