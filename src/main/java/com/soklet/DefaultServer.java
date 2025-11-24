/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.Handler;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.LogEntry;
import com.soklet.internal.microhttp.Logger;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.Options;
import com.soklet.internal.microhttp.OptionsBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.soklet.Utilities.emptyByteArray;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultServer implements Server {
	@Nonnull
	private static final String DEFAULT_HOST;
	@Nonnull
	private static final Integer DEFAULT_CONCURRENCY;
	@Nonnull
	private static final Duration DEFAULT_REQUEST_TIMEOUT;
	@Nonnull
	private static final Duration DEFAULT_SOCKET_SELECT_TIMEOUT;
	@Nonnull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
	@Nonnull
	private static final Integer DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
	@Nonnull
	private static final Integer DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT;
	@Nonnull
	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT;

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_CONCURRENCY = Runtime.getRuntime().availableProcessors();
		DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_SOCKET_SELECT_TIMEOUT = Duration.ofMillis(100);
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024 * 10;
		DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES = 1_024 * 64;
		DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT = 0;
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
	}

	@Nonnull
	private final Integer port;
	@Nonnull
	private final String host;
	@Nonnull
	private final Integer concurrency;
	@Nonnull
	private final Duration requestTimeout;
	@Nonnull
	private final Duration socketSelectTimeout;
	@Nonnull
	private final Duration shutdownTimeout;
	@Nonnull
	private final Integer maximumRequestSizeInBytes;
	@Nonnull
	private final Integer requestReadBufferSizeInBytes;
	@Nonnull
	private final Integer socketPendingConnectionLimit;
	@Nonnull
	private final MultipartParser multipartParser;
	@Nonnull
	private final ReentrantLock lock;
	@Nonnull
	private final Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
	@Nullable
	private volatile ExecutorService requestHandlerExecutorService;
	@Nullable
	private volatile RequestHandler requestHandler;
	@Nullable
	private volatile LifecycleInterceptor lifecycleInterceptor;
	@Nullable
	private volatile EventLoop eventLoop;

	protected DefaultServer(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.lock = new ReentrantLock();

		this.port = builder.port;
		this.host = builder.host != null ? builder.host : DEFAULT_HOST;
		this.concurrency = builder.concurrency != null ? builder.concurrency : DEFAULT_CONCURRENCY;
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.requestReadBufferSizeInBytes = builder.requestReadBufferSizeInBytes != null ? builder.requestReadBufferSizeInBytes : DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
		this.requestTimeout = builder.requestTimeout != null ? builder.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
		this.socketSelectTimeout = builder.socketSelectTimeout != null ? builder.socketSelectTimeout : DEFAULT_SOCKET_SELECT_TIMEOUT;
		this.socketPendingConnectionLimit = builder.socketPendingConnectionLimit != null ? builder.socketPendingConnectionLimit : DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT;
		this.shutdownTimeout = builder.shutdownTimeout != null ? builder.shutdownTimeout : DEFAULT_SHUTDOWN_TIMEOUT;
		this.multipartParser = builder.multipartParser != null ? builder.multipartParser : DefaultMultipartParser.defaultInstance();
		this.requestHandlerExecutorServiceSupplier = builder.requestHandlerExecutorServiceSupplier != null ? builder.requestHandlerExecutorServiceSupplier : () -> {
			String threadNamePrefix = "request-handler-";

			if (Utilities.virtualThreadsAvailable())
				return Utilities.createVirtualThreadsNewThreadPerTaskExecutor(threadNamePrefix, (Thread thread, Throwable throwable) -> {
					safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Unexpected exception occurred during server HTTP request processing")
							.throwable(throwable)
							.build());
				});

			return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), new NonvirtualThreadFactory(threadNamePrefix));
		};
	}

	@Override
	public void start() {
		getLock().lock();

		try {
			if (isStarted())
				return;

			Options options = OptionsBuilder.newBuilder()
					.withHost(getHost())
					.withPort(getPort())
					.withConcurrency(getConcurrency())
					.withRequestTimeout(getRequestTimeout())
					.withResolution(getSocketSelectTimeout())
					.withReadBufferSize(getRequestReadBufferSizeInBytes())
					.withMaxRequestSize(getMaximumRequestSizeInBytes())
					.withAcceptLength(getSocketPendingConnectionLimit())
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

			Handler handler = ((microhttpRequest, microHttpCallback) -> {
				ExecutorService requestHandlerExecutorServiceReference = this.requestHandlerExecutorService;

				if (requestHandlerExecutorServiceReference == null)
					return;

				requestHandlerExecutorServiceReference.submit(() -> {
					RequestHandler requestHandler = getRequestHandler().orElse(null);

					if (requestHandler == null)
						return;

					AtomicBoolean shouldWriteFailsafeResponse = new AtomicBoolean(true);

					try {
						// Normalize body
						byte[] body = microhttpRequest.body();

						if (body != null && body.length == 0)
							body = null;

						Map<String, Set<String>> headers = headersFromMicrohttpRequest(microhttpRequest);

						// Special case: look for a poison-pill header that indicates "content too large",
						// make a note of it, and then remove it from the request.
						// This header is specially set for Soklet inside of Microhttp's connection event loop.
						boolean contentTooLarge = false;

						if (headers.containsKey("com.soklet.CONTENT_TOO_LARGE")) {
							headers.remove("com.soklet.CONTENT_TOO_LARGE");
							contentTooLarge = true;
						}

						Request request = Request.with(HttpMethod.valueOf(microhttpRequest.method().toUpperCase(ENGLISH)), microhttpRequest.uri())
								.multipartParser(getMultipartParser())
								.headers(headers)
								.body(body)
								.contentTooLarge(contentTooLarge)
								.build();

						requestHandler.handleRequest(request, (requestResult -> {
							try {
								MicrohttpResponse microhttpResponse = toMicrohttpResponse(requestResult.getMarshaledResponse());
								shouldWriteFailsafeResponse.set(false);

								try {
									microHttpCallback.accept(microhttpResponse);
								} catch (Throwable t) {
									safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Unable to write response")
											.throwable(t)
											.build());
								}
							} catch (Throwable t) {
								safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An error occurred while marshaling to a response")
										.throwable(t)
										.build());

								try {
									microHttpCallback.accept(provideMicrohttpFailsafeResponse(microhttpRequest, t));
								} catch (Throwable t2) {
									safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An error occurred while writing a failsafe response")
											.throwable(t2)
											.build());
								}
							}
						}));
					} catch (Throwable t) {
						if (t instanceof URISyntaxException) {
							safelyLog(LogEvent.with(LogEventType.SERVER_UNPARSEABLE_REQUEST, format("Unable to parse request URI: %s", microhttpRequest.uri()))
									.throwable(t)
									.build());
						} else {
							safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An unexpected error occurred during request handling")
									.throwable(t)
									.build());
						}

						if (shouldWriteFailsafeResponse.get()) {
							try {
								microHttpCallback.accept(provideMicrohttpFailsafeResponse(microhttpRequest, t));
							} catch (Throwable t2) {
								safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An error occurred while writing a failsafe response")
										.throwable(t2)
										.build());
							}
						}
					}
				});
			});

			this.requestHandlerExecutorService = getRequestHandlerExecutorServiceSupplier().get();

			try {
				this.eventLoop = new EventLoop(options, logger, handler);
				eventLoop.start();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
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

			getLock().unlock();
		}
	}

	@Nonnull
	protected MicrohttpResponse provideMicrohttpFailsafeResponse(@Nonnull MicrohttpRequest microhttpRequest,
																															 @Nonnull Throwable throwable) {
		requireNonNull(microhttpRequest);
		requireNonNull(throwable);

		Integer statusCode = 500;
		Charset charset = StandardCharsets.UTF_8;
		String reasonPhrase = StatusCode.fromStatusCode(statusCode).get().getReasonPhrase();
		List<Header> headers = List.of(new Header("Content-Type", format("text/plain; charset=%s", charset.name())));
		byte[] body = format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(charset);

		return new MicrohttpResponse(statusCode, reasonPhrase, headers, body);
	}

	@Nonnull
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
	public void initialize(@Nonnull SokletConfig sokletConfig,
												 @Nonnull RequestHandler requestHandler) {
		requireNonNull(requestHandler);
		requireNonNull(sokletConfig);

		this.requestHandler = requestHandler;
		this.lifecycleInterceptor = sokletConfig.getLifecycleInterceptor();
	}

	@Nonnull
	protected Map<String, Set<String>> headersFromMicrohttpRequest(@Nonnull MicrohttpRequest microhttpRequest) {
		requireNonNull(microhttpRequest);

		// Turn Microhttp headers back into "name: value" lines for consumption by the Soklet parser/normalizer
		List<String> rawHeaderLines = microhttpRequest.headers().stream()
				.map(header -> format("%s: %s", header.name(), header.value() == null ? "" : header.value()))
				.collect(Collectors.toList());

		return Utilities.extractHeadersFromRawHeaderLines(rawHeaderLines);
	}

	@Nonnull
	protected MicrohttpResponse toMicrohttpResponse(@Nonnull MarshaledResponse marshaledResponse) {
		requireNonNull(marshaledResponse);

		List<Header> headers = new ArrayList<>();

		// Non-cookies headers get their values comma-separated
		for (Map.Entry<String, Set<String>> entry : marshaledResponse.getHeaders().entrySet()) {
			String name = entry.getKey();
			Set<String> values = entry.getValue();

			// Force natural ordering for consistent output if the set is not already sorted.
			if (!isAlreadySorted(values))
				values = new TreeSet<>(entry.getValue());

			for (String value : values)
				headers.add(new Header(name, value == null ? "" : value));
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
		byte[] body = marshaledResponse.getBody().orElse(emptyByteArray());

		return new MicrohttpResponse(marshaledResponse.getStatusCode(), reasonPhrase, headers, body);
	}

	@Nonnull
	protected String reasonPhraseForStatusCode(@Nonnull Integer statusCode) {
		requireNonNull(statusCode);

		StatusCode formalStatusCode = StatusCode.fromStatusCode(statusCode).orElse(null);
		return formalStatusCode == null ? "Unknown" : formalStatusCode.getReasonPhrase();
	}

	@Nonnull
	protected Boolean isAlreadySorted(@Nonnull Set<?> set) {
		requireNonNull(set);
		return set instanceof SortedSet || set instanceof LinkedHashSet;
	}

	protected void safelyLog(@Nonnull LogEvent logEvent) {
		requireNonNull(logEvent);

		try {
			getLifecycleInterceptor().ifPresent(lifecycleInterceptor -> lifecycleInterceptor.didReceiveLogEvent(logEvent));
		} catch (Throwable throwable) {
			// The LifecycleInterceptor implementation errored out, but we can't let that affect us - swallow its exception.
			// Not much else we can do here but dump to stderr
			throwable.printStackTrace();
		}
	}

	@Nonnull
	protected Integer getPort() {
		return this.port;
	}

	@Nonnull
	protected Integer getConcurrency() {
		return this.concurrency;
	}

	@Nonnull
	protected String getHost() {
		return this.host;
	}

	@Nonnull
	protected Duration getRequestTimeout() {
		return this.requestTimeout;
	}

	@Nonnull
	protected Duration getSocketSelectTimeout() {
		return this.socketSelectTimeout;
	}

	@Nonnull
	protected Duration getShutdownTimeout() {
		return this.shutdownTimeout;
	}

	@Nonnull
	protected Integer getMaximumRequestSizeInBytes() {
		return this.maximumRequestSizeInBytes;
	}

	@Nonnull
	protected Integer getRequestReadBufferSizeInBytes() {
		return this.requestReadBufferSizeInBytes;
	}

	@Nonnull
	protected Integer getSocketPendingConnectionLimit() {
		return this.socketPendingConnectionLimit;
	}

	@Nonnull
	protected MultipartParser getMultipartParser() {
		return this.multipartParser;
	}

	@Nonnull
	protected Optional<ExecutorService> getRequestHandlerExecutorService() {
		return Optional.ofNullable(this.requestHandlerExecutorService);
	}

	@Nonnull
	protected ReentrantLock getLock() {
		return this.lock;
	}

	@Nonnull
	protected Optional<RequestHandler> getServerListener() {
		return Optional.ofNullable(this.requestHandler);
	}

	@Nonnull
	protected Optional<EventLoop> getEventLoop() {
		return Optional.ofNullable(this.eventLoop);
	}

	@Nonnull
	protected Supplier<ExecutorService> getRequestHandlerExecutorServiceSupplier() {
		return this.requestHandlerExecutorServiceSupplier;
	}

	@Nonnull
	protected Optional<LifecycleInterceptor> getLifecycleInterceptor() {
		return Optional.ofNullable(this.lifecycleInterceptor);
	}

	@Nonnull
	protected Optional<RequestHandler> getRequestHandler() {
		return Optional.ofNullable(this.requestHandler);
	}

	@ThreadSafe
	protected static class NonvirtualThreadFactory implements ThreadFactory {
		@Nonnull
		private final String namePrefix;
		@Nonnull
		private final AtomicInteger idGenerator;

		public NonvirtualThreadFactory(@Nonnull String namePrefix) {
			requireNonNull(namePrefix);

			this.namePrefix = namePrefix;
			this.idGenerator = new AtomicInteger(0);
		}

		@Override
		@Nonnull
		public Thread newThread(@Nonnull Runnable runnable) {
			String name = format("%s-%s", getNamePrefix(), getIdGenerator().incrementAndGet());
			return new Thread(runnable, name);
		}

		@Nonnull
		protected String getNamePrefix() {
			return this.namePrefix;
		}

		@Nonnull
		protected AtomicInteger getIdGenerator() {
			return this.idGenerator;
		}
	}
}
