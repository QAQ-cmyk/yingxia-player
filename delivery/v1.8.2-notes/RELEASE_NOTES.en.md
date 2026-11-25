# YingXia 1.8.2 · Backup Import Hardening

[简体中文](更新说明.md)

## What's new in 1.8.2

**YingXia 1.8.2** is a focused reliability & safety hardening release (including all folder-level batch encryption features from 1.8.1):

- **10 MB streaming cap on local backup imports**: Restoring a local JSON metadata backup now streams with a strict 10 MB limit (`LibraryBackup.readBoundedUtf8`) and aborts immediately with a localized warning if exceeded, preventing OOM crashes if a large video file is selected by mistake.
- **Off-main-thread backup file I/O**: Moved backup export, share, and import file operations onto `Dispatchers.IO` to keep the UI smooth.
- **CI reliability**: Added a CI-only Google Maven Central mirror and Gradle caching in GitHub Actions without touching `settings.gradle.kts` (preserving clean FOSS reproducible builds).
- **Upgrade in place**: Signed with the same release certificate as 1.8.1 / 1.8.0 / 1.7.x. **Install directly over your existing version — do not uninstall first** (uninstalling deletes the encrypted vault).

**Verification**: Android 8.0+. JVM unit tests (25/25), Android Lint (0 errors), `scripts/check-apk-privacy.py` (`PASS`: zero `INTERNET` / `ACCESS_NETWORK_STATE` permissions, cloud backup disabled), and Android 14 (API 34) emulator checks all passed.
