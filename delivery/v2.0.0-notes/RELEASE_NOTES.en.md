# YingXia 2.0.0 · Minimalist 2.0 UI, Security Hardening & Dual Editions

[简体中文](更新说明.md)

## What's new in 2.0.0

**YingXia 2.0.0** is a major release combining a clean minimalist redesign, R8 & security hardening, and optional self-hosted sync — while keeping the default edition **100% offline with zero network permissions**:

- **Minimalist 2.0 UI & UX Overhaul**:
  - Replaced saturated candy accents with Monochrome + Slate Blue (`#2563EB` / `#60A5FA`) and full edge-to-edge transparency.
  - Streamlined navigation to 3 tabs (**Library | Folders | Space**, adapting automatically to `NavigationRail` on screens `>= 600dp`), with unified filter capsules for All, In Progress, Favorites, Bookmarks, and History.
  - Added a floating dark multi-select action island, dismissible continue-watching capsule, and Cinema Dark player layout with single-row action capsules and inline bookmark pills.
- **Dual-Edition Architecture (`offline` & `sync`)**:
  - `YingXia-2.0.0.apk` (`offline` default edition, `cn.yingxia.player`): Physically excludes sync/network code at build time — **zero `INTERNET` or `ACCESS_NETWORK_STATE` permissions and zero network DEX classes**.
  - `YingXia-2.0.0-sync.apk` (`sync` self-hosted edition, `cn.yingxia.player.sync`): Installs cleanly alongside the offline app to connect to your self-hosted Kotlin + Ktor `:server` for HTTP 206 range streaming, zero-knowledge E2EE library/bookmark sync, and SHA-256-verified `.vault` ciphertext cold backup/restore.
- **Performance & Security Hardening**:
  - Enabled R8 code minification and resource shrinking for release APKs (~4.4 MB).
  - Added optional Android Keystore **biometric quick unlock** for the Encrypted Vault (auto-invalidated on password reset or new biometric enrollment), password-encrypted local backup export/restore (`.yxbackup.enc`), zero-fill wipe of temporary backup share cache, and zero-network `LocalVideoThumbnails` memory caching.
- **Upgrade in place**: `YingXia-2.0.0.apk` is signed with the same release certificate as 1.9.1 / 1.8.x / 1.7.x. **Install directly over your existing offline version — do not uninstall first.**

**Verification**: Android 8.0+. `:shared-protocol:test` (1/1), `:server:test` (1/1), offline & sync JVM unit tests (30/30 each), dual-flavor Android Lint (0 errors), Android 14 (API 34) emulator instrumentation suites (offline: 45 passed / 1 API < 29 skipped / 0 failed out of 46; sync end-to-end: 2/2 passed), and `scripts/check-apk-privacy.py` on the final signed `YingXia-2.0.0.apk` (`PASS`) all passed.
