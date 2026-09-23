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

import static java.util.Objects.requireNonNull;

/**
 * A streaming HTTP response body.
 * <p>
 * This type describes how response bytes are produced; it is not itself responsible for writing to a transport.
 * Descriptors are immutable and thread-safe, but caller-supplied writers, publishers, readers, input streams, and
 * factories are responsible for their own behavior.
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
	 * <p>
	 * Soklet requests one item at a time and writes each item through its bounded streaming queue. If the queue is
	 * full, the subscriber's {@code onNext} path may block until space is available.
	 * <p>
	 * If the response is canceled before the publisher terminates, Soklet cancels the publisher subscription.
	 * A publisher may deliver its first subscription asynchronously after {@code subscribe} returns normally.
	 * Cancelation in that interval retains lifecycle capacity until the subscription arrives and its cancel attempt
	 * physically finishes; no data is requested from that late subscription. If it never arrives, cleanup expiry
	 * and shutdown report the pending obligation without releasing its capacity or retaining a waiting producer.
	 * <p>
	 * Entered subscription calls remain accounted for even if they synchronously publish a terminal signal before
	 * returning. Successful response production waits for those calls to finish. Calls already claimed before
	 * cancelation can finish concurrently with the subscription's cancel operation.
	 * <p>
	 * Publishers must follow the {@link Flow} signal protocol. If {@code subscribe} throws before delivering a
	 * subscription, acquisition has failed and the publisher remains responsible for its partial resources; it
	 * must not subsequently deliver a subscription. Signals before the first subscription are protocol failures.
	 * Subscriptions supplied after a failed acquisition or completed lifetime are rejected before invoking their
	 * methods, leaving cleanup with the publisher. A cleanup deadline cannot make arbitrary provider code return.
	 *
	 * @param publisher the publisher that emits response bytes
	 * @return a streaming response body
	 */
	@NonNull
	static StreamingResponseBody fromPublisher(java.util.concurrent.Flow.@NonNull Publisher<@NonNull ByteBuffer> publisher) {
		return new PublisherBody(publisher);
	}

	/**
	 * Creates a streaming response body backed by an input stream factory.
	 * <p>
	 * This adapter is intended for special cases where an existing streaming source is already exposed as an
	 * {@link InputStream}. Dynamic application streaming should usually use {@link #fromWriter(StreamingResponseWriter)}
	 * or {@link #fromPublisher(Flow.Publisher)}.
	 * <p>
	 * The factory is invoked lazily when response production starts, not when this descriptor is constructed, and
	 * may throw a checked exception. Each invocation must open an independently owned input stream.
	 * If the response is canceled while a read is blocked, Soklet closes the input stream. The source must support
	 * close racing a read, including unblocking the read; {@code InputStream} alone does not guarantee this behavior.
	 *
	 * @param inputStreamFactory opens the input stream to copy
	 * @return a streaming response body
	 */
	@NonNull
	static StreamingResponseBody fromInputStream(
			@NonNull StreamResourceFactory<? extends @NonNull InputStream> inputStreamFactory) {
		return withInputStream(inputStreamFactory).build();
	}

	/**
	 * Acquires a builder for an input-stream-backed response body.
	 * <p>
	 * The factory remains lazy and follows the acquisition and concurrent-close contract of
	 * {@link #fromInputStream(StreamResourceFactory)}.
	 *
	 * @param inputStreamFactory opens the input stream to copy
	 * @return the builder
	 */
	@NonNull
	static InputStreamBuilder withInputStream(
			@NonNull StreamResourceFactory<? extends @NonNull InputStream> inputStreamFactory) {
		return new InputStreamBuilder(inputStreamFactory);
	}

	/**
	 * Creates a streaming response body backed by a reader factory.
	 * <p>
	 * The charset is required. Encoding errors default to {@link CodingErrorAction#REPORT}; use
	 * {@link #withReader(StreamResourceFactory, Charset)} to override the JDK encoder actions explicitly.
	 * The factory is invoked lazily when response production starts, not when this descriptor is constructed, and
	 * may throw a checked exception. Each invocation must open an independently owned reader.
	 * If the response is canceled while a read is blocked, Soklet closes the reader. The source must support close
	 * racing a read, including unblocking the read; {@code Reader} alone does not guarantee this behavior.
	 *
	 * @param readerFactory opens the reader to copy
	 * @param charset       charset used to encode characters to response bytes
	 * @return a streaming response body
	 */
	@NonNull
	static StreamingResponseBody fromReader(
			@NonNull StreamResourceFactory<? extends @NonNull Reader> readerFactory,
			@NonNull Charset charset) {
		return withReader(readerFactory, charset).build();
	}

	/**
	 * Acquires a builder for a reader-backed response body.
	 * <p>
	 * The factory remains lazy and follows the acquisition and concurrent-close contract of
	 * {@link #fromReader(StreamResourceFactory, Charset)}.
	 *
	 * @param readerFactory opens the reader to copy
	 * @param charset       charset used to encode characters to response bytes
	 * @return the builder
	 */
	@NonNull
	static ReaderBuilder withReader(
			@NonNull StreamResourceFactory<? extends @NonNull Reader> readerFactory,
			@NonNull Charset charset) {
		return new ReaderBuilder(readerFactory, charset);
	}

	/**
	 * A streaming body backed by a {@link Flow.Publisher}.
	 */
	@ThreadSafe
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
	 * A streaming body backed by an {@link InputStream} factory.
	 */
	@ThreadSafe
	final class InputStreamBody implements StreamingResponseBody {
		@NonNull
		private final StreamResourceFactory<? extends InputStream> inputStreamFactory;
		@NonNull
		private final Integer bufferSizeInBytes;

		private InputStreamBody(@NonNull InputStreamBuilder builder) {
			requireNonNull(builder);

			this.inputStreamFactory = builder.inputStreamFactory;
			this.bufferSizeInBytes = builder.bufferSizeInBytes == null
					? DEFAULT_INPUT_STREAM_BUFFER_SIZE_IN_BYTES
					: builder.bufferSizeInBytes;

			if (this.bufferSizeInBytes < 1)
				throw new IllegalArgumentException("Input stream buffer size must be > 0");
		}

		/**
		 * The checked factory that opens an independently owned source input stream for each execution.
		 *
		 * @return the input stream factory
		 */
		@NonNull
		public StreamResourceFactory<? extends @NonNull InputStream> getInputStreamFactory() {
			return this.inputStreamFactory;
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
	 * A streaming body backed by a {@link Reader} factory.
	 */
	@ThreadSafe
	final class ReaderBody implements StreamingResponseBody {
		@NonNull
		private final StreamResourceFactory<? extends Reader> readerFactory;
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

			this.readerFactory = builder.readerFactory;
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
		 * The checked factory that opens an independently owned source reader for each execution.
		 *
		 * @return the reader factory
		 */
		@NonNull
		public StreamResourceFactory<? extends @NonNull Reader> getReaderFactory() {
			return this.readerFactory;
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
	@ThreadSafe
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
		private final StreamResourceFactory<? extends InputStream> inputStreamFactory;
		@Nullable
		private Integer bufferSizeInBytes;

		private InputStreamBuilder(@NonNull StreamResourceFactory<? extends InputStream> inputStreamFactory) {
			this.inputStreamFactory = requireNonNull(inputStreamFactory);
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
		private final StreamResourceFactory<? extends Reader> readerFactory;
		@NonNull
		private final Charset charset;
		@Nullable
		private Integer bufferSizeInCharacters;
		@Nullable
		private CodingErrorAction malformedInputAction;
		@Nullable
		private CodingErrorAction unmappableCharacterAction;

		private ReaderBuilder(@NonNull StreamResourceFactory<? extends Reader> readerFactory,
													@NonNull Charset charset) {
			this.readerFactory = requireNonNull(readerFactory);
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
