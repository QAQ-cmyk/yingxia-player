# Encrypted Vault Architecture & On-Disk Format

> 中文版详见 [docs/加密保险箱.md](加密保险箱.md)

YingXia's Encrypted Vault is designed so private local videos can be stored, seeked, and played on Android **with zero network permissions and zero plaintext video cache on disk**.

## Threat Model & Security Boundaries

### What the vault protects against
- Casual browsing of the phone's system gallery, file manager, or third-party media apps.
- Ordinary search, watch history, thumbnail disk caches, and JSON record backups leaking private video names or bookmarks.
- Screenshots and screen recordings inside vault screens (`WindowManager.LayoutParams.FLAG_SECURE`).
- Cloud sync or device-migration exfiltration of app private files (`allowBackup="false"`, `fullBackupContent="false"`, stored in `Context.noBackupFilesDir`).
- Tampering or cross-video record swapping (authenticated encryption with per-video AAD).

### Explicit non-goals (out of scope)
- Rooted / compromised OS kernels, debuggers, or malicious apps with superuser access.
- Copies already uploaded by your system gallery's cloud sync, system trash bins, or third-party gallery caches.
- Recovering deleted vaults after uninstalling the app or clearing app data.
- Password recovery when on-device recovery questions were **not** enabled beforehand.

---

## Cryptographic Design (`app/src/main/java/cn/yingxia/player/vault/`)

All vault files reside inside `Context.noBackupFilesDir/encrypted-vault/`, which is never exposed via `FileProvider`.

| File | Cryptographic Format | Purpose |
| --- | --- | --- |
| `keyset.bin` | ASCII header `YINGXIA-VAULT-1` + 16B random salt + 12B random nonce + AES-256-GCM ciphertext (`PBKDF2-HMAC-SHA256`, **600,000 iterations**, 256-bit derived key; AAD = header + salt) | Wraps two serialized Google Tink keysets (`StreamingAead` + `Aead`). Passwords and plaintext keysets never touch disk. |
| `<UUID>.vault` | Google Tink **`AES256_GCM_HKDF_1MB`** Streaming AEAD, AAD = `yingxia-vault-v1:video:<UUID>` | Encrypted video payload with 1 MB authenticated chunks supporting random seeking. |
| `<UUID>.meta` | Google Tink **`AES256_GCM`** AEAD, AAD = `yingxia-vault-v1:metadata:<UUID>` | Encrypted UTF-8 JSON storing display name, original filename, MIME type, SHA-256 digest, duration, removal/restore journal state. |
| `<UUID>.playback` | Google Tink **`AES256_GCM`** AEAD, AAD = `yingxia-vault-v1:metadata:playback-v1:<UUID>` | Isolated private playback state: resume position, favorite flag, crop-black-bars flag, and up to 256 timestamped bookmarks. |
| `questions-v3.bin` | 32B `keyset.bin` SHA-256 fingerprint + 12B IV + Android Keystore AES-256-GCM ciphertext (AAD = fingerprint) wrapping custom prompts + HMAC-SHA256-bound rewrapped keyset | Optional offline recovery envelope bound to the current device's Android Keystore and current `keyset.bin`. |

---

## Core Safety Invariants

1. **Atomic ciphertext writes & fsync**: Temporary `.part` and `.tmp` files contain ciphertext only. Files are `fd.sync()`'d and atomically moved before metadata commits.
2. **Verify before deleting gallery originals**: Moving a video into the vault encrypts the stream, re-reads and decrypts the `.vault` ciphertext to confirm byte length and SHA-256 match the original, re-verifies the source file has not changed, and *only then* invokes Android's system deletion consent dialog (`MediaStore.createDeleteRequest` / `RecoverableSecurityException`).
3. **Zero plaintext playback cache**: `VaultDataSource` implements Media3's `BaseDataSource` over Tink's `SeekableByteChannel`. Segments are authenticated and decrypted directly into memory buffers during playback.
4. **Immediate lock on backgrounding**: Moving the app to background cancels active IO jobs, releases `VaultPlaybackSession`, clears in-memory metadata lists, closes every open `SeekableByteChannel`, and wipes session primitives.
5. **Non-destructive error handling**: Damaged `.meta` or `.playback` files are preserved on disk and never silently overwritten or deleted.
6. **Offline recovery hardening**: Custom recovery prompts are encrypted via Android Keystore; 5 consecutive failed reset attempts trigger a persisted 60-second cooldown; successful password reset immediately rotates `keyset.bin` and invalidates the previous recovery envelope via fingerprint mismatch.

---

## Module Map

- `VaultCrypto.kt`: Versioned keyset wrapping (`PBKDF2-HMAC-SHA256` + `AES-256-GCM`), Tink Streaming AEAD / AEAD session lifecycle, atomic writes, SHA-256 digests.
- `VaultStore.kt`: Private directory management, verified encryption/restoration (`Movies/映匣恢复/`), interrupted-restore recovery.
- `VaultRecovery.kt`: Offline 3-question Keystore-bound recovery enrollment, rate-limited reset, and disable flow.
- `VaultDataSource.kt`: Random-access Media3 `DataSource` reading authenticated chunks on demand.
- `VaultVideoRepository.kt`: Isolated private `VideoRepository` (never touches Room, ordinary backups, or Coil thumbnail caches).
- `VaultPlaybackSession.kt`: Vault-owned Media3 player session reusing `PlayerPage` gestures and tools via `PlayerUiSession`.
- `VaultCoordinator.kt`: Foreground-serialized operations, Android system deletion consent reconciliation, background lock enforcement.
