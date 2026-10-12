/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NativeStaticFileAccessTests {
    @Test
    void secureBodiesExposeASeparateTransportContractInsteadOfAnOrdinaryPath(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("public"));
        Path asset = Files.writeString(root.resolve("asset.txt"), "public");
        Path secret = Files.writeString(directory.resolve("secret.txt"), "secret");
        StaticFiles files = StaticFiles.withRoot(root).build();
        MarshaledResponse response = files.marshaledResponseFor("asset.txt",
                Request.fromPath(HttpMethod.GET, "/asset.txt")).orElseThrow();
        MarshaledResponseBody.SecureFile body = assertInstanceOf(MarshaledResponseBody.SecureFile.class,
                response.getBody().orElseThrow());
        assertEquals(0, MarshaledResponseBody.SecureFile.class.getConstructors().length,
                "Applications must not construct unverified secure descriptors");
        assertEquals(0L, body.getOffset());
        assertEquals(6L, body.getCount());
        try (var channel = body.openChannel()) {
            ByteBuffer bytes = ByteBuffer.allocate(6);
            while (bytes.hasRemaining()) assertTrue(channel.read(bytes) > 0);
            assertEquals("public", new String(bytes.array(), StandardCharsets.UTF_8));
        }
        Files.delete(asset);
        Files.createSymbolicLink(asset, secret.toAbsolutePath());
        assertThrows(IOException.class, body::openChannel,
                "A custom transport must receive the same late replacement rejection as the built-in transport");
    }

    @Test
    void openedIdentityCannotBeBorrowedFromAnAuthorizedNameRestoredAfterOpen(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("public")).toRealPath();
        Path approved = Files.writeString(root.resolve("asset.txt"), "public");
        Path replacement = Files.writeString(directory.resolve("replacement.txt"), "secret");
        BasicFileAttributes expected = Files.readAttributes(approved, BasicFileAttributes.class);
        Files.setLastModifiedTime(replacement, expected.lastModifiedTime());
        NativeStaticFileAccess access = NativeStaticFileAccess.forPath(root);
        try (var pinned = access.openDirectory(root)) {
            Path saved = root.resolve("saved.txt");
            Files.move(approved, saved);
            Files.move(replacement, approved);
            try (var wrongFile = access.openAt(pinned, Path.of("asset.txt"))) {
                Files.move(approved, replacement);
                Files.move(saved, approved);
                BasicFileAttributes restoredName = Files.readAttributes(approved, BasicFileAttributes.class);
                BasicFileAttributes actuallyOpened = wrongFile.attributes();
                assertEquals(expected.fileKey(), restoredName.fileKey());
                assertEquals(expected.size(), actuallyOpened.size());
                assertEquals(expected.lastModifiedTime(), actuallyOpened.lastModifiedTime());
                assertNotEquals(expected.fileKey(), actuallyOpened.fileKey());
                assertThrows(IOException.class, () -> SecureStaticFileRoot.checkIdentity(actuallyOpened,
                        expected.fileKey(), expected.size(), expected.lastModifiedTime()));
            }
        }
    }

    @Test
    void everyNativeTraversalStepRejectsSymbolicLinks(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("public")).toRealPath();
        Path outside = Files.createDirectory(directory.resolve("outside")).toRealPath();
        Files.writeString(outside.resolve("asset.txt"), "secret");
        Files.createSymbolicLink(root.resolve("asset.txt"), outside.resolve("asset.txt"));
        Files.createSymbolicLink(root.resolve("child"), outside);
        NativeStaticFileAccess access = NativeStaticFileAccess.forPath(root);
        try (var pinned = access.openDirectory(root)) {
            assertThrows(IOException.class, () -> access.openAt(pinned, Path.of("asset.txt")));
            assertThrows(IOException.class, () -> access.openAt(pinned, Path.of("child")));
            assertThrows(IOException.class, () -> access.openDirectory(root.resolve("child")));
        }
    }

    @Test
    void pinnedRootAndChildRemainBoundToTheirDirectoriesAfterRename(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("public")).toRealPath();
        Path child = Files.createDirectory(root.resolve("child"));
        Files.writeString(root.resolve("asset.txt"), "public");
        Files.writeString(child.resolve("asset.txt"), "public");
        Path outside = Files.createDirectory(directory.resolve("outside")).toRealPath();
        Files.writeString(outside.resolve("asset.txt"), "secret");
        NativeStaticFileAccess access = NativeStaticFileAccess.forPath(root);
        try (var pinnedRoot = access.openDirectory(root);
             var pinnedChild = access.openAt(pinnedRoot, Path.of("child"))) {
            assertTrue(pinnedRoot.attributes().isDirectory());
            assertTrue(pinnedChild.attributes().isDirectory());
            Files.move(child, root.resolve("old-child"));
            Files.createSymbolicLink(child, outside);
            assertThrows(IOException.class, () -> access.openAt(pinnedRoot, Path.of("child")));
            try (var originalChildFile = access.openAt(pinnedChild, Path.of("asset.txt"))) {
                assertEquals("public", sixByteContents(originalChildFile.channel()));
            }
            Files.move(root, directory.resolve("old-public"));
            Files.createSymbolicLink(root, outside);
            try (var originalRootFile = access.openAt(pinnedRoot, Path.of("asset.txt"))) {
                assertEquals("public", sixByteContents(originalRootFile.channel()));
            }
        }
    }

    @Test
    void relativeOpenRequiresOneSafeComponentFromTheNativeFilesystem(@TempDir Path directory) throws Exception {
        Path root = directory.toRealPath();
        Files.writeString(root.resolve("asset.txt"), "public");
        NativeStaticFileAccess access = NativeStaticFileAccess.forPath(root);
        try (var pinned = access.openDirectory(root)) {
            for (Path name : List.of(Path.of(""), Path.of("."), Path.of(".."),
                    Path.of("child/asset.txt"), Path.of("../asset.txt"), root.resolve("asset.txt")))
                assertThrows(IOException.class, () -> access.openAt(pinned, name), name.toString());
            assertThrows(IOException.class, () -> access.openDirectory(Path.of("relative-root")));
            assertThrows(IOException.class, () -> access.openDirectory(root.resolve("asset.txt")));
            URI archive = URI.create("jar:" + root.resolve("fixture.zip").toUri());
            try (var foreign = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
                assertThrows(IOException.class, () -> access.openAt(pinned, foreign.getPath("asset.txt")));
                assertThrows(IOException.class, () -> NativeStaticFileAccess.forPath(foreign.getPath("/")));
            }
        }
    }

    @Test
    void transferredHandleCannotInspectOrCloseItsChannelsDescriptor(@TempDir Path directory) throws Exception {
        Path root = directory.toRealPath();
        Files.writeString(root.resolve("asset.txt"), "public");
        NativeStaticFileAccess access = NativeStaticFileAccess.forPath(root);
        try (var pinned = access.openDirectory(root);
             var opened = access.openAt(pinned, Path.of("asset.txt"))) {
            try (FileChannel channel = opened.channel()) {
                assertThrows(IOException.class, opened::attributes);
                assertThrows(IOException.class, opened::channel);
                opened.close();
                assertTrue(channel.isOpen());
                assertEquals("public", sixByteContentsWithoutClosing(channel));
            }
            opened.close();
        }
    }

    @Test
    void closingAnAlreadyClosedHandleCannotCloseASubsequentlyOpenedDescriptor(@TempDir Path directory) throws Exception {
        Path root = directory.toRealPath();
        Files.writeString(root.resolve("asset.txt"), "public");
        NativeStaticFileAccess access = NativeStaticFileAccess.forPath(root);
        try (var pinned = access.openDirectory(root)) {
            var first = access.openAt(pinned, Path.of("asset.txt"));
            first.close();
            assertThrows(IOException.class, first::attributes);
            try (var next = access.openAt(pinned, Path.of("asset.txt"))) {
                first.close();
                assertEquals("public", sixByteContents(next.channel()));
            }
        }
    }

    @Test
    void openedDirectoryCannotPassARegularFileIdentityCheck(@TempDir Path directory) throws Exception {
        Path root = directory.toRealPath();
        NativeStaticFileAccess access = NativeStaticFileAccess.forPath(root);
        try (var pinned = access.openDirectory(root)) {
            BasicFileAttributes attributes = pinned.attributes();
            assertTrue(attributes.isDirectory());
            assertThrows(IOException.class, () -> SecureStaticFileRoot.checkIdentity(attributes,
                    attributes.fileKey(), attributes.size(), attributes.lastModifiedTime()));
        }
    }

    @Test
    @Timeout(15)
    void openingAFifoDoesNotBlockAndItsOpenedTypeIsRejected(@TempDir Path directory) throws Exception {
        Path root = directory.toRealPath();
        Path fifo = root.resolve("fifo");
        Path makerLog = root.resolve("mkfifo.log");
        Process maker = new ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true)
                .redirectOutput(makerLog.toFile()).start();
        try {
            assertTrue(maker.waitFor(3, TimeUnit.SECONDS), "mkfifo did not complete");
            assertEquals(0, maker.exitValue(), boundedProcessLog(makerLog));
        } finally {
            try {
                maker.destroyForcibly();
                assertTrue(maker.waitFor(2, TimeUnit.SECONDS), "mkfifo remained alive");
            } finally {
                closeProcessStreams(maker);
            }
        }
        runBoundedChild(root, FifoProbe.class, List.of(), "NATIVE_FIFO_PROBE_PASS");
    }

    @Test
    @Timeout(10)
    void missingUnsupportedModuleFailsClosedInsteadOfReopeningPaths(@TempDir Path directory) throws Exception {
        runBoundedChild(directory.toRealPath(), UnsupportedRuntimeProbe.class,
                List.of("--limit-modules=java.base"), "NATIVE_UNSUPPORTED_PROBE_PASS");
    }

    @Test
    @Timeout(10)
    void installedLegacySecurityManagerIsRejectedEvenAfterBridgeInitialization(@TempDir Path directory) throws Exception {
        // Java 24 permanently disabled SecurityManager installation. Both macOS
        // baseline runtimes (17 and 21) execute the isolated policy probe.
        if (Runtime.version().feature() >= 24)
            return;
        Path root = directory.toRealPath();
        Files.writeString(root.resolve("asset.txt"), "public");
        runBoundedChild(root, SecurityManagerProbe.class,
                List.of("-Djava.security.manager=allow"), "NATIVE_SECURITY_MANAGER_PROBE_PASS");
    }

    private static void runBoundedChild(Path root, Class<?> entrypoint, List<String> options,
            String expectedMarker) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> arguments = new java.util.ArrayList<>();
        arguments.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        arguments.addAll(options);
        arguments.addAll(List.of("-classpath", classpath, entrypoint.getName(), root.toString()));
        Path log = root.resolve(entrypoint.getSimpleName() + ".log");
        Process probe = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(probe.waitFor(4, TimeUnit.SECONDS), "Native descriptor probe did not complete");
            String output = boundedProcessLog(log);
            assertEquals(0, probe.exitValue(), output);
            assertTrue(output.contains(expectedMarker), output);
        } finally {
            try {
                probe.destroyForcibly();
                assertTrue(probe.waitFor(2, TimeUnit.SECONDS), "Native descriptor probe remained alive");
            } finally {
                closeProcessStreams(probe);
            }
        }
    }

    private static String boundedProcessLog(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(8193);
            if (bytes.length > 8192)
                throw new IOException("Native descriptor probe output exceeded the eight-KiB fixture limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void closeProcessStreams(Process process) throws IOException {
        try (var input = process.getInputStream();
             var error = process.getErrorStream();
             var output = process.getOutputStream()) {
            // Closing redirected/null streams also closes any retained process pipe.
        }
    }

    public static final class UnsupportedRuntimeProbe {
        public static void main(String[] arguments) throws Exception {
            try {
                NativeStaticFileAccess.forPath(Path.of(arguments[0]));
                throw new AssertionError("A limited runtime unexpectedly supplied the native bridge");
            } catch (IOException expected) {
                System.out.println("NATIVE_UNSUPPORTED_PROBE_PASS");
            }
        }
    }

    @SuppressWarnings("removal")
    public static final class SecurityManagerProbe {
        public static void main(String[] arguments) throws Exception {
            Path root = Path.of(arguments[0]);
            NativeStaticFileAccess access = NativeStaticFileAccess.forPath(root);
            try (var pinned = access.openDirectory(root);
                 var file = access.openAt(pinned, Path.of("asset.txt"))) {
                System.setSecurityManager(new SecurityManager() {
                    @Override
                    public void checkPermission(java.security.Permission permission) {
                        // Leave ordinary JVM cleanup possible; the native bridge
                        // must reject the installed policy regardless of its grants.
                    }
                });
                expectSecurityManagerRejection(() -> NativeStaticFileAccess.forPath(root));
                expectSecurityManagerRejection(() -> access.openDirectory(root));
                expectSecurityManagerRejection(() -> access.openAt(pinned, Path.of("asset.txt")));
                expectSecurityManagerRejection(pinned::attributes);
                expectSecurityManagerRejection(file::channel);
                System.out.println("NATIVE_SECURITY_MANAGER_PROBE_PASS");
            } finally {
                System.setSecurityManager(null);
            }
        }

        private static void expectSecurityManagerRejection(IoOperation operation) throws IOException {
            try {
                operation.run();
                throw new AssertionError("Native access bypassed an installed SecurityManager");
            } catch (IOException expected) {
                if (!expected.getMessage().contains("SecurityManager"))
                    throw expected;
            }
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        Object run() throws IOException;
    }

    public static final class FifoProbe {
        public static void main(String[] arguments) throws Exception {
            Path root = Path.of(arguments[0]);
            NativeStaticFileAccess access = NativeStaticFileAccess.forPath(root);
            try (var pinned = access.openDirectory(root);
                 var fifo = access.openAt(pinned, Path.of("fifo"))) {
                BasicFileAttributes attributes = fifo.attributes();
                if (attributes.isRegularFile())
                    throw new AssertionError("FIFO was incorrectly identified as a regular file");
                try {
                    SecureStaticFileRoot.checkIdentity(attributes, attributes.fileKey(),
                            attributes.size(), attributes.lastModifiedTime());
                    throw new AssertionError("FIFO passed the regular-file identity check");
                } catch (IOException expected) {
                    System.out.println("NATIVE_FIFO_PROBE_PASS");
                }
            }
        }
    }

    private static String sixByteContents(FileChannel channel) throws IOException {
        try (channel) {
            return sixByteContentsWithoutClosing(channel);
        }
    }

    private static String sixByteContentsWithoutClosing(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(6);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) <= 0)
                throw new IOException("Expected six readable file bytes");
        }
        buffer.flip();
        return StandardCharsets.UTF_8.decode(buffer).toString();
    }
}
