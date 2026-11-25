# YingXia 1.9.0 · Uninstall-Safe Encrypted Vault Backup & Migration

[简体中文](更新说明.md)

## What's new in 1.9.0

Based on community feedback, **YingXia 1.9.0** adds **uninstall-safe pure-ciphertext vault backup & phone migration**, with **zero network permissions, zero new permissions, and zero plaintext exposure**:

- **Pure AES-256-GCM incremental ciphertext backup**: Bind any local folder (defaulting to `Documents/YingXiaVault`) or USB OTG drive via Android SAF. Exports only `keyset.bin`, canonical UUID ciphertext files (`.vault` / `.meta` / `.playback`), `.nomedia`, and a checksum-only `manifest.json`. **No plaintext video, no titles/filenames, no passwords, and no device-bound KeyStore security questions are ever exported.**
- **Incremental sync & optional auto-sync**: Skips unchanged files by size + SHA-256 so subsequent backups finish in milliseconds; includes an optional toggle to automatically sync ciphertext changes after vault updates.
- **One-tap restore after reinstall or phone switch**: Opening an empty vault offers **Open backup folder to restore**, jumping straight to `Documents/YingXiaVault` via `EXTRA_INITIAL_URI` (and smartly locating `YingXiaVault/` even if a parent folder is selected). Verifies the original password and every file's integrity before committing, restoring all videos, custom titles, playback positions, and bookmarks while detaching old-device MediaStore URIs.
- **Safe merge into an existing vault**: Merge another backup into an unlocked vault using its own password, re-encrypting in memory with automatic SHA-256 deduplication without modifying existing entries or changing your current password.
- **Upgrade in place**: Signed with the same release certificate as 1.8.x / 1.7.x. **Install directly over your existing version — do not uninstall first.**

**Verification**: Android 8.0+. JVM unit tests (29/29), Android 14 (API 34) emulator full suite (41/41, including real Android 14 SAF `DocumentsUI` direct restore, post-reinstall `ExoPlayer` playback, 1-byte ciphertext tamper rejection, and 36 MiB incremental backup/restore performance benchmark), Android Lint (0 errors), and `scripts/check-apk-privacy.py` (`PASS`: zero `INTERNET` / `ACCESS_NETWORK_STATE` permissions, cloud backup disabled) all passed.
