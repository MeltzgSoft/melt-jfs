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
| In-place object editing (`supportsObjectEditing` / `overwriteFile`) | ✅ (Android edit extension, gated by `LIBMTP_Check_Capability`) | ✅ (same extension via `SendCommand`), grows included | none — stale post-edit sizes are corrected by the bridge's size overlays |

Legend: ✅ done · ⚠️ works via fallback · ❌ missing.

Functional parity: verified end-to-end on a real Windows host against **two** devices — an
Astell&Kern AK100_II and a FiiO M11 Plus — across all four of their storages. The full integration
suite is green over WPD (208 tests: 206 passed, 2 legitimate skips, 0 failures), including
`partialReadPullsAudioHeaderWithoutTransferringWholeObject` and the `audioViewReadsUploaded*Tags` /
`uploadedId3v23Mp3TagsAreReadBackViaAudioView` suites, and it is green on Linux/libmtp with the same
devices.

No caveats remain on the FiiO. The intermittent failures previously recorded here — the growing
replace on its SD card, storages transiently disappearing, sessions wedging mid-run — were all
symptoms of WPD-side defects in this backend, not of the device; they are described under "Growing a
file", "Device lifetime" and "Resource ownership". Two consecutive full runs now pass with the
suite's per-test device open/close churn intact, which is the load that used to wedge the driver
within a single run.

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

**Grows go through the in-place path, like libmtp.** An earlier version of this code refused them,
on the theory that a grow half-landed and left whole-object reads truncated. That was wrong, and the
`growProbe` dev task (`src/dev/.../MTPGrowProbe.java`) measured it on every storage of both devices —
grow a 5-byte object to 26 bytes, then compare the reported size, a `GetPartialObject` read, and a
whole-object transfer:

| Storage | Reported size | GetPartialObject | Whole-object read |
|---|---|---|---|
| FiiO / Internal shared | 26 ✅ | 26 ✅ | 26 ✅ |
| FiiO / M11 Plus Micro SD | **5 (stale)** | 26 ✅ | 26 ✅ |
| AK100_II / Internal | **5 (stale)** | 26 ✅ | 26 ✅ |
| AK100_II / SD card | **5 (stale)** | 26 ✅ | 26 ✅ |

Every edit command is accepted and the full new content reads back correctly everywhere, by both read
paths. Whole-object reads are *not* truncated — `getFile` streams `IStream::Read` to EOF and never
consults the reported size. The only defect is `WPD_OBJECT_SIZE` staying at the pre-edit value on
three of the four storages; it does not heal over time, nor across a device reconnect, so it is the
device's own metadata rather than a driver-side cache.

A stale size is not cosmetic — the attribute views and `MTPLazyReadChannel` bound reads by it, so an
uncorrected short value truncates every later read of a file that grew. `MTPDeviceBridge` therefore
carries the length actually written in `sizeOverlays`, alongside the existing rename overlays and
reconciled the same way: the overlay is applied wherever the item is listed and dropped as soon as
the device reports the new length itself. This is very likely what libmtp has been doing all along —
it serves size from its own cached `LIBMTP_file_t` after an edit instead of re-asking the device,
which is why the same devices look correct on Linux.

Refusing grows was worse than the problem it avoided: it forced a Windows-only delete + re-create
under the same name, which races the asynchronous-delete window on exactly the devices the in-place
path exists to protect. That fallback was the direct cause of the intermittent
`appendExtendsExistingFile` / `moveReplacesExistingTarget` failures on the FiiO SD card, and its
retry loop hammered the device with repeated `SendObjectInfo` for a name the device still held.
`overwriteFile` now falls back only for objects larger than `SendPartialObject`'s 32-bit length.

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

## Device lifetime

**Release every interface obtained from the device before `IPortableDevice::Close`.**
`IPortableDeviceContent` and `IPortableDeviceProperties` hold references into the driver's
per-client session; closing while they are outstanding leaves that session pinned instead of torn
down. The leak is permanent, and once enough accumulate WpdMtpDr stops handing the device out —
`IPortableDevice::Open` then blocks **indefinitely, with no timeout**, wedging every client of that
device including File Explorer. `WpdBackend.closeInterfaces` is the single teardown used by the
success, failure and non-MTP-device paths; do not reintroduce a `Close`-then-release ordering, and
do not release only the device pointer on an open failure.

This is the sharpest libmtp/WPD asymmetry in the codebase. `LIBMTP_Release_Device` closes a USB
handle: no reference graph to get wrong, no driver-side session to strand. So the integration
suite's per-test `MTPDeviceBridge.close()` + reopen — 50+ full device open/close cycles per storage —
does libmtp no harm while it was steadily poisoning the WPD driver. (It does cost libmtp wall-clock
time — each reopen re-claims the USB interface and re-enumerates storages, the largest fixed cost of
the Linux suite — but that is the price of the churn's diagnostic value.) That churn is a useful leak
detector; prefer fixing what it exposes over reducing it.

## Resource ownership

Two more places where a COM object must survive an error path, both previously wrong:

- **`sendFile` must release the object `IStream` on every path.** After
  `CreateObjectWithPropertiesAndData` the device is mid-`SendObject`; abandoning the stream leaves it
  there and corrupts the session for every later request — the same hazard as the resource stream
  described under "Why *not* the resource `IStream`". The create has already sent `SendObjectInfo`, so
  a failed transfer can also leave a partial object squatting on the filename; it is deleted so a
  caller's retry starts clean. Every step of that cleanup is best-effort and must never displace the
  original failure or skip the release.
- **Enumeration failures are not end-of-list.** `IEnumPortableDeviceObjectIDs::Next` returning a
  failure HRESULT must propagate, not `break`. Treating it as the end of the list silently yields a
  truncated listing — and when enumerating the device root, that meant a whole storage vanishing and
  every path under it becoming `NoSuchFileException`. Relatedly, `listStorages` caches its result per
  open device (only a *complete* enumeration is cached) so path resolution does not re-enumerate over
  the wire on every call; libmtp serves the same list from state captured at open.

## Notes

- **Symptoms once blamed on the device were ours.** An earlier version of this note attributed
  intermittent `IStream::Write` failures, storages transiently disappearing, and wedged sessions to
  the FiiO stalling mid-request. Most of that traced back to four WPD-side defects, all of which
  libmtp is structurally immune to — see "Device lifetime" and "Resource ownership" below. Suspect
  this code before the hardware.
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
