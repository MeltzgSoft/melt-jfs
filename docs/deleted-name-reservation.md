# Deleted-name reservation (session recycling)

Some devices refuse to reuse the name of an object deleted earlier in the same MTP session. This file
records what was measured on real hardware, why the obvious workarounds do not work, and the design of
the mitigation we intend to add. **Nothing here is implemented yet** — the current code sidesteps the
problem rather than fixing it (see [Current behaviour](#current-behaviour)).

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

## Current behaviour

Nothing recovers from this today. Two things keep it from mattering much:

- **The integration suite gives every artifact a name unique to the test** (see `uniq` in
  `MTPFileSystemIntegrationTest`), so no test depends on reusing a freed name.
  `createDirectorySucceedsAfterDeletingSameName` probes the device and self-skips where recreation is
  impossible.
- **Replacing file writes avoid deletion entirely** where the device supports it: `writeFile` rewrites
  the existing object in place via the Android edit extension, so the id and name never change and the
  reservation is never triggered. There is no equivalent for folders — you cannot rewrite a directory —
  which is why folder creation is the exposed case.

So the gap is felt by **consumers**, not by our tests: a sync tool that deletes a folder and recreates
it under the same name gets an opaque `IOException` on this storage.

## Proposed mitigation: gated session recycle

Retry the create once against a fresh session, but only when we have positive evidence the name was
freed by us in this session.

```
createDirectory(deviceId, path):
    try:
        createDirectoryOnce(deviceId, path)          # acquires the read lock internally
    catch IOException e:
        if not freedThisSession(deviceId, storageId, parentId, name): throw e
        refresh()                                    # write lock, OUTSIDE the read lock
        createDirectoryOnce(deviceId, path)          # one retry; propagate whatever it throws
```

### Gating

Gate on *"this exact name was deleted through this bridge in this session"*. Without that, any
`createFolder` failure — a full storage, a read-only volume, an invalid name — would trigger a full
device reconnect and mask the real error.

The bridge already tombstones deleted ids, but tombstones are keyed by *id*; this needs a *name*-keyed
record: `(deviceId, storageId, parentId, filename)` for every name freed by `delete` and by the
replacing paths in `writeFile`/`move`. Cleared in `closeUnsafe()` alongside the tombstones — after a
recycle the reservation is gone, so the entry has served its purpose and must not authorise a second
recycle for the same name.

### Constraints that shape the design

1. **It cannot be done inline.** `createDirectory` holds `connectionLock.readLock()` while `close()`
   and `refresh()` take `connectionLock.writeLock()`. `ReentrantReadWriteLock` does not permit
   upgrading read → write, so recycling inside the locked region deadlocks. The retry must sit above
   the lock, which is why the sketch above splits out a `createDirectoryOnce`.
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
- **Capability flag and surface a typed exception** — cheapest option: detect the condition and throw
  something more descriptive than a bare `IOException` so callers can choose their own recovery, with
  no reconnect. Worth doing regardless of whether the recycle lands, since it turns an opaque failure
  into an actionable one.

## Verifying a fix

`createDirectorySucceedsAfterDeletingSameName` in `MTPFileSystemIntegrationTest` is the acceptance
test: it currently self-skips on the affected storage, and a working mitigation should make it **pass**
there instead of skipping. Watch the skip log — a silent skip is what the failure mode looks like.

Anything landed here also needs a WPD check: all measurements above are libmtp on Linux, and whether
WPD's `IPortableDeviceContent.CreateObjectWithPropertiesOnly` hits the same reservation on the same
device is unknown.
