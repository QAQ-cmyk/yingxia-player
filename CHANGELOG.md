# Changelog

All notable changes to **YingXia Player (映匣)** are documented here.

## [2.0.3] - 2026-11-28

### Fixed
- **Unified portrait player theme with app Light/Dark mode**: Removed forced Dark Mode on the portrait player screen (`PlayerUi.kt`), keeping the top status bar strip and 16:9 video viewport pure black while making the title area, action capsules, inline bookmark pills, segmented queue/bookmark header, queue cards, bottom sheets, and bottom system navigation bar icons follow the user's active Light/Dark theme smoothly.

---

## [2.0.2] - 2026-11-28

### Fixed
- **Restored side-by-side 2-column video grid on standard phones**: Replaced `GridCells.Adaptive(162.dp)` (which collapsed to a single vertical column on 360dp–376dp Android phones and zoomed display settings) with `ResponsiveVideoGridCells` (`videoGridColumnCount` guaranteed `>= 2` columns on portrait phones while still scaling up to 3–4+ columns on landscape/tablet screens).
- **Aligned 16dp page margins & filter pill spacing**: Restored `16.dp` horizontal margins across Library, Folders, Space, Settings, and Bookmarks, and tightened filter capsule spacing so `All`, `In Progress`, and `Favorites` remain clearly visible on 360dp screens.

---

## [2.0.1] - 2026-11-28

### Fixed
- **Fixed system gallery Share → YingXia rejection on OEM ROMs**: Allowed Android multi-user numeric URI authority prefixes (such as `content://0@media/...` and `content://0@com.miui.gallery.open/...`) while still blocking arbitrary `userInfo` spoofing and self-provider URIs, handled `ACTION_SEND` intents where OEM galleries supply distinct `EXTRA_STREAM` and `clipData` aliases, accepted generic `*/*` / `null` share MIME types (still verified as video via `MediaMetadataRetriever`), and hardened `OpenableColumns` / `0@media` handling in `LocalMedia`.

---

## [2.0.0] - 2026-11-26

### Added & Changed
- **Minimalist 2.0 UI & UX Overhaul**:
  - Replaced high-saturation pink/candy accents with a crisp Monochrome + Slate Blue (`#2563EB` / `#60A5FA`) palette, unified neutral icon badges, and full edge-to-edge transparency across all screens.
  - Streamlined bottom navigation from 4 tabs to 3 (`Library | Folders | Space`) with automatic `NavigationRail` adaptation on wide screens (`>= 600dp`), consolidating All, In-Progress, Favorites, Bookmarks, and History into single-row filter capsules on Library.
  - Upgraded multi-select to a floating obsidian action island (`Play`, `Vault`, `Delete`, `More ⋯`), added a dismissible floating continue-watching capsule, and unified the portrait/landscape player under Cinema Dark (`#0B0D11`) with single-row action capsules and inline bookmark jump pills.
- **Dual-Edition Gradle Architecture & Self-Hosted Ktor Backend**:
  - Split `:app` into `offline` (`cn.yingxia.player`, permanent zero network permissions) and `sync` (`cn.yingxia.player.sync`) product flavors via `SyncExtensionBridge`.
  - Added `:shared-protocol` (`ZeroKnowledgeCrypto` with PBKDF2-HMAC-SHA256 600,000 iterations + AES-256-GCM) and self-hosted Kotlin + Ktor `:server` supporting `HTTP 206 Partial Content` range streaming, Zero-Knowledge snapshot sync, and SHA-256-verified `.vault` ciphertext cold backup.

### Performance & Security Hardened
- Enabled R8 minification (`isMinifyEnabled = true`) and resource shrinking (`isShrinkResources = true`) for release builds with explicit Tink, Room, and Kotlinx Serialization ProGuard rules.
- Added optional hardware-backed Android Keystore `BiometricPrompt` quick unlock for the Encrypted Vault (`VaultBiometric`), invalidated automatically on password changes or new biometric enrollments.
- Added password-encrypted local backup export/restore (`.yxbackup.enc`), automatic zero-fill wipe of temporary `cacheDir/backup/` share files (`LibraryBackup.clearShareCache`), `MAX_SHARED_VIDEO_URIS = 100` guard in `GalleryShare`, replaced Coil/OkHttp with a zero-network pure-Android `LocalVideoThumbnails` in-memory `LruCache` loader, Compose `key` + `contentType` + `hasActiveVideo` recomposition isolation, and DEX-level verification in `scripts/check-apk-privacy.py`.

---

## [1.9.1] - 2026-11-26

### Fixed
- **Fixed Library Check freeze & ANR crash**: Moved `startLibraryScan()`, `Sha256Check`, and `ReadableVideoCheck` completely off the main thread onto `Dispatchers.IO`, added per-chunk cancellation checks (no longer swallowing `CancellationException`), hardened corrupt-file / file-descriptor cleanup, and fixed progress counter overflow.
- **Much faster duplicate scanning**: Added exact byte-size pre-filtering (`duplicateCandidateIds`) so videos with unique byte sizes skip unnecessary full-file SHA-256 reads while preserving exact byte-for-byte duplicate accuracy.

---

## [1.9.0] - 2026-11-25

### Added
- **Uninstall-Safe Pure-Ciphertext Vault Backup & Phone Migration (Zero Network, Zero New Permissions)**:
  - Bind any local external folder (defaulting to `Documents/YingXiaVault`, or an OTG USB drive / SD card) via Android SAF to maintain an **incremental pure AES-256-GCM ciphertext mirror** (`keyset.bin`, canonical `UUID.{vault,meta,playback}`, `.nomedia`, and checksum-only `manifest.json`).
  - **Zero plaintext exposure**: No video plaintext, no video titles or original filenames, no passwords, and no device-bound KeyStore security-question envelopes are ever written to the backup folder.
  - **Optional auto-sync toggle**: Automatically syncs incremental ciphertext changes after encrypting, deleting, or deduplicating vault items (non-blocking if external storage is temporarily disconnected).
  - **Direct restore after reinstall or phone switch**: Opening an empty vault presents **Restore from encrypted backup** which opens directly at `Documents/YingXiaVault` via `EXTRA_INITIAL_URI` (and smartly discovers `YingXiaVault/` even if a parent directory is picked; auto-detects on Android 8–10). Verifies password + every ciphertext & plaintext SHA-256 before committing, and detaches old-device MediaStore removal URIs so unrelated gallery files on the new device can never be touched.
  - **Safe merge into an existing vault**: Merge another backup into an already-unlocked vault using its own password, re-encrypting in memory with automatic SHA-256 content deduplication without altering existing entries or your current vault password.

---

## [1.8.2] - 2026-11-25

### Fixed & Hardened
- **Bounded backup import (10 MB streaming limit)**: Importing a local backup JSON now streams with a strict 10 MB byte cap (`LibraryBackup.readBoundedUtf8`) and aborts immediately if exceeded, preventing out-of-memory crashes when an oversized file (such as a video) is picked by mistake, with localized error notices in English and Simplified Chinese.
- **Off-main-thread backup I/O**: Moved backup export, share, and import file I/O onto `Dispatchers.IO` so UI remains responsive.
- **CI reliability**: Added a CI-only Google Maven Central mirror init-script and Gradle cache in `.github/workflows/android.yml` (keeping `settings.gradle.kts` clean for F-Droid / IzzyOnDroid builds).

---

## [1.8.1] - 2026-11-25

### Added
- **Batch encrypt whole folders into the Encrypted Vault**:
  - Added a one-tap lock button directly on every folder card in the **Folders** tab (both system media folders and user-managed folders).
  - Added a quick **Encrypt all videos in this folder** button in the top bar when browsing inside any folder (respects active search filters).
  - Upgraded the in-vault **Encrypt local videos** picker with **By folder / All videos** tabs, real-time folder/title search (with pinyin initials), tri-state folder checkboxes to select entire folders in one tap, and expandable per-folder video rows to fine-tune exclusions.

---

## [1.8.0] - 2026-11-24

### Added
- **Full English & Simplified Chinese internationalization (i18n)**: All screens — including gallery sharing, one-time playback without history, quick vault lock, player controls, vault flows, and library checks — now support English (default) and Simplified Chinese (`values-zh`).
- **In-app language switcher & Android 13+ per-app language support**: Switch between Follow system / 简体中文 / English inside **Me → Playback & Appearance**, plus `locales_config.xml` integration.
- **F-Droid & IzzyOnDroid readiness**: Added `fastlane/metadata/android/` store metadata (`en-US` and `zh-CN` descriptions, icon, feature graphic, and screenshots) and disabled APK `dependenciesInfo` signing blocks for clean FOSS builds.
- **Project landing page & English documentation**: Added GitHub Pages website (`docs/index.html`), Social Preview artwork (`docs/social-preview.png`), and English vault architecture guide (`docs/VAULT_ARCHITECTURE.md`).
- **Vault batch import, deduplication & cleanup**:
  - Android 11+ multi-video vault moves now encrypt and verify all selected videos first, then request **one single system deletion dialog** (up to 100 items per batch) and survive background auto-locking without dropping pending confirmation items.
  - Cross-entry deduplication by source ID, canonical/removal URI, and exact byte size + SHA-256 content digest prevents duplicate vault copies when re-importing across gallery share, local library, or restored files.
  - Added one-tap **Remove all originals** for pending items, one-tap verified **Clean duplicate copies** for legacy duplicates, an optional **Also delete vault copy after verified restore** checkbox, direct **Encrypt & remove originals** option for MediaStore gallery shares, and upgraded the in-vault video picker with thumbnails, select-all, and already-in-vault badges.

### Fixed
- Fixed duplicate encrypted copies being created when moving/sharing the same video across different URI aliases or after restoring to the gallery.
- Improved OEM ROM compatibility in `verifySystemDeletion` when `ContentResolver.query` returns `null` after a system MediaStore delete.
- Player fullscreen gesture feedback icon now tracks brightness vs. volume structurally rather than relying on localized text prefixes.
- Instrumented UI, gallery share, and vault tests now resolve localized string resources so tests pass on both English and Chinese devices.

---

## [1.7.4] - 2026-11-19

### Added
- **Share directly from system gallery (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`)**: Play a video once without history or save encrypted copies to the vault without granting full library access.
- **Watch without history**: One-time playback from the video card menu or multi-select bar that starts from the beginning without altering prior resume positions or watch history.
- **One-tap quick lock during vault playback**: Tap **Lock** right inside the vault player (including fullscreen and touch-guard modes) to immediately stop playback, lock the vault, and return to Home.

---

## [1.7.2] - 2026-11-19

### Added
- **Custom vault recovery questions**: Write three custom questions (up to 80 characters each) or customize suggestions from the built-in list, stored encrypted on-device via Android Keystore for completely offline password reset.
- Preserved remaining answers when going back to edit a single question during recovery setup.
- Automated privacy verification script (`scripts/check-apk-privacy.py`) and GitHub Actions CI workflow (`.github/workflows/android.yml`).

---

## [1.7.0 - 1.7.1] - 2026-11-18

### Added
- Optional offline 3-question vault password recovery bound to the local device's Android Keystore with 5-attempt cooldown protection.
- Pick 3 preset questions before entering answers.

---

## [1.6.1] - 2026-11-18

### Added
- Shared playback controls inside the Encrypted Vault: 8 playback speeds, press-and-hold 2×/3× boost, A–B loop, sleep timer, crop black bars, favorites, and encrypted per-video timestamped bookmarks.
- Minimalist Material 3 home gallery and extended player seekbar polish.

---

## [1.6.0] - 2026-11-17

### Added
- **Offline Encrypted Video Vault**: Google Tink `AES256_GCM_HKDF_1MB` Streaming AEAD encryption, PBKDF2-HMAC-SHA256 (600,000 iterations), on-demand in-memory decryption playback (`VaultDataSource`), SHA-256 verification before requesting gallery deletion, `FLAG_SECURE`, and instant background locking.

---

## [1.5.1] - 2026-11-16

### Changed
- Removed all network, casting, WebDAV, and Wi-Fi transfer features and stripped `INTERNET` / `ACCESS_NETWORK_STATE` permissions so YingXia is strictly 100% offline.

---

## [1.2.1 - 1.4.0] - 2026-11-13 to 2026-11-16

### Added
- Core local video library, Media3 playback engine, 8 speeds + press-and-hold boost, A–B loop, timestamped bookmarks, sleep timer, Chinese pinyin initials search, verified local folder management, offline duplicate/unreadable file scanner, and local JSON metadata backup/restore.
