# 映匣 1.5.0

中文 Android 本地视频播放器，支持 Android 8.0（API 26）及以上。Kotlin + Compose + Media3，Room 保存关联记录，DataStore 保存偏好。没有账号。观看记录和视频默认留在这台手机上。

[1.2.1 正式签名包](https://github.com/QAQ-cmyk/yingxia-player/releases/download/v1.2.1/YingXia-1.2.1.apk) · [已发布的版本](https://github.com/QAQ-cmyk/yingxia-player/releases) · [安装说明](delivery/安装与使用.md) · [测试记录](delivery/测试记录.md)

1.5.0 是当前源码版本，需要自行构建。已发布的调试包仍是 1.4.0；debug 签名不能覆盖已经安装的 1.2.1 正式签名包。

1.1.0 至 1.2.1 曾内置 B 站网页。该功能已经移除，不再提供在线浏览、网页登录或广告过滤。1.5.0 同时去掉系统投屏和 WebDAV 云端传输。`delivery/v1.*-notes/` 只保留当时的说明。

## 现在有什么

相册全部/部分授权、文件选择、搜索排序、文件夹、收藏；八档倍速、长按加速、进度拖动；全屏、亮度音量、锁定、续播和队列；SRT/VTT 与内嵌字幕、A-B 循环、时间书签、睡眠定时；批量隐藏、收藏、移动和删除确认；深色模式。双指可以放大当前这一次播放，播放工具里可以裁掉黑边、旋转和水平镜像，旋转和镜像记在每条视频上。播放与外观里可以打开播放时防误触。搜索支持汉字和拼音首字母。

「我的」里可以在同一 Wi-Fi 上和另一台映匣互传视频。接收页要保持打开，文件写到 Movies/YingXia，不经过服务器。也可以把收藏、进度、显示名称、旋转镜像和播放设置导出成一个文本文件，或从这样的文件恢复。备份不含视频，也不含密码。

## 已经去掉

- B 站网页、登录和广告过滤。
- 投屏到电视或电脑。
- WebDAV / 云端或 NAS 传输，以及本机保存的 WebDAV 地址和密码。
- 清晰度、放大到 4K、音质增强。
- 手势灵敏度的低/标准/高选项。防误触还在，手势使用一套固定的手感。

隐藏只影响应用内显示；「删除原文件」则会真正删除手机文件。应用没有统计、崩溃上报或广告。

## 安装与交付

正式覆盖升级仍使用 [1.2.1 签名包](https://github.com/QAQ-cmyk/yingxia-player/releases/download/v1.2.1/YingXia-1.2.1.apk)，不要先卸载。1.5.0 的 debug 包只能装到测试机。截图、安装说明和测试记录位于仓库的 `delivery/`。APK 和本机构建缓存不纳入 Git 历史。

## 构建

JDK 17、Android SDK Platform 35、Build Tools 35.0.0。使用 Android Studio 打开工程，在未提交的 `local.properties` 中设置 `sdk.dir=C:/Android/Sdk`。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\scripts\device-tests.ps1 -Serial emulator-5554 -Sdk C:\Android\Sdk
```

Windows 建议英文路径。首次下载构建依赖需要网络；SDK、缓存、机器路径和签名私钥不包含在源码包中。签名材料保存在原工作区 `.tools/signing/`，由项目所有者单独保管。设备测试使用专门模拟器，不能为了调试测试卸载日常使用的正式包。

## 结构与依据

`core` 公共协议；`data` 媒体、记录与真实文件操作；`playback` 播放适配；`features` 注册功能；`ui` 页面与手势；`transfer` 同一 Wi-Fi 互传。详情见 `docs/扩展接口.md` 和 `docs/网络功能参考.md`。

实际测试范围见 `delivery/测试记录.md`。模拟器结果不代表所有手机或编码格式均已通过。
