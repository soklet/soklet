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

import com.soklet.SokletConfiguration;
import com.soklet.core.HttpMethod;
import com.soklet.core.LifecycleInterceptor;
import com.soklet.core.LogEvent;
import com.soklet.core.LogEventType;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
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
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
	private static final Duration DEFAULT_SOCKET_SELECT_TIMEOUT;
	@Nonnull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
	@Nonnull
	private static final Integer DEFAULT_SOCKET_READ_BUFFER_SIZE_IN_BYTES;
	@Nonnull
	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT;

	@Nonnull
	private static final ServerSentEvent SERVER_SENT_EVENT_CONNECTION_VALIDITY_CHECK;
	@Nonnull
	private static final ServerSentEvent SERVER_SENT_EVENT_POISON_PILL;

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_REQUEST_TIMEOUT = Duration.ofHours(12);
		DEFAULT_SOCKET_SELECT_TIMEOUT = Duration.ofMillis(100);
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024;
		DEFAULT_SOCKET_READ_BUFFER_SIZE_IN_BYTES = 1_024 * 8;
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

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
	private final Duration socketSelectTimeout;
	@Nonnull
	private final Duration shutdownTimeout;
	@Nonnull
	private final Integer maximumRequestSizeInBytes;
	@Nonnull
	private final Integer socketReadBufferSizeInBytes;
	@Nonnull
	private final ConcurrentHashMap<ResourcePath, DefaultServerSentEventBroadcaster> broadcastersByResourcePath;
	@Nonnull
	private final ConcurrentHashMap<ResourcePath, ResourcePathDeclaration> resourcePathDeclarationsByResourcePathCache;
	@Nonnull
	private final ConcurrentLruMap<ServerSentEventConnection, DefaultServerSentEventBroadcaster> globalConnections;
	@Nonnull
	private final ReentrantLock lock;
	@Nonnull
	private final Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
	@Nonnull
	private final Integer concurrentConnectionLimit;
	@Nonnull
	private final AtomicBoolean stopPoisonPill;
	@Nullable
	private volatile ExecutorService requestHandlerExecutorService;
	@Nullable
	private volatile ScheduledExecutorService connectionValidityExecutorService;
	@Nonnull
	private volatile Boolean started;
	@Nonnull
	private volatile Boolean stopping;
	@Nullable
	private Thread eventLoopThread;
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

			if (unregistered) {
				getConnectionUnregisteredListener().accept(serverSentEventConnection);

				// If requested, send a poison pill so the socket thread gets terminated
				if (sendPoisonPill)
					serverSentEventConnection.getWriteQueue().add(SERVER_SENT_EVENT_POISON_PILL);
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
		this.socketReadBufferSizeInBytes = builder.socketReadBufferSizeInBytes != null ? builder.socketReadBufferSizeInBytes : DEFAULT_SOCKET_READ_BUFFER_SIZE_IN_BYTES;
		this.requestTimeout = builder.requestTimeout != null ? builder.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
		this.socketSelectTimeout = builder.socketSelectTimeout != null ? builder.socketSelectTimeout : DEFAULT_SOCKET_SELECT_TIMEOUT;
		this.shutdownTimeout = builder.shutdownTimeout != null ? builder.shutdownTimeout : DEFAULT_SHUTDOWN_TIMEOUT;
		this.resourceMethodsByResourcePathDeclaration = Map.of(); // Temporary to remain non-null; will be overridden by Soklet via #initialize

		// TODO: let clients specify initial capacity
		this.broadcastersByResourcePath = new ConcurrentHashMap<>(1_024);
		this.resourcePathDeclarationsByResourcePathCache = new ConcurrentHashMap<>(1_024);

		this.requestHandlerExecutorServiceSupplier = builder.requestHandlerExecutorServiceSupplier != null ? builder.requestHandlerExecutorServiceSupplier : () -> {
			String threadNamePrefix = "sse-handler-";

			// Default implementation: cowardly refuse to run on anything other than a runtime that supports Virtual threads.
			// Applications can override this by bringing their own ExecutorService via requestHandlerExecutorServiceSupplier, but it's not recommended.
			if (!Utilities.virtualThreadsAvailable())
				throw new IllegalStateException(format("Virtual threads are required for %s", getClass().getSimpleName()));

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
		// TODO: we should fail-fast if there are multiple @ServerSentEventSource annotations with the same resource path.  Should that happen here or at the Soklet level?
		this.resourceMethodsByResourcePathDeclaration = sokletConfiguration.getResourceMethodResolver().getResourceMethods().stream()
				.filter(resourceMethod -> resourceMethod.isServerSentEventSource())
				.collect(Collectors.toMap(ResourceMethod::getResourcePath, Function.identity()));
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

			getLifecycleInterceptor().get().willStartServerSentEventServer(this);

			this.requestHandlerExecutorService = getRequestHandlerExecutorServiceSupplier().get();
			this.eventLoopThread = new Thread(this::startInternal, "sse-event-loop");
			eventLoopThread.start();

			this.connectionValidityExecutorService = Executors.newScheduledThreadPool(1, new ThreadFactory() {
				@Override
				@Nonnull
				public Thread newThread(@Nonnull Runnable runnable) {
					requireNonNull(runnable);
					return new Thread(runnable, "sse-connection-validator");
				}
			});

			// TODO: make durations configurable
			this.connectionValidityExecutorService.scheduleWithFixedDelay(() -> {
				try {
					performConnectionValidityTask();
				} catch (Throwable throwable) {
					// TODO: send to logger
					throwable.printStackTrace();
					System.out.println("Connection validity checker failed");
				}
			}, 5, 15, TimeUnit.SECONDS);

			this.started = true;
			getLifecycleInterceptor().get().didStartServerSentEventServer(this);
		} finally {
			getLock().unlock();
		}
	}

	protected void performConnectionValidityTask() {
		Collection<DefaultServerSentEventBroadcaster> broadcasters = getBroadcastersByResourcePath().values();
		int i = 0;

		//System.out.println("Global connections (" + getGlobalConnections().size() + "): " + getGlobalConnections());

		if (broadcasters.size() > 0) {
			for (DefaultServerSentEventBroadcaster broadcaster : broadcasters) {
				++i;
				System.out.println(format("Performing validity checks for broadcaster %d of %d (%s)...", i, broadcasters.size(), broadcaster.getResourcePath().getPath()));

				Set<ServerSentEventConnection> serverSentEventConnections = broadcaster.getServerSentEventConnections();

				System.out.println(format("This broadcaster has %d SSE connections", serverSentEventConnections.size()));

				if (serverSentEventConnections.size() == 0) {
					// This broadcaster can be entirely dealloced because it has no more connections.
					// TODO: this should be more of a failsafe, we should factor into its own method and call this at the end of a socket thread too for immediate cleanup
					// TODO: broadcaster removes/adds be protected with a "broadcasterLock"
					System.out.println("Because this broadcaster has no connections, removing it.");
					getBroadcastersByResourcePath().remove(broadcaster.getResourcePath());
				} else {
					int j = 0;

					for (ServerSentEventConnection serverSentEventConnection : serverSentEventConnections) {
						++j;
						System.out.println(format("Enqueuing heartbeat for socket %d of %d...", j, serverSentEventConnections.size()));
						// TODO: keep track of when the most recent validity check was done so we don't do it too frequently, e.g. with AtomicReference<Instant> on ServerSentEventConnection (nice-to-have)
						serverSentEventConnection.writeQueue.add(SERVER_SENT_EVENT_CONNECTION_VALIDITY_CHECK); // TODO: should this just be the heartbeat event?
					}
				}
			}
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
		Request request = null;
		ResourceMethod resourceMethod = null;
		ServerSentEvent serverSentEvent = null;
		Instant writeStarted = null;
		Throwable throwable = null;

		try (clientSocketChannel) {
			String rawRequest = null;

			try {
				rawRequest = readRequest(clientSocketChannel);
			} catch (Exception e) {
				// TODO: cleanup, send a 400?
				System.out.println("Unable to read raw request.");
				e.printStackTrace();
			}

			// Use the socket's address as an identifier
			String requestIdentifier = clientSocketChannel.getRemoteAddress().toString();
			request = parseRequest(requestIdentifier, rawRequest);

			System.out.println(format("Received SSE request on socket: %s", debuggingString(request)));

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
			getRequestHandler().get().handleRequest(request, (@Nonnull MarshaledResponse marshaledResponse) -> {
				marshaledResponseStatusCode.set(marshaledResponse.getStatusCode());
				String handshakeResponse = toHandshakeResponse(marshaledResponse);

				try {
					clientSocketChannel.write(ByteBuffer.wrap(handshakeResponse.getBytes(StandardCharsets.UTF_8)));
				} catch (IOException e) {
					throw new UncheckedIOException("Unable to write initial SSE handshake response", e);
				}
			});

			// Happy path? Register the channel for future ServerSentEvent writes.
			// If no socket channel registration (404) or >= 300 HTTP status, we're done immediately now that initial data has been written.
			if (resourceMethod != null && marshaledResponseStatusCode.get() < 300) {
				getLifecycleInterceptor().get().willEstablishServerSentEventConnection(request, resourceMethod);

				clientSocketChannelRegistration = registerClientSocketChannel(clientSocketChannel, request).get();

				getLifecycleInterceptor().get().didEstablishServerSentEventConnection(request, resourceMethod);

				while (true) {
					System.out.println(format("Waiting for SSE broadcasts on socket: %s", debuggingString(request)));
					serverSentEvent = clientSocketChannelRegistration.serverSentEventConnection().getWriteQueue().take();

					if (serverSentEvent == SERVER_SENT_EVENT_POISON_PILL) {
						System.out.println("Encountered poison pill, exiting...");
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
						clientSocketChannel.write(buffer);
					} catch (Throwable t) {
						writeThrowable = t;
					} finally {
						Instant writeFinished = Instant.now();
						Duration writeDuration = Duration.between(writeStarted, writeFinished);

						getLifecycleInterceptor().get().didFinishServerSentEventWriting(request,
								clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), serverSentEvent, writeDuration, throwable);

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

				System.out.println(format("Closing socket %s immediately after handshake instead of waiting for broadcasts. Reason: %s", debuggingString(request), reason));
			}
		} catch (Throwable t) {
			throwable = t;
			System.out.println("Closing socket due to exception: " + t.getMessage());

			if (t instanceof InterruptedException) {
				System.out.println("Socket thread was interrupted");
				Thread.currentThread().interrupt();  // Restore interrupt status
			}
		} finally {
			// First, tell the event source to unregister the connection
			if (clientSocketChannelRegistration != null) {
				if (resourceMethod != null)
					getLifecycleInterceptor().get().willTerminateServerSentEventConnection(request, resourceMethod, throwable);

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
					// Should already be closed, but just in case
					clientSocketChannel.close();
				} catch (Exception ignored) {
					System.out.println("Unable to close socket channel");
					ignored.printStackTrace();
				} finally {
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

		// TODO: make this a configurable value.  It's just here temporarily.
		final Map<String, Set<String>> DEFAULT_HEADERS = Map.of(
				"Content-Type", Set.of("text/event-stream"),
				"Cache-Control", Set.of("no-cache"),
				"Connection", Set.of("keep-alive"),
				"X-Accel-Buffering", Set.of("on")
		);

		final Set<String> ILLEGAL_HEADER_NAMES = Set.of("Content-Length");

		int statusCode = marshaledResponse.getStatusCode();
		Map<String, Set<String>> headers = marshaledResponse.getHeaders();

		List<String> lines = new ArrayList<>(1 + headers.size());

		// e.g. "HTTP/1.1 200 OK"
		lines.add(format("HTTP/1.1 %d %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()));

		// Write default headers
		// TODO: do these apply for responses > HTTP 299?  Probably not.
		for (Entry<String, Set<String>> entry : DEFAULT_HEADERS.entrySet()) {
			String headerName = entry.getKey();
			Set<String> headerValues = entry.getValue();

			if (headerValues != null)
				for (String headerValue : headerValues)
					lines.add(format("%s: %s", headerName, headerValue));
		}

		// Write custom headers
		for (Entry<String, Set<String>> entry : headers.entrySet()) {
			// TODO: case-insensitive comparison
			String headerName = entry.getKey();
			Set<String> headerValues = entry.getValue();

			// Only write headers that are not part of the default set
			if (!DEFAULT_HEADERS.containsKey(headerName) && !ILLEGAL_HEADER_NAMES.contains(headerName))
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

		public ServerSentEventConnection(@Nonnull Request request,
																		 @Nonnull ResourceMethod resourceMethod) {
			requireNonNull(request);
			requireNonNull(resourceMethod);

			this.request = request;
			this.resourceMethod = resourceMethod;
			this.writeQueue = new ArrayBlockingQueue<>(8);
			this.establishedAt = Instant.now(); // Don't use this for anything currently, but might later
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

		boolean interrupted = false;
		this.stopping = false;

		try {
			if (!isStarted())
				return;

			getLifecycleInterceptor().get().willStopServerSentEventServer(this);
			this.stopping = true;

			try {
				this.connectionValidityExecutorService.shutdown();
				this.connectionValidityExecutorService.awaitTermination(getShutdownTimeout().getSeconds(), TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				interrupted = true;
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to shut down Server-Sent Event connection validity checker")
						.throwable(e)
						.build());
			}

			getStopPoisonPill().set(true);

			// TODO: need an additional check/lock to prevent race condition where someone acquires an event source while we are shutting down
			for (DefaultServerSentEventBroadcaster broadcaster : getBroadcastersByResourcePath().values()) {
				try {
					broadcaster.unregisterAllServerSentEventConnections(true);
				} catch (Exception e) {
					System.out.println("Unable to unregister connection, continuing on...");
					e.printStackTrace();
				}
			}

			// Clear global connections map for sanity (though it should be empty by this point)
			getGlobalConnections().clear();

			try {
				getRequestHandlerExecutorService().get().shutdown();
				getRequestHandlerExecutorService().get().awaitTermination(getShutdownTimeout().getSeconds(), TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				interrupted = true;
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to shut down Server-Sent Event request handler")
						.throwable(e)
						.build());
			}
		} finally {
			this.started = false;
			this.eventLoopThread = null;
			this.requestHandlerExecutorService = null;
			this.connectionValidityExecutorService = null;
			this.getBroadcastersByResourcePath().clear();
			this.getResourcePathDeclarationsByResourcePathCache().clear();
			getStopPoisonPill().set(false);

			if (this.stopping)
				getLifecycleInterceptor().get().didStopServerSentEventServer(this);

			if (interrupted)
				Thread.currentThread().interrupt();

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
	public Optional<? extends ServerSentEventBroadcaster> acquireBroadcaster(@Nullable ResourcePath resourcePath) {
		if (resourcePath == null)
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

		// TODO: should this be sent as a LogEvent?
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
	protected ReentrantLock getLock() {
		return this.lock;
	}

	@Nonnull
	protected Supplier<ExecutorService> getRequestHandlerExecutorServiceSupplier() {
		return this.requestHandlerExecutorServiceSupplier;
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
		private Duration socketSelectTimeout;
		@Nullable
		private Duration shutdownTimeout;
		@Nullable
		private Integer maximumRequestSizeInBytes;
		@Nullable
		private Integer socketReadBufferSizeInBytes;
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
		public Builder socketSelectTimeout(@Nullable Duration socketSelectTimeout) {
			this.socketSelectTimeout = socketSelectTimeout;
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
