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
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class SecureStaticFileAccessTests {
    @Test
    void immutableRootDeclarationIsRequiredAndCanBeWithdrawn(@TempDir Path root) {
        for (boolean followSymlinks : List.of(false, true)) {
            StaticFiles.Builder builder = StaticFiles.withRoot(root).followSymlinks(followSymlinks);
            assertTrue(assertThrows(IllegalArgumentException.class, builder::build)
                    .getMessage().contains("trustedImmutableRoot(true)"));
            builder.trustedImmutableRoot(true).build();
            assertThrows(IllegalArgumentException.class, () -> builder.trustedImmutableRoot(false).build());
            assertThrows(IllegalArgumentException.class, () -> builder.trustedImmutableRoot(true)
                    .trustedImmutableRoot(null).build());
        }
    }

    @Test
    void secureBodiesExposeTheirOwnTransportContract(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("public"));
        Path asset = Files.writeString(root.resolve("asset.txt"), "public");
        Path secret = Files.writeString(directory.resolve("secret.txt"), "secret");
        StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).build();
        MarshaledResponse response = files.marshaledResponseFor("asset.txt", request()).orElseThrow();
        MarshaledResponseBody.SecureFile body = assertInstanceOf(MarshaledResponseBody.SecureFile.class,
                response.getBody().orElseThrow());
        assertEquals(0, MarshaledResponseBody.SecureFile.class.getConstructors().length);
        assertEquals(0L, body.getOffset());
        assertEquals(6L, body.getCount());
        try (var channel = body.openChannel()) {
            assertInstanceOf(FileChannel.class, channel, "Default provider keeps the native transfer opportunity");
            assertEquals("public", contents(channel));
        }
        Files.delete(asset);
        Files.createSymbolicLink(asset, secret.toAbsolutePath());
        // Deliberate contract violation: observed replacement must still fail closed.
        assertThrows(IOException.class, body::openChannel);
    }

    @Test
    void sameSizeAndTimeReplacementIsRejected(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("public"));
        Path asset = Files.writeString(root.resolve("asset.txt"), "public");
        BasicFileAttributes expected = Files.readAttributes(asset, BasicFileAttributes.class);
        SecureStaticFileRoot access = new SecureStaticFileRoot(root);
        Path replacement = Files.writeString(directory.resolve("replacement.txt"), "secret");
        Files.setLastModifiedTime(replacement, expected.lastModifiedTime());
        Files.move(replacement, asset, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IOException.class, () -> access.open(asset, expected.fileKey(),
                expected.size(), expected.lastModifiedTime()));
    }

    @Test
    void traversalRejectsLinksAndUnsafeComponents(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("public"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("asset.txt"), "secret");
        BasicFileAttributes expected = Files.readAttributes(secret, BasicFileAttributes.class);
        SecureStaticFileRoot access = new SecureStaticFileRoot(root);
        Files.createSymbolicLink(root.resolve("asset.txt"), secret);
        Files.createSymbolicLink(root.resolve("child"), outside);
        for (Path path : List.of(root, root.resolve("asset.txt"), root.resolve("child/asset.txt"),
                outside.resolve("asset.txt"), root.resolve("../outside/asset.txt"))) {
            assertThrows(IOException.class, () -> access.open(path, expected.fileKey(),
                    expected.size(), expected.lastModifiedTime()), path.toString());
        }
    }

    @Test
    void rootReplacementRequiresANewHelper(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("public"));
        Path asset = Files.writeString(root.resolve("asset.txt"), "public");
        BasicFileAttributes expected = Files.readAttributes(asset, BasicFileAttributes.class);
        SecureStaticFileRoot access = new SecureStaticFileRoot(root);
        Files.move(root, directory.resolve("old-public"));
        Files.createDirectory(root);
        Files.writeString(root.resolve("asset.txt"), "secret");
        assertThrows(IOException.class, () -> access.open(asset, expected.fileKey(),
                expected.size(), expected.lastModifiedTime()));
    }

    @Test
    void followedRootAliasRetargetingInvalidatesExistingHelperAndBody(@TempDir Path directory) throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Files.writeString(first.resolve("asset.txt"), "public");
        Files.writeString(second.resolve("asset.txt"), "secret");
        Path alias = Files.createSymbolicLink(directory.resolve("current"), first);
        StaticFiles files = StaticFiles.withRoot(alias).trustedImmutableRoot(true).followSymlinks(true).build();
        MarshaledResponse response = files.marshaledResponseFor("asset.txt", request()).orElseThrow();
        Files.delete(alias);
        Files.createSymbolicLink(alias, second);
        assertTrue(files.marshaledResponseFor("asset.txt", request()).isEmpty());
        assertThrows(java.io.UncheckedIOException.class, response::bodyBytesOrEmpty);
        assertEquals("secret", new String(StaticFiles.withRoot(alias).trustedImmutableRoot(true)
                .followSymlinks(true).build().marshaledResponseFor("asset.txt", request()).orElseThrow()
                .bodyBytesOrEmpty(), StandardCharsets.UTF_8));
    }

    @Test
    void searchOnlyAncestorDoesNotNeedDirectoryListing(@TempDir Path directory) throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path ancestor = Files.createDirectory(directory.resolve("home"));
        Path root = Files.createDirectory(ancestor.resolve("public"));
        Files.writeString(root.resolve("asset.txt"), "public");
        var permissions = Files.getPosixFilePermissions(ancestor);
        try {
            Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("--x--x--x"));
            assumeFalse(Files.isReadable(ancestor), "Privileged processes bypass read permissions");
            StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).build();
            assertEquals("public", new String(files.marshaledResponseFor("asset.txt", request())
                    .orElseThrow().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
        } finally {
            Files.setPosixFilePermissions(ancestor, permissions);
        }
    }

    @Test
    void helperConstructionAndDeliveryDoNotRetainDirectoryDescriptors(@TempDir Path root) throws Exception {
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        assumeTrue(operatingSystem instanceof com.sun.management.UnixOperatingSystemMXBean);
        var unix = (com.sun.management.UnixOperatingSystemMXBean) operatingSystem;
        Files.writeString(root.resolve("asset.txt"), "public");
        // Warm up provider and descriptor classes before measuring; retain helpers so GC cannot mask a leak.
        StaticFiles.withRoot(root).trustedImmutableRoot(true).build()
                .marshaledResponseFor("asset.txt", request()).orElseThrow().bodyBytesOrEmpty();
        long before = unix.getOpenFileDescriptorCount();
        List<StaticFiles> retained = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).build();
            retained.add(files);
            assertArrayEquals("public".getBytes(StandardCharsets.UTF_8), files
                    .marshaledResponseFor("asset.txt", request()).orElseThrow().bodyBytesOrEmpty());
        }
        assertTrue(unix.getOpenFileDescriptorCount() <= before + 8, "Helpers or deliveries retained descriptors");
        assertEquals(512, retained.size());
        java.lang.ref.Reference.reachabilityFence(retained);
    }

    @Test
    void directoryCannotPassRegularFileIdentityCheck(@TempDir Path root) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class);
        assertThrows(IOException.class, () -> SecureStaticFileRoot.checkIdentity(attributes,
                attributes.fileKey(), attributes.size(), attributes.lastModifiedTime()));
    }

    @Test
    @Timeout(15)
    void immutableFifoIsRejectedWithoutOpeningIt(@TempDir Path directory) throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path fifo = directory.resolve("asset.txt");
        Process maker = new ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("mkfifo.log").toFile()).start();
        try {
            assertTrue(maker.waitFor(3, TimeUnit.SECONDS));
            assertEquals(0, maker.exitValue());
        } finally {
            stop(maker);
        }
        runBoundedChild(directory, FifoProbe.class, List.of(), "PUBLIC_FIFO_PASS");
    }

    @Test
    @Timeout(10)
    void staticFilesWorksWithoutUnsupportedModule(@TempDir Path directory) throws Exception {
        runBoundedChild(directory, PublicRuntimeProbe.class, List.of("--limit-modules=java.base"), "PUBLIC_NIO_PASS");
    }

    @Test
    @Timeout(10)
    void staticFilesWorksWhenUnsafeMemoryAccessIsDenied(@TempDir Path directory) throws Exception {
        assumeTrue(Runtime.version().feature() >= 24);
        runBoundedChild(directory, PublicRuntimeProbe.class,
                List.of("--sun-misc-unsafe-memory-access=deny"), "PUBLIC_NIO_PASS");
    }

    private static Request request() { return Request.fromPath(HttpMethod.GET, "/asset.txt"); }

    private static String contents(SeekableByteChannel channel) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(6);
        while (bytes.hasRemaining()) assertTrue(channel.read(bytes) > 0);
        return new String(bytes.array(), StandardCharsets.UTF_8);
    }

    private static void runBoundedChild(Path root, Class<?> entrypoint, List<String> options, String marker) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> arguments = new ArrayList<>();
        arguments.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        arguments.addAll(options);
        arguments.addAll(List.of("-classpath", classpath, entrypoint.getName(), root.toString()));
        Path log = root.resolve(entrypoint.getSimpleName() + ".log");
        Process probe = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(probe.waitFor(4, TimeUnit.SECONDS), "Public NIO probe did not complete");
            String output;
            try (var input = Files.newInputStream(log)) {
                byte[] bytes = input.readNBytes(8193);
                assertTrue(bytes.length <= 8192, "Probe log exceeded eight KiB");
                output = new String(bytes, StandardCharsets.UTF_8);
            }
            assertEquals(0, probe.exitValue(), output);
            assertTrue(output.contains(marker), output);
            assertFalse(output.contains("sun.misc.Unsafe"), output);
        } finally {
            stop(probe);
        }
    }

    private static void stop(Process process) throws Exception {
        try {
            process.destroyForcibly();
            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "Probe remained alive");
        } finally {
            try (var input = process.getInputStream(); var error = process.getErrorStream(); var output = process.getOutputStream()) {
                // Close pipes even when child termination fails its assertion.
            }
        }
    }

    public static final class PublicRuntimeProbe {
        public static void main(String[] arguments) throws Exception {
            Path root = Path.of(arguments[0]);
            Files.writeString(root.resolve("asset.txt"), "public");
            StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).build();
            String body = new String(files.marshaledResponseFor("asset.txt", request())
                    .orElseThrow().bodyBytesOrEmpty(), StandardCharsets.UTF_8);
            if (!body.equals("public")) throw new AssertionError(body);
            System.out.println("PUBLIC_NIO_PASS");
        }
    }

    public static final class FifoProbe {
        public static void main(String[] arguments) throws Exception {
            StaticFiles files = StaticFiles.withRoot(Path.of(arguments[0])).trustedImmutableRoot(true).build();
            if (files.marshaledResponseFor("asset.txt", request()).isPresent())
                throw new AssertionError("FIFO was treated as a regular file");
            System.out.println("PUBLIC_FIFO_PASS");
        }
    }
}
