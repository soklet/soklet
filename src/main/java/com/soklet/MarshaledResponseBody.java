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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static java.util.Objects.requireNonNull;

/**
 * A known-length, finalized HTTP response body.
 * <p>
 * This type describes the body to write; it is not itself responsible for writing to a transport. Bodies may be backed
 * by bytes, files, file channels, or byte buffers.
 * <p>
 * These descriptors are thread-safe value holders, but they do not take ownership of or make immutable their backing
 * content. From construction until every response using a descriptor has completed, callers must not concurrently
 * mutate a backing byte array or byte-buffer content, modify a path-backed file region, or close or modify a borrowed
 * file channel. A channel configured with {@link FileChannel#getCloseOnComplete()} is closed by Soklet after use.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface MarshaledResponseBody permits MarshaledResponseBody.Bytes, MarshaledResponseBody.File, MarshaledResponseBody.FileChannel, MarshaledResponseBody.ByteBuffer, MarshaledResponseBody.SecureFile {
	/**
	 * The number of bytes this body will write.
	 *
	 * @return the body length
	 */
	@NonNull
	Long getLength();

	/**
	 * A finalized response body backed by a byte array.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	final class Bytes implements MarshaledResponseBody {
		@NonNull
		private final byte[] bytes;

		public Bytes(byte @NonNull [] bytes) {
			this.bytes = requireNonNull(bytes);
		}

		/**
		 * The byte array backing this body.
		 * <p>
		 * For compatibility with prior {@link MarshaledResponse} behavior, this array is not defensively copied. Callers
		 * must not mutate it from construction until every response using this body has completed.
		 *
		 * @return the bytes to write
		 */
		public byte @NonNull [] getBytes() {
			return this.bytes;
		}

		@Override
		@NonNull
		public Long getLength() {
			return Long.valueOf(getBytes().length);
		}
	}

	/**
	 * A finalized response body backed by a file path.
	 * <p>
	 * The referenced file region remains caller-owned and must not be modified while a response is using it.
	 * <p>
	 * The standard HTTP server allows native transfer for the JDK's standard file channels when the platform supports it.
	 * Channels from other filesystem providers, including ZIP and JAR filesystems, use a socket adapter. This can lose
	 * zero-copy transfer, reduce throughput, and use more CPU.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	final class File implements MarshaledResponseBody {
		@NonNull
		private final Path path;
		@NonNull
		private final Long offset;
		@NonNull
		private final Long count;

		public File(@NonNull Path path, @NonNull Long offset, @NonNull Long count) {
			this.path = requireNonNull(path);
			this.offset = requireNonNull(offset);
			this.count = requireNonNull(count);
			validateOffsetAndCount(offset, count);
		}

		/**
		 * The file path backing this body.
		 *
		 * @return the file path
		 */
		@NonNull
		public Path getPath() {
			return this.path;
		}

		/**
		 * The zero-based file offset from which response bytes should be written.
		 *
		 * @return the file offset
		 */
		@NonNull
		public Long getOffset() {
			return this.offset;
		}

		/**
		 * The number of file bytes to write.
		 *
		 * @return the byte count
		 */
		@NonNull
		public Long getCount() {
			return this.count;
		}

		@Override
		@NonNull
		public Long getLength() {
			return getCount();
		}
	}

	/**
	 * A static-file body acquired under the helper's trusted immutable-root contract.
	 * Soklet creates these descriptors for {@link StaticFiles}; an application cannot construct one
	 * directly. The opened channel is owned by the transport for the duration of delivery.
	 * <p>
	 * Custom transports must use {@link #openChannel()}, apply {@link #getOffset()} and {@link #getCount()},
	 * and close the channel after delivery or failure. A transport that cannot handle this descriptor
	 * must reject it; opening the path separately loses the helper's root and file validation.
	 */
	@ThreadSafe
	final class SecureFile implements MarshaledResponseBody {
		private final SecureStaticFileRoot root;
		private final Path path;
		private final Long offset;
		private final Long count;
		private final BasicFileAttributes attributes;

		SecureFile(SecureStaticFileRoot root, Path path, Long offset, Long count,
				BasicFileAttributes attributes) {
			this.root = requireNonNull(root);
			this.path = requireNonNull(path);
			this.offset = requireNonNull(offset);
			this.count = requireNonNull(count);
			this.attributes = requireNonNull(attributes);
			validateOffsetAndCount(offset, count);
		}

		/**
		 * Opens the approved file after validating the captured root and expected file metadata.
		 * The immutable-root contract of {@link StaticFiles} applies to every acquisition.
		 * The caller must close the returned channel.
		 *
		 * @return an open channel for the response body
		 * @throws IOException if a change is detected or acquisition fails
		 */
		public @NonNull SeekableByteChannel openChannel() throws IOException {
			return root.open(path, attributes);
		}

		/** @return the zero-based starting byte offset */
		public @NonNull Long getOffset() {
			return offset;
		}

		/** @return the number of bytes to send */
		public @NonNull Long getCount() {
			return count;
		}

		/** @return the number of bytes to send */
		@Override
		public @NonNull Long getLength() {
			return count;
		}
	}

	/**
	 * A finalized response body backed by a {@link java.nio.channels.FileChannel}.
	 * <p>
	 * The channel remains caller-owned while a response is using it. Callers must not close it or modify the referenced
	 * region concurrently. Soklet closes the channel after use only when {@link #getCloseOnComplete()} is {@code true}.
	 * <p>
	 * The standard HTTP server allows native transfer for the JDK's standard file channels when the platform supports it.
	 * Custom channels, including delegating wrappers and channels from other filesystem providers, use a socket adapter
	 * to identify target failures without risking another write after uncertain partial progress. This can lose zero-copy
	 * transfer, reduce throughput, and use more CPU. Supplying a wrapper's underlying JDK channel can retain native transfer
	 * only when bypassing the wrapper preserves the intended bytes, behavior, and ownership.
	 * <p>
	 * A custom channel's {@link java.nio.channels.FileChannel#transferTo(long, long, java.nio.channels.WritableByteChannel)}
	 * runs on the socket event loop. It must honor partial and zero-byte target writes and return the completed byte count;
	 * it must not spin or block waiting for socket capacity to finish the requested transfer.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	final class FileChannel implements MarshaledResponseBody {
		private final java.nio.channels.@NonNull FileChannel channel;
		@NonNull
		private final Long offset;
		@NonNull
		private final Long count;
		@NonNull
		private final Boolean closeOnComplete;

		public FileChannel(java.nio.channels.@NonNull FileChannel channel,
											 @NonNull Long offset,
											 @NonNull Long count,
											 @NonNull Boolean closeOnComplete) {
			this.channel = requireNonNull(channel);
			this.offset = requireNonNull(offset);
			this.count = requireNonNull(count);
			this.closeOnComplete = requireNonNull(closeOnComplete);
			validateOffsetAndCount(offset, count);
		}

		/**
		 * The file channel backing this body.
		 *
		 * @return the file channel
		 */
		public java.nio.channels.@NonNull FileChannel getChannel() {
			return this.channel;
		}

		/**
		 * The zero-based channel offset from which response bytes should be written.
		 *
		 * @return the channel offset
		 */
		@NonNull
		public Long getOffset() {
			return this.offset;
		}

		/**
		 * The number of channel bytes to write.
		 *
		 * @return the byte count
		 */
		@NonNull
		public Long getCount() {
			return this.count;
		}

		/**
		 * Whether Soklet should close this caller-supplied channel after the response completes or fails.
		 *
		 * @return {@code true} if Soklet should close the channel
		 */
		@NonNull
		public Boolean getCloseOnComplete() {
			return this.closeOnComplete;
		}

		@Override
		@NonNull
		public Long getLength() {
			return getCount();
		}
	}

	/**
	 * A finalized response body backed by a {@link java.nio.ByteBuffer}.
	 * <p>
	 * The buffer's position and limit at construction time define the response slice. The descriptor retains a
	 * read-only view, but the caller's original buffer may still share its content; callers must not mutate that content
	 * while a response is using this body.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	final class ByteBuffer implements MarshaledResponseBody {
		private final java.nio.@NonNull ByteBuffer buffer;

		public ByteBuffer(java.nio.@NonNull ByteBuffer buffer) {
			this.buffer = requireNonNull(buffer).slice().asReadOnlyBuffer();
		}

		/**
		 * The read-only buffer slice backing this body.
		 *
		 * @return a read-only duplicate of the response buffer
		 */
		public java.nio.@NonNull ByteBuffer getBuffer() {
			return this.buffer.asReadOnlyBuffer();
		}

		@Override
		@NonNull
		public Long getLength() {
			return Long.valueOf(this.buffer.remaining());
		}
	}

	private static void validateOffsetAndCount(@NonNull Long offset, @NonNull Long count) {
		requireNonNull(offset);
		requireNonNull(count);

		if (offset < 0)
			throw new IllegalArgumentException("Offset must be >= 0.");

		if (count < 0)
			throw new IllegalArgumentException("Count must be >= 0.");

		if (Long.MAX_VALUE - offset < count)
			throw new IllegalArgumentException("Offset plus count exceeds maximum supported file position.");
	}
}
