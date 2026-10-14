package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.net.URI;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StaticFileIdentityTests {
    @Test
    void bothSymlinkModesFailClosedOnProviderWithoutStableIdentity(@TempDir Path directory) throws Exception {
        Path archive = directory.resolve("assets.zip");
        try (var fileSystem = FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()),
                Map.of("create", "true"))) {
            Path root = Files.createDirectory(fileSystem.getPath("/public"));
            Files.writeString(root.resolve("asset.txt"), "public");
            for (boolean followSymlinks : List.of(false, true)) {
                java.io.UncheckedIOException failure = assertThrows(java.io.UncheckedIOException.class,
                        () -> StaticFiles.withRoot(root).trustedImmutableRoot(true).followSymlinks(followSymlinks).build());
                assertTrue(failure.getCause().getMessage().contains("stable directory identity"));
            }
        }
    }

    @Test
    void fixtureFilesystemIsCaseInsensitiveWhenRequired(@TempDir Path root) throws Exception {
        if (!Boolean.getBoolean("soklet.test.requireCaseInsensitiveFilesystem"))
            return;
        Path actual = Files.writeString(root.resolve("CaseSensitivityProbe.txt"), "probe");
        Path alias = root.resolve("casesensitivityprobe.TXT");
        assertTrue(Files.exists(alias), "This CI job requires a case-insensitive fixture filesystem");
        assertTrue(Files.isSameFile(actual, alias), "Case variants must resolve to the same fixture file");
    }

    @Test
    void configuredAncestorSpellingIsPreservedAndDeploymentSwapRequiresRebuild(@TempDir Path directory) throws Exception {
        Path firstRelease = Files.createDirectories(directory.resolve("release-one/public/private"));
        Path secondRelease = Files.createDirectories(directory.resolve("release-two/public/private"));
        Files.writeString(firstRelease.resolve("secret.txt"), "secret one");
        Files.writeString(secondRelease.resolve("secret.txt"), "secret two");
        Files.writeString(firstRelease.getParent().resolve("asset.txt"), "release one");
        Files.writeString(secondRelease.getParent().resolve("asset.txt"), "release two");
        Path current = directory.resolve("current");
        Files.createSymbolicLink(current, firstRelease.getParent().getParent());
        Path configuredRoot = current.resolve("public");
        AtomicReference<Path> seen = new AtomicReference<>();
        StaticFiles files = StaticFiles.withRoot(configuredRoot).trustedImmutableRoot(true).accessResolver((path, attributes) -> {
            seen.set(path);
            return path.startsWith(configuredRoot.resolve("private")) ? StaticFiles.Access.DENY : StaticFiles.Access.ALLOW;
        }).build();

        assertEquals(403, files.marshaledResponseFor("private/secret.txt", request("private/secret.txt")).orElseThrow().getStatusCode());
        assertEquals(configuredRoot.resolve("private/secret.txt"), seen.get());
        assertEquals("release one", new String(files.marshaledResponseFor("asset.txt", request("asset.txt")).orElseThrow().bodyBytesOrNull(), StandardCharsets.UTF_8));

        Files.delete(current);
        Files.createSymbolicLink(current, secondRelease.getParent().getParent());
        Files.delete(firstRelease.getParent().resolve("asset.txt"));
        // The old helper rejects a changed root. A trusted deployment explicitly
        // constructs a new helper after prior responses finish.
        files = StaticFiles.withRoot(configuredRoot).trustedImmutableRoot(true).accessResolver((path, attributes) -> {
            seen.set(path);
            return path.startsWith(configuredRoot.resolve("private")) ? StaticFiles.Access.DENY : StaticFiles.Access.ALLOW;
        }).build();
        assertEquals("release two", new String(files.marshaledResponseFor("asset.txt", request("asset.txt")).orElseThrow().bodyBytesOrNull(), StandardCharsets.UTF_8));
        assertEquals(403, files.marshaledResponseFor("private/secret.txt", request("private/secret.txt")).orElseThrow().getStatusCode());
    }

    @Test
    void searchOnlyDirectoriesUseCheckedPathAccess(@TempDir Path root) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Path nested = Files.createDirectory(root.resolve("traverse"));
        Files.writeString(nested.resolve("asset.txt"), "public asset");
        var originalPermissions = Files.getPosixFilePermissions(nested);
        try {
            Files.setPosixFilePermissions(nested, PosixFilePermissions.fromString("--x--x--x"));
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(nested),
                    "A privileged process can bypass directory read permissions");
            StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).build();
            var response = files.marshaledResponseFor("traverse/asset.txt", request("traverse/asset.txt"));
            assertEquals("public asset", new String(response.orElseThrow().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
        } finally {
            Files.setPosixFilePermissions(nested, originalPermissions);
        }
    }

    @Test
    void accessPoliciesReceiveActualFileAndDirectorySpelling(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("Private"));
        Path hidden = Files.writeString(directory.resolve(".env"), "secret");
        Path denied = Files.writeString(directory.resolve("Internal.json"), "private");
        AtomicReference<Path> seen = new AtomicReference<>();
        StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).accessResolver((path, attributes) -> {
            seen.set(path);
            assertEquals("Private", path.getParent().getFileName().toString());
            return path.getFileName().toString().equals(".env") ? StaticFiles.Access.HIDE : StaticFiles.Access.DENY;
        }).build();
        for (String spelling : List.of("Private/.env", "private/.ENV", "PRIVATE/.EnV")) {
            Optional<MarshaledResponse> response = files.marshaledResponseFor(spelling, request(spelling));
            assertTrue(response.isEmpty());
            if (Files.exists(root.resolve(spelling)))
                assertEquals(hidden.getFileName().toString(), seen.get().getFileName().toString());
        }
        for (String spelling : List.of("Private/Internal.json", "private/INTERNAL.JSON")) {
            Optional<MarshaledResponse> response = files.marshaledResponseFor(spelling, request(spelling));
            if (Files.exists(root.resolve(spelling))) {
                assertEquals(403, response.orElseThrow().getStatusCode());
                assertEquals(denied.getFileName().toString(), seen.get().getFileName().toString());
            } else
                assertTrue(response.isEmpty());
        }
    }

    @Test
    void indexAndContentResolversReceiveTheSameActualIdentity(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("Documents"));
        Files.writeString(directory.resolve("INDEX.html"), "index");
        AtomicReference<Path> accessPath = new AtomicReference<>();
        AtomicReference<Path> contentPath = new AtomicReference<>();
        StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).indexFileNames(List.of("INDEX.html"))
                .accessResolver((path, attributes) -> { accessPath.set(path); return StaticFiles.Access.ALLOW; })
                .mimeTypeResolver(path -> { contentPath.set(path); return Optional.of("text/html"); }).build();
        String spelling = Files.exists(root.resolve("documents")) ? "documents" : "Documents";
        MarshaledResponse response = files.marshaledResponseFor(spelling, request(spelling)).orElseThrow();
        assertEquals("INDEX.html", accessPath.get().getFileName().toString());
        assertEquals("Documents", accessPath.get().getParent().getFileName().toString());
        assertEquals(accessPath.get(), contentPath.get());
        assertEquals("index", new String(response.bodyBytesOrNull(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void distinctCaseSensitiveFilesAndHardLinkNamesKeepTheirOwnPolicies(@TempDir Path root) throws Exception {
        Path hidden = Files.writeString(root.resolve(".env"), "secret");
        if (!Files.exists(root.resolve(".ENV")))
            Files.writeString(root.resolve(".ENV"), "public");
        Files.createLink(root.resolve("public.txt"), hidden);
        StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).accessResolver((path, attributes) ->
                path.getFileName().toString().equals(".env") ? StaticFiles.Access.HIDE : StaticFiles.Access.ALLOW).build();
        assertTrue(files.marshaledResponseFor(".env", request(".env")).isEmpty());
        assertEquals(200, files.marshaledResponseFor("public.txt", request("public.txt")).orElseThrow().getStatusCode());
        Optional<MarshaledResponse> caseVariant = files.marshaledResponseFor(".ENV", request(".ENV"));
        if (Files.isSameFile(hidden, root.resolve(".ENV")))
            assertTrue(caseVariant.isEmpty());
        else
            assertEquals(200, caseVariant.orElseThrow().getStatusCode());
    }

    @Test
    void linksAndResolverTriggeredLinkReplacementsFailClosed(@TempDir Path root) throws Exception {
        Path outside = Files.createTempDirectory(root.getParent(), "soklet-outside-");
        try {
            Path secret = Files.writeString(outside.resolve("secret.txt"), "outside secret");
            Files.createSymbolicLink(root.resolve("link.txt"), secret);
            StaticFiles ordinary = StaticFiles.withRoot(root).trustedImmutableRoot(true).build();
            assertTrue(ordinary.marshaledResponseFor("link.txt", request("link.txt")).isEmpty());
            assertTrue(ordinary.marshaledResponseFor("LINK.TXT", request("LINK.TXT")).isEmpty());
            Path file = Files.writeString(root.resolve("public.txt"), "public");
            StaticFiles replaced = StaticFiles.withRoot(root).trustedImmutableRoot(true).headersResolver((path, attributes) -> {
                try {
                    Files.delete(file);
                    Files.createSymbolicLink(file, secret);
                } catch (Exception exception) { throw new IllegalStateException(exception); }
                return java.util.Map.of();
            }).build();
            assertTrue(replaced.marshaledResponseFor("public.txt", request("public.txt")).isEmpty());
        } finally {
            Files.deleteIfExists(outside.resolve("secret.txt"));
            Files.delete(outside);
        }
    }

    @Test
    void replacedRootCannotExposeMetadataToApplicationResolvers(@TempDir Path directory) throws Exception {
        for (boolean followSymlinks : List.of(false, true)) {
            Path root = Files.createDirectory(directory.resolve("public-" + followSymlinks));
            Files.writeString(root.resolve("asset.txt"), "public");
            java.util.concurrent.atomic.AtomicInteger resolverCalls = new java.util.concurrent.atomic.AtomicInteger();
            StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).followSymlinks(followSymlinks)
                    .accessResolver((path, attributes) -> {
                        resolverCalls.incrementAndGet();
                        return StaticFiles.Access.ALLOW;
                    }).build();
            Files.move(root, directory.resolve("old-" + followSymlinks));
            Files.createDirectory(root);
            Files.writeString(root.resolve("asset.txt"), "secret");
            assertTrue(files.marshaledResponseFor("asset.txt", request("asset.txt")).isEmpty());
            assertEquals(0, resolverCalls.get(), "Validate the root before exposing replacement metadata");
        }
    }

    @Test
    void modificationTimeOnlyChangeDuringResolversRejectsEveryRepresentation(@TempDir Path root) throws Exception {
        Path asset = Files.writeString(root.resolve("asset.txt"), "public");
        List<Request> requests = List.of(request("asset.txt"), Request.fromPath(HttpMethod.HEAD, "/asset.txt"),
                Request.withPath(HttpMethod.GET, "/asset.txt").headers(Map.of("Range", List.of("bytes=1-3"))).build(),
                Request.withPath(HttpMethod.GET, "/asset.txt").headers(Map.of("If-None-Match", List.of("\"v1\""))).build());
        for (Request request : requests) {
            StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true)
                    .entityTagResolver((path, attributes) -> Optional.of(EntityTag.fromStrongValue("v1")))
                    .headersResolver((path, attributes) -> {
                        try {
                            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(
                                    attributes.lastModifiedTime().toInstant().plusSeconds(10)));
                        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        return Map.of();
                    }).build();
            assertTrue(files.marshaledResponseFor("asset.txt", request).isEmpty(), request.getHttpMethod().toString());
        }
    }

    @Test
    void modificationTimeOnlyChangeBeforeDeliveryRejectsFullAndRangeBodies(@TempDir Path root) throws Exception {
        Path asset = Files.writeString(root.resolve("asset.txt"), "public");
        StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).build();
        MarshaledResponse full = files.marshaledResponseFor("asset.txt", request("asset.txt")).orElseThrow();
        MarshaledResponse range = files.marshaledResponseFor("asset.txt", Request.withPath(HttpMethod.GET, "/asset.txt")
                .headers(Map.of("Range", List.of("bytes=1-3"))).build()).orElseThrow();
        assertEquals(206, range.getStatusCode());
        var attributes = Files.readAttributes(asset, java.nio.file.attribute.BasicFileAttributes.class);
        Files.setLastModifiedTime(asset, java.nio.file.attribute.FileTime.from(attributes.lastModifiedTime().toInstant().plusSeconds(10)));
        var after = Files.readAttributes(asset, java.nio.file.attribute.BasicFileAttributes.class);
        assertEquals(attributes.fileKey(), after.fileKey());
        assertEquals(attributes.size(), after.size());
        assertThrows(java.io.UncheckedIOException.class, full::bodyBytesOrEmpty);
        assertThrows(java.io.UncheckedIOException.class, range::bodyBytesOrEmpty);
    }

    private static Request request(String relativePath) {
        return Request.fromPath(HttpMethod.GET, "/" + relativePath);
    }
}
