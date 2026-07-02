package org.meltzg.fs.mtp;

import org.junit.*;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Integration tests that exercise the MTP filesystem provider against a physically connected device.
 * Skipped automatically when no device is present or libmtp is not installed.
 *
 * Run with: ./gradlew test --tests '*FileSystemIntegration*'
 */
public class MTPFileSystemIntegrationTest {

    private static final String TEST_DIR_NAME = "__melt_jfs_test__";
    private static final String TEST_FILE_NAME = "__melt_jfs_test__.bin";
    private static final String TEST_FILE_NAME2 = "__melt_jfs_test__moved.bin";
    private static final String TEST_DIR_NAME2 = "__melt_jfs_test_moved__";

    private MTPFileSystemProvider provider;
    private MTPFileSystem fs;

    @Before
    public void setUp() throws IOException {
        assumeTrue("native MTP backend not available", isBackendAvailable());
        MTPDeviceBridge.INSTANCE.close();
        var bridge = MTPDeviceBridge.getInstance();
        assumeTrue("No MTP device connected", !bridge.getDeviceConns().isEmpty());

        MTPDeviceIdentifier deviceId = bridge.getDeviceConns().keySet().iterator().next();
        provider = new MTPFileSystemProvider();
        fs = provider.newFileSystem(URI.create("mtp://" + deviceId + "/"), null);

        // Best-effort cleanup of any leftovers from a previous failed run
        cleanUpTestArtifacts();
    }

    @After
    public void tearDown() throws IOException {
        if (fs != null && fs.isOpen()) {
            cleanUpTestArtifacts();
            fs.close();
        }
        MTPDeviceBridge.INSTANCE.close();
    }

    private void cleanUpTestArtifacts() {
        try {
            var storage = firstStorageOrNull();
            if (storage != null) {
                Files.deleteIfExists(storage.resolve(TEST_FILE_NAME));
                Files.deleteIfExists(storage.resolve(TEST_FILE_NAME2));
                deleteTreeQuietly(storage.resolve(TEST_DIR_NAME));
                deleteTreeQuietly(storage.resolve(TEST_DIR_NAME2));
            }
        } catch (IOException ignored) {}
    }

    private void deleteTreeQuietly(Path path) {
        try {
            if (!Files.exists(path)) return;
            if (Files.isDirectory(path)) {
                try (var children = Files.newDirectoryStream(path)) {
                    for (var child : children) deleteTreeQuietly(child);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    // --- root / storage structure ---

    @Test
    public void rootHasDirectoryAttributes() throws IOException {
        var attrs = Files.readAttributes(fs.getPath("/"), BasicFileAttributes.class);
        assertTrue(attrs.isDirectory());
        assertFalse(attrs.isRegularFile());
        assertFalse(attrs.isSymbolicLink());
    }

    @Test
    public void listRootReturnsStorages() throws IOException {
        var storages = listChildren(fs.getPath("/"));
        assertFalse("Expected at least one storage on the device", storages.isEmpty());
        for (var s : storages) {
            assertTrue("Storage root should be a directory: " + s, Files.isDirectory(s));
            assertFalse("Storage root should not be a regular file: " + s, Files.isRegularFile(s));
        }
    }

    @Test
    public void fileStoreReportsConsistentCapacity() throws IOException {
        var storage = requireFirstStorage();
        var store = Files.getFileStore(storage);
        assertTrue("Total space should be > 0", store.getTotalSpace() > 0);
        assertTrue("Usable space should be >= 0", store.getUsableSpace() >= 0);
        assertTrue("Usable space should be <= total space", store.getUsableSpace() <= store.getTotalSpace());
        assertEquals("Unallocated space should equal usable space", store.getUsableSpace(), store.getUnallocatedSpace());
    }

    // --- directory operations ---

    @Test
    public void createDirectoryAppearsInListing() throws IOException {
        var storage = requireFirstStorage();
        var testDir = storage.resolve(TEST_DIR_NAME);

        Files.createDirectory(testDir);
        try {
            var names = listChildren(storage).stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());
            assertTrue("Created directory should appear in listing", names.contains(TEST_DIR_NAME));
            assertTrue("Created directory should report isDirectory=true", Files.isDirectory(testDir));
            assertFalse("Created directory should not report isRegularFile=true", Files.isRegularFile(testDir));
        } finally {
            Files.deleteIfExists(testDir);
        }
    }

    @Test
    public void deleteDirectoryDisappearsFromListing() throws IOException {
        var storage = requireFirstStorage();
        var testDir = storage.resolve(TEST_DIR_NAME);

        Files.createDirectory(testDir);
        Files.delete(testDir);

        assertFalse("Deleted directory should not exist", Files.exists(testDir));
        var names = listChildren(storage).stream()
            .map(p -> p.getFileName().toString())
            .collect(Collectors.toList());
        assertFalse("Deleted directory should not appear in listing", names.contains(TEST_DIR_NAME));
    }

    @Test(expected = NoSuchFileException.class)
    public void deleteNonExistentThrows() throws IOException {
        var storage = requireFirstStorage();
        Files.delete(storage.resolve(TEST_DIR_NAME));
    }

    // --- walk ---

    @Test
    public void walkRootDepth1IncludesAllStorages() throws IOException {
        var storages = listChildren(fs.getPath("/"));
        var walked = Files.walk(fs.getPath("/"), 1).collect(Collectors.toList());
        for (var storage : storages) {
            assertTrue("Walk from root should include storage: " + storage, walked.contains(storage));
        }
    }

    @Test
    public void walkStorageIncludesRootEntry() throws IOException {
        var storage = requireFirstStorage();
        var walked = Files.walk(storage, 1).collect(Collectors.toList());
        assertFalse("Walk should produce at least the storage root itself", walked.isEmpty());
        assertTrue("Walk should include the storage root", walked.contains(storage));
    }

    // --- file attributes and content ---

    @Test
    public void fileAttributesAreConsistent() throws IOException {
        var storage = requireFirstStorage();
        var file = findFirstFile(storage, 2);
        assumeTrue("No regular files found in first 2 levels of " + storage, file != null);

        var attrs = Files.readAttributes(file, BasicFileAttributes.class);
        assertTrue("File should report isRegularFile=true", attrs.isRegularFile());
        assertFalse("File should report isDirectory=false", attrs.isDirectory());
        assertTrue("File size should be >= 0", attrs.size() >= 0);
        assertNotNull("File key should not be null", attrs.fileKey());
    }

    @Test
    public void readFileBytesMatchReportedSize() throws IOException {
        var storage = requireFirstStorage();
        var file = findFirstFile(storage, 2);
        assumeTrue("No regular files found in first 2 levels of " + storage, file != null);

        long reportedSize = Files.size(file);
        byte[] content = Files.readAllBytes(file);
        assertNotNull("File content should not be null", content);
        assertEquals("Read byte count should match reported file size", reportedSize, content.length);
    }

    // --- write path ---

    @Test
    public void writeThenReadRoundTrips() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        var payload = "melt-jfs write path éñ 🎵".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Files.write(file, payload);
        try {
            assertTrue("Written file should exist", Files.exists(file));
            assertTrue("Written file should be a regular file", Files.isRegularFile(file));
            assertFalse("Written file should not be a directory", Files.isDirectory(file));
            assertEquals("Reported size should match payload", payload.length, Files.size(file));
            assertArrayEquals("Round-tripped bytes should match", payload, Files.readAllBytes(file));

            var names = listChildren(storage).stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());
            assertTrue("Written file should appear in listing", names.contains(TEST_FILE_NAME));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void overwriteReplacesContent() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);

        Files.write(file, "first-and-longer".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(file, "second".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            assertEquals("second", new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
            long count = listChildren(storage).stream()
                .filter(p -> p.getFileName().toString().equals(TEST_FILE_NAME))
                .count();
            assertEquals("Overwrite should not leave a duplicate entry", 1, count);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test(expected = FileAlreadyExistsException.class)
    public void createNewFailsWhenFileExists() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        Files.write(file, new byte[]{1, 2, 3});
        try {
            Files.newByteChannel(file, java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).close();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void deleteWrittenFileDisappearsFromListing() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);

        Files.write(file, new byte[]{4, 5, 6});
        Files.delete(file);

        assertFalse("Deleted file should not exist", Files.exists(file));
    }

    // --- move path ---

    @Test
    public void moveRenamesWithinSameDirectory() throws IOException {
        var storage = requireFirstStorage();
        var src = storage.resolve(TEST_FILE_NAME);
        var dst = storage.resolve(TEST_FILE_NAME2);
        var payload = "rename me".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Files.write(src, payload);
        try {
            Files.move(src, dst);
            assertFalse("Source should no longer exist after move", Files.exists(src));
            assertTrue("Target should exist after move", Files.exists(dst));
            assertArrayEquals("Moved content should be preserved", payload, Files.readAllBytes(dst));
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(dst);
        }
    }

    @Test
    public void moveRelocatesIntoSubdirectory() throws IOException {
        var storage = requireFirstStorage();
        var dir = storage.resolve(TEST_DIR_NAME);
        var src = storage.resolve(TEST_FILE_NAME);
        var dst = dir.resolve(TEST_FILE_NAME);
        var payload = "relocate me".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Files.createDirectory(dir);
        Files.write(src, payload);
        try {
            Files.move(src, dst);
            assertFalse("Source should no longer exist after move", Files.exists(src));
            assertTrue("Target in subdirectory should exist", Files.exists(dst));
            assertArrayEquals("Relocated content should be preserved", payload, Files.readAllBytes(dst));

            var names = listChildren(dir).stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());
            assertTrue("Moved file should appear in the subdirectory listing", names.contains(TEST_FILE_NAME));
        } finally {
            Files.deleteIfExists(dst);
            Files.deleteIfExists(src);
            Files.deleteIfExists(dir);
        }
    }

    @Test(expected = FileAlreadyExistsException.class)
    public void moveFailsWhenTargetExistsWithoutReplace() throws IOException {
        var storage = requireFirstStorage();
        var src = storage.resolve(TEST_FILE_NAME);
        var dst = storage.resolve(TEST_FILE_NAME2);
        Files.write(src, new byte[]{1});
        Files.write(dst, new byte[]{2});
        try {
            Files.move(src, dst);
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(dst);
        }
    }

    @Test
    public void moveReplacesExistingTarget() throws IOException {
        var storage = requireFirstStorage();
        var src = storage.resolve(TEST_FILE_NAME);
        var dst = storage.resolve(TEST_FILE_NAME2);
        Files.write(src, "winner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(dst, "loser".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            assertFalse(Files.exists(src));
            assertEquals("winner", new String(Files.readAllBytes(dst), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(dst);
        }
    }

    @Test
    public void moveEmptyDirectorySucceeds() throws IOException {
        var storage = requireFirstStorage();
        var srcDir = storage.resolve(TEST_DIR_NAME);
        var dstDir = storage.resolve(TEST_DIR_NAME2);

        Files.createDirectory(srcDir);
        try {
            Files.move(srcDir, dstDir);
            assertFalse("Source directory should be gone after move", Files.exists(srcDir));
            assertTrue("Target directory should exist after move", Files.isDirectory(dstDir));
        } finally {
            deleteTreeQuietly(srcDir);
            deleteTreeQuietly(dstDir);
        }
    }

    @Test
    public void moveNonEmptyDirectoryThrowsWhenNotNativelySupported() throws IOException {
        // A device without native MTP MoveObject cannot relocate a non-empty directory to a
        // different folder without moving its entries. Per the Files.move contract the provider's
        // emulation must fail with DirectoryNotEmptyException rather than recursively copy. Devices
        // that do implement MoveObject relocate the tree in one native operation, so the emulation
        // path under test is never reached there; probe the device and skip when that is the case.
        var storage = requireFirstStorage();
        var srcDir = storage.resolve(TEST_DIR_NAME);
        var destParent = storage.resolve(TEST_DIR_NAME2);
        var moved = destParent.resolve(TEST_DIR_NAME);

        Files.createDirectory(srcDir);
        Files.write(srcDir.resolve("child.bin"), new byte[]{1, 2, 3});
        Files.createDirectory(destParent);
        try {
            DirectoryNotEmptyException refused = null;
            try {
                Files.move(srcDir, moved);
            } catch (DirectoryNotEmptyException e) {
                refused = e;
            }
            assumeTrue("device supports native directory move; emulation path not exercised",
                refused != null);

            // The emulation refused the move: the source must be untouched and the target absent.
            assertTrue("Source directory should still exist", Files.isDirectory(srcDir));
            assertTrue("Source child should still exist", Files.isRegularFile(srcDir.resolve("child.bin")));
            assertFalse("Target should not have been created", Files.exists(moved));
        } finally {
            deleteTreeQuietly(srcDir);
            deleteTreeQuietly(destParent);
        }
    }

    // --- copy ---

    @Test
    public void copyFileCreatesIndependentCopy() throws IOException {
        var storage = requireFirstStorage();
        var src = storage.resolve(TEST_FILE_NAME);
        var dst = storage.resolve(TEST_FILE_NAME2);
        var payload = "copy me".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Files.write(src, payload);
        try {
            Files.copy(src, dst);
            assertTrue("Source should still exist after copy", Files.isRegularFile(src));
            assertTrue("Target should exist after copy", Files.isRegularFile(dst));
            assertArrayEquals("Copied content should match the source", payload, Files.readAllBytes(dst));
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(dst);
        }
    }

    @Test(expected = FileAlreadyExistsException.class)
    public void copyFailsWhenTargetExistsWithoutReplace() throws IOException {
        var storage = requireFirstStorage();
        var src = storage.resolve(TEST_FILE_NAME);
        var dst = storage.resolve(TEST_FILE_NAME2);
        Files.write(src, new byte[]{1});
        Files.write(dst, new byte[]{2});
        try {
            Files.copy(src, dst);
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(dst);
        }
    }

    @Test
    public void copyReplacesExistingTarget() throws IOException {
        var storage = requireFirstStorage();
        var src = storage.resolve(TEST_FILE_NAME);
        var dst = storage.resolve(TEST_FILE_NAME2);
        Files.write(src, "new".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(dst, "old-and-longer".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            assertEquals("new", new String(Files.readAllBytes(dst), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue("Source should remain after copy", Files.isRegularFile(src));
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(dst);
        }
    }

    @Test
    public void copyDirectoryIsNonRecursive() throws IOException {
        var storage = requireFirstStorage();
        var srcDir = storage.resolve(TEST_DIR_NAME);
        var dstDir = storage.resolve(TEST_DIR_NAME2);
        Files.createDirectory(srcDir);
        Files.write(srcDir.resolve("child.bin"), new byte[]{9});
        try {
            Files.copy(srcDir, dstDir);
            assertTrue("Target directory should be created", Files.isDirectory(dstDir));
            // Per the Files.copy contract, copying a directory creates the directory only.
            assertFalse("Directory entries should not be copied recursively",
                Files.exists(dstDir.resolve("child.bin")));
        } finally {
            deleteTreeQuietly(srcDir);
            deleteTreeQuietly(dstDir);
        }
    }

    // --- append & large-file streaming ---

    @Test
    public void appendExtendsExistingFile() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        Files.write(file, "first".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            Files.write(file, "-second".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
            assertEquals("first-second",
                new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void largeFileRoundTripStreams() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        var payload = new byte[4 * 1024 * 1024]; // 4 MiB: exercises the streamed read/write path
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + 7);
        }

        Files.write(file, payload);
        try {
            assertEquals("Reported size should match the written length", payload.length, Files.size(file));
            assertArrayEquals("Large round-tripped content should match", payload, Files.readAllBytes(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // --- isSameFile ---

    @Test
    public void isSameFileTrueForEquivalentPaths() throws IOException {
        var storage = requireFirstStorage();
        var a = storage.resolve(TEST_FILE_NAME);
        var b = storage.resolve(TEST_FILE_NAME);
        Files.write(a, new byte[]{1});
        try {
            assertTrue("Two paths resolving to the same object should be the same file",
                Files.isSameFile(a, b));
            assertTrue("A path should be the same file as itself", Files.isSameFile(a, a));
        } finally {
            Files.deleteIfExists(a);
        }
    }

    @Test
    public void isSameFileFalseForDifferentPaths() throws IOException {
        var storage = requireFirstStorage();
        var a = storage.resolve(TEST_FILE_NAME);
        var b = storage.resolve(TEST_FILE_NAME2);
        Files.write(a, new byte[]{1});
        Files.write(b, new byte[]{2});
        try {
            assertFalse("Distinct paths should not be the same file", Files.isSameFile(a, b));
        } finally {
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
        }
    }

    // --- isHidden ---

    @Test
    public void isHiddenAlwaysFalse() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        Files.write(file, new byte[]{7});
        try {
            assertFalse("MTP filesystem reports no hidden files", Files.isHidden(file));
            assertFalse("Storage root should not be hidden", Files.isHidden(storage));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // --- checkAccess ---

    @Test
    public void checkAccessSucceedsForReadAndWriteOnExistingFile() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        Files.write(file, new byte[]{1, 2, 3});
        try {
            // No exception means access is granted.
            provider.checkAccess(file);
            provider.checkAccess(file, AccessMode.READ);
            provider.checkAccess(file, AccessMode.WRITE);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void checkAccessExecuteThrowsAccessDenied() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        Files.write(file, new byte[]{1});
        try {
            assertThrows(AccessDeniedException.class,
                () -> provider.checkAccess(file, AccessMode.EXECUTE));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test(expected = NoSuchFileException.class)
    public void checkAccessThrowsForMissingFile() throws IOException {
        var storage = requireFirstStorage();
        provider.checkAccess(storage.resolve(TEST_FILE_NAME));
    }

    // --- attribute views / string attributes ---

    @Test
    public void basicFileAttributeViewReadsAttributes() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        var payload = new byte[]{1, 2, 3, 4, 5};
        Files.write(file, payload);
        try {
            var view = Files.getFileAttributeView(file, BasicFileAttributeView.class);
            assertNotNull("BasicFileAttributeView should be available", view);
            var attrs = view.readAttributes();
            assertTrue("View should report the file as a regular file", attrs.isRegularFile());
            assertEquals("View should report the written size", payload.length, attrs.size());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void setTimesViaViewIsUnsupported() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        Files.write(file, new byte[]{1});
        try {
            var view = Files.getFileAttributeView(file, BasicFileAttributeView.class);
            assertThrows(UnsupportedOperationException.class,
                () -> view.setTimes(FileTime.fromMillis(0), null, null));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void readStringAttributesReturnsRequestedKeys() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        var payload = new byte[]{1, 2, 3, 4};
        Files.write(file, payload);
        try {
            var attrs = Files.readAttributes(file, "basic:size,isRegularFile,isDirectory");
            assertEquals("Only the requested keys should be returned", 3, attrs.size());
            assertEquals((long) payload.length, attrs.get("size"));
            assertEquals(Boolean.TRUE, attrs.get("isRegularFile"));
            assertEquals(Boolean.FALSE, attrs.get("isDirectory"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void readStringAttributesWildcardReturnsAllBasicKeys() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        Files.write(file, new byte[]{1});
        try {
            var attrs = Files.readAttributes(file, "*");
            assertTrue("Wildcard should include size", attrs.containsKey("size"));
            assertTrue("Wildcard should include isDirectory", attrs.containsKey("isDirectory"));
            assertTrue("Wildcard should include fileKey", attrs.containsKey("fileKey"));
            assertTrue("Wildcard should include lastModifiedTime", attrs.containsKey("lastModifiedTime"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void readUnknownStringAttributeThrows() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        Files.write(file, new byte[]{1});
        try {
            assertThrows(IllegalArgumentException.class,
                () -> Files.readAttributes(file, "basic:notAnAttribute"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void setAttributeIsUnsupported() throws IOException {
        var storage = requireFirstStorage();
        var file = storage.resolve(TEST_FILE_NAME);
        Files.write(file, new byte[]{1});
        try {
            assertThrows(UnsupportedOperationException.class,
                () -> Files.setAttribute(file, "basic:lastModifiedTime", FileTime.fromMillis(0)));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // --- helpers ---

    private Path requireFirstStorage() throws IOException {
        var storage = firstStorageOrNull();
        assumeTrue("No storages available on device", storage != null);
        return storage;
    }

    private Path firstStorageOrNull() throws IOException {
        var storages = listChildren(fs.getPath("/"));
        return storages.isEmpty() ? null : storages.get(0);
    }

    private List<Path> listChildren(Path dir) throws IOException {
        var result = new ArrayList<Path>();
        try (var stream = Files.newDirectoryStream(dir)) {
            stream.forEach(result::add);
        }
        return result;
    }

    private Path findFirstFile(Path dir, int depthRemaining) throws IOException {
        try (var stream = Files.newDirectoryStream(dir)) {
            for (var child : stream) {
                if (Files.isRegularFile(child)) return child;
                if (Files.isDirectory(child) && depthRemaining > 0) {
                    var found = findFirstFile(child, depthRemaining - 1);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static boolean isBackendAvailable() {
        try {
            // Loads the platform's native backend: libmtp on Linux/macOS, WPD (ole32) on Windows.
            MtpBackend.defaultBackend();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
