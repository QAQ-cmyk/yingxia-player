# YingXia 1.9.1 · Fix Library Check Freeze & Crash

[简体中文](更新说明.md)

## What's new in 1.9.1

**YingXia 1.9.1** is a hotfix release (including uninstall-safe encrypted vault backup from 1.9.0 and folder batch encryption from 1.8.1):

- **Fixed Library Check UI freeze and ANR crash**: Moved `startLibraryScan()`, `Sha256Check`, and `ReadableVideoCheck` completely onto `Dispatchers.IO`, added responsive per-chunk cancellation handling, and hardened corrupt-file and `MediaMetadataRetriever` descriptor cleanup.
- **Much faster duplicate scanning**: Videos with unique byte sizes now skip unnecessary full-file SHA-256 reads while keeping exact byte-for-byte duplicate accuracy.
- **Upgrade in place**: Signed with the same release certificate as 1.9.0 / 1.8.x / 1.7.x. **Install directly over your existing version — do not uninstall first.**

**Verification**: Android 8.0+. JVM unit tests (29/29), Android 14 (API 34) emulator `LibraryScanUiTest` (duplicate + unique + corrupt media scanned cleanly in 2.9s with zero crashes), Android Lint (0 errors), and `scripts/check-apk-privacy.py` (`PASS`: zero `INTERNET` / `ACCESS_NETWORK_STATE` permissions, cloud backup disabled) all passed.
