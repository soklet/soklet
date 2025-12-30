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

import com.soklet.ServerSentEventRequestResult.HandshakeAccepted;
import com.soklet.ServerSentEventRequestResult.HandshakeRejected;
import com.soklet.annotation.ServerSentEventSource;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.soklet.Utilities.emptyByteArray;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Soklet's main class - manages a {@link Server} (and optionally a {@link ServerSentEventServer}) using the provided system configuration.
 * <p>
 * <pre>{@code // Use out-of-the-box defaults
 * SokletConfig config = SokletConfig.withServer(
 *   Server.withPort(8080).build()
 * ).build();
 *
 * try (Soklet soklet = Soklet.withConfig(config)) {
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
 *     RequestResult result = simulator.performRequest(request);
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
 *       String actualBody = new String(body, StandardCharsets.UTF_8);
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
	public static Soklet withConfig(@NonNull SokletConfig sokletConfig) {
		requireNonNull(sokletConfig);
		return new Soklet(sokletConfig);
	}

	@NonNull
	private final SokletConfig sokletConfig;
	@NonNull
	private final ReentrantLock lock;
	@NonNull
	private final AtomicReference<CountDownLatch> awaitShutdownLatchReference;

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

		// Fail fast in the event that Soklet appears misconfigured
		if (sokletConfig.getResourceMethodResolver().getResourceMethods().size() == 0)
			throw new IllegalStateException(format("No Soklet Resource Methods were found. Please ensure your %s is configured correctly. "
					+ "See https://www.soklet.com/docs/request-handling#resource-method-resolution for details.", ResourceMethodResolver.class.getSimpleName()));

		// SSE misconfiguration check: @ServerSentEventSource resource methods are declared, but not ServerSentEventServer exists
		boolean hasSseResourceMethods = sokletConfig.getResourceMethodResolver().getResourceMethods().stream()
				.anyMatch(resourceMethod -> resourceMethod.isServerSentEventSource());

		if (hasSseResourceMethods && sokletConfig.getServerSentEventServer().isEmpty())
			throw new IllegalStateException(format("Resource Methods annotated with @%s were found, but no %s is configured. See https://www.soklet.com/docs/server-sent-events for details.",
					ServerSentEventSource.class.getSimpleName(), ServerSentEventServer.class.getSimpleName()));

		// Use a layer of indirection here so the Soklet type does not need to directly implement the `RequestHandler` interface.
		// Reasoning: the `handleRequest` method for Soklet should not be public, which might lead to accidental invocation by users.
		// That method should only be called by the managed `Server` instance.
		Soklet soklet = this;

		sokletConfig.getServer().initialize(getSokletConfig(), (request, marshaledResponseConsumer) -> {
			// Delegate to Soklet's internal request handling method
			soklet.handleRequest(request, ServerType.STANDARD_HTTP, marshaledResponseConsumer);
		});

		ServerSentEventServer serverSentEventServer = sokletConfig.getServerSentEventServer().orElse(null);

		if (serverSentEventServer != null)
			serverSentEventServer.initialize(sokletConfig, (request, marshaledResponseConsumer) -> {
				// Delegate to Soklet's internal request handling method
				soklet.handleRequest(request, ServerType.SERVER_SENT_EVENT, marshaledResponseConsumer);
			});
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
			LifecycleObserver lifecycleObserver = sokletConfig.getLifecycleObserver();

			// 1. Notify global intent to start
			lifecycleObserver.willStartSoklet(this);

			try {
				Server server = sokletConfig.getServer();

				// 2. Attempt to start Main Server
				lifecycleObserver.willStartServer(server);
				try {
					server.start();
					lifecycleObserver.didStartServer(server);
				} catch (Throwable t) {
					lifecycleObserver.didFailToStartServer(server, t);
					throw t; // Rethrow to trigger outer catch block
				}

				// 3. Attempt to start SSE Server (if present)
				ServerSentEventServer serverSentEventServer = sokletConfig.getServerSentEventServer().orElse(null);

				if (serverSentEventServer != null) {
					lifecycleObserver.willStartServerSentEventServer(serverSentEventServer);
					try {
						serverSentEventServer.start();
						lifecycleObserver.didStartServerSentEventServer(serverSentEventServer);
					} catch (Throwable t) {
						lifecycleObserver.didFailToStartServerSentEventServer(serverSentEventServer, t);
						throw t; // Rethrow to trigger outer catch block
					}
				}

				// 4. Global success
				lifecycleObserver.didStartSoklet(this);
			} catch (Throwable t) {
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
				LifecycleObserver lifecycleObserver = sokletConfig.getLifecycleObserver();

				// 1. Notify global intent to stop
				lifecycleObserver.willStopSoklet(this);

				Throwable firstEncounteredException = null;
				Server server = sokletConfig.getServer();

				// 2. Attempt to stop Main Server
				if (server.isStarted()) {
					lifecycleObserver.willStopServer(server);
					try {
						server.stop();
						lifecycleObserver.didStopServer(server);
					} catch (Throwable t) {
						firstEncounteredException = t;
						lifecycleObserver.didFailToStopServer(server, t);
					}
				}

				// 3. Attempt to stop SSE Server
				ServerSentEventServer serverSentEventServer = sokletConfig.getServerSentEventServer().orElse(null);

				if (serverSentEventServer != null && serverSentEventServer.isStarted()) {
					lifecycleObserver.willStopServerSentEventServer(serverSentEventServer);
					try {
						serverSentEventServer.stop();
						lifecycleObserver.didStopServerSentEventServer(serverSentEventServer);
					} catch (Throwable t) {
						if (firstEncounteredException == null)
							firstEncounteredException = t;

						lifecycleObserver.didFailToStopServerSentEventServer(serverSentEventServer, t);
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

					getSokletConfig().getLifecycleObserver().didReceiveLogEvent(logEvent);
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
	 * Nonpublic "informal" implementation of {@link com.soklet.Server.RequestHandler} so Soklet does not need to expose {@code handleRequest} publicly.
	 * Reasoning: users of this library should never call {@code handleRequest} directly - it should only be invoked in response to events
	 * provided by a {@link Server} or {@link ServerSentEventServer} implementation.
	 */
	protected void handleRequest(@NonNull Request request,
															 @NonNull ServerType serverType,
															 @NonNull Consumer<RequestResult> requestResultConsumer) {
		requireNonNull(request);
		requireNonNull(serverType);
		requireNonNull(requestResultConsumer);

		Instant processingStarted = Instant.now();

		SokletConfig sokletConfig = getSokletConfig();
		ResourceMethodResolver resourceMethodResolver = sokletConfig.getResourceMethodResolver();
		ResponseMarshaler responseMarshaler = sokletConfig.getResponseMarshaler();
		LifecycleObserver lifecycleObserver = sokletConfig.getLifecycleObserver();
		RequestInterceptor requestInterceptor = sokletConfig.getRequestInterceptor();
		MetricsCollector metricsCollector = sokletConfig.getMetricsCollector();

		// Holders to permit mutable effectively-final variables
		AtomicReference<MarshaledResponse> marshaledResponseHolder = new AtomicReference<>();
		AtomicReference<Throwable> resourceMethodResolutionExceptionHolder = new AtomicReference<>();
		AtomicReference<Request> requestHolder = new AtomicReference<>(request);
		AtomicReference<ResourceMethod> resourceMethodHolder = new AtomicReference<>();
		AtomicReference<RequestResult> requestResultHolder = new AtomicReference<>();

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
			requestInterceptor.wrapRequest(request, (wrappedRequest) -> {
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
					lifecycleObserver.didStartRequestHandling(requestHolder.get(), resourceMethodHolder.get());
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
						(metricsInvocation) -> metricsInvocation.didStartRequestHandling(requestHolder.get(), resourceMethodHolder.get()));

				try {
					AtomicBoolean didInvokeMarshaledResponseConsumer = new AtomicBoolean(false);

					requestInterceptor.interceptRequest(requestHolder.get(), resourceMethodHolder.get(), (interceptorRequest) -> {
						requestHolder.set(interceptorRequest);

						try {
							if (resourceMethodResolutionExceptionHolder.get() != null)
								throw resourceMethodResolutionExceptionHolder.get();

							RequestResult requestResult = toRequestResult(requestHolder.get(), resourceMethodHolder.get(), serverType);
							requestResultHolder.set(requestResult);

							MarshaledResponse originalMarshaledResponse = requestResult.getMarshaledResponse();
							MarshaledResponse updatedMarshaledResponse = requestResult.getMarshaledResponse();

							// A few special cases that are "global" in that they can affect all requests and
							// need to happen after marshaling the response...

							// 1. Customize response for HEAD (e.g. remove body, set Content-Length header)
							updatedMarshaledResponse = applyHeadResponseIfApplicable(requestHolder.get(), updatedMarshaledResponse);

							// 2. Apply other standard response customizations (CORS, Content-Length)
							// Note that we don't want to write Content-Length for SSE "accepted" handshakes
							HandshakeResult handshakeResult = requestResult.getHandshakeResult().orElse(null);
							boolean suppressContentLength = handshakeResult != null && handshakeResult instanceof HandshakeResult.Accepted;

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
							if (!Objects.equals(t, resourceMethodResolutionExceptionHolder.get())) {
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
						throw new IllegalStateException(format("%s::interceptRequest must call marshaledResponseConsumer", RequestInterceptor.class.getSimpleName()));
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
							lifecycleObserver.willWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get());
						} finally {
							willStartResponseWritingCompleted.set(true);
						}

						safelyCollectMetrics.accept(
								format("An exception occurred while invoking %s::willWriteResponse", MetricsCollector.class.getSimpleName()),
								(metricsInvocation) -> metricsInvocation.willWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get()));

						Instant responseWriteStarted = Instant.now();

						try {
							RequestResult requestResult = requestResultHolder.get();

							if (requestResult != null)
								requestResultConsumer.accept(requestResult);
							else
								requestResultConsumer.accept(RequestResult.withMarshaledResponse(marshaledResponseHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.build());

							Instant responseWriteFinished = Instant.now();
							Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

							try {
								lifecycleObserver.didWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration);
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
									(metricsInvocation) -> metricsInvocation.didWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration));
						} catch (Throwable t) {
							throwables.add(t);

							Instant responseWriteFinished = Instant.now();
							Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

							try {
								lifecycleObserver.didFailToWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, t);
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
									(metricsInvocation) -> metricsInvocation.didFailToWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, t));
						}
					} finally {
						Duration processingDuration = Duration.between(processingStarted, Instant.now());

						safelyCollectMetrics.accept(
								format("An exception occurred while invoking %s::didFinishRequestHandling", MetricsCollector.class.getSimpleName()),
								(metricsInvocation) -> metricsInvocation.didFinishRequestHandling(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables)));

						try {
							lifecycleObserver.didFinishRequestHandling(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables));
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
				throw new IllegalStateException(format("%s::wrapRequest must call requestConsumer", RequestInterceptor.class.getSimpleName()));
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
					lifecycleObserver.willWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get());
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
						(metricsInvocation) -> metricsInvocation.willWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get()));
			}

			try {
				Instant responseWriteStarted = Instant.now();

				if (!didFinishResponseWritingCompleted.get()) {
					try {
						RequestResult requestResult = requestResultHolder.get();

						if (requestResult != null)
							requestResultConsumer.accept(requestResult);
						else
							requestResultConsumer.accept(RequestResult.withMarshaledResponse(marshaledResponseHolder.get())
									.resourceMethod(resourceMethodHolder.get())
									.build());

						Instant responseWriteFinished = Instant.now();
						Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

						try {
							lifecycleObserver.didWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration);
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
								(metricsInvocation) -> metricsInvocation.didWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration));
					} catch (Throwable t2) {
						throwables.add(t2);

						Instant responseWriteFinished = Instant.now();
						Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

						try {
							lifecycleObserver.didFailToWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, t);
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
								(metricsInvocation) -> metricsInvocation.didFailToWriteResponse(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, t));
					}
				}
			} finally {
				if (!didFinishRequestHandlingCompleted.get()) {
					Duration processingDuration = Duration.between(processingStarted, Instant.now());

					safelyCollectMetrics.accept(
							format("An exception occurred while invoking %s::didFinishRequestHandling", MetricsCollector.class.getSimpleName()),
							(metricsInvocation) -> metricsInvocation.didFinishRequestHandling(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables)));

					try {
						lifecycleObserver.didFinishRequestHandling(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables));
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
	protected RequestResult toRequestResult(@NonNull Request request,
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
			return RequestResult.withMarshaledResponse(responseMarshaler.forContentTooLarge(request, resourceMethodResolver.resourceMethodForRequest(request, serverType).orElse(null)))
					.resourceMethod(resourceMethod)
					.build();

		// Special short-circuit for OPTIONS *
		if (request.getResourcePath() == ResourcePath.OPTIONS_SPLAT_RESOURCE_PATH)
			return RequestResult.withMarshaledResponse(responseMarshaler.forOptionsSplat(request)).build();

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

						return RequestResult.withMarshaledResponse(marshaledResponse)
								.corsPreflightResponse(corsPreflightResponse)
								.resourceMethod(resourceMethod)
								.build();
					}

					// Reject
					return RequestResult.withMarshaledResponse(responseMarshaler.forCorsPreflightRejected(request, corsPreflight))
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

						return RequestResult.withMarshaledResponse(responseMarshaler.forOptions(request, allowedHttpMethods))
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
					return RequestResult.withMarshaledResponse(responseMarshaler.forNotFound(request))
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
					return RequestResult.withMarshaledResponse(responseMarshaler.forMethodNotAllowed(request, allowedHttpMethods))
							.resourceMethod(resourceMethod)
							.build();
				} else {
					// no matching resource method found, it's a 404
					return RequestResult.withMarshaledResponse(responseMarshaler.forNotFound(request))
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
		HandshakeResult handshakeResult = null;

		// If null/void return, it's a 204
		// If it's a MarshaledResponse object, no marshaling + return it immediately - caller knows exactly what it wants to write.
		// If it's a Response object, use as is.
		// If it's a non-Response type of object, assume it's the response body and wrap in a Response.
		if (responseObject == null) {
			response = Response.withStatusCode(204).build();
		} else if (responseObject instanceof MarshaledResponse) {
			MarshaledResponse marshaledResponse = (MarshaledResponse) responseObject;
			enforceBodylessStatusCode(marshaledResponse.getStatusCode(), marshaledResponse.getBody().isPresent());

			return RequestResult.withMarshaledResponse(marshaledResponse)
					.resourceMethod(resourceMethod)
					.build();
		} else if (responseObject instanceof Response) {
			response = (Response) responseObject;
		} else if (responseObject instanceof HandshakeResult.Accepted accepted) { // SSE "accepted" handshake
			return RequestResult.withMarshaledResponse(toMarshaledResponse(accepted))
					.resourceMethod(resourceMethod)
					.handshakeResult(accepted)
					.build();
		} else if (responseObject instanceof HandshakeResult.Rejected rejected) { // SSE "rejected" handshake
			response = rejected.getResponse();
			handshakeResult = rejected;
		} else {
			response = Response.withStatusCode(200).body(responseObject).build();
		}

		enforceBodylessStatusCode(response.getStatusCode(), response.getBody().isPresent());

		MarshaledResponse marshaledResponse = responseMarshaler.forResourceMethod(request, response, resourceMethod);

		enforceBodylessStatusCode(marshaledResponse.getStatusCode(), marshaledResponse.getBody().isPresent());

		return RequestResult.withMarshaledResponse(marshaledResponse)
				.response(response)
				.resourceMethod(resourceMethod)
				.handshakeResult(handshakeResult)
				.build();
	}

	@NonNull
	private MarshaledResponse toMarshaledResponse(HandshakeResult.@NonNull Accepted accepted) {
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
					.headers(headers -> headers.put("Date", Set.of(CachedHttpDate.getCurrentValue())))
					.finish();

		marshaledResponse = applyCorsResponseIfApplicable(request, marshaledResponse);

		return marshaledResponse;
	}

	@NonNull
	protected MarshaledResponse applyContentLengthIfApplicable(@NonNull Request request,
																														 @NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		Set<String> normalizedHeaderNames = marshaledResponse.getHeaders().keySet().stream()
				.map(headerName -> headerName.toLowerCase(Locale.US))
				.collect(Collectors.toSet());

		// If Content-Length is already specified, don't do anything
		if (normalizedHeaderNames.contains("content-length"))
			return marshaledResponse;

		// If Content-Length is not specified, specify as the number of bytes in the body
		return marshaledResponse.copy()
				.headers((mutableHeaders) -> {
					String contentLengthHeaderValue = String.valueOf(marshaledResponse.getBody().orElse(emptyByteArray()).length);
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
	 * Is either the managed {@link Server} or {@link ServerSentEventServer} started?
	 *
	 * @return {@code true} if at least one is started, {@code false} otherwise
	 */
	@NonNull
	public Boolean isStarted() {
		getLock().lock();

		try {
			if (getSokletConfig().getServer().isStarted())
				return true;

			ServerSentEventServer serverSentEventServer = getSokletConfig().getServerSentEventServer().orElse(null);
			return serverSentEventServer != null && serverSentEventServer.isStarted();
		} finally {
			getLock().unlock();
		}
	}

	/**
	 * Runs Soklet with special non-network "simulator" implementations of {@link Server} and {@link ServerSentEventServer} - useful for integration testing.
	 * <p>
	 * See <a href="https://www.soklet.com/docs/testing">https://www.soklet.com/docs/testing</a> for how to write these tests.
	 *
	 * @param sokletConfig      configuration that drives the Soklet system
	 * @param simulatorConsumer code to execute within the context of the simulator
	 */
	public static void runSimulator(@NonNull SokletConfig sokletConfig,
																	@NonNull Consumer<Simulator> simulatorConsumer) {
		requireNonNull(sokletConfig);
		requireNonNull(simulatorConsumer);

		// Create Soklet instance - this initializes the REAL implementations through proxies
		Soklet soklet = Soklet.withConfig(sokletConfig);

		// Extract proxies (they're guaranteed to be proxies now)
		ServerProxy serverProxy = (ServerProxy) sokletConfig.getServer();
		ServerSentEventServerProxy serverSentEventServerProxy = sokletConfig.getServerSentEventServer()
				.map(s -> (ServerSentEventServerProxy) s)
				.orElse(null);

		// Create mock implementations
		MockServer mockServer = new MockServer();
		MockServerSentEventServer mockServerSentEventServer = new MockServerSentEventServer();

		// Switch proxies to simulator mode
		serverProxy.enableSimulatorMode(mockServer);

		if (serverSentEventServerProxy != null)
			serverSentEventServerProxy.enableSimulatorMode(mockServerSentEventServer);

		try {
			// Initialize mocks with request handlers that delegate to Soklet's processing
			mockServer.initialize(sokletConfig, (request, marshaledResponseConsumer) -> {
				// Delegate to Soklet's internal request handling
				soklet.handleRequest(request, ServerType.STANDARD_HTTP, marshaledResponseConsumer);
			});

			if (mockServerSentEventServer != null)
				mockServerSentEventServer.initialize(sokletConfig, (request, marshaledResponseConsumer) -> {
					// Delegate to Soklet's internal request handling for SSE
					soklet.handleRequest(request, ServerType.SERVER_SENT_EVENT, marshaledResponseConsumer);
				});

			// Create and provide simulator
			Simulator simulator = new DefaultSimulator(mockServer, mockServerSentEventServer);
			simulatorConsumer.accept(simulator);
		} finally {
			// Always restore to real implementations
			serverProxy.disableSimulatorMode();

			if (serverSentEventServerProxy != null)
				serverSentEventServerProxy.disableSimulatorMode();
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
		private MockServer server;
		@Nullable
		private MockServerSentEventServer serverSentEventServer;

		public DefaultSimulator(@NonNull MockServer server,
														@Nullable MockServerSentEventServer serverSentEventServer) {
			requireNonNull(server);

			this.server = server;
			this.serverSentEventServer = serverSentEventServer;
		}

		@NonNull
		@Override
		public RequestResult performRequest(@NonNull Request request) {
			AtomicReference<RequestResult> requestResultHolder = new AtomicReference<>();
			Server.RequestHandler requestHandler = getServer().getRequestHandler().orElse(null);

			if (requestHandler == null)
				throw new IllegalStateException("You must register a request handler prior to simulating requests");

			requestHandler.handleRequest(request, (requestResult -> {
				requestResultHolder.set(requestResult);
			}));

			return requestResultHolder.get();
		}

		@NonNull
		@Override
		public ServerSentEventRequestResult performServerSentEventRequest(@NonNull Request request) {
			MockServerSentEventServer serverSentEventServer = getServerSentEventServer().orElse(null);

			if (serverSentEventServer == null)
				throw new IllegalStateException(format("You must specify a %s in your %s to simulate Server-Sent Event requests",
						ServerSentEventServer.class.getSimpleName(), SokletConfig.class.getSimpleName()));

			AtomicReference<RequestResult> requestResultHolder = new AtomicReference<>();
			ServerSentEventServer.RequestHandler requestHandler = serverSentEventServer.getRequestHandler().orElse(null);

			if (requestHandler == null)
				throw new IllegalStateException("You must register a request handler prior to simulating SSE Event Source requests");

			requestHandler.handleRequest(request, (requestResult -> {
				requestResultHolder.set(requestResult);
			}));

			RequestResult requestResult = requestResultHolder.get();
			HandshakeResult handshakeResult = requestResult.getHandshakeResult().orElse(null);

			if (handshakeResult == null)
				return new ServerSentEventRequestResult.RequestFailed(requestResult);

			if (handshakeResult instanceof HandshakeResult.Accepted acceptedHandshake) {
				Consumer<ServerSentEventUnicaster> clientInitializer = acceptedHandshake.getClientInitializer().orElse(null);

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

			if (handshakeResult instanceof HandshakeResult.Rejected rejectedHandshake)
				return new HandshakeRejected(rejectedHandshake, requestResult);

			throw new IllegalStateException(format("Encountered unexpected %s: %s", HandshakeResult.class.getSimpleName(), handshakeResult));
		}

		@NonNull
		@Override
		public Simulator onBroadcastError(@Nullable Consumer<Throwable> onBroadcastError) {
			MockServerSentEventServer serverSentEventServer = getServerSentEventServer().orElse(null);

			if (serverSentEventServer != null)
				serverSentEventServer.onBroadcastError(onBroadcastError);

			return this;
		}

		@NonNull
		@Override
		public Simulator onUnicastError(@Nullable Consumer<Throwable> onUnicastError) {
			MockServerSentEventServer serverSentEventServer = getServerSentEventServer().orElse(null);

			if (serverSentEventServer != null)
				serverSentEventServer.onUnicastError(onUnicastError);

			return this;
		}

		@NonNull
		MockServer getServer() {
			return this.server;
		}

		@NonNull
		Optional<MockServerSentEventServer> getServerSentEventServer() {
			return Optional.ofNullable(this.serverSentEventServer);
		}
	}

	/**
	 * Mock server that doesn't touch the network at all, useful for testing.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	static class MockServer implements Server {
		@Nullable
		private SokletConfig sokletConfig;
		private Server.@Nullable RequestHandler requestHandler;

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
	 * Mock Server-Sent Event unicaster that doesn't touch the network at all, useful for testing.
	 */
	@ThreadSafe
	static class MockServerSentEventUnicaster implements ServerSentEventUnicaster {
		@NonNull
		private final ResourcePath resourcePath;
		@NonNull
		private final Consumer<ServerSentEvent> eventConsumer;
		@NonNull
		private final Consumer<String> commentConsumer;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> unicastErrorHandler;

		public MockServerSentEventUnicaster(@NonNull ResourcePath resourcePath,
																				@NonNull Consumer<ServerSentEvent> eventConsumer,
																				@NonNull Consumer<String> commentConsumer,
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
		public void unicastEvent(@NonNull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);
			try {
				getEventConsumer().accept(serverSentEvent);
			} catch (Throwable throwable) {
				handleUnicastError(throwable);
			}
		}

		@Override
		public void unicastComment(@NonNull String comment) {
			requireNonNull(comment);
			try {
				getCommentConsumer().accept(comment);
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
		protected Consumer<ServerSentEvent> getEventConsumer() {
			return this.eventConsumer;
		}

		@NonNull
		protected Consumer<String> getCommentConsumer() {
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
	static class MockServerSentEventBroadcaster implements ServerSentEventBroadcaster {
		// ConcurrentHashMap doesn't allow null values, so we use a sentinel if context is null
		private static final Object NULL_CONTEXT_SENTINEL;

		static {
			NULL_CONTEXT_SENTINEL = new Object();
		}

		@NonNull
		private final ResourcePath resourcePath;
		// Maps the Consumer (Listener) to its Context object (e.g. Locale)
		@NonNull
		private final Map<@NonNull Consumer<ServerSentEvent>, @NonNull Object> eventConsumers;
		// Same goes for comments
		@NonNull
		private final Map<@NonNull Consumer<String>, @NonNull Object> commentConsumers;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> broadcastErrorHandler;

		public MockServerSentEventBroadcaster(@NonNull ResourcePath resourcePath,
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
		public void broadcastEvent(@NonNull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);

			for (Consumer<ServerSentEvent> eventConsumer : getEventConsumers().keySet()) {
				try {
					eventConsumer.accept(serverSentEvent);
				} catch (Throwable throwable) {
					handleBroadcastError(throwable);
				}
			}
		}

		@Override
		public void broadcastComment(@NonNull String comment) {
			requireNonNull(comment);

			for (Consumer<String> commentConsumer : getCommentConsumers().keySet()) {
				try {
					commentConsumer.accept(comment);
				} catch (Throwable throwable) {
					handleBroadcastError(throwable);
				}
			}
		}

		@Override
		public <T> void broadcastEvent(
				@NonNull Function<Object, T> keySelector,
				@NonNull Function<T, ServerSentEvent> eventProvider
		) {
			requireNonNull(keySelector);
			requireNonNull(eventProvider);

			// 1. Create a temporary cache for this specific broadcast operation.
			// This ensures we only run the expensive 'eventProvider' once per unique key.
			Map<T, ServerSentEvent> payloadCache = new HashMap<>();

			this.getEventConsumers().forEach((consumer, context) -> {
				try {
					// 2. Derive the key from the subscriber's context
					T key = keySelector.apply(context);

					// 3. Memoize: Generate the payload if we haven't seen this key yet, otherwise reuse it
					ServerSentEvent event = payloadCache.computeIfAbsent(key, eventProvider);

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
				@NonNull Function<T, String> commentProvider
		) {
			requireNonNull(keySelector);
			requireNonNull(commentProvider);

			// 1. Create temporary cache
			Map<T, String> commentCache = new HashMap<>();

			this.getCommentConsumers().forEach((consumer, context) -> {
				try {
					// 2. Derive key
					T key = keySelector.apply(context);

					// 3. Memoize
					String comment = commentCache.computeIfAbsent(key, commentProvider);

					// 4. Dispatch
					consumer.accept(comment);
				} catch (Throwable throwable) {
					handleBroadcastError(throwable);
				}
			});
		}

		@NonNull
		public Boolean registerEventConsumer(@NonNull Consumer<ServerSentEvent> eventConsumer) {
			return registerEventConsumer(eventConsumer, null);
		}

		/**
		 * Registers a consumer with an associated context, simulating a client with specific traits.
		 */
		@NonNull
		public Boolean registerEventConsumer(@NonNull Consumer<ServerSentEvent> eventConsumer, @Nullable Object context) {
			requireNonNull(eventConsumer);
			// map.put returns null if the key was new, which conceptually matches "add" returning true
			return this.getEventConsumers().put(eventConsumer, context == null ? NULL_CONTEXT_SENTINEL : context) == null;
		}

		@NonNull
		public Boolean unregisterEventConsumer(@NonNull Consumer<ServerSentEvent> eventConsumer) {
			requireNonNull(eventConsumer);
			return this.getEventConsumers().remove(eventConsumer) != null;
		}

		@NonNull
		public Boolean registerCommentConsumer(@NonNull Consumer<String> commentConsumer) {
			return registerCommentConsumer(commentConsumer, null);
		}

		/**
		 * Registers a consumer with an associated context, simulating a client with specific traits.
		 */
		@NonNull
		public Boolean registerCommentConsumer(@NonNull Consumer<String> commentConsumer, @Nullable Object context) {
			requireNonNull(commentConsumer);
			return this.getCommentConsumers().put(commentConsumer, context == null ? NULL_CONTEXT_SENTINEL : context) == null;
		}

		@NonNull
		public Boolean unregisterCommentConsumer(@NonNull Consumer<String> commentConsumer) {
			requireNonNull(commentConsumer);
			return this.getCommentConsumers().remove(commentConsumer) != null;
		}

		@NonNull
		protected Map<@NonNull Consumer<ServerSentEvent>, @NonNull Object> getEventConsumers() {
			return this.eventConsumers;
		}

		@NonNull
		protected Map<@NonNull Consumer<String>, @NonNull Object> getCommentConsumers() {
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
	static class MockServerSentEventServer implements ServerSentEventServer {
		@Nullable
		private SokletConfig sokletConfig;
		private ServerSentEventServer.@Nullable RequestHandler requestHandler;
		@NonNull
		private final ConcurrentHashMap<@NonNull ResourcePath, @NonNull MockServerSentEventBroadcaster> broadcastersByResourcePath;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> broadcastErrorHandler;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> unicastErrorHandler;

		public MockServerSentEventServer() {
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
		public Optional<? extends ServerSentEventBroadcaster> acquireBroadcaster(@Nullable ResourcePath resourcePath) {
			if (resourcePath == null)
				return Optional.empty();

			MockServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath()
					.computeIfAbsent(resourcePath, rp -> new MockServerSentEventBroadcaster(rp, broadcastErrorHandler));

			return Optional.of(broadcaster);
		}

		public void registerEventConsumer(@NonNull ResourcePath resourcePath,
																			@NonNull Consumer<ServerSentEvent> eventConsumer) {
			registerEventConsumer(resourcePath, eventConsumer, null);
		}

		public void registerEventConsumer(@NonNull ResourcePath resourcePath,
																			@NonNull Consumer<ServerSentEvent> eventConsumer,
																			@Nullable Object context) {
			requireNonNull(resourcePath);
			requireNonNull(eventConsumer);

			MockServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath()
					.computeIfAbsent(resourcePath, rp -> new MockServerSentEventBroadcaster(rp, broadcastErrorHandler));

			broadcaster.registerEventConsumer(eventConsumer, context);
		}

		@NonNull
		public Boolean unregisterEventConsumer(@NonNull ResourcePath resourcePath,
																					 @NonNull Consumer<ServerSentEvent> eventConsumer) {
			requireNonNull(resourcePath);
			requireNonNull(eventConsumer);

			MockServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath().get(resourcePath);

			if (broadcaster == null)
				return false;

			return broadcaster.unregisterEventConsumer(eventConsumer);
		}

		public void registerCommentConsumer(@NonNull ResourcePath resourcePath,
																				@NonNull Consumer<String> commentConsumer) {
			registerCommentConsumer(resourcePath, commentConsumer, null);
		}

		public void registerCommentConsumer(@NonNull ResourcePath resourcePath,
																				@NonNull Consumer<String> commentConsumer,
																				@Nullable Object context) {
			requireNonNull(resourcePath);
			requireNonNull(commentConsumer);

			MockServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath()
					.computeIfAbsent(resourcePath, rp -> new MockServerSentEventBroadcaster(rp, broadcastErrorHandler));

			broadcaster.registerCommentConsumer(commentConsumer, context);
		}

		@NonNull
		public Boolean unregisterCommentConsumer(@NonNull ResourcePath resourcePath,
																						 @NonNull Consumer<String> commentConsumer) {
			requireNonNull(resourcePath);
			requireNonNull(commentConsumer);

			MockServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath().get(resourcePath);

			if (broadcaster == null)
				return false;

			return broadcaster.unregisterCommentConsumer(commentConsumer);
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
													 ServerSentEventServer.@NonNull RequestHandler requestHandler) {
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
		protected Optional<ServerSentEventServer.RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}

		@NonNull
		protected ConcurrentHashMap<@NonNull ResourcePath, @NonNull MockServerSentEventBroadcaster> getBroadcastersByResourcePath() {
			return this.broadcastersByResourcePath;
		}

		@NonNull
		protected AtomicReference<Consumer<Throwable>> getUnicastErrorHandler() {
			return this.unicastErrorHandler;
		}
	}

	/**
	 * Efficiently provides the current time in RFC 1123 HTTP-date format.
	 * Updates once per second to avoid the overhead of formatting dates on every request.
	 */
	@ThreadSafe
	private static final class CachedHttpDate {
		@NonNull
		private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
				.withZone(ZoneId.of("GMT"));
		@NonNull
		private static volatile String CURRENT_VALUE = FORMATTER.format(Instant.now());

		static {
			Thread t = new Thread(() -> {
				while (true) {
					try {
						Thread.sleep(1000);
						CURRENT_VALUE = FORMATTER.format(Instant.now());
					} catch (InterruptedException e) {
						break; // Allow thread to die on JVM shutdown
					}
				}
			}, "soklet-date-header-value-updater");
			t.setDaemon(true);
			t.start();
		}

		@NonNull
		static String getCurrentValue() {
			return CURRENT_VALUE;
		}
	}
}
