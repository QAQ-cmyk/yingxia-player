# 安全与隐私 · Security

映匣在手机上播放和加密视频。发布 APK 不申请联网权限。普通播放记录保存在应用私有数据库中。保险箱另行加密视频、文件名和播放记录，进入后台时锁定。

**密码恢复（1.7.0）**　默认仅用密码。解锁后可以自愿设置三道密保问题。全部答案加上本机 Android Keystore 密钥才能在本机重设密码。知道三个答案并能使用这台手机的人也能重设，请使用互不相同的私密短语。密码与答案都不以明文保存。未提前开启密保时，作者无法帮你找回密码。卸载、清除应用数据或丢失设备也无法通过密保找回视频。换机前先解锁并恢复到相册，自行保存副本。

系统相册的云同步、其他应用留下的副本、已受控制的设备均在保险箱的保护范围之外。项目尚未经过独立安全审计。[保险箱格式与使用说明](docs/加密保险箱.md)

## Reporting a vulnerability

请通过 GitHub 私密报告发送受影响版本、Android 版本和使用测试文件观察到的现象。请勿附上真实视频、密保答案或密码。[私密报告 / Report privately](https://github.com/QAQ-cmyk/yingxia-player/security/advisories/new)

YingXia plays and encrypts videos on the device. Release builds request no internet permission. The vault encrypts videos, names and private playback records separately from the ordinary library. It locks when the app goes into the background.

In 1.7.0, recovery is optional and off by default. All three answers and the original installation’s Android Keystore key are needed to reset the password. Anyone who knows all answers and has access to the phone can also reset it. Choose three distinct private phrases. Neither the answers nor the vault password are stored in plaintext. Before switching phones or uninstalling, unlock and export the videos you want to keep. Recovery cannot reconstruct deleted files. Other gallery apps, cloud copies and compromised devices are outside this protection. The project has not undergone an independent security audit.
