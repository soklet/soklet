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

import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.nio.file.StandardOpenOption.READ;
import static java.util.Objects.requireNonNull;

/**
 * A finalized representation of a {@link Response}, suitable for sending to clients over the wire.
 * <p>
 * Your application's {@link ResponseMarshaler} is responsible for taking the {@link Response} returned by a <em>Resource Method</em> as input
 * and converting its {@link Response#getBody()} to a {@link MarshaledResponseBody}.
 * <p>
 * For example, if a {@link Response} were to specify a body of {@code List.of("one", "two")}, a {@link ResponseMarshaler} might
 * convert it to the JSON string {@code ["one", "two"]} and provide as output a corresponding {@link MarshaledResponse} with a byte-array-backed body containing UTF-8 bytes that represent {@code ["one", "two"]}.
 * <p>
 * Alternatively, your <em>Resource Method</em> might want to directly serve bytes to clients (e.g. an image or PDF) and skip the {@link ResponseMarshaler} entirely.
 * To accomplish this, just have your <em>Resource Method</em> return a {@link MarshaledResponse} instance: this tells Soklet "I already know exactly what bytes I want to send; don't go through the normal marshaling process".
 * <p>
 * Instances can be acquired via the {@link #withResponse(Response)}, {@link #withStatusCode(Integer)}, or {@link #withFile(Path, Request)} builder factory methods.
 * Convenience instance factories are also available via {@link #fromResponse(Response)} and {@link #fromStatusCode(Integer)}.
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/response-writing">https://www.soklet.com/docs/response-writing</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class MarshaledResponse {
	@NonNull
	private final Integer statusCode;
	@NonNull
	private final Map<@NonNull String, @NonNull Set<@NonNull String>> headers;
	@NonNull
	private final Set<@NonNull ResponseCookie> cookies;
	@Nullable
	private final MarshaledResponseBody body;
	@Nullable
	private final StreamingResponseBody stream;

	/**
	 * Acquires a builder for {@link MarshaledResponse} instances.
	 *
	 * @param response the logical response whose values are used to prime this builder
	 * @return the builder
	 */
	@NonNull
	public static Builder withResponse(@NonNull Response response) {
		requireNonNull(response);

		Object rawBody = response.getBody().orElse(null);
		MarshaledResponseBody body = null;

		if (rawBody != null && rawBody instanceof byte[] byteArrayBody)
			body = new MarshaledResponseBody.Bytes(byteArrayBody);

		Builder builder = new Builder(response.getStatusCode())
				.headers(response.getHeaders())
				.cookies(response.getCookies());

		if (body != null)
			builder.body(body);

		return builder;
	}

	/**
	 * Creates a {@link MarshaledResponse} from a logical {@link Response} without additional customization.
	 *
	 * @param response the logical response whose values are used to construct this instance
	 * @return a {@link MarshaledResponse} instance
	 */
	@NonNull
	public static MarshaledResponse fromResponse(@NonNull Response response) {
		return withResponse(response).build();
	}

	/**
	 * Acquires a builder for {@link MarshaledResponse} instances.
	 *
	 * @param statusCode the HTTP status code for this response
	 * @return the builder
	 */
	@NonNull
	public static Builder withStatusCode(@NonNull Integer statusCode) {
		requireNonNull(statusCode);
		return new Builder(statusCode);
	}

	/**
	 * Creates a {@link MarshaledResponse} with the given status code and no additional customization.
	 *
	 * @param statusCode the HTTP status code for this response
	 * @return a {@link MarshaledResponse} instance
	 */
	@NonNull
	public static MarshaledResponse fromStatusCode(@NonNull Integer statusCode) {
		return withStatusCode(statusCode).build();
	}

	/**
	 * Acquires a file-specific builder for {@link MarshaledResponse} instances.
	 * <p>
	 * Files are special among known-length response bodies: correct file responses can depend on request
	 * headers such as {@code Range}, {@code If-Range}, {@code If-Match}, and {@code If-None-Match}, plus
	 * filesystem metadata such as length and last-modified time. This custom factory keeps that behavior
	 * in one place. Other known-length body types should use {@link Builder#body(MarshaledResponseBody)}
	 * or one of its overloads.
	 *
	 * @param path the file path to write
	 * @param request the incoming request whose method and conditional/range headers should be honored
	 * @return a file-specific builder
	 */
	@NonNull
	public static FileBuilder withFile(@NonNull Path path,
																		 @NonNull Request request) {
		requireNonNull(path);
		requireNonNull(request);
		return new FileBuilder(path, request);
	}

	@NonNull
	static FileBuilder withFile(@NonNull Path path,
															@NonNull Request request,
															@NonNull BasicFileAttributes attributes) {
		requireNonNull(path);
		requireNonNull(request);
		requireNonNull(attributes);
		return new FileBuilder(path, request).attributes(attributes);
	}

	/**
	 * Vends a mutable copier seeded with this instance's data, suitable for building new instances.
	 *
	 * @return a copier for this instance
	 */
	@NonNull
	public Copier copy() {
		return new Copier(this);
	}

	protected MarshaledResponse(@NonNull Builder builder) {
		requireNonNull(builder);

		this.statusCode = builder.statusCode;
		this.headers = builder.headers == null ? Map.of() : new LinkedCaseInsensitiveMap<>(builder.headers);
		this.cookies = builder.cookies == null ? Set.of() : new LinkedHashSet<>(builder.cookies);
		this.body = builder.body;
		this.stream = builder.stream;

		// Verify headers are legal
		for (Entry<String, Set<String>> entry : this.headers.entrySet()) {
			String headerName = entry.getKey();
			Set<String> headerValues = entry.getValue();

			for (String headerValue : headerValues)
				Utilities.validateHeaderNameAndValue(headerName, headerValue);
		}

		if (getBody().isPresent() && getStream().isPresent())
			throw new IllegalStateException("A MarshaledResponse may not specify both a known-length body and a streaming response body.");

		if (getStream().isPresent()) {
			if (isBodylessStatusCode(getStatusCode()))
				throw new IllegalStateException(format("HTTP status code %d must not include a streaming response body.", getStatusCode()));

			if (this.headers.containsKey("Content-Length"))
				throw new IllegalStateException("Streaming responses must not specify Content-Length.");

			if (this.headers.containsKey("Transfer-Encoding"))
				throw new IllegalStateException("Streaming responses must not specify Transfer-Encoding.");
		}
	}

	@Override
	public String toString() {
		return format("%s{statusCode=%s, headers=%s, cookies=%s, body=%s}", getClass().getSimpleName(),
				getStatusCode(), getHeaders(), getCookies(),
				format("%d bytes", getBodyLength()));
	}

	/**
	 * The HTTP status code for this response.
	 *
	 * @return the status code
	 */
	@NonNull
	public Integer getStatusCode() {
		return this.statusCode;
	}

	/**
	 * The HTTP headers to write for this response.
	 * <p>
	 * Soklet writes one header line per value. If order matters, provide either a {@link java.util.SortedSet} or
	 * {@link java.util.LinkedHashSet} to preserve the desired ordering; otherwise values are naturally sorted for consistency.
	 *
	 * @return the headers to write
	 */
	@NonNull
	public Map<@NonNull String, @NonNull Set<@NonNull String>> getHeaders() {
		return this.headers;
	}

	/**
	 * The HTTP cookies to write for this response.
	 *
	 * @return the cookies to write
	 */
	@NonNull
	public Set<@NonNull ResponseCookie> getCookies() {
		return this.cookies;
	}

	/**
	 * The finalized HTTP response body to write, if available.
	 *
	 * @return the response body to write, or {@link Optional#empty()}) if no body should be written
	 */
	@NonNull
	public Optional<MarshaledResponseBody> getBody() {
		return Optional.ofNullable(this.body);
	}

	/**
	 * The finalized streaming HTTP response body to write, if available.
	 *
	 * @return the streaming response body to write, or {@link Optional#empty()} if no stream should be written
	 */
	@NonNull
	public Optional<StreamingResponseBody> getStream() {
		return Optional.ofNullable(this.stream);
	}

	/**
	 * Whether this response has a streaming response body.
	 *
	 * @return {@code true} if this response is streaming
	 */
	@NonNull
	public Boolean isStreaming() {
		return getStream().isPresent();
	}

	/**
	 * The number of bytes this response body will write.
	 *
	 * @return the body length, or {@code 0} if no body is present
	 */
	@NonNull
	public Long getBodyLength() {
		MarshaledResponseBody body = getBody().orElse(null);
		return body == null ? 0L : body.getLength();
	}

	@Nullable
	byte[] bodyBytesOrNull() {
		MarshaledResponseBody body = getBody().orElse(null);

		if (body == null)
			return null;

		if (body instanceof MarshaledResponseBody.Bytes bytes)
			return bytes.getBytes();

		if (body instanceof MarshaledResponseBody.File file)
			return materializeFile(file.getPath(), file.getOffset(), file.getCount());

		if (body instanceof MarshaledResponseBody.FileChannel fileChannel)
			return materializeFileChannel(fileChannel.getChannel(), fileChannel.getOffset(), fileChannel.getCount(), fileChannel.getCloseOnComplete());

		if (body instanceof MarshaledResponseBody.ByteBuffer byteBuffer)
			return materializeByteBuffer(byteBuffer.getBuffer());

		throw new IllegalStateException(format("Unsupported marshaled response body type: %s", body.getClass().getName()));
	}

	@NonNull
	byte[] bodyBytesOrEmpty() {
		byte[] bytes = bodyBytesOrNull();
		return bytes == null ? Utilities.emptyByteArray() : bytes;
	}

	/**
	 * Builder used to construct instances of {@link MarshaledResponse} via {@link MarshaledResponse#withResponse(Response)} or {@link MarshaledResponse#withStatusCode(Integer)}.
	 * <p>
	 * Known-length bodies and streaming bodies are mutually exclusive. This builder does not automatically clear one
	 * when the other is set; use {@link #withoutBody()} or {@link #withoutStream()} before {@link #build()} when
	 * switching body modes.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private Integer statusCode;
		@Nullable
		private Set<@NonNull ResponseCookie> cookies;
		@Nullable
		private Map<@NonNull String, @NonNull Set<@NonNull String>> headers;
		@Nullable
		private MarshaledResponseBody body;
		@Nullable
		private StreamingResponseBody stream;

		protected Builder(@NonNull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
		}

		@NonNull
		public Builder statusCode(@NonNull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
			return this;
		}

		@NonNull
		public Builder cookies(@Nullable Set<@NonNull ResponseCookie> cookies) {
			this.cookies = cookies;
			return this;
		}

		@NonNull
		public Builder headers(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
			this.headers = headers;
			return this;
		}

		/**
		 * Sets a byte-array-backed response body, or removes any current body if {@code bytes} is {@code null}.
		 *
		 * @param bytes the response bytes to write, or {@code null} for no body
		 * @return this builder
		 */
		@NonNull
		public Builder body(@Nullable byte[] bytes) {
			return bytes == null
					? withoutBody()
					: body(new MarshaledResponseBody.Bytes(bytes));
		}

		/**
		 * Sets a response body descriptor, or removes any current body if {@code body} is {@code null}.
		 *
		 * @param body the response body descriptor to write, or {@code null} for no body
		 * @return this builder
		 */
		@NonNull
		public Builder body(@Nullable MarshaledResponseBody body) {
			if (body == null)
				return withoutBody();

			this.body = body;
			return this;
		}

		/**
		 * Sets a path-backed response body, or removes any current body if {@code path} is {@code null}.
		 *
		 * @param path the file path to write, or {@code null} for no body
		 * @return this builder
		 */
		@NonNull
		public Builder body(@Nullable Path path) {
			if (path == null)
				return withoutBody();

			this.body = fileBody(path);
			return this;
		}

		/**
		 * Sets a ranged path-backed response body.
		 *
		 * @param path   the file path to write
		 * @param offset the zero-based file offset from which response bytes should be written
		 * @param count  the number of file bytes to write
		 * @return this builder
		 */
		@NonNull
		public Builder body(@NonNull Path path, @NonNull Long offset, @NonNull Long count) {
			requireNonNull(path);
			this.body = fileBody(path, offset, count);
			return this;
		}

		/**
		 * Sets a file-channel-backed response body.
		 *
		 * @param fileChannel     the file channel to write
		 * @param offset          the zero-based channel offset from which response bytes should be written
		 * @param count           the number of channel bytes to write
		 * @param closeOnComplete whether Soklet should close the channel after response completion
		 * @return this builder
		 */
		@NonNull
		public Builder body(@NonNull FileChannel fileChannel,
												@NonNull Long offset,
												@NonNull Long count,
												@NonNull Boolean closeOnComplete) {
			requireNonNull(fileChannel);
			this.body = fileChannelBody(fileChannel, offset, count, closeOnComplete);
			return this;
		}

		/**
		 * Sets a byte-buffer-backed response body, or removes any current body if {@code byteBuffer} is {@code null}.
		 *
		 * @param byteBuffer the byte buffer to write, or {@code null} for no body
		 * @return this builder
		 */
		@NonNull
		public Builder body(@Nullable ByteBuffer byteBuffer) {
			if (byteBuffer == null)
				return withoutBody();

			this.body = new MarshaledResponseBody.ByteBuffer(byteBuffer);
			return this;
		}

		/**
		 * Sets a streaming response body, or removes any current stream if {@code stream} is {@code null}.
		 * <p>
		 * A response may have a known-length body or a stream, but not both. Setting a stream does not remove any
		 * current known-length body; call {@link #withoutBody()} first if replacing a known-length body with a stream.
		 * {@link #build()} rejects responses that still specify both.
		 *
		 * @param stream the streaming response body to write, or {@code null} for no stream
		 * @return this builder
		 */
		@NonNull
		public Builder stream(@Nullable StreamingResponseBody stream) {
			if (stream == null)
				return withoutStream();

			this.stream = stream;
			return this;
		}

		/**
		 * Removes the response body from this builder.
		 * <p>
		 * If the current body owns a caller-supplied {@link FileChannel}, the channel is closed before it is
		 * removed. Path-backed file bodies are lazy and do not hold open resources at this point.
		 *
		 * @return this builder
		 */
		@NonNull
		public Builder withoutBody() {
			releaseBodyResources(this.body);
			this.body = null;
			return this;
		}

		/**
		 * Removes the streaming response body from this builder.
		 *
		 * @return this builder
		 */
		@NonNull
		public Builder withoutStream() {
			this.stream = null;
			return this;
		}

		@NonNull
		public MarshaledResponse build() {
			return new MarshaledResponse(this);
		}
	}

	/**
	 * File-specific builder used by {@link MarshaledResponse#withFile(Path, Request)}.
	 * <p>
	 * Files are special among known-length response bodies because validators, byte ranges, and
	 * {@code HEAD} behavior depend on the current request and filesystem metadata. This builder produces
	 * the final {@link MarshaledResponse} directly from those inputs; for ordinary precomputed bytes,
	 * buffers, channels, or path-backed bodies without HTTP file semantics, use {@link Builder#body(byte[])},
	 * {@link Builder#body(ByteBuffer)}, {@link Builder#body(FileChannel, Long, Long, Boolean)}, or
	 * {@link Builder#body(Path)} instead.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class FileBuilder {
		private final FileResponse.@NonNull Builder builder;
		private final FileResponse.@NonNull RequestContext requestContext;

		private FileBuilder(@NonNull Path path,
												@NonNull Request request) {
			requireNonNull(path);
			requireNonNull(request);
			this.builder = FileResponse.withPath(path);
			this.requestContext = FileResponse.RequestContext.fromRequest(request);
		}

		@NonNull
		public FileBuilder contentType(@Nullable String contentType) {
			this.builder.contentType(contentType);
			return this;
		}

		@NonNull
		public FileBuilder entityTag(@Nullable EntityTag entityTag) {
			this.builder.entityTag(entityTag);
			return this;
		}

		@NonNull
		public FileBuilder lastModified(@Nullable Instant lastModified) {
			this.builder.lastModified(lastModified);
			return this;
		}

		@NonNull
		public FileBuilder cacheControl(@Nullable String cacheControl) {
			this.builder.cacheControl(cacheControl);
			return this;
		}

		@NonNull
		public FileBuilder headers(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
			this.builder.headers(headers);
			return this;
		}

		@NonNull
		public FileBuilder rangeRequests(@Nullable Boolean rangeRequests) {
			this.builder.rangeRequests(rangeRequests);
			return this;
		}

		@NonNull
		FileBuilder attributes(@Nullable BasicFileAttributes attributes) {
			this.builder.attributes(attributes);
			return this;
		}

		@NonNull
		public MarshaledResponse build() {
			return this.builder.build().marshaledResponseFor(this.requestContext);
		}
	}

	/**
	 * Builder used to copy instances of {@link MarshaledResponse} via {@link MarshaledResponse#copy()}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Copier {
		@NonNull
		private final Builder builder;

		Copier(@NonNull MarshaledResponse marshaledResponse) {
			requireNonNull(marshaledResponse);

			this.builder = new Builder(marshaledResponse.getStatusCode())
					.headers(new LinkedCaseInsensitiveMap<>(marshaledResponse.getHeaders()))
					.cookies(new LinkedHashSet<>(marshaledResponse.getCookies()));

			marshaledResponse.getBody().ifPresent(this.builder::body);
			marshaledResponse.getStream().ifPresent(this.builder::stream);
		}

		@NonNull
		public Copier statusCode(@NonNull Integer statusCode) {
			requireNonNull(statusCode);
			this.builder.statusCode(statusCode);
			return this;
		}

		@NonNull
		public Copier headers(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
			this.builder.headers(headers);
			return this;
		}

		// Convenience method for mutation
		@NonNull
		public Copier headers(@NonNull Consumer<Map<@NonNull String, @NonNull Set<@NonNull String>>> headersConsumer) {
			requireNonNull(headersConsumer);

			if (this.builder.headers == null)
				this.builder.headers(new LinkedCaseInsensitiveMap<>());

			headersConsumer.accept(this.builder.headers);
			return this;
		}

		@NonNull
		public Copier cookies(@Nullable Set<@NonNull ResponseCookie> cookies) {
			this.builder.cookies(cookies);
			return this;
		}

		// Convenience method for mutation
		@NonNull
		public Copier cookies(@NonNull Consumer<Set<@NonNull ResponseCookie>> cookiesConsumer) {
			requireNonNull(cookiesConsumer);

			if (this.builder.cookies == null)
				this.builder.cookies(new LinkedHashSet<>());

			cookiesConsumer.accept(this.builder.cookies);
			return this;
		}

		@NonNull
		public Copier body(@Nullable byte[] bytes) {
			this.builder.body(bytes);
			return this;
		}

		@NonNull
		public Copier body(@Nullable MarshaledResponseBody body) {
			this.builder.body(body);
			return this;
		}

		@NonNull
		public Copier body(@Nullable Path path) {
			this.builder.body(path);
			return this;
		}

		@NonNull
		public Copier body(@NonNull Path path, @NonNull Long offset, @NonNull Long count) {
			this.builder.body(path, offset, count);
			return this;
		}

		@NonNull
		public Copier body(@NonNull FileChannel fileChannel,
											 @NonNull Long offset,
											 @NonNull Long count,
											 @NonNull Boolean closeOnComplete) {
			this.builder.body(fileChannel, offset, count, closeOnComplete);
			return this;
		}

		@NonNull
		public Copier body(@Nullable ByteBuffer byteBuffer) {
			this.builder.body(byteBuffer);
			return this;
		}

		@NonNull
		public Copier stream(@Nullable StreamingResponseBody stream) {
			this.builder.stream(stream);
			return this;
		}

		/**
		 * Removes the response body from this copier.
		 * <p>
		 * If the current body owns a caller-supplied {@link FileChannel}, the channel is closed before it is
		 * removed. Path-backed file bodies are lazy and do not hold open resources at this point.
		 *
		 * @return this copier
		 */
		@NonNull
		public Copier withoutBody() {
			this.builder.withoutBody();
			return this;
		}

		/**
		 * Removes the streaming response body from this copier.
		 *
		 * @return this copier
		 */
		@NonNull
		public Copier withoutStream() {
			this.builder.withoutStream();
			return this;
		}

		@NonNull
		public MarshaledResponse finish() {
			return this.builder.build();
		}
	}

	private static MarshaledResponseBody.File fileBody(@NonNull Path path) {
		Long size = fileSize(path);
		return new MarshaledResponseBody.File(path, 0L, size);
	}

	private static MarshaledResponseBody.File fileBody(@NonNull Path path, @NonNull Long offset, @NonNull Long count) {
		Long size = fileSize(path);
		validateRangeWithinLength(offset, count, size);
		return new MarshaledResponseBody.File(path, offset, count);
	}

	private static MarshaledResponseBody.FileChannel fileChannelBody(@NonNull FileChannel fileChannel,
																																	 @NonNull Long offset,
																																	 @NonNull Long count,
																																	 @NonNull Boolean closeOnComplete) {
		requireNonNull(fileChannel);
		requireNonNull(closeOnComplete);
		validateRangeWithinLength(offset, count, fileChannelSize(fileChannel));
		return new MarshaledResponseBody.FileChannel(fileChannel, offset, count, closeOnComplete);
	}

	private static void releaseBodyResources(@Nullable MarshaledResponseBody body) {
		if (!(body instanceof MarshaledResponseBody.FileChannel fileChannelBody) || !fileChannelBody.getCloseOnComplete())
			return;

		try {
			fileChannelBody.getChannel().close();
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to close file-channel response body.", e);
		}
	}

	@NonNull
	private static Long fileSize(@NonNull Path path) {
		requireNonNull(path);

		if (!Files.isRegularFile(path))
			throw new IllegalArgumentException(format("File body path must reference a regular file: %s", path));

		if (!Files.isReadable(path))
			throw new IllegalArgumentException(format("File body path must be readable: %s", path));

		try {
			return Files.size(path);
		} catch (IOException e) {
			throw new UncheckedIOException(format("Unable to determine file body length for path: %s", path), e);
		}
	}

	@NonNull
	private static Long fileChannelSize(@NonNull FileChannel fileChannel) {
		requireNonNull(fileChannel);

		try {
			return fileChannel.size();
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to determine file-channel body length.", e);
		}
	}

	private static void validateRangeWithinLength(@NonNull Long offset, @NonNull Long count, @NonNull Long length) {
		requireNonNull(offset);
		requireNonNull(count);
		requireNonNull(length);

		if (offset < 0)
			throw new IllegalArgumentException("Offset must be >= 0.");

		if (count < 0)
			throw new IllegalArgumentException("Count must be >= 0.");

		if (Long.MAX_VALUE - offset < count)
			throw new IllegalArgumentException("Offset plus count exceeds maximum supported file position.");

		if (offset + count > length)
			throw new IllegalArgumentException(format("Offset plus count must be <= body length %d.", length));
	}

	private static boolean isBodylessStatusCode(@NonNull Integer statusCode) {
		requireNonNull(statusCode);
		return (statusCode >= 100 && statusCode < 200) || statusCode == 204 || statusCode == 304;
	}

	@NonNull
	private static byte[] materializeFile(@NonNull Path path, @NonNull Long offset, @NonNull Long count) {
		try (FileChannel fileChannel = FileChannel.open(path, READ)) {
			return materializeFileChannel(fileChannel, offset, count, false);
		} catch (IOException e) {
			throw new UncheckedIOException(format("Unable to read file response body: %s", path), e);
		}
	}

	@NonNull
	private static byte[] materializeFileChannel(@NonNull FileChannel fileChannel,
																							 @NonNull Long offset,
																							 @NonNull Long count,
																							 @NonNull Boolean closeOnComplete) {
		requireNonNull(fileChannel);
		requireNonNull(offset);
		requireNonNull(count);
		requireNonNull(closeOnComplete);

		if (count > Integer.MAX_VALUE)
			throw new IllegalStateException("Response body is too large to materialize as a byte array.");

		byte[] bytes = new byte[Math.toIntExact(count)];
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		long position = offset;

		try {
			while (buffer.hasRemaining()) {
				int read = fileChannel.read(buffer, position);
				if (read < 0)
					throw new EOFException("File ended before the expected response body length was read.");
				if (read == 0)
					throw new EOFException("File did not provide the expected response body length.");
				position += read;
			}
			return bytes;
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to materialize file-channel response body.", e);
		} finally {
			if (closeOnComplete) {
				try {
					fileChannel.close();
				} catch (IOException e) {
					throw new UncheckedIOException("Unable to close file-channel response body.", e);
				}
			}
		}
	}

	@NonNull
	private static byte[] materializeByteBuffer(@NonNull ByteBuffer byteBuffer) {
		requireNonNull(byteBuffer);

		if (byteBuffer.remaining() > Integer.MAX_VALUE)
			throw new IllegalStateException("Response body is too large to materialize as a byte array.");

		ByteBuffer duplicate = byteBuffer.asReadOnlyBuffer();
		byte[] bytes = new byte[duplicate.remaining()];
		duplicate.get(bytes);
		return bytes;
	}
}
