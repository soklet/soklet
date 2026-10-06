package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StaticFileIdentityTests {
    @Test
    void accessPoliciesReceiveActualFileAndDirectorySpelling(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("Private"));
        Path hidden = Files.writeString(directory.resolve(".env"), "secret");
        Path denied = Files.writeString(directory.resolve("Internal.json"), "private");
        AtomicReference<Path> seen = new AtomicReference<>();
        StaticFiles files = StaticFiles.withRoot(root).accessResolver((path, attributes) -> {
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
        StaticFiles files = StaticFiles.withRoot(root).indexFileNames(List.of("INDEX.html"))
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
        StaticFiles files = StaticFiles.withRoot(root).accessResolver((path, attributes) ->
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
            StaticFiles ordinary = StaticFiles.withRoot(root).build();
            assertTrue(ordinary.marshaledResponseFor("link.txt", request("link.txt")).isEmpty());
            assertTrue(ordinary.marshaledResponseFor("LINK.TXT", request("LINK.TXT")).isEmpty());
            Path file = Files.writeString(root.resolve("public.txt"), "public");
            StaticFiles replaced = StaticFiles.withRoot(root).headersResolver((path, attributes) -> {
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

    private static Request request(String relativePath) {
        return Request.fromPath(HttpMethod.GET, "/" + relativePath);
    }
}
