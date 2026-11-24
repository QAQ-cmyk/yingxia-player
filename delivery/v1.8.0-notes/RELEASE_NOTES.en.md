# YingXia 1.8.0 · Full English & Chinese UI + F-Droid Readiness

[简体中文](更新说明.md)

## What's new in 1.8.0

YingXia 1.8.0 brings full **English and Simplified Chinese** support across the entire app, making the zero-network local video player and AES-256-GCM encrypted vault accessible to everyone worldwide.

- **Full bilingual interface.** Automatically follows your system language (English default for non-Chinese devices, Simplified Chinese for Chinese locales) — including 1.7.4's gallery sharing, one-time watch without history, and quick vault lock — and supports instant in-app switching in **Me → Playback & Appearance → Language** plus Android 13+ per-app language settings.
- **FOSS store readiness.** Added Fastlane metadata (`en-US` and `zh-CN`) and disabled Google Play `dependenciesInfo` APK blocks for clean IzzyOnDroid and F-Droid builds.
- **Encrypted Vault deduplication & single-dialog batch moves.**
  - Prevented duplicate encrypted copies when moving or sharing the same video across different URI aliases (gallery share vs. MediaStore `external`/`external_primary`, SAF imports, or re-importing restored files) using identity matching plus exact byte-size + SHA-256 verification.
  - On Android 11+, multi-video vault moves now encrypt and verify all selected items first, then request **one single system deletion dialog** (instead of one per video) and preserve pending batch confirmation state across background auto-locking.
  - Added one-tap **Remove all originals**, verified **Clean duplicate copies** for legacy duplicates, an optional **Also delete vault copy after verified restore** checkbox, direct **Encrypt & remove originals** for MediaStore gallery shares, and upgraded the vault picker with thumbnails, select-all, and already-in-vault badges.
- **Bug fixes & polish.** Improved OEM ROM deletion verification when `ContentResolver.query` returns `null`; fixed fullscreen brightness/volume gesture indicator detection under non-Chinese locales and updated instrumented UI, gallery share, and vault tests for bilingual environments.
- **Upgrade in place.** Install the APK directly over 1.7.4 (or earlier 1.7.x). **Do not uninstall first** — uninstalling deletes the encrypted vault.

**Verification.** Requires Android 8.0 or later. JVM unit tests (23/23), Android 14 (API 34) emulator full instrumented suite (`OK (34 tests)` — 33 passed, 1 API<29-only skipped), Release build, Release Lint, and `scripts/check-apk-privacy.py` passed (zero `INTERNET` / `ACCESS_NETWORK_STATE` permissions, cloud backup disabled, debugging disabled). Please run a quick physical-device check before publishing this release and replace this sentence with your device model and Android version.
