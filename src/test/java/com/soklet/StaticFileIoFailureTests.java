package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StaticFileIoFailureTests {
    @Test
    void acquisitionFailuresAreNotReportedAsMissingFiles(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("asset.txt"), "public");
        for (boolean follow : List.of(false, true)) {
            Provider provider = new Provider(directory);
            StaticFiles files = StaticFiles.withRoot(provider.wrap(directory)).trustedImmutableRoot(true)
                    .followSymlinks(follow).build();
            for (String operation : List.of("realPath", "attributes", "open")) {
                IOException expected = new FileSystemException(directory.toString(), null, "Too many open files");
                provider.failure = expected;
                provider.operation = operation;
                UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                        () -> files.marshaledResponseFor("asset.txt", request()));
                assertSame(expected, failure.getCause(), operation);
                provider.failure = null;
                assertEquals("public", new String(files.marshaledResponseFor("asset.txt", request())
                        .orElseThrow().bodyBytesOrEmpty(), java.nio.charset.StandardCharsets.UTF_8));
            }
            provider.operation = "open";
            provider.failure = new AccessDeniedException(directory.resolve("asset.txt").toString());
            assertTrue(files.marshaledResponseFor("asset.txt", request()).isEmpty());
            provider.failure = null;
            assertTrue(files.marshaledResponseFor("missing.txt", request()).isEmpty());
            assertTrue(files.marshaledResponseFor("asset.txt/child", request()).isEmpty());
        }
    }

    @Test
    void ioFailuresAfterResolversAndDuringHashingPropagate(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("asset.txt"), "public");
        for (boolean hash : List.of(false, true)) {
            Provider provider = new Provider(directory);
            IOException expected = new IOException("Transient I/O failure");
            StaticFiles.Builder builder = StaticFiles.withRoot(provider.wrap(directory)).trustedImmutableRoot(true);
            if (hash) {
                builder.entityTagResolver(StaticFiles.EntityTagResolver.fromContentHash())
                        .accessResolver((path, attributes) -> {
                            provider.operation = "open";
                            provider.failure = expected;
                            return StaticFiles.Access.ALLOW;
                        });
            } else {
                builder.headersResolver((path, attributes) -> {
                    provider.operation = "open";
                    provider.failure = expected;
                    return Map.of();
                });
            }
            StaticFiles files = builder.build();
            UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                    () -> files.marshaledResponseFor("asset.txt", request()));
            assertSame(expected, failure.getCause());
        }
    }

    @Test
    void nullFileKeyFallbackKeepsContainmentAndReplacementChecks(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("public"));
        Path asset = Files.writeString(root.resolve("asset.txt"), "public");
        Path outside = Files.writeString(directory.resolve("secret.txt"), "secret");
        Provider provider = new Provider(directory);
        provider.nullFileKeys = true;
        StaticFiles files = StaticFiles.withRoot(provider.wrap(root)).trustedImmutableRoot(true).build();
        MarshaledResponse response = files.marshaledResponseFor("asset.txt", request()).orElseThrow();
        assertEquals("public", new String(response.bodyBytesOrEmpty(), java.nio.charset.StandardCharsets.UTF_8));
        Files.delete(asset);
        Files.createSymbolicLink(asset, outside);
        assertTrue(files.marshaledResponseFor("asset.txt", request()).isEmpty());
        assertThrows(UncheckedIOException.class, response::bodyBytesOrEmpty);
        Files.delete(asset);
        Files.move(root, directory.resolve("old-public"));
        Files.createDirectory(root);
        Files.writeString(root.resolve("asset.txt"), "secret");
        Files.setLastModifiedTime(root, FileTime.fromMillis(1));
        assertTrue(files.marshaledResponseFor("asset.txt", request()).isEmpty());
    }

    private static Request request() { return Request.fromPath(HttpMethod.GET, "/asset.txt"); }

    @Test
    void ordinarySymlinksDoNotHideGenuineIoFailures(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("asset.txt"), "public");
        Files.createSymbolicLink(root.resolve("link"), Path.of("asset.txt"));
        Provider provider = new Provider(root);
        StaticFiles files = StaticFiles.withRoot(provider.wrap(root)).trustedImmutableRoot(true).followSymlinks(true).build();
        IOException expected = new FileSystemException(root.toString(), null, "I/O failure");
        provider.failure = expected;
        provider.operation = "realPath";
        assertSame(expected, assertThrows(UncheckedIOException.class,
                () -> files.marshaledResponseFor("link", request())).getCause());
    }

    @Test
    void unreadableIndexFallsThroughButOtherAccessFailuresPropagate(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("index.html"), "unreadable");
        Files.writeString(root.resolve("index.htm"), "fallback");
        for (boolean follow : List.of(false, true)) {
            Provider provider = new Provider(root);
            provider.deniedReadPath = root.resolve("index.html").toRealPath();
            StaticFiles files = StaticFiles.withRoot(provider.wrap(root)).trustedImmutableRoot(true)
                    .followSymlinks(follow).indexFileNames(List.of("index.html", "index.htm")).build();
            assertEquals("fallback", new String(files.marshaledResponseFor("", request()).orElseThrow().bodyBytesOrEmpty(),
                    java.nio.charset.StandardCharsets.UTF_8));
            provider.operation = "checkAccess";
            provider.failure = new FileSystemException(root.toString(), null, "I/O failure");
            assertSame(provider.failure, assertThrows(UncheckedIOException.class,
                    () -> files.marshaledResponseFor("", request())).getCause());
        }
    }

    @Test
    void unsupportedNoFollowIsAnAcquisitionFailureInsteadOfMissing(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("asset.txt"), "public");
        for (RuntimeException unsupported : List.of(new UnsupportedOperationException(), new IllegalArgumentException())) {
            Provider provider = new Provider(root);
            StaticFiles files = StaticFiles.withRoot(provider.wrap(root)).trustedImmutableRoot(true).build();
            provider.noFollowFailure = unsupported;
            UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                    () -> files.marshaledResponseFor("asset.txt", request()));
            assertSame(unsupported, failure.getCause().getCause());
            assertTrue(failure.getCause().getMessage().contains("no-follow"));
        }
    }

    // Public-NIO provider fixture: Windows-like null keys/no SecureDirectoryStream,
    // plus deterministic I/O failures without exhausting the test process's descriptors.
    static final class Provider extends FileSystemProvider {
        private final FileSystem delegate;
        private final WrappedFileSystem fileSystem;
        private IOException failure;
        private String operation;
        private Path deniedReadPath;
        private RuntimeException noFollowFailure;
        boolean nullFileKeys;

        Provider(Path directory) {
            delegate = directory.getFileSystem();
            fileSystem = new WrappedFileSystem();
        }
        Path wrap(Path path) { return new WrappedPath(path); }
        Path unwrap(Path path) { return ((WrappedPath) path).delegate; }
        void fail(String operation) throws IOException {
            if (failure != null && operation.equals(this.operation)) throw failure;
        }
        @Override public String getScheme() { return "static-file-test"; }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
        @Override public FileSystem getFileSystem(URI uri) { return fileSystem; }
        @Override public Path getPath(URI uri) { throw new UnsupportedOperationException(); }
        @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            if (noFollowFailure != null && options.contains(LinkOption.NOFOLLOW_LINKS)) throw noFollowFailure;
            fail("open"); return Files.newByteChannel(unwrap(path), options, attrs);
        }
        @Override public DirectoryStream<Path> newDirectoryStream(Path path, DirectoryStream.Filter<? super Path> filter) throws IOException {
            DirectoryStream<Path> stream = Files.newDirectoryStream(unwrap(path));
            return new DirectoryStream<>() {
                @Override public Iterator<Path> iterator() {
                    return java.util.stream.StreamSupport.stream(stream.spliterator(), false).map(Provider.this::wrap)
                            .filter(value -> {
                                try { return filter.accept(value); }
                                catch (IOException failure) { throw new DirectoryIteratorException(failure); }
                            }).iterator();
                }
                @Override public void close() throws IOException { stream.close(); }
            };
        }
        @Override public void createDirectory(Path path, FileAttribute<?>... attrs) throws IOException { Files.createDirectory(unwrap(path), attrs); }
        @Override public void delete(Path path) throws IOException { Files.delete(unwrap(path)); }
        @Override public void copy(Path source, Path target, CopyOption... options) throws IOException { Files.copy(unwrap(source), unwrap(target), options); }
        @Override public void move(Path source, Path target, CopyOption... options) throws IOException { Files.move(unwrap(source), unwrap(target), options); }
        @Override public boolean isSameFile(Path first, Path second) throws IOException { return Files.isSameFile(unwrap(first), unwrap(second)); }
        @Override public boolean isHidden(Path path) throws IOException { return Files.isHidden(unwrap(path)); }
        @Override public FileStore getFileStore(Path path) throws IOException { return Files.getFileStore(unwrap(path)); }
        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException {
            fail("checkAccess");
            if (deniedReadPath != null && unwrap(path).toRealPath().equals(deniedReadPath)
                    && Arrays.asList(modes).contains(AccessMode.READ))
                throw new AccessDeniedException(path.toString());
            delegate.provider().checkAccess(unwrap(path), modes);
        }
        @Override public Path readSymbolicLink(Path path) throws IOException { return wrap(Files.readSymbolicLink(unwrap(path))); }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) { return Files.getFileAttributeView(unwrap(path), type, options); }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
            fail("attributes");
            A attributes = Files.readAttributes(unwrap(path), type, options);
            if (!nullFileKeys || type != BasicFileAttributes.class) return attributes;
            return type.cast(new BasicFileAttributes() {
                @Override public FileTime lastModifiedTime() { return attributes.lastModifiedTime(); }
                @Override public FileTime lastAccessTime() { return attributes.lastAccessTime(); }
                @Override public FileTime creationTime() { return attributes.creationTime(); }
                @Override public boolean isRegularFile() { return attributes.isRegularFile(); }
                @Override public boolean isDirectory() { return attributes.isDirectory(); }
                @Override public boolean isSymbolicLink() { return attributes.isSymbolicLink(); }
                @Override public boolean isOther() { return attributes.isOther(); }
                @Override public long size() { return attributes.size(); }
                @Override public Object fileKey() { return null; }
            });
        }
        @Override public Map<String, Object> readAttributes(Path path, String attrs, LinkOption... options) throws IOException { fail("attributes"); return Files.readAttributes(unwrap(path), attrs, options); }
        @Override public void setAttribute(Path path, String attr, Object value, LinkOption... options) throws IOException { Files.setAttribute(unwrap(path), attr, value, options); }

        private final class WrappedFileSystem extends FileSystem {
            @Override public FileSystemProvider provider() { return Provider.this; }
            @Override public void close() { }
            @Override public boolean isOpen() { return true; }
            @Override public boolean isReadOnly() { return false; }
            @Override public String getSeparator() { return delegate.getSeparator(); }
            @Override public Iterable<Path> getRootDirectories() { return List.of(wrap(delegate.getPath("/"))); }
            @Override public Iterable<FileStore> getFileStores() { return delegate.getFileStores(); }
            @Override public Set<String> supportedFileAttributeViews() { return delegate.supportedFileAttributeViews(); }
            @Override public Path getPath(String first, String... more) { return wrap(delegate.getPath(first, more)); }
            @Override public PathMatcher getPathMatcher(String pattern) { return path -> delegate.getPathMatcher(pattern).matches(unwrap(path)); }
            @Override public UserPrincipalLookupService getUserPrincipalLookupService() { return delegate.getUserPrincipalLookupService(); }
            @Override public WatchService newWatchService() throws IOException { return delegate.newWatchService(); }
        }
        private final class WrappedPath implements Path {
            private final Path delegate;
            WrappedPath(Path delegate) { this.delegate = delegate; }
            private Path wrapNullable(Path path) { return path == null ? null : wrap(path); }
            @Override public FileSystem getFileSystem() { return fileSystem; }
            @Override public boolean isAbsolute() { return delegate.isAbsolute(); }
            @Override public Path getRoot() { return wrapNullable(delegate.getRoot()); }
            @Override public Path getFileName() { return wrapNullable(delegate.getFileName()); }
            @Override public Path getParent() { return wrapNullable(delegate.getParent()); }
            @Override public int getNameCount() { return delegate.getNameCount(); }
            @Override public Path getName(int index) { return wrap(delegate.getName(index)); }
            @Override public Path subpath(int begin, int end) { return wrap(delegate.subpath(begin, end)); }
            @Override public boolean startsWith(Path other) { return delegate.startsWith(unwrap(other)); }
            @Override public boolean endsWith(Path other) { return delegate.endsWith(unwrap(other)); }
            @Override public Path normalize() { return wrap(delegate.normalize()); }
            @Override public Path resolve(Path other) { return wrap(delegate.resolve(unwrap(other))); }
            @Override public Path relativize(Path other) { return wrap(delegate.relativize(unwrap(other))); }
            @Override public URI toUri() { return delegate.toUri(); }
            @Override public Path toAbsolutePath() { return wrap(delegate.toAbsolutePath()); }
            @Override public Path toRealPath(LinkOption... options) throws IOException { fail("realPath"); return wrap(delegate.toRealPath(options)); }
            @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException { return delegate.register(watcher, events, modifiers); }
            @Override public Iterator<Path> iterator() { return java.util.stream.StreamSupport.stream(delegate.spliterator(), false).map(Provider.this::wrap).iterator(); }
            @Override public int compareTo(Path other) { return delegate.compareTo(unwrap(other)); }
            @Override public boolean equals(Object other) { return other instanceof Provider.WrappedPath path && path.getFileSystem() == fileSystem && delegate.equals(path.delegate); }
            @Override public int hashCode() { return delegate.hashCode(); }
            @Override public String toString() { return delegate.toString(); }
        }
    }
}
