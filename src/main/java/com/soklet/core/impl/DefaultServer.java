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

package com.soklet.core.impl;

import com.soklet.SokletConfiguration;
import com.soklet.core.HttpMethod;
import com.soklet.core.LifecycleInterceptor;
import com.soklet.core.LogEvent;
import com.soklet.core.LogEventType;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.MultipartParser;
import com.soklet.core.Request;
import com.soklet.core.ResponseCookie;
import com.soklet.core.Server;
import com.soklet.core.StatusCode;
import com.soklet.core.Utilities;
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.Handler;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.LogEntry;
import com.soklet.internal.microhttp.Logger;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.Options;
import com.soklet.internal.microhttp.OptionsBuilder;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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

import static com.soklet.core.Utilities.emptyByteArray;
import static com.soklet.core.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultServer implements Server {
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

	@Nonnull
	public static Builder withPort(@Nonnull Integer port) {
		requireNonNull(port);
		return new Builder(port);
	}

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
		this.multipartParser = builder.multipartParser != null ? builder.multipartParser : DefaultMultipartParser.sharedInstance();
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

						// Special case: look for a poison-pill header that indicates "content too large",
						// make a note of it, and then remove it from the request.
						// This header is specially set for Soklet inside of Microhttp's connection event loop.
						Map<String, Set<String>> headers = headersFromMicrohttpRequest(microhttpRequest);
						boolean contentTooLarge = false;

						for (Header header : microhttpRequest.headers()) {
							if (header.name().equals("com.soklet.CONTENT_TOO_LARGE")) {
								headers.remove("com.soklet.CONTENT_TOO_LARGE");
								contentTooLarge = true;
							}
						}

						String uri = decodeUrlPathOnly(microhttpRequest.uri());
						uri = removeDotSegments(uri);

						Request request = Request.with(HttpMethod.valueOf(microhttpRequest.method().toUpperCase(ENGLISH)), uri)
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

	// URL-decodes the path but leaves query parameters URL-encoded
	@Nonnull
	protected String decodeUrlPathOnly(@Nonnull String url) {
		try {
			URI uri = new URI(url);
			String rawPath = uri.getRawPath();
			String decodedPath = percentDecodePath(rawPath);  // Implement as shown above
			String rawQuery = uri.getRawQuery();

			return rawQuery != null && !rawQuery.isEmpty()
					? decodedPath + "?" + rawQuery
					: decodedPath;
		} catch (URISyntaxException e) {
			throw new RuntimeException(format("Unable to decode URL %s", url), e);
		}
	}

	@Nonnull
	protected String percentDecodePath(@Nonnull String rawPath) {
		requireNonNull(rawPath);

		ByteArrayOutputStream out = new ByteArrayOutputStream(rawPath.length());

		for (int i = 0; i < rawPath.length(); ) {
			char c = rawPath.charAt(i);
			if (c == '%' && i + 2 < rawPath.length()) {
				int b = Integer.parseInt(rawPath.substring(i + 1, i + 3), 16);
				out.write(b);
				i += 3;
			} else {
				out.write((byte) c);
				i++;
			}
		}

		return out.toString(StandardCharsets.UTF_8);
	}

	@Nonnull
	protected String removeDotSegments(@Nonnull String path) {
		requireNonNull(path);

		Deque<String> stack = new ArrayDeque<>();

		for (String seg : path.split("/")) {
			if (seg.isEmpty() || ".".equals(seg))
				continue;

			if ("..".equals(seg)) {
				if (!stack.isEmpty())
					stack.removeLast();
			} else {
				stack.addLast(seg);
			}
		}

		return "/" + String.join("/", stack);
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
				getRequestHandlerExecutorService().get().shutdown();
				getRequestHandlerExecutorService().get().awaitTermination(getShutdownTimeout().getSeconds(), TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				interrupted = true;
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Unable to shut down server request handler executor service")
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
	public void initialize(@Nonnull SokletConfiguration sokletConfiguration,
												 @Nonnull RequestHandler requestHandler) {
		requireNonNull(requestHandler);
		requireNonNull(sokletConfiguration);

		this.requestHandler = requestHandler;
		this.lifecycleInterceptor = sokletConfiguration.getLifecycleInterceptor();
	}

	@Nonnull
	protected Map<String, Set<String>> headersFromMicrohttpRequest(@Nonnull MicrohttpRequest microhttpRequest) {
		requireNonNull(microhttpRequest);

		Map<String, Set<String>> headers = new LinkedCaseInsensitiveMap<>(microhttpRequest.headers().size());

		for (Header header : microhttpRequest.headers()) {
			Set<String> values = headers.computeIfAbsent(header.name(), k -> new LinkedHashSet<>());

			String value = trimAggressivelyToNull(header.value());

			if (value != null)
				values.add(value);
		}

		return headers;
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

			headers.add(new Header(name, String.join(", ", values)));
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
			getLifecycleInterceptor().get().didReceiveLogEvent(logEvent);
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

	/**
	 * Builder used to construct instances of {@link DefaultServer}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private Integer port;
		@Nullable
		private String host;
		@Nullable
		private Integer concurrency;
		@Nullable
		private Duration requestTimeout;
		@Nullable
		private Duration socketSelectTimeout;
		@Nullable
		private Duration shutdownTimeout;
		@Nullable
		private Integer maximumRequestSizeInBytes;
		@Nullable
		private Integer requestReadBufferSizeInBytes;
		@Nullable
		private Integer socketPendingConnectionLimit;
		@Nullable
		private MultipartParser multipartParser;
		@Nullable
		private Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;

		@Nonnull
		protected Builder(@Nonnull Integer port) {
			requireNonNull(port);
			this.port = port;
		}

		@Nonnull
		public Builder port(@Nonnull Integer port) {
			requireNonNull(port);
			this.port = port;
			return this;
		}

		@Nonnull
		public Builder host(@Nullable String host) {
			this.host = host;
			return this;
		}

		@Nonnull
		public Builder concurrency(@Nullable Integer concurrency) {
			this.concurrency = concurrency;
			return this;
		}

		@Nonnull
		public Builder requestTimeout(@Nullable Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
			return this;
		}

		@Nonnull
		public Builder socketSelectTimeout(@Nullable Duration socketSelectTimeout) {
			this.socketSelectTimeout = socketSelectTimeout;
			return this;
		}

		@Nonnull
		public Builder socketPendingConnectionLimit(@Nullable Integer socketPendingConnectionLimit) {
			this.socketPendingConnectionLimit = socketPendingConnectionLimit;
			return this;
		}

		@Nonnull
		public Builder shutdownTimeout(@Nullable Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		@Nonnull
		public Builder maximumRequestSizeInBytes(@Nullable Integer maximumRequestSizeInBytes) {
			this.maximumRequestSizeInBytes = maximumRequestSizeInBytes;
			return this;
		}

		@Nonnull
		public Builder requestReadBufferSizeInBytes(@Nullable Integer requestReadBufferSizeInBytes) {
			this.requestReadBufferSizeInBytes = requestReadBufferSizeInBytes;
			return this;
		}

		@Nonnull
		public Builder multipartParser(@Nullable MultipartParser multipartParser) {
			this.multipartParser = multipartParser;
			return this;
		}

		@Nonnull
		public Builder requestHandlerExecutorServiceSupplier(@Nullable Supplier<ExecutorService> requestHandlerExecutorServiceSupplier) {
			this.requestHandlerExecutorServiceSupplier = requestHandlerExecutorServiceSupplier;
			return this;
		}

		@Nonnull
		public DefaultServer build() {
			return new DefaultServer(this);
		}
	}
}
