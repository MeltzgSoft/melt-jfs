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
  - the audio tag readers (`org.meltzg.fs.mtp.audio.*` — `FlacMetadataReader`, `Mp3MetadataReader`,
    `Mp4MetadataReader`, `OggMetadataReader`, `WavMetadataReader`), which are pure Java over
    `RangedByteSource`.
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
| **Ranged read (`readPartial`)** | ✅ | ✅ (MTP GetPartialObject via `SendCommand`) | none |
| `supportsPartialReads()` | ✅ `true` | ✅ `true` | none |
| Lazy read channel (`newByteChannel`) | ✅ lazy | ✅ lazy | none |
| `audio` view (embedded tags) | ✅ | ✅ (lit up by `readPartial`) | none |
| Audio tag readers (FLAC/MP3/MP4/Ogg/Opus/WAV) | ✅ (neutral) | ✅ (neutral) | none — pure Java, backend-agnostic |
| In-place object editing (`supportsObjectEditing` / `overwriteFile`) | ✅ (Android edit extension, gated by `LIBMTP_Check_Capability`) | ⚠️ falls back to delete + send | benign — see note below |

Legend: ✅ done · ⚠️ works via fallback · ❌ missing.

Functional parity: verified end-to-end on a real Windows host (Astell&Kern AK100_II) — the full
`MTPFileSystemIntegrationTest` suite passes on WPD across both storages, including
`partialReadPullsAudioHeaderWithoutTransferringWholeObject` and the `audioViewReadsUploaded*Tags` /
`uploadedId3v23Mp3TagsAreReadBackViaAudioView` suites. The one open gap (in-place object editing) is
behavioral only — every operation still succeeds on WPD through its fallback.

### The in-place editing gap

`MTPDeviceBridge.writeFile` rewrites an existing object in place (BeginEditObject / TruncateObject /
SendPartialObject / EndEditObject) when the backend reports `supportsObjectEditing`, because some
devices apply deletes to their MTP database asynchronously and reject a send that reuses a
just-deleted name for the rest of the session (observed on the FiiO M11 Plus SD card; see the
tombstone handling in `MTPDeviceBridge`). `WpdBackend` inherits the SPI default (`false`) and takes
the delete + send fallback.

This is fine on Windows: the WPD driver (WpdMtpDr) keeps its own host-side object model and creates
objects via `SendObjectPropList`, which masks the asynchronous-delete device behavior — the FiiO
failures never reproduced over WPD. If in-place editing is ever wanted on Windows anyway, the seam
already exists: the Android opcodes (`BeginEditObject` 0x95C4, `SendPartialObject` 0x95C2,
`TruncateObject` 0x95C3, `EndEditObject` 0x95C5) can be issued through the same
`IPortableDevice::SendCommand` MTP pass-through that `readPartial` uses.

## How `readPartial` works on WPD

`WpdBackend.readPartial` issues the MTP **GetPartialObject** operation as a raw MTP command through
`IPortableDevice::SendCommand` (the WPD MTP pass-through, `WPD_CATEGORY_MTP_EXT_VENDOR_OPERATIONS`).
Each call is a bounded request→data→response transaction: initiate
(`WPD_COMMAND_MTP_EXT_EXECUTE_COMMAND_WITH_DATA_TO_READ`), read the data phase in chunks
(`…_READ_DATA`), then always close it (`…_END_DATA_TRANSFER`).

- The MTP object handle is the hex after the `"o"` prefix of the WPD object-id string (the Microsoft
  WpdMtp driver's id convention).
- Opcode is probed on first use: standard `GetPartialObject` (0x101B, 32-bit offset), falling back to
  the Android `GetPartialObject64` (0x95C1, 64-bit offset); the working opcode is cached. The AK100_II
  uses 0x101B.

### Why *not* the resource `IStream`
An earlier attempt reused `getFile`'s path — `IPortableDeviceResources::GetStream` + `IStream::Seek` +
`Read`. That stream is a **whole-object** transfer (a full MTP `GetObject` data phase): reading only a
prefix and releasing the stream leaves the device mid-transfer and **hard-wedges** it (it drops off the
bus). Aborting with `IPortableDeviceContent::Cancel` avoided the disconnect but corrupted the MTP
session, cascading `IOException`s into the *next* operations. `SendCommand`/`GetPartialObject` is the
correct primitive because the transaction is bounded and self-completing — it is also what libmtp uses.

## Notes

- The audio tag readers require **no** per-platform work — they operate on `RangedByteSource`, which any
  backend satisfies through `readPartial`. All formats wired into `AudioTagReaders` (FLAC, MP3, MP4/M4A,
  Ogg Vorbis, Opus, WAV) — and any added later — get Windows support for free.
- Keep the status table current as new formats and features land.
