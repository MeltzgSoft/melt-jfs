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
| In-place object editing (`supportsObjectEditing` / `overwriteFile`) | ✅ (Android edit extension, gated by `LIBMTP_Check_Capability`) | ✅ (same extension via `SendCommand`), shrink/same-size only | grows fall back to delete + send — see note below |

Legend: ✅ done · ⚠️ works via fallback · ❌ missing.

Functional parity: verified end-to-end on a real Windows host against **two** devices — an
Astell&Kern AK100_II and a FiiO M11 Plus — across all four of their storages. The full
`MTPFileSystemIntegrationTest` suite passes (204/208, 2 skipped) including
`partialReadPullsAudioHeaderWithoutTransferringWholeObject` and the `audioViewReadsUploaded*Tags` /
`uploadedId3v23Mp3TagsAreReadBackViaAudioView` suites. The 2 remaining failures are a device
limitation on the FiiO M11 Plus SD card only — see "Growing a file" below.

### In-place object editing on WPD

`MTPDeviceBridge.writeFile` rewrites an existing object in place (BeginEditObject / TruncateObject /
SendPartialObject / EndEditObject) when the backend reports `supportsObjectEditing`, because some
devices apply deletes to their MTP database asynchronously and reject a send that reuses a
just-deleted name for the rest of the session (observed on the FiiO M11 Plus — on the SD card *and*
its internal storage; see the tombstone handling in `MTPDeviceBridge`). Contrary to an earlier note
here, the WPD driver does **not** mask that behavior: it reproduces over WPD.

`WpdBackend` therefore implements the extension, issuing the Android opcodes (`BeginEditObject`
0x95C4, `SendPartialObject` 0x95C2, `TruncateObject` 0x95C3, `EndEditObject` 0x95C5) through the same
`IPortableDevice::SendCommand` MTP pass-through that `readPartial` uses. The sequence mirrors the
libmtp backend: truncate to zero, then stream the file from offset 0 (SendPartialObject only extends
from the object's current end, so the object must be emptied first).

Two things to know when touching this code:

- **Command pids matter.** The MTP-ext execute/data commands are a contiguous block in
  `WpdMtpExtensions.h`: without-data 12, to-read 13, to-write 14, read-data 15, write-data 16,
  end-transfer 17 (and GET_SUPPORTED_VENDOR_OPCODES 11). A wrong pid surfaces as `E_NOTIMPL`
  (0x80004001) from the driver, not as a device error.
- **`supportsObjectEditing` is not cached**, because with several devices attached a wrong "no"
  cached from one would disable the in-place path for another. When the driver will not answer the
  vendor-opcode query it returns an optimistic `true`; `overwriteFile` then fails cleanly and the
  caller falls back.

#### Growing a file

`overwriteFile` refuses up front (before issuing any edit command, so the object is left untouched)
when the new content is **larger** than the object's current size, and the caller falls back to
delete + send. Growing an object in place is unreliable **over WPD**: on the AK100_II and the FiiO's
FAT32 SD card every edit command returns MTP OK and the new bytes read back correctly via
`GetPartialObject`, yet the WPD-reported object size stays at the old value, so whole-object reads
come back truncated. The same devices grow correctly through libmtp with the same opcodes (the full
suite passes on Linux), which points at the Windows path — most likely WpdMtpDr's host-side object
cache not refreshing `WPD_OBJECT_SIZE` after pass-through edits — rather than the device firmware.
Until a way to refresh the driver's view is found, grows fall back; overwrites that shrink or keep
the size — the common case, and the one that matters for the asynchronous-delete devices — stay on
the in-place path.

The residual gap: on the **FiiO M11 Plus SD card**, the delete + send fallback for a growing replace
races that storage's asynchronous delete. The retry backoff usually wins, but
`appendExtendsExistingFile` and `moveReplacesExistingTarget` have been observed failing there in one
session and passing in the next. Windows-only; on Linux/libmtp the grow happens in place and the race
never starts.

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

- **The FiiO M11 Plus can stall its MTP session mid-request** (observed as intermittent
  `IStream::Write` failures, a storage transiently disappearing, and renames whose old name lingers
  in listings — the last is compensated by the bridge's rename overlay, which patches the new name
  into listings until the device reports it). Because WpdMtpDr serializes every WPD client through
  one queue, a stall blocks *all*
  of them — the test run hangs, and File Explorer opened against the same device hangs too (Windows
  eventually kills it with an Application Hang event). Explorer's forced restart tears down its WPD
  handles, which cancels the outstanding I/O and unblocks the queue; the request that was in flight
  fails (e.g. HRESULT 0x8007065d). If a run appears hung on the FiiO, this — not the test code — is
  the likely cause.
- **Integration-test artifact names are unique per run.** `MTPFileSystemIntegrationTest` derives every
  file/directory name from a token unique to the JVM run and test-method invocation. On a device that
  applies deletes asynchronously, a name deleted by one test cannot be re-created for the rest of the
  session, so reusing fixed names across tests (or across runs on one connection) poisons them and
  cascades write failures — the suite would pass on a freshly plugged-in device and then fail on every
  later run until it was re-plugged. Do not reintroduce shared constant artifact names.
- The audio tag readers require **no** per-platform work — they operate on `RangedByteSource`, which any
  backend satisfies through `readPartial`. All formats wired into `AudioTagReaders` (FLAC, MP3, MP4/M4A,
  Ogg Vorbis, Opus, WAV) — and any added later — get Windows support for free.
- Keep the status table current as new formats and features land.
