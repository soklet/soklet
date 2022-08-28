/*
 * Copyright 2022 Revetware LLC.
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

import com.soklet.core.HttpMethod;
import com.soklet.core.LogHandler;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.core.RequestHandler;
import com.soklet.core.Server;
import com.soklet.core.StatusCode;
import com.soklet.microhttp.EventLoop;
import com.soklet.microhttp.Handler;
import com.soklet.microhttp.Header;
import com.soklet.microhttp.LogEntry;
import com.soklet.microhttp.Logger;
import com.soklet.microhttp.Options;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.soklet.core.Utilities.emptyByteArray;
import static com.soklet.core.Utilities.trimToNull;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class MicrohttpServer implements Server {
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
	private static final Integer DEFAULT_MAXIMUM_HEADER_SIZE_IN_BYTES;
	@Nonnull
	private static final Integer DEFAULT_SOCKET_READ_BUFFER_SIZE_IN_BYTES;
	@Nonnull
	private static final Integer DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT;
	@Nonnull
	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT;

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
	private final Integer maximumHeaderSizeInBytes;
	@Nonnull
	private final Integer socketReadBufferSizeInBytes;
	@Nonnull
	private final Integer socketPendingConnectionLimit;
	@Nonnull
	private final LogHandler logHandler;
	@Nonnull
	private final ReentrantLock lock;
	@Nonnull
	private Supplier<ExecutorService> eventLoopExecutorServiceSupplier;
	@Nonnull
	private Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
	@Nullable
	private volatile ExecutorService eventLoopExecutorService;
	@Nullable
	private volatile ExecutorService requestHandlerExecutorService;
	@Nullable
	private volatile RequestHandler requestHandler;
	@Nullable
	private volatile EventLoop eventLoop;

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_CONCURRENCY = Runtime.getRuntime().availableProcessors();
		DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_SOCKET_SELECT_TIMEOUT = Duration.ofMillis(100);
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024;
		DEFAULT_MAXIMUM_HEADER_SIZE_IN_BYTES = 1_024 * 8;
		DEFAULT_SOCKET_READ_BUFFER_SIZE_IN_BYTES = 1_024 * 64;
		DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT = 0;
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
	}

	protected MicrohttpServer(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.lock = new ReentrantLock();

		this.port = builder.port;
		this.host = builder.host != null ? builder.host : DEFAULT_HOST;
		this.concurrency = builder.concurrency != null ? builder.concurrency : DEFAULT_CONCURRENCY;
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.maximumHeaderSizeInBytes = builder.maximumHeaderSizeInBytes != null ? builder.maximumHeaderSizeInBytes : DEFAULT_MAXIMUM_HEADER_SIZE_IN_BYTES;
		this.socketReadBufferSizeInBytes = builder.socketReadBufferSizeInBytes != null ? builder.socketReadBufferSizeInBytes : DEFAULT_SOCKET_READ_BUFFER_SIZE_IN_BYTES;
		this.requestTimeout = builder.requestTimeout != null ? builder.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
		this.socketSelectTimeout = builder.socketSelectTimeout != null ? builder.socketSelectTimeout : DEFAULT_SOCKET_SELECT_TIMEOUT;
		this.socketPendingConnectionLimit = builder.socketPendingConnectionLimit != null ? builder.socketPendingConnectionLimit : DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT;
		this.shutdownTimeout = builder.shutdownTimeout != null ? builder.shutdownTimeout : DEFAULT_SHUTDOWN_TIMEOUT;
		this.logHandler = builder.logHandler != null ? builder.logHandler : new DefaultLogHandler();
		this.eventLoopExecutorServiceSupplier = builder.eventLoopExecutorServiceSupplier != null ? builder.eventLoopExecutorServiceSupplier : () -> {
			return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("http-server").uncaughtExceptionHandler((Thread thread, Throwable throwable) -> {
				getLogHandler().logError("Unexpected exception occurred during server event-loop processing", throwable);
			}).factory());
		};
		this.requestHandlerExecutorServiceSupplier = builder.requestHandlerExecutorServiceSupplier != null ? builder.requestHandlerExecutorServiceSupplier : () -> {
			return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("http-server").uncaughtExceptionHandler((Thread thread, Throwable throwable) -> {
				getLogHandler().logError("Unexpected exception occurred during server HTTP request processing", throwable);
			}).factory());
		};
	}

	@Override
	public void start() throws Exception {
		getLock().lock();

		try {
			if (isStarted())
				return;

			Options options = new Options()
					.withHost(getHost())
					.withPort(getPort())
					.withConcurrency(getConcurrency())
					.withRequestTimeout(getRequestTimeout())
					.withResolution(getSocketSelectTimeout())
					.withBufferSize(getSocketReadBufferSizeInBytes())
					.withMaxRequestSize(getMaximumRequestSizeInBytes())
					.withMaxHeaderSize(getMaximumHeaderSizeInBytes())
					.withAcceptLength(getSocketPendingConnectionLimit());

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

			Handler handler = ((connectionMetadata, microhttpRequest, microHttpCallback) -> {
				ExecutorService requestHandlerExecutorServiceReference = this.requestHandlerExecutorService;

				if (requestHandlerExecutorServiceReference == null)
					return;

				requestHandlerExecutorServiceReference.submit(() -> {
					RequestHandler requestHandler = getRequestHandler().orElse(null);

					if (requestHandler == null)
						return;

					AtomicBoolean shouldWriteFailsafeResponse = new AtomicBoolean(true);

					try {
						byte[] body = microhttpRequest.body();

						if (body != null && body.length == 0)
							body = null;

						Request request = new Request.Builder(HttpMethod.valueOf(microhttpRequest.method().toUpperCase(ENGLISH)), microhttpRequest.uri())
								.headers(headersFromMicrohttpRequest(microhttpRequest))
								.cookies(cookiesFromMicrohttpRequest(microhttpRequest))
								.body(body)
								.build();

						requestHandler.handleRequest(request, (marshaledResponse -> {
							try {
								com.soklet.microhttp.Response microhttpResponse = toMicrohttpResponse(marshaledResponse);
								shouldWriteFailsafeResponse.set(false);

								try {
									microHttpCallback.accept(microhttpResponse);
								} catch (Throwable t) {
									logHandler.logError("Unable to write Microhttp response", t);
								}
							} catch (Throwable t) {
								logHandler.logError("An error occurred while marshaling to a Microhttp response", t);

								try {
									microHttpCallback.accept(provideMicrohttpFailsafeResponse(microhttpRequest, t));
								} catch (Throwable t2) {
									logHandler.logError("An error occurred while writing a failsafe Microhttp response", t2);
								}
							}
						}));
					} catch (Throwable t) {
						logHandler.logError("An unexpected error occurred during Microhttp request handling", t);

						if (shouldWriteFailsafeResponse.get()) {
							try {
								microHttpCallback.accept(provideMicrohttpFailsafeResponse(microhttpRequest, t));
							} catch (Throwable t2) {
								logHandler.logError("An error occurred while writing a failsafe Microhttp response", t2);
							}
						}
					}
				});
			});

			this.eventLoopExecutorService = getEventLoopExecutorServiceSupplier().get();
			this.requestHandlerExecutorService = getRequestHandlerExecutorServiceSupplier().get();
			this.eventLoop = new EventLoop(options, logger, handler);

			this.eventLoopExecutorService.submit(() -> {
				if (this.eventLoop != null)
					eventLoop.start();
			});
		} finally {
			getLock().unlock();
		}
	}

	@Nonnull
	protected com.soklet.microhttp.Response provideMicrohttpFailsafeResponse(@Nonnull com.soklet.microhttp.Request microHttpRequest,
																																					 @Nonnull Throwable throwable) {
		requireNonNull(microHttpRequest);
		requireNonNull(throwable);

		Integer statusCode = 500;
		String reasonPhrase = StatusCode.fromStatusCode(statusCode).get().getReasonPhrase();
		List<Header> headers = List.of(new Header("Content-Type", "text/plain"));
		byte[] body = format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(StandardCharsets.UTF_8);

		return new com.soklet.microhttp.Response(statusCode, reasonPhrase, headers, body);
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
	public void close() throws Exception {
		getLock().lock();

		try {
			if (!isStarted())
				return;

			try {
				getEventLoop().get().stop();
			} catch (Exception e) {
				getLogHandler().logError("Unable to shut down event loop", e);
			}

			boolean interrupted = false;

			try {
				getRequestHandlerExecutorService().get().shutdown();
				getRequestHandlerExecutorService().get().awaitTermination(getShutdownTimeout().getSeconds(), TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				interrupted = true;
			} catch (Exception e) {
				getLogHandler().logError("Unable to shut down request handler executor service", e);
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}

			try {
				getEventLoopExecutorService().get().shutdown();
				getEventLoopExecutorService().get().awaitTermination(getShutdownTimeout().getSeconds(), TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				interrupted = true;
			} catch (Exception e) {
				getLogHandler().logError("Unable to shut down event loop executor service", e);
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		} finally {
			this.eventLoop = null;
			this.eventLoopExecutorService = null;
			this.requestHandlerExecutorService = null;

			getLock().unlock();
		}
	}

	@Override
	public void setRequestHandler(@Nullable RequestHandler requestHandler) {
		this.requestHandler = requestHandler;
	}

	@Nonnull
	protected Map<String, Set<String>> headersFromMicrohttpRequest(@Nonnull com.soklet.microhttp.Request microHttpRequest) {
		requireNonNull(microHttpRequest);

		Map<String, Set<String>> headers = new HashMap<>(microHttpRequest.headers().size());

		for (Header header : microHttpRequest.headers()) {
			Set<String> values = headers.get(header.name());

			if (values == null) {
				values = new HashSet<>();
				headers.put(header.name(), values);
			}

			String value = trimToNull(header.value());

			if (value != null)
				values.add(value);
		}

		return headers;
	}

	@Nonnull
	protected Set<HttpCookie> cookiesFromMicrohttpRequest(@Nonnull com.soklet.microhttp.Request microHttpRequest) {
		requireNonNull(microHttpRequest);

		String cookieHeaderValue = trimToNull(microHttpRequest.header("Cookie"));

		if (cookieHeaderValue != null) {
			try {
				return new HashSet<>(HttpCookie.parse(cookieHeaderValue));
			} catch (Exception ignored) {
				// Bad cookies - ignore them
			}
		}

		return Set.of();
	}

	@Nonnull
	protected com.soklet.microhttp.Response toMicrohttpResponse(@Nonnull MarshaledResponse marshaledResponse) {
		requireNonNull(marshaledResponse);

		List<Header> headers = new ArrayList<>();

		// Non-cookies headers get their values comma-separated
		for (Map.Entry<String, Set<String>> entry : marshaledResponse.getHeaders().entrySet()) {
			String name = entry.getKey();
			Set<String> values = new TreeSet<>(entry.getValue()); // TreeSet to force natural ordering for consistent output
			headers.add(new Header(name, values.stream().collect(Collectors.joining(", "))));
		}

		// Cookie headers are split into multiple instances of Set-Cookie.
		// Force natural ordering for consistent output.
		List<HttpCookie> sortedCookies = new ArrayList<>(marshaledResponse.getCookies());
		Collections.sort(sortedCookies, (cookie1, cookie2) -> cookie1.getName().compareTo(cookie2.getName()));

		for (HttpCookie httpCookie : sortedCookies)
			headers.add(new Header("Set-Cookie", httpCookie.toString()));

		return new com.soklet.microhttp.Response(marshaledResponse.getStatusCode(),
				marshaledResponse.getReasonPhrase(),
				headers,
				marshaledResponse.getBody().orElse(emptyByteArray()));
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
	protected Integer getMaximumHeaderSizeInBytes() {
		return this.maximumHeaderSizeInBytes;
	}

	@Nonnull
	protected Integer getSocketReadBufferSizeInBytes() {
		return this.socketReadBufferSizeInBytes;
	}

	@Nonnull
	protected Integer getSocketPendingConnectionLimit() {
		return this.socketPendingConnectionLimit;
	}

	@Nonnull
	protected LogHandler getLogHandler() {
		return this.logHandler;
	}

	@Nonnull
	protected Optional<ExecutorService> getEventLoopExecutorService() {
		return Optional.ofNullable(this.eventLoopExecutorService);
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
	protected Supplier<ExecutorService> getEventLoopExecutorServiceSupplier() {
		return this.eventLoopExecutorServiceSupplier;
	}

	@Nonnull
	protected Supplier<ExecutorService> getRequestHandlerExecutorServiceSupplier() {
		return this.requestHandlerExecutorServiceSupplier;
	}

	@Nonnull
	protected Optional<RequestHandler> getRequestHandler() {
		return Optional.ofNullable(this.requestHandler);
	}

	/**
	 * Builder used to construct instances of {@link MicrohttpServer}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetware.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private final Integer port;
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
		private Integer maximumHeaderSizeInBytes;
		@Nullable
		private Integer socketReadBufferSizeInBytes;
		@Nullable
		private Integer socketPendingConnectionLimit;
		@Nullable
		private LogHandler logHandler;
		@Nullable
		private Supplier<ExecutorService> eventLoopExecutorServiceSupplier;
		@Nullable
		private Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;

		@Nonnull
		public Builder(@Nonnull Integer port) {
			requireNonNull(port);
			this.port = port;
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
		public Builder maximumHeaderSizeInBytes(@Nullable Integer maximumHeaderSizeInBytes) {
			this.maximumHeaderSizeInBytes = maximumHeaderSizeInBytes;
			return this;
		}

		@Nonnull
		public Builder socketReadBufferSizeInBytes(@Nullable Integer socketReadBufferSizeInBytes) {
			this.socketReadBufferSizeInBytes = socketReadBufferSizeInBytes;
			return this;
		}

		@Nonnull
		public Builder logHandler(@Nullable LogHandler logHandler) {
			this.logHandler = logHandler;
			return this;
		}

		@Nonnull
		public Builder eventLoopExecutorServiceSupplier(@Nullable Supplier<ExecutorService> eventLoopExecutorServiceSupplier) {
			this.eventLoopExecutorServiceSupplier = eventLoopExecutorServiceSupplier;
			return this;
		}

		@Nonnull
		public Builder requestHandlerExecutorServiceSupplier(@Nullable Supplier<ExecutorService> requestHandlerExecutorServiceSupplier) {
			this.requestHandlerExecutorServiceSupplier = requestHandlerExecutorServiceSupplier;
			return this;
		}

		@Nonnull
		public MicrohttpServer build() {
			return new MicrohttpServer(this);
		}
	}
}
