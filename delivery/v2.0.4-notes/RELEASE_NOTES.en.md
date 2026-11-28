# YingXia Player v2.0.4 Release Notes

> Adds selectable long-press boost speeds (`1.5× / 2× / 2.5× / 3× / 4×`) right inside the player speed sheet & Settings, plus live horizontal slide-to-adjust while holding. Includes all v2.0.1–v2.0.3 improvements. Signed with the same certificate — install directly over your existing app without uninstalling.

## What's New in v2.0.4

- **5 Selectable Long-Press Boost Speeds (`1.5× / 2× / 2.5× / 3× / 4×`)**:
  - Pick your preferred default long-press speed directly from the **Player Speed Sheet** (works in both ordinary and encrypted Vault playback) or in **Space → Settings**.
  - Persisted automatically and preserved across encrypted backups and sync snapshots.
- **Live Slide-to-Switch While Holding**: After long-pressing the video to enter temporary boost, slide left or right without lifting your finger to step smoothly across `1.5×`–`4×` with subtle haptic feedback; releasing restores your original playback speed (never slowing down if already playing faster).

## Includes Prior 2.0.x Improvements

- **v2.0.3**: Portrait player details screen now matches Light/Dark theme seamlessly.
- **v2.0.2**: Restored side-by-side 2-column portrait video grid and 16dp margins.
- **v2.0.1**: Fixed OEM gallery share rejection (`0@media`, dual `EXTRA_STREAM` + `clipData`, `*/*`).

## Which APK to Install

- **`YingXia-2.0.4.apk` (Recommended · Offline Core)**: `cn.yingxia.player` — zero network permissions and zero sync/network DEX classes.
- **`YingXia-2.0.4-sync.apk` (Optional · Self-Hosted Sync Edition)**: `cn.yingxia.player.sync` — installs alongside Offline Core and adds self-hosted Ktor E2EE sync & HTTP 206 streaming.
