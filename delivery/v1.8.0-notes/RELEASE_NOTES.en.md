# YingXia 1.8.0 · Full English & Chinese UI + F-Droid Readiness

[简体中文](更新说明.md)

## What's new in 1.8.0

YingXia 1.8.0 brings full **English and Simplified Chinese** support across the entire app, making the zero-network local video player and AES-256-GCM encrypted vault accessible to everyone worldwide.

- **Full bilingual interface.** Automatically follows your system language (English default for non-Chinese devices, Simplified Chinese for Chinese locales) — including 1.7.4's gallery sharing, one-time watch without history, and quick vault lock — and supports instant in-app switching in **Me → Playback & Appearance → Language** plus Android 13+ per-app language settings.
- **FOSS store readiness.** Added Fastlane metadata (`en-US` and `zh-CN`) and disabled Google Play `dependenciesInfo` APK blocks for clean IzzyOnDroid and F-Droid builds.
- **Bug fixes & polish.** Fixed fullscreen brightness/volume gesture indicator detection under non-Chinese locales and updated instrumented UI, gallery share, and vault tests for bilingual environments.
- **Upgrade in place.** Install the APK directly over 1.7.4 (or earlier 1.7.x). **Do not uninstall first** — uninstalling deletes the encrypted vault.

**Verification.** Requires Android 8.0 or later. All unit tests, Release build, Release Lint, and `scripts/check-apk-privacy.py` passed (zero `INTERNET` / `ACCESS_NETWORK_STATE` permissions, cloud backup disabled, debugging disabled). Please run a quick physical-device check before publishing this release and replace this sentence with your device model and Android version.
