# YingXia Player v2.0.2 Release Notes

> Hotfix restoring the side-by-side 2-column video wall on standard 360dp phones (and zoomed display sizes), plus all OEM gallery-share fixes from v2.0.1. Signed with the same certificate as v1.9.1 / v2.0.0 / v2.0.1 — install directly over your existing app without uninstalling to preserve your local encrypted vault.

## What's Fixed in v2.0.2

- **Always 2-column side-by-side video grid on portrait phones**: Replaced `GridCells.Adaptive(162.dp)` (which collapsed into a single column on 360dp–376dp Android phones) with `ResponsiveVideoGridCells` guaranteeing at least 2 side-by-side columns scrolling vertically on all portrait phones while scaling up to 3–4+ columns on landscape and tablet screens.
- **Consistent 16dp margins & cleaner filter capsules**: Unified horizontal margins to `16.dp` across Library, Folders, Space, Settings, and Bookmarks, and tightened filter pill spacing so `All`, `In Progress`, and `Favorites` remain legible on 360dp screens.

## Includes v2.0.1 Fixes

- **OEM Gallery Share compatibility**: Supports Android multi-user numeric authority prefixes (`content://0@media/...`, `content://0@com.miui.gallery.open/...`), dual `EXTRA_STREAM` + `clipData` aliases on `ACTION_SEND`, and `*/*` MIME types while still blocking spoofed `userInfo`, self-provider URIs, and non-video files.

## Which APK to Install

- **`YingXia-2.0.2.apk` (Recommended · Offline Core)**: `cn.yingxia.player` — zero `INTERNET` / `ACCESS_NETWORK_STATE` permissions and zero sync/network DEX classes.
- **`YingXia-2.0.2-sync.apk` (Optional · Self-Hosted Sync Edition)**: `cn.yingxia.player.sync` — installs alongside Offline Core and adds self-hosted Ktor E2EE sync & HTTP 206 streaming.
