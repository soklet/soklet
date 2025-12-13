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

import com.soklet.DefaultServerSentEventServer.ServerSentEventConnection.WriteQueueElement;
import com.soklet.annotation.ServerSentEventSource;
import com.soklet.exception.IllegalRequestException;
import com.soklet.internal.util.ConcurrentLruMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultServerSentEventServer implements ServerSentEventServer {
	@Nonnull
	private static final String DEFAULT_HOST;
	@Nonnull
	private static final Duration DEFAULT_REQUEST_TIMEOUT;
	@Nonnull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
	@Nonnull
	private static final Integer DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
	@Nonnull
	private static final Duration DEFAULT_HEARTBEAT_INTERVAL;
	@Nonnull
	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT;
	@Nonnull
	private static final Integer DEFAULT_CONNECTION_QUEUE_CAPACITY;
	@Nonnull
	private static final Integer DEFAULT_CONCURRENT_CONNECTION_LIMIT;
	@Nonnull
	private static final Integer DEFAULT_BROADCASTER_CACHE_CAPACITY;
	@Nonnull
	private static final Integer DEFAULT_RESOURCE_PATH_CACHE_CAPACITY;
	@Nonnull
	private static final Boolean DEFAULT_VERIFY_CONNECTION_ONCE_ESTABLISHED;
	@Nonnull
	private static final ServerSentEvent SERVER_SENT_EVENT_POISON_PILL;
	@Nonnull
	private static final byte[] FAILSAFE_HANDSHAKE_HTTP_500_RESPONSE;
	@Nonnull
	private static final ResourcePathDeclaration NO_MATCH_SENTINEL;

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024;
		DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES = 1_024;
		DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(1);
		DEFAULT_CONNECTION_QUEUE_CAPACITY = 512;
		DEFAULT_CONCURRENT_CONNECTION_LIMIT = 8_192;
		DEFAULT_BROADCASTER_CACHE_CAPACITY = 1_024;
		DEFAULT_RESOURCE_PATH_CACHE_CAPACITY = 8_192;
		DEFAULT_VERIFY_CONNECTION_ONCE_ESTABLISHED = true;

		// Make a unique "poison pill" server-sent event used to stop a socket listener thread by injecting it into the relevant write queue.
		// When this event is taken off of the queue, the socket is torn down and the thread finishes running.
		// The contents don't matter; the object reference is used to determine if it's poison.
		SERVER_SENT_EVENT_POISON_PILL = ServerSentEvent.withEvent("poison").build();

		// Cache off a special failsafe response
		FAILSAFE_HANDSHAKE_HTTP_500_RESPONSE = createFailsafeHandshakeHttp500Response();

		// Create a sentinel ResourcePathDeclaration that represents "no match found".
		// This allows us to cache negative lookups and avoid repeated O(n) scans.
		NO_MATCH_SENTINEL = ResourcePathDeclaration.withPath("/__soklet_internal_no_match_sentinel__");
	}

	/**
	 * Strategy/callback for handling backpressure on a single connection's write queue.
	 * Implemented by the server; injected into broadcasters. Avoids a broadcaster holding a server reference explicitly.
	 */
	@FunctionalInterface
	protected interface BackpressureHandler {
		void onBackpressure(@Nonnull DefaultServerSentEventBroadcaster owner,
												@Nonnull ServerSentEventConnection connection,
												@Nonnull String cause);
	}

	@Nonnull
	private final Integer port;
	@Nonnull
	private final String host;
	@Nonnull
	private final Duration requestTimeout;
	@Nonnull
	private final Duration shutdownTimeout;
	@Nonnull
	private final Duration heartbeatInterval;
	@Nonnull
	private final Integer maximumRequestSizeInBytes;
	@Nonnull
	private final Integer requestReadBufferSizeInBytes;
	@Nonnull
	private final Boolean verifyConnectionOnceEstablished;
	@Nonnull
	private final ConcurrentHashMap<ResourcePath, DefaultServerSentEventBroadcaster> broadcastersByResourcePath;
	@Nonnull
	private final ConcurrentLruMap<ResourcePath, ResourcePathDeclaration> resourcePathDeclarationsByResourcePathCache;
	@Nonnull
	private final ConcurrentLruMap<ServerSentEventConnection, DefaultServerSentEventBroadcaster> globalConnections;
	@Nonnull
	private final ReentrantLock lock;
	@Nonnull
	private final Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
	@Nonnull
	private final Supplier<ExecutorService> requestReaderExecutorServiceSupplier;
	@Nonnull
	private final Integer concurrentConnectionLimit;
	@Nonnull
	private final Integer connectionQueueCapacity;
	@Nonnull
	private final AtomicBoolean stopPoisonPill;
	@Nullable
	private volatile ExecutorService requestHandlerExecutorService;
	@Nullable
	private volatile ExecutorService requestReaderExecutorService;
	@Nonnull
	private volatile Boolean started;
	@Nonnull
	private volatile Boolean stopping;
	@Nullable
	private Thread eventLoopThread;
	@Nullable
	private volatile ServerSocketChannel serverSocketChannel;
	// Does not need to be concurrent because it's calculated just once at initialization time and is never modified after
	@Nonnull
	private volatile Map<ResourcePathDeclaration, ResourceMethod> resourceMethodsByResourcePathDeclaration;
	@Nullable
	private RequestHandler requestHandler;
	@Nullable
	private LifecycleInterceptor lifecycleInterceptor;
	@Nullable
	private IdGenerator<?> idGenerator;

	@ThreadSafe
	protected static class DefaultServerSentEventBroadcaster implements ServerSentEventBroadcaster {
		@Nonnull
		private final ResourceMethod resourceMethod;
		@Nonnull
		private final ResourcePath resourcePath;
		@Nonnull
		private final BackpressureHandler backpressureHandler;
		@Nonnull
		private final Consumer<ServerSentEventConnection> connectionUnregisteredListener;
		@Nonnull
		private final Consumer<LogEvent> logEventConsumer;
		// This must be threadsafe, e.g. via ConcurrentHashMap#newKeySet
		@Nonnull
		private final Set<ServerSentEventConnection> serverSentEventConnections;

		public DefaultServerSentEventBroadcaster(@Nonnull ResourceMethod resourceMethod,
																						 @Nonnull ResourcePath resourcePath,
																						 @Nonnull BackpressureHandler backpressureHandler,
																						 @Nonnull Consumer<ServerSentEventConnection> connectionUnregisteredListener,
																						 @Nonnull Consumer<LogEvent> logEventConsumer) {
			requireNonNull(resourceMethod);
			requireNonNull(resourcePath);
			requireNonNull(backpressureHandler);
			requireNonNull(connectionUnregisteredListener);
			requireNonNull(logEventConsumer);

			this.resourceMethod = resourceMethod;
			this.resourcePath = resourcePath;
			this.backpressureHandler = backpressureHandler;
			this.connectionUnregisteredListener = connectionUnregisteredListener;
			this.logEventConsumer = logEventConsumer;
			// TODO: let clients specify capacity
			this.serverSentEventConnections = ConcurrentHashMap.newKeySet(256);
		}

		@Nonnull
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@Nonnull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@Nonnull
		@Override
		public Long getClientCount() {
			return (long) getServerSentEventConnections().size();
		}

		@Override
		public void broadcastEvent(@Nonnull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);

			// We can broadcast from the current thread because putting elements onto blocking queues is reasonably fast.
			// The blocking queues are consumed by separate per-socket-channel threads
			for (ServerSentEventConnection serverSentEventConnection : getServerSentEventConnections())
				enqueueServerSentEvent(this, serverSentEventConnection, serverSentEvent, getBackpressureHandler());
		}

		@Override
		public void broadcastComment(@Nonnull String comment) {
			requireNonNull(comment);

			// We can broadcast from the current thread because putting elements onto blocking queues is reasonably fast.
			// The blocking queues are consumed by separate per-socket-channel threads
			for (ServerSentEventConnection serverSentEventConnection : getServerSentEventConnections())
				enqueueComment(this, serverSentEventConnection, comment, getBackpressureHandler());
		}

		@Override
		public <T> void broadcastEvent(@Nonnull Function<Object, T> keySelector,
																	 @Nonnull Function<T, ServerSentEvent> eventProvider) {
			requireNonNull(keySelector);
			requireNonNull(eventProvider);

			Map<T, ServerSentEvent> payloadCache = new HashMap<>();

			for (ServerSentEventConnection connection : getServerSentEventConnections()) {
				Object clientContext = connection.getClientContext().orElse(null);

				try {
					// Ask client code to generate a key given the context object
					T key = keySelector.apply(clientContext);

					// Ask client code to generate payload (if not present in our local cache)
					ServerSentEvent event = payloadCache.computeIfAbsent(key, eventProvider);
					enqueueServerSentEvent(this, connection, event, getBackpressureHandler());
				} catch (Throwable t) {
					this.getLogEventConsumer().accept(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_BROADCAST_GENERATION_FAILED,
									format("Failed to generate Server-Sent-Event for connection on %s with client context %s",
											getResourcePath(), (clientContext == null ? "[none specified]" : clientContext)))
							.throwable(t)
							.build());
				}
			}
		}

		@Override
		public <T> void broadcastComment(@Nonnull Function<Object, T> keySelector,
																		 @Nonnull Function<T, String> commentProvider) {
			requireNonNull(keySelector);
			requireNonNull(commentProvider);

			Map<T, String> payloadCache = new HashMap<>();

			for (ServerSentEventConnection connection : getServerSentEventConnections()) {
				Object clientContext = connection.getClientContext().orElse(null);

				try {
					// Ask client code to generate a key given the context object
					T key = keySelector.apply(clientContext);

					// Ask client code to generate payload (if not present in our local cache)
					String comment = payloadCache.computeIfAbsent(key, commentProvider);

					enqueueComment(this, connection, comment, getBackpressureHandler());
				} catch (Throwable t) {
					this.getLogEventConsumer().accept(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_BROADCAST_GENERATION_FAILED,
									format("Failed to generate Server-Sent Event comment for connection on %s with client context %s",
											getResourcePath(), (clientContext == null ? "[none specified]" : clientContext)))
							.throwable(t)
							.build());
				}
			}
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

			if (unregistered)
				getConnectionUnregisteredListener().accept(serverSentEventConnection);

			// Send poison pill regardless of whether we were registered, if requested.
			// This handles the edge case where eviction happens before registration completes.
			if (sendPoisonPill)
				enqueueServerSentEvent(this, serverSentEventConnection, SERVER_SENT_EVENT_POISON_PILL, getBackpressureHandler());

			return unregistered;
		}

		@Nonnull
		public void unregisterAllServerSentEventConnections(@Nonnull Boolean sendPoisonPill) {
			requireNonNull(sendPoisonPill);

			// Snapshot list for consistency during unregister process
			for (ServerSentEventConnection serverSentEventConnection : new ArrayList<>(getServerSentEventConnections()))
				unregisterServerSentEventConnection(serverSentEventConnection, sendPoisonPill);
		}

		@Nonnull
		private BackpressureHandler getBackpressureHandler() {
			return this.backpressureHandler;
		}

		@Nonnull
		private Set<ServerSentEventConnection> getServerSentEventConnections() {
			return this.serverSentEventConnections;
		}

		@Nonnull
		private Consumer<ServerSentEventConnection> getConnectionUnregisteredListener() {
			return this.connectionUnregisteredListener;
		}

		@Nonnull
		private Consumer<LogEvent> getLogEventConsumer() {
			return this.logEventConsumer;
		}
	}

	protected DefaultServerSentEventServer(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.stopPoisonPill = new AtomicBoolean(false);
		this.started = false;
		this.stopping = false;
		this.lock = new ReentrantLock();
		this.port = builder.port;
		this.host = builder.host != null ? builder.host : DEFAULT_HOST;
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.requestReadBufferSizeInBytes = builder.requestReadBufferSizeInBytes != null ? builder.requestReadBufferSizeInBytes : DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
		this.verifyConnectionOnceEstablished = builder.verifyConnectionOnceEstablished != null ? builder.verifyConnectionOnceEstablished : DEFAULT_VERIFY_CONNECTION_ONCE_ESTABLISHED;
		this.idGenerator = builder.idGenerator != null ? builder.idGenerator : IdGenerator.withDefaults();
		this.requestTimeout = builder.requestTimeout != null ? builder.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
		this.shutdownTimeout = builder.shutdownTimeout != null ? builder.shutdownTimeout : DEFAULT_SHUTDOWN_TIMEOUT;
		this.heartbeatInterval = builder.heartbeatInterval != null ? builder.heartbeatInterval : DEFAULT_HEARTBEAT_INTERVAL;
		this.resourceMethodsByResourcePathDeclaration = Map.of(); // Temporary to remain non-null; will be overridden by Soklet via #initialize

		if (this.maximumRequestSizeInBytes <= 0)
			throw new IllegalArgumentException("Maximum request size must be > 0");

		if (this.requestReadBufferSizeInBytes <= 0)
			throw new IllegalArgumentException("Request read buffer size must be > 0");

		if (this.requestTimeout.isNegative() || this.requestTimeout.isZero())
			throw new IllegalArgumentException("Request timeout must be > 0");

		if (this.shutdownTimeout.isNegative())
			throw new IllegalArgumentException("Shutdown timeout must be >= 0");

		if (this.heartbeatInterval.isNegative() || this.heartbeatInterval.isZero())
			throw new IllegalArgumentException("Heartbeat interval must be > 0");

		int broadcasterInitialCapacity = builder.broadcasterCacheCapacity != null
				? builder.broadcasterCacheCapacity
				: DEFAULT_BROADCASTER_CACHE_CAPACITY;

		// Important: broadcaster registry must not evict live broadcasters, or active clients can become orphaned.
		// We rely on maybeCleanupBroadcaster(...) to remove empty broadcasters safely.
		this.broadcastersByResourcePath = new ConcurrentHashMap<>(broadcasterInitialCapacity);

		this.resourcePathDeclarationsByResourcePathCache = new ConcurrentLruMap<>(builder.resourcePathCacheCapacity != null ? builder.resourcePathCacheCapacity : DEFAULT_RESOURCE_PATH_CACHE_CAPACITY, (resourcePath, broadcaster) -> { /* nothing to do for now */});

		// Cowardly refuse to run on anything other than a runtime that supports Virtual threads.
		if (!Utilities.virtualThreadsAvailable())
			throw new IllegalStateException(format("Virtual threads are required for %s", getClass().getSimpleName()));

		this.requestHandlerExecutorServiceSupplier = builder.requestHandlerExecutorServiceSupplier != null ? builder.requestHandlerExecutorServiceSupplier : () -> {
			String threadNamePrefix = "sse-request-handler-";

			return Utilities.createVirtualThreadsNewThreadPerTaskExecutor(threadNamePrefix, (Thread thread, Throwable throwable) -> {
				try {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unexpected exception occurred during server Server-Sent Event processing")
							.throwable(throwable)
							.build());
				} catch (Throwable loggingThrowable) {
					// We are in a bad state - the log operation in the uncaught exception handler failed.
					// Not much else we can do here but dump to stderr and try to stop the server.
					throwable.printStackTrace(System.err);
					loggingThrowable.printStackTrace(System.err);
				}
			});
		};

		this.requestReaderExecutorServiceSupplier = () -> {
			String threadNamePrefix = "sse-request-reader-";

			return Utilities.createVirtualThreadsNewThreadPerTaskExecutor(threadNamePrefix, (Thread thread, Throwable throwable) -> {
				try {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unexpected exception occurred during server Server-Sent Event request reading")
							.throwable(throwable)
							.build());
				} catch (Throwable loggingThrowable) {
					// We are in a bad state - the log operation in the uncaught exception handler failed.
					// Not much else we can do here but dump to stderr and try to stop the server.
					throwable.printStackTrace(System.err);
					loggingThrowable.printStackTrace(System.err);
				}
			});
		};

		this.concurrentConnectionLimit = builder.concurrentConnectionLimit != null ? builder.concurrentConnectionLimit : DEFAULT_CONCURRENT_CONNECTION_LIMIT;

		if (this.concurrentConnectionLimit < 1)
			throw new IllegalArgumentException("The value for concurrentConnectionLimit must be > 0");

		this.connectionQueueCapacity = builder.connectionQueueCapacity != null ? builder.connectionQueueCapacity : DEFAULT_CONNECTION_QUEUE_CAPACITY;

		if (this.connectionQueueCapacity < 1)
			throw new IllegalArgumentException("The value for connectionQueueCapacity must be > 0");

		// Initialize the global LRU map with the specified limit. Assume ConcurrentLRUMap supports a removal listener.
		this.globalConnections = new ConcurrentLruMap<>(getConcurrentConnectionLimit(), (evictedConnection, broadcaster) -> {
			// This callback is triggered when a connection is evicted from the global LRU map.
			// Unregister the evicted connection from the broadcaster and send poison pill to close it.
			broadcaster.unregisterServerSentEventConnection(evictedConnection, true);
		});
	}

	@Override
	public void initialize(@Nonnull SokletConfig sokletConfig,
												 @Nonnull RequestHandler requestHandler) {
		requireNonNull(sokletConfig);
		requireNonNull(requestHandler);

		this.lifecycleInterceptor = sokletConfig.getLifecycleInterceptor();
		this.requestHandler = requestHandler;

		// Pick out all the @ServerSentEventSource resource methods and store off keyed on resource path for ease of lookup.
		// This is computed just once here and will never change.
		// Fail fast if there are duplicates for the same declaration.
		this.resourceMethodsByResourcePathDeclaration =
				sokletConfig.getResourceMethodResolver().getResourceMethods().stream()
						.filter(ResourceMethod::isServerSentEventSource)
						.collect(Collectors.toMap(
								ResourceMethod::getResourcePathDeclaration,
								Function.identity(),
								(resourceMethod1, resourceMethod2) -> {
									throw new IllegalStateException(format("Multiple @%s methods mapped to the same resource path: %s",
											ServerSentEventSource.class.getSimpleName(), resourceMethod1.getResourcePathDeclaration()));
								}
						));
	}

	@Override
	public void start() {
		getLock().lock();

		try {
			if (isStarted())
				return;

			// Should never happen, this would already be set by the Soklet instance
			if (getRequestHandler().isEmpty())
				throw new IllegalStateException(format("No %s was registered for %s", RequestHandler.class, getClass()));

			if (getLifecycleInterceptor().isEmpty())
				throw new IllegalStateException(format("No %s was registered for %s", LifecycleInterceptor.class, getClass()));

			this.requestHandlerExecutorService = getRequestHandlerExecutorServiceSupplier().get();
			this.requestReaderExecutorService = getRequestReaderExecutorServiceSupplier().get();
			this.stopping = false;
			this.started = true; // set before thread starts to avoid early exit races
			this.eventLoopThread = new Thread(this::startInternal, "sse-event-loop");
			this.eventLoopThread.start();
		} finally {
			getLock().unlock();
		}
	}

	@Nonnull
	private static Boolean enqueueServerSentEvent(@Nonnull DefaultServerSentEventBroadcaster owner,
																								@Nonnull ServerSentEventConnection connection,
																								@Nonnull ServerSentEvent serverSentEvent,
																								@Nonnull BackpressureHandler handler) {
		requireNonNull(owner);
		requireNonNull(connection);
		requireNonNull(serverSentEvent);
		requireNonNull(handler);

		BlockingQueue<WriteQueueElement> writeQueue = connection.getWriteQueue();
		WriteQueueElement e = WriteQueueElement.withServerSentEvent(serverSentEvent);

		if (writeQueue.offer(e))
			return true;

		// Queue full — delegate to handler: log + close + unregister.
		String cause = (serverSentEvent == SERVER_SENT_EVENT_POISON_PILL) ? "poison-pill" : "event";
		handler.onBackpressure(owner, connection, cause);

		return false;
	}

	@Nonnull
	private static Boolean enqueueComment(@Nonnull DefaultServerSentEventBroadcaster owner,
																				@Nonnull ServerSentEventConnection connection,
																				@Nonnull String comment,
																				@Nonnull BackpressureHandler handler) {
		requireNonNull(owner);
		requireNonNull(connection);
		requireNonNull(comment);
		requireNonNull(handler);

		BlockingQueue<WriteQueueElement> writeQueue = connection.getWriteQueue();
		WriteQueueElement writeQueueElement = WriteQueueElement.withComment(comment);

		if (writeQueue.offer(writeQueueElement))
			return true;

		handler.onBackpressure(owner, connection, "comment");
		return false;
	}

	protected void startInternal() {
		if (!isStarted() || isStopping())
			return;

		ServerSocketChannel serverSocketChannel;
		boolean bindSucceeded = false;

		try {
			serverSocketChannel = ServerSocketChannel.open();
			serverSocketChannel.bind(new InetSocketAddress(getHost(), getPort()));

			bindSucceeded = true;

			this.serverSocketChannel = serverSocketChannel;

			ExecutorService executorService = getRequestHandlerExecutorService().orElse(null);

			if (executorService == null || executorService.isShutdown() || isStopping())
				// We started while a stop was underway; exit quietly
				return;

			while (!getStopPoisonPill().get()) {
				SocketChannel clientSocketChannel = this.serverSocketChannel.accept();
				Socket socket = clientSocketChannel.socket();
				socket.setKeepAlive(true);
				socket.setTcpNoDelay(true);

				try {
					executorService.submit(() -> handleClientSocketChannel(clientSocketChannel));
				} catch (RejectedExecutionException e) {
					// Pool is shutting down; close channel and exit the loop
					try {
						clientSocketChannel.close();
					} catch (IOException ignored) {
						// Nothing to do
					}

					break;
				}
			}
		} catch (ClosedChannelException ignored) {
			// expected during shutdown
		} catch (IOException e) {
			safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR,
					"SSE event loop encountered an IO error").throwable(e).build());
		} finally {
			// Close the server socket if we opened it
			ServerSocketChannel serverSocketChannelToClose = this.serverSocketChannel;
			this.serverSocketChannel = null;

			if (serverSocketChannelToClose != null) {
				try {
					serverSocketChannelToClose.close();
				} catch (IOException ignored) {
					// Nothing to do
				}
			}

			// If the socket was never bound, ensure a correct stop
			if (!bindSucceeded)
				stop();
		}
	}

	protected void handleClientSocketChannel(@Nonnull SocketChannel clientSocketChannel) {
		requireNonNull(clientSocketChannel);

		ClientSocketChannelRegistration clientSocketChannelRegistration = null;
		Request request = null;
		ResourceMethod resourceMethod = null;
		Instant writeStarted;
		Throwable throwable = null;

		// Keep track of whether or not we should go through the "handshake accepted" flow.
		// Might change from "accepted" to "rejected" if an error occurs
		AtomicReference<HandshakeResult.Accepted> handshakeAcceptedReference = new AtomicReference<>();

		try (clientSocketChannel) {
			try {
				// TODO: in a future version, we might introduce lifecycle interceptor option here and for Server for "will/didInitiateConnection"
				String rawRequest = readRequest(clientSocketChannel);
				request = parseRequest(rawRequest);
			} catch (RequestTooLargeIOException e) {
				// Exception provides a "too large"-flagged request with whatever data we could pull out of it
				request = e.getTooLargeRequest();
			} catch (SocketTimeoutException e) {
				// TODO: in a future version, we might introduce lifecycle interceptor option here and for Server for "request timed out"
				throw e;
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_HANDSHAKE_REQUEST_UNPARSEABLE, "Unable to parse Server-Sent Event request")
						.throwable(e)
						.build());
				throw e;
			}

			// OK, we've successfully parsed the SSE handshake request - now, determine the resource path
			ResourcePathDeclaration resourcePathDeclaration = matchingResourcePath(request.getResourcePath()).orElse(null);

			if (resourcePathDeclaration != null)
				resourceMethod = getResourceMethodsByResourcePathDeclaration().get(resourcePathDeclaration);

			// We're now ready to write the handshake response - and then we keep the socket open for subsequent writes if handshake was accepted (otherwise we write the body and close).
			// To write the handshake response, we delegate to the Soklet instance, handing it the request we just parsed
			// and receiving a MarshaledResponse to write.  This lets the normal Soklet request processing flow occur.
			// Subsequent writes to the open socket (those following successful transmission of the "accepted" handshake response) are done via a ServerSentEventBroadcaster and sidestep the Soklet request processing flow.
			getRequestHandler().get().handleRequest(request, (@Nonnull RequestResult requestResult) -> {
				// Set to the value Soklet processing gives us. Will be the empty Optional if no resource method was matched
				HandshakeResult handshakeResult = requestResult.getHandshakeResult().orElse(null);

				// Store a reference to the accepted handshake if we have it
				if (handshakeResult != null && handshakeResult instanceof HandshakeResult.Accepted accepted)
					handshakeAcceptedReference.set(accepted);

				byte[] handshakeHttpResponse;

				try {
					handshakeHttpResponse = createHandshakeHttpResponse(requestResult);
				} catch (Throwable t) {
					// Should not happen, but if it does, we fall back to "rejected" handshake mode and write a failsafe 500 response
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to generate SSE handshake response")
							.throwable(t)
							.build());

					// Clear the accepted handshake reference in case it was set
					handshakeAcceptedReference.set(null);
					handshakeHttpResponse = FAILSAFE_HANDSHAKE_HTTP_500_RESPONSE;
				}

				try {
					ByteBuffer byteBuffer = ByteBuffer.wrap(handshakeHttpResponse);

					while (byteBuffer.hasRemaining())
						clientSocketChannel.write(byteBuffer);
				} catch (Throwable t) {
					// We couldn't write a response to the client (maybe they disconnected).
					// Go through the rejected flow and close out the connection
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_WRITING_HANDSHAKE_RESPONSE_FAILED, "Unable to write SSE handshake response")
							.throwable(t)
							.build());

					// Clear the accepted handshake reference in case it was set
					handshakeAcceptedReference.set(null);
				}
			});

			// Happy path: register the channel for future ServerSentEvent writes and keep it open.
			// Otherwise, we're done immediately now that initial data has been written - shut it all down.
			HandshakeResult.Accepted handshakeAccepted = handshakeAcceptedReference.get();

			if (handshakeAccepted != null) {
				getLifecycleInterceptor().get().willEstablishServerSentEventConnection(request, resourceMethod);

				// If there is a client initializer, invoke it immediately prior to finalizing the SSE connection
				Consumer<ServerSentEventUnicaster> clientInitializer = handshakeAccepted.getClientInitializer().orElse(null);

				clientSocketChannelRegistration = registerClientSocketChannel(clientSocketChannel, request, handshakeAccepted)
						.orElseThrow(() -> new IllegalStateException("SSE handshake accepted but connection could not be registered"));

				getLifecycleInterceptor().get().didEstablishServerSentEventConnection(request, resourceMethod);

				BlockingQueue<WriteQueueElement> writeQueue =
						clientSocketChannelRegistration.serverSentEventConnection().getWriteQueue();

				while (true) {
					WriteQueueElement writeQueueElement;

					try {
						// Wait for an event/comment; if idle for heartbeatInterval, emit a heartbeat comment.
						writeQueueElement = writeQueue.poll(getHeartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						// Interrupted during shutdown - exit cleanly
						Thread.currentThread().interrupt();
						break;
					}

					// Idle heartbeat
					if (writeQueueElement == null)
						writeQueueElement = WriteQueueElement.withComment("");

					ServerSentEvent serverSentEvent = writeQueueElement.getServerSentEvent().orElse(null);
					String comment = writeQueueElement.getComment().orElse(null);

					if (serverSentEvent == SERVER_SENT_EVENT_POISON_PILL) {
						// Encountered poison pill, exit...
						break;
					}

					String payload;

					if (serverSentEvent != null) {
						// It's a normal server-sent event
						payload = formatServerSentEventForResponse(serverSentEvent);
					} else if (comment != null) {
						// It's a comment (includes heartbeats)
						payload = formatCommentForResponse(comment);
					} else {
						throw new IllegalStateException("Not sure what to do; no Server-Sent Event or comment available");
					}

					ByteBuffer byteBuffer = ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8));

					if (comment != null) {
						try {
							getLifecycleInterceptor().get().willWriteServerSentEventComment(request, clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), comment);
						} catch (Throwable t) {
							safelyLog(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_WILL_WRITE_SERVER_SENT_EVENT_COMMENT_FAILED, format("An exception occurred while invoking %s::willWriteServerSentEventComment", LifecycleInterceptor.class.getSimpleName()))
									.throwable(t)
									.build());
						}
					}

					if (serverSentEvent != null) {
						try {
							getLifecycleInterceptor().get().willWriteServerSentEvent(request, clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), serverSentEvent);
						} catch (Throwable t) {
							safelyLog(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_WILL_WRITE_SERVER_SENT_EVENT_FAILED, format("An exception occurred while invoking %s::willWriteServerSentEvent", LifecycleInterceptor.class.getSimpleName()))
									.throwable(t)
									.build());
						}
					}

					writeStarted = Instant.now();
					Throwable writeThrowable = null;

					try {
						while (byteBuffer.hasRemaining()) {
							clientSocketChannel.write(byteBuffer);
						}
					} catch (Throwable t) {
						writeThrowable = t;
					} finally {
						Instant writeFinished = Instant.now();
						Duration writeDuration = Duration.between(writeStarted, writeFinished);

						if (serverSentEvent != null) {
							if (writeThrowable != null) {
								try {
									getLifecycleInterceptor().get().didFailToWriteServerSentEvent(request, clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), serverSentEvent, writeDuration, writeThrowable);
								} catch (Throwable t) {
									safelyLog(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_WRITE_SERVER_SENT_EVENT_FAILED, format("An exception occurred while invoking %s::didFailToWriteServerSentEvent", LifecycleInterceptor.class.getSimpleName()))
											.throwable(t)
											.build());
								}
							} else {
								try {
									getLifecycleInterceptor().get().didWriteServerSentEvent(request, clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), serverSentEvent, writeDuration);
								} catch (Throwable t) {
									safelyLog(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_WRITE_SERVER_SENT_EVENT_FAILED, format("An exception occurred while invoking %s::didWriteServerSentEvent", LifecycleInterceptor.class.getSimpleName()))
											.throwable(t)
											.build());
								}
							}
						} else if (comment != null) {
							if (writeThrowable != null) {
								try {
									getLifecycleInterceptor().get().didFailToWriteServerSentEventComment(request, clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), comment, writeDuration, writeThrowable);
								} catch (Throwable t) {
									safelyLog(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_WRITE_SERVER_SENT_EVENT_COMMENT_FAILED, format("An exception occurred while invoking %s::didFailToWriteServerSentEventComment", LifecycleInterceptor.class.getSimpleName()))
											.throwable(t)
											.build());
								}
							} else {
								try {
									getLifecycleInterceptor().get().didWriteServerSentEventComment(request, clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), comment, writeDuration);
								} catch (Throwable t) {
									safelyLog(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_WRITE_SERVER_SENT_EVENT_COMMENT_FAILED, format("An exception occurred while invoking %s::didWriteServerSentEventComment", LifecycleInterceptor.class.getSimpleName()))
											.throwable(t)
											.build());
								}
							}
						}

						if (writeThrowable != null)
							throw writeThrowable;
					}
				}
			}
		} catch (Throwable t) {
			throwable = t;

			// If we attempted to establish (willEstablish fired), but registration is null, it means we failed before didEstablish could fire.
			if (handshakeAcceptedReference.get() != null && clientSocketChannelRegistration == null) {
				try {
					getLifecycleInterceptor().get().didFailToEstablishServerSentEventConnection(request, resourceMethod, t);
				} catch (Throwable t1) {
					safelyLog(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_ESTABLISH_SERVER_SENT_EVENT_CONNECTION_FAILED, format("An exception occurred while invoking %s::didFailToEstablishServerSentEventConnection", LifecycleInterceptor.class.getSimpleName()))
							.throwable(t1)
							.build());
				}
			}

			if (t instanceof InterruptedException)
				Thread.currentThread().interrupt();
		} finally {
			// First, close the channel itself...
			if (clientSocketChannel != null) {
				try {
					clientSocketChannel.close();
				} catch (Exception exception) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR,
									"Unable to close Server-Sent Event connection socket channel")
							.throwable(exception)
							.build());
				}
			}

			// ...then unregister from broadcaster (prevents race with broadcasts)
			if (clientSocketChannelRegistration != null) {
				// We know resourceMethod is non-null if clientSocketChannelRegistration is non-null,
				// because registration requires a valid resourceMethod. But let's be defensive.
				ResourceMethod registeredResourceMethod = clientSocketChannelRegistration
						.serverSentEventConnection()
						.getResourceMethod();

				try {
					getLifecycleInterceptor().get().willTerminateServerSentEventConnection(request, registeredResourceMethod, throwable);
				} catch (Throwable t) {
					safelyLog(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_WILL_TERMINATE_SERVER_SENT_EVENT_CONNECTION_FAILED, format("An exception occurred while invoking %s::willTerminateServerSentEventConnection", LifecycleInterceptor.class.getSimpleName()))
							.throwable(t)
							.build());
				}

				try {
					clientSocketChannelRegistration.broadcaster().unregisterServerSentEventConnection(clientSocketChannelRegistration.serverSentEventConnection(), false);
				} catch (Exception exception) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to de-register Server-Sent Event connection")
							.throwable(exception)
							.build());
				}

				// Cleanup empty broadcaster
				maybeCleanupBroadcaster(clientSocketChannelRegistration.broadcaster());

				Instant connectionFinished = Instant.now();
				Duration connectionDuration = Duration.between(
						clientSocketChannelRegistration.serverSentEventConnection().getEstablishedAt(),
						connectionFinished);

				try {
					getLifecycleInterceptor().get().didTerminateServerSentEventConnection(request, registeredResourceMethod, connectionDuration, throwable);
				} catch (Throwable t) {
					safelyLog(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_TERMINATE_SERVER_SENT_EVENT_CONNECTION_FAILED, format("An exception occurred while invoking %s::didTerminateServerSentEventConnection", LifecycleInterceptor.class.getSimpleName()))
							.throwable(t)
							.build());
				}
			}
		}
	}

	@Nonnull
	protected byte[] createHandshakeHttpResponse(@Nonnull RequestResult requestResult) throws IOException {
		requireNonNull(requestResult);

		MarshaledResponse marshaledResponse = requestResult.getMarshaledResponse();
		HandshakeResult handshakeResult = requestResult.getHandshakeResult().orElse(null);

		// Shared buffer for building the header section
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);
				 OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
				 PrintWriter printWriter = new PrintWriter(outputStreamWriter, false)) {

			if (handshakeResult != null && handshakeResult instanceof HandshakeResult.Accepted) {
				final Set<String> ILLEGAL_LOWERCASE_HEADER_NAMES = Set.of("content-length");

				// HTTP status line
				printWriter.print("HTTP/1.1 200 OK\r\n");

				// Write headers, ignoring illegal ones
				for (Entry<String, Set<String>> entry : marshaledResponse.getHeaders().entrySet()) {
					String headerName = entry.getKey();

					if (headerName == null)
						continue;

					if (ILLEGAL_LOWERCASE_HEADER_NAMES.contains(headerName.toLowerCase(Locale.ENGLISH)))
						throw new IllegalArgumentException(format("You may not specify the '%s' header for %s.%s responses",
								headerName, HandshakeResult.class.getSimpleName(), HandshakeResult.Accepted.class.getSimpleName()));

					Set<String> values = entry.getValue();

					if (values != null)
						for (String value : values)
							printWriter.printf("%s: %s\r\n", headerName, value);
				}

				// Emit cookies (one header per cookie)
				for (ResponseCookie cookie : marshaledResponse.getCookies())
					printWriter.printf("Set-Cookie: %s\r\n", cookie.toSetCookieHeaderRepresentation());

				// Terminate header section
				printWriter.print("\r\n");
				printWriter.flush();

				// No body for SSE handshakes
				return outputStream.toByteArray();
			} else if (handshakeResult == null || handshakeResult instanceof HandshakeResult.Rejected) {
				// Status line
				int statusCode = marshaledResponse.getStatusCode();
				String reasonPhrase = StatusCode.fromStatusCode(statusCode).map(StatusCode::getReasonPhrase).orElse("");

				if (reasonPhrase.length() > 0)
					printWriter.printf("HTTP/1.1 %d %s\r\n", statusCode, reasonPhrase);
				else
					printWriter.printf("HTTP/1.1 %d\r\n", statusCode);

				// Write headers
				boolean hasContentLength = false;
				boolean hasTransferEncoding = false;

				for (Entry<String, Set<String>> entry : marshaledResponse.getHeaders().entrySet()) {
					String headerName = entry.getKey();

					if (headerName == null)
						continue;

					String lowercaseHeaderName = headerName.toLowerCase(Locale.ENGLISH);

					if (lowercaseHeaderName.equals("content-length"))
						hasContentLength = true;

					if (lowercaseHeaderName.equals("transfer-encoding"))
						hasTransferEncoding = true;

					Set<String> headerValues = entry.getValue();

					if (headerValues != null)
						for (String headerValue : headerValues)
							printWriter.printf("%s: %s\r\n", headerName, headerValue);
				}

				// Emit cookies (one header per cookie)
				for (ResponseCookie cookie : marshaledResponse.getCookies())
					printWriter.printf("Set-Cookie: %s\r\n", cookie.toSetCookieHeaderRepresentation());

				byte[] body = marshaledResponse.getBody().orElse(null);
				int bodyLength = (body == null ? 0 : body.length);

				// Add Content-Length if body is present and user didn’t set it
				if (bodyLength > 0 && !hasContentLength && !hasTransferEncoding)
					printWriter.printf("Content-Length: %d\r\n", bodyLength);

				// Default Connection: close (rejected handshakes do not remain open)
				printWriter.print("Connection: close\r\n");

				// End headers
				printWriter.print("\r\n");
				printWriter.flush();

				// Write headers + body
				if (body != null && body.length > 0)
					outputStream.write(body);

				return outputStream.toByteArray();
			} else {
				throw new IllegalStateException(String.format("Unsupported %s: %s", HandshakeResult.class.getSimpleName(), handshakeResult));
			}
		}
	}

	@Nonnull
	private static byte[] createFailsafeHandshakeHttp500Response() {
		String body = "HTTP 500: Internal Server Error";
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

		String response =
				"HTTP/1.1 500 Internal Server Error\r\n" +
						"Content-Type: text/plain; charset=UTF-8\r\n" +
						"Content-Length: " + bodyBytes.length + "\r\n" +
						"Connection: close\r\n" +
						"\r\n";

		byte[] headerBytes = response.getBytes(StandardCharsets.UTF_8);

		// Combine headers + body into a single byte array
		byte[] combined = new byte[headerBytes.length + bodyBytes.length];
		System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
		System.arraycopy(bodyBytes, 0, combined, headerBytes.length, bodyBytes.length);
		return combined;
	}

	@Nonnull
	protected String formatCommentForResponse(@Nonnull String comment) {
		requireNonNull(comment);

		String[] lines = comment.split("\\R", -1);

		if (lines.length == 0)
			return ":\n\n";

		StringBuilder stringBuilder = new StringBuilder();

		for (String line : lines) {
			stringBuilder.append(':');

			if (!line.isEmpty())
				stringBuilder.append(' ').append(line);

			stringBuilder.append('\n');
		}

		stringBuilder.append('\n');

		return stringBuilder.toString();
	}

	@Nonnull
	protected String formatServerSentEventForResponse(@Nonnull ServerSentEvent serverSentEvent) {
		requireNonNull(serverSentEvent);

		String event = serverSentEvent.getEvent().orElse(null);

		String data = serverSentEvent.getData().orElse(null);
		List<String> dataLines = data == null
				? List.of()
				: Arrays.stream(data.split("\\R", -1)) // preserve trailing empties
				.map(line -> format("data: %s", line))
				.collect(Collectors.toList());

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

		// Per SSE spec, an event with no fields is effectively a comment/keep-alive.
		// This can occur if a ServerSentEvent is constructed with no event, data, id, or retry.
		// We emit a comment line to maintain the SSE stream format.
		if (lines.size() == 0)
			return ":\n\n";

		return format("%s\n\n", lines.stream().collect(Collectors.joining("\n")));
	}

	@ThreadSafe
	protected static final class ServerSentEventConnection {
		@Nonnull
		private final Request request;
		@Nonnull
		private final ResourceMethod resourceMethod;
		@Nullable
		private final Object clientContext;
		@Nonnull
		private final BlockingQueue<WriteQueueElement> writeQueue;
		@Nonnull
		private final Instant establishedAt;
		@Nonnull
		private final SocketChannel socketChannel;
		@Nonnull
		private final AtomicBoolean closing;

		@ThreadSafe
		static final class WriteQueueElement {
			@Nullable
			private final ServerSentEvent serverSentEvent;
			@Nullable
			private final String comment;

			@Nonnull
			public static WriteQueueElement withServerSentEvent(@Nonnull ServerSentEvent serverSentEvent) {
				requireNonNull(serverSentEvent);
				return new WriteQueueElement(serverSentEvent, null);
			}

			@Nonnull
			public static WriteQueueElement withComment(@Nonnull String comment) {
				requireNonNull(comment);
				return new WriteQueueElement(null, comment);
			}

			private WriteQueueElement(@Nullable ServerSentEvent serverSentEvent,
																@Nullable String comment) {
				if (serverSentEvent == null && comment == null)
					throw new IllegalStateException("Must provide either a server-sent event or a comment");

				if (serverSentEvent != null && comment != null)
					throw new IllegalStateException("Must provide either a server-sent event or a comment; not both");

				this.serverSentEvent = serverSentEvent;
				this.comment = comment;
			}

			@Nonnull
			public Optional<ServerSentEvent> getServerSentEvent() {
				return Optional.ofNullable(this.serverSentEvent);
			}

			@Nonnull
			public Optional<String> getComment() {
				return Optional.ofNullable(this.comment);
			}
		}

		public ServerSentEventConnection(@Nonnull Request request,
																		 @Nonnull ResourceMethod resourceMethod,
																		 @Nullable Object clientContext,
																		 @Nonnull Integer connectionQueueCapacity,
																		 @Nonnull SocketChannel socketChannel) {
			requireNonNull(request);
			requireNonNull(resourceMethod);
			requireNonNull(connectionQueueCapacity);
			requireNonNull(socketChannel);

			this.request = request;
			this.resourceMethod = resourceMethod;
			this.clientContext = clientContext;
			this.writeQueue = new ArrayBlockingQueue<>(connectionQueueCapacity);
			this.establishedAt = Instant.now();
			this.socketChannel = socketChannel;
			this.closing = new AtomicBoolean(false);
		}

		@Nonnull
		public Request getRequest() {
			return this.request;
		}

		@Nonnull
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@Nonnull
		public Optional<Object> getClientContext() {
			return Optional.ofNullable(this.clientContext);
		}

		@Nonnull
		public BlockingQueue<WriteQueueElement> getWriteQueue() {
			return this.writeQueue;
		}

		@Nonnull
		public Instant getEstablishedAt() {
			return this.establishedAt;
		}

		@Nonnull
		public SocketChannel getSocketChannel() {
			return this.socketChannel;
		}

		@Nonnull
		public AtomicBoolean getClosing() {
			return this.closing;
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
																																									@Nonnull Request request,
																																									@Nonnull HandshakeResult.Accepted handshakeAccepted) {
		requireNonNull(clientSocketChannel);
		requireNonNull(request);
		requireNonNull(handshakeAccepted);

		if (isStopping() || !isStarted())
			return Optional.empty();

		ResourcePath resourcePath = request.getResourcePath();

		if (!matchingResourcePath(resourcePath).isPresent())
			return Optional.empty();

		ResourceMethod resourceMethod = resourceMethodForResourcePath(resourcePath).orElse(null);

		if (resourceMethod == null)
			return Optional.empty();

		// Create the connection (write queue owned per connection)
		ServerSentEventConnection serverSentEventConnection = new ServerSentEventConnection(
				request,
				resourceMethod,
				handshakeAccepted.getClientContext().orElse(null),
				getConnectionQueueCapacity(),
				clientSocketChannel
		);

		// If a client initializer exists, hand it the unicaster to support Last-Event-ID "catch up" scenarios
		ServerSentEventUnicaster serverSentEventUnicaster =
				new DefaultServerSentEventUnicaster(resourcePath, serverSentEventConnection.getWriteQueue());

		handshakeAccepted.getClientInitializer().ifPresent((clientInitializer) -> {
			clientInitializer.accept(serverSentEventUnicaster);
		});

		// Now that the client initializer has run (if present), enqueue a single "heartbeat" comment to immediately "flush"/verify the connection if configured to do so
		if (getVerifyConnectionOnceEstablished())
			serverSentEventConnection.getWriteQueue().offer(WriteQueueElement.withComment(""));

		// IMPORTANT: atomically (per resourcePath) get-or-create broadcaster and register the connection,
		// so cleanup cannot remove the broadcaster between acquire and register (split-brain prevention).
		DefaultServerSentEventBroadcaster broadcaster =
				registerConnectionWithBroadcaster(resourcePath, resourceMethod, serverSentEventConnection);

		return Optional.of(new ClientSocketChannelRegistration(serverSentEventConnection, broadcaster));
	}

	@ThreadSafe
	protected static class DefaultServerSentEventUnicaster implements ServerSentEventUnicaster {
		@Nonnull
		private final ResourcePath resourcePath;
		@Nonnull
		private final BlockingQueue<WriteQueueElement> writeQueue;

		public DefaultServerSentEventUnicaster(@Nonnull ResourcePath resourcePath,
																					 @Nonnull BlockingQueue<WriteQueueElement> writeQueue) {
			requireNonNull(resourcePath);
			requireNonNull(writeQueue);

			this.resourcePath = resourcePath;
			this.writeQueue = writeQueue;
		}

		@Override
		public void unicastEvent(@Nonnull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);

			if (!getWriteQueue().offer(WriteQueueElement.withServerSentEvent(serverSentEvent)))
				throw new IllegalStateException("SSE client initializer exceeded connection write-queue capacity");
		}

		@Override
		public void unicastComment(@Nonnull String comment) {
			requireNonNull(comment);
			
			if (!getWriteQueue().offer(WriteQueueElement.withComment(comment)))
				throw new IllegalStateException("SSE client initializer exceeded connection write-queue capacity");
		}

		@Nonnull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@Nonnull
		protected BlockingQueue<WriteQueueElement> getWriteQueue() {
			return this.writeQueue;
		}
	}

	protected void maybeCleanupBroadcaster(@Nonnull DefaultServerSentEventBroadcaster broadcaster) {
		requireNonNull(broadcaster);

		try {
			getBroadcastersByResourcePath().computeIfPresent(broadcaster.getResourcePath(), (path, existingBroadcaster) -> {
						// Only remove if it's the same instance AND empty
						if (existingBroadcaster == broadcaster && existingBroadcaster.getServerSentEventConnections().isEmpty())
							return null;  // Remove the mapping

						return existingBroadcaster; // Keep the mapping
					}
			);
		} catch (Exception e) {
			safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Failed to clean up empty broadcaster")
					.throwable(e)
					.build());
		}
	}

	@Nonnull
	protected Request parseRequest(@Nonnull String rawRequest) {
		requireNonNull(rawRequest);

		rawRequest = trimAggressivelyToNull(rawRequest);

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

		Request.RawBuilder requestBuilder = null;
		List<String> headerLines = new ArrayList<>();

		for (String line : rawRequest.lines().toList()) {
			line = trimAggressivelyToNull(line);

			if (line == null)
				continue;

			if (requestBuilder == null) {
				// This is the first line.
				// Example: GET /testing?one=two HTTP/1.1
				String[] components = line.trim().split("\\s+");

				if (components.length != 3)
					throw new IllegalStateException(format("Malformed Server-Sent Event request line '%s'. Expected a format like 'GET /example?one=two HTTP/1.1'", line));

				HttpMethod httpMethod;
				String rawHttpMethod = components[0];
				String rawUrl = components[1];

				if (rawHttpMethod == null)
					throw new IllegalRequestException(format("Malformed Server-Sent Event request line '%s'. Unable to parse HTTP method. Expected a format like 'GET /example?one=two HTTP/1.1'", line));

				try {
					httpMethod = HttpMethod.valueOf(rawHttpMethod);
				} catch (IllegalArgumentException e) {
					throw new IllegalRequestException(format("Malformed Server-Sent Event request line '%s'. Unable to parse HTTP method. Expected a format like 'GET /example?one=two HTTP/1.1'", line), e);
				}

				if (rawUrl == null)
					throw new IllegalRequestException(format("Malformed Server-Sent Event request line '%s'. Unable to parse URL. Expected a format like 'GET /example?one=two HTTP/1.1'", line));

				requestBuilder = Request.withRawUrl(httpMethod, rawUrl);
			} else {
				if (line.isEmpty())
					continue;

				// This is a header line.
				// Example: Accept-Encoding: gzip, deflate, br, zstd
				int indexOfFirstColon = line.indexOf(":");

				if (indexOfFirstColon == -1)
					throw new IllegalStateException(format("Malformed Server-Sent Event request line '%s'. Expected a format like 'Header-Name: Value", line));

				headerLines.add(line);
			}
		}

		Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(headerLines);

		return requestBuilder.idGenerator(getIdGenerator()).headers(headers).build();
	}

	@Nonnull
	protected String readRequest(@Nonnull SocketChannel clientSocketChannel) throws IOException {
		requireNonNull(clientSocketChannel);

		// Because reads from the socket channel are blocking, there is no way to specify a timeout for it.
		// We work around this by performing the read in a virtual thread, and use the timeout functionality
		// built in to Futures to interrupt the thread if it doesn't finish in time.
		Future<String> readFuture = null;

		try {
			ExecutorService requestReaderExecutorService = getRequestReaderExecutorService().orElse(null);

			if (requestReaderExecutorService == null || requestReaderExecutorService.isShutdown())
				throw new IOException("Server is shutting down");

			readFuture = requestReaderExecutorService.submit(() -> {
				ByteBuffer buffer = ByteBuffer.allocate(getRequestReadBufferSizeInBytes());
				StringBuilder requestBuilder = new StringBuilder();
				boolean headersComplete = false;
				int totalBytesRead = 0;

				while (!headersComplete) {
					int bytesRead = clientSocketChannel.read(buffer);

					// If the thread was interrupted while blocked in read(...),
					// the read call should throw InterruptedIOException or similar.
					if (Thread.interrupted())
						throw new InterruptedIOException("Thread interrupted while reading request data");

					// End of stream (connection closed by client)
					if (bytesRead == -1)
						throw new IOException("Client closed the connection before request was complete");

					// Flip the buffer to read mode
					buffer.flip();

					// Decode the buffer content to a string and append to the request
					byte[] bytes = new byte[buffer.remaining()];
					buffer.get(bytes);

					totalBytesRead += bytes.length;

					// Check size limit
					// To test:
					// echo -ne 'GET /example HTTP/1.1\r\nHost: example.com FILLER_UNTIL_WE_ARE_TOO_BIG\r\n\r\n' | netcat -v localhost 8081
					if (totalBytesRead > getMaximumRequestSizeInBytes()) {
						String rawRequest = requestBuilder.toString();

						// Given our partial raw request, try to parse it into a request...
						Request tooLargeRequest = parseTooLargeRequestForRawRequest(rawRequest).orElse(null);

						// ...if unable to parse into a request (as in, we can't even make it through the first line), bail
						if (tooLargeRequest == null)
							throw new IOException(format("Request is too large (exceeded %d bytes) but we do not have enough data available to know its path", getMaximumRequestSizeInBytes()));

						throw new RequestTooLargeIOException(format("Request too large (exceeded %d bytes)", getMaximumRequestSizeInBytes()), tooLargeRequest);
					}

					requestBuilder.append(new String(bytes, StandardCharsets.UTF_8));

					// Check if the headers are complete (CRLFCRLF or LFLF)
					if (requestBuilder.indexOf("\r\n\r\n") != -1 || requestBuilder.indexOf("\n\n") != -1)
						headersComplete = true;

					// Clear the buffer for the next read
					buffer.clear();
				}

				return requestBuilder.toString();
			});

			// Wait up to the specified timeout for reading to complete
			return readFuture.get(getRequestTimeout().getSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			// Time's up; cancel the task so the blocking read is interrupted
			if (readFuture != null)
				readFuture.cancel(true);

			throw new SocketTimeoutException(format("Reading request took longer than %d seconds", getRequestTimeout().getSeconds()));
		} catch (InterruptedException e) {
			if (readFuture != null)
				readFuture.cancel(true);

			// Current thread interrupted while waiting
			Thread.currentThread().interrupt(); // restore interrupt status
			throw new IOException("Interrupted while awaiting request data", e);
		} catch (ExecutionException e) {
			if (readFuture != null)
				readFuture.cancel(true);

			// The task itself threw an exception
			if (e.getCause() instanceof IOException)
				throw (IOException) e.getCause();

			throw new IOException("Unexpected exception while reading request", e.getCause());
		}
	}

	/**
	 * Given partial raw request data (once we hit max size threshold, we stop collecting it), parse out what we have as
	 * best we can into a request that is marked "too large".
	 * <p>
	 * If there isn't sufficient data to parse into a request (or if the data is malformed), then return the empty value.
	 */
	@Nonnull
	protected Optional<Request> parseTooLargeRequestForRawRequest(@Nonnull String rawRequest) {
		requireNonNull(rawRequest);

		// Supports both relative and absolute paths.
		// e.g. "GET /index.html HTTP/1.1\r\n" would return "/index.html".
		// e.g. "GET https://www.soklet.com/index.html HTTP/1.1\r\n" would return "/index.html".
		String firstLine = null;

		int crLfIndex = rawRequest.indexOf("\r\n");
		if (crLfIndex == -1)
			crLfIndex = rawRequest.indexOf('\n');

		if (crLfIndex != -1)
			firstLine = rawRequest.substring(0, crLfIndex).trim();

		// We don't even have a complete first line of the request
		if (firstLine == null || firstLine.length() == 0)
			return Optional.empty();

		String[] parts = firstLine.trim().split("\\s+");

		// First line of the request is malformed
		if (parts.length < 2)
			return Optional.empty();

		String rawHttpMethod = parts[0];

		if (rawHttpMethod != null)
			rawHttpMethod = rawHttpMethod.trim().toUpperCase(Locale.ENGLISH);

		HttpMethod httpMethod;

		try {
			httpMethod = HttpMethod.valueOf(rawHttpMethod);
		} catch (IllegalArgumentException e) {
			// Malformed HTTP method specified
			return Optional.empty();
		}

		String rawUri = parts[1];

		// Validate URI
		if (rawUri != null) {
			URI uri;

			try {
				uri = new URI(rawUri.trim());
			} catch (Exception e) {
				// Malformed URI specified
				return Optional.empty();
			}

			// Normalize absolute URIs to relative form
			String rawPath = uri.getRawPath() == null ? "/" : uri.getRawPath();
			String rawQuery = uri.getRawQuery();
			rawUri = rawQuery == null ? rawPath : rawPath + "?" + rawQuery;
		}

		// TODO: eventually would be nice to parse headers as best we can.  For now, we just parse the first request line
		return Optional.of(Request.withRawUrl(httpMethod, rawUri)
				.idGenerator(getIdGenerator())
				.contentTooLarge(true)
				.build());
	}

	@NotThreadSafe
	protected static class RequestTooLargeIOException extends IOException {
		@Nonnull
		private final Request tooLargeRequest;

		public RequestTooLargeIOException(@Nullable String message,
																			@Nonnull Request tooLargeRequest) {
			super(message);
			this.tooLargeRequest = requireNonNull(tooLargeRequest);
		}

		@Nonnull
		public Request getTooLargeRequest() {
			return this.tooLargeRequest;
		}
	}

	@Override
	public void stop() {
		getLock().lock();

		boolean interrupted = false;

		try {
			if (!isStarted())
				return;

			this.stopping = true;
			getStopPoisonPill().set(true);

			// Close server socket to unblock accept()
			if (this.serverSocketChannel != null) {
				try {
					this.serverSocketChannel.close();
				} catch (Exception e) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to close Server-Sent Event SocketChannel").throwable(e).build());
				}
			}

			// Close client connections - sends poison pills to all registered connections
			for (DefaultServerSentEventBroadcaster broadcaster : getBroadcastersByResourcePath().values()) {
				try {
					broadcaster.unregisterAllServerSentEventConnections(true);
				} catch (Exception e) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to shut down open Server-Sent Event connections")
							.throwable(e)
							.build());
				}
			}

			// Clear global connections map for sanity (though it should be empty by this point)
			getGlobalConnections().clear();

			// Use shutdownNow() immediately for request handlers.
			// Poison pills handle normal cases; interrupt handles edge cases where
			// a connection registered after we iterated above.
			if (this.requestHandlerExecutorService != null)
				this.requestHandlerExecutorService.shutdownNow();

			if (this.requestReaderExecutorService != null)
				this.requestReaderExecutorService.shutdownNow();

			// Shared wall-clock deadline
			final long deadlineNanos = System.nanoTime() + getShutdownTimeout().toNanos();

			// Await the accept-loop thread and all executors **in parallel**
			long grace = Math.min(250L, remainingMillis(deadlineNanos));

			List<CompletableFuture<Boolean>> waits = new ArrayList<>(3);
			waits.add(joinAsync(this.eventLoopThread, grace));

			if (this.requestHandlerExecutorService != null)
				waits.add(awaitTerminationAsync(this.requestHandlerExecutorService, grace));

			if (this.requestReaderExecutorService != null)
				waits.add(awaitTerminationAsync(this.requestReaderExecutorService, grace));

			// Wait for all, but no longer than the single budget
			try {
				CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).get(grace, TimeUnit.MILLISECONDS);
			} catch (TimeoutException te) {
				// Budget exhausted; escalate below
			} catch (InterruptedException e) {
				interrupted = true;
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Exception while awaiting SSE shutdown").throwable(e).build());
			}

			// Escalate for any stragglers using remaining time
			hardenJoin(this.eventLoopThread, remainingMillis(deadlineNanos));

			// Pools already had shutdownNow() called; just await termination
			if (this.requestHandlerExecutorService != null)
				awaitPoolTermination(this.requestHandlerExecutorService, remainingMillis(deadlineNanos));

			if (this.requestReaderExecutorService != null)
				awaitPoolTermination(this.requestReaderExecutorService, remainingMillis(deadlineNanos));
		} finally {
			try {
				this.started = false;
				this.stopping = false; // allow future restarts
				this.eventLoopThread = null;
				this.requestHandlerExecutorService = null;
				this.requestReaderExecutorService = null;
				this.getBroadcastersByResourcePath().clear();
				this.getResourcePathDeclarationsByResourcePathCache().clear();
				getStopPoisonPill().set(false);

				if (interrupted)
					Thread.currentThread().interrupt();
			} finally {
				getLock().unlock();
			}
		}
	}

	private void awaitPoolTermination(@Nullable ExecutorService executorService,
																		@Nonnull Long millis) {
		requireNonNull(millis);

		if (executorService == null || executorService.isTerminated())
			return;

		try {
			executorService.awaitTermination(Math.max(100L, millis), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Nonnull
	protected CompletableFuture<Boolean> awaitTerminationAsync(@Nullable ExecutorService executorService,
																														 @Nonnull Long millis) {
		requireNonNull(millis);

		if (executorService == null || millis <= 0)
			return CompletableFuture.completedFuture(false);

		return CompletableFuture.supplyAsync(() -> {
			try {
				return executorService.awaitTermination(millis, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		});
	}

	@Nonnull
	protected CompletableFuture<Boolean> joinAsync(@Nullable Thread thread,
																								 @Nonnull Long millis) {
		requireNonNull(millis);

		if (thread == null || millis <= 0)
			return CompletableFuture.completedFuture(true);

		return CompletableFuture.supplyAsync(() -> {
			try {
				thread.join(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}

			return !thread.isAlive();
		});
	}

	protected void hardenJoin(@Nullable Thread thread,
														@Nonnull Long millis) {
		requireNonNull(millis);

		if (thread == null || !thread.isAlive())
			return;

		thread.interrupt();

		try {
			thread.join(Math.max(100L, millis));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Nonnull
	protected Long remainingMillis(@Nonnull Long deadlineNanos) {
		requireNonNull(deadlineNanos);

		long rem = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
		return Math.max(0L, rem);
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
	public Optional<? extends ServerSentEventBroadcaster> acquireBroadcaster(@Nullable ResourcePath resourcePath) {
		if (resourcePath == null)
			return Optional.empty();

		if (isStopping() || !isStarted())
			return Optional.empty();

		ResourceMethod resourceMethod = resourceMethodForResourcePath(resourcePath).orElse(null);

		if (resourceMethod == null)
			return Optional.empty();

		return acquireBroadcasterInternal(resourcePath, resourceMethod);
	}

	@Nonnull
	protected Optional<ResourceMethod> resourceMethodForResourcePath(@Nonnull ResourcePath resourcePath) {
		requireNonNull(resourcePath);

		ResourcePathDeclaration resourcePathDeclaration = matchingResourcePath(resourcePath).orElse(null);

		if (resourcePathDeclaration == null)
			return Optional.empty();

		ResourceMethod resourceMethod = getResourceMethodsByResourcePathDeclaration().get(resourcePathDeclaration);

		// Internal sanity guard
		if (resourceMethod == null)
			throw new IllegalStateException(format("Internal error: unable to find %s instance that matches %s", ResourceMethod.class, resourcePathDeclaration));

		return Optional.of(resourceMethod);
	}

	@Nonnull
	private DefaultServerSentEventBroadcaster newBroadcaster(@Nonnull ResourceMethod resourceMethod,
																													 @Nonnull ResourcePath resourcePath) {
		requireNonNull(resourceMethod);
		requireNonNull(resourcePath);

		BackpressureHandler handler = (owner, connection, cause) ->
				closeConnectionDueToBackpressure(owner, connection, cause);

		return new DefaultServerSentEventBroadcaster(
				resourceMethod,
				resourcePath,
				handler,
				(serverSentEventConnection) -> {
					// When the broadcaster unregisters a connection it manages, remove it from the global set as well
					getGlobalConnections().remove(serverSentEventConnection);
				},
				this::safelyLog
		);
	}

	@Nonnull
	private DefaultServerSentEventBroadcaster registerConnectionWithBroadcaster(@Nonnull ResourcePath resourcePath,
																																							@Nonnull ResourceMethod resourceMethod,
																																							@Nonnull ServerSentEventConnection connection) {
		requireNonNull(resourcePath);
		requireNonNull(resourceMethod);
		requireNonNull(connection);

		return getBroadcastersByResourcePath().compute(resourcePath, (rp, existing) -> {
			DefaultServerSentEventBroadcaster broadcaster =
					(existing != null) ? existing : newBroadcaster(resourceMethod, rp);

			boolean registered = broadcaster.registerServerSentEventConnection(connection);

			if (!registered)
				throw new IllegalStateException(format("Unable to register Server-Sent Event connection for %s", resourcePath));

			// Track in global LRU limit. If this causes eviction, the eviction callback will unregister/poison the evicted connection.
			getGlobalConnections().put(connection, broadcaster);

			return broadcaster;
		});
	}

	@Nonnull
	protected Optional<DefaultServerSentEventBroadcaster> acquireBroadcasterInternal(@Nonnull ResourcePath resourcePath,
																																									 @Nonnull ResourceMethod resourceMethod) {
		requireNonNull(resourcePath);
		requireNonNull(resourceMethod);

		DefaultServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath()
				.computeIfAbsent(resourcePath, (rp) -> newBroadcaster(resourceMethod, rp));

		return Optional.of(broadcaster);
	}

	protected void closeConnectionDueToBackpressure(@Nonnull DefaultServerSentEventBroadcaster owner,
																									@Nonnull ServerSentEventConnection connection,
																									@Nonnull String cause) {
		requireNonNull(owner);
		requireNonNull(connection);
		requireNonNull(cause);

		// Ensure only once
		if (!connection.getClosing().compareAndSet(false, true))
			return;

		// TODO: future releases should propagate out typed information to LifecycleInterceptor::willTerminateServerSentEventConnection indicating why the connection was terminated (in this case, backpressure) - not just an exception
		// String message = format("Closing Server-Sent Event connection due to backpressure (write queue at capacity) while enqueuing %s. {resourcePath=%s, queueSize=%d, remainingCapacity=%d}",
		//					cause, owner.getResourcePath(), writeQueue.size(), writeQueue.remainingCapacity());

		// Unregister from broadcaster (avoid enqueueing further)
		try {
			owner.unregisterServerSentEventConnection(connection, false);
		} catch (Throwable ignored) { /* best-effort */ }

		// Best effort to wake the consumer loop
		try {
			BlockingQueue<WriteQueueElement> writeQueue = connection.getWriteQueue();
			writeQueue.clear();
			writeQueue.offer(WriteQueueElement.withServerSentEvent(SERVER_SENT_EVENT_POISON_PILL));
		} catch (Throwable ignored) { /* best-effort */ }

		// Force-close the channel to break any pending I/O
		try {
			SocketChannel socketChannel = connection.getSocketChannel();

			if (socketChannel != null)
				socketChannel.close();

		} catch (Throwable ignored) { /* channel may already be closed */ }
	}

	@Nonnull
	protected Optional<ResourcePathDeclaration> matchingResourcePath(@Nullable ResourcePath resourcePath) {
		if (resourcePath == null)
			return Optional.empty();

		ResourcePathDeclaration resourcePathDeclaration = getResourcePathDeclarationsByResourcePathCache()
				.computeIfAbsent(resourcePath, rp -> {
					for (ResourcePathDeclaration d : getResourceMethodsByResourcePathDeclaration().keySet())
						if (d.matches(rp))
							return d;

					// Return sentinel instead of null to cache the negative result
					return NO_MATCH_SENTINEL;
				});

		// Convert sentinel back to empty Optional
		if (resourcePathDeclaration == NO_MATCH_SENTINEL)
			return Optional.empty();

		return Optional.of(resourcePathDeclaration);
	}

	protected void safelyLog(@Nonnull LogEvent logEvent) {
		requireNonNull(logEvent);

		try {
			getLifecycleInterceptor().ifPresent(lifecycleInterceptor -> lifecycleInterceptor.didReceiveLogEvent(logEvent));
		} catch (Throwable throwable) {
			// The LifecycleInterceptor implementation errored out, but we can't let that affect us - swallow its exception.
			// Not much else we can do here but dump to stderr
			throwable.printStackTrace(System.err);
		}
	}

	@Nonnull
	protected Integer getPort() {
		return this.port;
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
	protected Duration getShutdownTimeout() {
		return this.shutdownTimeout;
	}

	@Nonnull
	protected Duration getHeartbeatInterval() {
		return this.heartbeatInterval;
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
	public Map<ResourcePathDeclaration, ResourceMethod> getResourceMethodsByResourcePathDeclaration() {
		return this.resourceMethodsByResourcePathDeclaration;
	}

	@Nonnull
	protected ConcurrentHashMap<ResourcePath, DefaultServerSentEventBroadcaster> getBroadcastersByResourcePath() {
		return this.broadcastersByResourcePath;
	}

	@Nonnull
	protected ConcurrentLruMap<ResourcePath, ResourcePathDeclaration> getResourcePathDeclarationsByResourcePathCache() {
		return this.resourcePathDeclarationsByResourcePathCache;
	}

	@Nonnull
	protected Optional<ExecutorService> getRequestHandlerExecutorService() {
		return Optional.ofNullable(this.requestHandlerExecutorService);
	}

	@Nonnull
	protected Optional<ExecutorService> getRequestReaderExecutorService() {
		return Optional.ofNullable(this.requestReaderExecutorService);
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
	protected Supplier<ExecutorService> getRequestReaderExecutorServiceSupplier() {
		return this.requestReaderExecutorServiceSupplier;
	}

	@Nonnull
	protected Integer getConcurrentConnectionLimit() {
		return this.concurrentConnectionLimit;
	}

	@Nonnull
	protected Integer getConnectionQueueCapacity() {
		return this.connectionQueueCapacity;
	}

	@Nonnull
	protected Boolean getVerifyConnectionOnceEstablished() {
		return this.verifyConnectionOnceEstablished;
	}

	@Nonnull
	protected ConcurrentLruMap<ServerSentEventConnection, DefaultServerSentEventBroadcaster> getGlobalConnections() {
		return this.globalConnections;
	}

	@Nonnull
	protected AtomicBoolean getStopPoisonPill() {
		return this.stopPoisonPill;
	}

	@Nonnull
	protected Optional<Thread> getEventLoopThread() {
		return Optional.ofNullable(this.eventLoopThread);
	}

	@Nonnull
	protected Optional<RequestHandler> getRequestHandler() {
		return Optional.ofNullable(this.requestHandler);
	}

	@Nonnull
	protected Optional<LifecycleInterceptor> getLifecycleInterceptor() {
		return Optional.ofNullable(this.lifecycleInterceptor);
	}

	@Nullable
	private IdGenerator<?> getIdGenerator() {
		return this.idGenerator;
	}
}
