# YingXia 2.0.1 · Fix OEM Gallery Share Compatibility

[简体中文](更新说明.md)

## What's new in 2.0.1

**YingXia 2.0.1** is a hotfix release (including the full 2.0.0 minimalist redesign, R8 ~4.2 MB optimization, biometric vault unlock, encrypted local backup, and dual-edition architecture):

- **Fixed "No readable local videos received" when sharing from OEM galleries**:
  - Accepted Android multi-user numeric authority prefixes (such as `content://0@media/...` and `content://0@com.miui.gallery.open/...`) while continuing to block arbitrary `userInfo` spoofing and self-provider URIs.
  - Handled single-video `ACTION_SEND` intents carrying distinct `EXTRA_STREAM` and `clipData` aliases as well as `*/*` / `null` share MIME types (with `MediaMetadataRetriever` video verification downstream).
  - Hardened `LocalMedia` `0@media` normalization, OEM `FileProvider` `OpenableColumns` queries, and video-track detection.
- **Upgrade in place**: `YingXia-2.0.1.apk` is signed with the same release certificate as 2.0.0 / 1.9.1 / 1.8.x. **Install directly over your existing version — do not uninstall first.**
