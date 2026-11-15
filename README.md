# 映匣

映匣是一个完全离线的 Android 本地视频播放器。它给想把片子留在自己手机里的人用：没有账号，不上传观看记录，也不把视频送到任何服务器。打开飞行模式，播放、整理、备份和检查都可以继续。

它不打算在编码格式覆盖上和 MX Player 或 VLC 比。能在这台手机上正常打开的本地视频，用一套清楚的中文界面看完，就够了。

[下载 1.5.1 调试包](https://github.com/QAQ-cmyk/yingxia-player/releases/download/v1.5.1/YingXia-1.5.1-debug.apk) · [1.5.1 发布页](https://github.com/QAQ-cmyk/yingxia-player/releases/tag/v1.5.1) · [1.2.1 正式签名包](https://github.com/QAQ-cmyk/yingxia-player/releases/download/v1.2.1/YingXia-1.2.1.apk)

`YingXia-1.5.1-debug.apk` 是 debug 签名（versionName 1.5.1，versionCode 11）。它只能装到测试机，**不能覆盖**已经安装的 [1.2.1 正式签名包](https://github.com/QAQ-cmyk/yingxia-player/releases/download/v1.2.1/YingXia-1.2.1.apk)。要覆盖升级日常使用的正式包，请继续用 1.2.1，不要先卸载。

源码以 [MIT 许可证](LICENSE) 发布。Copyright 2026 QAQ-cmyk。

## 现在适合谁

- 视频和观看记录都留在这台手机上。
- 不注册、不登录，也不需要网络权限。
- 愿意用拼音首字母找片，并在本机查出内容完全相同或打不开的文件。
- 想在编译期加自己的本机功能，而不是装一个插件市场。

## 要点

- **完全离线。** 清单里没有 `INTERNET`，也没有 Wi-Fi 权限。Media3 自带的 `ACCESS_NETWORK_STATE` 已去掉，因为本地文件用不到。
- **没有账号。** 应用不上传观看记录或视频，也没有统计、崩溃上报或广告。
- **本机备份。** 「我的」里可以把收藏、进度、显示名称、裁黑边和播放设置导出成文本文件，或从这样的文件恢复。备份不含视频。应用自己不会发送这个文件；如果使用系统分享，文件交给你选的应用。
- **本机检查。** 「我的 → 检查视频」用文件内容的 SHA-256 找出完全相同的副本，并用系统媒体接口找出打不开的文件。可以取消，不会自动删除。打不开只说明读不了，不表示可以修复。
- **拼音搜索。** 除了文件名里的字，也可以用拼音首字母，例如「啤酒花」搜 `pjh`。排序同样按中文拼音处理。
- **画面。** 双指捏合只放大这一次播放，第二根手指不会同时拖进度或调亮度。播放工具里可以裁掉黑边：按比例铺满后裁掉边缘，不会补出新的细节。
- **防误触。** 播放中可以挡住单击和滑动，点画面左侧的锁解开。
- **扩展。** 本机视频来源、播放工具和本机检查都在编译期注册。不加载外部安装包，也没有插件商店。

支持 Android 8.0（API 26）及以上。Kotlin、Jetpack Compose、Media3。

## 还保留的功能

相册全部或部分授权、从文件打开、文件夹、收藏、显示名称、隐藏（可撤销）和删除确认。八档倍速、长按临时加速、进度拖动、全屏、亮度、音量、锁定、续播和播放列表。A-B 循环、时间书签、睡眠定时（含「播完当前」）。批量收藏、隐藏、移动和删除。浅色、深色和跟随系统。

播放器底部是上一条、播放、下一条，进度条贴在画面最下沿。只有按住画面时才会出现 2×。外面是粉白界面，桌面图标是珊瑚红底的快门。

隐藏只影响映匣里的列表。「删除原文件」会删除手机里的文件，并经过确认。

## 页面怎么走

底部四个标签是第一层：首页、文件夹、收藏、我的。

点进历史、书签、隐藏、设置、备份、检查，或点进一个文件夹，是第二层。左上角回到上一层。

播放是单独的一层。返回之后，回到刚才的列表。

## 构建

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。在未提交的 `local.properties` 里设置 `sdk.dir`。签名私钥不在仓库里。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 和构建缓存不要提交。

## 如何添加功能

新代码在编译期注册，不要访问网络。

1. **本机视频来源。** 实现 `MediaSource` 的 `query` 和 `resolve`，加入 `AppGraph.extraSources`。下次扫描会并入资料库，并保留已有的收藏和进度。
2. **播放工具。** 实现 `PlayerFeature`，在 `yingxiaTools()` 里追加一项 `PlayerTool`（功能、图标、面板）。播放工具列表会出现入口。
3. **本机检查。** 实现 `LibraryCheck`，加入 `AppGraph.checks`。「检查视频」会执行它。`CheckHit.ContentKey` 表示内容相同，`CheckHit.Unreadable` 表示打不开。

接口注释在 `core/Models.kt` 和 `core/LibraryChecks.kt`。

## 许可

[MIT License](LICENSE)。Copyright 2026 QAQ-cmyk。

## 以前有过、现在没有

1.1.0 至 1.2.1 曾内置 B 站网页。之后的版本里曾有投屏、WebDAV 和同一 Wi-Fi 互传。这些都已从当前版本删除。`delivery/v1.*-notes/` 只保留当时的记录，不描述现在的应用。
