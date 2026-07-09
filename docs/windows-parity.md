# Windows (WPD) feature parity

Tracks the work needed on **`WpdBackend`** (Windows Portable Devices, the Windows-native MTP path) to
match **`NativeLibMTP`** (libmtp, Linux/macOS). **Keep this file updated whenever a change touches the
`MtpBackend` SPI or adds a feature that depends on a backend primitive.**

## How parity works

- There are two `MtpBackend` implementations, selected per platform by `MtpBackend.defaultBackend()`:
  `NativeLibMTP` (libmtp via FFM) and `WpdBackend` (WPD COM via FFM).
- Everything **above** the SPI is platform-neutral and needs no per-backend work:
  - the provider's attribute views (`basic`, `mtp`, `audio`),
  - the lazy read channel (`MTPLazyReadChannel`) and eager `newInputStream`,
  - the audio tag readers (`org.meltzg.fs.mtp.audio.*` — `FlacMetadataReader`, `Mp3MetadataReader`, …),
    which are pure Java over `RangedByteSource`.
- These light up on Windows automatically **as soon as the backend implements the primitive they call.**
- Capability gating keeps unimplemented primitives graceful: `MtpBackend.supportsPartialReads()` lets the
  higher layers degrade instead of failing (the `audio` view returns null tags; the read channel falls
  back to an eager whole-file download).

## Status

| Capability | libmtp | WPD | Gap |
|---|---|---|---|
| Whole-object read (`getFile`) | ✅ | ✅ (IStream::Read) | none |
| Eager `newInputStream` / `Files.copy` | ✅ | ✅ | none |
| `mtp` view (device-index metadata) | ✅ | ✅ | none |
| `sendFile` audio object-format inference | ✅ | ✅ (`audioFormatForFilename`) | none |
| **Ranged read (`readPartial`)** | ✅ | ❌ | **implement via IStream Seek+Read** |
| `supportsPartialReads()` | ✅ `true` | ❌ `false` (default) | flip to `true` once `readPartial` lands |
| Lazy read channel (`newByteChannel`) | ✅ lazy | ⚠️ eager fallback | correct, but not lazy until `readPartial` lands |
| `audio` view (embedded FLAC/MP3 tags) | ✅ | ❌ (gated off) | needs `readPartial`; then automatic |
| FLAC / MP3 tag readers | ✅ (neutral) | ✅ (neutral) | none — pure Java, backend-agnostic |

Legend: ✅ done · ⚠️ works via fallback · ❌ missing.

## Tasks

### 1. `WpdBackend.readPartial` — the one blocking gap
Implement `byte[] readPartial(DeviceHandle, String itemId, long offset, int maxBytes)` and override
`supportsPartialReads()` to return `true`.

- WPD already streams whole objects through an `IStream` in `getFile` (`IStream::Read`). Reuse that path:
  open the object's default resource stream, `IStream::Seek(offset, STREAM_SEEK_SET)`, then read up to
  `maxBytes`.
- Match `NativeLibMTP.readPartial`'s contract exactly: return the bytes actually read — shorter than
  `maxBytes` near end-of-object, empty at or past it.
- **Payoff:** once this lands, the lazy channel, the `audio` attribute view, and *every* current and
  future audio tag reader work on Windows with no further changes.
- **Caveat:** confirm the resource `IStream` supports arbitrary `Seek` (`STGM`/seek capability). If a
  device or stream does not, either fall back to reading-and-discarding up to `offset`, or leave
  `supportsPartialReads()` device-conditional so the higher layers keep degrading gracefully.

### 2. Verify capability gating end-to-end on Windows
With `readPartial` implemented, confirm on a real Windows host that:
- `supportsPartialReads()` → `true` routes `newReadableByteChannel` to `MTPLazyReadChannel`, and
- `Files.readAttributes(path, "audio:*")` returns embedded tags (currently null on Windows).

Mirror the libmtp device tests: `flacTagsReadFromHeaderRecoverRealTitle`, `audioViewRecoversEmbeddedTagsFromFlac`,
`partialReadPullsAudioHeaderWithoutTransferringWholeObject`.

## Notes

- The audio tag readers require **no** per-platform work — they operate on `RangedByteSource`, which any
  backend satisfies through `readPartial`. New format readers (M4A, OGG/Opus, WAV — see
  `AudioTagReaders`) inherit Windows support for free once task 1 is done.
- Keep the status table current as new formats and features land.
