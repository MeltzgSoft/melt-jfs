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
  - the audio tag readers (`org.meltzg.audio.*` — `FlacMetadataReader`, `Mp3MetadataReader`,
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
| `sendFile` audio object-format inference | ✅ | ✅ (`audioFormatForFilename`) | none — fixed 2026-08-27 after WPD GUIDs were found word-swapped |
| **Ranged read (`readPartial`)** | ✅ | ✅ (MTP GetPartialObject via `SendCommand`) | none |
| `supportsPartialReads()` | ✅ `true` | ✅ `true` | none |
| Lazy read channel (`newByteChannel`) | ✅ lazy | ✅ lazy | none |
| `audio` view (embedded tags) | ✅ | ✅ (lit up by `readPartial`) | none |
| Audio tag readers (FLAC/MP3/MP4/Ogg/Opus/WAV) | ✅ (neutral) | ✅ (neutral) | none — pure Java, backend-agnostic |
| In-place object editing (`supportsObjectEditing` / `overwriteFile`) | ✅ (Android edit extension, gated by `LIBMTP_Check_Capability`) | ✅ (same extension via `SendCommand`), grows included | none — stale post-edit sizes are corrected by the bridge's size overlays |
| `createDirectory` name-collision check (`FileAlreadyExistsException`) | ✅ | ✅ | none — verified on all four storages in the 2026-07-31 WPD run |
| Deleted-name recovery (`reopenClearsNameReservations` / session recycle) | ✅ `true` | ❌ `false` | **real gap** — a WPD reopen does not clear the reservation, so the recovery is gated off there and the create fails as before (see [2](#2-verify--does-the-deleted-name-reservation-apply-to-folder-creation-on-wpd)) |

Legend: ✅ done · ⚠️ works via fallback or unverified · ❌ missing.

Functional parity: verified end-to-end on a real Windows host against **two** devices — an
Astell&Kern AK100_II and a FiiO M11 Plus — across all four of their storages. The full integration
suite was green over WPD (208 tests: 206 passed, 2 legitimate skips, 0 failures), including
`partialReadPullsAudioHeaderWithoutTransferringWholeObject` and the `audioViewReadsUploaded*Tags` /
`uploadedId3v23Mp3TagsAreReadBackViaAudioView` suites, and it is green on Linux/libmtp with the same
devices.

> **That WPD figure predates PR #25 and no longer describes the current suite.** The suite is now 220
> tests (three new `createDirectory*` cases × four storages), and PR #25 also changed how the suite
> drives the device session — see [Pending Windows work](#pending-windows-work-pr-25). Newer
> 2026-08-28 runs show two distinct WPD-only differences:
>
> | | Tests | Passed | Skipped | Failed |
> |---|---|---|---|---|
> | Linux / libmtp (`device-tests-linux-1298.log`) | 220 | 218 | 2 | 0 |
> | Windows / WPD (`device-tests-windows-1299.log`) | 220 | 216 | 3 | 1 |
>
> Two skips are common to both: `moveNonEmptyDirectoryThrowsWhenNotNativelySupported` on the FiiO's
> two storages, which natively supports directory move. The third, Windows-only, is
> `createDirectorySucceedsAfterDeletingSameName` on FiiO / Micro SD — the deleted-name reservation,
> which the session recycle clears on libmtp but not over WPD. The Windows failure is separate:
> `isHiddenAlwaysFalse` on FiiO / Internal shared storage fails while writing its one-byte setup file,
> after a roughly 133 second gap following `uploadedId3v23Mp3TagsAreReadBackViaAudioView`. A later run
> with full Gradle exception logging (`device-tests-windows-1309.log`) identified the failing call as
> `IStream::Write` returning `0x802a0006` (`E_WPD_DEVICE_IS_HUNG`). That points at a WPD
> upload/session-state issue, not at hidden-file semantics; after this state, repeated close/reopen
> attempts spend about 91 seconds each failing to enumerate the FiiO storages.

The earlier intermittent failures recorded here — the growing replace on the FiiO SD card, storages
transiently disappearing, sessions wedging mid-run — were symptoms of WPD-side defects in this backend,
not of the device; they are described under "Growing a file", "Device lifetime" and "Resource
ownership". Two consecutive full runs once passed with the suite's per-test device open/close churn
intact, which is the load that used to wedge the driver within a single run. **PR #25 has since removed
that churn**, so those runs stand as evidence the leaks were fixed, but a green suite no longer
re-proves it; see [Pending Windows work](#pending-windows-work-pr-25). The 2026-08-28 WPD upload
failure on FiiO / Internal shared storage is now tracked separately above.

### Audio object formats on WPD

`WpdBackend.sendFile` must set `WPD_OBJECT_FORMAT` to the real WPD format GUID, not to a GUID formed
by pasting the MTP object-format code after `0000`. The latter was wrong: for the common MTP-derived
formats, WPD's GUID uses the 16-bit MTP format code in the high word of `GUID.Data1`
(`30090000-...` for MP3, `b9060000-...` for FLAC, etc.), and M4A has a separate WPD GUID. The bad
mapping meant Windows uploaded audio objects with nonstandard format GUIDs while libmtp uploaded the
same extensions under recognized audio filetypes. That was a real implementation parity gap, despite
the previous status table claiming this path was complete.

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

## Pending Windows work (PR #25)

All three verification items below have since been **run on a Windows host with both devices
attached** (2026-07-31). Items 1 and 3 came back clean; item 2 came back negative and produced the
one real parity gap in the Status table. Two open items remain, both marked below: the TTL-window
HRESULT backstop (item 1) and the leak-detector decision (item 3).

Note the original framing — "PR #25 introduces no new `MtpBackend` primitive, so there is most likely
nothing to implement" — did not survive item 2: `reopenClearsNameReservations()` was added to the SPI
precisely because the two backends diverge here.

### 1. Verify — the `createDirectory` collision check

`MTPDeviceBridge.createDirectory` now refuses a taken name with `FileAlreadyExistsException`, decided
above the SPI from the cached listing, before any native call. This matters more on WPD than on
libmtp: libmtp merely failed with a generic `IOException`, whereas WPD **could silently succeed and
create a duplicate-named object**, which is the worse outcome the check is meant to prevent.

- [x] Run `createDirectoryFailsWhenDirectoryExists` and `createDirectoryFailsWhenFileExistsWithSameName`
      on all four storages. **Both pass on WPD everywhere**, as on libmtp (2026-07-31 run).
- [x] Confirm no duplicate-named object is left behind on the device afterwards. Settled by
      construction rather than by direct inspection: `FileAlreadyExistsException` is raised only by
      the pre-SPI cached-listing check in `MTPDeviceBridge.createDirectory`, before any native call,
      so a passing test means `CreateObjectWithPropertiesOnly` was never issued and no duplicate
      could have been created. The tests assert the exception, not the device's post-state.
- [ ] **Consider implementing** a backstop for the TTL-window race (caveat 2 on PR #25): if something
      outside the session creates the name while a cached listing is still fresh, the check misses it
      and the create falls through to the driver. On libmtp that surfaces as a generic `IOException`;
      on WPD it can create the duplicate. Mapping WPD's native already-exists HRESULT from
      `CreateObjectWithPropertiesOnly` into `FileAlreadyExistsException` would close it on the side
      where the consequence is worst.

### 2. Verify — does the deleted-name reservation apply to *folder* creation on WPD?

**Answered: yes, and the mitigation does not work here.** See
[`deleted-name-reservation.md`](deleted-name-reservation.md) for the full measurements. On libmtp,
the FiiO SD card refuses a `CreateFolder` for a name deleted earlier in the same session, and a
session reopen is the only reliable clear (4/4, ~500ms); retry/backoff and rename-into-the-name both
fail. The folder-side reservation reproduces identically over WPD — same device, same single storage
— so `CreateObjectWithPropertiesOnly` being a different opcode path than `SendObjectInfo` turned out
not to matter. What *does* differ is the escape: a WPD reopen leaves the reservation in place.

- [x] Run `createDirectorySucceedsAfterDeletingSameName`. **It reproduces on WPD**, skipping on
      FiiO / Micro SD and only there — the same single storage as libmtp, with
      `CreateObjectWithPropertiesOnly failed (HRESULT 0x80004005)`.
- [x] Check whether a WPD session reopen clears it as it does on libmtp. **It does not.** A full
      `closeInterfaces` + `IPortableDevice::Close` + fresh `Open` left the reservation intact and the
      retry was refused identically. The gated session-recycle mitigation is therefore **libmtp-only**,
      gated off on WPD by `MtpBackend.reopenClearsNameReservations()`. A Windows fix needs a lever that
      resets the session *on the device*, which the pooled driver session appears to prevent — see
      [`deleted-name-reservation.md`](deleted-name-reservation.md).

### 3. Regression risk — the suite no longer churns device open/close

PR #25 makes `MTPFileSystemIntegrationTest` reuse **one** MTP session across all ~204 rows instead of
tearing the bridge down and reopening per test. On Linux that was pure overhead and the suite went
from 7m25s to 49s. On Windows it is not pure overhead: per "Device lifetime" below, that churn — 50+
open/close cycles per storage — is what exposed the driver-session leaks, and this file explicitly
advises *fixing what the churn exposes rather than reducing it*. The change removes that detector as a
side effect of a Linux optimisation.

Nothing regressed on Windows by construction — reusing a session exercises the driver *less*, not
differently — but the guard is gone.

- [x] Run the suite over WPD once as-is, to confirm session reuse works on the driver at all.
      **It does** — green across three full runs on 2026-07-31 (216 rows on one held session, ~45s
      each), with no mid-run wedge. One of those runs additionally forced a mid-suite
      `recycleConnections()` teardown and reopen, and the remaining rows were unaffected.
- [ ] Decide how to keep the leak detector. Cheapest option: a system property (say
      `-Dmeltjfs.test.churnSessions=true`) that restores the per-test teardown, run on Windows either
      always or as a second CI job, leaving Linux fast by default. Whatever is chosen, record it here —
      the churn's diagnostic value is a Windows-only concern and will otherwise be quietly lost.

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
does libmtp no harm while it was steadily poisoning the WPD driver. That churn is a useful leak
detector; prefer fixing what it exposes over reducing it.

**As of PR #25 that churn is gone from `MTPFileSystemIntegrationTest`**, which now holds one session
for the whole class. The reopen cost this file described as "the price of the churn's diagnostic
value" turned out to be most of the Linux runtime — 7m25s to 49s once removed — so the trade was taken
for Linux, and the WPD leak detector went with it. `MTPDeviceBridgeIntegrationTest` still opens and
closes per test, but that is 4 tests, not 200, so it is a far weaker probe. If a driver-session leak
is ever suspected again, restore the churn deliberately (see
[Pending Windows work](#pending-windows-work-pr-25), item 3) rather than assuming a green suite still
proves the absence of leaks — it no longer applies the load that used to expose them.

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
  later run until it was re-plugged. Do not reintroduce shared constant artifact names. This matters
  more since PR #25: the suite now holds one session across the whole class, so a reused name is no
  longer laundered by the next test's reconnect. See [`deleted-name-reservation.md`](deleted-name-reservation.md).
- The audio tag readers require **no** per-platform work — they operate on `RangedByteSource`, which any
  backend satisfies through `readPartial`. All formats wired into `AudioTagReaders` (FLAC, MP3, MP4/M4A,
  Ogg Vorbis, Opus, WAV) — and any added later — get Windows support for free.
- Keep the status table current as new formats and features land.
