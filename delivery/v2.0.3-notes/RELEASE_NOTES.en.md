# YingXia Player v2.0.3 Release Notes

> Aligns the portrait video player details screen with the user's active Light/Dark theme (eliminating the abrupt white-to-black jump in Light Mode) while preserving the pure-black 16:9 video frame and fullscreen experience. Includes all fixes from v2.0.1 and v2.0.2. Signed with the same certificate — install directly over your existing app without uninstalling.

## What's Improved in v2.0.3

- **Cohesive Light/Dark portrait player**: Removed forced Dark Mode on the portrait player details area (`PlayerUi.kt`). The top status bar strip and 16:9 video viewport remain pure black for distraction-free viewing, while the title section, action capsules, inline bookmark pills, queue/bookmark tabs, playlist cards, modal bottom sheets, and bottom system navigation bar icons now seamlessly match Light or Dark mode.

## Includes Prior 2.0.x Fixes

- **v2.0.2**: Guaranteed side-by-side 2-column vertical-scrolling video grid on portrait phones (`ResponsiveVideoGridCells`) and unified 16dp margins.
- **v2.0.1**: Fixed OEM gallery share rejection (`0@media` numeric multi-user prefixes, dual `EXTRA_STREAM` + `clipData`, `*/*` MIME).

## Which APK to Install

- **`YingXia-2.0.3.apk` (Recommended · Offline Core)**: `cn.yingxia.player` — zero network permissions and zero sync/network DEX classes.
- **`YingXia-2.0.3-sync.apk` (Optional · Self-Hosted Sync Edition)**: `cn.yingxia.player.sync` — installs alongside Offline Core and adds self-hosted Ktor E2EE sync & HTTP 206 streaming.
