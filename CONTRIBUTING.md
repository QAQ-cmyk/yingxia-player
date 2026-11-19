# 参与映匣 · Contributing

欢迎提交设备实测、简洁易用的界面改进和离线播放修复。反馈时写明映匣版本、Android 版本和复现步骤。请用可公开的测试素材。涉及私密内容或安全问题，请走[私密报告](SECURITY.md)。

Device test reports, usability improvements and offline playback fixes are welcome. Include the app version, Android version and steps to reproduce with a non-private sample. [Extension interfaces](docs/扩展接口.md)

## Build and test

使用 JDK 17、Android SDK Platform 35 与 Build Tools 35.0.0。首次构建需要下载开发依赖，安装后的应用可离线工作。Configure the SDK with Android Studio or `ANDROID_HOME`. On Windows use `gradlew.bat` in place of `./gradlew`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

在专用模拟器上运行界面与保险箱测试。Use an emulator reserved for test data.

```sh
./gradlew :app:connectedDebugAndroidTest
```

## Verify the APK

从对应发布页下载 APK 与 SHA256SUMS 文件，先比较 SHA-256，再检查签名和打包后的权限。Download both the APK and checksums from the same release. In the commands below, replace `app.apk` with the downloaded file. The privacy check needs the Android SDK command-line tools.

```sh
sha256sum app.apk
apksigner verify --verbose --print-certs app.apk
python scripts/check-apk-privacy.py app.apk
```

Windows 下可用 `Get-FileHash app.apk -Algorithm SHA256` 计算摘要。项目 1.6.1 发行证书的 SHA-256 指纹如下，可用于比较覆盖安装时的签名。Signing certificate fingerprint for comparison with the 1.6.1 release:

```text
F7:EB:BF:96:05:81:FE:79:51:B2:0C:D1:62:E6:8F:94:E3:F1:4D:45:0E:52:7E:2D:40:D5:B5:57:75:C4:45:0D
```

脚本检查 APK 清单中的联网权限、备份和调试标志。The script inspects the packaged manifest and does not perform a security audit.
