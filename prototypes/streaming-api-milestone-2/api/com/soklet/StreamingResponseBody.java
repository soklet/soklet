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

/**
 * Compile-only descriptor signatures; factories replace Supplier with no legacy
 * overload. Descriptors remain immutable; captured provider state must support
 * descriptor reuse. Input/reader adapters coordinate close-on-cancel and require
 * providers that support close racing read. Checked acquisition runs lazily on
 * the producer, never on the request handler merely for building a descriptor.
 */
@ThreadSafe
public sealed interface StreamingResponseBody permits StreamingResponseBody.PublisherBody,
		StreamingResponseBody.InputStreamBody, StreamingResponseBody.ReaderBody, StreamingResponseBody.WriterBody {
	@NonNull Integer DEFAULT_INPUT_STREAM_BUFFER_SIZE_IN_BYTES = 16 * 1024;
	@NonNull Integer DEFAULT_READER_BUFFER_SIZE_IN_CHARACTERS = 8192;
	@NonNull CodingErrorAction DEFAULT_MALFORMED_INPUT_ACTION = CodingErrorAction.REPORT;
	@NonNull CodingErrorAction DEFAULT_UNMAPPABLE_CHARACTER_ACTION = CodingErrorAction.REPORT;

	@NonNull static StreamingResponseBody fromWriter(@NonNull StreamingResponseWriter writer) { throw signatureOnly(); }
	@NonNull static StreamingResponseBody fromPublisher(Flow.@NonNull Publisher<@NonNull ByteBuffer> publisher) { throw signatureOnly(); }
	@NonNull static StreamingResponseBody fromInputStream(
			@NonNull StreamResourceFactory<? extends @NonNull InputStream> inputStreamFactory) { throw signatureOnly(); }
	@NonNull static InputStreamBuilder withInputStream(
			@NonNull StreamResourceFactory<? extends @NonNull InputStream> inputStreamFactory) { throw signatureOnly(); }
	@NonNull static StreamingResponseBody fromReader(
			@NonNull StreamResourceFactory<? extends @NonNull Reader> readerFactory,
			@NonNull Charset charset) { throw signatureOnly(); }
	@NonNull static ReaderBuilder withReader(
			@NonNull StreamResourceFactory<? extends @NonNull Reader> readerFactory,
			@NonNull Charset charset) { throw signatureOnly(); }

	@ThreadSafe
	final class PublisherBody implements StreamingResponseBody {
		private PublisherBody() {}
		public Flow.@NonNull Publisher<@NonNull ByteBuffer> getPublisher() { throw signatureOnly(); }
	}
	@ThreadSafe
	final class InputStreamBody implements StreamingResponseBody {
		private InputStreamBody() {}
		@NonNull public StreamResourceFactory<? extends @NonNull InputStream> getInputStreamFactory() { throw signatureOnly(); }
		@NonNull public Integer getBufferSizeInBytes() { throw signatureOnly(); }
	}
	@ThreadSafe
	final class ReaderBody implements StreamingResponseBody {
		private ReaderBody() {}
		@NonNull public StreamResourceFactory<? extends @NonNull Reader> getReaderFactory() { throw signatureOnly(); }
		@NonNull public Charset getCharset() { throw signatureOnly(); }
		@NonNull public Integer getBufferSizeInCharacters() { throw signatureOnly(); }
		@NonNull public CodingErrorAction getMalformedInputAction() { throw signatureOnly(); }
		@NonNull public CodingErrorAction getUnmappableCharacterAction() { throw signatureOnly(); }
		@NonNull public CharsetEncoder newEncoder() { throw signatureOnly(); }
	}
	@ThreadSafe
	final class WriterBody implements StreamingResponseBody {
		private WriterBody() {}
		@NonNull public StreamingResponseWriter getWriter() { throw signatureOnly(); }
	}
	@NotThreadSafe
	final class InputStreamBuilder {
		private InputStreamBuilder() {}
		@NonNull public InputStreamBuilder bufferSizeInBytes(@Nullable Integer bufferSizeInBytes) { throw signatureOnly(); }
		@NonNull public StreamingResponseBody build() { throw signatureOnly(); }
	}
	@NotThreadSafe
	final class ReaderBuilder {
		private ReaderBuilder() {}
		@NonNull public ReaderBuilder bufferSizeInCharacters(@Nullable Integer bufferSizeInCharacters) { throw signatureOnly(); }
		@NonNull public ReaderBuilder malformedInputAction(@Nullable CodingErrorAction malformedInputAction) { throw signatureOnly(); }
		@NonNull public ReaderBuilder unmappableCharacterAction(@Nullable CodingErrorAction unmappableCharacterAction) { throw signatureOnly(); }
		@NonNull public StreamingResponseBody build() { throw signatureOnly(); }
	}
	private static UnsupportedOperationException signatureOnly() { return new UnsupportedOperationException("Compile-only API fixture"); }
}
