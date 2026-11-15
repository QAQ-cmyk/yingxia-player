# 映匣 1.5.1

中文 Android 本地视频播放器，支持 Android 8.0（API 26）及以上。Kotlin + Compose + Media3。没有账号，无需联网，可完全离线使用。观看记录和视频都留在这台手机上。

[1.2.1 正式签名包](https://github.com/QAQ-cmyk/yingxia-player/releases/download/v1.2.1/YingXia-1.2.1.apk) · [已发布的版本](https://github.com/QAQ-cmyk/yingxia-player/releases) · [安装说明](delivery/安装与使用.md)

1.5.1 是当前源码版本，需要自行构建。debug 签名不能覆盖已经安装的 1.2.1 正式签名包。

1.1.0 至 1.2.1 曾内置 B 站网页，已经移除。后来短暂有过的投屏、WebDAV 和同一 Wi-Fi 互传也已去掉。`delivery/v1.*-notes/` 只保留当时的说明。

## 现在有什么

相册授权、文件选择、搜索排序、文件夹、收藏、续播。八档倍速、长按加速、进度拖动、全屏、亮度音量、锁定。A-B 循环、时间书签、睡眠定时。批量隐藏、收藏、移动和删除确认。深色模式。双指可以放大当前这一次播放，播放工具里可以裁掉黑边。播放与外观里可以打开播放时防误触。搜索支持汉字和拼音首字母。

「我的」里可以检查内容完全相同的视频，以及打不开的文件。检查只在本机进行，不会自动删除。「我的」也可以把收藏、进度、显示名称、裁黑边和播放设置导出成一个文本文件，或从这样的文件恢复。备份不含视频。应用自己不会上传；如果你用系统分享把文件交给别的应用，由那个应用决定。

## 已经去掉

- B 站网页、登录和广告过滤。
- 投屏、WebDAV、同一 Wi-Fi 互传，以及清单里的网络和 Wi-Fi 权限。
- 清晰度、放大到 4K、音质增强。
- 手势灵敏度的低 / 标准 / 高。防误触还在。
- 左转、右转、水平镜像，以及字幕选择。裁掉黑边还在。

隐藏只影响应用内显示；「删除原文件」会真正删除手机文件。应用没有统计、崩溃上报或广告。

## 页面怎么走

底部四个标签是第一层：首页、文件夹、收藏、我的。点进历史、书签、隐藏、设置、备份、检查，或点进一个文件夹，是第二层。左上角返回上一层。播放是单独的一层，返回就回到刚才的列表。

## 如何添加功能

扩展都在进程内、编译期注册，不加载外部 APK，也没有插件商店。新功能不要访问网络。

1. **本机视频来源**：实现 `MediaSource`（`query` / `resolve`），加入 `AppGraph.extraSources`。下次扫描会并入资料库，并保留已有的收藏和进度。
2. **播放工具**：实现 `PlayerFeature`，在 `yingxiaTools()` 里追加一项 `PlayerTool`（功能、图标、面板）。播放工具列表会自动出现入口。
3. **本机检查**：实现 `LibraryCheck`，加入 `AppGraph.checks`。「检查视频」会执行它。`CheckHit.ContentKey` 用来找内容相同的文件，`CheckHit.Unreadable` 表示打不开。

详情也写在这些接口的注释里。

## 安装与构建

正式覆盖升级仍使用 [1.2.1 签名包](https://github.com/QAQ-cmyk/yingxia-player/releases/download/v1.2.1/YingXia-1.2.1.apk)，不要先卸载。

JDK 17、Android SDK Platform 35、Build Tools 35.0.0。在未提交的 `local.properties` 中设置 `sdk.dir`。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

签名私钥不在仓库里。APK 和构建缓存不纳入 Git。
