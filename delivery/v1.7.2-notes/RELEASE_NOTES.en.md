# YingXia 1.7.2 · Custom vault recovery questions

[简体中文](更新说明.md)

## English release notes

YingXia 1.7.2 lets you write three recovery questions for your encrypted vault. If you forget the password, answer all three on the same phone to set a new one and regain access to your videos and bookmarks.

- **Set up recovery.** Open **我的 → 加密保险箱** (Me → Encrypted vault), unlock it, and tap **设置密保问题** (Set recovery questions). Write three different questions, then enter an answer for each. You can also edit a suggested question. If you go back and change one question, the other two answers stay filled in.
- **Upgrade in place.** Install the APK over your existing copy. Uninstalling deletes the vault. Questions set in 1.7.0 or 1.7.1 continue to work. To replace them, turn recovery off with your current password and set it up again. If you’re upgrading from 1.6.1, you can enable recovery after unlocking the vault.
- **Privacy and recovery.** Custom prompts are encrypted on your device, and recovery works offline. The lock screen can display the questions, so keep answers out of the prompts. Recovery must be enabled before you forget the password. After a successful reset, you can set it up again.

**Installation and testing.** Requires Android 8.0 or later. All 17 unit tests and three Android 14 emulator vault tests passed. Coverage included Chinese and English prompts, editing, failed-attempt limits, damaged recovery data, and decrypting the original video after a reset. The signed 1.7.1-to-1.7.2 upgrade was verified on an emulator. Physical-device testing is still pending. The APK signature was verified, the app requests no internet permission, and system backup is disabled.

APK SHA-256 · `a5da1a14b248d389c770659823245771af0c07b48e9e45274efbfc1e5506303e`
