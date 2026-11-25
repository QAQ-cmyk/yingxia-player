# YingXia 1.8.1 · Folder Batch Encryption

[简体中文](更新说明.md)

## What's new in 1.8.1

Based on community feedback, **YingXia 1.8.1** adds **folder-level batch encryption** so you no longer have to pick videos one by one when your phone has hundreds of files:

- **One-tap folder encryption from the Folders tab**: Every folder card (both system folders and user-managed folders) now has a lock action button to encrypt the entire folder into the vault in one go (using Android 11+'s single-dialog batch removal and automatic deduplication).
- **Quick encrypt inside any folder**: When browsing inside a folder, tap the lock icon in the top bar to encrypt all visible videos in that folder (including filtered search results).
- **By folder / All videos vault picker**: The in-vault **Encrypt local videos** dialog now supports switching between **By folder** and **All videos**, instant search filtering, tri-state folder checkboxes for one-tap whole-folder selection, and expandable folder rows to uncheck individual items.
- **Upgrade in place**: Signed with the same release certificate as 1.8.0 / 1.7.x. **Install directly over your existing version — do not uninstall first** (uninstalling deletes the encrypted vault).

**Verification**: Android 8.0+. JVM unit tests (24/24), Android 14 (API 34) emulator `VaultConsentUiTest` (3/3, covering both Folders tab and By-folder vault picker batch encryption), Android Lint (0 errors), and `scripts/check-apk-privacy.py` (`PASS`: zero `INTERNET` / `ACCESS_NETWORK_STATE` permissions, cloud backup disabled) all passed.
