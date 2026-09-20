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

import com.soklet.exception.IllegalRequestException;
import com.soklet.internal.microhttp.ConnectionListener;
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.Handler;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.LogEntry;
import com.soklet.internal.microhttp.Logger;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.Options;
import com.soklet.internal.microhttp.OptionsBuilder;
import com.soklet.internal.microhttp.StreamingMicrohttpResponses;
import com.soklet.internal.microhttp.UnparsedRequestRejection;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.IdentityHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

import static com.soklet.internal.ObjectIdentity.sameInstance;
import static com.soklet.Utilities.emptyByteArray;
import static com.soklet.Utilities.trimAggressivelyToEmpty;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultHttpServer implements HttpServer {
	@NonNull
	private static final String DEFAULT_HOST;
	@NonNull
	private static final Integer DEFAULT_CONCURRENCY;
	@NonNull
	private static final Duration DEFAULT_REQUEST_HEADER_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_REQUEST_BODY_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_RESPONSE_WRITE_IDLE_TIMEOUT;
	@NonNull
	private static final ResponseCompressor DEFAULT_RESPONSE_COMPRESSOR;
	@NonNull
	private static final RequestDecompressionPolicy DEFAULT_REQUEST_DECOMPRESSION_POLICY;
	@NonNull
	private static final Duration DEFAULT_REQUEST_HANDLER_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_SOCKET_SELECT_TIMEOUT;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_HEADER_COUNT;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_HEADERS_SIZE_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_TARGET_LENGTH_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT;
	@NonNull
	private static final Integer DEFAULT_CONCURRENT_CONNECTION_LIMIT;
	@NonNull
	private static final Integer DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY_MULTIPLIER;
	@NonNull
	private static final Integer DEFAULT_VIRTUAL_REQUEST_HANDLER_CONCURRENCY_MULTIPLIER;
	@NonNull
	private static final Integer DEFAULT_STREAMING_QUEUE_CAPACITY_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_STREAMING_CHUNK_SIZE_IN_BYTES;
	@NonNull
	private static final Duration DEFAULT_STREAMING_RESPONSE_TIMEOUT;
	@NonNull
	private static final Integer DEFAULT_NONVIRTUAL_STREAMING_CONCURRENCY_MULTIPLIER;
	private static final Integer UNPARSED_REQUEST_CAPTURE_LIMIT_IN_BYTES;
	private static final Integer UNPARSED_RESPONSE_SIZE_LIMIT_IN_BYTES;

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_CONCURRENCY = Runtime.getRuntime().availableProcessors();
		DEFAULT_REQUEST_HEADER_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_REQUEST_BODY_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_RESPONSE_WRITE_IDLE_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_RESPONSE_COMPRESSOR = ResponseCompressor.disabledInstance();
		DEFAULT_REQUEST_DECOMPRESSION_POLICY = RequestDecompressionPolicy.disabledInstance();
		DEFAULT_REQUEST_HANDLER_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_SOCKET_SELECT_TIMEOUT = Duration.ofMillis(100);
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024 * 10;
		DEFAULT_MAXIMUM_HEADER_COUNT = 100;
		DEFAULT_MAXIMUM_HEADERS_SIZE_IN_BYTES = 64 * 1_024;
		DEFAULT_MAXIMUM_REQUEST_TARGET_LENGTH_IN_BYTES = 8_192;
		DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES = 1_024 * 64;
		DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT = 0;
		DEFAULT_CONCURRENT_CONNECTION_LIMIT = 8_192;
		DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY_MULTIPLIER = 64;
		DEFAULT_VIRTUAL_REQUEST_HANDLER_CONCURRENCY_MULTIPLIER = 16;
		DEFAULT_STREAMING_QUEUE_CAPACITY_IN_BYTES = 1_024 * 1_024;
		DEFAULT_STREAMING_CHUNK_SIZE_IN_BYTES = 1_024 * 16;
		DEFAULT_STREAMING_RESPONSE_TIMEOUT = Duration.ZERO;
		DEFAULT_NONVIRTUAL_STREAMING_CONCURRENCY_MULTIPLIER = 4;
		UNPARSED_REQUEST_CAPTURE_LIMIT_IN_BYTES = 64 * 1_024;
		UNPARSED_RESPONSE_SIZE_LIMIT_IN_BYTES = 64 * 1_024;
	}

	@NonNull
	private final Integer port;
	@NonNull
	private final String host;
	@NonNull
	private final Integer concurrency;
	@NonNull
	private final Duration requestHeaderTimeout;
	@NonNull
	private final Duration requestBodyTimeout;
	@NonNull
	private final Duration responseWriteIdleTimeout;
	@NonNull
	private final ResponseCompressor responseCompressor;
	@NonNull
	private final RequestDecompressionPolicy requestDecompressionPolicy;
	@NonNull
	private final Duration requestHandlerTimeout;
	@NonNull
	private final Integer requestHandlerConcurrency;
	@NonNull
	private final Integer requestHandlerQueueCapacity;
	@NonNull
	private final Duration socketSelectTimeout;
	@NonNull
	private final Integer maximumRequestSizeInBytes;
	@NonNull
	private final Integer maximumRequestBodySizeInBytes;
	@NonNull
	private final Integer maximumHeaderCount;
	@NonNull
	private final Integer maximumHeadersSizeInBytes;
	@NonNull
	private final Integer maximumRequestTargetLengthInBytes;
	@NonNull
	private final Integer requestReadBufferSizeInBytes;
	@NonNull
	private final Integer socketPendingConnectionLimit;
	@NonNull
	private final Integer concurrentConnectionLimit;
	@NonNull
	private final MultipartParser multipartParser;
	@NonNull
	private final IdGenerator<?> idGenerator;
	@NonNull
	private final ReentrantLock lock;
	@NonNull
	private final Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
	@NonNull
	private final Supplier<ExecutorService> streamingExecutorServiceSupplier;
	@NonNull
	private final BuiltInTransportLifecycleAdapter lifecycleAdapter;
	@NonNull
	private volatile Runnable startSetupHook = () -> {};
	@NonNull
	private final Integer streamingQueueCapacityInBytes;
	@NonNull
	private final Integer streamingChunkSizeInBytes;
	@NonNull
	private final Duration streamingResponseTimeout;
	@NonNull
	private final Duration streamingResponseIdleTimeout;
	@Nullable
	private volatile ExecutorService requestHandlerExecutorService;
	@Nullable
	private volatile ExecutorService streamingExecutorService;
	@Nullable
	private volatile ScheduledExecutorService streamingTimeoutExecutorService;
	@Nullable
	private volatile AtomicBoolean streamingForcedShutdownStarted;
	@Nullable
	private volatile TimeoutScheduler requestHandlerTimeoutScheduler;
	@Nullable
	private volatile RequestHandler requestHandler;
	@NonNull
	private volatile LifecycleObserver lifecycleObserver = LifecycleObserver.defaultInstance();
	@NonNull
	private volatile LifecyclePolicy lifecyclePolicy = LifecyclePolicy.fromDefaults();
	@Nullable
	private volatile MetricsCollector metricsCollector;
	@Nullable
	private volatile ResponseMarshaler responseMarshaler;
	@Nullable
	private volatile EventLoop eventLoop;

	DefaultHttpServer(@NonNull Builder builder) {
		requireNonNull(builder);

		this.lock = new ReentrantLock();

		this.port = builder.port;
		this.host = builder.host != null ? builder.host : DEFAULT_HOST;
		this.concurrency = builder.concurrency != null ? builder.concurrency : DEFAULT_CONCURRENCY;
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.maximumRequestBodySizeInBytes = builder.maximumRequestBodySizeInBytes != null
				? builder.maximumRequestBodySizeInBytes : this.maximumRequestSizeInBytes;
		this.maximumHeaderCount = builder.maximumHeaderCount != null ? builder.maximumHeaderCount : DEFAULT_MAXIMUM_HEADER_COUNT;
		this.maximumHeadersSizeInBytes = builder.maximumHeadersSizeInBytes != null ? builder.maximumHeadersSizeInBytes : DEFAULT_MAXIMUM_HEADERS_SIZE_IN_BYTES;
		this.maximumRequestTargetLengthInBytes = builder.maximumRequestTargetLengthInBytes != null ? builder.maximumRequestTargetLengthInBytes : DEFAULT_MAXIMUM_REQUEST_TARGET_LENGTH_IN_BYTES;
		this.requestReadBufferSizeInBytes = builder.requestReadBufferSizeInBytes != null ? builder.requestReadBufferSizeInBytes : DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
		this.requestHeaderTimeout = builder.requestHeaderTimeout != null ? builder.requestHeaderTimeout : DEFAULT_REQUEST_HEADER_TIMEOUT;
		this.requestBodyTimeout = builder.requestBodyTimeout != null ? builder.requestBodyTimeout : DEFAULT_REQUEST_BODY_TIMEOUT;
		this.responseWriteIdleTimeout = builder.responseWriteIdleTimeout != null ? builder.responseWriteIdleTimeout : DEFAULT_RESPONSE_WRITE_IDLE_TIMEOUT;
		this.responseCompressor = builder.responseCompressor != null ? builder.responseCompressor : DEFAULT_RESPONSE_COMPRESSOR;
		this.requestDecompressionPolicy = builder.requestDecompressionPolicy != null ? builder.requestDecompressionPolicy : DEFAULT_REQUEST_DECOMPRESSION_POLICY;
		this.requestHandlerTimeout = builder.requestHandlerTimeout != null ? builder.requestHandlerTimeout : DEFAULT_REQUEST_HANDLER_TIMEOUT;
		this.socketSelectTimeout = builder.socketSelectTimeout != null ? builder.socketSelectTimeout : DEFAULT_SOCKET_SELECT_TIMEOUT;
		this.socketPendingConnectionLimit = builder.socketPendingConnectionLimit != null ? builder.socketPendingConnectionLimit : DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT;
		this.concurrentConnectionLimit = builder.concurrentConnectionLimit != null ? builder.concurrentConnectionLimit : DEFAULT_CONCURRENT_CONNECTION_LIMIT;
		this.multipartParser = builder.multipartParser != null ? builder.multipartParser : DefaultMultipartParser.defaultInstance();
		this.idGenerator = builder.idGenerator != null ? builder.idGenerator : IdGenerator.defaultInstance();

		if (this.port < 0 || this.port > 65_535)
			throw new IllegalArgumentException("Port must be between 0 and 65535");

		if (this.concurrency < 1)
			throw new IllegalArgumentException("Concurrency must be > 0");

		if (this.requestReadBufferSizeInBytes < 1)
			throw new IllegalArgumentException("Request read buffer size must be > 0");

		if (this.socketSelectTimeout.isNegative() || this.socketSelectTimeout.isZero())
			throw new IllegalArgumentException("Socket select timeout must be > 0");

		int defaultRequestHandlerConcurrency = Utilities.virtualThreadsAvailable()
				? Math.max(1, this.concurrency * DEFAULT_VIRTUAL_REQUEST_HANDLER_CONCURRENCY_MULTIPLIER)
				: Math.max(1, this.concurrency);

		this.requestHandlerConcurrency = builder.requestHandlerConcurrency != null
				? builder.requestHandlerConcurrency
				: defaultRequestHandlerConcurrency;

		if (this.requestHandlerConcurrency < 1)
			throw new IllegalArgumentException("Request handler concurrency must be > 0");

		this.requestHandlerQueueCapacity = builder.requestHandlerQueueCapacity != null
				? builder.requestHandlerQueueCapacity
				: Math.max(1, this.requestHandlerConcurrency * DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY_MULTIPLIER);

		if (this.requestHandlerQueueCapacity < 1)
			throw new IllegalArgumentException("Request handler queue capacity must be > 0");

		if (this.maximumRequestSizeInBytes < 1)
			throw new IllegalArgumentException("Maximum request size must be > 0");

		if (this.maximumRequestBodySizeInBytes < 1)
			throw new IllegalArgumentException("Maximum request body size must be > 0");

		if (this.maximumRequestBodySizeInBytes > this.maximumRequestSizeInBytes)
			throw new IllegalArgumentException("Maximum request body size must not exceed maximum request size");

		if (this.maximumHeaderCount < 1)
			throw new IllegalArgumentException("Maximum header count must be > 0");

		if (this.maximumHeadersSizeInBytes < 1)
			throw new IllegalArgumentException("Maximum headers size must be > 0");

		if (this.maximumRequestTargetLengthInBytes < 1)
			throw new IllegalArgumentException("Maximum request target length must be > 0");

		if (this.requestHeaderTimeout.isNegative() || this.requestHeaderTimeout.isZero())
			throw new IllegalArgumentException("Request header timeout must be > 0");

		if (this.requestBodyTimeout.isNegative() || this.requestBodyTimeout.isZero())
			throw new IllegalArgumentException("Request body timeout must be > 0");

		if (this.responseWriteIdleTimeout.isNegative())
			throw new IllegalArgumentException("Response write idle timeout must be >= 0");

		this.requestHandlerExecutorServiceSupplier = builder.requestHandlerExecutorServiceSupplier != null ? builder.requestHandlerExecutorServiceSupplier : () -> {
			String threadNamePrefix = "request-handler-";
			int threadPoolSize = getRequestHandlerConcurrency();
			int queueCapacity = getRequestHandlerQueueCapacity();

			if (Utilities.virtualThreadsAvailable()) {
				ThreadFactory threadFactory = Utilities.createVirtualThreadFactory(threadNamePrefix, (Thread thread, Throwable throwable) -> {
					safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Unexpected exception occurred during server HTTP request processing")
							.throwable(throwable)
							.build());
				});

				return new ThreadPoolExecutor(
						threadPoolSize,
						threadPoolSize,
						0L,
						TimeUnit.MILLISECONDS,
						new ArrayBlockingQueue<>(queueCapacity),
						threadFactory);
			}

			return new ThreadPoolExecutor(
					threadPoolSize,
					threadPoolSize,
					0L,
					TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(queueCapacity),
					new NonvirtualThreadFactory(threadNamePrefix));
		};

		this.streamingQueueCapacityInBytes = builder.streamingQueueCapacityInBytes != null
				? builder.streamingQueueCapacityInBytes
				: DEFAULT_STREAMING_QUEUE_CAPACITY_IN_BYTES;

		if (this.streamingQueueCapacityInBytes < 1)
			throw new IllegalArgumentException("Streaming queue capacity must be > 0");

		this.streamingChunkSizeInBytes = builder.streamingChunkSizeInBytes != null
				? builder.streamingChunkSizeInBytes
				: DEFAULT_STREAMING_CHUNK_SIZE_IN_BYTES;

		if (this.streamingChunkSizeInBytes < 1)
			throw new IllegalArgumentException("Streaming chunk size must be > 0");

		this.streamingResponseTimeout = builder.streamingResponseTimeout != null
				? builder.streamingResponseTimeout
				: DEFAULT_STREAMING_RESPONSE_TIMEOUT;

		if (this.streamingResponseTimeout.isNegative())
			throw new IllegalArgumentException("Streaming response timeout must be >= 0");

		this.streamingResponseIdleTimeout = builder.streamingResponseIdleTimeout != null
				? builder.streamingResponseIdleTimeout
				: this.requestBodyTimeout;

		if (this.streamingResponseIdleTimeout.isNegative())
			throw new IllegalArgumentException("Streaming response idle timeout must be >= 0");

		this.streamingExecutorServiceSupplier = builder.streamingExecutorServiceSupplier != null ? builder.streamingExecutorServiceSupplier : () -> {
			String threadNamePrefix = "streaming-";
			int threadPoolSize = Utilities.virtualThreadsAvailable()
					? Math.max(1, getConcurrency() * DEFAULT_VIRTUAL_REQUEST_HANDLER_CONCURRENCY_MULTIPLIER)
					: Math.max(1, getConcurrency() * DEFAULT_NONVIRTUAL_STREAMING_CONCURRENCY_MULTIPLIER);
			int queueCapacity = Math.max(1, threadPoolSize * DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY_MULTIPLIER);

			if (Utilities.virtualThreadsAvailable()) {
				ThreadFactory threadFactory = Utilities.createVirtualThreadFactory(threadNamePrefix, (Thread thread, Throwable throwable) -> {
					safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Unexpected exception occurred during server streaming response processing")
							.throwable(throwable)
							.build());
				});

				return new ThreadPoolExecutor(
						threadPoolSize,
						threadPoolSize,
						0L,
						TimeUnit.MILLISECONDS,
						new ArrayBlockingQueue<>(queueCapacity),
						threadFactory);
			}

			return new ThreadPoolExecutor(
					threadPoolSize,
					threadPoolSize,
					0L,
					TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(queueCapacity),
					new NonvirtualThreadFactory(threadNamePrefix));
		};

		if (this.requestHandlerTimeout.isNegative() || this.requestHandlerTimeout.isZero())
			throw new IllegalArgumentException("Request handler timeout must be > 0");

		if (this.concurrentConnectionLimit < 0)
			throw new IllegalArgumentException("Concurrent connection limit must be >= 0");

		this.lifecycleAdapter = new BuiltInTransportLifecycleAdapter(
				InternalLifecycleComponentType.HTTP, new HttpLifecycleOperations(),
				this::getGracefulShutdownTimeout,
				this::getForcedShutdownTimeout);
	}

	@NonNull
	@Override
	public TransportIdentity getTransportIdentity() {
		return getLifecycleAdapter().identity().publicIdentity();
	}

	@NonNull
	@Override
	public TransportRuntime attach(
			@NonNull HttpTransportAttachmentContext attachmentContext,
			@NonNull StartupContext startupContext) {
		requireNonNull(startupContext);
		HttpTransportAttachmentContext exactContext = requireNonNull(
				attachmentContext);
		initialize(exactContext.getSokletConfig(),
				exactContext.getAdmissionFencedRequestHandler());
		return getLifecycleAdapter().delegatedRuntime(
				exactContext.getTransportTerminationSignal(), DefaultHttpServer.this::start);
	}

	public void start() {
		getLock().lock();

		try {
			if (getLifecycleAdapter().shutdownInProgress())
				throw new IllegalStateException(
						"Cannot start HTTP server while shutdown is in progress");
			if (isStarted())
				return;

			if (getRequestHandler().isEmpty())
				throw new IllegalStateException(format("No %s was registered for %s", RequestHandler.class, getClass()));

			BuiltInTransportLifecycleAdapter.Generation lifecycleGeneration =
					getLifecycleAdapter().beginStart();
			AtomicBoolean streamingForcedShutdownStarted = new AtomicBoolean();
			this.streamingForcedShutdownStarted = streamingForcedShutdownStarted;
			try {
				this.startSetupHook.run();

			OptionsBuilder optionsBuilder = OptionsBuilder.newBuilder()
					.withHost(getHost())
					.withPort(getPort())
					.withConcurrency(getConcurrency())
					.withRequestHeaderTimeout(getRequestHeaderTimeout())
					.withRequestBodyTimeout(getRequestBodyTimeout())
					.withResponseWriteIdleTimeout(getResponseWriteIdleTimeout())
					.withResolution(getSocketSelectTimeout())
					.withReadBufferSize(getRequestReadBufferSizeInBytes())
					.withMaxRequestSize(getMaximumRequestSizeInBytes())
					.withMaxRequestBodySize(getMaximumRequestBodySizeInBytes())
					.withMaxHeaderCount(getMaximumHeaderCount())
					.withMaxHeadersSize(getMaximumHeadersSizeInBytes())
					.withMaxRequestTargetLength(getMaximumRequestTargetLengthInBytes())
					.withAcceptLength(getSocketPendingConnectionLimit())
					.withMaxConnections(getConcurrentConnectionLimit())
					.withUnparsedRequestCaptureLimitInBytes(
							UNPARSED_REQUEST_CAPTURE_LIMIT_IN_BYTES)
					.withUnparsedResponseSizeLimitInBytes(
							UNPARSED_RESPONSE_SIZE_LIMIT_IN_BYTES);
			Options options = optionsBuilder.build();

			Logger logger = transportLogger();

			ConnectionListener connectionListener = new ConnectionListener() {
				@Override
				public void willAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
					notifyWillAcceptConnection(remoteAddress);
				}

				@Override
				public void didAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
					notifyDidAcceptConnection(remoteAddress);
				}

				@Override
				public void didFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
					notifyDidFailToAcceptConnection(remoteAddress, ConnectionRejectionReason.MAX_CONNECTIONS, null);
				}

				@Override
				public void didFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress,
																							@Nullable Throwable throwable) {
					notifyDidFailToAcceptConnection(remoteAddress, ConnectionRejectionReason.INTERNAL_ERROR, throwable);
				}

				@Override
				public void didTerminateEventLoop(@NonNull EventLoop eventLoop,
																			@NonNull Throwable throwable) {
					// The failure signal is the first framework-visible consequence.  The
					// adapter's coordinator exclusively owns transport-wide quiesce/force.
					getLifecycleAdapter().signalUnexpectedFailure(lifecycleGeneration, throwable);
					notifyDidFailToAcceptConnection(null,
							ConnectionRejectionReason.INTERNAL_ERROR, throwable);
				}
			};

			Handler handlerDelegate = ((microhttpRequest, microHttpCallback) -> {
				ExecutorService requestHandlerExecutorServiceReference = this.requestHandlerExecutorService;
				TimeoutScheduler requestHandlerTimeoutSchedulerReference = this.requestHandlerTimeoutScheduler;
				InetSocketAddress remoteAddress = microhttpRequest.remoteAddress();
				String requestTarget = microhttpRequest.uri();

				notifyWillAcceptRequest(remoteAddress, requestTarget);

				if (requestHandlerExecutorServiceReference == null) {
					IllegalStateException executorUnavailableException = new IllegalStateException("Request handler executor service is unavailable");

					notifyDidFailToAcceptRequest(remoteAddress, requestTarget, RequestRejectionReason.INTERNAL_ERROR, executorUnavailableException);

					safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Request handler executor service is unavailable").build());
					try {
						microHttpCallback.accept(provideMicrohttpFailsafeResponse(503, microhttpRequest,
								executorUnavailableException));
					} catch (Throwable t2) {
						safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An error occurred while writing a failsafe response")
								.throwable(t2)
								.build());
					}
					return;
				}

				AtomicBoolean responseWritten = new AtomicBoolean(false);
				AtomicReference<TimeoutScheduler.ScheduledTask> timeoutFutureRef = new AtomicReference<>();
				AtomicReference<Thread> handlerThreadRef = new AtomicReference<>();

				if (requestHandlerTimeoutSchedulerReference != null && !requestHandlerTimeoutSchedulerReference.isShutdown()) {
					timeoutFutureRef.set(requestHandlerTimeoutSchedulerReference.schedule(() -> {
						if (!responseWritten.compareAndSet(false, true))
							return;

						// The CAS makes the interrupt mutually exclusive with the handler's own finally-clear:
						// whichever side wins the CAS owns the thread reference. A benign sliver remains where
						// the handler completes between our CAS and the interrupt() call below — interrupting a
						// completed handler is acceptable because the timeout response has already been claimed
						// via responseWritten above. Intentional; do not "fix" by rechecking after the CAS.
						Thread handlerThread = handlerThreadRef.get();
						if (handlerThread != null && handlerThreadRef.compareAndSet(handlerThread, null))
							handlerThread.interrupt();

						try {
							MicrohttpResponse timeoutResponse = withConnectionClose(
									provideMicrohttpFailsafeResponse(503, microhttpRequest,
											new TimeoutException("Request handling timed out")));
							microHttpCallback.accept(timeoutResponse);
						} catch (Throwable t2) {
							safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An error occurred while writing a timeout response")
									.throwable(t2)
									.build());
						}
					}, getRequestHandlerTimeout()));
				}

				try {
					requestHandlerExecutorServiceReference.submit(() -> {
						try {
							if (responseWritten.get())
								return;

							handlerThreadRef.set(Thread.currentThread());

							RequestHandler requestHandler = getRequestHandler().orElse(null);

							if (requestHandler == null)
								return;

							Request request = null;

							try {
								notifyWillReadRequest(remoteAddress, requestTarget);

								// Normalize body
								byte[] body = microhttpRequest.body();
								int encodedBodySizeInBytes = body == null ? 0 : body.length;

								if (body != null && body.length == 0)
									body = null;

								List<Header> requestHeaders = microhttpRequest.headers();
								boolean contentTooLarge = microhttpRequest.contentTooLarge();

								// Transparently decompress the body if the (opt-in) policy applies.
								// Size/ratio violations use Soklet's normal content-too-large response path.
								if (!contentTooLarge) {
									try {
										DecompressedRequestBody decompressedRequestBody = maybeDecompressRequestBody(requestHeaders, body);

										if (decompressedRequestBody != null) {
											body = decompressedRequestBody.body();
											requestHeaders = decompressedRequestBody.adjustedHeaders();
										}
									} catch (RequestBodyDecompressionException e) {
										if (e.getReason() != RequestBodyDecompressionException.Reason.DECOMPRESSED_CONTENT_TOO_LARGE)
											throw e;

										contentTooLarge = true;
										body = null;
									}
								}

								HttpMethod httpMethod;

								try {
									String normalizedMethod = trimAggressivelyToEmpty(microhttpRequest.method()).toUpperCase(ENGLISH);

									if (normalizedMethod.equals("PRI"))
										throw new IllegalRequestException("HTTP/2.0 Connection Preface specified, but Soklet only supports HTTP/1.1");

									httpMethod = HttpMethod.valueOf(normalizedMethod);
								} catch (IllegalArgumentException e) {
									throw new IllegalRequestException("Unsupported HTTP method specified");
								}

								request = Request.withRawUrl(httpMethod, microhttpRequest.uri())
										.multipartParser(getMultipartParser())
										.idGenerator(getIdGenerator())
										.microhttpHeaders(requestHeaders)
										.body(body)
										.encodedBodySizeInBytes(encodedBodySizeInBytes)
										.remoteAddress(microhttpRequest.remoteAddress())
										.contentTooLarge(contentTooLarge)
										.build();

								notifyDidReadRequest(remoteAddress, requestTarget);

								Request requestForResponse = request;

								requestHandler.handleRequest(requestForResponse, (requestResult -> {
									try {
										MicrohttpResponse microhttpResponse = toMicrohttpResponse(requestForResponse,
												requestResult.getResourceMethod().orElse(null),
												requestResult.getMarshaledResponse(),
												requestResult.getHeadResponseCompressionBody().orElse(null),
												streamingForcedShutdownStarted::get);
										if (responseWritten.compareAndSet(false, true)) {
											cancelTimeout(timeoutFutureRef.getAndSet(null));
											try {
												microHttpCallback.accept(microhttpResponse);
											} catch (Throwable t) {
												safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Unable to write response")
														.throwable(t)
														.build());
											}
										}
									} catch (Throwable t) {
										safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An error occurred while marshaling to a response")
												.throwable(t)
												.build());

										if (responseWritten.compareAndSet(false, true)) {
											cancelTimeout(timeoutFutureRef.getAndSet(null));
											try {
												microHttpCallback.accept(provideMicrohttpFailsafeResponse(500, microhttpRequest, t));
											} catch (Throwable t2) {
												safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An error occurred while writing a failsafe response")
														.throwable(t2)
														.build());
											}
										}
									}
								}));
							} catch (Throwable t) {
								Integer failsafeStatusCode = 500;
								RequestReadFailureReason failureReason = RequestReadFailureReason.INTERNAL_ERROR;

								if (t instanceof IllegalRequestException) {
									failsafeStatusCode = 400;
									failureReason = RequestReadFailureReason.UNPARSEABLE_REQUEST;
									String message = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
									safelyLog(LogEvent.with(LogEventType.SERVER_UNPARSEABLE_REQUEST, message)
											.throwable(t)
											.build());
								} else if (t instanceof URISyntaxException) {
									failsafeStatusCode = 400;
									failureReason = RequestReadFailureReason.UNPARSEABLE_REQUEST;
									// URISyntaxException renders its request-controlled input in its message.
									// The typed failure callback below still receives both the raw target and
									// original exception so applications may apply their own disclosure policy.
									safelyLog(LogEvent.with(LogEventType.SERVER_UNPARSEABLE_REQUEST,
											"Unable to parse request URI").build());
								} else if (t instanceof RequestBodyDecompressionException requestBodyDecompressionException) {
									failsafeStatusCode = requestBodyDecompressionException.getReason().getStatusCode();
									failureReason = RequestReadFailureReason.REQUEST_BODY_DECOMPRESSION_FAILED;
									String message = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
									safelyLog(LogEvent.with(LogEventType.SERVER_UNPARSEABLE_REQUEST, message)
											.throwable(t)
											.build());
								} else {
									safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An unexpected error occurred during request handling")
											.throwable(t)
											.build());
								}

								if (request == null) {
									notifyDidFailToReadRequest(microhttpRequest.remoteAddress(),
											microhttpRequest.uri(),
											failureReason,
											t);
								}

								if (responseWritten.compareAndSet(false, true)) {
									cancelTimeout(timeoutFutureRef.getAndSet(null));
									try {
										microHttpCallback.accept(provideMicrohttpFailsafeResponse(failsafeStatusCode, microhttpRequest, t));
									} catch (Throwable t2) {
										safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An error occurred while writing a failsafe response")
												.throwable(t2)
												.build());
									}
								}
							}
						} finally {
							handlerThreadRef.compareAndSet(Thread.currentThread(), null);
						}
					});

					notifyDidAcceptRequest(remoteAddress, requestTarget);
				} catch (RejectedExecutionException e) {
					RequestRejectionReason rejectionReason = rejectionReasonFor(requestHandlerExecutorServiceReference);
					notifyDidFailToAcceptRequest(remoteAddress, requestTarget, rejectionReason, e);

					safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Request handler executor rejected task")
							.throwable(e)
							.build());

					if (responseWritten.compareAndSet(false, true)) {
						cancelTimeout(timeoutFutureRef.getAndSet(null));
						try {
							microHttpCallback.accept(withConnectionClose(provideMicrohttpFailsafeResponse(503, microhttpRequest, e)));
						} catch (Throwable t2) {
							safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An error occurred while writing a failsafe response")
									.throwable(t2)
									.build());
						}
					}
				}
			});
			// Distinct pipelined dispatches can be record-equal, especially when the
			// parser reuses its empty body. Admissions belong to dispatch identity.
			Map<MicrohttpRequest, AdmissionFence.Admission> lifecycleAdmissions =
					Collections.synchronizedMap(new IdentityHashMap<>());
			Handler handler = new Handler() {
				@Override
				public void handle(@NonNull MicrohttpRequest request,
						@NonNull Consumer<MicrohttpResponse> responseConsumer) {
					AdmissionFence.Admission admission = getLifecycleAdapter()
							.tryAdmit(lifecycleGeneration).orElse(null);
					if (admission == null) {
						RejectedExecutionException exception = new RejectedExecutionException(
								"HTTP request rejected because the server is shutting down");
						responseConsumer.accept(withConnectionClose(
								provideMicrohttpFailsafeResponse(503, request, exception)));
						return;
					}

					AdmissionFence.Admission existing = lifecycleAdmissions.putIfAbsent(
							request, admission);
					if (existing != null) {
						admission.close();
						throw new IllegalStateException(
								"HTTP request was dispatched more than once concurrently");
					}
					if (!getLifecycleAdapter().admissionOpen(lifecycleGeneration)) {
						releaseLifecycleAdmission(lifecycleAdmissions, request);
						RejectedExecutionException exception = new RejectedExecutionException(
								"HTTP request rejected because shutdown won admission");
						responseConsumer.accept(withConnectionClose(
								provideMicrohttpFailsafeResponse(503, request, exception)));
						return;
					}

					try {
						handlerDelegate.handle(request, response -> {
							try {
								responseConsumer.accept(response);
							} finally {
								releaseLifecycleAdmission(lifecycleAdmissions, request);
							}
						});
					} catch (RuntimeException | Error throwable) {
						releaseLifecycleAdmission(lifecycleAdmissions, request);
						throw throwable;
					}
				}

				@Override
				public void cancel(@NonNull MicrohttpRequest request,
						@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
					releaseLifecycleAdmission(lifecycleAdmissions, request);
				}

				@Override
				public boolean handleUnparsedRequest(
						@NonNull UnparsedRequestRejection rejection,
						@NonNull Consumer<byte[]> responseConsumer) {
					AdmissionFence.Admission admission = getLifecycleAdapter()
							.tryAdmit(lifecycleGeneration).orElse(null);
					if (admission == null)
						return false;
					if (!getLifecycleAdapter().admissionOpen(lifecycleGeneration)) {
						admission.close();
						return false;
					}

					try {
						boolean accepted = submitUnparsedRequest(rejection,
								responseConsumer, admission);
						if (!accepted)
							admission.close();
						return accepted;
					} catch (RuntimeException | Error throwable) {
						admission.close();
						throw throwable;
					}
				}
			};

				this.requestHandlerExecutorService = getRequestHandlerExecutorServiceSupplier().get();
				this.streamingExecutorService = getStreamingExecutorServiceSupplier().get();
				this.streamingTimeoutExecutorService = new ScheduledThreadPoolExecutor(1, new NonvirtualThreadFactory("streaming-timeout"));
				this.requestHandlerTimeoutScheduler = new TimeoutScheduler(new NonvirtualThreadFactory("request-handler-timeout"));
				EventLoop eventLoop = new EventLoop(options, logger, handler, connectionListener);
				eventLoop.useCoordinatorOwnedUnexpectedTermination();
				this.eventLoop = eventLoop;
				eventLoop.start();
				getLifecycleAdapter().markReady(lifecycleGeneration);
			} catch (BindException e) {
				getLifecycleAdapter().failedStart(lifecycleGeneration, e, false);
				throw new UncheckedIOException(format("Soklet was unable to start the HTTP server - port %d is already in use.", getPort()), e);
			} catch (IOException e) {
				getLifecycleAdapter().failedStart(lifecycleGeneration, e, false);
				throw new UncheckedIOException(e);
			} catch (RuntimeException e) {
				getLifecycleAdapter().failedStart(lifecycleGeneration, e, false);
				throw e;
			} catch (Error error) {
				try {
					getLifecycleAdapter().failedStart(lifecycleGeneration, error, false);
				} catch (Throwable cleanupFailure) {
					if (!sameInstance(cleanupFailure, error))
						error.addSuppressed(cleanupFailure);
				}
				throw error;
			}
		} finally {
			getLock().unlock();
		}
	}

	public void stop() {
		BuiltInTransportLifecycleAdapter.Generation generation;
		ReentrantLock lock = getLock();
		lock.lock();
		try {
			generation = getLifecycleAdapter().requestStop();
		} finally {
			lock.unlock();
		}
		getLifecycleAdapter().awaitStop(generation);
	}

	@NonNull
	protected MicrohttpResponse provideMicrohttpFailsafeResponse(@NonNull Integer statusCode,
																															 @NonNull MicrohttpRequest microhttpRequest,
																															 @NonNull Throwable throwable) {
		requireNonNull(statusCode);
		requireNonNull(microhttpRequest);
		requireNonNull(throwable);

		Charset charset = StandardCharsets.UTF_8;
		String reasonPhrase = StatusCode.fromStatusCode(statusCode)
				.map(StatusCode::getReasonPhrase)
				.orElse("Unknown");
		List<Header> headers = List.of(new Header("Content-Type", format("text/plain; charset=%s", charset.name())));
		byte[] body = format("HTTP %s: %s", statusCode, reasonPhrase).getBytes(charset);

		return new MicrohttpResponse(statusCode, reasonPhrase, headers, body);
	}

	private void cancelTimeout(TimeoutScheduler.@Nullable ScheduledTask timeoutTask) {
		if (timeoutTask != null)
			timeoutTask.cancel();
	}

	private static void releaseLifecycleAdmission(
			@NonNull Map<MicrohttpRequest, AdmissionFence.Admission> admissions,
			@NonNull MicrohttpRequest request) {
		AdmissionFence.Admission admission = admissions.remove(requireNonNull(request));
		if (admission != null)
			admission.close();
	}

	@NonNull
	private MicrohttpResponse withConnectionClose(@NonNull MicrohttpResponse response) {
		requireNonNull(response);

		if (hasConnectionCloseHeader(response))
			return response;

		List<Header> headers = new ArrayList<>();

		if (response.headers() != null)
			headers.addAll(response.headers());

		headers.add(new Header("Connection", "close"));
		return response.withHeaders(headers);
	}

	private boolean hasConnectionCloseHeader(@NonNull MicrohttpResponse response) {
		requireNonNull(response);

		List<Header> headers = response.headers();
		if (headers == null)
			return false;

		for (Header header : headers) {
			if (!"Connection".equalsIgnoreCase(header.name()))
				continue;

			String value = header.value();
			if (value == null)
				continue;

			for (String part : value.split(",", -1)) {
				if ("close".equalsIgnoreCase(part.trim()))
					return true;
			}
		}

		return false;
	}

	@NonNull
	public Boolean isStarted() {
		getLock().lock();

		try {
			EventLoop eventLoop = getEventLoop().orElse(null);
			return eventLoop != null && eventLoop.isRunning();
		} finally {
			getLock().unlock();
		}
	}

	public void initialize(@NonNull SokletConfig sokletConfig,
												 @NonNull RequestHandler requestHandler) {
		requireNonNull(requestHandler);
		requireNonNull(sokletConfig);

		this.requestHandler = requestHandler;
		this.lifecycleObserver = sokletConfig.getAggregateLifecycleObserver();
		this.lifecyclePolicy = sokletConfig.getLifecyclePolicy();
		this.metricsCollector = sokletConfig.getMetricsCollector();
		this.responseMarshaler = sokletConfig.getResponseMarshaler();
	}

	private boolean submitUnparsedRequest(
			@NonNull UnparsedRequestRejection rejection,
			@NonNull Consumer<byte[]> responseConsumer,
			AdmissionFence.@NonNull Admission admission) {
		requireNonNull(rejection);
		requireNonNull(responseConsumer);
		requireNonNull(admission);

		ExecutorService executor = this.requestHandlerExecutorService;
		TimeoutScheduler timeoutScheduler = this.requestHandlerTimeoutScheduler;
		if (executor == null || executor.isShutdown()
				|| timeoutScheduler == null || timeoutScheduler.isShutdown())
			return false;

		AtomicBoolean responseClaimed = new AtomicBoolean();
		AtomicBoolean executedInline = new AtomicBoolean();
		AtomicReference<FutureTask<Void>> applicationTaskReference =
				new AtomicReference<>();
		AtomicReference<TimeoutScheduler.ScheduledTask> timeoutReference =
				new AtomicReference<>();
		Thread submittingThread = Thread.currentThread();

		try {
			timeoutReference.set(timeoutScheduler.schedule(() -> {
				if (!responseClaimed.compareAndSet(false, true))
					return;
				try {
					responseConsumer.accept(null);
				} finally {
					admission.close();
					FutureTask<Void> applicationTask =
							applicationTaskReference.get();
					if (applicationTask != null)
						applicationTask.cancel(true);
				}
			}, getRequestHandlerTimeout()));

			FutureTask<Void> applicationTask = new FutureTask<>(() -> {
				MarshaledResponse marshaledResponse = null;
				try {
					if (responseClaimed.get())
						return;

					UnparsedRequest request = UnparsedRequest
							.withServerTypeAndReason(ServerType.HTTP,
									unparsedRequestReason(rejection.reason()))
							.remoteAddress(rejection.remoteAddress())
							.capturedBytes(rejection.capturedBytesForTransfer())
							.observedByteCount(rejection.observedByteCount())
							.captureTruncated(rejection.captureTruncated())
							.build();

					if (responseClaimed.get())
						return;
					notifyDidRejectUnparsedRequest(request);
					if (responseClaimed.get())
						return;

					ResponseMarshaler responseMarshaler = requireNonNull(
							this.responseMarshaler,
							"Response marshaler is unavailable.");
					marshaledResponse = requireNonNull(
							responseMarshaler.forUnparsedRequest(request),
							"Response marshaler returned null for an unparsed request.");
					byte[] serializedResponse =
							serializeUnparsedRequestResponse(marshaledResponse);

					if (responseClaimed.compareAndSet(false, true)) {
						cancelTimeout(timeoutReference.getAndSet(null));
						try {
							responseConsumer.accept(serializedResponse);
						} finally {
							admission.close();
						}
					}
				} catch (Throwable throwable) {
					releaseRejectedUnparsedResponseResources(marshaledResponse);
					if (responseClaimed.compareAndSet(false, true)) {
						cancelTimeout(timeoutReference.getAndSet(null));
						try {
							responseConsumer.accept(null);
						} finally {
							admission.close();
						}
						safelyLog(LogEvent.with(
								LogEventType.RESPONSE_MARSHALER_FOR_UNPARSED_REQUEST_FAILED,
								"Unable to marshal a response for an unparsed request; using the built-in response")
								.throwable(throwable)
								.build());
					}
				}
			}, null);
			applicationTaskReference.set(applicationTask);
			executor.execute(() -> {
				// A caller-runs or direct executor must not turn malformed input
				// into application work on the selector thread. Check before
				// entering the FutureTask so timeout cancellation can never
				// interrupt the selector through the task's runner reference.
				if (sameInstance(Thread.currentThread(), submittingThread)) {
					executedInline.set(true);
					return;
				}
				applicationTask.run();
			});
			if (executedInline.get()) {
				cancelTimeout(timeoutReference.getAndSet(null));
				return false;
			}
			return true;
		} catch (RejectedExecutionException exception) {
			cancelTimeout(timeoutReference.getAndSet(null));
			return false;
		} catch (RuntimeException | Error throwable) {
			try {
				cancelTimeout(timeoutReference.getAndSet(null));
			} catch (RuntimeException | Error cancelationFailure) {
				throwable.addSuppressed(cancelationFailure);
			}
			throw throwable;
		}
	}

	private void notifyDidRejectUnparsedRequest(
			@NonNull UnparsedRequest request) {
		try {
			getLifecycleObserver().didRejectUnparsedRequest(requireNonNull(request));
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(
						LogEventType.LIFECYCLE_OBSERVER_DID_REJECT_UNPARSED_REQUEST_FAILED,
						"An exception occurred while invoking LifecycleObserver::didRejectUnparsedRequest")
					.throwable(throwable)
					.build());
		}
	}

	@NonNull
	private static UnparsedRequestReason unparsedRequestReason(
			UnparsedRequestRejection.@NonNull Reason reason) {
		return switch (requireNonNull(reason)) {
			case MALFORMED_REQUEST -> UnparsedRequestReason.MALFORMED_REQUEST;
			case REQUEST_TARGET_TOO_LONG ->
					UnparsedRequestReason.REQUEST_TARGET_TOO_LONG;
			case EXPECTATION_FAILED -> UnparsedRequestReason.EXPECTATION_FAILED;
			case REQUEST_HEADERS_TOO_LARGE ->
					UnparsedRequestReason.REQUEST_HEADERS_TOO_LARGE;
		};
	}

	private byte @NonNull [] serializeUnparsedRequestResponse(
			@NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(marshaledResponse);

		if (marshaledResponse.getStreamingResponseBody().isPresent())
			throw new IllegalArgumentException(
					"Unparsed-request responses may not stream a body.");

		int statusCode = marshaledResponse.getStatusCode();
		if (statusCode < 200 || statusCode > 599)
			throw new IllegalArgumentException(
					"Unparsed-request response status must be a final HTTP "
							+ "status from 200 through 599.");
		if (statusMustNotIncludeBody(statusCode)
				&& marshaledResponse.getBody().isPresent())
			throw new IllegalArgumentException(format(
					"HTTP status %d must not include an unparsed-request response body.",
					statusCode));

		byte[] body = unparsedRequestResponseBody(marshaledResponse);
		String reasonPhrase = reasonPhraseForStatusCode(statusCode);
		long serializedSize = body.length
				+ "HTTP/1.1".length() + 1L
				+ Integer.toString(statusCode).length() + 1L
				+ reasonPhrase.length() + 2L
				+ serializedHeaderSize("Connection", "close") + 2L;
		if (!statusMustNotIncludeBody(statusCode))
			serializedSize += serializedHeaderSize("Content-Length",
					Integer.toString(body.length));
		ensureUnparsedResponseSize(serializedSize);

		Set<String> connectionNamedHeaders = new TreeSet<>(
				String.CASE_INSENSITIVE_ORDER);
		Set<String> connectionValues = marshaledResponse.getHeaders()
				.get("Connection");

		if (connectionValues != null) {
			for (String value : connectionValues) {
				for (String token : value.split(",", -1)) {
					String normalized = trimAggressivelyToEmpty(token);
					if (!normalized.isEmpty())
						connectionNamedHeaders.add(normalized);
				}
			}
		}

		List<Header> headers = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry :
				marshaledResponse.getHeaders().entrySet()) {
			String name = entry.getKey();
			if (unparsedResponseHeaderIsTransportOwned(name)
					|| connectionNamedHeaders.contains(name))
				continue;

			for (String value : entry.getValue()) {
				serializedSize += serializedHeaderSize(name, value);
				ensureUnparsedResponseSize(serializedSize);
				headers.add(new Header(name, value));
			}
		}

		Set<ResponseCookie> cookies = marshaledResponse.getCookies();
		List<ResponseCookie> sortedCookies = new ArrayList<>(cookies);
		if (!isAlreadySorted(cookies))
			sortedCookies.sort(Comparator.comparing(ResponseCookie::getName));
		if (!connectionNamedHeaders.contains("Set-Cookie")) {
			for (ResponseCookie cookie : sortedCookies) {
				String value = cookie.toSetCookieHeaderRepresentation();
				serializedSize += serializedHeaderSize("Set-Cookie", value);
				ensureUnparsedResponseSize(serializedSize);
				headers.add(new Header("Set-Cookie", value));
			}
		}

		headers.sort(Comparator.comparing(Header::name,
				String.CASE_INSENSITIVE_ORDER)
				.thenComparing(Header::name)
				.thenComparing(Header::value));

		MicrohttpResponse response = new MicrohttpResponse(statusCode,
				reasonPhrase, headers, body);
		List<Header> transportHeaders = new ArrayList<>();
		transportHeaders.add(new Header("Connection", "close"));
		if (!statusMustNotIncludeBody(statusCode))
			transportHeaders.add(new Header("Content-Length",
					Integer.toString(body.length)));
		if (!response.hasHeader("Date"))
			transportHeaders.add(new Header("Date", HttpDate.currentSecondHeaderValue()));
		return response.serialize("HTTP/1.1", transportHeaders,
				UNPARSED_RESPONSE_SIZE_LIMIT_IN_BYTES);
	}

	private static long serializedHeaderSize(@NonNull String name,
			@NonNull String value) {
		return (long) requireNonNull(name).length() + 2L
				+ requireNonNull(value).length() + 2L;
	}

	private static void ensureUnparsedResponseSize(long serializedSize) {
		if (serializedSize > UNPARSED_RESPONSE_SIZE_LIMIT_IN_BYTES)
			throw new IllegalArgumentException(
					"Serialized unparsed-request response exceeds its size limit.");
	}

	private static byte @NonNull [] unparsedRequestResponseBody(
			@NonNull MarshaledResponse marshaledResponse) {
		MarshaledResponseBody body = requireNonNull(marshaledResponse)
				.getBody().orElse(null);

		if (body == null)
			return emptyByteArray();
		if (body.getLength() > UNPARSED_RESPONSE_SIZE_LIMIT_IN_BYTES)
			throw new IllegalArgumentException(
					"Unparsed-request response body exceeds its size limit.");
		if (body instanceof MarshaledResponseBody.Bytes bytes)
			return bytes.getBytes().clone();
		if (body instanceof MarshaledResponseBody.ByteBuffer byteBuffer) {
			ByteBuffer source = byteBuffer.getBuffer();
			byte[] bytes = new byte[source.remaining()];
			source.get(bytes);
			return bytes;
		}

		throw new IllegalArgumentException(format(
				"Unsupported unparsed-request response body type: %s",
				body.getClass().getName()));
	}

	private static boolean unparsedResponseHeaderIsTransportOwned(
			@NonNull String name) {
		return switch (requireNonNull(name).toLowerCase(ENGLISH)) {
			case "connection", "content-length", "keep-alive",
					"proxy-connection", "te", "trailer", "transfer-encoding",
					"upgrade" -> true;
			default -> false;
		};
	}

	private static boolean statusMustNotIncludeBody(int statusCode) {
		return statusCode == 204 || statusCode == 205 || statusCode == 304;
	}

	private static void releaseRejectedUnparsedResponseResources(
			@Nullable MarshaledResponse marshaledResponse) {
		if (marshaledResponse == null)
			return;

		MarshaledResponseBody body = marshaledResponse.getBody().orElse(null);
		if (!(body instanceof MarshaledResponseBody.FileChannel fileChannel)
				|| !fileChannel.getCloseOnComplete())
			return;

		try {
			fileChannel.getChannel().close();
		} catch (IOException ignored) {
			// Best effort: this is already a response-validation fallback path.
		}
	}

	@NonNull
	Logger transportLogger() {
		return new Logger() {
			@Override
			public boolean enabled() {
				return false;
			}

			@Override
			public boolean failureEnabled() {
				return true;
			}

			@Override
			public void log(@Nullable LogEntry... logEntries) {
				// Trace logging is disabled for the embedded transport by default.
			}

			@Override
			public void log(@Nullable Exception e,
											@Nullable LogEntry... logEntries) {
				// Trace logging is disabled for the embedded transport by default.
			}

			@Override
			public void logFailure(@Nullable LogEntry... logEntries) {
				logTransportFailure(null, logEntries);
			}

			@Override
			public void logFailure(@Nullable Exception e,
														 @Nullable LogEntry... logEntries) {
				logTransportFailure(e, logEntries);
			}

			@Override
			public void logFailure(@Nullable Throwable throwable,
														 @Nullable LogEntry... logEntries) {
				logTransportFailure(throwable, logEntries);
			}
		};
	}

	private void logTransportFailure(@Nullable Throwable throwable,
																	 @Nullable LogEntry... logEntries) {
		String event = valueForLogEntry("event", logEntries).orElse("unknown");
		MetricsCollector.TransportFailureReason reason = transportFailureReasonFor(event);
		String connectionId = valueForLogEntry("id", logEntries).orElse(null);
		String message = connectionId == null
				? format("HTTP transport failure: %s", event)
				: format("HTTP transport failure: %s (connectionId=%s)", event, connectionId);

		safelyLog(LogEvent.with(LogEventType.SERVER_TRANSPORT_FAILURE, message)
				.throwable(throwable)
				.build());
		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didRecordTransportFailure", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didRecordTransportFailure(ServerType.HTTP, reason, throwable));
	}

	@NonNull
	private static Optional<String> valueForLogEntry(@NonNull String key,
																									 @Nullable LogEntry... logEntries) {
		requireNonNull(key);

		if (logEntries == null)
			return Optional.empty();

		for (LogEntry logEntry : logEntries) {
			if (logEntry == null)
				continue;
			if (key.equals(logEntry.key()))
				return Optional.ofNullable(logEntry.value());
		}

		return Optional.empty();
	}

	private static MetricsCollector.TransportFailureReason transportFailureReasonFor(@Nullable String event) {
		if (event == null)
			return MetricsCollector.TransportFailureReason.UNKNOWN;

		return switch (event) {
			case "request_timeout" -> MetricsCollector.TransportFailureReason.REQUEST_READ_TIMEOUT;
			case "exceed_request_max_close" -> MetricsCollector.TransportFailureReason.REQUEST_TOO_LARGE;
			case "malformed_request" -> MetricsCollector.TransportFailureReason.MALFORMED_REQUEST;
			case "read_error" -> MetricsCollector.TransportFailureReason.READ_ERROR;
			case "write_error" -> MetricsCollector.TransportFailureReason.WRITE_ERROR;
			case "response_write_idle_timeout" -> MetricsCollector.TransportFailureReason.RESPONSE_WRITE_IDLE_TIMEOUT;
			case "response_ready_error" -> MetricsCollector.TransportFailureReason.RESPONSE_READY_ERROR;
			case "request_timeout_error" -> MetricsCollector.TransportFailureReason.REQUEST_READ_TIMEOUT_ERROR;
			case "response_write_idle_timeout_error" -> MetricsCollector.TransportFailureReason.RESPONSE_WRITE_IDLE_TIMEOUT_ERROR;
			case "task_error" -> MetricsCollector.TransportFailureReason.TASK_ERROR;
			case "timeout_task_error" -> MetricsCollector.TransportFailureReason.TIMEOUT_TASK_ERROR;
			case "selection_key_error" -> MetricsCollector.TransportFailureReason.SELECTION_KEY_ERROR;
			case "register_error" -> MetricsCollector.TransportFailureReason.REGISTER_ERROR;
			case "accept_loop_error" -> MetricsCollector.TransportFailureReason.ACCEPT_LOOP_ERROR;
			case "connection_setup_error" -> MetricsCollector.TransportFailureReason.CONNECTION_SETUP_ERROR;
			case "event_loop_terminate", "sub_event_loop_terminate" -> MetricsCollector.TransportFailureReason.EVENT_LOOP_TERMINATED;
			default -> MetricsCollector.TransportFailureReason.UNKNOWN;
		};
	}

	@NonNull
	protected Map<@NonNull String, @NonNull Set<@NonNull String>> headersFromMicrohttpRequest(@NonNull MicrohttpRequest microhttpRequest) {
		requireNonNull(microhttpRequest);

		Map<String, Set<String>> headers = new LinkedCaseInsensitiveMap<>();
		for (Header header : microhttpRequest.headers())
			Utilities.addParsedHeader(headers, header.name(), header.value());

		Utilities.freezeStringValueSets(headers);
		return Collections.unmodifiableMap(headers);
	}

	@NonNull
	protected MicrohttpResponse toMicrohttpResponse(@NonNull MarshaledResponse marshaledResponse) {
		return toMicrohttpResponse(null, null, marshaledResponse, null, () -> false);
	}

	@NonNull
	protected MicrohttpResponse toMicrohttpResponse(@Nullable Request request,
																	@Nullable ResourceMethod resourceMethod,
																	@NonNull MarshaledResponse marshaledResponse) {
		return toMicrohttpResponse(request, resourceMethod, marshaledResponse, null,
				() -> false);
	}

	@NonNull
	private MicrohttpResponse toMicrohttpResponse(@Nullable Request request,
																@Nullable ResourceMethod resourceMethod,
																@NonNull MarshaledResponse marshaledResponse,
																@Nullable MarshaledResponseBody headResponseCompressionBody,
																@NonNull BooleanSupplier streamingForcedShutdownStarted) {
		requireNonNull(marshaledResponse);
		requireNonNull(streamingForcedShutdownStarted);

		List<Header> headers = new ArrayList<>();

		// Emit one header line per value (order preserved for SortedSet/LinkedHashSet)
		for (Map.Entry<String, Set<String>> entry : marshaledResponse.getHeaders().entrySet()) {
			String name = entry.getKey();
			Set<String> values = entry.getValue();

			if (name == null || values == null || values.isEmpty())
				continue;

			List<String> normalizedValues = normalizeHeaderValues(values);
			if (normalizedValues.isEmpty())
				continue;

			for (String value : normalizedValues)
				headers.add(new Header(name, value));
		}

		// ResponseCookie headers are split into multiple instances of Set-Cookie.
		// Force natural ordering for consistent output if the set is not already sorted.
		Set<ResponseCookie> cookies = marshaledResponse.getCookies();
		List<ResponseCookie> sortedCookies = new ArrayList<>(cookies);

		if (!isAlreadySorted(cookies))
			sortedCookies.sort(Comparator.comparing(ResponseCookie::getName));

		for (ResponseCookie cookie : sortedCookies)
			headers.add(new Header("Set-Cookie", cookie.toSetCookieHeaderRepresentation()));

		// Force natural order for consistent output
		headers.sort(Comparator.comparing(Header::name));

		String reasonPhrase = reasonPhraseForStatusCode(marshaledResponse.getStatusCode());
		StreamingResponseBody stream = marshaledResponse.getStreamingResponseBody().orElse(null);

		if (stream != null) {
			Request streamingRequest = requireNonNull(request);
			ResourceMethod streamingResourceMethod = requireNonNull(resourceMethod);
			ExecutorService streamingExecutorService = getStreamingExecutorService().orElse(null);
			ScheduledExecutorService streamingTimeoutExecutorService = getStreamingTimeoutExecutorService().orElse(null);

			if (streamingExecutorService == null)
				throw new IllegalStateException("Streaming executor service is unavailable.");

			if (streamingTimeoutExecutorService == null)
				throw new IllegalStateException("Streaming timeout executor service is unavailable.");

			Duration streamingResponseTimeout = getStreamingResponseTimeout();
			Duration streamingResponseIdleTimeout = getStreamingResponseIdleTimeout();
			Instant deadline = streamingResponseTimeout.isZero()
					? null
					: Instant.now().plus(streamingResponseTimeout);
			Duration idleTimeout = streamingResponseIdleTimeout.isZero()
					? null
					: streamingResponseIdleTimeout;

			return StreamingMicrohttpResponses.withStreamingBody(
					marshaledResponse.getStatusCode(),
					reasonPhrase,
					headers,
					streamingRequest,
					stream,
					streamingExecutorService,
					streamingTimeoutExecutorService,
					getStreamingQueueCapacityInBytes(),
					getStreamingChunkSizeInBytes(),
					deadline,
					idleTimeout,
					streamingForcedShutdownStarted,
					(establishedAt, streamDuration, cancelationReason, throwable) ->
							notifyDidTerminateResponseStream(streamingRequest, streamingResourceMethod, marshaledResponse, establishedAt, streamDuration, cancelationReason, throwable),
					(throwable) -> safelyLog(LogEvent.with(LogEventType.RESPONSE_STREAM_CANCELATION_CALLBACK_FAILED,
									"An exception occurred while invoking a streaming response cancelation callback")
							.throwable(throwable)
							.request(streamingRequest)
							.resourceMethod(streamingResourceMethod)
							.marshaledResponse(marshaledResponse)
							.build()));
		}

		MarshaledResponseBody body = marshaledResponse.getBody().orElse(null);
		boolean head = request != null && request.getHttpMethod() == HttpMethod.HEAD;
		MarshaledResponseBody compressionBody = head && body == null
				? headResponseCompressionBody
				: body;

		if (isResponseCompressionCandidate(request, marshaledResponse, headers, compressionBody)) {
			Request compressionRequest = requireNonNull(request);
			MarshaledResponseBody uncompressedBody = requireNonNull(compressionBody);
			// The transport result carries HEAD's original representation separately, so planning
			// sees the same body as GET without retaining it in lifecycle/log response objects.
			MarshaledResponse compressionResponse = sameInstance(body, uncompressedBody) ? marshaledResponse
					: marshaledResponse.copy().body(uncompressedBody).finish();
			ResponseCompressionPlan plan = requireNonNull(
					getResponseCompressor().plan(compressionRequest, compressionResponse),
					"Response compressor must not return null.");

			// Both encoded and identity responses participate in encoding negotiation. This must
			// also cover a custom planner that examines Accept-Encoding and returns none().
			headers = varyAcceptEncodingHeaders(headers);
			String contentEncoding = plan.getContentEncoding().orElse(null);

			if (contentEncoding != null && requestAcceptsContentEncoding(compressionRequest, contentEncoding)) {
				headers = compressedHeaders(headers, contentEncoding);
				return new MicrohttpResponse(marshaledResponse.getStatusCode(), reasonPhrase, headers,
						head ? emptyByteArray() : compressResponseBody(plan, uncompressedBody));
			}

			if (!requestAcceptsIdentity(compressionRequest))
				return new MicrohttpResponse(406, reasonPhraseForStatusCode(406),
						compressionNotAcceptableHeaders(headers), emptyByteArray());
		}

		if (body == null)
			return new MicrohttpResponse(marshaledResponse.getStatusCode(), reasonPhrase, headers, emptyByteArray());

		if (body instanceof MarshaledResponseBody.Bytes bytes)
			return new MicrohttpResponse(marshaledResponse.getStatusCode(), reasonPhrase, headers, bytes.getBytes());

		if (body instanceof MarshaledResponseBody.File file)
			return MicrohttpResponse.withFileBody(
					marshaledResponse.getStatusCode(), reasonPhrase, headers,
					file.getPath(), file.getOffset(), file.getCount());

		if (body instanceof MarshaledResponseBody.FileChannel fileChannel)
			return MicrohttpResponse.withFileChannelBody(
					marshaledResponse.getStatusCode(), reasonPhrase, headers,
					fileChannel.getChannel(), fileChannel.getOffset(), fileChannel.getCount(), fileChannel.getCloseOnComplete());

		if (body instanceof MarshaledResponseBody.ByteBuffer byteBuffer)
			return MicrohttpResponse.withByteBufferBody(marshaledResponse.getStatusCode(), reasonPhrase, headers, byteBuffer.getBuffer());

		throw new IllegalStateException(format("Unsupported marshaled response body type: %s", body.getClass().getName()));
	}

	private boolean isResponseCompressionCandidate(@Nullable Request request,
			@NonNull MarshaledResponse marshaledResponse, @NonNull List<@NonNull Header> headers,
			@Nullable MarshaledResponseBody body) {
		if (request == null || sameInstance(getResponseCompressor(), ResponseCompressor.disabledInstance()))
			return false;

		if (!(body instanceof MarshaledResponseBody.Bytes || body instanceof MarshaledResponseBody.ByteBuffer)
				|| body.getLength() == 0)
			return false;

		int statusCode = marshaledResponse.getStatusCode();
		return statusCode >= 200 && statusCode != 204 && statusCode != 206 && statusCode != 304
				&& !hasHeader(headers, "Content-Encoding")
				&& !hasHeader(headers, "Content-Range")
				&& !hasHeader(headers, "Transfer-Encoding");
	}

	private boolean requestAcceptsContentEncoding(@NonNull Request request, @NonNull String contentEncoding) {
		Integer codingQ = contentEncodingQuality(request, contentEncoding);
		if (codingQ != null)
			return codingQ > 0;
		Integer wildcardQ = contentEncodingQuality(request, "*");
		// Preserve opt-in response encoding when the client does not advertise any coding.
		return wildcardQ != null && wildcardQ > 0;
	}

	private boolean requestAcceptsIdentity(@NonNull Request request) {
		Integer identityQ = contentEncodingQuality(request, "identity");
		if (identityQ != null)
			return identityQ > 0;
		Integer wildcardQ = contentEncodingQuality(request, "*");
		return wildcardQ == null || wildcardQ > 0;
	}

	@Nullable
	private Integer contentEncodingQuality(@NonNull Request request, @NonNull String contentEncoding) {
		Integer quality = null;
		for (String value : request.getHeaderValues("Accept-Encoding").orElse(Set.of())) {
			for (String part : value.split(",", -1)) {
				EncodingPreference preference = EncodingPreference.fromHeaderValue(part).orElse(null);
				if (preference != null && contentEncoding.equalsIgnoreCase(preference.coding()))
					quality = Math.max(quality == null ? 0 : quality, preference.q());
			}
		}
		return quality;
	}

	@NonNull
	private List<@NonNull Header> varyAcceptEncodingHeaders(@NonNull List<@NonNull Header> headers) {
		if (hasHeaderToken(headers, "Vary", "Accept-Encoding") || hasHeaderToken(headers, "Vary", "*"))
			return headers;

		List<Header> result = new ArrayList<>(headers.size() + 1);
		boolean updated = false;
		for (Header header : headers) {
			if (!updated && header.name().equalsIgnoreCase("Vary")) {
				String value = Utilities.trimAggressivelyToNull(header.value());
				result.add(new Header(header.name(), value == null ? "Accept-Encoding" : value + ", Accept-Encoding"));
				updated = true;
			} else {
				result.add(header);
			}
		}
		if (!updated)
			result.add(new Header("Vary", "Accept-Encoding"));
		result.sort(Comparator.comparing(Header::name));
		return result;
	}

	@NonNull
	private List<@NonNull Header> compressedHeaders(@NonNull List<@NonNull Header> headers,
			@NonNull String contentEncoding) {
		List<Header> result = new ArrayList<>(headers.size() + 1);
		for (Header header : headers) {
			if (header.name().equalsIgnoreCase("Content-Length"))
				continue;
			result.add(header.name().equalsIgnoreCase("ETag")
					? new Header(header.name(), weakEntityTagHeaderValue(header.value())) : header);
		}
		result.add(new Header("Content-Encoding", contentEncoding));
		result.sort(Comparator.comparing(Header::name));
		return result;
	}

	@NonNull
	private List<@NonNull Header> compressionNotAcceptableHeaders(@NonNull List<@NonNull Header> headers) {
		// Keep response policy (including CORS, cookies, cache directives and all Vary dimensions),
		// but do not attach the rejected representation's metadata to the empty 406 response.
		Set<String> representationHeaders = Set.of("content-length", "content-type", "content-encoding",
				"content-language", "content-location", "content-range", "etag", "last-modified", "accept-ranges",
				"content-md5", "content-digest", "repr-digest", "digest");
		List<Header> result = new ArrayList<>(headers.size());
		for (Header header : headers)
			if (!representationHeaders.contains(header.name().toLowerCase(ENGLISH)))
				result.add(header);
		return result;
	}

	private byte @NonNull [] compressResponseBody(@NonNull ResponseCompressionPlan plan,
			@NonNull MarshaledResponseBody body) {
		ResponseCompressionCodec codec = plan.getCodec().orElseThrow();
		ByteBuffer input = body instanceof MarshaledResponseBody.Bytes bytes
				? ByteBuffer.wrap(bytes.getBytes()).asReadOnlyBuffer()
				: ((MarshaledResponseBody.ByteBuffer) body).getBuffer();
		Thread invocationThread = Thread.currentThread();
		AtomicBoolean active = new AtomicBoolean(true);
		Supplier<byte @NonNull []> compressedBodySupplier = new Supplier<>() {
			private byte @Nullable [] compressedBytes;

			@Override
			public byte @NonNull [] get() {
				if (!sameInstance(Thread.currentThread(), invocationThread) || !active.get())
					throw new IllegalStateException("Compression supplier must be used synchronously during its response callback.");
				if (this.compressedBytes == null)
					this.compressedBytes = requireNonNull(codec.compress(input.asReadOnlyBuffer()),
							"Response compression codec must not return null.");
				return this.compressedBytes;
			}
		};

		try {
			var compressedBodyProvider = plan.getCompressedBodyProvider().orElse(null);
			return requireNonNull(compressedBodyProvider == null ? compressedBodySupplier.get()
					: compressedBodyProvider.apply(compressedBodySupplier), "Compressed body provider must not return null.");
		} finally {
			active.set(false);
		}
	}

	@NonNull
	private String weakEntityTagHeaderValue(@NonNull String headerValue) {
		requireNonNull(headerValue);
		EntityTag entityTag = EntityTag.fromHeaderValue(headerValue).orElse(null);

		if (entityTag == null || entityTag.isWeak())
			return headerValue;

		return EntityTag.fromWeakValue(entityTag.getValue()).toHeaderValue();
	}

	/**
	 * Applies the (opt-in) request decompression policy to an incoming request body.
	 *
	 * @return the decompressed body plus adjusted headers, or {@code null} if no decompression applies
	 * @throws RequestBodyDecompressionException if the request must be rejected (415/400/413)
	 */
	@Nullable
	private DecompressedRequestBody maybeDecompressRequestBody(@NonNull List<@NonNull Header> headers,
																														 byte @Nullable [] body) {
		requireNonNull(headers);

		RequestDecompressionPolicy requestDecompressionPolicy = getRequestDecompressionPolicy();

		if (!requestDecompressionPolicy.isEnabled())
			return null;

		List<String> contentEncodings = contentEncodingTokens(headers);

		if (contentEncodings.isEmpty())
			return null;

		// "identity" is a no-op coding
		if (contentEncodings.size() == 1 && "identity".equals(contentEncodings.get(0)))
			return null;

		boolean singleGzipCoding = contentEncodings.size() == 1
				&& ("gzip".equals(contentEncodings.get(0)) || "x-gzip".equals(contentEncodings.get(0)));

		if (!singleGzipCoding)
			throw new RequestBodyDecompressionException(RequestBodyDecompressionException.Reason.UNSUPPORTED_CONTENT_ENCODING,
					"Unsupported request Content-Encoding");

		// A Content-Encoding header with no body to decode: pass through unchanged
		if (body == null)
			return null;

		long maximumDecompressedBodySizeInBytes = requestDecompressionPolicy.getMaximumDecompressedBodySizeInBytes()
				.map(Integer::longValue)
				.orElse(getMaximumRequestSizeInBytes().longValue());

		byte[] decompressedBody = gunzipRequestBody(body, maximumDecompressedBodySizeInBytes,
				requestDecompressionPolicy.getMaximumCompressionRatio().longValue());

		return new DecompressedRequestBody(decompressedBody, headersForDecompressedBody(headers, decompressedBody.length));
	}

	/**
	 * All {@code Content-Encoding} coding tokens across header lines and comma-separated values,
	 * trimmed and lowercased.
	 */
	@NonNull
	private List<@NonNull String> contentEncodingTokens(@NonNull List<@NonNull Header> headers) {
		requireNonNull(headers);

		List<String> tokens = new ArrayList<>(2);

		for (Header header : headers) {
			if (!header.name().equalsIgnoreCase("Content-Encoding"))
				continue;

			String value = header.value();

			if (value == null)
				continue;

			for (String token : value.split(",", -1)) {
				String normalized = token.trim().toLowerCase(ENGLISH);

				if (!normalized.isEmpty())
					tokens.add(normalized);
			}
		}

		return tokens;
	}

	/**
	 * Rewrites headers after transparent decompression so handlers observe a self-consistent request:
	 * {@code Content-Encoding} is removed, any original {@code Content-Length} is replaced with the
	 * decompressed size, and {@code Transfer-Encoding} is removed (wire framing was already resolved,
	 * and a fully-buffered decompressed body must not carry both framing headers).
	 */
	@NonNull
	private List<@NonNull Header> headersForDecompressedBody(@NonNull List<@NonNull Header> headers,
																													 int decompressedBodyLength) {
		requireNonNull(headers);

		List<Header> adjustedHeaders = new ArrayList<>(headers.size() + 1);

		for (Header header : headers) {
			if (header.name().equalsIgnoreCase("Content-Encoding")
					|| header.name().equalsIgnoreCase("Content-Length")
					|| header.name().equalsIgnoreCase("Transfer-Encoding"))
				continue;

			adjustedHeaders.add(header);
		}

		adjustedHeaders.add(new Header("Content-Length", Integer.toString(decompressedBodyLength)));
		return adjustedHeaders;
	}

	/**
	 * Gunzips a request body with decompression-bomb guards: decompression aborts as soon as the output
	 * exceeds {@code maximumDecompressedSizeInBytes} or {@code (compressed size × maximumCompressionRatio) + 8 KB}.
	 * <p>
	 * Package-private for testing.
	 *
	 * @throws RequestBodyDecompressionException on malformed gzip content (400) or a limit violation (413)
	 */
	static byte @NonNull [] gunzipRequestBody(byte @NonNull [] compressedBody,
																						long maximumDecompressedSizeInBytes,
																						long maximumCompressionRatio) {
		requireNonNull(compressedBody);

		// The additive allowance keeps legitimately small compressed bodies from tripping the ratio check
		long ratioAllowanceInBytes = maximumCompressionRatio * (long) compressedBody.length + 8_192L;
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream(Math.min(Math.max(32, compressedBody.length * 4), 65_536));
		byte[] buffer = new byte[8_192];
		long totalDecompressedBytes = 0L;

		try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressedBody))) {
			int bytesRead;

			while ((bytesRead = gzipInputStream.read(buffer)) != -1) {
				totalDecompressedBytes += bytesRead;

				if (totalDecompressedBytes > maximumDecompressedSizeInBytes)
					throw new RequestBodyDecompressionException(RequestBodyDecompressionException.Reason.DECOMPRESSED_CONTENT_TOO_LARGE,
							format("Decompressed request body exceeds the %d-byte limit", maximumDecompressedSizeInBytes));

				if (totalDecompressedBytes > ratioAllowanceInBytes)
					throw new RequestBodyDecompressionException(RequestBodyDecompressionException.Reason.DECOMPRESSED_CONTENT_TOO_LARGE,
							format("Decompressed request body exceeds the %d:1 compression ratio limit", maximumCompressionRatio));

				outputStream.write(buffer, 0, bytesRead);
			}
		} catch (IOException e) {
			throw new RequestBodyDecompressionException(RequestBodyDecompressionException.Reason.MALFORMED_CONTENT,
					"Request body could not be decompressed as gzip", e);
		}

		return outputStream.toByteArray();
	}

	private record DecompressedRequestBody(
			byte @NonNull [] body,
			@NonNull List<@NonNull Header> adjustedHeaders
	) {}

	@NonNull
	private Boolean hasHeader(@NonNull List<@NonNull Header> headers,
														@NonNull String name) {
		requireNonNull(headers);
		requireNonNull(name);

		for (Header header : headers) {
			if (header.name().equalsIgnoreCase(name))
				return true;
		}

		return false;
	}

	@NonNull
	private Boolean hasHeaderToken(@NonNull List<@NonNull Header> headers,
																 @NonNull String name,
																 @NonNull String token) {
		requireNonNull(headers);
		requireNonNull(name);
		requireNonNull(token);

		for (Header header : headers) {
			if (!header.name().equalsIgnoreCase(name))
				continue;

			String value = header.value();

			if (value == null)
				continue;

			for (String part : value.split(",", -1)) {
				if (token.equalsIgnoreCase(part.trim()))
					return true;
			}
		}

		return false;
	}

	@NonNull
	protected String reasonPhraseForStatusCode(@NonNull Integer statusCode) {
		requireNonNull(statusCode);

		StatusCode formalStatusCode = StatusCode.fromStatusCode(statusCode).orElse(null);
		return formalStatusCode == null ? "Unknown" : formalStatusCode.getReasonPhrase();
	}

	@NonNull
	protected Boolean isAlreadySorted(@NonNull Set<?> set) {
		requireNonNull(set);
		return set instanceof SortedSet || set instanceof LinkedHashSet;
	}

	@NonNull
	private static List<String> normalizeHeaderValues(@NonNull Set<String> values) {
		requireNonNull(values);

		if (values.isEmpty())
			return List.of();

		List<String> normalizedValues;

		if (values instanceof SortedSet || values instanceof LinkedHashSet
				|| values.spliterator().hasCharacteristics(java.util.Spliterator.ORDERED)) {
			normalizedValues = new ArrayList<>(values.size());
			for (String value : values)
				normalizedValues.add(value == null ? "" : value);
		} else {
			SortedSet<String> sortedValues = new TreeSet<>();
			for (String value : values)
				sortedValues.add(value == null ? "" : value);
			normalizedValues = new ArrayList<>(sortedValues);
		}

		return normalizedValues;
	}


	protected void safelyLog(@NonNull LogEvent logEvent) {
		requireNonNull(logEvent);

		try {
			getLifecycleObserver().didReceiveLogEvent(logEvent);
		} catch (Throwable throwable) {
			LifecycleObserverLogFallback.report(throwable);
		}
	}

	protected void safelyCollectMetrics(@NonNull String message,
																			@NonNull Consumer<MetricsCollector> metricsConsumer) {
		requireNonNull(message);
		requireNonNull(metricsConsumer);

		MetricsCollector metricsCollector = this.metricsCollector;

		if (metricsCollector == null)
			return;

		try {
			metricsConsumer.accept(metricsCollector);
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.METRICS_COLLECTOR_FAILED, message)
					.throwable(throwable)
					.build());
		}
	}

	private void notifyDidTerminateResponseStream(@Nullable Request request,
																								@Nullable ResourceMethod resourceMethod,
																								@NonNull MarshaledResponse marshaledResponse,
																								@NonNull Instant establishedAt,
																								@NonNull Duration streamDuration,
																								@Nullable StreamTerminationReason cancelationReason,
																								@Nullable Throwable throwable) {
		requireNonNull(marshaledResponse);
		requireNonNull(establishedAt);
		requireNonNull(streamDuration);

		if (cancelationReason != null) {
			LogEventType logEventType = cancelationReason == StreamTerminationReason.PRODUCER_FAILED
					? LogEventType.RESPONSE_STREAM_FAILED
					: LogEventType.RESPONSE_STREAM_CANCELED;

			safelyLog(LogEvent.with(logEventType, format("Streaming response terminated: %s", cancelationReason.name()))
					.throwable(throwable)
					.request(request)
					.resourceMethod(resourceMethod)
					.marshaledResponse(marshaledResponse)
					.build());
		}

		if (request == null)
			return;

		StreamingResponseHandle streamingResponse = new DefaultStreamingResponseHandle(ServerType.HTTP,
				request, resourceMethod, marshaledResponse, establishedAt);
		StreamTermination termination = StreamTermination
				.with(cancelationReason == null ? StreamTerminationReason.COMPLETED : cancelationReason, streamDuration)
				.cause(throwable)
				.build();

		try {
			getLifecycleObserver().willTerminateResponseStream(streamingResponse, termination);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_TERMINATE_RESPONSE_STREAM_FAILED,
							format("An exception occurred while invoking %s::willTerminateResponseStream", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.request(request)
					.resourceMethod(resourceMethod)
					.marshaledResponse(marshaledResponse)
					.build());
		}

		try {
			getLifecycleObserver().didTerminateResponseStream(streamingResponse, termination);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_TERMINATE_RESPONSE_STREAM_FAILED,
							format("An exception occurred while invoking %s::didTerminateResponseStream", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.request(request)
					.resourceMethod(resourceMethod)
					.marshaledResponse(marshaledResponse)
					.build());
		}
	}

	private void notifyWillAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
		try {
			getLifecycleObserver().willAcceptConnection(ServerType.HTTP, remoteAddress);
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_ACCEPT_CONNECTION_FAILED,
							format("An exception occurred while invoking %s::willAcceptConnection", LifecycleObserver.class.getSimpleName()))
					.throwable(throwable)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::willAcceptConnection", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.willAcceptConnection(ServerType.HTTP, remoteAddressSnapshot));
	}

	private void notifyDidAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
		try {
			getLifecycleObserver().didAcceptConnection(ServerType.HTTP, remoteAddress);
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_ACCEPT_CONNECTION_FAILED,
							format("An exception occurred while invoking %s::didAcceptConnection", LifecycleObserver.class.getSimpleName()))
					.throwable(throwable)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didAcceptConnection", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didAcceptConnection(ServerType.HTTP, remoteAddressSnapshot));
	}

	private void notifyDidFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress,
																							 @NonNull ConnectionRejectionReason reason,
																							 @Nullable Throwable throwable) {
		requireNonNull(reason);

		try {
			getLifecycleObserver().didFailToAcceptConnection(ServerType.HTTP, remoteAddress, reason, throwable);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_FAIL_TO_ACCEPT_CONNECTION_FAILED,
							format("An exception occurred while invoking %s::didFailToAcceptConnection", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		ConnectionRejectionReason reasonSnapshot = reason;
		Throwable throwableSnapshot = throwable;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didFailToAcceptConnection", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didFailToAcceptConnection(ServerType.HTTP,
						remoteAddressSnapshot,
						reasonSnapshot,
						throwableSnapshot));
	}

	private void notifyWillAcceptRequest(@Nullable InetSocketAddress remoteAddress,
																			 @Nullable String requestTarget) {
		try {
			getLifecycleObserver().willAcceptRequest(ServerType.HTTP, remoteAddress, requestTarget);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_ACCEPT_REQUEST_FAILED,
							format("An exception occurred while invoking %s::willAcceptRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::willAcceptRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.willAcceptRequest(ServerType.HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidAcceptRequest(@Nullable InetSocketAddress remoteAddress,
																			@Nullable String requestTarget) {
		try {
			getLifecycleObserver().didAcceptRequest(ServerType.HTTP, remoteAddress, requestTarget);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_ACCEPT_REQUEST_FAILED,
							format("An exception occurred while invoking %s::didAcceptRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didAcceptRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didAcceptRequest(ServerType.HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidFailToAcceptRequest(@Nullable InetSocketAddress remoteAddress,
																						@Nullable String requestTarget,
																						@NonNull RequestRejectionReason reason,
																						@Nullable Throwable throwable) {
		requireNonNull(reason);

		try {
			getLifecycleObserver().didFailToAcceptRequest(ServerType.HTTP, remoteAddress, requestTarget, reason, throwable);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_FAIL_TO_ACCEPT_REQUEST_FAILED,
							format("An exception occurred while invoking %s::didFailToAcceptRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;
		RequestRejectionReason reasonSnapshot = reason;
		Throwable throwableSnapshot = throwable;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didFailToAcceptRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didFailToAcceptRequest(ServerType.HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot,
						reasonSnapshot,
						throwableSnapshot));
	}

	private void notifyWillReadRequest(@Nullable InetSocketAddress remoteAddress,
																		 @Nullable String requestTarget) {
		try {
			getLifecycleObserver().willReadRequest(ServerType.HTTP, remoteAddress, requestTarget);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_READ_REQUEST_FAILED,
							format("An exception occurred while invoking %s::willReadRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::willReadRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.willReadRequest(ServerType.HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidReadRequest(@Nullable InetSocketAddress remoteAddress,
																		@Nullable String requestTarget) {
		try {
			getLifecycleObserver().didReadRequest(ServerType.HTTP, remoteAddress, requestTarget);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_READ_REQUEST_FAILED,
							format("An exception occurred while invoking %s::didReadRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didReadRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didReadRequest(ServerType.HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidFailToReadRequest(@Nullable InetSocketAddress remoteAddress,
																					@Nullable String requestTarget,
																					@NonNull RequestReadFailureReason reason,
																					@Nullable Throwable throwable) {
		requireNonNull(reason);

		try {
			getLifecycleObserver().didFailToReadRequest(ServerType.HTTP, remoteAddress, requestTarget, reason, throwable);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_FAIL_TO_READ_REQUEST_FAILED,
							format("An exception occurred while invoking %s::didFailToReadRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;
		RequestReadFailureReason reasonSnapshot = reason;
		Throwable throwableSnapshot = throwable;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didFailToReadRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didFailToReadRequest(ServerType.HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot,
						reasonSnapshot,
						throwableSnapshot));
	}

	@NonNull
	private static RequestRejectionReason rejectionReasonFor(@NonNull ExecutorService executorService) {
		requireNonNull(executorService);

		if (executorService.isShutdown() || executorService.isTerminated())
			return RequestRejectionReason.REQUEST_HANDLER_EXECUTOR_SHUTDOWN;

		return RequestRejectionReason.REQUEST_HANDLER_QUEUE_FULL;
	}

	@NonNull
	protected Integer getPort() {
		return this.port;
	}

	@NonNull
	protected Integer getConcurrency() {
		return this.concurrency;
	}

	@NonNull
	protected String getHost() {
		return this.host;
	}

	@NonNull
	protected Duration getRequestHeaderTimeout() {
		return this.requestHeaderTimeout;
	}

	@NonNull
	protected Duration getRequestBodyTimeout() {
		return this.requestBodyTimeout;
	}

	@NonNull
	protected Duration getResponseWriteIdleTimeout() {
		return this.responseWriteIdleTimeout;
	}

	@NonNull
	protected ResponseCompressor getResponseCompressor() {
		return this.responseCompressor;
	}

	@NonNull
	protected RequestDecompressionPolicy getRequestDecompressionPolicy() {
		return this.requestDecompressionPolicy;
	}

	@NonNull
	protected Duration getRequestHandlerTimeout() {
		return this.requestHandlerTimeout;
	}

	private record EncodingPreference(@NonNull String coding,
																		@NonNull Integer q) {
		private EncodingPreference {
			requireNonNull(coding);
			requireNonNull(q);
		}

		@NonNull
		private static Optional<EncodingPreference> fromHeaderValue(@Nullable String headerValue) {
			String trimmed = Utilities.trimAggressivelyToNull(headerValue);

			if (trimmed == null)
				return Optional.empty();

			String[] parts = trimmed.split(";", -1);
			String coding = Utilities.trimAggressivelyToNull(parts[0]);

			if (coding == null)
				return Optional.empty();

			Integer q = 1000;

			for (int i = 1; i < parts.length; i++) {
				String part = Utilities.trimAggressivelyToNull(parts[i]);

				if (part == null)
					continue;

				int equalsIndex = part.indexOf('=');

				if (equalsIndex <= 0)
					continue;

				String name = Utilities.trimAggressivelyToNull(part.substring(0, equalsIndex));

				if (!"q".equalsIgnoreCase(name))
					continue;

				q = parseQ(part.substring(equalsIndex + 1));
			}

			return Optional.of(new EncodingPreference(coding.toLowerCase(ENGLISH), q));
		}

		@NonNull
		private static Integer parseQ(@Nullable String value) {
			String trimmed = Utilities.trimAggressivelyToNull(value);

			if (trimmed == null)
				return 0;

			try {
				double parsed = Double.parseDouble(trimmed);

				if (Double.isNaN(parsed) || parsed < 0.0D || parsed > 1.0D)
					return 0;

				return (int) Math.round(parsed * 1000.0D);
			} catch (NumberFormatException e) {
				return 0;
			}
		}
	}

	@NonNull
	protected Integer getRequestHandlerConcurrency() {
		return this.requestHandlerConcurrency;
	}

	@NonNull
	protected Integer getRequestHandlerQueueCapacity() {
		return this.requestHandlerQueueCapacity;
	}

	@NonNull
	protected Integer getStreamingQueueCapacityInBytes() {
		return this.streamingQueueCapacityInBytes;
	}

	@NonNull
	protected Integer getStreamingChunkSizeInBytes() {
		return this.streamingChunkSizeInBytes;
	}

	@NonNull
	protected Duration getStreamingResponseTimeout() {
		return this.streamingResponseTimeout;
	}

	@NonNull
	protected Duration getStreamingResponseIdleTimeout() {
		return this.streamingResponseIdleTimeout;
	}

	@NonNull
	protected Duration getSocketSelectTimeout() {
		return this.socketSelectTimeout;
	}

	@NonNull
	protected Duration getGracefulShutdownTimeout() {
		return this.lifecyclePolicy.getGracefulShutdownTimeout();
	}

	@NonNull
	protected Duration getForcedShutdownTimeout() {
		return this.lifecyclePolicy.getForcedShutdownTimeout();
	}

	@NonNull
	protected Integer getMaximumRequestSizeInBytes() {
		return this.maximumRequestSizeInBytes;
	}

	@NonNull
	protected Integer getMaximumRequestBodySizeInBytes() {
		return this.maximumRequestBodySizeInBytes;
	}

	@NonNull
	protected Integer getMaximumHeaderCount() {
		return this.maximumHeaderCount;
	}

	@NonNull
	protected Integer getMaximumHeadersSizeInBytes() {
		return this.maximumHeadersSizeInBytes;
	}

	@NonNull
	protected Integer getMaximumRequestTargetLengthInBytes() {
		return this.maximumRequestTargetLengthInBytes;
	}

	@NonNull
	protected Integer getRequestReadBufferSizeInBytes() {
		return this.requestReadBufferSizeInBytes;
	}

	@NonNull
	protected Integer getSocketPendingConnectionLimit() {
		return this.socketPendingConnectionLimit;
	}

	@NonNull
	protected Integer getConcurrentConnectionLimit() {
		return this.concurrentConnectionLimit;
	}

	@NonNull
	protected MultipartParser getMultipartParser() {
		return this.multipartParser;
	}

	@NonNull
	protected IdGenerator<?> getIdGenerator() {
		return this.idGenerator;
	}

	@NonNull
	protected Optional<ExecutorService> getRequestHandlerExecutorService() {
		return Optional.ofNullable(this.requestHandlerExecutorService);
	}

	@NonNull
	protected Optional<ExecutorService> getStreamingExecutorService() {
		return Optional.ofNullable(this.streamingExecutorService);
	}

	@NonNull
	protected Optional<ScheduledExecutorService> getStreamingTimeoutExecutorService() {
		return Optional.ofNullable(this.streamingTimeoutExecutorService);
	}

	@NonNull
	protected Optional<TimeoutScheduler> getRequestHandlerTimeoutScheduler() {
		return Optional.ofNullable(this.requestHandlerTimeoutScheduler);
	}

	@NonNull
	protected ReentrantLock getLock() {
		return this.lock;
	}

	@NonNull
	protected Optional<RequestHandler> getServerListener() {
		return Optional.ofNullable(this.requestHandler);
	}

	@NonNull
	protected Optional<EventLoop> getEventLoop() {
		return Optional.ofNullable(this.eventLoop);
	}

	@NonNull
	protected Supplier<ExecutorService> getRequestHandlerExecutorServiceSupplier() {
		return this.requestHandlerExecutorServiceSupplier;
	}

	@NonNull
	protected Supplier<ExecutorService> getStreamingExecutorServiceSupplier() {
		return this.streamingExecutorServiceSupplier;
	}

	@NonNull
	protected LifecycleObserver getLifecycleObserver() {
		return this.lifecycleObserver;
	}

	@NonNull
	protected Optional<RequestHandler> getRequestHandler() {
		return Optional.ofNullable(this.requestHandler);
	}

	@NonNull
	BuiltInTransportLifecycleAdapter getLifecycleAdapter() {
		return this.lifecycleAdapter;
	}

	void setStartSetupHookForTests(@NonNull Runnable startSetupHook) {
		this.startSetupHook = requireNonNull(startSetupHook);
	}

	@NonNull
	private HttpRuntimeSnapshot runtimeSnapshot() {
		return new HttpRuntimeSnapshot(this.eventLoop,
				this.requestHandlerExecutorService, this.streamingExecutorService,
				this.streamingTimeoutExecutorService,
				this.streamingForcedShutdownStarted,
				this.requestHandlerTimeoutScheduler);
	}

	private void releaseRuntimeSnapshot(@NonNull HttpRuntimeSnapshot snapshot) {
		requireNonNull(snapshot);
		if (sameInstance(this.eventLoop, snapshot.eventLoop()))
			this.eventLoop = null;
		if (sameInstance(this.requestHandlerExecutorService, snapshot.requestHandlerExecutor()))
			this.requestHandlerExecutorService = null;
		if (sameInstance(this.streamingExecutorService, snapshot.streamingExecutor()))
			this.streamingExecutorService = null;
		if (sameInstance(this.streamingTimeoutExecutorService, snapshot.streamingTimeoutExecutor()))
			this.streamingTimeoutExecutorService = null;
		if (sameInstance(this.streamingForcedShutdownStarted, snapshot.streamingForcedShutdownStarted()))
			this.streamingForcedShutdownStarted = null;
		if (this.requestHandlerTimeoutScheduler == snapshot.requestTimeoutScheduler())
			this.requestHandlerTimeoutScheduler = null;
	}

	private static boolean awaitExecutor(@Nullable ExecutorService executor,
			long absoluteDeadlineNanos) throws InterruptedException {
		if (executor == null || executor.isTerminated())
			return true;
		long remainingNanos = LifecycleDeadlines.remainingNanos(
				absoluteDeadlineNanos, System.nanoTime());
		return remainingNanos > 0L
				&& executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
	}

	private static boolean awaitScheduler(@Nullable TimeoutScheduler scheduler,
			long absoluteDeadlineNanos) throws InterruptedException {
		if (scheduler == null)
			return true;
		return scheduler.awaitTerminationUntil(absoluteDeadlineNanos);
	}

	private record HttpRuntimeSnapshot(
			@Nullable EventLoop eventLoop,
			@Nullable ExecutorService requestHandlerExecutor,
			@Nullable ExecutorService streamingExecutor,
			@Nullable ScheduledExecutorService streamingTimeoutExecutor,
			@Nullable AtomicBoolean streamingForcedShutdownStarted,
			@Nullable TimeoutScheduler requestTimeoutScheduler) {
	}

	private final class HttpLifecycleOperations
			implements BuiltInTransportLifecycleAdapter.Operations {
		@NonNull
		private final AtomicReference<HttpRuntimeSnapshot> retainedSnapshot =
				new AtomicReference<>();

		@Override
		public void quiesce() {
			HttpRuntimeSnapshot snapshot = runtimeSnapshot();
			this.retainedSnapshot.compareAndSet(null, snapshot);
			EventLoop eventLoop = snapshot.eventLoop();
			if (eventLoop != null) {
				eventLoop.stopAccepting();
				eventLoop.beginDrain();
			}
			ExecutorService requestHandlerExecutor = snapshot.requestHandlerExecutor();
			if (requestHandlerExecutor != null)
				requestHandlerExecutor.shutdown();
			ExecutorService streamingExecutor = snapshot.streamingExecutor();
			if (streamingExecutor != null)
				streamingExecutor.shutdown();
			ScheduledExecutorService streamingTimeoutExecutor = snapshot.streamingTimeoutExecutor();
			if (streamingTimeoutExecutor != null)
				streamingTimeoutExecutor.shutdown();
			TimeoutScheduler requestTimeoutScheduler = snapshot.requestTimeoutScheduler();
			if (requestTimeoutScheduler != null)
				requestTimeoutScheduler.shutdown();
		}

		@Override
		public void force() {
			quiesce();
			HttpRuntimeSnapshot snapshot = retained();
			AtomicBoolean streamingForcedShutdownStarted = snapshot.streamingForcedShutdownStarted();
			if (streamingForcedShutdownStarted != null)
				streamingForcedShutdownStarted.set(true);
			EventLoop eventLoop = snapshot.eventLoop();
			if (eventLoop != null)
				eventLoop.stopConnections();
			ExecutorService requestHandlerExecutor = snapshot.requestHandlerExecutor();
			if (requestHandlerExecutor != null)
				requestHandlerExecutor.shutdownNow();
			ExecutorService streamingExecutor = snapshot.streamingExecutor();
			if (streamingExecutor != null)
				streamingExecutor.shutdownNow();
			ScheduledExecutorService streamingTimeoutExecutor = snapshot.streamingTimeoutExecutor();
			if (streamingTimeoutExecutor != null)
				streamingTimeoutExecutor.shutdownNow();
			TimeoutScheduler requestTimeoutScheduler = snapshot.requestTimeoutScheduler();
			if (requestTimeoutScheduler != null)
				requestTimeoutScheduler.shutdownNow();
		}

		@Override
		public boolean awaitTermination(long absoluteDeadlineNanos)
				throws InterruptedException {
			HttpRuntimeSnapshot snapshot = retained();
			EventLoop eventLoop = snapshot.eventLoop();
			boolean eventLoopTerminated = eventLoop == null
					|| eventLoop.joinUntil(absoluteDeadlineNanos);
			boolean requestHandlersTerminated = awaitExecutor(
					snapshot.requestHandlerExecutor(), absoluteDeadlineNanos);
			boolean streamingTerminated = awaitExecutor(
					snapshot.streamingExecutor(), absoluteDeadlineNanos);
			boolean streamingTimeoutsTerminated = awaitExecutor(
					snapshot.streamingTimeoutExecutor(), absoluteDeadlineNanos);
			boolean requestTimeoutsTerminated = awaitScheduler(
					snapshot.requestTimeoutScheduler(), absoluteDeadlineNanos);
			return eventLoopTerminated && requestHandlersTerminated
					&& streamingTerminated && streamingTimeoutsTerminated
					&& requestTimeoutsTerminated;
		}

		@Override
		@NonNull
		public Set<InternalResidualActivityType> residualActivity() {
			HttpRuntimeSnapshot snapshot = retained();
			Set<InternalResidualActivityType> kinds =
					EnumSet.noneOf(InternalResidualActivityType.class);
			EventLoop eventLoop = snapshot.eventLoop();
			if (eventLoop != null && !eventLoop.isTerminated())
				kinds.add(InternalResidualActivityType.EVENT_LOOP);
			if (eventLoop != null && eventLoop.numAdmittedConnections() > 0)
				kinds.add(InternalResidualActivityType.CONNECTION);
			if (!terminated(snapshot.requestHandlerExecutor())
					|| !terminated(snapshot.streamingExecutor())
					|| !terminated(snapshot.streamingTimeoutExecutor())
					|| !terminated(snapshot.requestTimeoutScheduler()))
				kinds.add(InternalResidualActivityType.EXECUTOR_TASK);
			return Collections.unmodifiableSet(kinds);
		}

		@Override
		public void releaseTerminatedEvidence() {
			HttpRuntimeSnapshot snapshot = retained();
			releaseRuntimeSnapshot(snapshot);
			this.retainedSnapshot.compareAndSet(snapshot, null);
		}

		@NonNull
		private HttpRuntimeSnapshot retained() {
			HttpRuntimeSnapshot snapshot = this.retainedSnapshot.get();
			if (snapshot != null)
				return snapshot;
			snapshot = runtimeSnapshot();
			this.retainedSnapshot.compareAndSet(null, snapshot);
			return requireNonNull(this.retainedSnapshot.get());
		}

		private boolean terminated(@Nullable ExecutorService executor) {
			return executor == null || executor.isTerminated();
		}

		private boolean terminated(@Nullable TimeoutScheduler scheduler) {
			return scheduler == null || scheduler.isTerminated();
		}
	}

	@ThreadSafe
	protected static class NonvirtualThreadFactory implements ThreadFactory {
		@NonNull
		private final String namePrefix;
		@NonNull
		private final AtomicInteger idGenerator;

		public NonvirtualThreadFactory(@NonNull String namePrefix) {
			requireNonNull(namePrefix);

			this.namePrefix = namePrefix;
			this.idGenerator = new AtomicInteger(0);
		}

		@Override
		@NonNull
		public Thread newThread(@NonNull Runnable runnable) {
			String name = format("%s-%s", getNamePrefix(), getIdGenerator().incrementAndGet());
			return new Thread(runnable, name);
		}

		@NonNull
		protected String getNamePrefix() {
			return this.namePrefix;
		}

		@NonNull
		protected AtomicInteger getIdGenerator() {
			return this.idGenerator;
		}
	}
}
