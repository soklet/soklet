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
import com.soklet.core.ServerSentEventBroadcaster;
import com.soklet.core.ServerSentEventServer;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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

		// Make a unique "poison pill" server-sent event used to stop a socket thread by injecting it into the relevant write queue.
		// The contents don't matter; the object reference is used to determine if it's poison.
		SERVER_SENT_EVENT_POISON_PILL = ServerSentEvent.withData("poison").build();
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
	private final Set<ResourcePath> registeredResourcePaths;
	@Nonnull
	private final ConcurrentHashMap<ResourcePathInstance, DefaultServerSentEventBroadcaster> broadcastersByResourcePathInstance;
	@Nonnull
	private final ConcurrentHashMap<ResourcePathInstance, ResourcePath> resourcePathsByResourcePathInstanceCache;
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
	protected static class DefaultServerSentEventBroadcaster implements ServerSentEventBroadcaster {
		@Nonnull
		private final ResourcePathInstance resourcePathInstance;
		// This must be threadsafe, e.g. via ConcurrentHashMap#newKeySet
		@Nonnull
		private final Set<ServerSentEventConnection> serverSentEventConnections;

		public DefaultServerSentEventBroadcaster(@Nonnull ResourcePathInstance resourcePathInstance) {
			requireNonNull(resourcePathInstance);

			this.resourcePathInstance = resourcePathInstance;
			// TODO: let clients specify capacity
			this.serverSentEventConnections = ConcurrentHashMap.newKeySet(1_024);
		}

		@Nonnull
		@Override
		public ResourcePathInstance getResourcePathInstance() {
			return this.resourcePathInstance;
		}

		@Nonnull
		@Override
		public Long getClientCount() {
			return (long) getServerSentEventConnections().size();
		}

		@Override
		public void broadcast(@Nonnull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);

			// We can broadcast from the current thread because putting elements onto blocking queues is reasonably fast.
			// The blocking queues are consumed by separate per-socket-channel threads
			for (ServerSentEventConnection serverSentEventConnection : getServerSentEventConnections())
				serverSentEventConnection.getWriteQueue().add(serverSentEvent);
		}

		@Nonnull
		public Boolean registerServerSentEventConnection(@Nullable ServerSentEventConnection serverSentEventConnection) {
			if (serverSentEventConnection == null)
				return false;

			// Underlying set is threadsafe so this is OK
			return getServerSentEventConnections().add(serverSentEventConnection);
		}

		@Nonnull
		public Boolean unregisterServerSentEventConnection(@Nullable ServerSentEventConnection serverSentEventConnection,
																											 @Nonnull Boolean sendPoisonPill) {
			requireNonNull(sendPoisonPill);

			if (serverSentEventConnection == null)
				return false;

			// Underlying set is threadsafe so this is OK
			boolean unregistered = getServerSentEventConnections().remove(serverSentEventConnection);

			// Send a poison pill so the socket thread gets terminated
			if (unregistered && sendPoisonPill)
				serverSentEventConnection.getWriteQueue().add(SERVER_SENT_EVENT_POISON_PILL);

			return unregistered;
		}

		@Nonnull
		public void unregisterAllServerSentEventConnections(@Nonnull Boolean sendPoisonPill) {
			requireNonNull(sendPoisonPill);

			// TODO: we probably want to have a lock around registration/unregistration
			for (ServerSentEventConnection serverSentEventConnection : getServerSentEventConnections())
				unregisterServerSentEventConnection(serverSentEventConnection, sendPoisonPill);
		}

		@Nonnull
		protected Set<ServerSentEventConnection> getServerSentEventConnections() {
			return this.serverSentEventConnections;
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

		// Registered resource paths are registered at creation time and can never change
		this.registeredResourcePaths = builder.resourcePaths == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(builder.resourcePaths));

		// TODO: let clients specify initial capacity
		this.broadcastersByResourcePathInstance = new ConcurrentHashMap<>(1_024);
		this.resourcePathsByResourcePathInstanceCache = new ConcurrentHashMap<>(1_024);

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

		ClientSocketChannelRegistration clientSocketChannelRegistration = null;

		try (clientSocketChannel) {
			String rawRequest = null;

			try {
				rawRequest = readRequest(clientSocketChannel);
			} catch (Exception e) {
				// TODO: cleanup
				System.out.println("Unable to read raw request.");
				e.printStackTrace();
			}

			// Use the socket's address as an identifier
			String requestIdentifier = clientSocketChannel.getRemoteAddress().toString();
			Request request = parseRequest(requestIdentifier, rawRequest);

			System.out.println(format("Bound SSE request to socket: %s", debuggingString(request)));

			clientSocketChannelRegistration = registerClientSocketChannel(clientSocketChannel, request).get();

			final String SSE_HEADERS_AS_STRING = "HTTP/1.1 200 OK\r\n" +
					"Content-Type: text/event-stream\r\n" +
					"Cache-Control: no-cache\r\n" +
					// TODO: let clients specify this and other headers
					"Access-Control-Allow-Origin: *\r\n" +
					"Connection: keep-alive\r\n\r\n";

			// Immediately send SSE headers
			clientSocketChannel.write(ByteBuffer.wrap(SSE_HEADERS_AS_STRING.getBytes()));

			while (true) {
				ServerSentEvent serverSentEvent = clientSocketChannelRegistration.serverSentEventConnection().getWriteQueue().take();

				if (serverSentEvent == SERVER_SENT_EVENT_POISON_PILL) {
					System.out.println("Encountered poison pill, exiting...");
					break;
				}

				System.out.println(format("Writing %s to %s...", serverSentEvent, debuggingString(request)));
				String message = formatForResponse(serverSentEvent);
				ByteBuffer buffer = ByteBuffer.wrap(message.getBytes());
				clientSocketChannel.write(buffer);
				System.out.println(format("Wrote %s to %s", serverSentEvent, debuggingString(request)));
			}
		} catch (Exception e) {
			System.out.println("Closing socket due to exception: " + e.getMessage());

			if (e instanceof InterruptedException) {
				System.out.println("Socket thread was interrupted");
				Thread.currentThread().interrupt();  // Restore interrupt status
			}
		} finally {
			// First, tell the event source to unregister the connection
			if (clientSocketChannelRegistration != null) {
				try {
					clientSocketChannelRegistration.broadcaster().unregisterServerSentEventConnection(clientSocketChannelRegistration.serverSentEventConnection(), false);

					System.out.println(format("SSE socket thread completed for request: %s", debuggingString(clientSocketChannelRegistration.serverSentEventConnection().getRequest())));
				} catch (Exception ignored) {
					System.out.println("Unable to de-register connection");
					ignored.printStackTrace();
				}
			}

			// Then, close the channel itself
			if (clientSocketChannel != null) {
				try {
					clientSocketChannel.close();
				} catch (Exception ignored) {
					System.out.println("Unable to close socket channel");
					ignored.printStackTrace();
				}
			}
		}
	}

	@Nonnull
	protected String formatForResponse(@Nonnull ServerSentEvent serverSentEvent) {
		requireNonNull(serverSentEvent);

		if (serverSentEvent.isHeartbeat())
			return ":\n\n";

		String event = serverSentEvent.getEvent().orElse(null);

		String data = serverSentEvent.getData().orElse(null);
		List<String> dataLines = data == null ? List.of() : data.lines()
				.map(line -> format("data: %s", line))
				.toList();

		String id = serverSentEvent.getId().orElse(null);
		Duration retry = serverSentEvent.getRetry().orElse(null);

		List<String> lines = new ArrayList<>(16);

		if (event != null)
			lines.add(format("event: %s", event));

		if (id != null)
			lines.add(format("id: %s", id));

		if (retry != null)
			lines.add(format("retry: %d", retry.toMillis()));

		if (dataLines.size() > 0)
			lines.addAll(dataLines);

		if (lines.size() == 0)
			return ":\n\n";

		return format("%s\n\n", lines.stream().collect(Collectors.joining("\n")));
	}

	@Nonnull
	protected String debuggingString(@Nonnull Request request) {
		requireNonNull(request);
		return format("%s %s %s", request.getId(), request.getHttpMethod().name(), request.getUri());
	}

	@ThreadSafe
	protected static class ServerSentEventConnection {
		@Nonnull
		private final Request request;
		@Nonnull
		private final ResourcePathInstance resourcePathInstance;
		@Nonnull
		private final BlockingQueue<ServerSentEvent> writeQueue;

		public ServerSentEventConnection(@Nonnull Request request,
																		 @Nonnull ResourcePathInstance resourcePathInstance) {
			requireNonNull(request);
			requireNonNull(resourcePathInstance);

			this.request = request;
			this.resourcePathInstance = resourcePathInstance;
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
		public BlockingQueue<ServerSentEvent> getWriteQueue() {
			return this.writeQueue;
		}
	}

	protected record ClientSocketChannelRegistration(@Nonnull ServerSentEventConnection serverSentEventConnection,
																									 @Nonnull DefaultServerSentEventBroadcaster broadcaster) {
		public ClientSocketChannelRegistration {
			requireNonNull(serverSentEventConnection);
			requireNonNull(broadcaster);
		}
	}

	@Nonnull
	protected Optional<ClientSocketChannelRegistration> registerClientSocketChannel(@Nonnull SocketChannel clientSocketChannel,
																																									@Nonnull Request request) {
		requireNonNull(clientSocketChannel);
		requireNonNull(request);

		ResourcePathInstance resourcePathInstance = ResourcePathInstance.of(request.getPath());

		// TODO: check to see if this is a 404 and if so, short-circuit

		// Get a handle to the event source (it will be created if necessary)
		DefaultServerSentEventBroadcaster broadcaster = acquireBroadcasterInternal(resourcePathInstance).get();

		// Create the connection and register it with the EventSource
		ServerSentEventConnection serverSentEventConnection = new ServerSentEventConnection(request, broadcaster.getResourcePathInstance());
		broadcaster.registerServerSentEventConnection(serverSentEventConnection);

		return Optional.of(new ClientSocketChannelRegistration(serverSentEventConnection, broadcaster));
	}

	@Nonnull
	protected Request parseRequest(@Nonnull String requestIdentifier,
																 @Nonnull String rawRequest) {
		requireNonNull(requestIdentifier);
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

		return requestBuilder.id(requestIdentifier).headers(headers).build();
	}

	@Nonnull
	protected String readRequest(@Nonnull SocketChannel clientSocketChannel) throws IOException {
		requireNonNull(clientSocketChannel);

		final int INITIAL_BUFFER_SIZE = 1_024;

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

			// TODO: need an additional check/lock to prevent race condition where someone acquires an event source while we are shutting down
			for (DefaultServerSentEventBroadcaster serverSentEventSource : getBroadcastersByResourcePathInstance().values()) {
				try {
					serverSentEventSource.unregisterAllServerSentEventConnections(true);
				} catch (Exception e) {
					System.out.println("Unable to unregister connection, continuing on...");
					e.printStackTrace();
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
			this.getBroadcastersByResourcePathInstance().clear();
			this.getResourcePathsByResourcePathInstanceCache().clear();
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
	public Optional<? extends ServerSentEventBroadcaster> acquireBroadcaster(@Nullable ResourcePathInstance resourcePathInstance) {
		if (resourcePathInstance == null)
			return Optional.empty();

		return acquireBroadcasterInternal(resourcePathInstance);
	}

	@Nonnull
	protected Optional<DefaultServerSentEventBroadcaster> acquireBroadcasterInternal(@Nullable ResourcePathInstance resourcePathInstance) {
		if (resourcePathInstance == null)
			return Optional.empty();

		// Try a cache lookup first
		ResourcePath resourcePath = getResourcePathsByResourcePathInstanceCache().get(resourcePathInstance);

		if (resourcePath == null) {
			// If the cache lookup fails, perform a manual lookup
			for (ResourcePath registeredResourcePath : getRegisteredResourcePaths()) {
				if (registeredResourcePath.matches(resourcePathInstance)) {
					resourcePath = registeredResourcePath;
					break;
				}
			}

			// Put the value in the cache for quick access later
			getResourcePathsByResourcePathInstanceCache().put(resourcePathInstance, resourcePath);
		}

		// The resource path instance doesn't match a resource path we already have on file - no event source exists for it.
		if (resourcePath == null)
			return Optional.empty();

		// Create the event source if it does not already exist
		DefaultServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePathInstance()
				.computeIfAbsent(resourcePathInstance, (ignored) -> new DefaultServerSentEventBroadcaster(resourcePathInstance));

		return Optional.of(broadcaster);
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
	protected Set<ResourcePath> getRegisteredResourcePaths() {
		return this.registeredResourcePaths;
	}

	@Nonnull
	protected ConcurrentHashMap<ResourcePathInstance, DefaultServerSentEventBroadcaster> getBroadcastersByResourcePathInstance() {
		return this.broadcastersByResourcePathInstance;
	}

	@Nonnull
	protected ConcurrentHashMap<ResourcePathInstance, ResourcePath> getResourcePathsByResourcePathInstanceCache() {
		return this.resourcePathsByResourcePathInstanceCache;
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
