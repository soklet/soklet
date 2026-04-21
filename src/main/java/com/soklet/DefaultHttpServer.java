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
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
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
import java.util.function.Consumer;
import java.util.function.Supplier;

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
	private static final Duration DEFAULT_REQUEST_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_REQUEST_HANDLER_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_SOCKET_SELECT_TIMEOUT;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_CONNECTIONS;
	@NonNull
	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT;
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

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_CONCURRENCY = Runtime.getRuntime().availableProcessors();
		DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_REQUEST_HANDLER_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_SOCKET_SELECT_TIMEOUT = Duration.ofMillis(100);
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024 * 10;
		DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES = 1_024 * 64;
		DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT = 0;
		DEFAULT_MAXIMUM_CONNECTIONS = 0;
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
		DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY_MULTIPLIER = 64;
		DEFAULT_VIRTUAL_REQUEST_HANDLER_CONCURRENCY_MULTIPLIER = 16;
		DEFAULT_STREAMING_QUEUE_CAPACITY_IN_BYTES = 1_024 * 1_024;
		DEFAULT_STREAMING_CHUNK_SIZE_IN_BYTES = 1_024 * 16;
		DEFAULT_STREAMING_RESPONSE_TIMEOUT = Duration.ZERO;
		DEFAULT_NONVIRTUAL_STREAMING_CONCURRENCY_MULTIPLIER = 4;
	}

	@NonNull
	private final Integer port;
	@NonNull
	private final String host;
	@NonNull
	private final Integer concurrency;
	@NonNull
	private final Duration requestTimeout;
	@NonNull
	private final Duration requestHandlerTimeout;
	@NonNull
	private final Integer requestHandlerConcurrency;
	@NonNull
	private final Integer requestHandlerQueueCapacity;
	@NonNull
	private final Duration socketSelectTimeout;
	@NonNull
	private final Duration shutdownTimeout;
	@NonNull
	private final Integer maximumRequestSizeInBytes;
	@NonNull
	private final Integer requestReadBufferSizeInBytes;
	@NonNull
	private final Integer socketPendingConnectionLimit;
	@NonNull
	private final Integer maximumConnections;
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
	private volatile TimeoutScheduler requestHandlerTimeoutScheduler;
	@Nullable
	private volatile RequestHandler requestHandler;
	@Nullable
	private volatile LifecycleObserver lifecycleObserver;
	@Nullable
	private volatile MetricsCollector metricsCollector;
	@Nullable
	private volatile EventLoop eventLoop;

	protected DefaultHttpServer(@NonNull Builder builder) {
		requireNonNull(builder);

		this.lock = new ReentrantLock();

		this.port = builder.port;
		this.host = builder.host != null ? builder.host : DEFAULT_HOST;
		this.concurrency = builder.concurrency != null ? builder.concurrency : DEFAULT_CONCURRENCY;
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.requestReadBufferSizeInBytes = builder.requestReadBufferSizeInBytes != null ? builder.requestReadBufferSizeInBytes : DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
		this.requestTimeout = builder.requestTimeout != null ? builder.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
		this.requestHandlerTimeout = builder.requestHandlerTimeout != null ? builder.requestHandlerTimeout : DEFAULT_REQUEST_HANDLER_TIMEOUT;
		this.socketSelectTimeout = builder.socketSelectTimeout != null ? builder.socketSelectTimeout : DEFAULT_SOCKET_SELECT_TIMEOUT;
		this.socketPendingConnectionLimit = builder.socketPendingConnectionLimit != null ? builder.socketPendingConnectionLimit : DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT;
		this.maximumConnections = builder.maximumConnections != null ? builder.maximumConnections : DEFAULT_MAXIMUM_CONNECTIONS;
		this.shutdownTimeout = builder.shutdownTimeout != null ? builder.shutdownTimeout : DEFAULT_SHUTDOWN_TIMEOUT;
		this.multipartParser = builder.multipartParser != null ? builder.multipartParser : DefaultMultipartParser.defaultInstance();
		this.idGenerator = builder.idGenerator != null ? builder.idGenerator : IdGenerator.defaultInstance();

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
				: this.requestTimeout;

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

		if (this.maximumConnections < 0)
			throw new IllegalArgumentException("Maximum connections must be >= 0");
	}

	@Override
	public void start() {
		getLock().lock();

		try {
			if (isStarted())
				return;

			if (getRequestHandler().isEmpty())
				throw new IllegalStateException(format("No %s was registered for %s", RequestHandler.class, getClass()));

			if (getLifecycleObserver().isEmpty())
				throw new IllegalStateException(format("No %s was registered for %s", LifecycleObserver.class, getClass()));

			Options options = OptionsBuilder.newBuilder()
					.withHost(getHost())
					.withPort(getPort())
					.withConcurrency(getConcurrency())
					.withRequestTimeout(getRequestTimeout())
					.withResolution(getSocketSelectTimeout())
					.withReadBufferSize(getRequestReadBufferSizeInBytes())
					.withMaxRequestSize(getMaximumRequestSizeInBytes())
					.withAcceptLength(getSocketPendingConnectionLimit())
					.withMaxConnections(getMaximumConnections())
					.build();

			Logger logger = new Logger() {
				@Override
				public boolean enabled() {
					return false;
				}

				@Override
				public void log(@Nullable LogEntry... logEntries) {
					// No-op
				}

				@Override
				public void log(@Nullable Exception e,
												@Nullable LogEntry... logEntries) {
					// No-op
				}
			};

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
			};

			Handler handler = ((microhttpRequest, microHttpCallback) -> {
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

						Thread handlerThread = handlerThreadRef.get();
						if (handlerThread != null)
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

							if (body != null && body.length == 0)
								body = null;

							boolean contentTooLarge = microhttpRequest.contentTooLarge();

							HttpMethod httpMethod;

							try {
								String normalizedMethod = trimAggressivelyToEmpty(microhttpRequest.method()).toUpperCase(ENGLISH);

								if (normalizedMethod.equals("PRI"))
									throw new IllegalRequestException("HTTP/2.0 Connection Preface specified, but Soklet only supports HTTP/1.1");

								httpMethod = HttpMethod.valueOf(normalizedMethod);
							} catch (IllegalArgumentException e) {
								throw new IllegalRequestException(format("Unsupported HTTP method specified: '%s'", microhttpRequest.method()));
							}

							request = Request.withRawUrl(httpMethod, microhttpRequest.uri())
									.multipartParser(getMultipartParser())
									.idGenerator(getIdGenerator())
									.microhttpHeaders(microhttpRequest.headers())
									.body(body)
									.remoteAddress(microhttpRequest.remoteAddress())
									.contentTooLarge(contentTooLarge)
									.build();

							notifyDidReadRequest(remoteAddress, requestTarget);

							Request requestForResponse = request;

							requestHandler.handleRequest(requestForResponse, (requestResult -> {
								try {
									MicrohttpResponse microhttpResponse = toMicrohttpResponse(requestForResponse,
											requestResult.getResourceMethod().orElse(null),
											requestResult.getMarshaledResponse());
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
								safelyLog(LogEvent.with(LogEventType.SERVER_UNPARSEABLE_REQUEST, t.getMessage())
										.throwable(t)
										.build());
							} else if (t instanceof URISyntaxException) {
								failsafeStatusCode = 400;
								failureReason = RequestReadFailureReason.UNPARSEABLE_REQUEST;
								safelyLog(LogEvent.with(LogEventType.SERVER_UNPARSEABLE_REQUEST, format("Unable to parse request URI: %s", microhttpRequest.uri()))
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

			this.requestHandlerExecutorService = getRequestHandlerExecutorServiceSupplier().get();
			this.streamingExecutorService = getStreamingExecutorServiceSupplier().get();
			this.streamingTimeoutExecutorService = new ScheduledThreadPoolExecutor(1, new NonvirtualThreadFactory("streaming-timeout"));
			this.requestHandlerTimeoutScheduler = new TimeoutScheduler(new NonvirtualThreadFactory("request-handler-timeout"));
			EventLoop eventLoop = null;

			try {
				eventLoop = new EventLoop(options, logger, handler, connectionListener);
				eventLoop.start();
				this.eventLoop = eventLoop;
			} catch (BindException e) {
				cleanupFailedStart(eventLoop);
				throw new UncheckedIOException(format("Soklet was unable to start the HTTP server - port %d is already in use.", options.port()), e);
			} catch (IOException e) {
				cleanupFailedStart(eventLoop);
				throw new UncheckedIOException(e);
			} catch (RuntimeException e) {
				cleanupFailedStart(eventLoop);
				throw e;
			}
		} finally {
			getLock().unlock();
		}
	}

	@Override
	public void stop() {
		getLock().lock();

		try {
			if (!isStarted())
				return;

			try {
				getEventLoop().get().stop();
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Unable to shut down server event loop")
						.throwable(e)
						.build());
			}

			boolean interrupted = false;

			try {
				ExecutorService requestHandlerExecutorService = getRequestHandlerExecutorService().orElse(null);

				if (requestHandlerExecutorService != null) {
					// Start graceful shutdown (no new tasks)
					requestHandlerExecutorService.shutdown();

					// Single wall-clock budget for the whole server shutdown
					final long deadlineNanos = System.nanoTime() + getShutdownTimeout().toNanos();

					// First: wait gracefully up to the remaining budget
					long remMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
					boolean done = remMillis == 0L || requestHandlerExecutorService.awaitTermination(remMillis, TimeUnit.MILLISECONDS);

					if (!done) {
						// Escalate: interrupt running tasks
						requestHandlerExecutorService.shutdownNow();

						// Small best-effort wait with whatever time remains
						remMillis = Math.max(100L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
						requestHandlerExecutorService.awaitTermination(remMillis, TimeUnit.MILLISECONDS);
					}
				}

				ExecutorService streamingExecutorService = getStreamingExecutorService().orElse(null);

				if (streamingExecutorService != null) {
					streamingExecutorService.shutdown();

					final long deadlineNanos = System.nanoTime() + getShutdownTimeout().toNanos();
					long remMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
					boolean done = remMillis == 0L || streamingExecutorService.awaitTermination(remMillis, TimeUnit.MILLISECONDS);

					if (!done) {
						streamingExecutorService.shutdownNow();
						remMillis = Math.max(100L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
						streamingExecutorService.awaitTermination(remMillis, TimeUnit.MILLISECONDS);
					}
				}

				ScheduledExecutorService streamingTimeoutExecutorService = getStreamingTimeoutExecutorService().orElse(null);

				if (streamingTimeoutExecutorService != null) {
					streamingTimeoutExecutorService.shutdownNow();
				}

				TimeoutScheduler requestHandlerTimeoutScheduler = getRequestHandlerTimeoutScheduler().orElse(null);

				if (requestHandlerTimeoutScheduler != null) {
					requestHandlerTimeoutScheduler.shutdown();
					long remMillis = Math.max(0L, getShutdownTimeout().toMillis());
					requestHandlerTimeoutScheduler.awaitTermination(remMillis, TimeUnit.MILLISECONDS);
					requestHandlerTimeoutScheduler.shutdownNow();
				}
			} catch (InterruptedException e) {
				interrupted = true;
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR,
								"Unable to shut down server request handler executor service")
						.throwable(e)
						.build());
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		} finally {
			this.eventLoop = null;
			this.requestHandlerExecutorService = null;
			this.streamingExecutorService = null;
			this.streamingTimeoutExecutorService = null;
			this.requestHandlerTimeoutScheduler = null;

			getLock().unlock();
		}
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
		byte[] body = format("HTTP %d: %s", statusCode, reasonPhrase).getBytes(charset);

		return new MicrohttpResponse(statusCode, reasonPhrase, headers, body);
	}

	private void cancelTimeout(TimeoutScheduler.@Nullable ScheduledTask timeoutTask) {
		if (timeoutTask != null)
			timeoutTask.cancel();
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

			for (String part : value.split(",")) {
				if ("close".equalsIgnoreCase(part.trim()))
					return true;
			}
		}

		return false;
	}

	@NonNull
	@Override
	public Boolean isStarted() {
		getLock().lock();

		try {
			return getEventLoop().isPresent();
		} finally {
			getLock().unlock();
		}
	}

	@Override
	public void initialize(@NonNull SokletConfig sokletConfig,
												 @NonNull RequestHandler requestHandler) {
		requireNonNull(requestHandler);
		requireNonNull(sokletConfig);

		this.requestHandler = requestHandler;
		this.lifecycleObserver = sokletConfig.getLifecycleObserver();
		this.metricsCollector = sokletConfig.getMetricsCollector();
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
		return toMicrohttpResponse(null, null, marshaledResponse);
	}

	@NonNull
	protected MicrohttpResponse toMicrohttpResponse(@Nullable Request request,
																									@Nullable ResourceMethod resourceMethod,
																									@NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(marshaledResponse);

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
		StreamingResponseBody stream = marshaledResponse.getStream().orElse(null);

		if (stream != null) {
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
					stream,
					streamingExecutorService,
					streamingTimeoutExecutorService,
					getStreamingQueueCapacityInBytes(),
					getStreamingChunkSizeInBytes(),
					deadline,
					idleTimeout,
					(streamDuration, cancelationReason, throwable) ->
							notifyDidTerminateResponseStream(request, resourceMethod, marshaledResponse, streamDuration, cancelationReason, throwable),
					(throwable) -> safelyLog(LogEvent.with(LogEventType.RESPONSE_STREAM_CANCELATION_CALLBACK_FAILED,
									"An exception occurred while invoking a streaming response cancelation callback")
							.throwable(throwable)
							.request(request)
							.resourceMethod(resourceMethod)
							.marshaledResponse(marshaledResponse)
							.build()));
		}

		MarshaledResponseBody body = marshaledResponse.getBody().orElse(null);

		if (body == null)
			return new MicrohttpResponse(marshaledResponse.getStatusCode(), reasonPhrase, headers, emptyByteArray());

		if (body instanceof MarshaledResponseBody.Bytes bytes)
			return new MicrohttpResponse(marshaledResponse.getStatusCode(), reasonPhrase, headers, bytes.getBytes());

		if (body instanceof MarshaledResponseBody.File file)
			return MicrohttpResponse.withFileBody(
					marshaledResponse.getStatusCode(),
					reasonPhrase,
					headers,
					file.getPath(),
					file.getOffset(),
					file.getCount());

		if (body instanceof MarshaledResponseBody.FileChannel fileChannel)
			return MicrohttpResponse.withFileChannelBody(
					marshaledResponse.getStatusCode(),
					reasonPhrase,
					headers,
					fileChannel.getChannel(),
					fileChannel.getOffset(),
					fileChannel.getCount(),
					fileChannel.getCloseOnComplete());

		if (body instanceof MarshaledResponseBody.ByteBuffer byteBuffer)
			return MicrohttpResponse.withByteBufferBody(
					marshaledResponse.getStatusCode(),
					reasonPhrase,
					headers,
					byteBuffer.getBuffer());

		throw new IllegalStateException(format("Unsupported marshaled response body type: %s", body.getClass().getName()));
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

		if (values instanceof SortedSet || values instanceof LinkedHashSet) {
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
			getLifecycleObserver().ifPresent(lifecycleObserver -> lifecycleObserver.didReceiveLogEvent(logEvent));
		} catch (Throwable throwable) {
			// The LifecycleObserver implementation errored out, but we can't let that affect us - swallow its exception.
			// Not much else we can do here but dump to stderr
			throwable.printStackTrace(System.err);
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
																								@NonNull Duration streamDuration,
																								@Nullable StreamingResponseCancelationReason cancelationReason,
																								@Nullable Throwable throwable) {
		requireNonNull(marshaledResponse);
		requireNonNull(streamDuration);

		if (cancelationReason != null) {
			LogEventType logEventType = cancelationReason == StreamingResponseCancelationReason.PRODUCER_FAILED
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

		try {
			getLifecycleObserver().ifPresent(lifecycleObserver ->
					lifecycleObserver.didTerminateResponseStream(ServerType.STANDARD_HTTP,
							request,
							resourceMethod,
							marshaledResponse,
							streamDuration,
							cancelationReason,
							throwable));
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
			getLifecycleObserver().ifPresent(lifecycleObserver ->
					lifecycleObserver.willAcceptConnection(ServerType.STANDARD_HTTP, remoteAddress));
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_ACCEPT_CONNECTION_FAILED,
							format("An exception occurred while invoking %s::willAcceptConnection", LifecycleObserver.class.getSimpleName()))
					.throwable(throwable)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::willAcceptConnection", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.willAcceptConnection(ServerType.STANDARD_HTTP, remoteAddressSnapshot));
	}

	private void notifyDidAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
		try {
			getLifecycleObserver().ifPresent(lifecycleObserver ->
					lifecycleObserver.didAcceptConnection(ServerType.STANDARD_HTTP, remoteAddress));
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_ACCEPT_CONNECTION_FAILED,
							format("An exception occurred while invoking %s::didAcceptConnection", LifecycleObserver.class.getSimpleName()))
					.throwable(throwable)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didAcceptConnection", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didAcceptConnection(ServerType.STANDARD_HTTP, remoteAddressSnapshot));
	}

	private void notifyDidFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress,
																							 @NonNull ConnectionRejectionReason reason,
																							 @Nullable Throwable throwable) {
		requireNonNull(reason);

		try {
			getLifecycleObserver().ifPresent(lifecycleObserver ->
					lifecycleObserver.didFailToAcceptConnection(ServerType.STANDARD_HTTP, remoteAddress, reason, throwable));
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
				(metricsCollector) -> metricsCollector.didFailToAcceptConnection(ServerType.STANDARD_HTTP,
						remoteAddressSnapshot,
						reasonSnapshot,
						throwableSnapshot));
	}

	private void notifyWillAcceptRequest(@Nullable InetSocketAddress remoteAddress,
																			 @Nullable String requestTarget) {
		try {
			getLifecycleObserver().ifPresent(lifecycleObserver ->
					lifecycleObserver.willAcceptRequest(ServerType.STANDARD_HTTP, remoteAddress, requestTarget));
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
				(metricsCollector) -> metricsCollector.willAcceptRequest(ServerType.STANDARD_HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidAcceptRequest(@Nullable InetSocketAddress remoteAddress,
																			@Nullable String requestTarget) {
		try {
			getLifecycleObserver().ifPresent(lifecycleObserver ->
					lifecycleObserver.didAcceptRequest(ServerType.STANDARD_HTTP, remoteAddress, requestTarget));
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
				(metricsCollector) -> metricsCollector.didAcceptRequest(ServerType.STANDARD_HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidFailToAcceptRequest(@Nullable InetSocketAddress remoteAddress,
																						@Nullable String requestTarget,
																						@NonNull RequestRejectionReason reason,
																						@Nullable Throwable throwable) {
		requireNonNull(reason);

		try {
			getLifecycleObserver().ifPresent(lifecycleObserver ->
					lifecycleObserver.didFailToAcceptRequest(ServerType.STANDARD_HTTP, remoteAddress, requestTarget, reason, throwable));
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
				(metricsCollector) -> metricsCollector.didFailToAcceptRequest(ServerType.STANDARD_HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot,
						reasonSnapshot,
						throwableSnapshot));
	}

	private void notifyWillReadRequest(@Nullable InetSocketAddress remoteAddress,
																		 @Nullable String requestTarget) {
		try {
			getLifecycleObserver().ifPresent(lifecycleObserver ->
					lifecycleObserver.willReadRequest(ServerType.STANDARD_HTTP, remoteAddress, requestTarget));
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
				(metricsCollector) -> metricsCollector.willReadRequest(ServerType.STANDARD_HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidReadRequest(@Nullable InetSocketAddress remoteAddress,
																		@Nullable String requestTarget) {
		try {
			getLifecycleObserver().ifPresent(lifecycleObserver ->
					lifecycleObserver.didReadRequest(ServerType.STANDARD_HTTP, remoteAddress, requestTarget));
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
				(metricsCollector) -> metricsCollector.didReadRequest(ServerType.STANDARD_HTTP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidFailToReadRequest(@Nullable InetSocketAddress remoteAddress,
																					@Nullable String requestTarget,
																					@NonNull RequestReadFailureReason reason,
																					@Nullable Throwable throwable) {
		requireNonNull(reason);

		try {
			getLifecycleObserver().ifPresent(lifecycleObserver ->
					lifecycleObserver.didFailToReadRequest(ServerType.STANDARD_HTTP, remoteAddress, requestTarget, reason, throwable));
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
				(metricsCollector) -> metricsCollector.didFailToReadRequest(ServerType.STANDARD_HTTP,
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
	protected Duration getRequestTimeout() {
		return this.requestTimeout;
	}

	@NonNull
	protected Duration getRequestHandlerTimeout() {
		return this.requestHandlerTimeout;
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
	protected Duration getShutdownTimeout() {
		return this.shutdownTimeout;
	}

	@NonNull
	protected Integer getMaximumRequestSizeInBytes() {
		return this.maximumRequestSizeInBytes;
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
	protected Integer getMaximumConnections() {
		return this.maximumConnections;
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
	protected Optional<LifecycleObserver> getLifecycleObserver() {
		return Optional.ofNullable(this.lifecycleObserver);
	}

	@NonNull
	protected Optional<RequestHandler> getRequestHandler() {
		return Optional.ofNullable(this.requestHandler);
	}

	private void cleanupFailedStart(@Nullable EventLoop eventLoop) {
		if (eventLoop != null) {
			try {
				eventLoop.stop();
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Unable to shut down server event loop after failed start")
						.throwable(e)
						.build());
			}
		}

		ExecutorService requestHandlerExecutorService = this.requestHandlerExecutorService;

		if (requestHandlerExecutorService != null) {
			requestHandlerExecutorService.shutdownNow();
		}

		ExecutorService streamingExecutorService = this.streamingExecutorService;

		if (streamingExecutorService != null) {
			streamingExecutorService.shutdownNow();
		}

		ScheduledExecutorService streamingTimeoutExecutorService = this.streamingTimeoutExecutorService;

		if (streamingTimeoutExecutorService != null) {
			streamingTimeoutExecutorService.shutdownNow();
		}

		TimeoutScheduler requestHandlerTimeoutScheduler = this.requestHandlerTimeoutScheduler;

		if (requestHandlerTimeoutScheduler != null) {
			requestHandlerTimeoutScheduler.shutdownNow();
		}

		this.eventLoop = null;
		this.requestHandlerExecutorService = null;
		this.streamingExecutorService = null;
		this.streamingTimeoutExecutorService = null;
		this.requestHandlerTimeoutScheduler = null;
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
