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

import com.soklet.SseRequestResult.HandshakeAccepted;
import com.soklet.SseRequestResult.HandshakeRejected;
import com.soklet.annotation.SseEventSource;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.soklet.Utilities.emptyByteArray;
import static com.soklet.Utilities.extractContentTypeFromHeaders;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Soklet's main class - manages one or more configured transport servers ({@link HttpServer}, {@link SseServer}, and/or {@link McpServer})
 * using the provided system configuration.
 * <p>
 * <pre>{@code // Use out-of-the-box defaults
 * SokletConfig config = SokletConfig.withHttpServer(
 *   HttpServer.fromPort(8080)
 * ).build();
 *
 * try (Soklet soklet = Soklet.fromConfig(config)) {
 *   soklet.start();
 *   System.out.println("Soklet started, press [enter] to exit");
 *   soklet.awaitShutdown(ShutdownTrigger.ENTER_KEY);
 * }}</pre>
 * <p>
 * Soklet also offers an off-network {@link Simulator} concept via {@link #runSimulator(SokletConfig, Consumer)}, useful for integration testing.
 * <p>
 * Given a <em>Resource Method</em>...
 * <pre>{@code public class HelloResource {
 *   @GET("/hello")
 *   public String hello(@QueryParameter String name) {
 *     return String.format("Hello, %s", name);
 *   }
 * }}</pre>
 * ...we might test it like this:
 * <pre>{@code @Test
 * public void integrationTest() {
 *   // Just use your app's existing configuration
 *   SokletConfig config = obtainMySokletConfig();
 *
 *   // Instead of running on a real HTTP server that listens on a port,
 *   // a non-network Simulator is provided against which you can
 *   // issue requests and receive responses.
 *   Soklet.runSimulator(config, (simulator -> {
 *     // Construct a request
 *     Request request = Request.withPath(HttpMethod.GET, "/hello")
 *       .queryParameters(Map.of("name", Set.of("Mark")))
 *       .build();
 *
 *     // Perform the request and get a handle to the response
 *     HttpRequestResult result = simulator.performHttpRequest(request);
 *     MarshaledResponse marshaledResponse = result.getMarshaledResponse();
 *
 *     // Verify status code
 *     Integer expectedCode = 200;
 *     Integer actualCode = marshaledResponse.getStatusCode();
 *     assertEquals(expectedCode, actualCode, "Bad status code");
 *
 *     // Verify response body
 *     marshaledResponse.getBody().ifPresentOrElse(body -> {
 *       String expectedBody = "Hello, Mark";
 *       byte[] bytes = ((MarshaledResponseBody.Bytes) body).getBytes();
 *       String actualBody = new String(bytes, StandardCharsets.UTF_8);
 *       assertEquals(expectedBody, actualBody, "Bad response body");
 *     }, () -> {
 *       Assertions.fail("No response body");
 *     });
 *   }));
 * }}</pre>
 * <p>
 * The {@link Simulator} also supports Server-Sent Events.
 * <p>
 * Integration testing documentation is available at <a href="https://www.soklet.com/docs/testing">https://www.soklet.com/docs/testing</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Soklet implements AutoCloseable {
	@NonNull
	private static final Map<@NonNull String, @NonNull Set<@NonNull String>> DEFAULT_ACCEPTED_HANDSHAKE_HEADERS;

	static {
		// Generally speaking, we always want these headers for SSE streaming responses.
		// Users can override if they think necessary
		LinkedCaseInsensitiveMap<Set<String>> defaultAcceptedHandshakeHeaders = new LinkedCaseInsensitiveMap<>(4);
		defaultAcceptedHandshakeHeaders.put("Content-Type", Set.of("text/event-stream; charset=UTF-8"));
		defaultAcceptedHandshakeHeaders.put("Cache-Control", Set.of("no-cache", "no-transform"));
		defaultAcceptedHandshakeHeaders.put("Connection", Set.of("keep-alive"));
		defaultAcceptedHandshakeHeaders.put("X-Accel-Buffering", Set.of("no"));

		DEFAULT_ACCEPTED_HANDSHAKE_HEADERS = Collections.unmodifiableMap(defaultAcceptedHandshakeHeaders);
	}

	/**
	 * Acquires a Soklet instance with the given configuration.
	 *
	 * @param sokletConfig configuration that drives the Soklet system
	 * @return a Soklet instance
	 */
	@NonNull
	public static Soklet fromConfig(@NonNull SokletConfig sokletConfig) {
		requireNonNull(sokletConfig);
		return new Soklet(sokletConfig);
	}

	@NonNull
	private final SokletConfig sokletConfig;
	@NonNull
	private final ReentrantLock lock;
	@NonNull
	private final AtomicReference<CountDownLatch> awaitShutdownLatchReference;
	@NonNull
	private final DefaultMcpRuntime defaultMcpRuntime;

	/**
	 * Creates a Soklet instance with the given configuration.
	 *
	 * @param sokletConfig configuration that drives the Soklet system
	 */
	private Soklet(@NonNull SokletConfig sokletConfig) {
		requireNonNull(sokletConfig);

		this.sokletConfig = sokletConfig;
		this.lock = new ReentrantLock();
		this.awaitShutdownLatchReference = new AtomicReference<>(new CountDownLatch(1));
		this.defaultMcpRuntime = new DefaultMcpRuntime(this);

		sokletConfig.getMcpServer()
				.map(McpServer::getSessionStore)
				.filter(DefaultMcpSessionStore.class::isInstance)
				.map(DefaultMcpSessionStore.class::cast)
				.ifPresent(sessionStore -> sessionStore.pinnedSessionPredicate(this.defaultMcpRuntime::hasActiveStream));

		sokletConfig.getMcpServer()
				.map(mcpServer -> mcpServer instanceof McpServerProxy mcpServerProxy ? mcpServerProxy.getRealImplementation() : mcpServer)
				.filter(DefaultMcpServer.class::isInstance)
				.map(DefaultMcpServer.class::cast)
				.ifPresent(defaultMcpServer -> defaultMcpServer.mcpRuntime(this.defaultMcpRuntime));

		Set<ResourceMethod> resourceMethods = sokletConfig.getResourceMethodResolver().getResourceMethods();

		// Fail fast in the event that Soklet appears misconfigured
		if (resourceMethods.size() == 0
				&& sokletConfig.getMcpServer().isEmpty())
			throw new IllegalStateException(format("No Soklet Resource Methods were found. First, try to rebuild and see if that solves the problem. If not, please ensure your %s is configured correctly. "
					+ "See https://www.soklet.com/docs/request-handling#resource-method-resolution for details.", ResourceMethodResolver.class.getSimpleName()));

		boolean hasStandardHttpResourceMethods = resourceMethods.stream()
				.anyMatch(resourceMethod -> !resourceMethod.isSseEventSource());

		if (hasStandardHttpResourceMethods && sokletConfig.getHttpServer().isEmpty())
			throw new IllegalStateException(format("Resource Methods were found, but no %s is configured. See https://www.soklet.com/docs/server-configuration for details.",
					HttpServer.class.getSimpleName()));

		// SSE misconfiguration check: @SseEventSource resource methods are declared, but not SseServer exists
		boolean hasSseResourceMethods = resourceMethods.stream()
				.anyMatch(resourceMethod -> resourceMethod.isSseEventSource());

		if (hasSseResourceMethods && sokletConfig.getSseServer().isEmpty())
			throw new IllegalStateException(format("Resource Methods annotated with @%s were found, but no %s is configured. See https://www.soklet.com/docs/server-sent-events for details.",
					SseEventSource.class.getSimpleName(), SseServer.class.getSimpleName()));

		MetricsCollector metricsCollector = sokletConfig.getMetricsCollector();

		if (metricsCollector instanceof DefaultMetricsCollector defaultMetricsCollector) {
			try {
				defaultMetricsCollector.initialize(sokletConfig);
			} catch (Throwable t) {
				sokletConfig.getAggregateLifecycleObserver().didReceiveLogEvent(
						LogEvent.with(LogEventType.METRICS_COLLECTOR_FAILED,
										format("An exception occurred while initializing %s", metricsCollector.getClass().getSimpleName()))
								.throwable(t)
								.build());
			}
		}

		// Use a layer of indirection here so the Soklet type does not need to directly implement the `RequestHandler` interface.
		// Reasoning: the `handleRequest` method for Soklet should not be public, which might lead to accidental invocation by users.
		// That method should only be called by the managed `HttpServer` instance.
		Soklet soklet = this;

		sokletConfig.getHttpServer().ifPresent(server -> server.initialize(getSokletConfig(), (request, marshaledResponseConsumer) -> {
			// Delegate to Soklet's internal request handling method
			soklet.handleRequest(request, ServerType.STANDARD_HTTP, marshaledResponseConsumer);
		}));

		SseServer sseServer = sokletConfig.getSseServer().orElse(null);

		if (sseServer != null)
			sseServer.initialize(sokletConfig, (request, marshaledResponseConsumer) -> {
				// Delegate to Soklet's internal request handling method
				soklet.handleRequest(request, ServerType.SSE, marshaledResponseConsumer);
			});

		McpServer mcpServer = sokletConfig.getMcpServer().orElse(null);

		if (mcpServer != null)
			mcpServer.initialize(sokletConfig, soklet::handleMcpRequest);
	}

	/**
	 * Starts the managed server instance[s].
	 * <p>
	 * If the managed server[s] are already started, this is a no-op.
	 */
	public void start() {
		getLock().lock();

		try {
			if (isStarted())
				return;

			getAwaitShutdownLatchReference().set(new CountDownLatch(1));

			SokletConfig sokletConfig = getSokletConfig();
			LifecycleObserver lifecycleObserver = sokletConfig.getAggregateLifecycleObserver();

				// 1. Notify global intent to start
				lifecycleObserver.willStartSoklet(this);

				HttpServer httpServer = sokletConfig.getHttpServer().orElse(null);
				SseServer sseServer = sokletConfig.getSseServer().orElse(null);
				McpServer mcpServer = sokletConfig.getMcpServer().orElse(null);
				boolean httpServerStarted = false;
				boolean sseServerStarted = false;
				boolean mcpServerStarted = false;

				try {
					// 2. Attempt to start Main HttpServer
					if (httpServer != null) {
						lifecycleObserver.willStartHttpServer(httpServer);
						try {
							httpServer.start();
							httpServerStarted = true;
							lifecycleObserver.didStartHttpServer(httpServer);
						} catch (Throwable t) {
							lifecycleObserver.didFailToStartHttpServer(httpServer, t);
							throw t; // Rethrow to trigger outer catch block
						}
					}

					// 3. Attempt to start SSE HttpServer (if present)
					if (sseServer != null) {
						lifecycleObserver.willStartSseServer(sseServer);
						try {
							sseServer.start();
							sseServerStarted = true;
							lifecycleObserver.didStartSseServer(sseServer);
						} catch (Throwable t) {
							lifecycleObserver.didFailToStartSseServer(sseServer, t);
							throw t; // Rethrow to trigger outer catch block
						}
					}

					if (mcpServer != null) {
						lifecycleObserver.willStartMcpServer(mcpServer);
						try {
							mcpServer.start();
							mcpServerStarted = true;
							lifecycleObserver.didStartMcpServer(mcpServer);
						} catch (Throwable t) {
							lifecycleObserver.didFailToStartMcpServer(mcpServer, t);
							throw t;
						}
				}

					// 4. Global success
					lifecycleObserver.didStartSoklet(this);
				} catch (Throwable t) {
					rollbackStartedServersAfterFailedStart(lifecycleObserver,
							httpServer, httpServerStarted,
							sseServer, sseServerStarted,
							mcpServer, mcpServerStarted,
							t);

					// 5. Global failure
					lifecycleObserver.didFailToStartSoklet(this, t);

					// Ensure the exception bubbles up so the application knows startup failed
				if (t instanceof RuntimeException)
					throw (RuntimeException) t;

				throw new RuntimeException(t);
			}
		} finally {
			getLock().unlock();
		}
	}

	private void rollbackStartedServersAfterFailedStart(@NonNull LifecycleObserver lifecycleObserver,
																										@Nullable HttpServer httpServer,
																										boolean httpServerStarted,
																										@Nullable SseServer sseServer,
																										boolean sseServerStarted,
																										@Nullable McpServer mcpServer,
																										boolean mcpServerStarted,
																										@NonNull Throwable startupFailure) {
		requireNonNull(lifecycleObserver);
		requireNonNull(startupFailure);

		if (mcpServerStarted && mcpServer != null)
			stopStartedMcpServerForRollback(lifecycleObserver, mcpServer, startupFailure);

		if (sseServerStarted && sseServer != null)
			stopStartedSseServerForRollback(lifecycleObserver, sseServer, startupFailure);

		if (httpServerStarted && httpServer != null)
			stopStartedHttpServerForRollback(lifecycleObserver, httpServer, startupFailure);

		CountDownLatch awaitShutdownLatch = getAwaitShutdownLatchReference().get();

		if (awaitShutdownLatch != null)
			awaitShutdownLatch.countDown();
	}

	private void stopStartedHttpServerForRollback(@NonNull LifecycleObserver lifecycleObserver,
																								@NonNull HttpServer httpServer,
																								@NonNull Throwable startupFailure) {
		requireNonNull(lifecycleObserver);
		requireNonNull(httpServer);
		requireNonNull(startupFailure);

		try {
			lifecycleObserver.willStopHttpServer(httpServer);
		} catch (Throwable t) {
			startupFailure.addSuppressed(t);
		}

		try {
			httpServer.stop();
			try {
				lifecycleObserver.didStopHttpServer(httpServer);
			} catch (Throwable t) {
				startupFailure.addSuppressed(t);
			}
		} catch (Throwable t) {
			startupFailure.addSuppressed(t);

			try {
				lifecycleObserver.didFailToStopHttpServer(httpServer, t);
			} catch (Throwable t2) {
				startupFailure.addSuppressed(t2);
			}
		}
	}

	private void stopStartedSseServerForRollback(@NonNull LifecycleObserver lifecycleObserver,
																							 @NonNull SseServer sseServer,
																							 @NonNull Throwable startupFailure) {
		requireNonNull(lifecycleObserver);
		requireNonNull(sseServer);
		requireNonNull(startupFailure);

		try {
			lifecycleObserver.willStopSseServer(sseServer);
		} catch (Throwable t) {
			startupFailure.addSuppressed(t);
		}

		try {
			sseServer.stop();
			try {
				lifecycleObserver.didStopSseServer(sseServer);
			} catch (Throwable t) {
				startupFailure.addSuppressed(t);
			}
		} catch (Throwable t) {
			startupFailure.addSuppressed(t);

			try {
				lifecycleObserver.didFailToStopSseServer(sseServer, t);
			} catch (Throwable t2) {
				startupFailure.addSuppressed(t2);
			}
		}
	}

	private void stopStartedMcpServerForRollback(@NonNull LifecycleObserver lifecycleObserver,
																							 @NonNull McpServer mcpServer,
																							 @NonNull Throwable startupFailure) {
		requireNonNull(lifecycleObserver);
		requireNonNull(mcpServer);
		requireNonNull(startupFailure);

		try {
			lifecycleObserver.willStopMcpServer(mcpServer);
		} catch (Throwable t) {
			startupFailure.addSuppressed(t);
		}

		try {
			mcpServer.stop();
			try {
				lifecycleObserver.didStopMcpServer(mcpServer);
			} catch (Throwable t) {
				startupFailure.addSuppressed(t);
			}
		} catch (Throwable t) {
			startupFailure.addSuppressed(t);

			try {
				lifecycleObserver.didFailToStopMcpServer(mcpServer, t);
			} catch (Throwable t2) {
				startupFailure.addSuppressed(t2);
			}
		}
	}

	/**
	 * Stops the managed server instance[s].
	 * <p>
	 * If the managed server[s] are already stopped, this is a no-op.
	 */
	public void stop() {
		getLock().lock();

		try {
			if (isStarted()) {
				SokletConfig sokletConfig = getSokletConfig();
				LifecycleObserver lifecycleObserver = sokletConfig.getAggregateLifecycleObserver();

				// 1. Notify global intent to stop
				lifecycleObserver.willStopSoklet(this);

				Throwable firstEncounteredException = null;
					HttpServer httpServer = sokletConfig.getHttpServer().orElse(null);

					// 2. Attempt to stop Main HttpServer
					if (httpServer != null && httpServer.isStarted()) {
						lifecycleObserver.willStopHttpServer(httpServer);
						try {
							httpServer.stop();
						lifecycleObserver.didStopHttpServer(httpServer);
					} catch (Throwable t) {
						firstEncounteredException = t;
						lifecycleObserver.didFailToStopHttpServer(httpServer, t);
					}
				}

				// 3. Attempt to stop SSE HttpServer
				SseServer sseServer = sokletConfig.getSseServer().orElse(null);

				if (sseServer != null && sseServer.isStarted()) {
					lifecycleObserver.willStopSseServer(sseServer);
					try {
						sseServer.stop();
						lifecycleObserver.didStopSseServer(sseServer);
					} catch (Throwable t) {
						if (firstEncounteredException == null)
							firstEncounteredException = t;

						lifecycleObserver.didFailToStopSseServer(sseServer, t);
					}
				}

				McpServer mcpServer = sokletConfig.getMcpServer().orElse(null);

				if (mcpServer != null && mcpServer.isStarted()) {
					lifecycleObserver.willStopMcpServer(mcpServer);
					try {
						mcpServer.stop();
						lifecycleObserver.didStopMcpServer(mcpServer);
					} catch (Throwable t) {
						if (firstEncounteredException == null)
							firstEncounteredException = t;

						lifecycleObserver.didFailToStopMcpServer(mcpServer, t);
					}
				}

				// 4. Global completion (Success or Failure)
				if (firstEncounteredException == null)
					lifecycleObserver.didStopSoklet(this);
				else
					lifecycleObserver.didFailToStopSoklet(this, firstEncounteredException);
			}
		} finally {
			try {
				getAwaitShutdownLatchReference().get().countDown();
			} finally {
				getLock().unlock();
			}
		}
	}

	/**
	 * Blocks the current thread until JVM shutdown ({@code SIGTERM/SIGINT/System.exit(...)} and so forth), <strong>or</strong> if one of the provided {@code shutdownTriggers} occurs.
	 * <p>
	 * This method will automatically invoke this instance's {@link #stop()} method once it becomes unblocked.
	 * <p>
	 * <strong>Notes regarding {@link ShutdownTrigger#ENTER_KEY}:</strong>
	 * <ul>
	 *   <li>It will invoke {@link #stop()} on <i>all</i> Soklet instances, as stdin is process-wide</li>
	 *   <li>It is only supported for environments with an interactive TTY and will be ignored if none exists (e.g. running in a Docker container) - Soklet will detect this and fire {@link LifecycleObserver#didReceiveLogEvent(LogEvent)} with an event of type {@link LogEventType#CONFIGURATION_UNSUPPORTED}</li>
	 * </ul>
	 *
	 * @param shutdownTriggers additional trigger[s] which signal that shutdown should occur, e.g. {@link ShutdownTrigger#ENTER_KEY} for "enter key pressed"
	 * @throws InterruptedException if the current thread has its interrupted status set on entry to this method, or is interrupted while waiting
	 */
	public void awaitShutdown(@Nullable ShutdownTrigger... shutdownTriggers) throws InterruptedException {
		Thread shutdownHook = null;
		boolean registeredEnterKeyShutdownTrigger = false;
		Set<ShutdownTrigger> shutdownTriggersAsSet = shutdownTriggers == null || shutdownTriggers.length == 0 ? Set.of() : EnumSet.copyOf(Set.of(shutdownTriggers));

		try {
			// Optionally listen for enter key
			if (shutdownTriggersAsSet.contains(ShutdownTrigger.ENTER_KEY)) {
				registeredEnterKeyShutdownTrigger = KeypressManager.register(this); // returns false if stdin unusable/disabled

				if (!registeredEnterKeyShutdownTrigger) {
					LogEvent logEvent = LogEvent.with(
							LogEventType.CONFIGURATION_UNSUPPORTED,
							format("Ignoring request for %s.%s - it is unsupported in this environment (no interactive TTY detected)", ShutdownTrigger.class.getSimpleName(), ShutdownTrigger.ENTER_KEY.name())
					).build();

					getSokletConfig().getAggregateLifecycleObserver().didReceiveLogEvent(logEvent);
				}
			}

			// Always register a shutdown hook
			shutdownHook = new Thread(() -> {
				try {
					stop();
				} catch (Throwable ignored) {
					// Nothing to do
				}
			}, "soklet-shutdown-hook");

			Runtime.getRuntime().addShutdownHook(shutdownHook);

			// Wait until "close" finishes
			getAwaitShutdownLatchReference().get().await();
		} finally {
			if (registeredEnterKeyShutdownTrigger)
				KeypressManager.unregister(this);

			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				// JVM shutting down
			}
		}
	}

	/**
	 * Handles "awaitShutdown" for {@link ShutdownTrigger#ENTER_KEY} by listening to stdin - all Soklet instances are terminated on keypress.
	 */
	@ThreadSafe
	private static final class KeypressManager {
		@NonNull
		private static final Set<@NonNull Soklet> SOKLET_REGISTRY;
		@NonNull
		private static final AtomicBoolean LISTENER_STARTED;

		static {
			SOKLET_REGISTRY = new CopyOnWriteArraySet<>();
			LISTENER_STARTED = new AtomicBoolean(false);
		}

		/**
		 * Register a Soklet for Enter-to-stop support. Returns true iff a listener is (or was already) active.
		 * If System.in is not usable (or disabled), returns false and does nothing.
		 */
		@NonNull
		synchronized static Boolean register(@NonNull Soklet soklet) {
			requireNonNull(soklet);

			// If stdin is not readable (e.g., container with no TTY), don't start a listener.
			if (!canReadFromStdin())
				return false;

			SOKLET_REGISTRY.add(soklet);

			// Start a single process-wide listener once.
			if (LISTENER_STARTED.compareAndSet(false, true)) {
				Thread thread = new Thread(KeypressManager::runLoop, "soklet-keypress-shutdown-listener");
				thread.setDaemon(true); // never block JVM exit
				thread.start();
			}

			return true;
		}

		synchronized static void unregister(@NonNull Soklet soklet) {
			SOKLET_REGISTRY.remove(soklet);
			// We intentionally keep the listener alive; it's daemon and cheap.
			// If stdin hits EOF, the listener exits on its own.
		}

		/**
		 * Heuristic: if System.in is present and calling available() doesn't throw,
		 * treat it as readable. Works even in IDEs where System.console() is null.
		 */
		@NonNull
		private static Boolean canReadFromStdin() {
			if (System.in == null)
				return false;

			try {
				// available() >= 0 means stream is open; 0 means no buffered data (that’s fine).
				return System.in.available() >= 0;
			} catch (IOException e) {
				return false;
			}
		}

		/**
		 * Single blocking read on stdin. On any line (or EOF), stop all registered servers.
		 */
		private static void runLoop() {
			try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
				// Blocks until newline or EOF; EOF (null) happens with /dev/null or closed pipe.
				bufferedReader.readLine();
				stopAllSoklets();
			} catch (Throwable ignored) {
				// If stdin is closed mid-run, just exit quietly.
			}
		}

		synchronized private static void stopAllSoklets() {
			// Either a line or EOF → stop everything that’s currently registered.
			for (Soklet soklet : SOKLET_REGISTRY) {
				try {
					soklet.stop();
				} catch (Throwable ignored) {
					// Nothing to do
				}
			}
		}

		private KeypressManager() {}
	}

	/**
	 * Nonpublic "informal" implementation of {@link com.soklet.HttpServer.RequestHandler} so Soklet does not need to expose {@code handleRequest} publicly.
	 * Reasoning: users of this library should never call {@code handleRequest} directly - it should only be invoked in response to events
	 * provided by a {@link HttpServer} or {@link SseServer} implementation.
	 */
	protected void handleRequest(@NonNull Request request,
															 @NonNull ServerType serverType,
															 @NonNull Consumer<HttpRequestResult> requestResultConsumer) {
		requireNonNull(request);
		requireNonNull(serverType);
		requireNonNull(requestResultConsumer);

		Instant processingStarted = Instant.now();

		SokletConfig sokletConfig = getSokletConfig();
		ResourceMethodResolver resourceMethodResolver = sokletConfig.getResourceMethodResolver();
		ResponseMarshaler responseMarshaler = sokletConfig.getResponseMarshaler();
		LifecycleObserver lifecycleObserver = sokletConfig.getAggregateLifecycleObserver();
		RequestInterceptor requestInterceptor = sokletConfig.getRequestInterceptor();
		MetricsCollector metricsCollector = sokletConfig.getMetricsCollector();

		// Holders to permit mutable effectively-final variables
		AtomicReference<MarshaledResponse> marshaledResponseHolder = new AtomicReference<>();
		AtomicReference<Throwable> resourceMethodResolutionExceptionHolder = new AtomicReference<>();
		AtomicReference<Request> requestHolder = new AtomicReference<>(request);
		AtomicReference<ResourceMethod> resourceMethodHolder = new AtomicReference<>();
		AtomicReference<HttpRequestResult> requestResultHolder = new AtomicReference<>();

		// Holders to permit mutable effectively-final state tracking
		AtomicBoolean willStartResponseWritingCompleted = new AtomicBoolean(false);
		AtomicBoolean didFinishResponseWritingCompleted = new AtomicBoolean(false);
		AtomicBoolean didFinishRequestHandlingCompleted = new AtomicBoolean(false);
		AtomicBoolean didInvokeWrapRequestConsumer = new AtomicBoolean(false);

		List<Throwable> throwables = new ArrayList<>(10);

		Consumer<LogEvent> safelyLog = (logEvent -> {
			try {
				lifecycleObserver.didReceiveLogEvent(logEvent);
			} catch (Throwable throwable) {
				throwable.printStackTrace();
				throwables.add(throwable);
			}
		});

		BiConsumer<String, Consumer<MetricsCollector>> safelyCollectMetrics = (message, metricsInvocation) -> {
			if (metricsCollector == null)
				return;

			try {
				metricsInvocation.accept(metricsCollector);
			} catch (Throwable throwable) {
				safelyLog.accept(LogEvent.with(LogEventType.METRICS_COLLECTOR_FAILED, message)
						.throwable(throwable)
						.request(requestHolder.get())
						.resourceMethod(resourceMethodHolder.get())
						.marshaledResponse(marshaledResponseHolder.get())
						.build());
			}
		};

		requestHolder.set(request);

		try {
			requestInterceptor.wrapRequest(serverType, request, (wrappedRequest) -> {
				didInvokeWrapRequestConsumer.set(true);
				requestHolder.set(wrappedRequest);

				try {
					// Resolve after wrapping so path/method rewrites affect routing.
					resourceMethodHolder.set(resourceMethodResolver.resourceMethodForRequest(requestHolder.get(), serverType).orElse(null));
					resourceMethodResolutionExceptionHolder.set(null);
				} catch (Throwable t) {
					safelyLog.accept(LogEvent.with(LogEventType.RESOURCE_METHOD_RESOLUTION_FAILED, "Unable to resolve Resource Method")
							.throwable(t)
							.request(requestHolder.get())
							.build());

					// If an exception occurs here, keep track of it - we will surface them after letting LifecycleObserver
					// see that a request has come in.
					throwables.add(t);
					resourceMethodResolutionExceptionHolder.set(t);
					resourceMethodHolder.set(null);
				}

				try {
					lifecycleObserver.didStartRequestHandling(serverType, requestHolder.get(), resourceMethodHolder.get());
				} catch (Throwable t) {
					safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_START_REQUEST_HANDLING_FAILED,
									format("An exception occurred while invoking %s::didStartRequestHandling",
											LifecycleObserver.class.getSimpleName()))
							.throwable(t)
							.request(requestHolder.get())
							.resourceMethod(resourceMethodHolder.get())
							.build());

					throwables.add(t);
				}

				safelyCollectMetrics.accept(
						format("An exception occurred while invoking %s::didStartRequestHandling", MetricsCollector.class.getSimpleName()),
						(metricsInvocation) -> metricsInvocation.didStartRequestHandling(serverType, requestHolder.get(), resourceMethodHolder.get()));

				try {
					AtomicBoolean didInvokeMarshaledResponseConsumer = new AtomicBoolean(false);

					requestInterceptor.interceptRequest(serverType, requestHolder.get(), resourceMethodHolder.get(), (interceptorRequest) -> {
						requestHolder.set(interceptorRequest);

						try {
							if (resourceMethodResolutionExceptionHolder.get() != null)
								throw resourceMethodResolutionExceptionHolder.get();

							HttpRequestResult requestResult = toHttpRequestResult(requestHolder.get(), resourceMethodHolder.get(), serverType);
							requestResultHolder.set(requestResult);

							MarshaledResponse originalMarshaledResponse = requestResult.getMarshaledResponse();
							MarshaledResponse updatedMarshaledResponse = requestResult.getMarshaledResponse();

							// A few special cases that are "global" in that they can affect all requests and
							// need to happen after marshaling the response...

							// 1. Customize response for HEAD (e.g. remove body, set Content-Length header)
							updatedMarshaledResponse = applyHeadResponseIfApplicable(requestHolder.get(), updatedMarshaledResponse);

							// 2. Apply other standard response customizations (CORS, Content-Length)
							// Note that we don't want to write Content-Length for SSE "accepted" handshakes
							SseHandshakeResult sseHandshakeResult = requestResult.getSseHandshakeResult().orElse(null);
							boolean suppressContentLength = sseHandshakeResult != null && sseHandshakeResult instanceof SseHandshakeResult.Accepted;

							updatedMarshaledResponse = applyCommonPropertiesToMarshaledResponse(requestHolder.get(), updatedMarshaledResponse, suppressContentLength);

							// Update our result holder with the modified response if necessary
							if (originalMarshaledResponse != updatedMarshaledResponse) {
								marshaledResponseHolder.set(updatedMarshaledResponse);
								requestResultHolder.set(requestResult.copy()
										.marshaledResponse(updatedMarshaledResponse)
										.finish());
							}

							return updatedMarshaledResponse;
							} catch (Throwable t) {
								if (t != resourceMethodResolutionExceptionHolder.get()) {
									throwables.add(t);

								safelyLog.accept(LogEvent.with(LogEventType.REQUEST_PROCESSING_FAILED,
												"An exception occurred while processing request")
										.throwable(t)
										.request(requestHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.build());
							}

							// Unhappy path.  Try to use configuration's exception response marshaler...
							try {
								MarshaledResponse marshaledResponse = responseMarshaler.forThrowable(requestHolder.get(), t, resourceMethodHolder.get());
								marshaledResponse = applyCommonPropertiesToMarshaledResponse(requestHolder.get(), marshaledResponse);
								marshaledResponseHolder.set(marshaledResponse);

								return marshaledResponse;
							} catch (Throwable t2) {
								throwables.add(t2);

								safelyLog.accept(LogEvent.with(LogEventType.RESPONSE_MARSHALER_FOR_THROWABLE_FAILED,
												format("An exception occurred while trying to write an exception response for %s", t))
										.throwable(t2)
										.request(requestHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.build());

								// The configuration's exception response marshaler failed - provide a failsafe response to recover
								return provideFailsafeMarshaledResponse(requestHolder.get(), t2);
							}
						}
					}, (interceptorMarshaledResponse) -> {
						requireNonNull(interceptorMarshaledResponse);
						didInvokeMarshaledResponseConsumer.set(true);
						marshaledResponseHolder.set(interceptorMarshaledResponse);
					});

					if (!didInvokeMarshaledResponseConsumer.get()) {
						requestResultHolder.set(null);
						throw new IllegalStateException(format("%s::interceptRequest must call responseWriter", RequestInterceptor.class.getSimpleName()));
					}
				} catch (Throwable t) {
					throwables.add(t);

					try {
						// In the event that an error occurs during processing of a RequestInterceptor method, for example
						safelyLog.accept(LogEvent.with(LogEventType.REQUEST_INTERCEPTOR_INTERCEPT_REQUEST_FAILED,
										format("An exception occurred while invoking %s::interceptRequest", RequestInterceptor.class.getSimpleName()))
								.throwable(t)
								.request(requestHolder.get())
								.resourceMethod(resourceMethodHolder.get())
								.build());

						MarshaledResponse marshaledResponse = responseMarshaler.forThrowable(requestHolder.get(), t, resourceMethodHolder.get());
						marshaledResponse = applyCommonPropertiesToMarshaledResponse(requestHolder.get(), marshaledResponse);
						marshaledResponseHolder.set(marshaledResponse);
					} catch (Throwable t2) {
						throwables.add(t2);

						safelyLog.accept(LogEvent.with(LogEventType.RESPONSE_MARSHALER_FOR_THROWABLE_FAILED,
										format("An exception occurred while invoking %s::forThrowable when trying to write an exception response for %s", ResponseMarshaler.class.getSimpleName(), t))
								.throwable(t2)
								.request(requestHolder.get())
								.resourceMethod(resourceMethodHolder.get())
								.build());

						marshaledResponseHolder.set(provideFailsafeMarshaledResponse(requestHolder.get(), t2));
					}
				} finally {
					try {
						try {
							lifecycleObserver.willWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get());
						} finally {
							willStartResponseWritingCompleted.set(true);
						}

						safelyCollectMetrics.accept(
								format("An exception occurred while invoking %s::willWriteResponse", MetricsCollector.class.getSimpleName()),
								(metricsInvocation) -> metricsInvocation.willWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get()));

						Instant responseWriteStarted = Instant.now();

						try {
							HttpRequestResult requestResult = requestResultHolder.get();

							if (requestResult != null)
								requestResultConsumer.accept(requestResult);
							else
								requestResultConsumer.accept(HttpRequestResult.withMarshaledResponse(marshaledResponseHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.build());

							Instant responseWriteFinished = Instant.now();
							Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

							try {
								lifecycleObserver.didWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration);
							} catch (Throwable t) {
								throwables.add(t);

								safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_WRITE_RESPONSE_FAILED,
												format("An exception occurred while invoking %s::didWriteResponse",
														LifecycleObserver.class.getSimpleName()))
										.throwable(t)
										.request(requestHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.marshaledResponse(marshaledResponseHolder.get())
										.build());
							} finally {
								didFinishResponseWritingCompleted.set(true);
							}

							safelyCollectMetrics.accept(
									format("An exception occurred while invoking %s::didWriteResponse", MetricsCollector.class.getSimpleName()),
									(metricsInvocation) -> metricsInvocation.didWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(),
											marshaledResponseHolder.get(), responseWriteDuration));
						} catch (Throwable t) {
							throwables.add(t);

							Instant responseWriteFinished = Instant.now();
							Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

							try {
								lifecycleObserver.didFailToWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, t);
							} catch (Throwable t2) {
								throwables.add(t2);

								safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_WRITE_RESPONSE_FAILED,
												format("An exception occurred while invoking %s::didFailToWriteResponse",
														LifecycleObserver.class.getSimpleName()))
										.throwable(t2)
										.request(requestHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.marshaledResponse(marshaledResponseHolder.get())
										.build());
							}

							safelyCollectMetrics.accept(
									format("An exception occurred while invoking %s::didFailToWriteResponse", MetricsCollector.class.getSimpleName()),
									(metricsInvocation) -> metricsInvocation.didFailToWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(),
											marshaledResponseHolder.get(), responseWriteDuration, t));
						}
					} finally {
						Duration processingDuration = Duration.between(processingStarted, Instant.now());

						safelyCollectMetrics.accept(
								format("An exception occurred while invoking %s::didFinishRequestHandling", MetricsCollector.class.getSimpleName()),
								(metricsInvocation) -> metricsInvocation.didFinishRequestHandling(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables)));

						try {
							lifecycleObserver.didFinishRequestHandling(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables));
						} catch (Throwable t) {
							safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_FINISH_REQUEST_HANDLING_FAILED,
											format("An exception occurred while invoking %s::didFinishRequestHandling",
													LifecycleObserver.class.getSimpleName()))
									.throwable(t)
									.request(requestHolder.get())
									.resourceMethod(resourceMethodHolder.get())
									.marshaledResponse(marshaledResponseHolder.get())
									.build());
						} finally {
							didFinishRequestHandlingCompleted.set(true);
						}
					}
				}
			});

			if (!didInvokeWrapRequestConsumer.get())
				throw new IllegalStateException(format("%s::wrapRequest must call requestProcessor", RequestInterceptor.class.getSimpleName()));
		} catch (Throwable t) {
			// If an error occurred during request wrapping, it's possible a response was never written/communicated back to LifecycleObserver.
			// Detect that here and inform LifecycleObserver accordingly.
			safelyLog.accept(LogEvent.with(LogEventType.REQUEST_INTERCEPTOR_WRAP_REQUEST_FAILED,
							format("An exception occurred while invoking %s::wrapRequest",
									RequestInterceptor.class.getSimpleName()))
					.throwable(t)
					.request(requestHolder.get())
					.resourceMethod(resourceMethodHolder.get())
					.marshaledResponse(marshaledResponseHolder.get())
					.build());

			// If we don't have a response, let the marshaler try to make one for the exception.
			// If that fails, use the failsafe.
			if (marshaledResponseHolder.get() == null) {
				try {
					MarshaledResponse marshaledResponse = responseMarshaler.forThrowable(requestHolder.get(), t, resourceMethodHolder.get());
					marshaledResponse = applyCommonPropertiesToMarshaledResponse(requestHolder.get(), marshaledResponse);
					marshaledResponseHolder.set(marshaledResponse);
				} catch (Throwable t2) {
					throwables.add(t2);

					safelyLog.accept(LogEvent.with(LogEventType.RESPONSE_MARSHALER_FOR_THROWABLE_FAILED,
									format("An exception occurred during request wrapping while invoking %s::forThrowable",
											ResponseMarshaler.class.getSimpleName()))
							.throwable(t2)
							.request(requestHolder.get())
							.resourceMethod(resourceMethodHolder.get())
							.marshaledResponse(marshaledResponseHolder.get())
							.build());

					marshaledResponseHolder.set(provideFailsafeMarshaledResponse(requestHolder.get(), t));
				}
			}

			if (!willStartResponseWritingCompleted.get()) {
				try {
					lifecycleObserver.willWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get());
				} catch (Throwable t2) {
					safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_WRITE_RESPONSE_FAILED,
									format("An exception occurred while invoking %s::willWriteResponse",
											LifecycleObserver.class.getSimpleName()))
							.throwable(t2)
							.request(requestHolder.get())
							.resourceMethod(resourceMethodHolder.get())
							.marshaledResponse(marshaledResponseHolder.get())
							.build());
				}

				safelyCollectMetrics.accept(
						format("An exception occurred while invoking %s::willWriteResponse", MetricsCollector.class.getSimpleName()),
						(metricsInvocation) -> metricsInvocation.willWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get()));
			}

			try {
				Instant responseWriteStarted = Instant.now();

				if (!didFinishResponseWritingCompleted.get()) {
					try {
						HttpRequestResult requestResult = requestResultHolder.get();

						if (requestResult != null)
							requestResultConsumer.accept(requestResult);
						else
							requestResultConsumer.accept(HttpRequestResult.withMarshaledResponse(marshaledResponseHolder.get())
									.resourceMethod(resourceMethodHolder.get())
									.build());

						Instant responseWriteFinished = Instant.now();
						Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

						try {
							lifecycleObserver.didWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration);
						} catch (Throwable t2) {
							throwables.add(t2);

							safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_WRITE_RESPONSE_FAILED,
											format("An exception occurred while invoking %s::didWriteResponse",
													LifecycleObserver.class.getSimpleName()))
									.throwable(t2)
									.request(requestHolder.get())
									.resourceMethod(resourceMethodHolder.get())
									.marshaledResponse(marshaledResponseHolder.get())
									.build());
						}

						safelyCollectMetrics.accept(
								format("An exception occurred while invoking %s::didWriteResponse", MetricsCollector.class.getSimpleName()),
								(metricsInvocation) -> metricsInvocation.didWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(),
										marshaledResponseHolder.get(), responseWriteDuration));
					} catch (Throwable t2) {
						throwables.add(t2);

						Instant responseWriteFinished = Instant.now();
						Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

						try {
							lifecycleObserver.didFailToWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, t);
						} catch (Throwable t3) {
							throwables.add(t3);

							safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_WRITE_RESPONSE_FAILED,
											format("An exception occurred while invoking %s::didFailToWriteResponse",
													LifecycleObserver.class.getSimpleName()))
									.throwable(t3)
									.request(requestHolder.get())
									.resourceMethod(resourceMethodHolder.get())
									.marshaledResponse(marshaledResponseHolder.get())
									.build());
						}

						safelyCollectMetrics.accept(
								format("An exception occurred while invoking %s::didFailToWriteResponse", MetricsCollector.class.getSimpleName()),
								(metricsInvocation) -> metricsInvocation.didFailToWriteResponse(serverType, requestHolder.get(), resourceMethodHolder.get(),
										marshaledResponseHolder.get(), responseWriteDuration, t));
					}
				}
			} finally {
				if (!didFinishRequestHandlingCompleted.get()) {
					Duration processingDuration = Duration.between(processingStarted, Instant.now());

					safelyCollectMetrics.accept(
							format("An exception occurred while invoking %s::didFinishRequestHandling", MetricsCollector.class.getSimpleName()),
							(metricsInvocation) -> metricsInvocation.didFinishRequestHandling(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables)));

					try {
						lifecycleObserver.didFinishRequestHandling(serverType, requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables));
					} catch (Throwable t2) {
						safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_FINISH_REQUEST_HANDLING_FAILED,
										format("An exception occurred while invoking %s::didFinishRequestHandling",
												LifecycleObserver.class.getSimpleName()))
								.throwable(t2)
								.request(requestHolder.get())
								.resourceMethod(resourceMethodHolder.get())
								.marshaledResponse(marshaledResponseHolder.get())
								.build());
					}
				}
			}
		}
	}

	@NonNull
	protected HttpRequestResult toHttpRequestResult(@NonNull Request request,
																					@Nullable ResourceMethod resourceMethod,
																					@NonNull ServerType serverType) throws Throwable {
		requireNonNull(request);
		requireNonNull(serverType);

		ResourceMethodParameterProvider resourceMethodParameterProvider = getSokletConfig().getResourceMethodParameterProvider();
		InstanceProvider instanceProvider = getSokletConfig().getInstanceProvider();
		CorsAuthorizer corsAuthorizer = getSokletConfig().getCorsAuthorizer();
		ResourceMethodResolver resourceMethodResolver = getSokletConfig().getResourceMethodResolver();
		ResponseMarshaler responseMarshaler = getSokletConfig().getResponseMarshaler();
		CorsPreflight corsPreflight = request.getCorsPreflight().orElse(null);

		// Special short-circuit for big requests
		if (request.isContentTooLarge())
			return HttpRequestResult.withMarshaledResponse(responseMarshaler.forContentTooLarge(request, resourceMethodResolver.resourceMethodForRequest(request, serverType).orElse(null)))
					.resourceMethod(resourceMethod)
					.build();

		// Special short-circuit for OPTIONS *
		if (request.getResourcePath() == ResourcePath.OPTIONS_SPLAT_RESOURCE_PATH)
			return HttpRequestResult.withMarshaledResponse(responseMarshaler.forOptionsSplat(request)).build();

		// No resource method was found for this HTTP method and path.
		if (resourceMethod == null) {
			// If this was an OPTIONS request, do special processing.
			// If not, figure out if we should return a 404 or 405.
			if (request.getHttpMethod() == HttpMethod.OPTIONS) {
				// See what methods are available to us for this request's path
				Map<HttpMethod, ResourceMethod> matchingResourceMethodsByHttpMethod = resolveMatchingResourceMethodsByHttpMethod(request, resourceMethodResolver, serverType);

				// Special handling for CORS preflight requests, if needed
				if (corsPreflight != null) {
					// Let configuration function determine if we should authorize this request.
					// Discard any OPTIONS references - see https://stackoverflow.com/a/68529748
					Map<HttpMethod, ResourceMethod> nonOptionsMatchingResourceMethodsByHttpMethod = matchingResourceMethodsByHttpMethod.entrySet().stream()
							.filter(entry -> entry.getKey() != HttpMethod.OPTIONS)
							.collect(Collectors.toMap(Entry::getKey, Entry::getValue));

					CorsPreflightResponse corsPreflightResponse = corsAuthorizer.authorizePreflight(request, corsPreflight, nonOptionsMatchingResourceMethodsByHttpMethod).orElse(null);

					// Allow or reject CORS depending on what the function said to do
					if (corsPreflightResponse != null) {
						// Allow
						MarshaledResponse marshaledResponse = responseMarshaler.forCorsPreflightAllowed(request, corsPreflight, corsPreflightResponse);

						return HttpRequestResult.withMarshaledResponse(marshaledResponse)
								.corsPreflightResponse(corsPreflightResponse)
								.resourceMethod(resourceMethod)
								.build();
					}

					// Reject
					return HttpRequestResult.withMarshaledResponse(responseMarshaler.forCorsPreflightRejected(request, corsPreflight))
							.resourceMethod(resourceMethod)
							.build();
				} else {
					// Just a normal OPTIONS response (non-CORS-preflight).
					// If there's a matching OPTIONS resource method for this OPTIONS request, then invoke it.
					ResourceMethod optionsResourceMethod = matchingResourceMethodsByHttpMethod.get(HttpMethod.OPTIONS);

					if (optionsResourceMethod != null) {
						resourceMethod = optionsResourceMethod;
					} else {
						Set<HttpMethod> allowedHttpMethods = allowedHttpMethodsForResponse(matchingResourceMethodsByHttpMethod, true);

						return HttpRequestResult.withMarshaledResponse(responseMarshaler.forOptions(request, allowedHttpMethods))
								.resourceMethod(resourceMethod)
								.build();
					}
				}
			} else if (request.getHttpMethod() == HttpMethod.HEAD) {
				// If there's a matching GET resource method for this HEAD request, then invoke it
				Request headGetRequest = request.copy().httpMethod(HttpMethod.GET).finish();
				ResourceMethod headGetResourceMethod = resourceMethodResolver.resourceMethodForRequest(headGetRequest, serverType).orElse(null);

				if (headGetResourceMethod != null)
					resourceMethod = headGetResourceMethod;
				else
					return HttpRequestResult.withMarshaledResponse(responseMarshaler.forNotFound(request))
							.resourceMethod(resourceMethod)
							.build();
			} else {
				// Not an OPTIONS request, so it's possible we have a 405. See if other HTTP methods match...
				Map<HttpMethod, ResourceMethod> otherMatchingResourceMethodsByHttpMethod = resolveMatchingResourceMethodsByHttpMethod(request, resourceMethodResolver, serverType);

				Set<HttpMethod> matchingNonOptionsHttpMethods = otherMatchingResourceMethodsByHttpMethod.keySet().stream()
						.filter(httpMethod -> httpMethod != HttpMethod.OPTIONS)
						.collect(Collectors.toSet());

				if (matchingNonOptionsHttpMethods.size() > 0) {
					// ...if some do, it's a 405
					Set<HttpMethod> allowedHttpMethods = allowedHttpMethodsForResponse(otherMatchingResourceMethodsByHttpMethod, true);
					return HttpRequestResult.withMarshaledResponse(responseMarshaler.forMethodNotAllowed(request, allowedHttpMethods))
							.resourceMethod(resourceMethod)
							.build();
				} else {
					// no matching resource method found, it's a 404
					return HttpRequestResult.withMarshaledResponse(responseMarshaler.forNotFound(request))
							.resourceMethod(resourceMethod)
							.build();
				}
			}
		}

		// Found a resource method - happy path.
		// 1. Get an instance of the resource class
		// 2. Get values to pass to the resource method on the resource class
		// 3. Invoke the resource method and use its return value to drive a response
		Class<?> resourceClass = resourceMethod.getMethod().getDeclaringClass();
		Object resourceClassInstance;

		try {
			resourceClassInstance = instanceProvider.provide(resourceClass);
		} catch (Exception e) {
			throw new IllegalArgumentException(format("Unable to acquire an instance of %s", resourceClass.getName()), e);
		}

		List<Object> parameterValues = resourceMethodParameterProvider.parameterValuesForResourceMethod(request, resourceMethod);

		Object responseObject;

		try {
			responseObject = resourceMethod.getMethod().invoke(resourceClassInstance, parameterValues.toArray());
		} catch (InvocationTargetException e) {
			if (e.getTargetException() != null)
				throw e.getTargetException();

			throw e;
		}

		// Unwrap the Optional<T>, if one exists.  We do not recurse deeper than one level
		if (responseObject instanceof Optional<?>)
			responseObject = ((Optional<?>) responseObject).orElse(null);

		Response response;
		SseHandshakeResult sseHandshakeResult = null;

		// If null/void return, it's a 204
		// If it's a MarshaledResponse object, no marshaling + return it immediately - caller knows exactly what it wants to write.
		// If it's a Response object, use as is.
		// If it's a non-Response type of object, assume it's the response body and wrap in a Response.
		if (responseObject == null) {
			response = Response.withStatusCode(204).build();
		} else if (responseObject instanceof MarshaledResponse) {
			MarshaledResponse marshaledResponse = (MarshaledResponse) responseObject;
			enforceBodylessStatusCode(marshaledResponse.getStatusCode(), marshaledResponse.getBody().isPresent() || marshaledResponse.getStream().isPresent());

			return HttpRequestResult.withMarshaledResponse(marshaledResponse)
					.resourceMethod(resourceMethod)
					.build();
		} else if (responseObject instanceof Response) {
			response = (Response) responseObject;
		} else if (responseObject instanceof SseHandshakeResult.Accepted accepted) { // SSE "accepted" handshake
			return HttpRequestResult.withMarshaledResponse(toMarshaledResponse(accepted))
					.resourceMethod(resourceMethod)
					.sseHandshakeResult(accepted)
					.build();
		} else if (responseObject instanceof SseHandshakeResult.Rejected rejected) { // SSE "rejected" handshake
			response = rejected.getResponse();
			sseHandshakeResult = rejected;
		} else {
			response = Response.withStatusCode(200).body(responseObject).build();
		}

		enforceBodylessStatusCode(response.getStatusCode(), response.getBody().isPresent());

		MarshaledResponse marshaledResponse = responseMarshaler.forResourceMethod(request, response, resourceMethod);

		enforceBodylessStatusCode(marshaledResponse.getStatusCode(), marshaledResponse.getBody().isPresent() || marshaledResponse.getStream().isPresent());

		return HttpRequestResult.withMarshaledResponse(marshaledResponse)
				.response(response)
				.resourceMethod(resourceMethod)
				.sseHandshakeResult(sseHandshakeResult)
				.build();
	}

	@NonNull
	private MarshaledResponse toMarshaledResponse(SseHandshakeResult.@NonNull Accepted accepted) {
		requireNonNull(accepted);

		Map<String, Set<String>> headers = accepted.getHeaders();
		LinkedCaseInsensitiveMap<Set<String>> finalHeaders = new LinkedCaseInsensitiveMap<>(DEFAULT_ACCEPTED_HANDSHAKE_HEADERS.size() + headers.size());

		// Start with defaults
		for (Map.Entry<String, Set<String>> e : DEFAULT_ACCEPTED_HANDSHAKE_HEADERS.entrySet())
			finalHeaders.put(e.getKey(), e.getValue()); // values already unmodifiable

		// Overlay user-supplied headers (prefer user values on key collision)
		for (Map.Entry<String, Set<String>> e : headers.entrySet()) {
			// Defensively copy so callers can't mutate after construction
			Set<String> values = e.getValue() == null ? Set.of() : Set.copyOf(e.getValue());
			finalHeaders.put(e.getKey(), values);
		}

		return MarshaledResponse.withStatusCode(200)
				.headers(finalHeaders)
				.cookies(accepted.getCookies())
				.build();
	}

	private static void enforceBodylessStatusCode(@NonNull Integer statusCode,
																								@NonNull Boolean hasBody) {
		requireNonNull(statusCode);
		requireNonNull(hasBody);

		if (hasBody && isBodylessStatusCode(statusCode))
			throw new IllegalStateException(format("HTTP status code %d must not include a response body", statusCode));
	}

	private static boolean isBodylessStatusCode(@NonNull Integer statusCode) {
		requireNonNull(statusCode);
		return (statusCode >= 100 && statusCode < 200) || statusCode == 204 || statusCode == 304;
	}

	@NonNull
	protected MarshaledResponse applyHeadResponseIfApplicable(@NonNull Request request,
																														@NonNull MarshaledResponse marshaledResponse) {
		if (request.getHttpMethod() != HttpMethod.HEAD)
			return marshaledResponse;

		return getSokletConfig().getResponseMarshaler().forHead(request, marshaledResponse);
	}

	// Hat tip to Aslan Parçası and GrayStar
	@NonNull
	protected MarshaledResponse applyCommonPropertiesToMarshaledResponse(@NonNull Request request,
																																			 @NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		return applyCommonPropertiesToMarshaledResponse(request, marshaledResponse, false);
	}

	protected void handleMcpRequest(@NonNull Request request,
																	@NonNull Consumer<HttpRequestResult> requestResultConsumer) {
		requireNonNull(request);
		requireNonNull(requestResultConsumer);

		requestResultConsumer.accept(this.defaultMcpRuntime.handleRequest(request));
	}

	protected void handleSimulatedMcpStreamDisconnect(@NonNull Request request,
																										@NonNull String sessionId) {
		requireNonNull(request);
		requireNonNull(sessionId);

		this.defaultMcpRuntime.handleClientDisconnectedStream(request, sessionId);
	}

	@NonNull
	protected MarshaledResponse applyCommonPropertiesToMarshaledResponse(@NonNull Request request,
																																			 @NonNull MarshaledResponse marshaledResponse,
																																			 @NonNull Boolean suppressContentLength) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(suppressContentLength);

		// Don't write Content-Length for an accepted SSE Handshake, for example
		if (!suppressContentLength)
			marshaledResponse = applyContentLengthIfApplicable(request, marshaledResponse);

		// If the Date header is missing, add it using our cached provider
		if (!marshaledResponse.getHeaders().containsKey("Date"))
			marshaledResponse = marshaledResponse.copy()
					.headers(headers -> headers.put("Date", Set.of(HttpDate.currentSecondHeaderValue())))
					.finish();

		marshaledResponse = applyCorsResponseIfApplicable(request, marshaledResponse);

		return marshaledResponse;
	}

	@NonNull
	protected MarshaledResponse applyContentLengthIfApplicable(@NonNull Request request,
																														 @NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		if (marshaledResponse.isStreaming())
			return marshaledResponse;

		Set<String> normalizedHeaderNames = marshaledResponse.getHeaders().keySet().stream()
				.map(headerName -> headerName.toLowerCase(Locale.US))
				.collect(Collectors.toSet());

		// If Content-Length is already specified, don't do anything
		if (normalizedHeaderNames.contains("content-length") || normalizedHeaderNames.contains("transfer-encoding"))
			return marshaledResponse;

		// If Content-Length is not specified, specify as the number of bytes in the body
		return marshaledResponse.copy()
				.headers((mutableHeaders) -> {
					String contentLengthHeaderValue = String.valueOf(marshaledResponse.getBodyLength());
					mutableHeaders.put("Content-Length", Set.of(contentLengthHeaderValue));
				}).finish();
	}

	@NonNull
	protected MarshaledResponse applyCorsResponseIfApplicable(@NonNull Request request,
																														@NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		Cors cors = request.getCors().orElse(null);

		// If non-CORS request, nothing further to do (note that CORS preflight was handled earlier)
		if (cors == null)
			return marshaledResponse;

		CorsAuthorizer corsAuthorizer = getSokletConfig().getCorsAuthorizer();

		// Does the authorizer say we are authorized?
		CorsResponse corsResponse = corsAuthorizer.authorize(request, cors).orElse(null);

		// Not authorized - don't apply CORS headers to the response
		if (corsResponse == null)
			return marshaledResponse;

		// Authorized - OK, let's apply the headers to the response
		return getSokletConfig().getResponseMarshaler().forCorsAllowed(request, cors, corsResponse, marshaledResponse);
	}

	@NonNull
	protected Map<@NonNull HttpMethod, @NonNull ResourceMethod> resolveMatchingResourceMethodsByHttpMethod(@NonNull Request request,
																																																				 @NonNull ResourceMethodResolver resourceMethodResolver,
																																																				 @NonNull ServerType serverType) {
		requireNonNull(request);
		requireNonNull(resourceMethodResolver);
		requireNonNull(serverType);

		// Special handling for OPTIONS *
		if (request.getResourcePath() == ResourcePath.OPTIONS_SPLAT_RESOURCE_PATH)
			return new LinkedHashMap<>();

		Map<HttpMethod, ResourceMethod> matchingResourceMethodsByHttpMethod = new LinkedHashMap<>(HttpMethod.values().length);

		for (HttpMethod httpMethod : HttpMethod.values()) {
			// Make a quick copy of the request to see if other paths match
			Request otherRequest = Request.withPath(httpMethod, request.getPath()).build();
			ResourceMethod resourceMethod = resourceMethodResolver.resourceMethodForRequest(otherRequest, serverType).orElse(null);

			if (resourceMethod != null)
				matchingResourceMethodsByHttpMethod.put(httpMethod, resourceMethod);
		}

		return matchingResourceMethodsByHttpMethod;
	}

	@NonNull
	private static Set<@NonNull HttpMethod> allowedHttpMethodsForResponse(@NonNull Map<@NonNull HttpMethod, @NonNull ResourceMethod> matchingResourceMethodsByHttpMethod,
																																				@NonNull Boolean includeOptions) {
		requireNonNull(matchingResourceMethodsByHttpMethod);
		requireNonNull(includeOptions);

		Set<HttpMethod> allowedHttpMethods = EnumSet.noneOf(HttpMethod.class);
		allowedHttpMethods.addAll(matchingResourceMethodsByHttpMethod.keySet());

		if (includeOptions)
			allowedHttpMethods.add(HttpMethod.OPTIONS);

		if (matchingResourceMethodsByHttpMethod.containsKey(HttpMethod.GET) || matchingResourceMethodsByHttpMethod.containsKey(HttpMethod.HEAD))
			allowedHttpMethods.add(HttpMethod.HEAD);

		return allowedHttpMethods;
	}

	@NonNull
	protected MarshaledResponse provideFailsafeMarshaledResponse(@NonNull Request request,
																															 @NonNull Throwable throwable) {
		requireNonNull(request);
		requireNonNull(throwable);

		Integer statusCode = 500;
		Charset charset = StandardCharsets.UTF_8;

		return MarshaledResponse.withStatusCode(statusCode)
				.headers(Map.of("Content-Type", Set.of(format("text/plain; charset=%s", charset.name()))))
				.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(charset))
				.build();
	}

	/**
	 * Synonym for {@link #stop()}.
	 */
	@Override
	public void close() {
		stop();
	}

	/**
	 * Is any managed transport server started?
	 *
	 * @return {@code true} if at least one configured transport server is started, {@code false} otherwise
	 */
	@NonNull
	public Boolean isStarted() {
		getLock().lock();

		try {
			HttpServer httpServer = getSokletConfig().getHttpServer().orElse(null);

			if (httpServer != null && httpServer.isStarted())
				return true;

			SseServer sseServer = getSokletConfig().getSseServer().orElse(null);
			if (sseServer != null && sseServer.isStarted())
				return true;

			McpServer mcpServer = getSokletConfig().getMcpServer().orElse(null);
			return mcpServer != null && mcpServer.isStarted();
		} finally {
			getLock().unlock();
		}
	}

	/**
	 * Runs Soklet with special non-network "simulator" implementations of the configured transport servers - useful for integration testing.
	 * <p>
	 * See <a href="https://www.soklet.com/docs/testing">https://www.soklet.com/docs/testing</a> for how to write these tests.
	 *
	 * @param sokletConfig      configuration that drives the Soklet system
	 * @param simulatorConsumer code to execute within the context of the simulator
	 */
	public static void runSimulator(@NonNull SokletConfig sokletConfig,
																	@NonNull Consumer<Simulator> simulatorConsumer) {
		runSimulator(sokletConfig, SimulatorOptions.defaultInstance(), simulatorConsumer);
	}

	/**
	 * Runs Soklet with special non-network "simulator" implementations of the configured transport servers - useful for integration testing.
	 * <p>
	 * See <a href="https://www.soklet.com/docs/testing">https://www.soklet.com/docs/testing</a> for how to write these tests.
	 *
	 * @param sokletConfig      configuration that drives the Soklet system
	 * @param simulatorOptions  simulator behavior options
	 * @param simulatorConsumer code to execute within the context of the simulator
	 */
	public static void runSimulator(@NonNull SokletConfig sokletConfig,
																	@NonNull SimulatorOptions simulatorOptions,
																	@NonNull Consumer<Simulator> simulatorConsumer) {
		requireNonNull(sokletConfig);
		requireNonNull(simulatorOptions);
		requireNonNull(simulatorConsumer);

		// Create Soklet instance - this initializes the REAL implementations through proxies
		Soklet soklet = Soklet.fromConfig(sokletConfig);

		// Extract proxies (they're guaranteed to be proxies now)
		HttpServerProxy serverProxy = sokletConfig.getHttpServer()
				.map(server -> (HttpServerProxy) server)
				.orElse(null);
		SseServerProxy sseServerProxy = sokletConfig.getSseServer()
				.map(s -> (SseServerProxy) s)
				.orElse(null);
		McpServerProxy mcpServerProxy = sokletConfig.getMcpServer()
				.map(mcpServer -> (McpServerProxy) mcpServer)
				.orElse(null);

		// Create mock implementations
		MockHttpServer mockServer = serverProxy == null ? null : new MockHttpServer();
		MockSseServer mockSseServer = new MockSseServer();
		MockMcpServer mockMcpServer = mcpServerProxy == null ? null : new MockMcpServer(mcpServerProxy.getRealImplementation());

		// Switch proxies to simulator mode
		if (serverProxy != null)
			serverProxy.enableSimulatorMode(mockServer);

		if (sseServerProxy != null)
			sseServerProxy.enableSimulatorMode(mockSseServer);

		if (mcpServerProxy != null)
			mcpServerProxy.enableSimulatorMode(mockMcpServer);

		try {
			// Initialize mocks with request handlers that delegate to Soklet's processing
				if (mockServer != null)
					mockServer.initialize(sokletConfig, (request, marshaledResponseConsumer) -> {
						// Delegate to Soklet's internal request handling
						soklet.handleRequest(request, ServerType.STANDARD_HTTP, marshaledResponseConsumer);
					});

			if (mockSseServer != null)
				mockSseServer.initialize(sokletConfig, (request, marshaledResponseConsumer) -> {
					// Delegate to Soklet's internal request handling for SSE
					soklet.handleRequest(request, ServerType.SSE, marshaledResponseConsumer);
				});

			if (mockMcpServer != null)
				mockMcpServer.initialize(sokletConfig, soklet::handleMcpRequest);

			if (mockMcpServer != null)
				mockMcpServer.onClientDisconnectedMcpStream(soklet::handleSimulatedMcpStreamDisconnect);

			// Create and provide simulator
			Simulator simulator = new DefaultSimulator(mockServer, mockSseServer, mockMcpServer, simulatorOptions);
			simulatorConsumer.accept(simulator);
		} finally {
			// Always restore to real implementations
			if (serverProxy != null)
				serverProxy.disableSimulatorMode();

			if (sseServerProxy != null)
				sseServerProxy.disableSimulatorMode();

			if (mcpServerProxy != null)
				mcpServerProxy.disableSimulatorMode();
		}
	}

	@NonNull
	protected SokletConfig getSokletConfig() {
		return this.sokletConfig;
	}

	@NonNull
	protected ReentrantLock getLock() {
		return this.lock;
	}

	@NonNull
	protected AtomicReference<CountDownLatch> getAwaitShutdownLatchReference() {
		return this.awaitShutdownLatchReference;
	}

	@ThreadSafe
	static class DefaultSimulator implements Simulator {
		@Nullable
		private MockHttpServer server;
		@Nullable
		private MockSseServer sseServer;
		@Nullable
		private MockMcpServer mcpServer;
		@NonNull
		private final SimulatorOptions simulatorOptions;

		public DefaultSimulator(@Nullable MockHttpServer server,
														@Nullable MockSseServer sseServer,
														@Nullable MockMcpServer mcpServer) {
			this(server, sseServer, mcpServer, SimulatorOptions.defaultInstance());
		}

		public DefaultSimulator(@Nullable MockHttpServer server,
														@Nullable MockSseServer sseServer,
														@Nullable MockMcpServer mcpServer,
														@NonNull SimulatorOptions simulatorOptions) {
			this.server = server;
			this.sseServer = sseServer;
			this.mcpServer = mcpServer;
			this.simulatorOptions = requireNonNull(simulatorOptions);
		}

		@NonNull
		@Override
		public HttpRequestResult performHttpRequest(@NonNull Request request) {
			MockHttpServer server = getHttpServer().orElse(null);

			if (server == null)
				throw new IllegalStateException(format("You must specify a %s in your %s to simulate requests",
						HttpServer.class.getSimpleName(), SokletConfig.class.getSimpleName()));

			AtomicReference<HttpRequestResult> requestResultHolder = new AtomicReference<>();
			HttpServer.RequestHandler requestHandler = server.getRequestHandler().orElse(null);

			if (requestHandler == null)
				throw new IllegalStateException("You must register a request handler prior to simulating requests");

			requestHandler.handleRequest(request, (requestResult -> {
				requestResultHolder.set(requestResult);
			}));

			return materializeStreamingResponse(request, requestResultHolder.get());
		}

		@NonNull
		private HttpRequestResult materializeStreamingResponse(@NonNull Request request,
																													 @Nullable HttpRequestResult requestResult) {
			requireNonNull(request);

			if (requestResult == null)
				throw new IllegalStateException("No HTTP request result was produced by the simulator");

			StreamingResponseBody stream = requestResult.getMarshaledResponse().getStream().orElse(null);

			if (stream == null)
				return requestResult;

			byte[] bytes;
			Instant streamStarted = Instant.now();

			try {
				bytes = materializeStreamingResponseBody(request, requestResult, stream);
				notifyDidTerminateSimulatorResponseStream(request, requestResult, streamStarted,
						Duration.between(streamStarted, Instant.now()), null, null);
			} catch (StreamingResponseCanceledException e) {
				StreamTerminationReason cancelationReason = e.getCancelationReason();
				Throwable cause = e.getCancelationCause().orElse(null);
				notifyDidTerminateSimulatorResponseStream(request, requestResult, streamStarted,
						Duration.between(streamStarted, Instant.now()), cancelationReason, cause);
				throw new IllegalStateException("Simulated streaming response was canceled: " + cancelationReason.name(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				notifyDidTerminateSimulatorResponseStream(request, requestResult, streamStarted,
						Duration.between(streamStarted, Instant.now()), StreamTerminationReason.CLIENT_DISCONNECTED, e);
				throw new IllegalStateException("Simulated streaming response was canceled: CLIENT_DISCONNECTED", e);
			} catch (Throwable t) {
				notifyDidTerminateSimulatorResponseStream(request, requestResult, streamStarted,
						Duration.between(streamStarted, Instant.now()), StreamTerminationReason.PRODUCER_FAILED, t);

				if (t instanceof Error error)
					throw error;

				throw new IllegalStateException("Simulated streaming response failed.", t);
			}

			MarshaledResponse marshaledResponse = requestResult.getMarshaledResponse().copy()
					.withoutStream()
					.body(bytes)
					.finish();

			return requestResult.copy()
					.marshaledResponse(marshaledResponse)
					.finish();
		}

		private void notifyDidTerminateSimulatorResponseStream(@NonNull Request request,
																													 @NonNull HttpRequestResult requestResult,
																													 @NonNull Instant establishedAt,
																													 @NonNull Duration streamDuration,
																													 @Nullable StreamTerminationReason cancelationReason,
																													 @Nullable Throwable throwable) {
			requireNonNull(request);
			requireNonNull(requestResult);
			requireNonNull(establishedAt);
			requireNonNull(streamDuration);

			MockHttpServer server = getHttpServer().orElse(null);
			SokletConfig sokletConfig = server == null ? null : server.getSokletConfig().orElse(null);

			if (sokletConfig == null)
				return;

			MarshaledResponse marshaledResponse = requestResult.getMarshaledResponse();
			ResourceMethod resourceMethod = requestResult.getResourceMethod().orElse(null);
			LifecycleObserver lifecycleObserver = sokletConfig.getAggregateLifecycleObserver();
			StreamingResponseHandle streamingResponse = new DefaultStreamingResponseHandle(ServerType.STANDARD_HTTP,
					request, resourceMethod, marshaledResponse, establishedAt);
			StreamTermination termination = StreamTermination
					.with(cancelationReason == null ? StreamTerminationReason.COMPLETED : cancelationReason, streamDuration)
					.cause(throwable)
					.build();

			try {
				lifecycleObserver.willTerminateResponseStream(streamingResponse, termination);
			} catch (Throwable t) {
				try {
					lifecycleObserver.didReceiveLogEvent(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_TERMINATE_RESPONSE_STREAM_FAILED,
									format("An exception occurred while invoking %s::willTerminateResponseStream", LifecycleObserver.class.getSimpleName()))
							.throwable(t)
							.request(request)
							.resourceMethod(resourceMethod)
							.marshaledResponse(marshaledResponse)
							.build());
				} catch (Throwable ignored) {
					// Keep simulator lifecycle observer failures contained.
				}
			}

			try {
				lifecycleObserver.didTerminateResponseStream(streamingResponse, termination);
			} catch (Throwable t) {
				try {
					lifecycleObserver.didReceiveLogEvent(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_TERMINATE_RESPONSE_STREAM_FAILED,
									format("An exception occurred while invoking %s::didTerminateResponseStream", LifecycleObserver.class.getSimpleName()))
							.throwable(t)
							.request(request)
							.resourceMethod(resourceMethod)
							.marshaledResponse(marshaledResponse)
							.build());
				} catch (Throwable ignored) {
					// Keep simulator lifecycle observer failures contained.
				}
			}
		}

		private void notifyDidReceiveSimulatorStreamCancelationCallbackFailure(@NonNull Request request,
																																					@NonNull HttpRequestResult requestResult,
																																					@NonNull Throwable throwable) {
			requireNonNull(request);
			requireNonNull(requestResult);
			requireNonNull(throwable);

			MockHttpServer server = getHttpServer().orElse(null);
			SokletConfig sokletConfig = server == null ? null : server.getSokletConfig().orElse(null);

			if (sokletConfig == null)
				return;

			LifecycleObserver lifecycleObserver = sokletConfig.getAggregateLifecycleObserver();
			ResourceMethod resourceMethod = requestResult.getResourceMethod().orElse(null);
			MarshaledResponse marshaledResponse = requestResult.getMarshaledResponse();

			try {
				lifecycleObserver.didReceiveLogEvent(LogEvent.with(LogEventType.RESPONSE_STREAM_CANCELATION_CALLBACK_FAILED,
								"An exception occurred while invoking a streaming response cancelation callback")
						.throwable(throwable)
						.request(request)
						.resourceMethod(resourceMethod)
						.marshaledResponse(marshaledResponse)
						.build());
			} catch (Throwable ignored) {
				// Keep simulator lifecycle observer failures contained.
			}
		}

		@NonNull
		private byte[] materializeStreamingResponseBody(@NonNull Request request,
																										@NonNull HttpRequestResult requestResult,
																										@NonNull StreamingResponseBody stream) throws Exception {
			requireNonNull(request);
			requireNonNull(requestResult);
			requireNonNull(stream);

			SimulatorCancelationToken cancelationToken = new SimulatorCancelationToken(throwable ->
					notifyDidReceiveSimulatorStreamCancelationCallbackFailure(request, requestResult, throwable));
			SimulatorStreamingResponseContext context = new SimulatorStreamingResponseContext(request, cancelationToken);
			SimulatorResponseStream output = new SimulatorResponseStream(getSimulatorOptions().getStreamingResponseBodyLimitInBytes(), cancelationToken);

			try {
				if (stream instanceof StreamingResponseBody.WriterBody writerBody) {
					writerBody.getWriter().writeTo(output, context);
				} else if (stream instanceof StreamingResponseBody.InputStreamBody inputStreamBody) {
					try (java.io.InputStream inputStream = requireNonNull(inputStreamBody.getInputStreamSupplier().get());
							 AutoCloseable ignored = context.onCancel(() -> closeQuietly(inputStream))) {
						byte[] buffer = new byte[inputStreamBody.getBufferSizeInBytes()];
						int read;

						while ((read = inputStream.read(buffer)) >= 0) {
							context.throwIfCanceled();
							if (read > 0)
								output.write(ByteBuffer.wrap(buffer, 0, read));
						}
					}
				} else if (stream instanceof StreamingResponseBody.ReaderBody readerBody) {
					try (java.io.Reader reader = requireNonNull(readerBody.getReaderSupplier().get());
							 AutoCloseable ignored = context.onCancel(() -> closeQuietly(reader))) {
						materializeReader(readerBody, reader, output, context);
					}
				} else if (stream instanceof StreamingResponseBody.PublisherBody publisherBody) {
					materializePublisher(publisherBody, output, context);
				} else {
					throw new IllegalStateException(format("Unsupported streaming response body type: %s", stream.getClass().getName()));
				}
			} catch (StreamingResponseCanceledException e) {
				cancelationToken.cancel(e.getCancelationReason(), e.getCancelationCause().orElse(null));
				throw e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				cancelationToken.cancel(cancelationToken.getCancelationReason()
						.orElse(StreamTerminationReason.CLIENT_DISCONNECTED), e);
				throw e;
			} catch (Throwable t) {
				cancelationToken.cancel(StreamTerminationReason.PRODUCER_FAILED, t);

				if (t instanceof Exception exception)
					throw exception;

				if (t instanceof Error error)
					throw error;

				throw new RuntimeException(t);
			}

			return output.toByteArray();
		}

		private void materializeReader(com.soklet.StreamingResponseBody.@NonNull ReaderBody readerBody,
																	 @NonNull Reader reader,
																	 @NonNull SimulatorResponseStream output,
																	 @NonNull SimulatorStreamingResponseContext context) throws IOException, InterruptedException, StreamingResponseCanceledException, CharacterCodingException {
			requireNonNull(readerBody);
			requireNonNull(reader);
			requireNonNull(output);
			requireNonNull(context);

			CharsetEncoder encoder = readerBody.newEncoder();
			CharBuffer charBuffer = CharBuffer.allocate(readerBody.getBufferSizeInCharacters());
			ByteBuffer byteBuffer = ByteBuffer.allocate(Math.max(128, (int) Math.ceil(readerBody.getBufferSizeInCharacters() * encoder.maxBytesPerChar())));

			while (reader.read(charBuffer) >= 0) {
				context.throwIfCanceled();
				charBuffer.flip();
				encodeCharsForSimulator(encoder, charBuffer, byteBuffer, false, output);
				charBuffer.compact();
			}

			charBuffer.flip();
			encodeCharsForSimulator(encoder, charBuffer, byteBuffer, true, output);

			CoderResult result;
			do {
				result = encoder.flush(byteBuffer);
				writeEncodedBytesForSimulator(byteBuffer, output);
				if (result.isError())
					result.throwException();
			} while (result.isOverflow());
		}

		private void encodeCharsForSimulator(@NonNull CharsetEncoder encoder,
																				 @NonNull CharBuffer charBuffer,
																				 @NonNull ByteBuffer byteBuffer,
																				 boolean endOfInput,
																				 @NonNull SimulatorResponseStream output) throws IOException, InterruptedException, StreamingResponseCanceledException, CharacterCodingException {
			CoderResult result;

			do {
				result = encoder.encode(charBuffer, byteBuffer, endOfInput);
				writeEncodedBytesForSimulator(byteBuffer, output);

				if (result.isError())
					result.throwException();
			} while (result.isOverflow());
		}

		private void writeEncodedBytesForSimulator(@NonNull ByteBuffer byteBuffer,
																							 @NonNull SimulatorResponseStream output) throws IOException, InterruptedException, StreamingResponseCanceledException {
			byteBuffer.flip();
			if (byteBuffer.hasRemaining())
				output.write(byteBuffer);
			byteBuffer.clear();
		}

		private void materializePublisher(com.soklet.StreamingResponseBody.@NonNull PublisherBody publisherBody,
																			@NonNull SimulatorResponseStream output,
																			@NonNull SimulatorStreamingResponseContext context) throws Exception {
			requireNonNull(publisherBody);
			requireNonNull(output);
			requireNonNull(context);

			CountDownLatch completed = new CountDownLatch(1);
			AtomicBoolean publisherTerminated = new AtomicBoolean(false);
			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

			try (AutoCloseable cancelationRegistration = context.onCancel(() -> {
				Flow.Subscription subscription = subscriptionRef.get();

				if (subscription != null)
					subscription.cancel();
			})) {
				publisherBody.getPublisher().subscribe(new Flow.Subscriber<>() {
					@Override
					public void onSubscribe(Flow.Subscription subscription) {
						requireNonNull(subscription);

						if (!subscriptionRef.compareAndSet(null, subscription)) {
							subscription.cancel();
							return;
						}

						subscription.request(1L);
					}

					@Override
					public void onNext(ByteBuffer item) {
						Flow.Subscription subscription = subscriptionRef.get();

						try {
							context.throwIfCanceled();
							output.write(requireNonNull(item));
							context.throwIfCanceled();
						} catch (Throwable t) {
							failure.compareAndSet(null, t);
							publisherTerminated.set(true);

							if (subscription != null)
								subscription.cancel();

							completed.countDown();
							return;
						}

						if (subscription != null)
							subscription.request(1L);
					}

					@Override
					public void onError(Throwable throwable) {
						publisherTerminated.set(true);
						failure.compareAndSet(null, throwable == null
								? new IllegalStateException("Publisher failed without an error")
								: throwable);
						completed.countDown();
					}

					@Override
					public void onComplete() {
						publisherTerminated.set(true);
						completed.countDown();
					}
				});

				while (!completed.await(100L, TimeUnit.MILLISECONDS))
					context.throwIfCanceled();
			} finally {
				if (!publisherTerminated.get()) {
					Flow.Subscription subscription = subscriptionRef.get();

					if (subscription != null)
						subscription.cancel();
				}
			}

			Throwable throwable = failure.get();

			if (throwable != null) {
				if (throwable instanceof Exception exception)
					throw exception;

				if (throwable instanceof Error error)
					throw error;

				throw new RuntimeException(throwable);
			}
		}

		private void closeQuietly(@NonNull AutoCloseable closeable) {
			requireNonNull(closeable);

			try {
				closeable.close();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Throwable ignored) {
				// Best effort only. The producer will observe cancelation separately.
			}
		}

		@NonNull
		@Override
		public SseRequestResult performSseRequest(@NonNull Request request) {
			MockSseServer sseServer = getSseServer().orElse(null);

			if (sseServer == null)
				throw new IllegalStateException(format("You must specify a %s in your %s to simulate Server-Sent Event requests",
						SseServer.class.getSimpleName(), SokletConfig.class.getSimpleName()));

			AtomicReference<HttpRequestResult> requestResultHolder = new AtomicReference<>();
			SseServer.RequestHandler requestHandler = sseServer.getRequestHandler().orElse(null);

			if (requestHandler == null)
				throw new IllegalStateException("You must register a request handler prior to simulating SSE Event Source requests");

			requestHandler.handleRequest(request, (requestResult -> {
				requestResultHolder.set(requestResult);
			}));

			HttpRequestResult requestResult = requestResultHolder.get();
			SseHandshakeResult sseHandshakeResult = requestResult.getSseHandshakeResult().orElse(null);

			if (sseHandshakeResult == null)
				return new SseRequestResult.RequestFailed(requestResult);

			if (sseHandshakeResult instanceof SseHandshakeResult.Accepted acceptedHandshake) {
				Consumer<SseUnicaster> clientInitializer = acceptedHandshake.getClientInitializer().orElse(null);

				// Create a synthetic logical response using values from the accepted handshake
				if (requestResult.getResponse().isEmpty())
					requestResult = requestResult.copy()
							.response(Response.withStatusCode(200)
									.headers(acceptedHandshake.getHeaders())
									.cookies(acceptedHandshake.getCookies())
									.build())
							.finish();

				HandshakeAccepted handshakeAccepted = new HandshakeAccepted(acceptedHandshake, request.getResourcePath(), requestResult, this, clientInitializer);
				return handshakeAccepted;
			}

			if (sseHandshakeResult instanceof SseHandshakeResult.Rejected rejectedHandshake)
				return new HandshakeRejected(rejectedHandshake, requestResult);

			throw new IllegalStateException(format("Encountered unexpected %s: %s", SseHandshakeResult.class.getSimpleName(), sseHandshakeResult));
		}

		@NonNull
		@Override
		public McpRequestResult performMcpRequest(@NonNull Request request) {
			requireNonNull(request);

			MockMcpServer mcpServer = getMcpServer().orElse(null);

			if (mcpServer == null)
				throw new IllegalStateException(format("You must specify an MCP server in your %s to simulate MCP requests",
						SokletConfig.class.getSimpleName()));

			AtomicReference<HttpRequestResult> requestResultHolder = new AtomicReference<>();
			McpServer.RequestHandler requestHandler = mcpServer.getRequestHandler().orElse(null);

			if (requestHandler == null)
				throw new IllegalStateException("You must register a request handler prior to simulating MCP requests");

			requestHandler.handleRequest(request, requestResultHolder::set);

			HttpRequestResult requestResult = requestResultHolder.get();

			if (requestResult == null)
				throw new IllegalStateException("No MCP request result was produced by the simulator");

			if (extractContentTypeFromHeaders(requestResult.getMarshaledResponse().getHeaders())
					.filter(contentType -> contentType.equalsIgnoreCase("text/event-stream"))
					.isPresent()) {
				McpRequestResult.StreamOpened streamOpened = new McpRequestResult.StreamOpened(
						requestResult,
						mcpServer.getMcpStreamErrorHandler(),
						requestResult.isMcpStreamClosedAfterReplay());

				for (McpObject mcpStreamMessage : requestResult.getMcpStreamMessages())
					streamOpened.emitMessage(mcpStreamMessage);

				if (!requestResult.isMcpStreamClosedAfterReplay())
					request.getHeader("MCP-Session-Id").ifPresent(sessionId -> mcpServer.registerOpenStream(sessionId, request, streamOpened));

				return streamOpened;
			}

			if (request.getHttpMethod() == HttpMethod.DELETE
					&& Objects.equals(requestResult.getMarshaledResponse().getStatusCode(), 204))
				request.getHeader("MCP-Session-Id").ifPresent(mcpServer::terminateStreamsForSession);

			return new McpRequestResult.ResponseCompleted(requestResult);
		}

		@NonNull
		@Override
		public Simulator onBroadcastError(@Nullable Consumer<Throwable> onBroadcastError) {
			MockSseServer sseServer = getSseServer().orElse(null);

			if (sseServer != null)
				sseServer.onBroadcastError(onBroadcastError);

			return this;
		}

		@NonNull
		@Override
		public Simulator onUnicastError(@Nullable Consumer<Throwable> onUnicastError) {
			MockSseServer sseServer = getSseServer().orElse(null);

			if (sseServer != null)
				sseServer.onUnicastError(onUnicastError);

			return this;
		}

		@NonNull
		@Override
		public Simulator onMcpStreamError(@Nullable Consumer<Throwable> onMcpStreamError) {
			MockMcpServer mcpServer = getMcpServer().orElse(null);

			if (mcpServer != null)
				mcpServer.onMcpStreamError(onMcpStreamError);

			return this;
		}

		@NonNull
		Optional<MockHttpServer> getHttpServer() {
			return Optional.ofNullable(this.server);
		}

		@NonNull
		Optional<MockSseServer> getSseServer() {
			return Optional.ofNullable(this.sseServer);
		}

		@NonNull
		Optional<MockMcpServer> getMcpServer() {
			return Optional.ofNullable(this.mcpServer);
		}

		@NonNull
		SimulatorOptions getSimulatorOptions() {
			return this.simulatorOptions;
		}
	}

	@NotThreadSafe
	private static final class SimulatorResponseStream implements ResponseStream {
		@NonNull
		private final ByteArrayOutputStream byteArrayOutputStream;
		@NonNull
		private final Integer limitInBytes;
		@NonNull
		private final SimulatorCancelationToken cancelationToken;
		private boolean closed;

		private SimulatorResponseStream(@NonNull Integer limitInBytes,
																		@NonNull SimulatorCancelationToken cancelationToken) {
			this.byteArrayOutputStream = new ByteArrayOutputStream();
			this.limitInBytes = requireNonNull(limitInBytes);
			this.cancelationToken = requireNonNull(cancelationToken);
		}

		@Override
		public void write(@NonNull byte[] bytes) throws IOException, StreamingResponseCanceledException {
			requireNonNull(bytes);
			write(ByteBuffer.wrap(bytes));
		}

		@Override
		public void write(@NonNull ByteBuffer byteBuffer) throws IOException, StreamingResponseCanceledException {
			requireNonNull(byteBuffer);
			this.cancelationToken.throwIfCanceled();

			if (this.closed)
				throw new StreamingResponseCanceledException(StreamTerminationReason.APPLICATION_CANCELED);

			ByteBuffer source = byteBuffer.asReadOnlyBuffer();
			int bytesToWrite = source.remaining();

			if ((long) this.byteArrayOutputStream.size() + bytesToWrite > this.limitInBytes) {
				this.cancelationToken.cancel(StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED, null);
				throw new StreamingResponseCanceledException(StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED);
			}

			byte[] bytes = new byte[bytesToWrite];
			source.get(bytes);
			this.byteArrayOutputStream.write(bytes);
		}

		@Override
		public void flush() throws StreamingResponseCanceledException {
			this.cancelationToken.throwIfCanceled();
		}

		@Override
		@NonNull
		public Boolean isOpen() {
			return !this.closed && !this.cancelationToken.isCanceled();
		}

		@NonNull
		private byte[] toByteArray() {
			this.closed = true;
			return this.byteArrayOutputStream.toByteArray();
		}
	}

	@ThreadSafe
	private static final class SimulatorCancelationToken implements CancelationToken {
		@NonNull
		private final AtomicBoolean canceled;
		@NonNull
		private final CopyOnWriteArrayList<Runnable> callbacks;
		@NonNull
		private final Consumer<Throwable> callbackFailureConsumer;
		@Nullable
		private volatile StreamTerminationReason reason;
		@Nullable
		private volatile Throwable cause;

		private SimulatorCancelationToken(@NonNull Consumer<Throwable> callbackFailureConsumer) {
			this.canceled = new AtomicBoolean(false);
			this.callbacks = new CopyOnWriteArrayList<>();
			this.callbackFailureConsumer = requireNonNull(callbackFailureConsumer);
		}

		@Override
		@NonNull
		public Boolean isCanceled() {
			return this.canceled.get();
		}

		@Override
		@NonNull
		public Optional<StreamTerminationReason> getCancelationReason() {
			return Optional.ofNullable(this.reason);
		}

		@Override
		@NonNull
		public Optional<Throwable> getCancelationCause() {
			return Optional.ofNullable(this.cause);
		}

		@Override
		@NonNull
		public AutoCloseable onCancel(@NonNull Runnable callback) {
			requireNonNull(callback);

			boolean runImmediately;

			synchronized (this) {
				runImmediately = this.canceled.get();

				if (!runImmediately)
					this.callbacks.add(callback);
			}

			if (runImmediately) {
				runCallback(callback);
				return () -> {
					// No-op
				};
			}

			return () -> {
				synchronized (this) {
					this.callbacks.remove(callback);
				}
			};
		}

		private boolean cancel(@NonNull StreamTerminationReason reason,
													 @Nullable Throwable cause) {
			requireNonNull(reason);

			if (reason == StreamTerminationReason.COMPLETED)
				throw new IllegalArgumentException("Cancelation reason cannot be COMPLETED");

			List<Runnable> callbacksToRun;

			synchronized (this) {
				if (this.canceled.get())
					return false;

				this.reason = reason;
				this.cause = cause;
				this.canceled.set(true);
				callbacksToRun = List.copyOf(this.callbacks);
				this.callbacks.clear();
			}

			for (Runnable callback : callbacksToRun)
				runCallback(callback);

			return true;
		}

		private void runCallback(@NonNull Runnable callback) {
			requireNonNull(callback);

			try {
				callback.run();
			} catch (Throwable t) {
				this.callbackFailureConsumer.accept(t);
			}
		}
	}

	@ThreadSafe
	private static final class SimulatorStreamingResponseContext implements StreamingResponseContext {
		@NonNull
		private final Request request;
		@NonNull
		private final CancelationToken cancelationToken;

		private SimulatorStreamingResponseContext(@NonNull Request request,
																							@NonNull CancelationToken cancelationToken) {
			this.request = requireNonNull(request);
			this.cancelationToken = requireNonNull(cancelationToken);
		}

		@Override
		@NonNull
		public CancelationToken getCancelationToken() {
			return this.cancelationToken;
		}

		@Override
		@NonNull
		public Request getRequest() {
			return this.request;
		}

		@Override
		@NonNull
		public Optional<Instant> getDeadline() {
			return Optional.empty();
		}

		@Override
		@NonNull
		public Optional<Duration> getIdleTimeout() {
			return Optional.empty();
		}
	}

	/**
	 * Mock server that doesn't touch the network at all, useful for testing.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	static class MockHttpServer implements HttpServer {
		@Nullable
		private SokletConfig sokletConfig;
		private HttpServer.@Nullable RequestHandler requestHandler;

		@Override
		public void start() {
			// No-op
		}

		@Override
		public void stop() {
			// No-op
		}

		@NonNull
		@Override
		public Boolean isStarted() {
			return true;
		}

		@Override
			public void initialize(@NonNull SokletConfig sokletConfig,
														 @NonNull RequestHandler requestHandler) {
				requireNonNull(sokletConfig);
				requireNonNull(requestHandler);

				this.sokletConfig = sokletConfig;
				this.requestHandler = requestHandler;
			}

		@NonNull
		protected Optional<SokletConfig> getSokletConfig() {
			return Optional.ofNullable(this.sokletConfig);
		}

		@NonNull
		protected Optional<RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}
	}

	/**
	 * Mock MCP server that doesn't touch the network at all, useful for testing.
	 */
	@ThreadSafe
	static class MockMcpServer implements McpServer, InternalMcpSessionMessagePublisher {
		@NonNull
		private final McpServer realImplementation;
		@Nullable
		private SokletConfig sokletConfig;
		private McpServer.@Nullable RequestHandler requestHandler;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> mcpStreamErrorHandler;
		@NonNull
		private final ConcurrentHashMap<@NonNull String, @NonNull CopyOnWriteArrayList<McpRequestResult.StreamOpened>> openStreamsBySessionId;
		@NonNull
		private final AtomicReference<@Nullable BiConsumer<Request, String>> clientDisconnectedMcpStreamHandler;

		public MockMcpServer(@NonNull McpServer realImplementation) {
			requireNonNull(realImplementation);

			this.realImplementation = realImplementation;
			this.mcpStreamErrorHandler = new AtomicReference<>();
			this.openStreamsBySessionId = new ConcurrentHashMap<>();
			this.clientDisconnectedMcpStreamHandler = new AtomicReference<>();
		}

		@Override
		public void start() {
			// No-op
		}

		@Override
		public void stop() {
			// No-op
		}

		@NonNull
		@Override
		public Boolean isStarted() {
			return true;
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
													 @NonNull RequestHandler requestHandler) {
			requireNonNull(sokletConfig);
			requireNonNull(requestHandler);

			this.sokletConfig = sokletConfig;
			this.requestHandler = requestHandler;
		}

		@NonNull
		@Override
		public McpHandlerResolver getHandlerResolver() {
			return getRealImplementation().getHandlerResolver();
		}

		@NonNull
		@Override
		public McpRequestAdmissionPolicy getRequestAdmissionPolicy() {
			return getRealImplementation().getRequestAdmissionPolicy();
		}

		@NonNull
		@Override
		public McpRequestInterceptor getRequestInterceptor() {
			return getRealImplementation().getRequestInterceptor();
		}

		@NonNull
		@Override
		public McpResponseMarshaler getResponseMarshaler() {
			return getRealImplementation().getResponseMarshaler();
		}

		@NonNull
		@Override
		public McpCorsAuthorizer getCorsAuthorizer() {
			return getRealImplementation().getCorsAuthorizer();
		}

		@NonNull
		@Override
		public McpSessionStore getSessionStore() {
			return getRealImplementation().getSessionStore();
		}

			@NonNull
			@Override
			public IdGenerator<String> getSessionIdGenerator() {
				return getRealImplementation().getSessionIdGenerator();
			}

		@NonNull
		protected McpServer getRealImplementation() {
			return this.realImplementation;
		}

		@NonNull
		protected Optional<SokletConfig> getSokletConfig() {
			return Optional.ofNullable(this.sokletConfig);
		}

		@NonNull
		protected Optional<RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}

		protected void onMcpStreamError(@Nullable Consumer<Throwable> onMcpStreamError) {
			this.mcpStreamErrorHandler.set(onMcpStreamError);
		}

		@NonNull
		protected AtomicReference<Consumer<Throwable>> getMcpStreamErrorHandler() {
			return this.mcpStreamErrorHandler;
		}

		protected void registerOpenStream(@NonNull String sessionId,
																			@NonNull Request request,
																			McpRequestResult.StreamOpened streamOpened) {
			requireNonNull(sessionId);
			requireNonNull(request);
			requireNonNull(streamOpened);

			getOpenStreamsBySessionId()
					.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>())
					.add(streamOpened);

			streamOpened.onClose(() -> closeOpenStream(sessionId, request, streamOpened));
		}

		protected void terminateStreamsForSession(@NonNull String sessionId) {
			requireNonNull(sessionId);

			CopyOnWriteArrayList<McpRequestResult.StreamOpened> streams = getOpenStreamsBySessionId().remove(sessionId);

			if (streams == null)
				return;

			for (McpRequestResult.StreamOpened streamOpened : streams)
				streamOpened.terminate();
		}

		@NonNull
		@Override
		public Boolean publishSessionMessage(@NonNull String sessionId,
																				 @NonNull McpObject message) {
			requireNonNull(sessionId);
			requireNonNull(message);

			CopyOnWriteArrayList<McpRequestResult.StreamOpened> streams = getOpenStreamsBySessionId().get(sessionId);

			if (streams == null || streams.isEmpty())
				return false;

			for (int i = streams.size() - 1; i >= 0; i--) {
				McpRequestResult.StreamOpened streamOpened = streams.get(i);

				if (streamOpened.isClosed())
					continue;

				streamOpened.emitMessage(message);
				return true;
			}

			return false;
		}

		protected void onClientDisconnectedMcpStream(@Nullable BiConsumer<Request, String> clientDisconnectedMcpStreamHandler) {
			this.clientDisconnectedMcpStreamHandler.set(clientDisconnectedMcpStreamHandler);
		}

		protected void closeOpenStream(@NonNull String sessionId,
																	 @NonNull Request request,
																	 McpRequestResult.StreamOpened streamOpened) {
			requireNonNull(sessionId);
			requireNonNull(request);
			requireNonNull(streamOpened);

			CopyOnWriteArrayList<McpRequestResult.StreamOpened> streams = getOpenStreamsBySessionId().get(sessionId);

			if (streams != null) {
				streams.remove(streamOpened);

				if (streams.isEmpty())
					getOpenStreamsBySessionId().remove(sessionId, streams);
			}

			BiConsumer<Request, String> handler = this.clientDisconnectedMcpStreamHandler.get();

			if (handler != null)
				handler.accept(request, sessionId);
		}

		@NonNull
		protected ConcurrentHashMap<@NonNull String, @NonNull CopyOnWriteArrayList<McpRequestResult.StreamOpened>> getOpenStreamsBySessionId() {
			return this.openStreamsBySessionId;
		}
	}

	/**
	 * Mock Server-Sent Event unicaster that doesn't touch the network at all, useful for testing.
	 */
	@ThreadSafe
	static class MockSseUnicaster implements SseUnicaster {
		@NonNull
		private final ResourcePath resourcePath;
		@NonNull
		private final Consumer<SseEvent> eventConsumer;
		@NonNull
		private final Consumer<SseComment> commentConsumer;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> unicastErrorHandler;

		public MockSseUnicaster(@NonNull ResourcePath resourcePath,
																				@NonNull Consumer<SseEvent> eventConsumer,
																				@NonNull Consumer<SseComment> commentConsumer,
																				@NonNull AtomicReference<Consumer<Throwable>> unicastErrorHandler) {
			requireNonNull(resourcePath);
			requireNonNull(eventConsumer);
			requireNonNull(commentConsumer);
			requireNonNull(unicastErrorHandler);

			this.resourcePath = resourcePath;
			this.eventConsumer = eventConsumer;
			this.commentConsumer = commentConsumer;
			this.unicastErrorHandler = unicastErrorHandler;
		}

		@Override
		public void unicastEvent(@NonNull SseEvent sseEvent) {
			requireNonNull(sseEvent);
			try {
				getEventConsumer().accept(sseEvent);
			} catch (Throwable throwable) {
				handleUnicastError(throwable);
			}
		}

		@Override
		public void unicastComment(@NonNull SseComment sseComment) {
			requireNonNull(sseComment);
			try {
				getCommentConsumer().accept(sseComment);
			} catch (Throwable throwable) {
				handleUnicastError(throwable);
			}
		}

		@NonNull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@NonNull
		protected Consumer<SseEvent> getEventConsumer() {
			return this.eventConsumer;
		}

		@NonNull
		protected Consumer<SseComment> getCommentConsumer() {
			return this.commentConsumer;
		}

		protected void handleUnicastError(@NonNull Throwable throwable) {
			requireNonNull(throwable);
			Consumer<Throwable> handler = this.unicastErrorHandler.get();

			if (handler != null) {
				try {
					handler.accept(throwable);
					return;
				} catch (Throwable ignored) {
					// Fall through to default behavior
				}
			}

			throwable.printStackTrace();
		}
	}

	/**
	 * Mock Server-Sent Event broadcaster that doesn't touch the network at all, useful for testing.
	 */
	@ThreadSafe
	static class MockSseBroadcaster implements SseBroadcaster {
		// ConcurrentHashMap doesn't allow null values, so we use a sentinel if context is null
		private static final Object NULL_CONTEXT_SENTINEL;

		static {
			NULL_CONTEXT_SENTINEL = new Object();
		}

		@NonNull
		private final ResourcePath resourcePath;
		// Maps the Consumer (Listener) to its Context object (e.g. Locale)
		@NonNull
		private final Map<@NonNull Consumer<SseEvent>, @NonNull Object> eventConsumers;
		// Same goes for comments
		@NonNull
		private final Map<@NonNull Consumer<SseComment>, @NonNull Object> commentConsumers;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> broadcastErrorHandler;

		public MockSseBroadcaster(@NonNull ResourcePath resourcePath,
																					@NonNull AtomicReference<Consumer<Throwable>> broadcastErrorHandler) {
			requireNonNull(resourcePath);
			requireNonNull(broadcastErrorHandler);

			this.resourcePath = resourcePath;
			this.eventConsumers = new ConcurrentHashMap<>();
			this.commentConsumers = new ConcurrentHashMap<>();
			this.broadcastErrorHandler = broadcastErrorHandler;
		}

		@NonNull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@NonNull
		@Override
		public Long getClientCount() {
			return Long.valueOf(getEventConsumers().size() + getCommentConsumers().size());
		}

		@Override
		public void broadcastEvent(@NonNull SseEvent sseEvent) {
			requireNonNull(sseEvent);

			for (Consumer<SseEvent> eventConsumer : getEventConsumers().keySet()) {
				try {
					eventConsumer.accept(sseEvent);
				} catch (Throwable throwable) {
					handleBroadcastError(throwable);
				}
			}
		}

		@Override
		public void broadcastComment(@NonNull SseComment sseComment) {
			requireNonNull(sseComment);

			for (Consumer<SseComment> commentConsumer : getCommentConsumers().keySet()) {
				try {
					commentConsumer.accept(sseComment);
				} catch (Throwable throwable) {
					handleBroadcastError(throwable);
				}
			}
		}

		@Override
		public <T> void broadcastEvent(
				@NonNull Function<Object, T> keySelector,
				@NonNull Function<T, SseEvent> eventProvider
		) {
			requireNonNull(keySelector);
			requireNonNull(eventProvider);

			// 1. Create a temporary cache for this specific broadcast operation.
			// This ensures we only run the expensive 'eventProvider' once per unique key.
			Map<T, SseEvent> payloadCache = new HashMap<>();

			this.getEventConsumers().forEach((consumer, context) -> {
				try {
					// 2. Derive the key from the subscriber's context
					T key = keySelector.apply(context);

					// 3. Memoize: Generate the payload if we haven't seen this key yet, otherwise reuse it
					SseEvent event = payloadCache.computeIfAbsent(key, eventProvider);

					// 4. Dispatch
					consumer.accept(event);
				} catch (Throwable throwable) {
					handleBroadcastError(throwable);
				}
			});
		}

		@Override
		public <T> void broadcastComment(
				@NonNull Function<Object, T> keySelector,
				@NonNull Function<T, SseComment> commentProvider
		) {
			requireNonNull(keySelector);
			requireNonNull(commentProvider);

			// 1. Create temporary cache
			Map<T, SseComment> commentCache = new HashMap<>();

			this.getCommentConsumers().forEach((consumer, context) -> {
				try {
					// 2. Derive key
					T key = keySelector.apply(context);

					// 3. Memoize
					SseComment comment = commentCache.computeIfAbsent(key, commentProvider);

					// 4. Dispatch
					consumer.accept(comment);
				} catch (Throwable throwable) {
					handleBroadcastError(throwable);
				}
			});
		}

		@NonNull
		public Boolean registerEventConsumer(@NonNull Consumer<SseEvent> eventConsumer) {
			return registerEventConsumer(eventConsumer, null);
		}

		/**
		 * Registers a consumer with an associated context, simulating a client with specific traits.
		 */
		@NonNull
		public Boolean registerEventConsumer(@NonNull Consumer<SseEvent> eventConsumer, @Nullable Object context) {
			requireNonNull(eventConsumer);
			// map.put returns null if the key was new, which conceptually matches "add" returning true
			return this.getEventConsumers().put(eventConsumer, context == null ? NULL_CONTEXT_SENTINEL : context) == null;
		}

		@NonNull
		public Boolean unregisterEventConsumer(@NonNull Consumer<SseEvent> eventConsumer) {
			requireNonNull(eventConsumer);
			return this.getEventConsumers().remove(eventConsumer) != null;
		}

		@NonNull
		public Boolean registerCommentConsumer(@NonNull Consumer<SseComment> commentConsumer) {
			return registerCommentConsumer(commentConsumer, null);
		}

		/**
		 * Registers a consumer with an associated context, simulating a client with specific traits.
		 */
		@NonNull
		public Boolean registerCommentConsumer(@NonNull Consumer<SseComment> commentConsumer, @Nullable Object context) {
			requireNonNull(commentConsumer);
			return this.getCommentConsumers().put(commentConsumer, context == null ? NULL_CONTEXT_SENTINEL : context) == null;
		}

		@NonNull
		public Boolean unregisterCommentConsumer(@NonNull Consumer<SseComment> commentConsumer) {
			requireNonNull(commentConsumer);
			return this.getCommentConsumers().remove(commentConsumer) != null;
		}

		@NonNull
		protected Map<@NonNull Consumer<SseEvent>, @NonNull Object> getEventConsumers() {
			return this.eventConsumers;
		}

		@NonNull
		protected Map<@NonNull Consumer<SseComment>, @NonNull Object> getCommentConsumers() {
			return this.commentConsumers;
		}

		protected void handleBroadcastError(@NonNull Throwable throwable) {
			requireNonNull(throwable);
			Consumer<Throwable> handler = this.broadcastErrorHandler.get();

			if (handler != null) {
				try {
					handler.accept(throwable);
					return;
				} catch (Throwable ignored) {
					// Fall through to default behavior
				}
			}

			throwable.printStackTrace();
		}
	}

	/**
	 * Mock Server-Sent Event server that doesn't touch the network at all, useful for testing.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	static class MockSseServer implements SseServer {
		@Nullable
		private SokletConfig sokletConfig;
		private SseServer.@Nullable RequestHandler requestHandler;
		@NonNull
		private final ConcurrentHashMap<@NonNull ResourcePath, @NonNull MockSseBroadcaster> broadcastersByResourcePath;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> broadcastErrorHandler;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> unicastErrorHandler;

		public MockSseServer() {
			this.broadcastersByResourcePath = new ConcurrentHashMap<>();
			this.broadcastErrorHandler = new AtomicReference<>();
			this.unicastErrorHandler = new AtomicReference<>();
		}

		@Override
		public void start() {
			// No-op
		}

		@Override
		public void stop() {
			// No-op
		}

		@NonNull
		@Override
		public Boolean isStarted() {
			return true;
		}

		@NonNull
		@Override
		public Optional<? extends SseBroadcaster> acquireBroadcaster(@Nullable ResourcePath resourcePath) {
			if (resourcePath == null)
				return Optional.empty();

			MockSseBroadcaster broadcaster = getBroadcastersByResourcePath()
					.computeIfAbsent(resourcePath, rp -> new MockSseBroadcaster(rp, broadcastErrorHandler));

			return Optional.of(broadcaster);
		}

		public void registerEventConsumer(@NonNull ResourcePath resourcePath,
																			@NonNull Consumer<SseEvent> eventConsumer) {
			registerEventConsumer(resourcePath, eventConsumer, null);
		}

		public void registerEventConsumer(@NonNull ResourcePath resourcePath,
																			@NonNull Consumer<SseEvent> eventConsumer,
																			@Nullable Object context) {
			requireNonNull(resourcePath);
			requireNonNull(eventConsumer);

			MockSseBroadcaster broadcaster = getBroadcastersByResourcePath()
					.computeIfAbsent(resourcePath, rp -> new MockSseBroadcaster(rp, broadcastErrorHandler));

			broadcaster.registerEventConsumer(eventConsumer, context);
		}

		@NonNull
		public Boolean unregisterEventConsumer(@NonNull ResourcePath resourcePath,
																					 @NonNull Consumer<SseEvent> eventConsumer) {
			requireNonNull(resourcePath);
			requireNonNull(eventConsumer);

			MockSseBroadcaster broadcaster = getBroadcastersByResourcePath().get(resourcePath);

			if (broadcaster == null)
				return false;

			return broadcaster.unregisterEventConsumer(eventConsumer);
		}

		public void registerCommentConsumer(@NonNull ResourcePath resourcePath,
																				@NonNull Consumer<SseComment> commentConsumer) {
			registerCommentConsumer(resourcePath, commentConsumer, null);
		}

		public void registerCommentConsumer(@NonNull ResourcePath resourcePath,
																				@NonNull Consumer<SseComment> commentConsumer,
																				@Nullable Object context) {
			requireNonNull(resourcePath);
			requireNonNull(commentConsumer);

			MockSseBroadcaster broadcaster = getBroadcastersByResourcePath()
					.computeIfAbsent(resourcePath, rp -> new MockSseBroadcaster(rp, broadcastErrorHandler));

			broadcaster.registerCommentConsumer(commentConsumer, context);
		}

		@NonNull
		public Boolean unregisterCommentConsumer(@NonNull ResourcePath resourcePath,
																						 @NonNull Consumer<SseComment> commentConsumer) {
			requireNonNull(resourcePath);
			requireNonNull(commentConsumer);

			MockSseBroadcaster broadcaster = getBroadcastersByResourcePath().get(resourcePath);

			if (broadcaster == null)
				return false;

			return broadcaster.unregisterCommentConsumer(commentConsumer);
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
													 SseServer.@NonNull RequestHandler requestHandler) {
			requireNonNull(sokletConfig);
			requireNonNull(requestHandler);

			this.sokletConfig = sokletConfig;
			this.requestHandler = requestHandler;
		}

		public void onBroadcastError(@Nullable Consumer<Throwable> onBroadcastError) {
			this.broadcastErrorHandler.set(onBroadcastError);
		}

		public void onUnicastError(@Nullable Consumer<Throwable> onUnicastError) {
			this.unicastErrorHandler.set(onUnicastError);
		}

		@NonNull
		protected Optional<SokletConfig> getSokletConfig() {
			return Optional.ofNullable(this.sokletConfig);
		}

		@NonNull
		protected Optional<SseServer.RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}

		@NonNull
		protected ConcurrentHashMap<@NonNull ResourcePath, @NonNull MockSseBroadcaster> getBroadcastersByResourcePath() {
			return this.broadcastersByResourcePath;
		}

		@NonNull
		protected AtomicReference<Consumer<Throwable>> getUnicastErrorHandler() {
			return this.unicastErrorHandler;
		}
	}

}
