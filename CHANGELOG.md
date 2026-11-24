# Changelog

All notable changes to **YingXia Player (映匣)** are documented here.

## [1.8.0] - Upcoming

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
