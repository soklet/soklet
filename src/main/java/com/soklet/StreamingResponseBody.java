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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * A streaming HTTP response body.
 * <p>
 * This type describes how response bytes are produced; it is not itself responsible for writing to a transport.
 * Descriptors are immutable and thread-safe, but caller-supplied writers, publishers, readers, input streams, and
 * suppliers are responsible for their own behavior.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface StreamingResponseBody permits StreamingResponseBody.PublisherBody, StreamingResponseBody.InputStreamBody, StreamingResponseBody.ReaderBody, StreamingResponseBody.WriterBody {
	@NonNull
	Integer DEFAULT_INPUT_STREAM_BUFFER_SIZE_IN_BYTES = 1_024 * 16;
	@NonNull
	Integer DEFAULT_READER_BUFFER_SIZE_IN_CHARACTERS = 8_192;
	@NonNull
	CodingErrorAction DEFAULT_MALFORMED_INPUT_ACTION = CodingErrorAction.REPORT;
	@NonNull
	CodingErrorAction DEFAULT_UNMAPPABLE_CHARACTER_ACTION = CodingErrorAction.REPORT;

	/**
	 * Creates a streaming response body backed by a writer callback.
	 *
	 * @param writer the callback that writes the response
	 * @return a streaming response body
	 */
	@NonNull
	static StreamingResponseBody fromWriter(@NonNull StreamingResponseWriter writer) {
		return new WriterBody(writer);
	}

	/**
	 * Creates a streaming response body backed by a {@link Flow.Publisher} of byte buffers.
	 *
	 * @param publisher the publisher that emits response bytes
	 * @return a streaming response body
	 */
	@NonNull
	static StreamingResponseBody fromPublisher(java.util.concurrent.Flow.@NonNull Publisher<@NonNull ByteBuffer> publisher) {
		return new PublisherBody(publisher);
	}

	/**
	 * Creates a streaming response body backed by an input stream supplier.
	 * <p>
	 * This adapter is intended for special cases where an existing streaming source is already exposed as an
	 * {@link InputStream}. Dynamic application streaming should usually use {@link #fromWriter(StreamingResponseWriter)}
	 * or {@link #fromPublisher(Flow.Publisher)}.
	 *
	 * @param inputStreamSupplier supplies the input stream to copy
	 * @return a streaming response body
	 */
	@NonNull
	static StreamingResponseBody fromInputStream(@NonNull Supplier<? extends InputStream> inputStreamSupplier) {
		return withInputStream(inputStreamSupplier).build();
	}

	/**
	 * Acquires a builder for an input-stream-backed response body.
	 *
	 * @param inputStreamSupplier supplies the input stream to copy
	 * @return the builder
	 */
	@NonNull
	static InputStreamBuilder withInputStream(@NonNull Supplier<? extends InputStream> inputStreamSupplier) {
		return new InputStreamBuilder(inputStreamSupplier);
	}

	/**
	 * Creates a streaming response body backed by a reader supplier.
	 * <p>
	 * The charset is required. Encoding errors default to {@link CodingErrorAction#REPORT}; use
	 * {@link #withReader(Supplier, Charset)} to override the JDK encoder actions explicitly.
	 *
	 * @param readerSupplier supplies the reader to copy
	 * @param charset        charset used to encode characters to response bytes
	 * @return a streaming response body
	 */
	@NonNull
	static StreamingResponseBody fromReader(@NonNull Supplier<? extends Reader> readerSupplier,
																					@NonNull Charset charset) {
		return withReader(readerSupplier, charset).build();
	}

	/**
	 * Acquires a builder for a reader-backed response body.
	 *
	 * @param readerSupplier supplies the reader to copy
	 * @param charset        charset used to encode characters to response bytes
	 * @return the builder
	 */
	@NonNull
	static ReaderBuilder withReader(@NonNull Supplier<? extends Reader> readerSupplier,
																	@NonNull Charset charset) {
		return new ReaderBuilder(readerSupplier, charset);
	}

	/**
	 * A streaming body backed by a {@link Flow.Publisher}.
	 */
	final class PublisherBody implements StreamingResponseBody {
		private final java.util.concurrent.Flow.@NonNull Publisher<@NonNull ByteBuffer> publisher;

		private PublisherBody(java.util.concurrent.Flow.@NonNull Publisher<@NonNull ByteBuffer> publisher) {
			this.publisher = requireNonNull(publisher);
		}

		/**
		 * The publisher that emits byte buffers for this response.
		 *
		 * @return the byte-buffer publisher
		 */
		public java.util.concurrent.Flow.@NonNull Publisher<@NonNull ByteBuffer> getPublisher() {
			return this.publisher;
		}
	}

	/**
	 * A streaming body backed by an {@link InputStream} supplier.
	 */
	final class InputStreamBody implements StreamingResponseBody {
		@NonNull
		private final Supplier<? extends InputStream> inputStreamSupplier;
		@NonNull
		private final Integer bufferSizeInBytes;

		private InputStreamBody(@NonNull InputStreamBuilder builder) {
			requireNonNull(builder);

			this.inputStreamSupplier = builder.inputStreamSupplier;
			this.bufferSizeInBytes = builder.bufferSizeInBytes == null
					? DEFAULT_INPUT_STREAM_BUFFER_SIZE_IN_BYTES
					: builder.bufferSizeInBytes;

			if (this.bufferSizeInBytes < 1)
				throw new IllegalArgumentException("Input stream buffer size must be > 0");
		}

		/**
		 * The supplier that opens the source input stream.
		 *
		 * @return the input stream supplier
		 */
		@NonNull
		public Supplier<? extends InputStream> getInputStreamSupplier() {
			return this.inputStreamSupplier;
		}

		/**
		 * The adapter buffer size.
		 *
		 * @return the adapter buffer size in bytes
		 */
		@NonNull
		public Integer getBufferSizeInBytes() {
			return this.bufferSizeInBytes;
		}
	}

	/**
	 * A streaming body backed by a {@link Reader} supplier.
	 */
	final class ReaderBody implements StreamingResponseBody {
		@NonNull
		private final Supplier<? extends Reader> readerSupplier;
		@NonNull
		private final Charset charset;
		@NonNull
		private final Integer bufferSizeInCharacters;
		@NonNull
		private final CodingErrorAction malformedInputAction;
		@NonNull
		private final CodingErrorAction unmappableCharacterAction;

		private ReaderBody(@NonNull ReaderBuilder builder) {
			requireNonNull(builder);

			this.readerSupplier = builder.readerSupplier;
			this.charset = builder.charset;
			this.bufferSizeInCharacters = builder.bufferSizeInCharacters == null
					? DEFAULT_READER_BUFFER_SIZE_IN_CHARACTERS
					: builder.bufferSizeInCharacters;
			this.malformedInputAction = builder.malformedInputAction == null
					? DEFAULT_MALFORMED_INPUT_ACTION
					: builder.malformedInputAction;
			this.unmappableCharacterAction = builder.unmappableCharacterAction == null
					? DEFAULT_UNMAPPABLE_CHARACTER_ACTION
					: builder.unmappableCharacterAction;

			if (this.bufferSizeInCharacters < 1)
				throw new IllegalArgumentException("Reader buffer size must be > 0");
		}

		/**
		 * The supplier that opens the source reader.
		 *
		 * @return the reader supplier
		 */
		@NonNull
		public Supplier<? extends Reader> getReaderSupplier() {
			return this.readerSupplier;
		}

		/**
		 * The charset used to encode characters to response bytes.
		 *
		 * @return the charset
		 */
		@NonNull
		public Charset getCharset() {
			return this.charset;
		}

		/**
		 * The adapter buffer size.
		 *
		 * @return the adapter buffer size in characters
		 */
		@NonNull
		public Integer getBufferSizeInCharacters() {
			return this.bufferSizeInCharacters;
		}

		/**
		 * Encoder behavior for malformed input.
		 *
		 * @return the malformed-input behavior
		 */
		@NonNull
		public CodingErrorAction getMalformedInputAction() {
			return this.malformedInputAction;
		}

		/**
		 * Encoder behavior for unmappable characters.
		 *
		 * @return the unmappable-character behavior
		 */
		@NonNull
		public CodingErrorAction getUnmappableCharacterAction() {
			return this.unmappableCharacterAction;
		}

		/**
		 * Creates a new charset encoder configured with this body's error actions.
		 *
		 * @return a newly configured charset encoder
		 */
		@NonNull
		public CharsetEncoder newEncoder() {
			return getCharset().newEncoder()
					.onMalformedInput(getMalformedInputAction())
					.onUnmappableCharacter(getUnmappableCharacterAction());
		}
	}

	/**
	 * A streaming body backed by a writer callback.
	 */
	final class WriterBody implements StreamingResponseBody {
		@NonNull
		private final StreamingResponseWriter writer;

		private WriterBody(@NonNull StreamingResponseWriter writer) {
			this.writer = requireNonNull(writer);
		}

		/**
		 * The callback that writes the response body.
		 *
		 * @return the streaming response writer
		 */
		@NonNull
		public StreamingResponseWriter getWriter() {
			return this.writer;
		}
	}

	/**
	 * Builder for input-stream-backed response bodies.
	 * <p>
	 * This class is intended for use by a single thread.
	 */
	@NotThreadSafe
	final class InputStreamBuilder {
		@NonNull
		private final Supplier<? extends InputStream> inputStreamSupplier;
		@Nullable
		private Integer bufferSizeInBytes;

		private InputStreamBuilder(@NonNull Supplier<? extends InputStream> inputStreamSupplier) {
			this.inputStreamSupplier = requireNonNull(inputStreamSupplier);
		}

		/**
		 * Sets the input stream copy buffer size.
		 *
		 * @param bufferSizeInBytes buffer size in bytes, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public InputStreamBuilder bufferSizeInBytes(@Nullable Integer bufferSizeInBytes) {
			this.bufferSizeInBytes = bufferSizeInBytes;
			return this;
		}

		/**
		 * Builds an input-stream-backed response body.
		 *
		 * @return a streaming response body
		 */
		@NonNull
		public StreamingResponseBody build() {
			return new InputStreamBody(this);
		}
	}

	/**
	 * Builder for reader-backed response bodies.
	 * <p>
	 * This class is intended for use by a single thread.
	 */
	@NotThreadSafe
	final class ReaderBuilder {
		@NonNull
		private final Supplier<? extends Reader> readerSupplier;
		@NonNull
		private final Charset charset;
		@Nullable
		private Integer bufferSizeInCharacters;
		@Nullable
		private CodingErrorAction malformedInputAction;
		@Nullable
		private CodingErrorAction unmappableCharacterAction;

		private ReaderBuilder(@NonNull Supplier<? extends Reader> readerSupplier,
													@NonNull Charset charset) {
			this.readerSupplier = requireNonNull(readerSupplier);
			this.charset = requireNonNull(charset);
		}

		/**
		 * Sets the reader copy buffer size.
		 *
		 * @param bufferSizeInCharacters buffer size in characters, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public ReaderBuilder bufferSizeInCharacters(@Nullable Integer bufferSizeInCharacters) {
			this.bufferSizeInCharacters = bufferSizeInCharacters;
			return this;
		}

		/**
		 * Sets encoder behavior for malformed input.
		 *
		 * @param malformedInputAction malformed-input behavior, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public ReaderBuilder malformedInputAction(@Nullable CodingErrorAction malformedInputAction) {
			this.malformedInputAction = malformedInputAction;
			return this;
		}

		/**
		 * Sets encoder behavior for unmappable characters.
		 *
		 * @param unmappableCharacterAction unmappable-character behavior, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public ReaderBuilder unmappableCharacterAction(@Nullable CodingErrorAction unmappableCharacterAction) {
			this.unmappableCharacterAction = unmappableCharacterAction;
			return this;
		}

		/**
		 * Builds a reader-backed response body.
		 *
		 * @return a streaming response body
		 */
		@NonNull
		public StreamingResponseBody build() {
			return new ReaderBody(this);
		}
	}
}
