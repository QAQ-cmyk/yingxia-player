# 映匣 1.2.1

中文 Android 本地视频播放器，支持 Android 8.0（API 26）及以上。Kotlin + Compose + Media3，Room 保存关联记录，DataStore 保存偏好。视频和记录都留在手机里，应用不联网。

[下载 1.2.1 安装包](https://github.com/QAQ-cmyk/yingxia-player/releases/download/v1.2.1/YingXia-1.2.1.apk) · [版本发布页](https://github.com/QAQ-cmyk/yingxia-player/releases/latest) · [安装说明](delivery/安装与使用.md) · [测试记录](delivery/测试记录.md)

1.1.0 至 1.2.1 曾内置 B 站网页。该功能已经移除，不再提供在线浏览、网页登录或广告过滤。`delivery/v1.*-notes/` 只保留当时的说明。

## 播放器功能

相册全部/部分授权、文件选择、搜索排序、文件夹、收藏；八档倍速、长按加速、进度拖动、互斥手势；全屏、亮度音量、锁定、续播和队列；SRT/VTT 与内嵌字幕、A-B 循环、时间书签、睡眠定时；批量隐藏恢复和深色模式。

本地视频不会上传。隐藏只影响应用内显示；「删除原文件」则会真正删除手机文件。

## 安装与交付

从 [Releases](https://github.com/QAQ-cmyk/yingxia-player/releases) 下载 `YingXia-1.2.1.apk`。沿用旧版签名，可直接覆盖升级保留记录，不要先卸载。发布页同时提供完整源码 ZIP 和 SHA-256 校验文件；截图、安装说明、接口说明和测试记录位于仓库的 `delivery/`。APK、打包源码和本机构建缓存不纳入 Git 历史。

## 构建

JDK 17、Android SDK Platform 35、Build Tools 35.0.0。使用 Android Studio 打开工程，在未提交的 `local.properties` 中设置 `sdk.dir=C:/Android/Sdk`。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\scripts\device-tests.ps1 -Serial emulator-5554 -Sdk C:\Android\Sdk
```

Windows 建议英文路径。首次下载构建依赖需要网络；SDK、缓存、机器路径和签名私钥不包含在源码包中。签名材料保存在原工作区 `.tools/signing/`，由项目所有者单独保管。设备测试使用专门模拟器，不能为了调试测试卸载日常使用的正式包。

## 结构与依据

`core` 公共协议；`data` 媒体、记录与真实文件操作；`playback` 播放适配；`features` 注册功能；`ui` 页面与手势。详情见 `docs/扩展接口.md`。

实际测试范围见 `delivery/测试记录.md`。模拟器结果不代表所有手机或编码格式均已通过。
