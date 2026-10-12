/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
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

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A deliberately small, read-only bridge to the JDK's Unix descriptor operations.
 * Public NIO cannot fstat an opened channel, and macOS JDK 17/21 do not expose
 * SecureDirectoryStream. Path-before/open/path-after checks cannot prevent ABA
 * replacement. Never substitute those checks when this bridge is unavailable.
 *
 * No JDK capability flags are changed. In particular, do not use the old macOS
 * fstatat/fdopendir entry points: their x86-64 ABI was fixed in JDK-8343020.
 * openat passes no ABI-dependent structures; fstat uses the JDK's own binding.
 */
final class NativeStaticFileAccess {

	private final Class<?> pathClass;
	private final MethodHandle open;
	private final MethodHandle openAt;
	private final MethodHandle close;
	private final MethodHandle pathBytes;
	private final MethodHandle attributes;
	private final MethodHandle descriptor;
	private final MethodHandle channel;
	private final int flags;

	static NativeStaticFileAccess forPath(Path path) throws IOException {
		checkSecurityManager();
		if (!isDefaultFileSystem(path))
			throw new IOException("Filesystem does not support secure static-file descriptors.");
		if (Holder.ACCESS == null || path.getClass() != Holder.ACCESS.pathClass)
			throw new IOException("JDK does not support secure static-file descriptors.", Holder.FAILURE);
		return Holder.ACCESS;
	}

	private NativeStaticFileAccess() throws ReflectiveOperationException {
		Class<?> provider = FileSystems.getDefault().provider().getClass();
		String architecture = System.getProperty("os.arch");
		boolean supportedArchitecture = "aarch64".equals(architecture)
				|| "amd64".equals(architecture) || "x86_64".equals(architecture);
		boolean mac = provider == optionalJdkClass("sun.nio.fs.MacOSXFileSystemProvider");
		boolean linux = provider == optionalJdkClass("sun.nio.fs.LinuxFileSystemProvider");
		if ((!mac && !linux) || !supportedArchitecture)
			throw new ReflectiveOperationException("Unsupported native filesystem provider or architecture.");

		MethodHandles.Lookup lookup = trustedLookup();
		this.pathClass = jdkClass("sun.nio.fs.UnixPath");
		Class<?> dispatcher = jdkClass("sun.nio.fs.UnixNativeDispatcher");
		Class<?> fileAttributes = jdkClass("sun.nio.fs.UnixFileAttributes");
		Class<?> constants = jdkClass("sun.nio.fs.UnixConstants");
		this.open = lookup.findStatic(dispatcher, "open",
				MethodType.methodType(int.class, pathClass, int.class, int.class));
		this.openAt = lookup.findStatic(dispatcher, "openat",
				MethodType.methodType(int.class, int.class, byte[].class, int.class, int.class));
		this.close = lookup.findStatic(dispatcher, "close", MethodType.methodType(void.class, int.class));
		this.pathBytes = lookup.findVirtual(pathClass, "asByteArray", MethodType.methodType(byte[].class));
		this.attributes = lookup.findStatic(fileAttributes, "get", MethodType.methodType(fileAttributes, int.class));
		this.descriptor = lookup.findConstructor(FileDescriptor.class, MethodType.methodType(void.class, int.class));
		this.channel = channelFactory(lookup);
		int readOnly = constant(lookup, constants, "O_RDONLY");
		int noFollow = constant(lookup, constants, "O_NOFOLLOW");
		if (readOnly != 0 || noFollow == 0)
			throw new ReflectiveOperationException("Unsupported native filesystem flags.");
		// Darwin fcntl.h and Linux asm-generic/fcntl.h (x86-64/AArch64).
		// NONBLOCK prevents a raced-in FIFO from hanging open; CLOEXEC prevents
		// pinned directory/file descriptors escaping into an application child.
		this.flags = readOnly | noFollow | (mac ? 0x4 | 0x1000000 : 0x800 | 0x80000);
	}

	Handle openDirectory(Path path) throws IOException {
		checkPath(path);
		if (!path.isAbsolute() || path.getRoot() == null)
			throw new IOException("Static file root must be absolute.");
		Handle current = new Handle(invokeOpen(path.getRoot()));
		try {
			checkDirectory(current);
			for (Path component : path) {
				Handle next = openAt(current, component);
				try {
					checkDirectory(next);
					current.close();
				} catch (Throwable failure) {
					closeOnFailure(next, failure);
					throw failure;
				}
				current = next;
			}
			return current;
		} catch (Throwable failure) {
			closeOnFailure(current, failure);
			throw failure;
		}
	}

	Handle openAt(Handle directory, Path name) throws IOException {
		checkPath(name);
		String spelling = name.toString();
		if (name.isAbsolute() || name.getNameCount() != 1 || spelling.isEmpty()
				|| spelling.equals(".") || spelling.equals(".."))
			throw new IOException("Unsafe static file path component.");
		try {
			return new Handle((int) openAt.invoke(directory.value(), (byte[]) pathBytes.invoke(name), flags, 0));
		} catch (Throwable failure) {
			throw ioFailure(failure);
		}
	}

	private int invokeOpen(Path root) throws IOException {
		try {
			return (int) open.invoke(root, flags, 0);
		} catch (Throwable failure) {
			throw ioFailure(failure);
		}
	}

	private void checkPath(Path path) throws IOException {
		checkSecurityManager();
		if (!isDefaultFileSystem(path) || path.getClass() != pathClass)
			throw new IOException("Filesystem does not support secure static-file descriptors.");
	}

	@SuppressWarnings("ReferenceEquality") // Require this exact filesystem instance, not a provider's equals implementation.
	private static boolean isDefaultFileSystem(Path path) {
		return path.getFileSystem() == FileSystems.getDefault();
	}

	private static void checkDirectory(Handle handle) throws IOException {
		BasicFileAttributes attributes = handle.attributes();
		if (!attributes.isDirectory() || attributes.fileKey() == null)
			throw new IOException("Static file traversal requires an identified directory.");
	}

	static void closeOnFailure(Handle handle, Throwable failure) {
		try {
			handle.close();
		} catch (Throwable closeFailure) {
			failure.addSuppressed(closeFailure);
		}
	}

	final class Handle implements AutoCloseable {
		private int fd;

		private Handle(int fd) { this.fd = fd; }

		private int value() throws IOException {
			if (fd < 0) throw new IOException("Static file descriptor is closed.");
			return fd;
		}

		BasicFileAttributes attributes() throws IOException {
			checkSecurityManager();
			try {
				return (BasicFileAttributes) attributes.invoke(value());
			} catch (Throwable failure) {
				throw ioFailure(failure);
			}
		}

		FileChannel channel() throws IOException {
			checkSecurityManager();
			try {
				FileDescriptor owned = (FileDescriptor) descriptor.invoke(value());
				FileChannel result = (FileChannel) channel.invoke(owned);
				fd = -1; // The JDK channel (including its cleaner) now owns the descriptor.
				return result;
			} catch (Throwable failure) {
				throw ioFailure(failure);
			}
		}

		@Override
		public void close() throws IOException {
			if (fd >= 0) {
				int owned = fd;
				fd = -1; // Never retry close: the number could already have been reused.
				try {
					close.invoke(owned);
				} catch (Throwable failure) {
					throw ioFailure(failure);
				}
			}
		}
	}

	@SuppressWarnings("removal")
	private static void checkSecurityManager() throws IOException {
		// Native descriptor calls bypass provider checkRead hooks. Do not bypass
		// a Java 17/21 application's installed SecurityManager policy.
		if (System.getSecurityManager() != null)
			throw new IOException("Secure static-file descriptors require no SecurityManager.");
	}

	private static IOException ioFailure(Throwable failure) {
		if (failure instanceof Error error) throw error;
		if (failure instanceof IOException io) return io;
		return new IOException("Unable to access secure static-file descriptor.", failure);
	}

	private static Class<?> jdkClass(String name) throws ClassNotFoundException {
		return Class.forName(name, true, null);
	}

	private static @Nullable Class<?> optionalJdkClass(String name) {
		try {
			return jdkClass(name);
		} catch (ClassNotFoundException unavailable) {
			return null;
		}
	}

	private static int constant(MethodHandles.Lookup lookup, Class<?> type, String name)
			throws ReflectiveOperationException {
		try {
			return (int) lookup.findStaticGetter(type, name, int.class).invoke();
		} catch (ReflectiveOperationException failure) {
			throw failure;
		} catch (Throwable failure) {
			throw new ReflectiveOperationException(failure);
		}
	}

	private static MethodHandle channelFactory(MethodHandles.Lookup lookup) throws ReflectiveOperationException {
		Class<?> type = jdkClass("sun.nio.ch.FileChannelImpl");
		// Bind only known read-only factory signatures. New JDK layouts fail closed.
		try {
			MethodHandle factory = lookup.findStatic(type, "open", MethodType.methodType(FileChannel.class,
					FileDescriptor.class, String.class, boolean.class, boolean.class, boolean.class, boolean.class, Closeable.class));
			return MethodHandles.insertArguments(factory, 1, null, true, false, false, false, null);
		} catch (NoSuchMethodException ignored) {
			try {
				MethodHandle factory = lookup.findStatic(type, "open", MethodType.methodType(FileChannel.class,
						FileDescriptor.class, String.class, boolean.class, boolean.class, boolean.class, Closeable.class));
				return MethodHandles.insertArguments(factory, 1, null, true, false, false, null);
			} catch (NoSuchMethodException olderJdk) {
				MethodHandle factory = lookup.findStatic(type, "open", MethodType.methodType(FileChannel.class,
						FileDescriptor.class, String.class, boolean.class, boolean.class, boolean.class, Object.class));
				return MethodHandles.insertArguments(factory, 1, null, true, false, false, null);
			}
		}
	}

	private static MethodHandles.Lookup trustedLookup() throws ReflectiveOperationException {
		// jdk.unsupported is optional in custom runtimes. Missing/denied access is
		// a construction failure, never a reason to revert to pathname delivery.
		Class<?> unsafeClass = jdkClass("sun.misc.Unsafe");
		Field singleton = unsafeClass.getDeclaredField("theUnsafe");
		singleton.setAccessible(true);
		Object unsafe = singleton.get(null);
		Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
		Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
		long offset = (long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field);
		return (MethodHandles.Lookup) unsafeClass.getMethod("getObject", Object.class, long.class).invoke(unsafe, base, offset);
	}

	private static final class Holder {
		private static final @Nullable NativeStaticFileAccess ACCESS;
		private static final @Nullable Throwable FAILURE;
		static {
			NativeStaticFileAccess access = null;
			Throwable failure = null;
			try {
				access = new NativeStaticFileAccess();
			} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
				failure = unavailable;
			}
			ACCESS = access;
			FAILURE = failure;
		}
	}
}
