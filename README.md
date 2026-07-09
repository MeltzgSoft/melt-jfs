# melt-jfs

A Java NIO `FileSystemProvider` implementation for MTP (Media Transfer Protocol) devices such as Android phones and dedicated audio players. Uses the Java Foreign Function & Memory (FFM) API (Project Panama) instead of JNI, so no native build step is required.

Two backends are selected automatically by platform:

| Platform | Backend | Native dependency |
|----------|---------|-------------------|
| Linux / macOS | libmtp (`NativeLibMTP`) | `libmtp.so.9` (Linux) / `libmtp.9.dylib` (macOS) |
| Windows | Windows Portable Devices COM (`WpdBackend`) | `ole32` + WPD (built into Windows) |

The Windows backend drives the WPD COM API entirely through FFM, so it works with the in-box MTP driver — no driver replacement (e.g. WinUSB/Zadig) and no libmtp install required.

## Requirements

- **Java 22+** (the FFM API is finalized in Java 22; `--enable-native-access=ALL-UNNAMED` is set automatically by the build)
- **Gradle** — use the included wrapper (`./gradlew`); no separate Gradle installation needed
- **libmtp** on Linux/macOS (see below); nothing extra on Windows

## System libraries (Linux / macOS)

The `NativeLibMTP` backend loads libmtp at runtime via FFM — no compile-time linking needed. Install it
for your platform:

**Linux (Debian/Ubuntu):**

```bash
sudo apt install libmtp9
```

**macOS (Homebrew):**

```bash
brew install libmtp
```

| Platform | Install | Library resolved |
|----------|---------|------------------|
| Linux | `apt install libmtp9` (or your distro's equivalent) | `libmtp.so.9` |
| macOS | `brew install libmtp` | `libmtp.9.dylib`, found in the Homebrew prefix (`/opt/homebrew` on Apple Silicon, `/usr/local` on Intel) |

The shared library is located automatically — on macOS, both the bare `libmtp.9.dylib`/`libmtp.dylib`
names and the absolute Homebrew paths are tried, so no extra environment setup is needed. See
`NativeLibMTP.lookupLibmtp()` for the exact search order.

## System libraries (Windows)

Nothing to install — the `WpdBackend` talks to the Windows Portable Devices COM API via `ole32.dll`,
which ships with Windows. The device must be in **MTP mode** and visible in File Explorer.

## Building

```bash
./gradlew build
```

## Publishing a release

Releases go to Maven Central (Sonatype Central Portal) automatically. Pushing a `vMAJOR.MINOR.PATCH`
tag triggers the [`release`](.github/workflows/release.yml) workflow, which derives the artifact
version from the tag (e.g. `v0.1.0` → `0.1.0`), then signs and uploads the JAR, sources, and javadoc.

```bash
git tag v0.1.0
git push origin v0.1.0
```

One-time setup — add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|--------|-------|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username (Account → Generate User Token at central.sonatype.com) |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
| `SIGNING_KEY` | ASCII-armored GPG private key (`gpg --armor --export-secret-keys <KEY_ID>`) |
| `SIGNING_PASSWORD` | Passphrase for that GPG key |

The public half of the signing key must be distributed to a public keyserver (e.g.
`gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`) so Central can verify the signatures.

To inspect a deployment before it goes public instead of auto-releasing, set
`mavenCentralAutomaticPublishing=false` in `gradle.properties` and release it manually from
[the portal](https://central.sonatype.com/publishing/deployments).

## Running tests

The unit tests use a `FakeLibMTP` test double — no physical device and no native libraries are needed. `./gradlew test` runs cleanly in CI.

```bash
./gradlew test
```

### Testing against a real device (local only)

Integration tests live in a separate `integrationTest` source set and run via their own task, in a
fresh JVM per test class (`forkEvery = 1`) so they never share `libmtp`/device state with the
fake-backed unit tests. They require libmtp installed (see above) and the device connected, and skip
automatically when no device is accessible. They are not part of `test`/`check`.

```bash
# Connect the device, then release the USB interface from any OS-level MTP mount so libmtp can claim it:
#   Linux — eject the device in your file manager (so GVFS lets go)
#   macOS — quit Android File Transfer (or any app holding the device)
./gradlew integrationTest
```

## Browsing a connected device

```bash
./gradlew browse            # full depth (one USB round-trip per directory)
./gradlew browse --args="2" # limit to 2 levels deep
```

## Project structure

```
src/
  main/java/org/meltzg/fs/mtp/
    MTPFileSystemProvider.java   # NIO SPI entry point
    MTPFileSystem.java           # FileSystem implementation
    MTPPath.java                 # Path implementation
    MTPFileStore.java            # FileStore (per storage volume)
    MTPBasicFileAttributes.java  # BasicFileAttributes wrapper
    MTPDeviceBridge.java         # Thread-safe device access singleton
    MTPLazyReadChannel.java      # Lazy, ranged-read SeekableByteChannel (fetches only the bytes read)
    MtpBackend.java              # Platform-neutral backend SPI (opaque String ids + device handles)
    NativeLibMTP.java            # FFM bindings for libmtp (implements MtpBackend; Linux/macOS)
    WpdBackend.java              # WPD COM backend via FFM (implements MtpBackend; Windows)
    WpdCom.java                  # Low-level COM/FFM plumbing used by WpdBackend
    audio/                       # Embedded audio-tag readers behind the "audio" attribute view
      AudioTagReaders.java       #   dispatcher (by extension) over a RangedByteSource
      {Flac,Mp3,Mp4,Ogg,Wav}MetadataReader.java  #   per-format tag + duration readers
      VorbisComment.java         #   shared Vorbis-comment parser (FLAC + Ogg/Opus)
    types/                       # Value records
  dev/java/org/meltzg/fs/mtp/
    MTPBrowser.java              # CLI tree walker (not included in the published JAR)
  test/java/org/meltzg/fs/mtp/
    FakeLibMTP.java              # In-memory MtpBackend test double (no native libs)
    MTPDeviceBridgeTest.java     # Unit tests using FakeLibMTP
    MTPDeviceBridgeFreshnessTest.java    # Hot-plug/unplug detection (FakeLibMTP)
    MTPFileStoreTest.java        # Unit tests using FakeLibMTP
    MTPFileSystemProviderTest.java
  integrationTest/java/org/meltzg/fs/mtp/  # Real-device tests (own JVM; skipped when no device)
    MTPDeviceBridgeIntegrationTest.java
    MTPFileSystemIntegrationTest.java
```

## URI schema

All MTP paths are addressed with URIs of the form:

```
mtp://<vendorId>:<productId>:<serial>/<storage>/<path...>
```

| Component | Description | Example |
|-----------|-------------|---------|
| `vendorId` | USB vendor ID (decimal) | `1949` |
| `productId` | USB product ID (decimal) | `3`    |
| `serial`   | Device serial string    | `ABC12345` |
| `storage`  | Storage volume name     | `Internal Storage` |
| `path`     | Path within the storage | `Music/song.mp3` |

The path hierarchy mirrors the device structure:

```
mtp://1949:3:ABC12345/               ← device root (lists storages)
mtp://1949:3:ABC12345/Internal Storage/          ← storage volume
mtp://1949:3:ABC12345/Internal Storage/Music/    ← directory
mtp://1949:3:ABC12345/Internal Storage/Music/song.mp3   ← file
```

Names containing spaces or other special characters are percent-encoded in the URI (e.g. `Internal%20Storage`), but `Path.toString()` always returns the decoded form.

## Adding the dependency

Released to Maven Central under `io.github.meltzg:melt-jfs`.

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.meltzg:melt-jfs:0.1.2")
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.meltzg</groupId>
    <artifactId>melt-jfs</artifactId>
    <version>0.1.2</version>
</dependency>
```

## Using melt-jfs in a Java program

### 1. Find connected devices

`MTPDeviceBridge` maintains the live device list. Call it once to enumerate what is attached:

```java
import org.meltzg.fs.mtp.MTPDeviceBridge;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPDeviceInfo;

MTPDeviceBridge bridge = MTPDeviceBridge.getInstance();

Map<MTPDeviceIdentifier, MTPDeviceInfo> devices = bridge.getDeviceInfo();
for (var entry : devices.entrySet()) {
    MTPDeviceIdentifier id = entry.getKey(); // "1949:3:ABC12345"
    MTPDeviceInfo info    = entry.getValue();
    System.out.println(id + " → " + info.friendlyName());
}
```

### 2. Open a FileSystem

Pass the device identifier in the URI. `FileSystems.newFileSystem` registers the filesystem for subsequent `Paths.get` calls.

```java
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.FileSystem;

MTPDeviceIdentifier id = bridge.getDeviceConns().keySet().iterator().next();
URI uri = URI.create("mtp://" + id + "/");

try (FileSystem fs = FileSystems.newFileSystem(uri, null)) {
    // use fs here
}
```

### 3. Use standard NIO operations

Once the filesystem is open, every `java.nio.file.Files` method works as-is — no MTP-specific API needed.

**List storages and browse directories**

```java
Path root = fs.getPath("/");

// List storage volumes
try (DirectoryStream<Path> storages = Files.newDirectoryStream(root)) {
    for (Path storage : storages) {
        System.out.println(storage); // e.g. /Internal Storage
    }
}

// Walk the whole device tree
Files.walk(root).forEach(System.out::println);
```

**Read a file**

```java
Path song = fs.getPath("/Internal Storage/Music/song.mp3");
byte[] bytes = Files.readAllBytes(song);
```

**Write a file**

```java
Path dest = fs.getPath("/Internal Storage/Notes/hello.txt");
Files.write(dest, "hello".getBytes());
```

**Copy between a local path and the device**

```java
Path localFile  = Path.of("/tmp/photo.jpg");
Path deviceFile = fs.getPath("/Internal Storage/DCIM/photo.jpg");

// local → device
Files.copy(localFile, deviceFile);

// device → local
Files.copy(deviceFile, Path.of("/tmp/copy.jpg"));
```

**Delete, move, create directories**

```java
Files.createDirectory(fs.getPath("/Internal Storage/NewFolder"));
Files.move(deviceFile, fs.getPath("/Internal Storage/NewFolder/photo.jpg"));
Files.delete(fs.getPath("/Internal Storage/NewFolder/photo.jpg"));
```

**Read a track's audio metadata (the `mtp` attribute view)**

MTP devices index their media and expose per-object Artist/Album/Title/Genre properties. The
`mtp` attribute view reads them as a metadata-only exchange — no file content is transferred,
so it is orders of magnitude cheaper than pulling the file and parsing its tags:

```java
Path song = fs.getPath("/Internal Storage/Music/song.mp3");
Map<String, Object> tags = Files.readAttributes(song, "mtp:*");
// {title=…, artist=…, album=…, genre=…, trackNumber=…, durationMillis=…}
// or request a subset: Files.readAttributes(song, "mtp:title,artist,album")
```

Every value is `null` when the object is not an audio track or the device has not indexed it
(e.g. a file uploaded moments ago that the device's media scanner has not yet classified).

**Read a file's embedded tags (the `audio` attribute view)**

Where the `mtp` view returns the device's *index* — which some devices populate with the filename, and
only after their media scanner runs — the `audio` view parses the file's **own embedded tags**, reading
just a small slice of the header over ranged reads rather than transferring the whole file:

```java
Path song = fs.getPath("/Internal Storage/Music/song.flac");
Map<String, Object> tags = Files.readAttributes(song, "audio:*");
// {title=…, artist=…, album=…, genre=…, trackNumber=…, discNumber=…, durationMillis=…}
// or request a subset: Files.readAttributes(song, "audio:title,artist,album")
```

Supported formats: **FLAC, MP3 (ID3v2), MP4/M4A, Ogg Vorbis, Opus, and WAV**. Large blocks such as
embedded album art are skipped, so the read stays small (typically a few KB) regardless of file size,
and it recovers the real embedded title even on devices whose `mtp` index only reports the filename —
immediately after upload, with no wait for the device's media scanner. Values are `null` when the object
is not a file, its format is unsupported, or the header is unreadable; `discNumber` is `0` for containers
that don't carry it (e.g. WAV/RIFF `INFO`). Requires a backend with ranged-read support (libmtp; the WPD
backend is a pending follow-up).

### JVM flags

The FFM API requires the following flag, which the Gradle build sets automatically. If you invoke the JVM directly, add:

```
--enable-native-access=ALL-UNNAMED
```

## Architecture notes

### Backend selection & injection

`MTPDeviceBridge` routes all device calls through a static `backend()` accessor. Tests inject a `FakeLibMTP` via `MTPDeviceBridge.setBackend(...)` in `@BeforeClass` and reset it to `null` in `@AfterClass`. When the override is `null`, the bridge falls through to `MtpBackend.defaultBackend()`, which picks the native backend for the current OS on first use.

```
MTPDeviceBridge.backend()
  ├── backendOverride != null  →  FakeLibMTP                (tests)
  └── backendOverride == null  →  MtpBackend.defaultBackend()
        ├── Windows  →  WpdBackend   (WPD COM via FFM; loads ole32)
        └── otherwise →  NativeLibMTP (libmtp; loads libmtp.so.9)
```

`MtpBackend` is the platform-neutral SPI: object identifiers (storage / item / parent) are opaque
`String`s — libmtp's 32-bit handles rendered as unsigned decimals, or WPD object-id strings verbatim —
and device handles are opaque per-backend types. `MtpBackend.ROOT_PARENT` denotes a storage's top
level (libmtp `0xFFFFFFFF`; the storage's functional-object id on WPD).

### FFM struct layouts

The struct layouts in `NativeLibMTP.java` target **x86-64 Linux** with **libmtp 1.1.x**. For other architectures or libmtp versions, regenerate them with [jextract](https://github.com/openjdk/jextract) against the installed `libmtp.h`.
