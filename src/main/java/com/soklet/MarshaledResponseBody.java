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

import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * A known-length, finalized HTTP response body.
 * <p>
 * This type describes the body to write; it is not itself responsible for writing to a transport. Bodies may be backed
 * by bytes, files, file channels, or byte buffers.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public sealed interface MarshaledResponseBody permits MarshaledResponseBody.Bytes, MarshaledResponseBody.File, MarshaledResponseBody.FileChannel, MarshaledResponseBody.ByteBuffer {
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
	final class Bytes implements MarshaledResponseBody {
		@NonNull
		private final byte[] bytes;

		public Bytes(@NonNull byte[] bytes) {
			this.bytes = requireNonNull(bytes);
		}

		/**
		 * The byte array backing this body.
		 * <p>
		 * For compatibility with prior {@link MarshaledResponse} behavior, this array is not defensively copied.
		 *
		 * @return the bytes to write
		 */
		@NonNull
		public byte[] getBytes() {
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
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
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
	 * A finalized response body backed by a {@link java.nio.channels.FileChannel}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
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
	 * The buffer's position and limit at construction time define the response slice.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
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
