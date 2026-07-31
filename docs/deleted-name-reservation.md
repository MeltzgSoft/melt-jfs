# Deleted-name reservation (session recycling)

Some devices refuse to reuse the name of an object deleted earlier in the same MTP session. This file
records what was measured on real hardware, why the obvious workarounds do not work, and the design of
the mitigation. The gated session recycle described below **is implemented** in
`MTPDeviceBridge.createDirectory`; it has unit coverage but has not yet been confirmed against the
affected hardware (see [Status](#status)).

## Symptom

After a successful `DeleteObject`, the device keeps the freed name reserved. Creating an object with
that name fails for the rest of the session:

```
java.io.IOException: LIBMTP_Create_Folder failed for: <name>
    at org.meltzg.fs.mtp.NativeLibMTP.createFolder
    at org.meltzg.fs.mtp.MTPDeviceBridge.createDirectory
```

This is the same asynchronous-object-database behaviour that motivated the tombstones, rename overlays
and size overlays in `MTPDeviceBridge` — the device's database lags its own successful mutations. The
reservation is one more consequence of that lag, and the only one not yet worked around.

## What was measured

Probed with a throwaway integration test over 4 forced runs, against both test devices and all four of
their storages (libmtp on Linux).

| Strategy | FiiO M11 Plus — Micro SD | Every other storage |
|---|---|---|
| Recreate the name, same session | **refused 4/4** | OK |
| Recreate after closing and reopening the session | **OK 4/4**, ~470–530ms (one 5.7s outlier) | OK |
| Create under a scratch name, then rename into the freed name | **refused 3/4** (`LIBMTP_Set_File_Name` code -1) | OK |
| Retry/backoff in-session over 3.75s (250/500/1000/2000ms) | **refused** (failing case took 4.135s vs 0.242s for a successful create) | n/a |

Only the FiiO M11 Plus **SD-card** storage exhibits it; the same device's internal storage does not, nor
does either AK100 II storage. One run out of five saw the same-session recreate succeed on the affected
storage, so it is overwhelmingly but not perfectly reproducible.

Three conclusions follow:

1. **It is not a propagation delay.** Waiting does not clear it, so a retry/backoff is the wrong shape
   of fix — it would only make a certain failure slow. This is why `createDirectory` has no retry.
2. **The reservation is name-scoped, not operation-scoped.** `SetObjectName` onto the freed name is
   refused just like `CreateFolder`, so there is no "create under a temporary name and rename into
   place" escape hatch.
3. **The reservation is session-scoped.** A fresh MTP session clears it reliably and cheaply.

> When probing this, drive `MTPDeviceBridge.move` directly rather than `Files.move`. The provider's
> copy + delete emulation falls back to creating the target *by name*, so a failure surfaces as
> `Create_Folder failed` against the target and masks whether `SetObjectName` was the operation actually
> refused.

## Status

Implemented and unit-tested. **libmtp-only: the recycle does not work over WPD**, and is gated off
there by `MtpBackend.reopenClearsNameReservations()` (see [WPD](#wpd)).

- [x] Run over WPD — **negative result**, measured. The recycle fired correctly and the device
      refused the retry identically; Windows now skips the recovery rather than paying a futile
      process-wide reconnect. Suite back to baseline: 220 tests, 217 passed, 3 skipped, 0 failed.
- [ ] Run the integration suite on Linux/libmtp, where a reopen *was* measured to clear the
      reservation. This is the half expected to work and it has **not been run yet** — the
      acceptance test `createDirectorySucceedsAfterDeletingSameName` should flip from skip to pass
      on FiiO / Micro SD. Watch the skip log: a silent skip is what the failure mode looks like.

## Behaviour before this mitigation

Nothing recovered from this. Two things kept it from mattering much:

- **The integration suite gives every artifact a name unique to the test** (see `uniq` in
  `MTPFileSystemIntegrationTest`), so no test depends on reusing a freed name.
  `createDirectorySucceedsAfterDeletingSameName` probes the device and self-skips where recreation is
  impossible.
- **Replacing file writes avoid deletion entirely** where the device supports it: `writeFile` rewrites
  the existing object in place via the Android edit extension, so the id and name never change and the
  reservation is never triggered. There is no equivalent for folders — you cannot rewrite a directory —
  which is why folder creation is the exposed case.

So the gap was felt by **consumers**, not by our tests: a sync tool that deletes a folder and recreates
it under the same name got an opaque `IOException` on this storage.

## The mitigation: gated session recycle

Retry the create once against a fresh session, but only when we have positive evidence the name was
freed by us in this session.

```
createDirectory(deviceId, path):
    try:
        createDirectoryOnce(deviceId, path)          # acquires the read lock internally
    catch FileSystemException e: throw e             # a definitive answer, not the reservation
    catch IOException e:
        if not freedNames.remove(deviceId, path): throw e   # consumes the gate
        recycleConnections()                         # write lock, OUTSIDE the read lock
        createDirectoryOnce(deviceId, path)          # one retry; suppressed-chains e on failure
```

### `refresh()` does **not** work here

An earlier draft of this design called `refresh()` to get the fresh session. It does not:
`reconcileDevicesUnsafe` short-circuits when the attached-device set is unchanged —

```java
if (signature.equals(lastSignature) && !deviceConns.isEmpty()) {
    return; // nothing changed; keep the live connections
}
```

— and the device *is* still attached, so its signature still matches and the MTP session that holds
the reservation survives untouched. (`ensureFresh()` is additionally throttled by
`DETECT_INTERVAL_NANOS`, a second way to no-op.) The recovery therefore needs
`recycleConnections()`, which closes and reopens unconditionally. Substituting `refresh()` back in
fails four of the six tests in `MTPDeviceBridgeSessionRecycleTest`.

### Gating

Gate on *"this exact name was deleted through this bridge in this session"*. Without that, any
`createFolder` failure — a full storage, a read-only volume, an invalid name — would trigger a full
device reconnect and mask the real error.

The bridge already tombstones deleted ids, but tombstones are keyed by *id* — which is exactly what a
delete destroys — so this needs a separate `freedNames` set keyed by `(deviceId, canonical path)`, the
path being what a later create names. Recorded for every name freed by a `DeleteObject` that nothing
reoccupies:

| Site | Recorded when |
|---|---|
| `delete` | always — nothing takes the name back |
| `writeFile` (replacing) | only if the follow-up send fails; a completed send reoccupies the name |
| `move` (replacing target) | only if the move/rename that would occupy the name fails |

Entries are consumed by `Set.remove` when they authorise a retry — which also makes the gate
thread-safe, since only one caller can win it — and cleared in `closeUnsafe()` alongside the
tombstones: after a recycle the reservation is gone, so the entry must not authorise a second one.

`FileAlreadyExistsException`, `NoSuchFileException` and `NotDirectoryException` are excluded from the
retry as a group (they share the `FileSystemException` supertype): each is an answer about the
filesystem that a fresh session would repeat.

### Constraints that shape the design

1. **It cannot be done inline.** `createDirectory` holds `connectionLock.readLock()` while
   `recycleConnections()` takes `connectionLock.writeLock()`. `ReentrantReadWriteLock` does not permit
   upgrading read → write, so recycling inside the locked region deadlocks. The retry must sit above
   the lock, which is why the code splits out a `createDirectoryOnce`.
2. **The blast radius is process-wide.** `closeUnsafe()` releases **every** open device, not just the
   one being fixed, and clears the listing cache, tombstones, rename overlays and size overlays. A
   single folder create would therefore disturb unrelated in-flight work on other devices. Any
   multi-step native operation that is mid-flight — an in-place edit session especially — cannot
   survive it.
3. **Object ids are not stable across the recycle.** Devices are reopened uncached, so item ids are
   renumbered. `MTPBasicFileAttributes.fileKey()` values handed out before the recycle become stale,
   which breaks a consumer using `fileKey` for identity. `MTPLazyReadChannel` re-resolves by path on
   every read and so should survive, but that is an implementation detail worth re-verifying rather
   than assuming.
4. **~500ms is the cost when it fires**, plus the cost of every cache the recycle just dropped being
   refilled. Acceptable on a rare poisoned path; not acceptable as anything routine.

### Alternatives considered

- **Retry/backoff in session** — measured, does not work (see the table). Rejected.
- **Scratch name + rename into place** — measured, does not work; `SetObjectName` is refused too.
  Rejected.
- **Per-device connection recycle instead of the whole bridge** — would shrink the blast radius to the
  one affected device and is the natural refinement, but `closeUnsafe()` is currently all-or-nothing
  and `currentScan` owns native resources shared by every device opened from it. Untangling that is a
  larger change than the mitigation itself; worth revisiting if the coarse recycle proves disruptive.
- **Capability flag and surface a typed exception** — detect the condition and throw something more
  descriptive than a bare `IOException` so callers can choose their own recovery, with no reconnect.
  Still worth doing alongside the recycle: it turns the *unrecoverable* case — the retry after the
  recycle also failing — from an opaque `IOException` into an actionable one.

## Unit coverage

`MTPDeviceBridgeSessionRecycleTest` drives a fake backend that reserves deleted names until the
device is released and reopened. It pins the recovery (a reserved name recreates after exactly one
recycle), both halves of the gate (no recycle for a name the bridge did not free; none for
`FileAlreadyExistsException`), that the gate is spent after one use, and that `writeFile` records a
freed name only when its send actually failed.

## WPD

The reservation **reproduces over WPD** — an integration run on Windows skipped
`createDirectorySucceedsAfterDeletingSameName` on FiiO / Micro SD, and only there, exactly matching
libmtp, with `IOException: CreateObjectWithPropertiesOnly failed (HRESULT 0x80004005)` from
`WpdBackend.createFolder`. So it is not an opcode-path difference between
`CreateObjectWithPropertiesOnly` and `SendObjectInfo`, and the driver does not mask it.

**But a WPD reopen does not clear it**, so the mitigation is libmtp-only. Measured by running the
mitigation itself: the gate fired, `recycleConnections()` ran a full teardown (release properties and
content, `IPortableDevice::Close`, fresh scan, fresh `Open`), and the retry was refused with the
identical HRESULT — visible in the test report as the post-recycle failure carrying the pre-recycle
one as a suppressed exception.

The likely reason is that what has to be reset is the *device's* MTP session, not the host's handle
to it. libmtp owns the USB handle, so releasing it genuinely ends the session and the device rebuilds
its object index on the next one. The WpdMtpDr driver pools a session across clients, so closing an
`IPortableDevice` does not end the session the device sees, and nothing on the device gets rebuilt.

Consequences:

- `MtpBackend.reopenClearsNameReservations()` gates the recovery. WPD returns false and takes the
  plain failure; do not flip it without re-measuring.
- **A Windows fix needs a different lever** — something that makes the *device* start a new MTP
  session while the driver keeps its pool. Unexplored: whether WPD exposes a session reset at all,
  or whether only a physical replug does it. If nothing does, the typed-exception alternative below
  is the realistic ceiling on Windows.
