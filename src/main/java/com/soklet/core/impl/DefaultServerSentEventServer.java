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
import com.soklet.annotation.ServerSentEventSource;
import com.soklet.core.HttpMethod;
import com.soklet.core.LifecycleInterceptor;
import com.soklet.core.LogEvent;
import com.soklet.core.LogEventType;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.core.RequestResult;
import com.soklet.core.ResourceMethod;
import com.soklet.core.ResourcePath;
import com.soklet.core.ResourcePathDeclaration;
import com.soklet.core.ServerSentEvent;
import com.soklet.core.ServerSentEventBroadcaster;
import com.soklet.core.ServerSentEventServer;
import com.soklet.core.StatusCode;
import com.soklet.core.Utilities;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import com.soklet.internal.util.ConcurrentLruMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
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
	private static final ServerSentEvent SERVER_SENT_EVENT_CONNECTION_VALIDITY_CHECK;
	@Nonnull
	private static final ServerSentEvent SERVER_SENT_EVENT_POISON_PILL;

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024;
		DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES = 1_024;
		DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
		DEFAULT_CONNECTION_QUEUE_CAPACITY = 256;

		// Make a unique "validity check" server-sent event used to wake a socket listener thread by injecting it into the relevant write queue.
		// When this event is taken off of the queue, a validity check is performed on the socket to see if it's still active.
		// If not, socket is torn down and the thread finishes running.
		// The contents don't matter; the object reference is used to determine if it's a validity check.
		SERVER_SENT_EVENT_CONNECTION_VALIDITY_CHECK = ServerSentEvent.withEvent("validity-check").build();

		// Make a unique "poison pill" server-sent event used to stop a socket listener thread by injecting it into the relevant write queue.
		// When this event is taken off of the queue, the socket is torn down and the thread finishes running.
		// The contents don't matter; the object reference is used to determine if it's poison.
		SERVER_SENT_EVENT_POISON_PILL = ServerSentEvent.withEvent("poison").build();
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
	// TODO: we probably want to convert to ConcurrentLruMap
	@Nonnull
	private final ConcurrentHashMap<ResourcePath, DefaultServerSentEventBroadcaster> broadcastersByResourcePath;
	// TODO: we probably want to convert to ConcurrentLruMap
	@Nonnull
	private final ConcurrentHashMap<ResourcePath, ResourcePathDeclaration> resourcePathDeclarationsByResourcePathCache;
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
	private final AtomicBoolean stopPoisonPill;
	@Nullable
	private volatile ExecutorService requestHandlerExecutorService;
	@Nullable
	private volatile ExecutorService requestReaderExecutorService;
	@Nullable
	private volatile ScheduledExecutorService connectionValidityExecutorService;
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
	private Map<ResourcePathDeclaration, ResourceMethod> resourceMethodsByResourcePathDeclaration;
	@Nullable
	private RequestHandler requestHandler;
	@Nullable
	private LifecycleInterceptor lifecycleInterceptor;

	@ThreadSafe
	protected static class DefaultServerSentEventBroadcaster implements ServerSentEventBroadcaster {
		@Nonnull
		private final ResourceMethod resourceMethod;
		@Nonnull
		private final ResourcePath resourcePath;
		@Nonnull
		private final Consumer<ServerSentEventConnection> connectionUnregisteredListener;
		// This must be threadsafe, e.g. via ConcurrentHashMap#newKeySet
		@Nonnull
		private final Set<ServerSentEventConnection> serverSentEventConnections;

		public DefaultServerSentEventBroadcaster(@Nonnull ResourceMethod resourceMethod,
																						 @Nonnull ResourcePath resourcePath,
																						 @Nonnull Consumer<ServerSentEventConnection> connectionUnregisteredListener) {
			requireNonNull(resourceMethod);
			requireNonNull(resourcePath);
			requireNonNull(connectionUnregisteredListener);

			this.resourceMethod = resourceMethod;
			this.resourcePath = resourcePath;
			this.connectionUnregisteredListener = connectionUnregisteredListener;
			// TODO: let clients specify capacity
			this.serverSentEventConnections = ConcurrentHashMap.newKeySet(1_024);
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
		public void broadcast(@Nonnull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);

			// We can broadcast from the current thread because putting elements onto blocking queues is reasonably fast.
			// The blocking queues are consumed by separate per-socket-channel threads
			for (ServerSentEventConnection serverSentEventConnection : getServerSentEventConnections())
				enqueueServerSentEvent(serverSentEventConnection, serverSentEvent);
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

			if (unregistered) {
				getConnectionUnregisteredListener().accept(serverSentEventConnection);

				// If requested, send a poison pill so the socket thread gets terminated
				if (sendPoisonPill)
					enqueueServerSentEvent(serverSentEventConnection, SERVER_SENT_EVENT_POISON_PILL);
			}

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

		@Nonnull
		protected Consumer<ServerSentEventConnection> getConnectionUnregisteredListener() {
			return this.connectionUnregisteredListener;
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
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.requestReadBufferSizeInBytes = builder.requestReadBufferSizeInBytes != null ? builder.requestReadBufferSizeInBytes : DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
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

		// TODO: let clients specify initial capacity
		this.broadcastersByResourcePath = new ConcurrentHashMap<>(1_024);
		this.resourcePathDeclarationsByResourcePathCache = new ConcurrentHashMap<>(1_024);

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
					throwable.printStackTrace();
					loggingThrowable.printStackTrace();
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
					throwable.printStackTrace();
					loggingThrowable.printStackTrace();
				}
			});
		};

		this.concurrentConnectionLimit = builder.concurrentConnectionLimit != null ? builder.concurrentConnectionLimit : 8_192;

		if (this.concurrentConnectionLimit < 1)
			throw new IllegalArgumentException("The value for concurrentConnectionLimit must be > 0");

		// Initialize the global LRU map with the specified limit. Assume ConcurrentLRUMap supports a removal listener.
		this.globalConnections = new ConcurrentLruMap<>(this.concurrentConnectionLimit, (evictedConnection, broadcaster) -> {
			// This callback is triggered when a connection is evicted from the global LRU map.
			// Unregister the evicted connection from the broadcaster and send poison pill to close it.
			broadcaster.unregisterServerSentEventConnection(evictedConnection, true);
		});
	}

	@Override
	public void initialize(@Nonnull SokletConfiguration sokletConfiguration,
												 @Nonnull RequestHandler requestHandler) {
		requireNonNull(sokletConfiguration);
		requireNonNull(requestHandler);

		this.lifecycleInterceptor = sokletConfiguration.getLifecycleInterceptor();
		this.requestHandler = requestHandler;

		// Pick out all the @ServerSentEventSource resource methods and store off keyed on resource path for ease of lookup.
		// This is computed just once here and will never change.
		// Fail fast if there are duplicates for the same declaration.
		this.resourceMethodsByResourcePathDeclaration =
				sokletConfiguration.getResourceMethodResolver().getResourceMethods().stream()
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

			this.connectionValidityExecutorService = Executors.newScheduledThreadPool(1, new ThreadFactory() {
				@Override
				@Nonnull
				public Thread newThread(@Nonnull Runnable runnable) {
					requireNonNull(runnable);
					return new Thread(runnable, "sse-connection-validator");
				}
			});

			int initialDelayInSeconds = (int) Math.max(1, Math.min(5, getHeartbeatInterval().getSeconds()));
			long periodInSeconds = Math.max(1, getHeartbeatInterval().getSeconds());

			this.connectionValidityExecutorService.scheduleWithFixedDelay(() -> {
				try {
					performConnectionValidityTask();
				} catch (Throwable throwable) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Server-Sent Event connection validity checker encountered an error")
							.throwable(throwable)
							.build());
				}
			}, initialDelayInSeconds, periodInSeconds, TimeUnit.SECONDS);
		} finally {
			getLock().unlock();
		}
	}

	private static void enqueueServerSentEvent(@Nonnull ServerSentEventConnection serverSentEventConnection,
																						 @Nonnull ServerSentEvent serverSentEvent) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(serverSentEvent);

		BlockingQueue<ServerSentEvent> writeQueue = serverSentEventConnection.getWriteQueue();

		if (!writeQueue.offer(serverSentEvent)) {
			writeQueue.poll();
			writeQueue.offer(serverSentEvent);
		}
	}

	protected void performConnectionValidityTask() {
		Collection<DefaultServerSentEventBroadcaster> broadcasters = getBroadcastersByResourcePath().values();

		if (broadcasters.size() > 0) {
			final long nowInNanos = System.nanoTime();

			for (DefaultServerSentEventBroadcaster broadcaster : broadcasters) {
				Set<ServerSentEventConnection> serverSentEventConnections = broadcaster.getServerSentEventConnections();

				if (serverSentEventConnections.size() == 0) {
					// This broadcaster can be entirely dealloced because it has no more connections.
					// Remove empty broadcaster as a failsafe (also handled on connection teardown).
					getBroadcastersByResourcePath().remove(broadcaster.getResourcePath(), broadcaster);
				} else {
					for (ServerSentEventConnection serverSentEventConnection : serverSentEventConnections) {
						// Throttle heartbeats per connection
						AtomicLong lastValidityCheckInNanos = serverSentEventConnection.getLastValidityCheckInNanos();
						long previousValidityCheckInNanos = lastValidityCheckInNanos.get();

						if ((nowInNanos - previousValidityCheckInNanos) >= getHeartbeatInterval().toNanos() && lastValidityCheckInNanos.compareAndSet(previousValidityCheckInNanos, nowInNanos))
							enqueueServerSentEvent(serverSentEventConnection, SERVER_SENT_EVENT_CONNECTION_VALIDITY_CHECK);
					}
				}
			}
		}
	}

	protected void startInternal() {
		if (!isStarted() || isStopping())
			return;

		try {
			this.serverSocketChannel = ServerSocketChannel.open();
			this.serverSocketChannel.bind(new InetSocketAddress(getHost(), getPort()));

			ExecutorService executorService = getRequestHandlerExecutorService().get();

			while (!getStopPoisonPill().get()) {
				SocketChannel clientSocketChannel = this.serverSocketChannel.accept();
				Socket socket = clientSocketChannel.socket();
				socket.setKeepAlive(true);
				socket.setTcpNoDelay(true);

				executorService.submit(() -> handleClientSocketChannel(clientSocketChannel));
			}
		} catch (ClosedChannelException ignored) {
			// expected during shutdown
		} catch (IOException e) {
			safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR,
					"SSE event loop encountered an IO error").throwable(e).build());
		} finally {
			this.serverSocketChannel = null;
		}
	}

	protected void handleClientSocketChannel(@Nonnull SocketChannel clientSocketChannel) {
		requireNonNull(clientSocketChannel);

		ClientSocketChannelRegistration clientSocketChannelRegistration = null;
		Request request = null;
		ResourceMethod resourceMethod = null;
		ServerSentEvent serverSentEvent;
		Instant writeStarted;
		Throwable throwable = null;

		try (clientSocketChannel) {
			// Use the socket's address as an identifier
			String requestIdentifier = clientSocketChannel.getRemoteAddress().toString();

			try {
				// TODO: in a future version, we might introduce lifecycle interceptor option here and for Server for "will/didInitiateConnection"
				String rawRequest = readRequest(requestIdentifier, clientSocketChannel);
				request = parseRequest(requestIdentifier, rawRequest);
			} catch (URISyntaxException e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_UNPARSEABLE_REQUEST, format("Unable to parse server-sent event request URI: %s", e.getInput()))
						.throwable(e)
						.build());
				throw e;
			} catch (RequestTooLargeIOException e) {
				// Exception provides a "too large"-flagged request with whatever data we could pull out of it
				request = e.getTooLargeRequest();
			} catch (SocketTimeoutException e) {
				// TODO: in a future version, we might introduce lifecycle interceptor option here and for Server for "request timed out"
				throw e;
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_UNPARSEABLE_REQUEST, "Unable to parse server-sent event request")
						.throwable(e)
						.build());
				throw e;
			}

			//System.out.println(format("Received SSE request on socket: %s", debuggingString(request)));

			// Determine the resource path
			ResourcePathDeclaration resourcePathDeclaration = matchingResourcePath(request.getResourcePath()).orElse(null);

			if (resourcePathDeclaration != null)
				resourceMethod = getResourceMethodsByResourcePathDeclaration().get(resourcePathDeclaration);

			AtomicInteger marshaledResponseStatusCode = new AtomicInteger(500);

			// OK, we have a request.
			// First thing to do is write an HTTP response (status, headers) - and then we keep the socket open for subsequent writes.
			// To write this initial "handshake" response, we delegate to the Soklet instance, handing it the request we just parsed
			// and receiving a MarshaledResponse to write.  This lets the normal Soklet request processing flow occur.
			// Subsequent writes to the open socket are done via a ServerSentEventBroadcaster and sidestep the Soklet request processing flow.
			getRequestHandler().get().handleRequest(request, (@Nonnull RequestResult requestResult) -> {
				MarshaledResponse marshaledResponse = requestResult.getMarshaledResponse();

				marshaledResponseStatusCode.set(marshaledResponse.getStatusCode());
				String handshakeResponse = toHandshakeResponse(marshaledResponse);

				try {
					ByteBuffer byteBuffer = ByteBuffer.wrap(handshakeResponse.getBytes(StandardCharsets.UTF_8));

					while (byteBuffer.hasRemaining())
						clientSocketChannel.write(byteBuffer);
				} catch (IOException e) {
					// TODO: log out?
					throw new UncheckedIOException("Unable to write initial SSE handshake response", e);
				}
			});

			// Happy path? Register the channel for future ServerSentEvent writes.
			// If no socket channel registration (404) or >= 300 HTTP status, we're done immediately now that initial data has been written.
			if (resourceMethod != null && marshaledResponseStatusCode.get() < 300) {
				getLifecycleInterceptor().get().willEstablishServerSentEventConnection(request, resourceMethod);

				clientSocketChannelRegistration = registerClientSocketChannel(clientSocketChannel, request).get();

				// TODO: is this the right spot?  Should it be lower down?
				getLifecycleInterceptor().get().didEstablishServerSentEventConnection(request, resourceMethod);

				while (true) {
					//System.out.println(format("Waiting for SSE broadcasts on socket: %s", debuggingString(request)));
					serverSentEvent = clientSocketChannelRegistration.serverSentEventConnection().getWriteQueue().take();

					if (serverSentEvent == SERVER_SENT_EVENT_POISON_PILL) {
						//System.out.println("Encountered poison pill, exiting...");
						break;
					}

					ByteBuffer buffer = null;

					if (serverSentEvent == SERVER_SENT_EVENT_CONNECTION_VALIDITY_CHECK) {
						//System.out.println("Performing socket validity check by writing a heartbeat message...");
						String message = formatForResponse(ServerSentEvent.forHeartbeat());
						buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
					} else {
						//System.out.println(format("Writing %s to %s...", serverSentEvent, debuggingString(request)));
						String message = formatForResponse(serverSentEvent);
						buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
					}

					getLifecycleInterceptor().get().willStartServerSentEventWriting(request,
							clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), serverSentEvent);

					writeStarted = Instant.now();
					Throwable writeThrowable = null;

					try {
						while (buffer.hasRemaining()) {
							clientSocketChannel.write(buffer);
						}
					} catch (Throwable t) {
						writeThrowable = t;
					} finally {
						Instant writeFinished = Instant.now();
						Duration writeDuration = Duration.between(writeStarted, writeFinished);

						getLifecycleInterceptor().get().didFinishServerSentEventWriting(request,
								clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), serverSentEvent, writeDuration, writeThrowable);

						if (writeThrowable != null)
							throw writeThrowable;
					}
				}
			} else {
				String reason = "unknown";

				if (resourceMethod == null)
					reason = format("no SSE resource method exists for %s", request.getUri());
				else if (marshaledResponseStatusCode.get() >= 300)
					reason = format("SSE resource method status code is %d", marshaledResponseStatusCode.get());

				//System.out.println(format("Closing socket %s immediately after handshake instead of waiting for broadcasts. Reason: %s", debuggingString(request), reason));
			}
		} catch (Throwable t) {
			throwable = t;
			// System.out.println("Closing socket due to exception: " + t.getMessage());

			if (t instanceof InterruptedException)
				Thread.currentThread().interrupt();  // Restore interrupt status
		} finally {
			// First, tell the event source to unregister the connection
			if (clientSocketChannelRegistration != null) {
				if (resourceMethod != null)
					getLifecycleInterceptor().get().willTerminateServerSentEventConnection(request, resourceMethod, throwable);

				try {
					clientSocketChannelRegistration.broadcaster().unregisterServerSentEventConnection(clientSocketChannelRegistration.serverSentEventConnection(), false);

					// System.out.println(format("SSE socket thread completed for request: %s", debuggingString(clientSocketChannelRegistration.serverSentEventConnection().getRequest())));
				} catch (Exception exception) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to de-register Server-Sent Event connection")
							.throwable(exception)
							.build());
				}
			}

			// Then, close the channel itself
			if (clientSocketChannel != null) {
				try {
					// Should already be closed, but just in case
					clientSocketChannel.close();
				} catch (Exception exception) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to close Server-Sent Event connection socket channel")
							.throwable(exception)
							.build());
				} finally {
					// If that broadcaster is now empty, remove it promptly
					if (clientSocketChannelRegistration != null)
						maybeCleanupBroadcaster(clientSocketChannelRegistration.broadcaster());

					if (clientSocketChannelRegistration != null && resourceMethod != null) {
						Instant connectionFinished = Instant.now();
						Duration connectionDuration = Duration.between(clientSocketChannelRegistration.serverSentEventConnection().getEstablishedAt(), connectionFinished);

						getLifecycleInterceptor().get().didTerminateServerSentEventConnection(request, resourceMethod, connectionDuration, throwable);
					}
				}
			}
		}
	}

	@Nonnull
	protected String toHandshakeResponse(@Nonnull MarshaledResponse marshaledResponse) {
		requireNonNull(marshaledResponse);

		// For example:
		//
		// "HTTP/1.1 200 OK\r\n" +
		// "Content-Type: text/event-stream\r\n" +
		// "Cache-Control: no-cache\r\n" +
		// "X-Accel-Buffering: no\r\n" +
		// "Connection: keep-alive\r\n\r\n";

		// Default SSE headers applied only on success (2xx). Case-insensitive map to avoid duplicates.
		LinkedCaseInsensitiveMap<Set<String>> DEFAULT_HEADERS = new LinkedCaseInsensitiveMap<>(4);
		DEFAULT_HEADERS.put("Content-Type", Set.of("text/event-stream; charset=utf-8"));
		DEFAULT_HEADERS.put("Cache-Control", Set.of("no-cache"));
		DEFAULT_HEADERS.put("Connection", Set.of("keep-alive"));
		DEFAULT_HEADERS.put("X-Accel-Buffering", Set.of("no"));

		final Set<String> ILLEGAL_HEADER_NAMES_LOWER = Set.of("content-length");

		int statusCode = marshaledResponse.getStatusCode();
		Map<String, Set<String>> headers = marshaledResponse.getHeaders();

		List<String> lines = new ArrayList<>(1 + headers.size());

		// e.g. "HTTP/1.1 200 OK"
		String reason = StatusCode.fromStatusCode(statusCode).map(StatusCode::getReasonPhrase).orElse("");
		lines.add(format("HTTP/1.1 %d %s", statusCode, reason));

		// Write default headers
		if (statusCode >= 200 && statusCode < 300) {
			for (Entry<String, Set<String>> entry : DEFAULT_HEADERS.entrySet()) {
				String headerName = entry.getKey();
				Set<String> headerValues = entry.getValue();

				if (headerValues != null)
					for (String headerValue : headerValues)
						lines.add(format("%s: %s", headerName, headerValue));
			}
		}

		// Write custom headers
		for (Entry<String, Set<String>> entry : headers.entrySet()) {
			String headerName = entry.getKey();
			Set<String> headerValues = entry.getValue();

			// Only write headers that are not part of the default set
			if (!DEFAULT_HEADERS.containsKey(headerName)
					&& !ILLEGAL_HEADER_NAMES_LOWER.contains(headerName.toLowerCase(Locale.ENGLISH)))

				if (headerValues != null)
					for (String headerValue : headerValues)
						lines.add(format("%s: %s", headerName, headerValue));
		}

		return lines.stream().collect(Collectors.joining("\r\n")) + "\r\n\r\n";
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
		private final ResourceMethod resourceMethod;
		@Nonnull
		private final BlockingQueue<ServerSentEvent> writeQueue;
		@Nonnull
		private final Instant establishedAt;
		@Nonnull
		private final AtomicLong lastValidityCheckInNanos;

		public ServerSentEventConnection(@Nonnull Request request,
																		 @Nonnull ResourceMethod resourceMethod) {
			requireNonNull(request);
			requireNonNull(resourceMethod);

			this.request = request;
			this.resourceMethod = resourceMethod;
			this.writeQueue = new ArrayBlockingQueue<>(DEFAULT_CONNECTION_QUEUE_CAPACITY);
			this.establishedAt = Instant.now();
			this.lastValidityCheckInNanos = new AtomicLong(System.nanoTime());
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
		public BlockingQueue<ServerSentEvent> getWriteQueue() {
			return this.writeQueue;
		}

		@Nonnull
		public Instant getEstablishedAt() {
			return this.establishedAt;
		}

		@Nonnull
		public AtomicLong getLastValidityCheckInNanos() {
			return this.lastValidityCheckInNanos;
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

		if (isStopping() || !isStarted())
			return Optional.empty();

		ResourcePath resourcePath = request.getResourcePath();

		if (!matchingResourcePath(resourcePath).isPresent())
			return Optional.empty();

		// Get a handle to the event source (it will be created if necessary)
		DefaultServerSentEventBroadcaster broadcaster = acquireBroadcasterInternal(resourcePath).get();

		// Create the connection and register it with the EventSource
		ServerSentEventConnection serverSentEventConnection = new ServerSentEventConnection(request, broadcaster.getResourceMethod());

		if (!broadcaster.registerServerSentEventConnection(serverSentEventConnection))
			return Optional.empty();

		// Also register the connection globally so we can enforce an overall limit on the number of open connections.
		// If this causes an eviction, the eviction callback supplied to the ConcurrentLruMap will handle cleanup.
		getGlobalConnections().put(serverSentEventConnection, broadcaster);

		return Optional.of(new ClientSocketChannelRegistration(serverSentEventConnection, broadcaster));
	}

	protected void maybeCleanupBroadcaster(@Nonnull DefaultServerSentEventBroadcaster broadcaster) {
		requireNonNull(broadcaster);

		try {
			if (broadcaster.getServerSentEventConnections().isEmpty())
				getBroadcastersByResourcePath().remove(broadcaster.getResourcePath(), broadcaster);
		} catch (Throwable ignored) {
			// Nothing to do
		}
	}

	@Nonnull
	protected Request parseRequest(@Nonnull String requestIdentifier,
																 @Nonnull String rawRequest) throws URISyntaxException {
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
				String rawUri = components[1];
				URI uri = null;

				if (!httpMethod.equals("GET"))
					throw new IllegalStateException(format("Malformed Server-Sent Event request line '%s'. Expected a format like 'GET /example?one=two HTTP/1.1'", line));

				if (rawUri != null) {
					try {
						uri = new URI(rawUri);
					} catch (Exception ignored) {
						// Malformed URI
					}
				}

				if (uri == null)
					throw new URISyntaxException(rawUri, format("Malformed Server-Sent Event request line '%s'. Expected a format like 'GET /example?one=two HTTP/1.1'", line));

				requestBuilder = Request.with(HttpMethod.GET, rawUri);
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
	protected String readRequest(@Nonnull String requestIdentifier,
															 @Nonnull SocketChannel clientSocketChannel) throws IOException {
		requireNonNull(requestIdentifier);
		requireNonNull(clientSocketChannel);

		// Because reads from the socket channel are blocking, there is no way to specify a timeout for it.
		// We work around this by performing the read in a virtual thread, and use the timeout functionality
		// built in to Futures to interrupt the thread if it doesn't finish in time.
		Future<String> readFuture = null;

		try {
			readFuture = getRequestReaderExecutorService().get().submit(() -> {
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
						Request tooLargeRequest = parseTooLargeRequestForRawRequest(requestIdentifier, rawRequest).orElse(null);

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
			// Current thread interrupted while waiting
			Thread.currentThread().interrupt(); // restore interrupt status
			throw new IOException("Interrupted while awaiting request data", e);
		} catch (ExecutionException e) {
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
	protected Optional<Request> parseTooLargeRequestForRawRequest(@Nonnull String requestIdentifier,
																																@Nonnull String rawRequest) {
		requireNonNull(requestIdentifier);
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

		String[] parts = firstLine.split(" ");

		// First line of the request is malformed
		if (parts.length < 2)
			return Optional.empty();

		String rawHttpMethod = parts[0];

		if (rawHttpMethod != null)
			rawHttpMethod = rawHttpMethod.trim().toUpperCase(Locale.ENGLISH);

		HttpMethod httpMethod = null;

		try {
			httpMethod = HttpMethod.valueOf(rawHttpMethod);
		} catch (IllegalArgumentException e) {
			// Malformed HTTP method specified
			return Optional.empty();
		}

		String rawUri = parts[1];

		// Validate URI
		if (rawUri != null) {
			try {
				new URI(rawUri.trim());
			} catch (Exception e) {
				// Malformed URI specified
				return Optional.empty();
			}
		}

		// TODO: eventually would be nice to parse headers as best we can.  For now, we just parse the first request line
		return Optional.of(Request.with(httpMethod, rawUri)
				.id(requestIdentifier)
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
		this.stopping = false;

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
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR,
							"Unable to close Server-Sent Event SocketChannel").throwable(e).build());
				}
			}

			// Close client connections
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

			// Initiate shutdowns quickly (no blocking here beyond close() above)
			if (this.connectionValidityExecutorService != null)
				this.connectionValidityExecutorService.shutdown();

			if (this.requestHandlerExecutorService != null)
				this.requestHandlerExecutorService.shutdown();

			if (this.requestReaderExecutorService != null)
				this.requestReaderExecutorService.shutdown();

			// Shared wall-clock deadline
			final long deadlineNanos = System.nanoTime() + getShutdownTimeout().toNanos();

			// Await the accept-loop thread and all executors **in parallel**
			List<CompletableFuture<Boolean>> waits = new ArrayList<>(4);

			waits.add(joinAsync(this.eventLoopThread, remainingMillis(deadlineNanos)));

			if (this.connectionValidityExecutorService != null)
				waits.add(awaitTerminationAsync(this.connectionValidityExecutorService, remainingMillis(deadlineNanos)));

			if (this.requestHandlerExecutorService != null)
				waits.add(awaitTerminationAsync(this.requestHandlerExecutorService, remainingMillis(deadlineNanos)));

			if (this.requestReaderExecutorService != null)
				waits.add(awaitTerminationAsync(this.requestReaderExecutorService, remainingMillis(deadlineNanos)));

			// Wait for all, but no longer than the single budget
			try {
				CompletableFuture
						.allOf(waits.toArray(CompletableFuture[]::new))
						.get(Math.max(0L, remainingMillis(deadlineNanos)), TimeUnit.MILLISECONDS);
			} catch (TimeoutException te) {
				// Budget exhausted; escalate below
			} catch (InterruptedException e) {
				interrupted = true;
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				// Log and continue shutdown
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Exception while awaiting SSE shutdown").throwable(e).build());
			}

			// Escalate for any stragglers using remaining time
			hardenJoin(this.eventLoopThread, remainingMillis(deadlineNanos));

			if (this.connectionValidityExecutorService != null)
				hardenPool(this.connectionValidityExecutorService, remainingMillis(deadlineNanos));

			if (this.requestHandlerExecutorService != null)
				hardenPool(this.requestHandlerExecutorService, remainingMillis(deadlineNanos));

			if (this.requestReaderExecutorService != null)
				hardenPool(this.requestReaderExecutorService, remainingMillis(deadlineNanos));
		} finally {
			try {
				this.started = false;
				this.stopping = false; // allow future restarts
				this.eventLoopThread = null;
				this.requestHandlerExecutorService = null;
				this.requestReaderExecutorService = null;
				this.connectionValidityExecutorService = null;
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

	protected void hardenPool(@Nullable ExecutorService executorService,
														@Nonnull Long millis) {
		requireNonNull(millis);

		if (executorService == null || executorService.isTerminated())
			return;

		// Interrupt stuck tasks
		executorService.shutdownNow();

		try {
			executorService.awaitTermination(Math.max(100L, millis), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
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

		return acquireBroadcasterInternal(resourcePath);
	}

	@Nonnull
	protected Optional<DefaultServerSentEventBroadcaster> acquireBroadcasterInternal(@Nullable ResourcePath resourcePath) {
		if (resourcePath == null)
			return Optional.empty();

		ResourcePathDeclaration resourcePathDeclaration = matchingResourcePath(resourcePath).orElse(null);

		if (resourcePathDeclaration == null)
			return Optional.empty();

		ResourceMethod resourceMethod = getResourceMethodsByResourcePathDeclaration().get(resourcePathDeclaration);

		// Internal sanity guard
		if (resourceMethod == null)
			throw new IllegalStateException(format("Internal error: unable to find %s instance that matches %s", ResourceMethod.class, resourcePathDeclaration));

		// Create the event source if it does not already exist
		DefaultServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath()
				.computeIfAbsent(resourcePath, (ignored) -> new DefaultServerSentEventBroadcaster(resourceMethod, resourcePath, (serverSentEventConnection -> {
					// When the broadcaster unregisters a connection it manages, remove it from the global set of connections as well
					getGlobalConnections().remove(serverSentEventConnection);
				})));

		return Optional.of(broadcaster);
	}

	@Nonnull
	protected Optional<ResourcePathDeclaration> matchingResourcePath(@Nullable ResourcePath resourcePath) {
		if (resourcePath == null)
			return Optional.empty();

		// TODO: convert to computeIfAbsent()

		// Try a cache lookup first
		ResourcePathDeclaration resourcePathDeclaration = getResourcePathDeclarationsByResourcePathCache().get(resourcePath);

		if (resourcePathDeclaration == null) {
			// If the cache lookup fails, perform a manual lookup
			for (ResourcePathDeclaration registeredResourcePathDeclaration : getResourceMethodsByResourcePathDeclaration().keySet()) {
				if (registeredResourcePathDeclaration.matches(resourcePath)) {
					resourcePathDeclaration = registeredResourcePathDeclaration;
					break;
				}
			}

			// Put the value in the cache for quick access later
			if (resourcePathDeclaration != null)
				getResourcePathDeclarationsByResourcePathCache().put(resourcePath, resourcePathDeclaration);
		}

		return Optional.ofNullable(resourcePathDeclaration);
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
	protected ConcurrentHashMap<ResourcePath, ResourcePathDeclaration> getResourcePathDeclarationsByResourcePathCache() {
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
	protected ConcurrentLruMap<ServerSentEventConnection, DefaultServerSentEventBroadcaster> getGlobalConnections() {
		return this.globalConnections;
	}

	@Nullable
	protected ScheduledExecutorService getConnectionValidityExecutorService() {
		return this.connectionValidityExecutorService;
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
		private Duration requestTimeout;
		@Nullable
		private Duration shutdownTimeout;
		@Nullable
		private Duration heartbeatInterval;
		@Nullable
		private Integer maximumRequestSizeInBytes;
		@Nullable
		private Integer requestReadBufferSizeInBytes;
		@Nullable
		private Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
		@Nullable
		private Integer concurrentConnectionLimit;

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
		public Builder requestTimeout(@Nullable Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
			return this;
		}

		@Nonnull
		public Builder shutdownTimeout(@Nullable Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		@Nonnull
		public Builder heartbeatInterval(@Nullable Duration heartbeatInterval) {
			this.heartbeatInterval = heartbeatInterval;
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
		public Builder concurrentConnectionLimit(@Nullable Integer concurrentConnectionLimit) {
			this.concurrentConnectionLimit = concurrentConnectionLimit;
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
