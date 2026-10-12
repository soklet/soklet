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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static java.util.Objects.requireNonNull;

/** A pinned root for static-file delivery. The one directory handle is GC-cleaned. */
final class SecureStaticFileRoot {
	private static final Cleaner CLEANER = Cleaner.create();
	private final Path configuredRoot;
	private final State state;
	private final NativeStaticFileAccess access;
	@SuppressWarnings("unused")
	private final Cleaner.Cleanable cleanable;

	SecureStaticFileRoot(Path configuredRoot) {
		this.configuredRoot = requireNonNull(configuredRoot);
		try {
			this.access = NativeStaticFileAccess.forPath(configuredRoot);
			BasicFileAttributes expected = Files.readAttributes(configuredRoot, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			if (!expected.isDirectory() || expected.fileKey() == null)
				throw new IllegalArgumentException("Static file root needs a stable directory identity.");
			Path realRoot = configuredRoot.toRealPath();
			NativeStaticFileAccess.Handle pinned = access.openDirectory(realRoot);
			try {
				BasicFileAttributes opened = pinned.attributes();
				if (!expected.fileKey().equals(opened.fileKey())
						|| Files.isSymbolicLink(configuredRoot))
					throw new IllegalArgumentException("Static file root changed while it was opened.");
				this.state = new State(pinned);
				this.cleanable = CLEANER.register(this, state);
			} catch (Throwable failure) {
				NativeStaticFileAccess.closeOnFailure(pinned, failure);
				throw failure;
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to securely open static file root.", e);
		}
	}

	SeekableByteChannel open(Path file, Object expectedFileKey, long expectedSize,
			java.nio.file.attribute.FileTime expectedModifiedTime) throws IOException {
		Path relative = configuredRoot.relativize(requireNonNull(file));
		if (relative.getNameCount() == 0 || relative.isAbsolute())
			throw new IOException("Static file path does not name a file under the root.");
		for (Path component : relative) {
			String name = component.toString();
			if (name.isEmpty() || name.equals(".") || name.equals(".."))
				throw new IOException("Unsafe static file path component.");
		}
		try {
			return openRelative(relative, expectedFileKey, expectedSize, expectedModifiedTime);
		} finally {
			// A cleaner may run as soon as this object becomes unreachable, even
			// while a method is still using the raw descriptor held in its state.
			Reference.reachabilityFence(this);
		}
	}

	private SeekableByteChannel openRelative(Path relative, Object expectedFileKey, long expectedSize,
			java.nio.file.attribute.FileTime expectedModifiedTime) throws IOException {
		NativeStaticFileAccess.Handle directory = state.handle;
		boolean ownsDirectory = false;
		SeekableByteChannel openedChannel;
		try {
			for (int index = 0; index < relative.getNameCount() - 1; index++) {
				NativeStaticFileAccess.Handle next = access.openAt(directory, relative.getName(index));
				try {
					BasicFileAttributes attributes = next.attributes();
					if (!attributes.isDirectory() || attributes.fileKey() == null)
						throw new IOException("Static file traversal requires an identified directory.");
					if (ownsDirectory) directory.close();
				} catch (Throwable failure) {
					NativeStaticFileAccess.closeOnFailure(next, failure);
					throw failure;
				}
				directory = next;
				ownsDirectory = true;
			}
			try (NativeStaticFileAccess.Handle file = access.openAt(directory, relative.getName(relative.getNameCount() - 1))) {
				// Check the opened object, not the name: an attacker can swap a
				// different same-sized file in for open and restore the old name.
				checkIdentity(file.attributes(), expectedFileKey, expectedSize, expectedModifiedTime);
				openedChannel = file.channel();
			}
		} catch (IOException | RuntimeException | Error failure) {
			if (ownsDirectory) NativeStaticFileAccess.closeOnFailure(directory, failure);
			throw failure;
		}
		try {
			if (ownsDirectory) directory.close();
			return openedChannel;
		} catch (IOException | RuntimeException | Error failure) {
			try { openedChannel.close(); }
			catch (Throwable closeFailure) { failure.addSuppressed(closeFailure); }
			throw failure;
		}
	}

	static void checkIdentity(BasicFileAttributes current, Object expectedFileKey, long expectedSize,
			java.nio.file.attribute.FileTime expectedModifiedTime) throws IOException {
		if (!current.isRegularFile() || expectedFileKey == null || !expectedFileKey.equals(current.fileKey())
				|| current.size() != expectedSize
				|| !expectedModifiedTime.equals(current.lastModifiedTime()))
			throw new IOException("Static file changed after authorization.");
	}

	private static final class State implements Runnable {
		private final NativeStaticFileAccess.Handle handle;

		private State(NativeStaticFileAccess.Handle handle) {
			this.handle = handle;
		}

		@Override
		public void run() {
			try {
				handle.close();
			} catch (IOException ignored) {
				// A cleaner cannot report a checked close failure.
			}
		}
	}
}
