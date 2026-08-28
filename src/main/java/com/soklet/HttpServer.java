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
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Contract for HTTP server implementations that are designed to be managed by a {@link com.soklet.Soklet} instance.
 * <p>
 * <strong>Most Soklet applications will use the default {@link HttpServer} (constructed via the {@link #withPort(Integer)} builder factory method) and therefore do not need to implement this interface directly.</strong>
 * <p>
 * For example:
 * <pre>{@code  SokletConfig config = SokletConfig.withHttpServer(
 *   HttpServer.fromPort(8080)
 * ).build();
 *
 * System.out.println("Soklet starting; press [enter] to exit");
 * SokletApplication.run(config, ShutdownTrigger.ENTER_KEY);}</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface HttpServer {
	/**
	 * Acquires the stable identity for this server's complete lifecycle graph.
	 * A decorator must return its delegate's exact identity object unchanged.
	 *
	 * @return the stable transport identity
	 */
	@NonNull
	TransportIdentity getTransportIdentity();

	/**
	 * Attaches this server to one Soklet lifecycle. Attachment is invoked once,
	 * remains in-memory and side-effect-free, and returns the runtime that owns
	 * binding and termination.
	 *
	 * @param attachmentContext framework-owned composition and termination context
	 * @param startupContext startup timing and cancellation information
	 * @return this server's one-shot runtime
	 */
	@NonNull
	TransportRuntime attach(@NonNull HttpTransportAttachmentContext attachmentContext,
			@NonNull StartupContext startupContext);

	/**
	 * Request/response processing contract for {@link HttpServer} implementations.
	 * <p>
	 * This is used internally by {@link com.soklet.Soklet} instances to "talk" to a {@link HttpServer}. It's the responsibility of the {@link HttpServer} to implement HTTP mechanics: read bytes from the request, write bytes to the response, and so forth.
	 * <p>
	 * <strong>Most Soklet applications will use Soklet's default {@link HttpServer} implementation and therefore do not need to implement this interface directly.</strong>
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@FunctionalInterface
	interface RequestHandler {
		/**
		 * Callback to be invoked by a {@link HttpServer} implementation after it has received an HTTP request but prior to writing an HTTP response.
		 * <p>
		 * The {@link HttpServer} is responsible for converting its internal request representation into a {@link Request}, which a {@link com.soklet.Soklet} instance consumes and performs Soklet application request processing logic.
		 * <p>
		 * The {@link com.soklet.Soklet} instance will generate a {@link MarshaledResponse} for the request, which it "hands back" to the {@link HttpServer} to be sent over the wire to the client.
		 *
		 * @param request               a Soklet {@link Request} representation of the {@link HttpServer}'s internal HTTP request data
		 * @param requestResultConsumer invoked by {@link com.soklet.Soklet} when it's time for the {@link HttpServer} to write HTTP response data to the client
		 */
		void handleRequest(@NonNull Request request,
											 @NonNull Consumer<HttpRequestResult> requestResultConsumer);
	}

	/**
	 * Acquires a builder for {@link HttpServer} instances.
	 *
	 * @param port the port number on which the server should listen
	 * @return the builder
	 */
	@NonNull
	static Builder withPort(@NonNull Integer port) {
		requireNonNull(port);
		return new Builder(port);
	}

	/**
	 * Creates a {@link HttpServer} configured with the given port and default settings.
	 *
	 * @param port the port number on which the server should listen
	 * @return a {@link HttpServer} instance
	 */
	@NonNull
	static HttpServer fromPort(@NonNull Integer port) {
		return withPort(port).build();
	}

	/**
	 * Builder used to construct a standard implementation of {@link HttpServer}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	final class Builder {
		@NonNull
		Integer port;
		@Nullable
		String host;
		@Nullable
		Integer concurrency;
		@Nullable
		Duration requestHeaderTimeout;
		@Nullable
		Duration requestBodyTimeout;
		@Nullable
		Duration responseWriteIdleTimeout;
		@Nullable
		ResponseGzipPolicy responseGzipPolicy;
		@Nullable
		RequestDecompressionPolicy requestDecompressionPolicy;
		@Nullable
		Duration requestHandlerTimeout;
		@Nullable
		Integer requestHandlerConcurrency;
		@Nullable
		Integer requestHandlerQueueCapacity;
		@Nullable
		Duration socketSelectTimeout;
		@Nullable
		Integer maximumRequestSizeInBytes;
		@Nullable
		Integer maximumHeaderCount;
		@Nullable
		Integer maximumHeadersSizeInBytes;
		@Nullable
		Integer maximumRequestTargetLengthInBytes;
		@Nullable
		Integer requestReadBufferSizeInBytes;
		@Nullable
		Integer socketPendingConnectionLimit;
		@Nullable
		Integer concurrentConnectionLimit;
		@Nullable
		MultipartParser multipartParser;
		@Nullable
		Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
		@Nullable
		Supplier<ExecutorService> streamingExecutorServiceSupplier;
		@Nullable
		Integer streamingQueueCapacityInBytes;
		@Nullable
		Integer streamingChunkSizeInBytes;
		@Nullable
		Duration streamingResponseTimeout;
		@Nullable
		Duration streamingResponseIdleTimeout;
		@Nullable
		IdGenerator<?> idGenerator;

		private Builder(@NonNull Integer port) {
			requireNonNull(port);
			this.port = port;
		}

		@NonNull
		public Builder port(@NonNull Integer port) {
			requireNonNull(port);
			this.port = port;
			return this;
		}

		@NonNull
		public Builder host(@Nullable String host) {
			this.host = host;
			return this;
		}

		@NonNull
		public Builder concurrency(@Nullable Integer concurrency) {
			this.concurrency = concurrency;
			return this;
		}

		/**
		 * Sets the maximum duration for reading the HTTP request line and headers.
		 * <p>
		 * If this value is not specified, Soklet uses the server default.
		 *
		 * @param requestHeaderTimeout the request header timeout, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder requestHeaderTimeout(@Nullable Duration requestHeaderTimeout) {
			this.requestHeaderTimeout = requestHeaderTimeout;
			return this;
		}

		/**
		 * Sets the maximum duration for reading the HTTP request body after the request
		 * line and headers have been received.
		 * <p>
		 * If this value is not specified, Soklet uses the server default.
		 *
		 * @param requestBodyTimeout the request body timeout, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder requestBodyTimeout(@Nullable Duration requestBodyTimeout) {
			this.requestBodyTimeout = requestBodyTimeout;
			return this;
		}

		/**
		 * Sets the maximum idle duration while writing a non-streaming HTTP response.
		 * <p>
		 * The timeout is reset each time response bytes are written to the socket.
		 * Use {@link Duration#ZERO} to disable this timeout.
		 * <p>
		 * If this value is not specified, Soklet uses the server default.
		 *
		 * @param responseWriteIdleTimeout the response write idle timeout, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder responseWriteIdleTimeout(@Nullable Duration responseWriteIdleTimeout) {
			this.responseWriteIdleTimeout = responseWriteIdleTimeout;
			return this;
		}

		/**
		 * Sets the policy used by the standard HTTP server to decide whether eligible finalized
		 * in-memory responses should be gzipped.
		 * <p>
		 * Soklet invokes this policy only after its own HTTP protocol checks pass. For example,
		 * {@code Accept-Encoding} must permit {@code gzip}, and Soklet will skip streaming, file,
		 * range, already-encoded, transfer-encoded, bodyless, and otherwise ineligible responses.
		 * If this value is not specified, response gzip is disabled.
		 *
		 * @param responseGzipPolicy the response gzip policy to use, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder responseGzipPolicy(@Nullable ResponseGzipPolicy responseGzipPolicy) {
			this.responseGzipPolicy = responseGzipPolicy;
			return this;
		}

		/**
		 * Sets the policy used by the standard HTTP server to decide whether and how gzip-compressed
		 * request bodies are transparently decompressed before request handling.
		 * <p>
		 * If this value is not specified, request decompression is disabled and request bodies are passed
		 * to handlers exactly as received. See {@link RequestDecompressionPolicy} for enabled-mode behavior,
		 * including decompression-bomb limits and rejection status codes.
		 *
		 * @param requestDecompressionPolicy the request decompression policy to use, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder requestDecompressionPolicy(@Nullable RequestDecompressionPolicy requestDecompressionPolicy) {
			this.requestDecompressionPolicy = requestDecompressionPolicy;
			return this;
		}

		@NonNull
		public Builder requestHandlerTimeout(@Nullable Duration requestHandlerTimeout) {
			this.requestHandlerTimeout = requestHandlerTimeout;
			return this;
		}

		@NonNull
		public Builder requestHandlerConcurrency(@Nullable Integer requestHandlerConcurrency) {
			this.requestHandlerConcurrency = requestHandlerConcurrency;
			return this;
		}

		@NonNull
		public Builder requestHandlerQueueCapacity(@Nullable Integer requestHandlerQueueCapacity) {
			this.requestHandlerQueueCapacity = requestHandlerQueueCapacity;
			return this;
		}

		@NonNull
		public Builder socketSelectTimeout(@Nullable Duration socketSelectTimeout) {
			this.socketSelectTimeout = socketSelectTimeout;
			return this;
		}

		@NonNull
		public Builder socketPendingConnectionLimit(@Nullable Integer socketPendingConnectionLimit) {
			this.socketPendingConnectionLimit = socketPendingConnectionLimit;
			return this;
		}

		@NonNull
		public Builder concurrentConnectionLimit(@Nullable Integer concurrentConnectionLimit) {
			this.concurrentConnectionLimit = concurrentConnectionLimit;
			return this;
		}

		/**
		 * Sets the maximum accepted HTTP request size in bytes.
		 * <p>
		 * This limit applies to the whole received HTTP request, including request line,
		 * headers, transfer framing, and body bytes. Applications that think in terms of
		 * payload size should leave room for request metadata and protocol framing.
		 *
		 * @param maximumRequestSizeInBytes the maximum request size, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumRequestSizeInBytes(@Nullable Integer maximumRequestSizeInBytes) {
			this.maximumRequestSizeInBytes = maximumRequestSizeInBytes;
			return this;
		}

		/**
		 * Sets the maximum number of HTTP header fields accepted in one request.
		 *
		 * @param maximumHeaderCount the maximum header count, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumHeaderCount(@Nullable Integer maximumHeaderCount) {
			this.maximumHeaderCount = maximumHeaderCount;
			return this;
		}

		/**
		 * Sets the maximum accepted HTTP header-section size in bytes.
		 * <p>
		 * This limit applies to the header bytes after the request line, including
		 * header-field line endings and the terminating blank line.
		 *
		 * @param maximumHeadersSizeInBytes the maximum headers size, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumHeadersSizeInBytes(@Nullable Integer maximumHeadersSizeInBytes) {
			this.maximumHeadersSizeInBytes = maximumHeadersSizeInBytes;
			return this;
		}

		/**
		 * Sets the maximum request-target length accepted in bytes.
		 *
		 * @param maximumRequestTargetLengthInBytes the maximum request-target length, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumRequestTargetLengthInBytes(@Nullable Integer maximumRequestTargetLengthInBytes) {
			this.maximumRequestTargetLengthInBytes = maximumRequestTargetLengthInBytes;
			return this;
		}

		@NonNull
		public Builder requestReadBufferSizeInBytes(@Nullable Integer requestReadBufferSizeInBytes) {
			this.requestReadBufferSizeInBytes = requestReadBufferSizeInBytes;
			return this;
		}

		@NonNull
		public Builder multipartParser(@Nullable MultipartParser multipartParser) {
			this.multipartParser = multipartParser;
			return this;
		}

		@NonNull
		public Builder requestHandlerExecutorServiceSupplier(@Nullable Supplier<ExecutorService> requestHandlerExecutorServiceSupplier) {
			this.requestHandlerExecutorServiceSupplier = requestHandlerExecutorServiceSupplier;
			return this;
		}

		/**
		 * Sets the executor service supplier used to run streaming response producers.
		 *
		 * @param streamingExecutorServiceSupplier the executor service supplier, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder streamingExecutorServiceSupplier(@Nullable Supplier<ExecutorService> streamingExecutorServiceSupplier) {
			this.streamingExecutorServiceSupplier = streamingExecutorServiceSupplier;
			return this;
		}

		/**
		 * Sets the per-stream producer queue capacity in bytes.
		 *
		 * @param streamingQueueCapacityInBytes the queue capacity, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder streamingQueueCapacityInBytes(@Nullable Integer streamingQueueCapacityInBytes) {
			this.streamingQueueCapacityInBytes = streamingQueueCapacityInBytes;
			return this;
		}

		/**
		 * Sets the maximum payload chunk size used for HTTP/1.1 chunked streaming.
		 *
		 * @param streamingChunkSizeInBytes the payload chunk size, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder streamingChunkSizeInBytes(@Nullable Integer streamingChunkSizeInBytes) {
			this.streamingChunkSizeInBytes = streamingChunkSizeInBytes;
			return this;
		}

		/**
		 * Sets the maximum total duration for a streaming response.
		 * <p>
		 * Use {@link Duration#ZERO} to disable the timeout.
		 *
		 * @param streamingResponseTimeout the streaming response timeout, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder streamingResponseTimeout(@Nullable Duration streamingResponseTimeout) {
			this.streamingResponseTimeout = streamingResponseTimeout;
			return this;
		}

		/**
		 * Sets the maximum idle duration between bytes produced for a streaming response.
		 * <p>
		 * Use {@link Duration#ZERO} to disable the timeout.
		 *
		 * @param streamingResponseIdleTimeout the streaming response idle timeout, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder streamingResponseIdleTimeout(@Nullable Duration streamingResponseIdleTimeout) {
			this.streamingResponseIdleTimeout = streamingResponseIdleTimeout;
			return this;
		}

		@NonNull
		public Builder idGenerator(@Nullable IdGenerator<?> idGenerator) {
			this.idGenerator = idGenerator;
			return this;
		}

		@NonNull
		public HttpServer build() {
			return new DefaultHttpServer(this);
		}
	}
}
