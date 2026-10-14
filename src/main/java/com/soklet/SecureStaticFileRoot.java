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

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.soklet.internal.ObjectIdentity.sameInstance;
import static java.util.Objects.requireNonNull;

/**
 * Public-NIO delivery for an explicitly trusted, immutable static-file tree.
 * No handles survive construction or an open operation, other than the returned
 * file channel. These checks detect observed replacement; public NIO cannot
 * inspect an opened regular file's identity and does not defeat ABA replacement
 * by a writer that violates the immutable-tree contract.
 */
final class SecureStaticFileRoot {
	private final Path configuredRoot;
	private final Path declaredRoot;
	private final Path resolvedRoot;
	private final Object rootFileKey;
	private final boolean secureDirectoryStreams;

	SecureStaticFileRoot(Path configuredRoot) {
		this(configuredRoot, configuredRoot);
	}

	SecureStaticFileRoot(Path configuredRoot, Path declaredRoot) {
		this.declaredRoot = requireNonNull(declaredRoot).toAbsolutePath().normalize();
		this.configuredRoot = requireNonNull(configuredRoot).toAbsolutePath().normalize();
		try {
			BasicFileAttributes expected = attributes(this.configuredRoot);
			checkDirectory(expected);
			this.rootFileKey = expected.fileKey();
			this.resolvedRoot = this.configuredRoot.toRealPath();
			checkRoot();
			// Probe the provider through its public API. Opening the resolved root
			// directly does not require listing each of its search-only ancestors.
			boolean secureDirectoryStreams;
			try (DirectoryStream<Path> directory = Files.newDirectoryStream(this.resolvedRoot)) {
				secureDirectoryStreams = directory instanceof SecureDirectoryStream<?>;
				if (directory instanceof SecureDirectoryStream<Path> secureDirectory)
					checkDirectoryIdentity(directoryAttributes(secureDirectory), this.rootFileKey);
			} catch (AccessDeniedException unlistableDirectory) {
				// A searchable directory can contain readable files without being
				// listable. The immutable-tree contract also permits path-based opens.
				secureDirectoryStreams = false;
			}
			this.secureDirectoryStreams = secureDirectoryStreams;
			checkRoot();
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to open trusted static file root.", e);
		}
	}

	SeekableByteChannel open(Path file, Object expectedFileKey, long expectedSize,
			FileTime expectedModifiedTime) throws IOException {
		requireNonNull(file);
		if (!sameInstance(file.getFileSystem(), this.configuredRoot.getFileSystem()) || !file.isAbsolute()
				|| !file.equals(file.normalize()) || !file.startsWith(this.configuredRoot))
			throw new IOException("Static file path does not name a file under the root.");
		Path relative = this.configuredRoot.relativize(file);
		for (Path component : relative) {
			String name = component.toString();
			if (name.isEmpty() || name.equals(".") || name.equals(".."))
				throw new IOException("Unsafe static file path component.");
		}

		checkRoot();
		List<Object> directoryKeys = directoryKeys(relative);
		SeekableByteChannel channel = null;
		try {
			if (this.secureDirectoryStreams) {
				try {
					channel = openSecure(relative, directoryKeys, expectedFileKey, expectedSize, expectedModifiedTime);
				} catch (AccessDeniedException unlistableDirectory) {
					// Directory streams require listing permission; opening a known
					// file only needs search permission on its ancestor directories.
					// Public file opens still enforce the file's own read permissions.
					channel = openTrustedPath(relative, expectedFileKey, expectedSize, expectedModifiedTime);
				}
			} else {
				channel = openTrustedPath(relative, expectedFileKey, expectedSize, expectedModifiedTime);
			}
			if (!directoryKeys.equals(directoryKeys(relative)))
				throw new IOException("Static file directory changed after authorization.");
			checkRoot();
			return channel;
		} catch (IOException | RuntimeException | Error failure) {
			closeOnFailure(channel, failure);
			throw failure;
		}
	}

	private SeekableByteChannel openSecure(Path relative, List<Object> directoryKeys,
			Object expectedFileKey, long expectedSize, FileTime expectedModifiedTime) throws IOException {
		SeekableByteChannel channel = null;
		try (DirectoryStream<Path> root = Files.newDirectoryStream(this.resolvedRoot)) {
			if (!(root instanceof SecureDirectoryStream<Path> secureRoot))
				throw new IOException("Filesystem no longer provides secure directory traversal.");
			SecureDirectoryStream<Path> directory = secureRoot;
			boolean ownsDirectory = false;
			try {
				checkDirectoryIdentity(directoryAttributes(directory), this.rootFileKey);
				for (int index = 0; index < relative.getNameCount() - 1; index++) {
					SecureDirectoryStream<Path> next = directory.newDirectoryStream(relative.getName(index),
							LinkOption.NOFOLLOW_LINKS);
					try {
						checkDirectoryIdentity(directoryAttributes(next), directoryKeys.get(index));
						if (ownsDirectory) {
							SecureDirectoryStream<Path> previous = directory;
							directory = null;
							previous.close();
						}
					} catch (IOException | RuntimeException | Error failure) {
						closeOnFailure(next, failure);
						throw failure;
					}
					directory = next;
					ownsDirectory = true;
				}
				Path name = relative.getName(relative.getNameCount() - 1);
				BasicFileAttributeView view = directory.getFileAttributeView(name, BasicFileAttributeView.class,
						LinkOption.NOFOLLOW_LINKS);
				checkIdentity(view.readAttributes(), expectedFileKey, expectedSize, expectedModifiedTime);
				channel = directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				// The view is relative to the directory but remains name-based, not
				// bound to channel. Immutability is required even with this provider.
				checkIdentity(view.readAttributes(), expectedFileKey, expectedSize, expectedModifiedTime);
				checkSize(channel, expectedSize);
				if (ownsDirectory) {
					SecureDirectoryStream<Path> previous = directory;
					directory = null;
					previous.close();
				}
				return channel;
			} catch (IOException | RuntimeException | Error failure) {
				if (ownsDirectory)
					closeOnFailure(directory, failure);
				throw failure;
			}
		} catch (IOException | RuntimeException | Error failure) {
			// Includes failure while closing the root after acquiring the channel.
			closeOnFailure(channel, failure);
			throw failure;
		}
	}

	private SeekableByteChannel openTrustedPath(Path relative, Object expectedFileKey,
			long expectedSize, FileTime expectedModifiedTime) throws IOException {
		Path file = this.resolvedRoot.resolve(relative);
		checkIdentity(attributes(file), expectedFileKey, expectedSize, expectedModifiedTime);
		// Providers without SecureDirectoryStream (including macOS JDK 21) use
		// path checks under the explicit immutable-tree contract. No directory
		// listing is needed here, so searchable nested directories remain usable.
		SeekableByteChannel channel = Files.newByteChannel(file,
				Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
		try {
			checkIdentity(attributes(file), expectedFileKey, expectedSize, expectedModifiedTime);
			checkSize(channel, expectedSize);
			return channel;
		} catch (IOException | RuntimeException | Error failure) {
			closeOnFailure(channel, failure);
			throw failure;
		}
	}

	private void checkRoot() throws IOException {
		checkDirectoryIdentity(attributes(this.configuredRoot), this.rootFileKey);
		checkDirectoryIdentity(attributes(this.resolvedRoot), this.rootFileKey);
		if (!this.configuredRoot.toRealPath().equals(this.resolvedRoot)
				|| !this.declaredRoot.toRealPath().equals(this.resolvedRoot))
			throw new IOException("Static file root changed; rebuild the static-file helper.");
	}

	private List<Object> directoryKeys(Path relative) throws IOException {
		List<Object> keys = new ArrayList<>(relative.getNameCount() - 1);
		Path current = this.resolvedRoot;
		for (int index = 0; index < relative.getNameCount() - 1; index++) {
			current = current.resolve(relative.getName(index));
			BasicFileAttributes attributes = attributes(current);
			checkDirectory(attributes);
			keys.add(attributes.fileKey());
		}
		return keys;
	}

	private static BasicFileAttributes attributes(Path path) throws IOException {
		return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
	}

	private static BasicFileAttributes directoryAttributes(SecureDirectoryStream<Path> directory) throws IOException {
		return directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
	}

	private static void checkDirectory(BasicFileAttributes attributes) throws IOException {
		if (!attributes.isDirectory() || attributes.fileKey() == null)
			throw new IOException("Static file traversal requires a stable directory identity.");
	}

	private static void checkDirectoryIdentity(BasicFileAttributes attributes, Object expectedFileKey) throws IOException {
		checkDirectory(attributes);
		if (!expectedFileKey.equals(attributes.fileKey()))
			throw new IOException("Static file directory changed after authorization.");
	}

	static void checkIdentity(BasicFileAttributes current, Object expectedFileKey, long expectedSize,
			FileTime expectedModifiedTime) throws IOException {
		if (!current.isRegularFile() || expectedFileKey == null || !expectedFileKey.equals(current.fileKey())
				|| current.size() != expectedSize
				|| !expectedModifiedTime.equals(current.lastModifiedTime()))
			throw new IOException("Static file changed after authorization.");
	}

	private static void checkSize(SeekableByteChannel channel, long expectedSize) throws IOException {
		if (channel.size() != expectedSize)
			throw new IOException("Static file size changed after authorization.");
	}

	private static void closeOnFailure(@Nullable AutoCloseable resource, Throwable failure) {
		if (resource == null)
			return;
		try {
			resource.close();
		} catch (Throwable closeFailure) {
			if (!sameInstance(closeFailure, failure))
				failure.addSuppressed(closeFailure);
		}
	}
}
