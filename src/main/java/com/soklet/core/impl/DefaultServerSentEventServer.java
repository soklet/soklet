/*
 * Copyright 2022-2024 Revetware LLC.
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

import com.soklet.core.LifecycleInterceptor;
import com.soklet.core.LogEvent;
import com.soklet.core.LogEventType;
import com.soklet.core.ResourcePath;
import com.soklet.core.ServerSentEvent;
import com.soklet.core.ServerSentEventServer;
import com.soklet.core.ServerSentEventSource;
import com.soklet.core.Utilities;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultServerSentEventServer implements ServerSentEventServer {
	@Nonnull
	private static final String SSE_HEADER;

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
	private static final Integer DEFAULT_SOCKET_READ_BUFFER_SIZE_IN_BYTES;
	@Nonnull
	private static final Integer DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT;
	@Nonnull
	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT;

	static {
		SSE_HEADER = "HTTP/1.1 200 OK\r\n" +
				"Content-Type: text/event-stream\r\n" +
				"Cache-Control: no-cache\r\n" +
				// TODO: remove this and let clients specify
				"Access-Control-Allow-Origin: *\r\n" +
				"Connection: keep-alive\r\n\r\n";

		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_CONCURRENCY = Runtime.getRuntime().availableProcessors();
		DEFAULT_REQUEST_TIMEOUT = Duration.ofHours(12);
		DEFAULT_SOCKET_SELECT_TIMEOUT = Duration.ofMillis(100);
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024;
		DEFAULT_SOCKET_READ_BUFFER_SIZE_IN_BYTES = 1_024 * 8;
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
	private final Integer socketReadBufferSizeInBytes;
	@Nonnull
	private final Integer socketPendingConnectionLimit;
	@Nonnull
	private final LifecycleInterceptor lifecycleInterceptor;
	@Nonnull
	private final ExecutorService clientSocketChannelWriteExecutorService;
	// Keeps track of Server-Sent Event Sources by Resource Path.
	// This map's keys and values are fully specified at construction time and will never change.
	// As a result, this can be a regular HashMap<K,V> as opposed to a ConcurrentHashMap<K,V>.
	@Nonnull
	private final Map<ResourcePath, DefaultServerSentEventSource> serverSentEventSourcesByResourcePath;
	// We don't want to have multiple writing threads write to the same client SocketChannel concurrently.
	// So, we maintain this map of per-channel locks to ensure thread safety when writing.
	// When a write is about to occur, the writer must acquire the lock and then release it after writing.
	@Nonnull
	private final ConcurrentHashMap<SocketChannel, ReentrantLock> writeLocksByClientSocketChannel;
	@Nonnull
	private final ReentrantLock lock;
	@Nonnull
	private final Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
	@Nonnull
	private final AtomicBoolean stopPoisonPill;
	@Nullable
	private volatile ExecutorService requestHandlerExecutorService;
	@Nonnull
	private volatile Boolean started;
	@Nullable
	private Thread eventLoopThread;

	@ThreadSafe
	protected static class DefaultServerSentEventSource implements ServerSentEventSource {
		@Nonnull
		private final ResourcePath resourcePath;
		@Nonnull
		private final ExecutorService clientSocketChannelWriteExecutorService;
		@Nonnull
		private final ConcurrentHashMap<SocketChannel, ReentrantLock> writeLocksByClientSocketChannel;
		// Keeps track of client SocketChannel instances.
		// This must be a threadsafe set, as it will be modified over time.
		@Nonnull
		private final Set<SocketChannel> clientSocketChannels;

		public DefaultServerSentEventSource(@Nonnull ResourcePath resourcePath,
																				@Nonnull ExecutorService clientSocketChannelWriteExecutorService,
																				@Nonnull ConcurrentHashMap<SocketChannel, ReentrantLock> writeLocksByClientSocketChannel,
																				@Nonnull Integer initialCapacity) {
			requireNonNull(resourcePath);
			requireNonNull(clientSocketChannelWriteExecutorService);
			requireNonNull(writeLocksByClientSocketChannel);
			requireNonNull(initialCapacity);

			this.resourcePath = resourcePath;
			this.clientSocketChannelWriteExecutorService = clientSocketChannelWriteExecutorService;
			this.writeLocksByClientSocketChannel = writeLocksByClientSocketChannel;
			this.clientSocketChannels = ConcurrentHashMap.newKeySet(initialCapacity);
		}

		@Nonnull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@Nonnull
		@Override
		public Long getClientCount() {
			return (long) this.clientSocketChannels.size();
		}

		@Override
		public void send(@Nonnull ServerSentEvent serverSentEvent) {
			for (SocketChannel clientSocketChannel : this.clientSocketChannels) {
				this.clientSocketChannelWriteExecutorService.submit(() -> {
					ReentrantLock writeLock = writeLocksByClientSocketChannel.getOrDefault(getResourcePath(), new ReentrantLock());
					writeLock.lock();

					try {
						String fakeData = "Fake data for now";
						String message = "data: " + fakeData + "\n\n";
						System.out.println("Sending fake data to client: " + message.trim());
						ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));

						if(clientSocketChannel.isOpen()) {
							clientSocketChannel.write(buffer);
						} else {
							// Socket is no longer open, remove it
							System.out.println("Socket is no longer open, remove it");
							removeClientSocketChannel(clientSocketChannel);
						}
					} catch (Exception e) {
						// TODO: send to lifecycle interceptor
						e.printStackTrace();

						// Looks like this channel is junk now
						removeClientSocketChannel(clientSocketChannel);
					} finally {
						writeLock.unlock();
					}
				});
			}
		}

		@Nonnull
		public Boolean addClientSocketChannel(@Nullable SocketChannel clientSocketChannel) {
			if (clientSocketChannel == null)
				return false;

			return this.clientSocketChannels.add(clientSocketChannel);
		}

		@Nonnull
		public Boolean removeClientSocketChannel(@Nullable SocketChannel clientSocketChannel) {
			if (clientSocketChannel == null)
				return false;

			try {
				clientSocketChannel.close();
			} catch (IOException e) {
				System.err.println("Unable to close socket: " + e.getMessage());
				// Unable to close socket channel, nothing we can do
			}

			return this.clientSocketChannels.remove(clientSocketChannel);
		}
	}

	@Nonnull
	public static Builder withPort(@Nonnull Integer port) {
		requireNonNull(port);
		return new Builder(port);
	}

	protected DefaultServerSentEventServer(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.stopPoisonPill = new AtomicBoolean(false);
		this.started = false;
		this.lock = new ReentrantLock();

		this.port = builder.port;
		this.host = builder.host != null ? builder.host : DEFAULT_HOST;
		this.concurrency = builder.concurrency != null ? builder.concurrency : DEFAULT_CONCURRENCY;
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.socketReadBufferSizeInBytes = builder.socketReadBufferSizeInBytes != null ? builder.socketReadBufferSizeInBytes : DEFAULT_SOCKET_READ_BUFFER_SIZE_IN_BYTES;
		this.requestTimeout = builder.requestTimeout != null ? builder.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
		this.socketSelectTimeout = builder.socketSelectTimeout != null ? builder.socketSelectTimeout : DEFAULT_SOCKET_SELECT_TIMEOUT;
		this.socketPendingConnectionLimit = builder.socketPendingConnectionLimit != null ? builder.socketPendingConnectionLimit : DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT;
		this.shutdownTimeout = builder.shutdownTimeout != null ? builder.shutdownTimeout : DEFAULT_SHUTDOWN_TIMEOUT;
		this.lifecycleInterceptor = builder.lifecycleInterceptor != null ? builder.lifecycleInterceptor : DefaultLifecycleInterceptor.sharedInstance();

		// TODO: let clients specify the executor service
		this.clientSocketChannelWriteExecutorService = Utilities.createVirtualThreadsNewThreadPerTaskExecutor("sse-stream-writer-", (Thread thread, Throwable throwable) -> {
			try {
				getLifecycleInterceptor().didReceiveLogEvent(LogEvent.with(LogEventType.SSE_EVENT_STREAM_WRITING_ERROR, "Unexpected exception occurred during Server-Sent Event stream writing")
						.throwable(throwable)
						.build());
			} catch (Throwable loggingThrowable) {
				// We are in a bad state - the log operation in the uncaught exception handler failed.
				// Not much else we can do here but dump to stderr
				throwable.printStackTrace();
				loggingThrowable.printStackTrace();
			}
		});

		// TODO: let clients specify initial capacity
		this.writeLocksByClientSocketChannel = new ConcurrentHashMap<>(1_024);

		// Keeps track of Server-Sent Event Sources by Resource Path.
		// This map's keys and values are fully specified at construction time and will never change.
		// As a result, this can be a regular HashMap<K,V> as opposed to a ConcurrentHashMap<K,V>.
		Set<ResourcePath> resourcePaths = builder.resourcePaths != null ? new HashSet<>(builder.resourcePaths) : Set.of();
		Map<ResourcePath, DefaultServerSentEventSource> serverSentEventSourcesByResourcePath = new HashMap<>(resourcePaths.size());

		// TODO: let clients specify initial capacity
		for (ResourcePath resourcePath : resourcePaths)
			serverSentEventSourcesByResourcePath.put(resourcePath, new DefaultServerSentEventSource(resourcePath, this.clientSocketChannelWriteExecutorService, writeLocksByClientSocketChannel, 256));

		this.serverSentEventSourcesByResourcePath = Collections.unmodifiableMap(serverSentEventSourcesByResourcePath);

		this.requestHandlerExecutorServiceSupplier = builder.requestHandlerExecutorServiceSupplier != null ? builder.requestHandlerExecutorServiceSupplier : () -> {
			String threadNamePrefix = "sse-handler-";

			// Default implementation: cowardly refuse to run on anything other than a runtime that supports Virtual threads.
			// Applications can override this by bringing their own ExecutorService via requestHandlerExecutorServiceSupplier, but it's not recommended.
			if (!Utilities.virtualThreadsAvailable())
				throw new IllegalStateException(format("Virtual threads are required for %s", getClass().getSimpleName()));

			return Utilities.createVirtualThreadsNewThreadPerTaskExecutor(threadNamePrefix, (Thread thread, Throwable throwable) -> {
				try {
					getLifecycleInterceptor().didReceiveLogEvent(LogEvent.with(LogEventType.SSE_SERVER_INTERNAL_ERROR, "Unexpected exception occurred during server Server-Sent Event processing")
							.throwable(throwable)
							.build());
				} catch (Throwable loggingThrowable) {
					// We are in a bad state - the log operation in the uncaught exception handler failed.
					// Not much else we can do here but dump to stderr
					throwable.printStackTrace();
					loggingThrowable.printStackTrace();
				}
			});
		};
	}

	@Override
	public void start() {
		getLock().lock();

		try {
			if (isStarted())
				return;

			System.out.println("Starting...");
			this.requestHandlerExecutorService = getRequestHandlerExecutorServiceSupplier().get();
			this.eventLoopThread = new Thread(this::startInternal, "sse-event-loop");
			eventLoopThread.start();
			this.started = true;
			System.out.println("Started.");
		} finally {
			getLock().unlock();
		}
	}

	protected void startInternal() {
		try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {
			serverSocket.bind(new InetSocketAddress(getPort()));
			System.out.println("SSE Server started on port " + getPort());
			ExecutorService executorService = getRequestHandlerExecutorService().get();

			while (!getStopPoisonPill().get()) {
				SocketChannel clientSocketChannel = serverSocket.accept();
				clientSocketChannel.configureBlocking(false);
				System.out.println("Accepted connection from: " + clientSocketChannel.getRemoteAddress());
				executorService.submit(() -> handleClientSocketChannel(clientSocketChannel));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void handleClientSocketChannel(@Nonnull SocketChannel clientSocketChannel) {
		requireNonNull(clientSocketChannel);

		DefaultServerSentEventSource serverSentEventSource = null;

		try (clientSocketChannel) {
			// Parse HTTP request to determine the requested URL and headers
			HttpRequest request = parseHttpRequest(clientSocketChannel);

			// Check if the requested URL is allowed
			ResourcePath resourcePath = new ResourcePath(request.url);
			serverSentEventSource = resourcePath == null ? null : serverSentEventSourcesByResourcePath.get(resourcePath);

			if (resourcePath == null || serverSentEventSource == null) {
				// Respond with a 404 Not Found if the URL is not allowed
				String response = "HTTP/1.1 404 Not Found\r\n\r\n";
				clientSocketChannel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
				System.out.println("Rejected connection for URL: " + request.url);
				return;
			}

			// Write SSE headers
			clientSocketChannel.write(ByteBuffer.wrap(SSE_HEADER.getBytes()));
			System.out.println("Wrote SSE header bytes");

			// Hang on to this socket channel for later writes via ServerSentEventSource
			serverSentEventSource.addClientSocketChannel(clientSocketChannel);


			// Send different messages depending on the URL
			//String messageData = getMessageForUrl(request.url);

			// Keep sending messages until the client disconnects
			//while (clientSocketChannel.isOpen()) {
			//sendToClient(clientSocketChannel, messageData);

			// Wait for a second between messages
			//Thread.sleep(1000);
			//}
		} catch (Exception e) {
			System.out.println("Client disconnected: " + e.getMessage());
			// Hang on to this socket channel for later writes via ServerSentEventSource
			if (serverSentEventSource != null)
				serverSentEventSource.removeClientSocketChannel(clientSocketChannel);

			//Thread.currentThread().interrupt();  // Restore interrupt status
		}
	}

	private HttpRequest parseHttpRequest(@Nonnull SocketChannel clientSocketChannel) throws IOException {
		requireNonNull(clientSocketChannel);

		ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
		StringBuilder requestDataBuffer = new StringBuilder();

		while (clientSocketChannel.read(byteBuffer) > 0) {
			byteBuffer.flip();
			requestDataBuffer.append(StandardCharsets.UTF_8.decode(byteBuffer));
			byteBuffer.clear();
		}

		String requestData = requestDataBuffer.toString();

		// System.out.println("Parsed request data:\n" + requestData.toString().trim());

		// Use Scanner to parse request data line by line
		try (Scanner scanner = new Scanner(requestData.toString())) {
			// Parse the request line
			String requestLine = scanner.nextLine();
			String[] requestLineParts = requestLine.split(" ");
			String method = requestLineParts[0];
			String url = requestLineParts[1];
			String version = requestLineParts[2];

			// Parse headers into a map
			Map<String, String> headers = new HashMap<>();
			while (scanner.hasNextLine()) {
				String line = scanner.nextLine();
				if (line.isEmpty()) break; // End of headers

				String[] headerParts = line.split(": ", 2);
				if (headerParts.length == 2) {
					headers.put(headerParts[0], headerParts[1]);
				}
			}

			return new HttpRequest(method, url, version, headers);
		}
	}

	// TODO: this is for testing only
	private Boolean isAllowedUrl(@Nonnull String url) {
		requireNonNull(url);
		return "/".equals(url);
	}

	// TODO: this is for testing only
	@Nonnull
	protected String getMessageForUrl(@Nonnull String url) {
		requireNonNull(url);

		switch (url) {
			case "/":
				return "Hello from /!";
			default:
				return "Unknown URL";
		}
	}

//	protected void sendToClient(@Nonnull SocketChannel clientSocketChannel,
//															@Nonnull String data) throws IOException {
//		requireNonNull(clientSocketChannel);
//		requireNonNull(data);
//
//		String message = "data: " + data + "\n\n";
//		System.out.println("Sending to client: " + message.trim());
//		ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
//		clientSocketChannel.write(buffer);
//	}

	// TODO: clean this up
	@NotThreadSafe
	private static class HttpRequest {
		String method;
		String url;
		String version;
		Map<String, String> headers;

		HttpRequest(String method, String url, String version, Map<String, String> headers) {
			this.method = method;
			this.url = url;
			this.version = version;
			this.headers = headers;
		}
	}

	@Override
	public void stop() {
		getLock().lock();

		try {
			if (!isStarted())
				return;

			System.out.println("Stopping...");

			try {
				getStopPoisonPill().set(true);
			} catch (Exception e) {
				getLifecycleInterceptor().didReceiveLogEvent(LogEvent.with(LogEventType.SSE_SERVER_INTERNAL_ERROR, "Unable to shut down server event loop")
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
				getLifecycleInterceptor().didReceiveLogEvent(LogEvent.with(LogEventType.SSE_SERVER_INTERNAL_ERROR, "Unable to shut down server request handler executor service")
						.throwable(e)
						.build());
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		} finally {
			this.started = false;
			this.eventLoopThread = null;
			this.requestHandlerExecutorService = null;
			getStopPoisonPill().set(false);

			System.out.println("Stopped.");

			getLock().unlock();
		}
	}

	@Nonnull
	@Override
	public Boolean isStarted() {
		getLock().lock();

		try {
			return this.started;
		} finally {
			getLock().unlock();
		}
	}

	@Nonnull
	@Override
	public Optional<ServerSentEventSource> acquireEventSource(@Nullable ResourcePath resourcePath) {
		if (resourcePath == null)
			return Optional.empty();

		ServerSentEventSource serverSentEventSource = getServerSentEventSourcesByResourcePath().get(resourcePath);
		return Optional.ofNullable(serverSentEventSource);
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
	protected Integer getSocketReadBufferSizeInBytes() {
		return this.socketReadBufferSizeInBytes;
	}

	@Nonnull
	protected Integer getSocketPendingConnectionLimit() {
		return this.socketPendingConnectionLimit;
	}

	@Nonnull
	protected LifecycleInterceptor getLifecycleInterceptor() {
		return this.lifecycleInterceptor;
	}

	@Nonnull
	protected ExecutorService getClientSocketChannelWriteExecutorService() {
		return this.clientSocketChannelWriteExecutorService;
	}

	@Nonnull
	protected Map<ResourcePath, DefaultServerSentEventSource> getServerSentEventSourcesByResourcePath() {
		return this.serverSentEventSourcesByResourcePath;
	}

	@Nonnull
	protected ConcurrentHashMap<SocketChannel, ReentrantLock> getWriteLocksByClientSocketChannel() {
		return this.writeLocksByClientSocketChannel;
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
	protected Supplier<ExecutorService> getRequestHandlerExecutorServiceSupplier() {
		return this.requestHandlerExecutorServiceSupplier;
	}

	@Nonnull
	protected AtomicBoolean getStopPoisonPill() {
		return this.stopPoisonPill;
	}

	@Nonnull
	protected Optional<Thread> getEventLoopThread() {
		return Optional.ofNullable(this.eventLoopThread);
	}

	/**
	 * Builder used to construct instances of {@link DefaultServerSentEventServer}.
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
		private Integer socketReadBufferSizeInBytes;
		@Nullable
		private Integer socketPendingConnectionLimit;
		@Nullable
		private LifecycleInterceptor lifecycleInterceptor;
		@Nullable
		private Set<ResourcePath> resourcePaths;
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
		public Builder socketReadBufferSizeInBytes(@Nullable Integer socketReadBufferSizeInBytes) {
			this.socketReadBufferSizeInBytes = socketReadBufferSizeInBytes;
			return this;
		}

		@Nonnull
		public Builder lifecycleInterceptor(@Nullable LifecycleInterceptor lifecycleInterceptor) {
			this.lifecycleInterceptor = lifecycleInterceptor;
			return this;
		}

		@Nonnull
		public Builder resourcePaths(@Nullable Set<ResourcePath> resourcePaths) {
			this.resourcePaths = resourcePaths;
			return this;
		}

		@Nonnull
		public Builder requestHandlerExecutorServiceSupplier(@Nullable Supplier<ExecutorService> requestHandlerExecutorServiceSupplier) {
			this.requestHandlerExecutorServiceSupplier = requestHandlerExecutorServiceSupplier;
			return this;
		}

		@Nonnull
		public DefaultServerSentEventServer build() {
			return new DefaultServerSentEventServer(this);
		}
	}
}
