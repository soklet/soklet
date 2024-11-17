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

import com.soklet.core.HttpMethod;
import com.soklet.core.LifecycleInterceptor;
import com.soklet.core.LogEvent;
import com.soklet.core.LogEventType;
import com.soklet.core.Request;
import com.soklet.core.ResourcePath;
import com.soklet.core.ResourcePathInstance;
import com.soklet.core.ServerSentEvent;
import com.soklet.core.ServerSentEventServer;
import com.soklet.core.ServerSentEventSource;
import com.soklet.core.Utilities;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultServerSentEventServer implements ServerSentEventServer {
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

	@Nonnull
	private static final ServerSentEvent SERVER_SENT_EVENT_POISON_PILL;

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_CONCURRENCY = Runtime.getRuntime().availableProcessors();
		DEFAULT_REQUEST_TIMEOUT = Duration.ofHours(12);
		DEFAULT_SOCKET_SELECT_TIMEOUT = Duration.ofMillis(100);
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024;
		DEFAULT_SOCKET_READ_BUFFER_SIZE_IN_BYTES = 1_024 * 8;
		DEFAULT_SOCKET_PENDING_CONNECTION_LIMIT = 0;
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

		SERVER_SENT_EVENT_POISON_PILL = new ServerSentEvent();
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
	@Nonnull
	private final ConcurrentHashMap<ResourcePathInstance, DefaultServerSentEventSource> serverSentEventSourcesByResourcePathInstance;
	@Nonnull
	private final ConcurrentHashMap<SocketChannel, ServerSentEventConnection> serverSentEventConnectionsBySocketChannel;
	// Note: this map's values must be concurrent hashsets, as the contents of the set can be modified by multiple threads at runtime.
	// However, the set references themselves (the map's values) will never change.
	@Nonnull
	private final ConcurrentHashMap<DefaultServerSentEventSource, Set<ServerSentEventConnection>> connectionSetsByEventSource;
	@Nonnull
	private final ConcurrentHashMap<ResourcePathInstance, ResourcePath> resourcePathsByResourcePathInstance;
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
	@Nonnull
	private volatile Boolean stopping;
	@Nullable
	private Thread eventLoopThread;

	@ThreadSafe
	protected static class DefaultServerSentEventSource implements ServerSentEventSource {
		@Nonnull
		private final ResourcePathInstance resourcePathInstance;
		@Nonnull
		private final ExecutorService clientSocketChannelWriteExecutorService;
		@Nonnull
		private final Function<DefaultServerSentEventSource, Set<ServerSentEventConnection>> connectionsSupplierFunction;
		@Nonnull
		private final Consumer<ServerSentEventConnection> connectionRemovalConsumer;

		public DefaultServerSentEventSource(@Nonnull ResourcePathInstance resourcePathInstance,
																				@Nonnull ExecutorService clientSocketChannelWriteExecutorService,
																				@Nonnull Function<DefaultServerSentEventSource, Set<ServerSentEventConnection>> connectionsSupplierFunction,
																				@Nonnull Consumer<ServerSentEventConnection> connectionRemovalConsumer
		) {
			requireNonNull(resourcePathInstance);
			requireNonNull(clientSocketChannelWriteExecutorService);
			requireNonNull(connectionsSupplierFunction);
			requireNonNull(connectionRemovalConsumer);

			this.resourcePathInstance = resourcePathInstance;
			this.clientSocketChannelWriteExecutorService = clientSocketChannelWriteExecutorService;
			this.connectionsSupplierFunction = connectionsSupplierFunction;
			this.connectionRemovalConsumer = connectionRemovalConsumer;
		}

		@Nonnull
		@Override
		public ResourcePathInstance getResourcePathInstance() {
			return this.resourcePathInstance;
		}

		@Nonnull
		@Override
		public Long getClientCount() {
			return (long) getConnections().size();
		}

		@Nonnull
		protected Set<ServerSentEventConnection> getConnections() {
			return this.connectionsSupplierFunction.apply(this);
		}

		@Override
		public void broadcast(@Nonnull ServerSentEvent serverSentEvent) {
			this.clientSocketChannelWriteExecutorService.submit(() -> {
				for (ServerSentEventConnection serverSentEventConnection : getConnections()) {
					serverSentEventConnection.getWriteQueue().add(serverSentEvent);
				}
			});
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
		this.stopping = false;
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
		this.serverSentEventConnectionsBySocketChannel = new ConcurrentHashMap<>(1_024);

		// Keeps track of Server-Sent Event Sources by Resource Path.
		// This map's keys and values are fully specified at construction time and will never change.
		// As a result, this can be a regular HashMap<K,V> as opposed to a ConcurrentHashMap<K,V>.
		//Set<ResourcePath> resourcePaths = builder.resourcePaths != null ? new HashSet<>(builder.resourcePaths) : Set.of();
		//Map<ResourcePath, DefaultServerSentEventSource> serverSentEventSourcesByResourcePath = new HashMap<>(resourcePaths.size());

		this.connectionSetsByEventSource = new ConcurrentHashMap<>();

		// TODO: let clients specify
		this.serverSentEventSourcesByResourcePathInstance = new ConcurrentHashMap<>(1_024);

		// TODO: let clients specify
		this.resourcePathsByResourcePathInstance = new ConcurrentHashMap<>(1_024);

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
					// Not much else we can do here but dump to stderr and try to stop the server.
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
		// Handle scenario where server is stopped immediately after starting (and before this thread is scheduled)
		// TODO: clean this up
		if (!isStarted() || isStopping()) {
			System.out.println("Server is stopped or stopping, exiting SSE event loop...");
			return;
		}

		try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {
			serverSocketChannel.bind(new InetSocketAddress(getPort()));

			// Handle scenario where server is stopped immediately after starting (and before this thread is scheduled)
			// TODO: clean this up
			if (!isStarted() || isStopping()) {
				System.out.println("Server is stopped or stopping, exiting SSE event loop...");
				return;
			}

			System.out.println("SSE Server started on port " + getPort());

			ExecutorService executorService = getRequestHandlerExecutorService().get();

			while (!getStopPoisonPill().get()) {
				SocketChannel clientSocketChannel = serverSocketChannel.accept();
				executorService.submit(() -> handleClientSocketChannel(clientSocketChannel));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			// In case we are not already being stopped, force a stop
			stop();
		}
	}

	protected void handleClientSocketChannel(@Nonnull SocketChannel clientSocketChannel) {
		requireNonNull(clientSocketChannel);

		try (clientSocketChannel) {
			String rawRequest = null;

			try {
				rawRequest = readRequest(clientSocketChannel);
			} catch (Exception e) {
				// TODO: cleanup
				System.out.println("Unable to read raw request.");
				e.printStackTrace();
			}

			Request request = null;

			try {
				request = parseRequest(rawRequest);
				System.out.println(request);
			} catch (Exception e) {
				// TODO: cleanup
				System.out.println("Unable to parse raw request.");
				e.printStackTrace();
			}

			ServerSentEventConnection serverSentEventConnection = registerClientSocketChannel(clientSocketChannel, request).get();

			final String SSE_HEADERS_AS_STRING = "HTTP/1.1 200 OK\r\n" +
					"Content-Type: text/event-stream\r\n" +
					"Cache-Control: no-cache\r\n" +
					// TODO: let clients specify this and other headers
					"Access-Control-Allow-Origin: *\r\n" +
					"Connection: keep-alive\r\n\r\n";

			// Immediately send SSE headers
			clientSocketChannel.write(ByteBuffer.wrap(SSE_HEADERS_AS_STRING.getBytes()));

			while (true) {
				ServerSentEvent serverSentEvent = serverSentEventConnection.getWriteQueue().take();

				if (serverSentEvent == SERVER_SENT_EVENT_POISON_PILL) {
					System.out.println("Encountered poison pill, exiting...");
					break;
				}

				System.out.println("Writing data...");
				String message = "data: " + serverSentEvent.toString() + "\n\n";
				ByteBuffer buffer = ByteBuffer.wrap(message.getBytes());
				clientSocketChannel.write(buffer);
				System.out.println("Wrote data.");
			}
		} catch (IOException | InterruptedException e) {
			System.out.println("Client disconnected: " + e.getMessage());

			if (e instanceof InterruptedException) {
				System.out.println("Interrupted");
				Thread.currentThread().interrupt();  // Restore interrupt status
			}
		} finally {
			unregisterClientSocketChannel(clientSocketChannel);
		}
	}

	@ThreadSafe
	protected static class ServerSentEventConnection {
		@Nonnull
		private final Request request;
		@Nonnull
		private final ResourcePathInstance resourcePathInstance;
		@Nonnull
		private final SocketChannel clientSocketChannel;
		@Nonnull
		private final BlockingQueue<ServerSentEvent> writeQueue;

		public ServerSentEventConnection(@Nonnull Request request,
																		 @Nonnull ResourcePathInstance resourcePathInstance,
																		 @Nonnull SocketChannel clientSocketChannel) {
			requireNonNull(request);
			requireNonNull(resourcePathInstance);
			requireNonNull(clientSocketChannel);

			this.request = request;
			this.resourcePathInstance = resourcePathInstance;
			this.clientSocketChannel = clientSocketChannel;
			this.writeQueue = new ArrayBlockingQueue<>(8);
		}

		@Nonnull
		public Request getRequest() {
			return this.request;
		}

		@Nonnull
		public ResourcePathInstance getResourcePathInstance() {
			return this.resourcePathInstance;
		}

		@Nonnull
		public SocketChannel getClientSocketChannel() {
			return this.clientSocketChannel;
		}

		@Nonnull
		public BlockingQueue<ServerSentEvent> getWriteQueue() {
			return this.writeQueue;
		}
	}

	@Nonnull
	protected Optional<ServerSentEventConnection> registerClientSocketChannel(@Nonnull SocketChannel clientSocketChannel,
																																						@Nonnull Request request) {
		requireNonNull(clientSocketChannel);
		requireNonNull(request);

		ResourcePathInstance resourcePathInstance = new ResourcePathInstance(request.getPath());

		// TODO: check to see if this is a 404 and don't do the getOrDefault below

		// Get a handle to the event source, creating it if it doesn't already exist
		DefaultServerSentEventSource serverSentEventSource = getServerSentEventSourcesByResourcePathInstance().computeIfAbsent(resourcePathInstance, (rpi) -> {
			System.out.println("Creating event source for " + resourcePathInstance);
			DefaultServerSentEventSource newServerSentEventSource = new DefaultServerSentEventSource(
					resourcePathInstance,
					this.clientSocketChannelWriteExecutorService,
					(DefaultServerSentEventSource defaultServerSentEventSource) -> {
						return this.connectionSetsByEventSource.get(defaultServerSentEventSource);
					}, (@Nonnull ServerSentEventConnection serverSentEventConnection) -> {
				unregisterClientSocketChannel(serverSentEventConnection.getClientSocketChannel());
			});

			// TODO: let clients specify capacity per-resource-path-instance
			connectionSetsByEventSource.put(newServerSentEventSource, ConcurrentHashMap.newKeySet(1_024));

			return newServerSentEventSource;
		});

		//DefaultServerSentEventSource serverSentEventSource = acquireEventSourceInternal(resourcePathInstance).orElse(null);

		// Should never occur
		if (serverSentEventSource == null) {
			System.out.println("WARNING: no event source for resource path instance " + resourcePathInstance);
			return Optional.empty();
		}

		try {
			System.out.println("Registering client socket channel " + clientSocketChannel.getRemoteAddress());
		} catch (Exception e) {
			e.printStackTrace();
		}

		ServerSentEventConnection serverSentEventConnection = new ServerSentEventConnection(request, serverSentEventSource.getResourcePathInstance(), clientSocketChannel);

		this.serverSentEventConnectionsBySocketChannel.put(clientSocketChannel, serverSentEventConnection);

		Set<ServerSentEventConnection> connectionSet = this.connectionSetsByEventSource.get(serverSentEventSource);

		if (connectionSet == null) {
			System.out.println("WARNING: no connection set for event source " + serverSentEventSource);
			return Optional.empty();
		}

		connectionSet.add(serverSentEventConnection);

		return Optional.of(serverSentEventConnection);
	}

	@Nonnull
	protected Boolean unregisterClientSocketChannel(@Nullable SocketChannel clientSocketChannel) {
		if (clientSocketChannel == null)
			return false;

		ServerSentEventConnection eventConnection = getServerSentEventConnectionsBySocketChannel().remove(clientSocketChannel);

		if (eventConnection != null) {
			DefaultServerSentEventSource eventSource = getServerSentEventSourcesByResourcePathInstance().get(eventConnection.getResourcePathInstance());

			if (eventSource != null) {
				Set<ServerSentEventConnection> eventConnections = getConnectionSetsByEventSource().get(eventSource);

				if (eventConnections != null)
					eventConnections.remove(eventConnection);
			}
		}

		try {
			System.out.println("Unregistering client socket channel " + clientSocketChannel.getRemoteAddress());
		} catch (Exception ignored) {
			// Don't worry about it
		}

		try {
			clientSocketChannel.close();
		} catch (Exception ignored) {
			// Don't worry about it
		}

		return eventConnection != null;
	}

	@Nonnull
	protected Request parseRequest(@Nonnull String rawRequest) {
		requireNonNull(rawRequest);

		rawRequest = Utilities.trimAggressivelyToNull(rawRequest);

		if (rawRequest == null)
			throw new IllegalStateException("Server-Sent Event HTTP request has no data");

		// Example request structure:
		//
		// GET /testing?one=two HTTP/1.1
		// Host: localhost:8081
		// Connection: keep-alive
		// sec-ch-ua-platform: "macOS"
		// Cache-Control: no-cache
		// User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36
		// Accept: text/event-stream
		// sec-ch-ua: "Chromium";v="130", "Google Chrome";v="130", "Not?A_Brand";v="99"
		// sec-ch-ua-mobile: ?0
		// Origin: null
		// Sec-Fetch-Site: cross-site
		// Sec-Fetch-Mode: cors
		// Sec-Fetch-Dest: empty
		// Accept-Encoding: gzip, deflate, br, zstd
		// Accept-Language: en-US,en;q=0.9,fr-CA;q=0.8,fr;q=0.7

		// We know any EventSource request must be a GET.  As a result, we know there is no request body.

		// First line is the URL and the rest are headers.
		// Line 1: GET /testing?one=two HTTP/1.1
		// Line 2: Accept-Encoding: gzip, deflate, br, zstd
		// ...and so forth.

		Request.Builder requestBuilder = null;
		Map<String, Set<String>> headers = new LinkedCaseInsensitiveMap<>(32);

		for (String line : rawRequest.lines().toList()) {
			line = Utilities.trimAggressivelyToNull(line);

			if (line == null)
				continue;

			if (requestBuilder == null) {
				// This is the first line.
				// Example: GET /testing?one=two HTTP/1.1
				String[] components = line.split(" ");

				if (components.length != 3)
					throw new IllegalStateException(format("Malformed Server-Sent Event request line '%s'. Expected a format like 'GET /example?one=two HTTP/1.1'", line));

				String httpMethod = components[0];
				String uri = components[1];

				if (!httpMethod.equals("GET"))
					throw new IllegalStateException(format("Malformed Server-Sent Event request line '%s'. Expected a format like 'GET /example?one=two HTTP/1.1'", line));

				if (!uri.startsWith("/"))
					throw new IllegalStateException(format("Malformed Server-Sent Event request line '%s'. Expected a format like 'GET /example?one=two HTTP/1.1'", line));

				requestBuilder = Request.with(HttpMethod.GET, uri);
			} else {
				// This is a header line.
				// Example: Accept-Encoding: gzip, deflate, br, zstd

				int indexOfFirstColon = line.indexOf(":");

				if (indexOfFirstColon == -1)
					throw new IllegalStateException(format("Malformed Server-Sent Event request line '%s'. Expected a format like 'Header-Name: Value", line));

				String headerName = line.substring(0, indexOfFirstColon);
				String headerValue = Utilities.trimAggressivelyToNull(line.substring(indexOfFirstColon + 1));

				Set<String> headerValues = headers.get(headerName);

				if (headerValues == null) {
					headerValues = new LinkedHashSet<>();
					headers.put(headerName, headerValues);
				}

				// Blank headers will have a key in the map, but an empty set of header values.
				if (headerValue != null)
					headerValues.add(headerValue);
			}
		}

		return requestBuilder.headers(headers).build();
	}

	@Nonnull
	protected String readRequest(@Nonnull SocketChannel clientSocketChannel) throws IOException {
		requireNonNull(clientSocketChannel);

		// Example request structure:
		//
		// GET /testing?one=two HTTP/1.1
		// Host: localhost:8081
		// Connection: keep-alive
		// sec-ch-ua-platform: "macOS"
		// Cache-Control: no-cache
		// User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36
		// Accept: text/event-stream
		// sec-ch-ua: "Chromium";v="130", "Google Chrome";v="130", "Not?A_Brand";v="99"
		// sec-ch-ua-mobile: ?0
		// Origin: null
		// Sec-Fetch-Site: cross-site
		// Sec-Fetch-Mode: cors
		// Sec-Fetch-Dest: empty
		// Accept-Encoding: gzip, deflate, br, zstd
		// Accept-Language: en-US,en;q=0.9,fr-CA;q=0.8,fr;q=0.7

		final int INITIAL_BUFFER_SIZE = 1024;

		ByteBuffer buffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);
		StringBuilder requestBuilder = new StringBuilder();
		boolean headersComplete = false;

		while (!headersComplete) {
			// Read data from the SocketChannel
			int bytesRead = clientSocketChannel.read(buffer);

			if (bytesRead == -1) {
				// End of stream (connection closed by client)
				throw new IOException("Client closed the connection before request was complete");
			}

			// Flip the buffer to read mode
			buffer.flip();

			// Decode the buffer content to a string and append to the request
			byte[] bytes = new byte[buffer.remaining()];
			buffer.get(bytes);
			requestBuilder.append(new String(bytes, StandardCharsets.UTF_8));

			// Check if the headers are complete (look for the "\r\n\r\n" marker)
			if (requestBuilder.indexOf("\r\n\r\n") != -1) {
				headersComplete = true;
			}

			// Clear the buffer for the next read
			buffer.clear();
		}

		// The HTTP request headers are now in the requestBuilder
		return requestBuilder.toString();
	}

	@Override
	public void stop() {
		getLock().lock();

		this.stopping = false;

		try {
			if (!isStarted())
				return;

			System.out.println("Stopping...");
			this.stopping = true;

			getStopPoisonPill().set(true);

			// TODO: unregister might be unsafe during iteration here
			for (Entry<SocketChannel, ServerSentEventConnection> entry : this.serverSentEventConnectionsBySocketChannel.entrySet()) {
				SocketChannel clientSocketChannel = entry.getKey();
				ServerSentEventConnection connection = entry.getValue();

				// Stop the writer
				connection.getWriteQueue().add(SERVER_SENT_EVENT_POISON_PILL);

				try {
					unregisterClientSocketChannel(clientSocketChannel);
				} catch (Exception e) {
				} finally {
					// TODO: logging/cleanup
				}
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

			if (this.stopping)
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
	protected Boolean isStopping() {
		getLock().lock();

		try {
			return this.stopping;
		} finally {
			getLock().unlock();
		}
	}

	@Nonnull
	@Override
	public Optional<? extends ServerSentEventSource> acquireEventSource(@Nullable ResourcePathInstance resourcePathInstance) {
		if (resourcePathInstance == null)
			return Optional.empty();

		return acquireEventSourceInternal(resourcePathInstance);
	}

	@Nonnull
	protected Optional<DefaultServerSentEventSource> acquireEventSourceInternal(@Nullable ResourcePathInstance resourcePathInstance) {
		if (resourcePathInstance == null)
			return Optional.empty();

		// DefaultServerSentEventSource serverSentEventSource = null;

		return Optional.ofNullable(getServerSentEventSourcesByResourcePathInstance().get(resourcePathInstance));

		// TODO: this could be optimized, but a system with hundreds of SSE event sources to walk would be very uncommon, so this is simple to understand and "fast enough"
//		for (Entry<ResourcePathInstance, DefaultServerSentEventSource> entry : getServerSentEventSourcesByResourcePathInstance().entrySet()) {
//			ResourcePathInstance resourcePathInstance = entry.getKey();
//
//			if (resourcePathInstance.matches(resourcePath)) {
//				serverSentEventSource = entry.getValue();
//				break;
//			}
//		}
//
//		return Optional.ofNullable(serverSentEventSource);
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
	protected ConcurrentHashMap<ResourcePathInstance, DefaultServerSentEventSource> getServerSentEventSourcesByResourcePathInstance() {
		return this.serverSentEventSourcesByResourcePathInstance;
	}

	@Nonnull
	protected ConcurrentHashMap<ResourcePathInstance, ResourcePath> getResourcePathsByResourcePathInstance() {
		return this.resourcePathsByResourcePathInstance;
	}

	@Nonnull
	protected ConcurrentHashMap<SocketChannel, ServerSentEventConnection> getServerSentEventConnectionsBySocketChannel() {
		return this.serverSentEventConnectionsBySocketChannel;
	}

	@Nonnull
	protected ConcurrentHashMap<DefaultServerSentEventSource, Set<ServerSentEventConnection>> getConnectionSetsByEventSource() {
		return this.connectionSetsByEventSource;
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
