# YingXia 1.7.4 · Share videos from your gallery

Select a video in your Android gallery and choose **Share → 映匣**. Granting access to just the selected videos is enough to play them or save encrypted copies.

- **Watch once.** Share one video and choose **本次不记播放记录** (Watch without saving history). Playback starts at the beginning and preserves your previous resume position and history order.
- **Save to the vault.** Share one or more videos, unlock the vault, then tap **保存加密副本** (Save encrypted copies). YingXia encrypts and verifies each video on your phone. **The originals stay in your gallery.** After checking playback in the vault, follow the instructions under each video to manage its original.
- **Also new since 1.7.2.** The ⋮ menu in the local library now offers the same one-time playback option. During vault playback, tap **锁箱** (Lock vault) to stop playback, lock the vault, and return to Home.

**Installation.** Requires Android 8.0 or later. Install over your existing official release to preserve your vault. Uninstalling deletes the vault. The APK has no internet permission, and system backup is disabled.

**Testing.** All 17 unit tests and three Android 14 emulator tests for gallery sharing passed. On that emulator we also installed the signed APK over 1.7.3 and played a video shared by the system gallery with full video library access denied. Gallery compatibility on physical devices is still unverified.

[简体中文更新说明](更新说明.md)
