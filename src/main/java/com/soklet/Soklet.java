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

import com.google.errorprone.annotations.CheckReturnValue;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.SimulationSession;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;

import com.soklet.SseRequestResult.HandshakeAccepted;
import com.soklet.SseRequestResult.HandshakeRejected;
import com.soklet.annotation.SseEventSource;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import com.soklet.internal.streaming.ManagedResponseStream;
import com.soklet.internal.streaming.ManagedSseLifecycle;
import com.soklet.internal.streaming.PublisherResponseStream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.soklet.internal.ObjectIdentity.sameInstance;
import static com.soklet.Utilities.emptyByteArray;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Soklet's main class - manages one or more configured transport servers ({@link HttpServer}, {@link SseServer}, and/or
 * {@link McpServer})
 * using the provided system configuration.
 * <p>
 * This is the direct, embedded lifecycle API. The caller owns process-signal,
 * shutdown-trigger, and standard-input integration and requests termination
 * through {@link #shutdown()}. Standalone processes that want Soklet to own a
 * JVM shutdown hook or {@link ShutdownTrigger} registration should use
 * {@link SokletApplication} instead. Do not mix both ownership models for one
 * lifecycle.
 * <p>
 * <pre>{@code // Use out-of-the-box defaults
 * SokletConfig config = SokletConfig.withHttpServer(
 *   HttpServer.fromPort(8080)
 * ).build();
 *
 * try (Soklet soklet = Soklet.fromConfig(config)) {
 *   soklet.start();
 *   installApplicationShutdownCallback(soklet::shutdown);
 *   ShutdownResult result = soklet.awaitShutdown();
 * }}</pre>
 * <p>
 * Soklet also offers an off-network {@link Simulator} through
 * {@link SokletSimulator}, useful for integration testing.
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
 *   // Instead of running on a real HTTP server that listens on a port,
 *   // a non-network Simulator is provided against which you can
 *   // issue requests and receive responses.
 *   SokletSimulator.run(SimulatorConfig.builder().httpServer().build(), simulator -> {
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
 *   });
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
		return new Soklet(sokletConfig, LifecycleRuntimeServices.system(),
				ignored -> { });
	}

	@NonNull
	static Soklet fromConfig(@NonNull SokletConfig sokletConfig,
			@NonNull LifecycleRuntimeServices services,
			@NonNull Consumer<InternalLifecycleCoreSnapshot> coreSnapshotPublisher) {
		return new Soklet(requireNonNull(sokletConfig), requireNonNull(services),
				requireNonNull(coreSnapshotPublisher));
	}

	@NonNull
	private final SokletConfig sokletConfig;
	@NonNull
	private final ReentrantLock lock;
	@NonNull
	private final CountDownLatch awaitShutdownLatch;
	@NonNull
	private final AtomicReference<@NonNull CountDownLatch>
			awaitShutdownLatchReference;
	@NonNull
	private final SokletFrameworkSetup frameworkSetup;
	@NonNull
	private final SokletDirectLifecycle directLifecycle;

	/**
	 * Creates a Soklet instance with the given configuration.
	 *
	 * @param sokletConfig configuration that drives the Soklet system
	 */
	private Soklet(@NonNull SokletConfig sokletConfig,
			@NonNull LifecycleRuntimeServices services,
			@NonNull Consumer<InternalLifecycleCoreSnapshot> coreSnapshotPublisher) {
		requireNonNull(sokletConfig);

		this.sokletConfig = sokletConfig;
		this.lock = new ReentrantLock();
		this.awaitShutdownLatch = new CountDownLatch(1);
		this.awaitShutdownLatchReference =
				new AtomicReference<>(this.awaitShutdownLatch);
		this.frameworkSetup = new SokletFrameworkSetup(sokletConfig);
		this.directLifecycle = new SokletDirectLifecycle(this, sokletConfig,
				this.frameworkSetup, requireNonNull(services),
				requireNonNull(coreSnapshotPublisher));
	}

	/**
	 * Claims this one-shot lifecycle's sole start attempt and returns only after
	 * every configured transport is globally ready.
	 *
	 * @throws SokletStartupException if startup ends before readiness, carrying
	 * the exact immutable lifecycle result
	 * @throws IllegalStateException if another start call already claimed this
	 * lifecycle attempt
	 */
	public void start() {
		this.directLifecycle.start();
	}

	/**
	 * Publishes Soklet-wide shutdown intent promptly and returns the one cached,
	 * read-only completion stage for this lifecycle attempt.
	 * <p>
	 * This method does not install or own process hooks, operating-system signal
	 * handlers, or standard-input listeners; direct lifecycle callers own those
	 * integrations.
	 *
	 * @return cached shutdown-result stage
	 */
	@NonNull
	public CompletionStage<@NonNull ShutdownResult> shutdown() {
		return this.directLifecycle.shutdown();
	}

	/**
	 * Blocks until this lifecycle publishes its immutable shutdown result.
	 *
	 * @return exact published result
	 * @throws InterruptedException if the current thread is interrupted while
	 * waiting
	 * @throws IllegalStateException as a best-effort diagnostic when the current
	 * thread is contributing to the unpublished shutdown barrier
	 */
	@NonNull
	@CheckReturnValue
	public ShutdownResult awaitShutdown() throws InterruptedException {
		return this.directLifecycle.awaitPublicCompletion();
	}

	/**
	 * Nonpublic "informal" implementation of {@link com.soklet.HttpServer.RequestHandler} so Soklet does not need to expose {@code handleRequest} publicly.
	 * Reasoning: users of this library should never call {@code handleRequest} directly - it should only be invoked in response to events
	 * provided by a {@link HttpServer} or {@link SseServer} implementation.
	 */
	protected void handleRequest(@NonNull Request request,
														 @NonNull ServerType serverType,
														 @NonNull Consumer<HttpRequestResult> requestResultConsumer) {
		try (LifecycleExecutionContext.Scope ignored =
					 this.directLifecycle.enterExecution()) {
			handleRequestWithinLifecycle(request, serverType,
					requestResultConsumer);
		}
	}

	private void handleRequestWithinLifecycle(@NonNull Request request,
														 @NonNull ServerType serverType,
														 @NonNull Consumer<HttpRequestResult> requestResultConsumer) {
		requireNonNull(request);
		requireNonNull(serverType);
		requireNonNull(requestResultConsumer);

		long processingStartedNanos = System.nanoTime();

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
		// The handler's effective request may be replaced by either interceptor hook. Paired
		// lifecycle/metrics callbacks retain the original per-dispatch identity, as do HTTP
		// stream handles, so observers never have to correlate caller-controlled request IDs.
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
				LifecycleObserverLogFallback.report(throwable);
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
					ResourceMethod resolvedResourceMethod =
							validateResolvedResourceMethod(resourceMethodResolver
									.resourceMethodForRequest(requestHolder.get(), serverType)
									.orElse(null));
					resourceMethodHolder.set(resolvedResourceMethod);
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
					lifecycleObserver.didStartRequestHandling(serverType, request, resourceMethodHolder.get());
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
						(metricsInvocation) -> metricsInvocation.didStartRequestHandling(serverType, request, resourceMethodHolder.get()));

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

							// Preserve HEAD's in-memory representation only in the transport result, never
							// in the bodyless MarshaledResponse exposed to lifecycle observers and logs.
							if (serverType == ServerType.HTTP && requireNonNull(requestHolder.get()).getHttpMethod() == HttpMethod.HEAD) {
								MarshaledResponseBody compressionBody = originalMarshaledResponse.getBody()
										.filter(body -> body instanceof MarshaledResponseBody.Bytes
												|| body instanceof MarshaledResponseBody.ByteBuffer).orElse(null);
								requestResult = requestResult.copy().headResponseCompressionBody(compressionBody).finish();
								requestResultHolder.set(requestResult);
							}

							// 1. Customize response for HEAD (e.g. remove body, set Content-Length header)
							updatedMarshaledResponse = applyHeadResponseIfApplicable(requestHolder.get(), updatedMarshaledResponse);

							// 2. Apply other standard response customizations (CORS, Content-Length)
							// Note that we don't want to write Content-Length for SSE "accepted" handshakes
							SseHandshakeResult sseHandshakeResult = requestResult.getSseHandshakeResult().orElse(null);
							boolean suppressContentLength = sseHandshakeResult != null && sseHandshakeResult instanceof SseHandshakeResult.Accepted;

							updatedMarshaledResponse = applyCommonPropertiesToMarshaledResponse(requestHolder.get(), updatedMarshaledResponse, suppressContentLength);
							if (!updatedMarshaledResponse.getStatusCode().equals(originalMarshaledResponse.getStatusCode())) {
								requestResult = requestResult.copy().headResponseCompressionBody(null).finish();
								requestResultHolder.set(requestResult);
							}

							// Update our result holder with the modified response if necessary
							if (originalMarshaledResponse != updatedMarshaledResponse) {
								marshaledResponseHolder.set(updatedMarshaledResponse);
								requestResultHolder.set(requestResult.copy()
										.marshaledResponse(updatedMarshaledResponse)
										.finish());
							}

							return updatedMarshaledResponse;
							} catch (Throwable t) {
								requestResultHolder.updateAndGet(result -> result == null ? null
										: result.copy().headResponseCompressionBody(null).finish());
								if (!sameInstance(t, resourceMethodResolutionExceptionHolder.get())) {
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
						HttpRequestResult requestResult = requestResultHolder.get();
						if (requestResult != null && !requestResult.getMarshaledResponse().getStatusCode()
								.equals(interceptorMarshaledResponse.getStatusCode()))
							requestResultHolder.set(requestResult.copy().headResponseCompressionBody(null).finish());
						marshaledResponseHolder.set(interceptorMarshaledResponse);
					});

					if (!didInvokeMarshaledResponseConsumer.get()) {
						requestResultHolder.set(null);
						throw new IllegalStateException(format("%s::interceptRequest must call responseWriter", RequestInterceptor.class.getSimpleName()));
					}
				} catch (Throwable t) {
					throwables.add(t);
					requestResultHolder.updateAndGet(result -> result == null ? null
							: result.copy().headResponseCompressionBody(null).finish());

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
								requestResultConsumer.accept(requestResult.copy()
										.marshaledResponse(requireNonNull(marshaledResponseHolder.get())).finish());
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
						Duration processingDuration = Duration.ofNanos(Math.max(0L,
								System.nanoTime() - processingStartedNanos));

						safelyCollectMetrics.accept(
								format("An exception occurred while invoking %s::didFinishRequestHandling", MetricsCollector.class.getSimpleName()),
								(metricsInvocation) -> metricsInvocation.didFinishRequestHandling(serverType, request, resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables)));

						try {
							lifecycleObserver.didFinishRequestHandling(serverType, request, resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables));
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

			requestResultHolder.updateAndGet(result -> result == null ? null
					: result.copy().headResponseCompressionBody(null).finish());

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
							requestResultConsumer.accept(requestResult.copy()
									.marshaledResponse(requireNonNull(marshaledResponseHolder.get())).finish());
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
					Duration processingDuration = Duration.ofNanos(Math.max(0L,
							System.nanoTime() - processingStartedNanos));

					safelyCollectMetrics.accept(
							format("An exception occurred while invoking %s::didFinishRequestHandling", MetricsCollector.class.getSimpleName()),
							(metricsInvocation) -> metricsInvocation.didFinishRequestHandling(serverType, request, resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables)));

					try {
						lifecycleObserver.didFinishRequestHandling(serverType, request, resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables));
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
		if (request.isContentTooLarge()) {
			ResourceMethod contentTooLargeResourceMethod =
					validateResolvedResourceMethod(resourceMethodResolver
							.resourceMethodForRequest(request, serverType).orElse(null));
			return HttpRequestResult.withMarshaledResponse(responseMarshaler.forContentTooLarge(request, contentTooLargeResourceMethod))
					.resourceMethod(resourceMethod)
					.build();
		}

		// Special short-circuit for OPTIONS *
		if (isOptionsSplat(request.getResourcePath()))
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
								.build();
					}

					// Reject
					return HttpRequestResult.withMarshaledResponse(responseMarshaler.forCorsPreflightRejected(request, corsPreflight))
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
								.build();
					}
				}
			} else if (request.getHttpMethod() == HttpMethod.HEAD) {
				// If there's a matching GET resource method for this HEAD request, then invoke it
				Request headGetRequest = request.copy().httpMethod(HttpMethod.GET).finish();
				ResourceMethod headGetResourceMethod =
						validateResolvedResourceMethod(resourceMethodResolver
								.resourceMethodForRequest(headGetRequest, serverType)
								.orElse(null));

				if (headGetResourceMethod != null)
					resourceMethod = headGetResourceMethod;
				else
					return HttpRequestResult.withMarshaledResponse(responseMarshaler.forNotFound(request))
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
							.build();
				} else {
					// no matching resource method found, it's a 404
					return HttpRequestResult.withMarshaledResponse(responseMarshaler.forNotFound(request))
							.build();
				}
			}
		}

		// Found a resource method - happy path.
		// 1. Get an instance of the resource class
		// 2. Get values to pass to the resource method on the resource class
		// 3. Invoke the resource method and use its return value to drive a response
		validateResolvedResourceMethod(resourceMethod);
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
			enforceBodylessStatusCode(marshaledResponse.getStatusCode(), marshaledResponse.getBody().isPresent() || marshaledResponse.getStreamingResponseBody().isPresent());

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

		enforceBodylessStatusCode(marshaledResponse.getStatusCode(), marshaledResponse.getBody().isPresent() || marshaledResponse.getStreamingResponseBody().isPresent());

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
			String headerName = requireNonNull(e.getKey());
			if (headerName.equalsIgnoreCase("Connection")
					|| headerName.equalsIgnoreCase("Keep-Alive"))
				continue;
			// Defensively copy so callers can't mutate after construction
			Set<String> values = e.getValue() == null ? Set.of() : Set.copyOf(e.getValue());
			finalHeaders.put(headerName, values);
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

		if (shouldOmitAutomaticContentLength(request, marshaledResponse))
			return marshaledResponse;

		// If Content-Length is not specified, specify as the number of bytes in the body
		return marshaledResponse.copy()
				.headers((mutableHeaders) -> {
					String contentLengthHeaderValue = String.valueOf(marshaledResponse.getBodyLength());
					mutableHeaders.put("Content-Length", Set.of(contentLengthHeaderValue));
				}).finish();
	}

	private boolean shouldOmitAutomaticContentLength(@NonNull Request request,
																									 @NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		int statusCode = marshaledResponse.getStatusCode();

		if ((statusCode >= 100 && statusCode < 200) || statusCode == 204 || statusCode == 304)
			return true;

		return request.getHttpMethod() == HttpMethod.HEAD && marshaledResponse.getBodyLength() == 0L;
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
		if (isOptionsSplat(request.getResourcePath()))
			return new LinkedHashMap<>();

		Map<HttpMethod, ResourceMethod> matchingResourceMethodsByHttpMethod = new LinkedHashMap<>(HttpMethod.values().length);

		for (HttpMethod httpMethod : HttpMethod.values()) {
			// Make a quick copy of the request to see if other paths match
			Request otherRequest = Request.withPath(httpMethod, request.getPath()).build();
			ResourceMethod resourceMethod =
					validateResolvedResourceMethod(resourceMethodResolver
							.resourceMethodForRequest(otherRequest, serverType)
							.orElse(null));

			if (resourceMethod != null)
				matchingResourceMethodsByHttpMethod.put(httpMethod, resourceMethod);
		}

		return matchingResourceMethodsByHttpMethod;
	}

	@Nullable
	private static ResourceMethod validateResolvedResourceMethod(
			@Nullable ResourceMethod resourceMethod) {
		if (resourceMethod != null)
			SokletFrameworkSetup.validateNoRemovedHttpServerInjection(
					resourceMethod);
		return resourceMethod;
	}

	@SuppressWarnings("ReferenceEquality")
	private static Boolean isOptionsSplat(@NonNull ResourcePath resourcePath) {
		requireNonNull(resourcePath);
		return resourcePath == ResourcePath.OPTIONS_SPLAT_RESOURCE_PATH;
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
				.body(format("HTTP %s: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(charset))
				.build();
	}

	/**
	 * Publishes shutdown intent, joins the lifecycle uninterruptibly, restores
	 * the caller's interrupt status, and applies the terminal result.
	 *
	 * @throws SokletShutdownIncompleteException if complete termination cannot be proven
	 * @throws SokletUnexpectedTerminationException if a lifecycle component terminated
	 * unexpectedly after readiness
	 * @throws IllegalStateException as a best-effort diagnostic when the current
	 * thread is contributing to the unpublished shutdown barrier
	 */
	@Override
	public void close() {
		shutdown();
		boolean interrupted = false;
		ShutdownResult result;

		try {
			for (;;) {
				try {
					result = awaitShutdown();
					break;
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}

			this.directLifecycle.throwIfUnsuccessfulShutdown(result);
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Returns a racy diagnostic snapshot of this one-shot lifecycle.
	 *
	 * @return current lifecycle status
	 */
	@NonNull
	public SokletStatus getStatus() {
		return this.directLifecycle.publicStatus();
	}

	/**
	 * Returns the immutable terminal result after it has been published.
	 *
	 * @return terminal result, otherwise empty
	 */
	@NonNull
	public Optional<@NonNull ShutdownResult> getShutdownResult() {
		return this.directLifecycle.publicResult();
	}

	void initializeForSimulator(@NonNull StartupContext startupContext,
			@NonNull DeadlineWaiter waiter) {
		this.directLifecycle.initializeForSimulator(startupContext, waiter);
	}

	@NonNull
	SokletDirectLifecycle getDirectLifecycle() {
		return this.directLifecycle;
	}

	@NonNull
	protected SokletConfig getSokletConfig() {
		return this.sokletConfig;
	}

	/**
	 * Returns the stable compatibility lock retained from earlier Soklet
	 * versions.  The one-shot lifecycle does not use this caller-obtainable lock
	 * for its internal state transitions, so holding it cannot delay lifecycle
	 * progress.
	 *
	 * @return passive compatibility lock
	 */
	@NonNull
	protected ReentrantLock getLock() {
		return this.lock;
	}

	/**
	 * Returns the stable compatibility reference initially containing the latch
	 * released when this one-shot lifecycle publishes its terminal result.
	 * Soklet retains that original latch directly, so replacing the exposed
	 * reference value cannot redirect lifecycle publication.
	 *
	 * @return one-shot shutdown-latch reference
	 */
	@NonNull
	protected AtomicReference<@NonNull CountDownLatch>
			getAwaitShutdownLatchReference() {
		return this.awaitShutdownLatchReference;
	}

	void releaseAwaitShutdownLatch() {
		this.awaitShutdownLatch.countDown();
	}

	@ThreadSafe
	static class DefaultSimulator implements Simulator {
		@Nullable
		private MockHttpServer server;
		@Nullable
		private MockSseServer sseServer;
		@NonNull
		private final SimulatorOptions simulatorOptions;
		private final @Nullable DefaultMcpServer mcpServer;
		private final @Nullable SimulatorScopeDispatchGate scopeDispatchGate;
		@NonNull
		private final Object mcpSimulationLock;
		@NonNull
		private final Object scopeStateLock;
		private volatile @Nullable SimulationSession mcpSimulationSession;
		private volatile @Nullable SimulationSession rejectedMcpSimulationSession;
		private volatile @Nullable StreamLifecycleCoordinator streamLifecycleCoordinator;
		private @Nullable Supplier<StreamLifecycleCoordinator> streamLifecycleCoordinatorFactoryForTests;
		private volatile @Nullable StreamLifecycleCoordinator sseLifecycleCoordinator;
		private @Nullable Supplier<StreamLifecycleCoordinator> sseLifecycleCoordinatorFactoryForTests;
		@NonNull
		private final AtomicBoolean closed;

		public DefaultSimulator(@Nullable MockHttpServer server,
											 @Nullable MockSseServer sseServer) {
			this(server, sseServer, SimulatorOptions.defaultInstance(), null, null);
		}

		public DefaultSimulator(@Nullable MockHttpServer server,
										@Nullable MockSseServer sseServer,
										@NonNull SimulatorOptions simulatorOptions) {
			this(server, sseServer, simulatorOptions, null, null);
		}

		DefaultSimulator(@Nullable MockHttpServer server,
				@Nullable MockSseServer sseServer,
				@NonNull SimulatorOptions simulatorOptions,
				@Nullable DefaultMcpServer mcpServer) {
			this(server, sseServer, simulatorOptions, mcpServer, null);
		}

		DefaultSimulator(@Nullable MockHttpServer server,
				@Nullable MockSseServer sseServer,
				@NonNull SimulatorOptions simulatorOptions,
				@Nullable DefaultMcpServer mcpServer,
				@Nullable SimulatorScopeDispatchGate scopeDispatchGate) {
			this.server = server;
			this.sseServer = sseServer;
			this.simulatorOptions = requireNonNull(simulatorOptions);
			this.mcpServer = mcpServer;
			this.scopeDispatchGate = scopeDispatchGate;
			this.mcpSimulationLock = new Object();
			this.scopeStateLock = new Object();
			this.closed = new AtomicBoolean();
		}

		@Override
		@NonNull
		public McpSimulation startMcpRequest(@NonNull Request request) {
			return startMcpRequest(requireNonNull(request),
					McpSimulationOptions.defaultInstance());
		}

		@Override
		@NonNull
		public McpSimulation startMcpRequest(@NonNull Request request,
				@NonNull McpSimulationOptions options) {
			Runnable releaseScope = enterScope(InternalLifecycleComponentType.MCP);
			try {
				synchronized (this.mcpSimulationLock) {
					if (this.mcpServer == null)
						throw new IllegalStateException(
								"You must specify a McpServer in your SokletConfig to simulate MCP requests");
					if (this.mcpSimulationSession == null
							&& this.scopeDispatchGate == null)
						this.mcpSimulationSession = this.mcpServer
								.openSimulationSession();
					if (this.mcpSimulationSession == null)
						throw new IllegalStateException(
								"The MCP simulator participant is not ready");
					return this.mcpSimulationSession.start(requireNonNull(request),
							requireNonNull(options));
				}
			} finally {
				releaseScope.run();
			}
		}

		void openMcpScope() {
			synchronized (this.mcpSimulationLock) {
				requireScopeOpenWhileLocked();
				if (this.mcpServer == null)
					throw new IllegalStateException(
							"The simulator scope has no MCP participant");
				if (this.mcpSimulationSession != null)
					throw new IllegalStateException(
							"The MCP simulator participant was already started");
				this.mcpServer.openSimulationSession(session -> {
					synchronized (this.scopeStateLock) {
						if (this.mcpSimulationSession != null
								|| this.rejectedMcpSimulationSession != null)
							throw new IllegalStateException(
									"The MCP simulator participant was already started");
						SimulationSession exactSession = requireNonNull(session);
						if (this.closed.get()) {
							// The runtime will reject and roll back this unclaimed session,
							// but custom execution or registration cleanup may finish later.
							// Retain its proof handle without exposing it as an active session.
							this.rejectedMcpSimulationSession = exactSession;
							requireScopeOpenWhileLocked();
						}
						this.mcpSimulationSession = exactSession;
					}
				});
			}
		}

		boolean sealScope() {
			// Close dispatch admission without waiting for a startup call that may be
			// inside the MCP session lock.  A request racing shutdown must lose at the
			// owner gate, and startup cancellation must remain deadline-bounded.
			synchronized (this.scopeStateLock) {
				if (!this.closed.compareAndSet(false, true))
					return false;
				if (this.scopeDispatchGate != null)
					this.scopeDispatchGate.seal();
				return true;
			}
		}

		void quiesceMcpScope() {
			SimulationSession session = mcpSimulationSession();
			if (session != null)
				session.quiesce();
		}

		void quiesceHttpScope() {
			StreamLifecycleCoordinator coordinator = this.streamLifecycleCoordinator;
			if (coordinator != null)
				coordinator.stopAdmission();
		}

		void forceHttpScope() {
			StreamLifecycleCoordinator coordinator = this.streamLifecycleCoordinator;
			if (coordinator != null)
				coordinator.force();
		}

		boolean awaitHttpScopeTermination(long absoluteDeadlineNanos,
				@NonNull NanoClock clock) throws InterruptedException {
			StreamLifecycleCoordinator coordinator = this.streamLifecycleCoordinator;
			if (coordinator == null)
				return true;
			long remaining = absoluteDeadlineNanos - requireNonNull(clock).nanoTime();
			return coordinator.awaitTermination(System.nanoTime() + Math.max(0L, remaining));
		}

		boolean httpScopeTerminationProven() {
			StreamLifecycleCoordinator coordinator = this.streamLifecycleCoordinator;
			return coordinator == null || coordinator.isTerminated();
		}

		@NonNull
		Set<InternalResidualActivityType> httpScopeResidualActivity() {
			StreamLifecycleCoordinator coordinator = this.streamLifecycleCoordinator;
			if (coordinator == null || coordinator.isTerminated())
				return Set.of();
			StreamLifecycleCoordinator.Snapshot snapshot = coordinator.snapshot();
			EnumSet<InternalResidualActivityType> residual = EnumSet.noneOf(InternalResidualActivityType.class);
			if (snapshot.reservations() > 0)
				residual.add(InternalResidualActivityType.STREAM);
			if (snapshot.queuedProducers() > 0 || snapshot.runningProducers() > 0 || snapshot.reservations() == 0)
				residual.add(InternalResidualActivityType.EXECUTOR_TASK);
			if (snapshot.callbacks() > 0 || snapshot.diagnostics() > 0 || snapshot.publisherLifetimes() > 0)
				residual.add(InternalResidualActivityType.CALLBACK);
			return Collections.unmodifiableSet(residual);
		}

		void setStreamLifecycleCoordinatorFactoryForTests(@NonNull Supplier<StreamLifecycleCoordinator> factory) {
			synchronized (this.scopeStateLock) {
				requireScopeOpenWhileLocked();
				if (this.streamLifecycleCoordinator != null)
					throw new IllegalStateException("Streaming lifecycle coordinator was already created");
				this.streamLifecycleCoordinatorFactoryForTests = requireNonNull(factory);
			}
		}

		@NonNull
		Optional<StreamLifecycleCoordinator> getStreamLifecycleCoordinatorForTests() {
			return Optional.ofNullable(this.streamLifecycleCoordinator);
		}

		private StreamLifecycleCoordinator.@Nullable Reservation reserveHttpStream() {
			synchronized (this.scopeStateLock) {
				if (this.closed.get())
					return null;
				if (this.streamLifecycleCoordinator == null) {
					MockHttpServer server = requireNonNull(this.server);
					LifecycleObserver observer = server.getSokletConfig().orElseThrow().getAggregateLifecycleObserver();
					this.streamLifecycleCoordinator = this.streamLifecycleCoordinatorFactoryForTests == null
							? new StreamLifecycleCoordinator(server.streamingLifecycleCapacity,
									server.streamingCallbackConcurrency, server.streamingCleanupTimeout, throwable -> {
								try {
									observer.didReceiveLogEvent(LogEvent.with(LogEventType.RESPONSE_STREAM_CLOSE_FAILED,
											"A simulated streaming response cleanup operation failed or exceeded its deadline")
											.throwable(throwable).build());
								} catch (Throwable observerFailure) {
									LifecycleObserverLogFallback.report(observerFailure);
								}
							})
							: requireNonNull(this.streamLifecycleCoordinatorFactoryForTests.get());
				}
				return this.streamLifecycleCoordinator.tryReserve();
			}
		}

		void quiesceSseScope() {
			MockSseServer server = this.sseServer;
			if (server != null)
				server.stop();
			StreamLifecycleCoordinator coordinator = this.sseLifecycleCoordinator;
			if (coordinator != null)
				coordinator.force();
		}

		void forceSseScope() {
			quiesceSseScope();
		}

		boolean awaitSseScopeTermination(long absoluteDeadlineNanos,
				@NonNull NanoClock clock) throws InterruptedException {
			StreamLifecycleCoordinator coordinator = this.sseLifecycleCoordinator;
			return coordinator == null || coordinator.awaitTermination(System.nanoTime()
					+ Math.max(0L, absoluteDeadlineNanos - requireNonNull(clock).nanoTime()));
		}

		boolean sseScopeTerminationProven() {
			StreamLifecycleCoordinator coordinator = this.sseLifecycleCoordinator;
			return coordinator == null || coordinator.isTerminated();
		}

		@NonNull
		Set<InternalResidualActivityType> sseScopeResidualActivity() {
			StreamLifecycleCoordinator coordinator = this.sseLifecycleCoordinator;
			if (coordinator == null || coordinator.isTerminated())
				return Set.of();
			StreamLifecycleCoordinator.Snapshot snapshot = coordinator.snapshot();
			EnumSet<InternalResidualActivityType> residual = EnumSet.noneOf(InternalResidualActivityType.class);
			if (snapshot.reservations() > 0)
				residual.add(InternalResidualActivityType.STREAM);
			if (snapshot.queuedProducers() > 0 || snapshot.runningProducers() > 0 || snapshot.reservations() == 0)
				residual.add(InternalResidualActivityType.EXECUTOR_TASK);
			if (snapshot.callbacks() > 0 || snapshot.diagnostics() > 0 || snapshot.retainedWork() > 0)
				residual.add(InternalResidualActivityType.CALLBACK);
			return Collections.unmodifiableSet(residual);
		}

		void setSseLifecycleCoordinatorFactoryForTests(@NonNull Supplier<StreamLifecycleCoordinator> factory) {
			synchronized (this.scopeStateLock) {
				requireScopeOpenWhileLocked();
				if (this.sseLifecycleCoordinator != null)
					throw new IllegalStateException("SSE lifecycle coordinator was already created");
				this.sseLifecycleCoordinatorFactoryForTests = requireNonNull(factory);
			}
		}

		@NonNull
		Optional<StreamLifecycleCoordinator> getSseLifecycleCoordinatorForTests() {
			return Optional.ofNullable(this.sseLifecycleCoordinator);
		}

		private StreamLifecycleCoordinator.@Nullable Reservation reserveSseStream() {
			synchronized (this.scopeStateLock) {
				if (this.closed.get())
					return null;
				if (this.sseLifecycleCoordinator == null) {
					MockSseServer server = requireNonNull(this.sseServer);
					LifecycleObserver observer = server.getSokletConfig().orElseThrow().getAggregateLifecycleObserver();
					this.sseLifecycleCoordinator = this.sseLifecycleCoordinatorFactoryForTests == null
							? new StreamLifecycleCoordinator(server.streamingLifecycleCapacity,
									DefaultSseServer.STREAMING_COORDINATOR_CALLBACK_CONCURRENCY,
									DefaultSseServer.STREAMING_COORDINATOR_CLEANUP_GRACE, throwable -> {
								try {
									observer.didReceiveLogEvent(LogEvent.with(LogEventType.SSE_SERVER_INTERNAL_ERROR,
											"A simulated SSE cleanup operation failed or exceeded its deadline")
											.throwable(throwable).build());
								} catch (Throwable observerFailure) {
									LifecycleObserverLogFallback.report(observerFailure);
								}
							}) : requireNonNull(this.sseLifecycleCoordinatorFactoryForTests.get());
				}
				return this.sseLifecycleCoordinator.tryReserve();
			}
		}

		void forceMcpScope() {
			SimulationSession session = mcpSimulationSession();
			if (session != null)
				session.force();
		}

		boolean awaitMcpScopeTermination(long absoluteDeadlineNanos,
				@NonNull NanoClock clock) throws InterruptedException {
			SimulationSession session = mcpEvidenceSession();
			return session == null
					|| session.awaitTermination(absoluteDeadlineNanos,
							requireNonNull(clock)::nanoTime);
		}

		boolean mcpScopeTerminationProven() {
			SimulationSession session = mcpEvidenceSession();
			return session == null || session.terminationProven();
		}

		@NonNull
		Set<InternalResidualActivityType> mcpScopeResidualActivity() {
			SimulationSession session = mcpEvidenceSession();
			if (session == null)
				return Set.of();
			com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.LifecycleEvidence
					evidence = session.lifecycleEvidence();
			EnumSet<InternalResidualActivityType> residual =
					EnumSet.noneOf(InternalResidualActivityType.class);
			if (evidence.executorTask() || evidence.subscriptionRegistration())
				residual.add(InternalResidualActivityType.EXECUTOR_TASK);
			if (evidence.stream())
				residual.add(InternalResidualActivityType.STREAM);
			if (evidence.callback())
				residual.add(InternalResidualActivityType.CALLBACK);
			return Collections.unmodifiableSet(residual);
		}

		void releaseMcpScopeEvidence() {
			SimulationSession activeSession = mcpSimulationSession();
			SimulationSession rejectedSession = rejectedMcpSimulationSession();
			if (activeSession != null)
				activeSession.releaseLifecycleEvidence();
			if (rejectedSession != null && rejectedSession != activeSession)
				rejectedSession.releaseLifecycleEvidence();
			clearMcpSimulationSession(activeSession);
			clearRejectedMcpSimulationSession(rejectedSession);
		}

		void releaseHttpAndSseScopeState() {
			MockHttpServer httpServer = this.server;
			MockSseServer serverSentEventsServer = this.sseServer;
			if (httpServer != null)
				httpServer.releaseSimulationScopeState();
			if (serverSentEventsServer != null)
				serverSentEventsServer.releaseSimulationScopeState();
		}

		@Nullable
		private SimulationSession mcpSimulationSession() {
			return this.mcpSimulationSession;
		}

		@Nullable
		private SimulationSession rejectedMcpSimulationSession() {
			return this.rejectedMcpSimulationSession;
		}

		@Nullable
		private SimulationSession mcpEvidenceSession() {
			SimulationSession activeSession = mcpSimulationSession();
			return activeSession == null
					? rejectedMcpSimulationSession() : activeSession;
		}

		private void clearMcpSimulationSession(@Nullable SimulationSession session) {
			synchronized (this.mcpSimulationLock) {
				if (this.mcpSimulationSession == session)
					this.mcpSimulationSession = null;
			}
		}

		private void clearRejectedMcpSimulationSession(
				@Nullable SimulationSession session) {
			synchronized (this.scopeStateLock) {
				if (this.rejectedMcpSimulationSession == session)
					this.rejectedMcpSimulationSession = null;
			}
		}

		@NonNull
		@Override
		public HttpRequestResult performHttpRequest(@NonNull Request request) {
			Runnable releaseScope = enterScope(InternalLifecycleComponentType.HTTP);
			try {
				// A caller may concurrently reuse an immutable Request. Each simulated
				// dispatch needs its own identity, shared by handling and stream callbacks.
				return performHttpRequestWhileAdmitted(requireNonNull(request).copy().finish());
			} finally {
				releaseScope.run();
			}
		}

		@NonNull
		private HttpRequestResult performHttpRequestWhileAdmitted(
				@NonNull Request request) {
			MockHttpServer server = this.server;

			if (server == null)
				throw new IllegalStateException(format("You must specify a %s in your %s to simulate requests",
						HttpServer.class.getSimpleName(), SokletConfig.class.getSimpleName()));

			AtomicReference<HttpRequestResult> requestResultHolder = new AtomicReference<>();
			HttpServer.RequestHandler requestHandler = server.getRequestHandler().orElse(null);

			if (requestHandler == null)
				throw new IllegalStateException("You must register a request handler prior to simulating requests");

			requestHandler.handleRequest(request, (requestResult -> {
				// Simulated responses do not run transport compression or retain its private HEAD input.
				requestResultHolder.set(requestResult.getHeadResponseCompressionBody().isEmpty() ? requestResult
						: requestResult.copy().headResponseCompressionBody(null).finish());
			}));

			return materializeStreamingResponse(request, requestResultHolder.get());
		}

		@NonNull
		private HttpRequestResult materializeStreamingResponse(@NonNull Request request,
																													 @Nullable HttpRequestResult requestResult) {
			requireNonNull(request);

			if (requestResult == null)
				throw new IllegalStateException("No HTTP request result was produced by the simulator");

			StreamingResponseBody stream = requestResult.getMarshaledResponse().getStreamingResponseBody().orElse(null);

			if (stream == null)
				return requestResult;

			StreamLifecycleCoordinator.Reservation reservation = reserveHttpStream();
			if (reservation == null)
				return requestResult.copy()
						.marshaledResponse(MarshaledResponse.withStatusCode(503).build())
						.finish();
			AtomicReference<HttpRequestResult> result = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			boolean entered = reservation.executeInline(() -> {
				try {
					result.set(materializeStreamingResponseWhileReserved(request, requestResult, stream, reservation));
				} catch (Throwable throwable) {
					failure.set(throwable);
				} finally {
					reservation.complete();
				}
			});
			if (!entered) {
				StreamTerminationReason reason = reservation.reason().orElse(StreamTerminationReason.SERVER_STOPPING);
				Throwable cause = reservation.cause().orElse(null);
				try {
					notifyDidTerminateSimulatorResponseStream(reservation, request, requestResult, Instant.now(),
							Duration.ZERO, reason, cause);
				} finally {
					reservation.complete();
				}
				throw new IllegalStateException("Simulated streaming response was canceled: " + reason.name(),
						new StreamingResponseCanceledException(reason, cause));
			}
			Throwable throwable = failure.get();
			if (throwable instanceof RuntimeException exception)
				throw exception;
			if (throwable instanceof Error error)
				throw error;
			if (throwable != null)
				throw new IllegalStateException("Simulated streaming response failed.", throwable);
			return requireNonNull(result.get());
		}

		@NonNull
		private HttpRequestResult materializeStreamingResponseWhileReserved(@NonNull Request request,
				@NonNull HttpRequestResult requestResult, @NonNull StreamingResponseBody stream,
				StreamLifecycleCoordinator.@NonNull Reservation reservation) {

			byte[] bytes;
			Instant streamStarted = Instant.now();

			try {
				bytes = materializeStreamingResponseBody(request, requestResult, stream, reservation);
				if (!reservation.completeTransport())
					throw new StreamingResponseCanceledException(reservation.reason().orElse(StreamTerminationReason.SERVER_STOPPING),
							reservation.cause().orElse(null));
				notifyDidTerminateSimulatorResponseStream(reservation, request, requestResult, streamStarted,
						Duration.between(streamStarted, Instant.now()), null, null);
			} catch (StreamingResponseCanceledException e) {
				StreamTerminationReason cancelationReason = e.getCancelationReason();
				Throwable cause = e.getCancelationCause().orElse(null);
				notifyDidTerminateSimulatorResponseStream(reservation, request, requestResult, streamStarted,
						Duration.between(streamStarted, Instant.now()), cancelationReason, cause);
				throw new IllegalStateException("Simulated streaming response was canceled: " + cancelationReason.name(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				notifyDidTerminateSimulatorResponseStream(reservation, request, requestResult, streamStarted,
						Duration.between(streamStarted, Instant.now()), StreamTerminationReason.APPLICATION_CANCELED, e);
				throw new IllegalStateException("Simulated streaming response was canceled: APPLICATION_CANCELED", e);
			} catch (Throwable t) {
				notifyDidTerminateSimulatorResponseStream(reservation, request, requestResult, streamStarted,
						Duration.between(streamStarted, Instant.now()), StreamTerminationReason.PRODUCER_FAILED, t);

				if (t instanceof Error error)
					throw error;

				throw new IllegalStateException("Simulated streaming response failed.", t);
			}

			MarshaledResponse marshaledResponse = requestResult.getMarshaledResponse().copy()
					.withoutStreamingResponseBody()
					.body(bytes)
					.finish();

			return requestResult.copy()
					.marshaledResponse(marshaledResponse)
					.finish();
		}

		private void notifyDidTerminateSimulatorResponseStream(StreamLifecycleCoordinator.@NonNull Reservation reservation,
				@NonNull Request request, @NonNull HttpRequestResult requestResult,
				@NonNull Instant establishedAt, @NonNull Duration streamDuration,
				@Nullable StreamTerminationReason cancelationReason, @Nullable Throwable throwable) {
			CountDownLatch delivered = new CountDownLatch(1);
			reservation.dispatchTermination(() -> {
				try {
					notifyDidTerminateSimulatorResponseStream(request, requestResult, establishedAt,
							streamDuration, cancelationReason, throwable);
				} finally {
					delivered.countDown();
				}
			});
			boolean interrupted = false;
			try {
				for (;;) {
					try {
						delivered.await();
						return;
					} catch (InterruptedException ignored) {
						interrupted = true;
					}
				}
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
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

			MockHttpServer server = this.server;
			SokletConfig sokletConfig = server == null ? null : server.getSokletConfig().orElse(null);

			if (sokletConfig == null)
				return;

			MarshaledResponse marshaledResponse = requestResult.getMarshaledResponse();
			ResourceMethod resourceMethod = requestResult.getResourceMethod().orElse(null);
			LifecycleObserver lifecycleObserver = sokletConfig.getAggregateLifecycleObserver();
			StreamingResponseHandle streamingResponse = new DefaultStreamingResponseHandle(ServerType.HTTP,
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
				} catch (Throwable observerFailure) {
					LifecycleObserverLogFallback.report(observerFailure);
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
				} catch (Throwable observerFailure) {
					LifecycleObserverLogFallback.report(observerFailure);
				}
			}
		}

		private void notifyDidReceiveSimulatorStreamCancelationCallbackFailure(@NonNull Request request,
																																					@NonNull HttpRequestResult requestResult,
																																					@NonNull Throwable throwable) {
			requireNonNull(request);
			requireNonNull(requestResult);
			requireNonNull(throwable);

			MockHttpServer server = this.server;
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
			} catch (Throwable observerFailure) {
				LifecycleObserverLogFallback.report(observerFailure);
			}
		}

		@NonNull
		private byte[] materializeStreamingResponseBody(@NonNull Request request,
																										@NonNull HttpRequestResult requestResult,
				@NonNull StreamingResponseBody stream,
				StreamLifecycleCoordinator.@NonNull Reservation reservation) throws Exception {
			requireNonNull(request);
			requireNonNull(requestResult);
			requireNonNull(stream);

			Consumer<Throwable> cleanupFailureConsumer = throwable ->
					notifyDidReceiveSimulatorStreamCancelationCallbackFailure(request, requestResult, throwable);
			SimulatorCancelationToken cancelationToken = new SimulatorCancelationToken(cleanupFailureConsumer, reservation);
			reservation.bindTermination(cancelationToken::deliverCancelation);
			SimulatorResponseOutput output = new SimulatorResponseOutput(cancelationToken,
					getSimulatorOptions().getStreamingResponseBodyLimitInBytes());

			try {
				cancelationToken.throwIfCanceled();
				if (stream instanceof StreamingResponseBody.PublisherBody publisherBody) {
					materializePublisher(publisherBody, output, cancelationToken, reservation);
				} else {
					ManagedResponseStream managedResponseStream = new ManagedResponseStream(request, cancelationToken,
							null, null, output, reservation::beginCleanup,
							throwable -> cancelSimulatorStream(cancelationToken, throwable), reservation::reportCleanupFailure);
					managedResponseStream.run(responseStream -> {
						if (stream instanceof StreamingResponseBody.WriterBody writerBody) {
							writerBody.getWriter().writeTo(responseStream);
						} else if (stream instanceof StreamingResponseBody.InputStreamBody inputStreamBody) {
							java.io.InputStream inputStream = responseStream.open(inputStreamBody.getInputStreamFactory());
							byte[] buffer = new byte[inputStreamBody.getBufferSizeInBytes()];
							int read;

							while ((read = inputStream.read(buffer)) >= 0) {
								responseStream.getCancelationToken().throwIfCanceled();
								if (read > 0)
									responseStream.write(ByteBuffer.wrap(buffer, 0, read));
							}
						} else if (stream instanceof StreamingResponseBody.ReaderBody readerBody) {
							Reader reader = responseStream.open(readerBody.getReaderFactory());
							materializeReader(readerBody, reader, responseStream);
						} else {
							throw new IllegalStateException(format("Unsupported streaming response body type: %s", stream.getClass().getName()));
						}
					});
				}
			} catch (Throwable t) {
				cancelSimulatorStream(cancelationToken, t);
				StreamTerminationReason reason = cancelationToken.getCancelationReason()
						.orElse(StreamTerminationReason.PRODUCER_FAILED);
				if (reason != StreamTerminationReason.PRODUCER_FAILED
						&& !(t instanceof InterruptedException && reason == StreamTerminationReason.APPLICATION_CANCELED)
						&& !(t instanceof StreamingResponseCanceledException canceledException
						&& canceledException.getCancelationReason() == reason)) {
					Throwable cause = cancelationToken.getCancelationCause().orElse(null);
					StreamingResponseCanceledException canceledException = new StreamingResponseCanceledException(reason, cause);
					if (!sameInstance(t, cause) && !(t instanceof InterruptedException))
						canceledException.addSuppressed(t);
					throw canceledException;
				}

				if (t instanceof Exception exception)
					throw exception;

				if (t instanceof Error error)
					throw error;

				throw new RuntimeException(t);
			}

			byte[] bytes = output.toByteArray();
			if (!reservation.completeProduction())
				cancelationToken.throwIfCanceled();
			cancelationToken.complete();
			return bytes;
		}

		private void cancelSimulatorStream(@NonNull SimulatorCancelationToken cancelationToken,
				@NonNull Throwable throwable) {
			if (throwable instanceof StreamingResponseCanceledException canceledException) {
				cancelationToken.cancel(canceledException.getCancelationReason(), canceledException.getCancelationCause().orElse(null));
			} else if (throwable instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				cancelationToken.cancel(cancelationToken.getCancelationReason()
						.orElse(StreamTerminationReason.APPLICATION_CANCELED), throwable);
			} else {
				cancelationToken.cancel(StreamTerminationReason.PRODUCER_FAILED, throwable);
			}
		}

		private void materializeReader(com.soklet.StreamingResponseBody.@NonNull ReaderBody readerBody,
																	 @NonNull Reader reader,
																	 @NonNull ResponseStream responseStream) throws IOException, InterruptedException, StreamingResponseCanceledException, CharacterCodingException {
			requireNonNull(readerBody);
			requireNonNull(reader);
			requireNonNull(responseStream);

			CharsetEncoder encoder = readerBody.newEncoder();
			int readSize = readerBody.getBufferSizeInCharacters();
			// Preserve single-character reads while leaving room for a carried high surrogate.
			CharBuffer charBuffer = CharBuffer.allocate(Math.max(2, readSize));
			ByteBuffer byteBuffer = ByteBuffer.allocate(Math.max(128, (int) Math.ceil(readSize * encoder.maxBytesPerChar())));

			while (true) {
				charBuffer.limit(charBuffer.position() + Math.min(readSize, charBuffer.remaining()));
				int read = reader.read(charBuffer);
				charBuffer.limit(charBuffer.capacity());
				if (read < 0)
					break;
				responseStream.getCancelationToken().throwIfCanceled();
				charBuffer.flip();
				encodeCharsForSimulator(encoder, charBuffer, byteBuffer, false, responseStream);
				charBuffer.compact();
			}

			charBuffer.flip();
			encodeCharsForSimulator(encoder, charBuffer, byteBuffer, true, responseStream);

			CoderResult result;
			do {
				result = encoder.flush(byteBuffer);
				writeEncodedBytesForSimulator(byteBuffer, responseStream);
				if (result.isError())
					result.throwException();
			} while (result.isOverflow());
		}

		private void encodeCharsForSimulator(@NonNull CharsetEncoder encoder,
																				 @NonNull CharBuffer charBuffer,
																				 @NonNull ByteBuffer byteBuffer,
																				 boolean endOfInput,
																				 @NonNull ResponseStream output) throws IOException, InterruptedException, StreamingResponseCanceledException, CharacterCodingException {
			CoderResult result;

			do {
				result = encoder.encode(charBuffer, byteBuffer, endOfInput);
				writeEncodedBytesForSimulator(byteBuffer, output);

				if (result.isError())
					result.throwException();
			} while (result.isOverflow());
		}

		private void writeEncodedBytesForSimulator(@NonNull ByteBuffer byteBuffer,
																							 @NonNull ResponseStream output) throws IOException, InterruptedException, StreamingResponseCanceledException {
			byteBuffer.flip();
			if (byteBuffer.hasRemaining())
				output.write(byteBuffer);
			byteBuffer.clear();
		}

		private void materializePublisher(com.soklet.StreamingResponseBody.@NonNull PublisherBody publisherBody,
				@NonNull SimulatorResponseOutput output,
				@NonNull SimulatorCancelationToken cancelationToken,
				StreamLifecycleCoordinator.@NonNull Reservation reservation) throws Exception {
			PublisherResponseStream.copy(publisherBody, cancelationToken, output, reservation,
					reservation::beginCleanup, throwable -> cancelSimulatorStream(cancelationToken, throwable),
					reservation::reportCleanupFailure);
		}

		@NonNull
		@Override
		public SseRequestResult performSseRequest(@NonNull Request request) {
			Runnable releaseScope = enterScope(InternalLifecycleComponentType.SSE);
			try {
				return performSseRequestWhileAdmitted(requireNonNull(request).copy().finish());
			} finally {
				releaseScope.run();
			}
		}

		@NonNull
		private SseRequestResult performSseRequestWhileAdmitted(
				@NonNull Request request) {
			MockSseServer sseServer = this.sseServer;

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
			if (requestResult == null)
				throw new IllegalStateException("SSE request handler did not provide a request result");

			SseHandshakeResult sseHandshakeResult = requestResult.getSseHandshakeResult().orElse(null);

			if (sseHandshakeResult == null)
				return new SseRequestResult.RequestFailed(requestResult);

			if (sseHandshakeResult instanceof SseHandshakeResult.Accepted acceptedHandshake) {
				SseClientInitializer clientInitializer = acceptedHandshake.getClientInitializer().orElse(null);
				StreamLifecycleCoordinator.Reservation reservation = reserveSseStream();
				if (reservation == null) {
					MarshaledResponse unavailable = sseServer.getSokletConfig().orElseThrow()
							.getResponseMarshaler().forServiceUnavailable(request, requestResult.getResourceMethod().orElse(null));
					return new SseRequestResult.RequestFailed(requestResult.copy()
							.marshaledResponse(unavailable)
							.response(Response.withStatusCode(unavailable.getStatusCode()).build())
							.sseHandshakeResult(null).finish());
				}

				try {
					// Create a synthetic logical response using values from the accepted handshake.
					if (requestResult.getResponse().isEmpty())
						requestResult = requestResult.copy()
								.response(Response.withStatusCode(200)
										.headers(acceptedHandshake.getHeaders())
										.cookies(acceptedHandshake.getCookies()).build()).finish();
					HandshakeAccepted handshakeAccepted = new HandshakeAccepted(acceptedHandshake, request,
							requestResult, sseServer, reservation);
					if (!handshakeAccepted.initialize(clientInitializer))
						throw new IllegalStateException("The simulated SSE connection terminated before activation");
					return handshakeAccepted;
				} catch (Throwable failure) {
					// An initializer already elected PRODUCER_FAILED; construction/activation
					// failures need the same reservation cleanup without replacing that winner.
					reservation.cancel(StreamTerminationReason.INTERNAL_ERROR, failure);
					reservation.complete();
					if (failure instanceof RuntimeException runtimeException)
						throw runtimeException;
					if (failure instanceof Error error)
						throw error;
					throw new IllegalStateException("The simulated SSE client initializer failed", failure);
				}
			}

			if (sseHandshakeResult instanceof SseHandshakeResult.Rejected rejectedHandshake)
				return new HandshakeRejected(rejectedHandshake, requestResult);

			throw new IllegalStateException(format("Encountered unexpected %s: %s", SseHandshakeResult.class.getSimpleName(), sseHandshakeResult));
		}

		@NonNull
		@Override
		public Simulator onBroadcastError(@Nullable Consumer<Throwable> onBroadcastError) {
			Runnable releaseScope = enterScope(InternalLifecycleComponentType.SSE);
			try {
				MockSseServer sseServer = this.sseServer;
				if (sseServer != null)
					sseServer.onBroadcastError(onBroadcastError);
				return this;
			} finally {
				releaseScope.run();
			}
		}

		@NonNull
		@Override
		public Simulator onUnicastError(@Nullable Consumer<Throwable> onUnicastError) {
			Runnable releaseScope = enterScope(InternalLifecycleComponentType.SSE);
			try {
				MockSseServer sseServer = this.sseServer;
				if (sseServer != null)
					sseServer.onUnicastError(onUnicastError);
				return this;
			} finally {
				releaseScope.run();
			}
		}

		@NonNull
		@Override
		public Optional<@NonNull HttpServer> getHttpServer() {
			requireScopeOpen();
			return Optional.ofNullable(this.server);
		}

		@NonNull
		@Override
		public Optional<@NonNull SseServer> getSseServer() {
			requireScopeOpen();
			return Optional.ofNullable(this.sseServer);
		}

		@NonNull
		@Override
		public Optional<@NonNull McpServer> getMcpServer() {
			requireScopeOpen();
			return Optional.ofNullable(this.mcpServer);
		}

		@NonNull
		Optional<MockSseServer> getSimulatedSseServer() {
			requireScopeOpen();
			return Optional.ofNullable(this.sseServer);
		}

		@NonNull
		private Runnable enterScope(
				@NonNull InternalLifecycleComponentType kind) {
			requireScopeOpen();
			return this.scopeDispatchGate == null
					? () -> {
					}
					: this.scopeDispatchGate.enter(requireNonNull(kind));
		}

		private void requireScopeOpen() {
			requireScopeOpenWhileLocked();
		}

		private void requireScopeOpenWhileLocked() {
			if (this.closed.get())
				throw new IllegalStateException("The simulator scope is closed.");
		}

		@NonNull
		SimulatorOptions getSimulatorOptions() {
			return this.simulatorOptions;
		}
	}

	@NotThreadSafe
	private static final class SimulatorResponseOutput implements ManagedResponseStream.Output {
		@NonNull
		private final ByteArrayOutputStream byteArrayOutputStream;
		@NonNull
		private final Integer limitInBytes;
		@NonNull
		private final SimulatorCancelationToken cancelationToken;
		private boolean closed;

		private SimulatorResponseOutput(@NonNull SimulatorCancelationToken cancelationToken,
				@NonNull Integer limitInBytes) {
			this.byteArrayOutputStream = new ByteArrayOutputStream();
			this.limitInBytes = requireNonNull(limitInBytes);
			this.cancelationToken = requireNonNull(cancelationToken);
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
			byteBuffer.position(byteBuffer.position() + bytesToWrite);
		}

		@Override
		public int stagingCapacityInBytes() {
			return Math.max(1, Math.min(8_192, this.limitInBytes));
		}

		@Override
		public void flush() throws StreamingResponseCanceledException {
			this.cancelationToken.throwIfCanceled();
		}

		@Override
		public boolean isOpen() {
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
		private static final Runnable NO_CALLBACKS = () -> {};
		private final Object lock = new Object();
		private boolean canceled;
		private boolean completed;
		private final StreamLifecycleCoordinator.@Nullable Reservation reservation;
		@Nullable
		private Set<CancelationCallbackRegistration> callbacks;
		@NonNull
		private final Consumer<Throwable> callbackFailureConsumer;
		@Nullable
		private StreamTerminationReason reason;
		@Nullable
		private Throwable cause;

		private SimulatorCancelationToken(@NonNull Consumer<Throwable> callbackFailureConsumer,
				StreamLifecycleCoordinator.@Nullable Reservation reservation) {
			this.callbackFailureConsumer = requireNonNull(callbackFailureConsumer);
			this.reservation = reservation;
		}

		@Override
		@NonNull
		public Boolean isCanceled() {
			synchronized (this.lock) {
				boolean canceled = this.canceled || this.reservation != null && this.reservation.isCanceled();
				return !isCompleted() && canceled;
			}
		}

		@Override
		@NonNull
		public Optional<StreamTerminationReason> getCancelationReason() {
			synchronized (this.lock) {
				Optional<StreamTerminationReason> reason = this.reason == null && this.reservation != null
						? this.reservation.reason() : Optional.ofNullable(this.reason);
				return isCompleted() ? Optional.empty() : reason;
			}
		}

		@Override
		@NonNull
		public Optional<Throwable> getCancelationCause() {
			synchronized (this.lock) {
				Optional<Throwable> cause = this.reason == null && this.reservation != null
						? this.reservation.cause() : Optional.ofNullable(this.cause);
				return isCompleted() ? Optional.empty() : cause;
			}
		}

		@Override
		@NonNull
		public CallbackRegistration onCancel(@NonNull Runnable callback) {
			requireNonNull(callback);
			CancelationCallbackRegistration registration = new CancelationCallbackRegistration(callback);
			boolean runImmediately;

			synchronized (this.lock) {
				if (isCompleted()) {
					registration.callback = null;
					return registration;
				}
				runImmediately = this.canceled;
				if (!runImmediately) {
					if (this.callbacks == null)
						this.callbacks = new LinkedHashSet<>();
					this.callbacks.add(registration);
				}
			}
			if (runImmediately)
				registration.invoke();
			return registration;
		}

		private boolean cancel(@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause) {
			requireNonNull(reason);
			if (reason == StreamTerminationReason.COMPLETED)
				throw new IllegalArgumentException("Cancelation reason cannot be COMPLETED");
			if (this.reservation != null)
				return this.reservation.cancel(reason, cause);
			Runnable callbacks = reserveCancelation(reason, cause);
			if (callbacks == null)
				return false;
			callbacks.run();
			return true;
		}

		private void deliverCancelation(@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
			Runnable callbacks = reserveCancelation(reason, cause);
			if (callbacks != null && !sameInstance(callbacks, NO_CALLBACKS))
				requireNonNull(this.reservation).dispatchCallbacks(callbacks);
		}

		@Nullable
		private Runnable reserveCancelation(@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {

			Set<CancelationCallbackRegistration> callbacksToRun;
			synchronized (this.lock) {
				if (this.canceled || isCompleted())
					return null;
				this.reason = reason;
				this.cause = cause;
				this.canceled = true;
				callbacksToRun = this.callbacks;
				this.callbacks = null;
			}
			if (callbacksToRun == null || callbacksToRun.isEmpty())
				return NO_CALLBACKS;
			return () -> {
				for (CancelationCallbackRegistration registration : callbacksToRun)
					registration.invoke();
				callbacksToRun.clear();
			};
		}

		private void complete() {
			Set<CancelationCallbackRegistration> completedCallbacks;
			synchronized (this.lock) {
				if (this.canceled || this.completed || this.reservation != null
						&& !this.reservation.isProductionComplete() && this.reservation.isCanceled())
					return;
				this.completed = true;
				completedCallbacks = this.callbacks;
				this.callbacks = null;
			}
			// Completion may release many application registrations. Keep traversal
			// outside the token monitor used by framework cancellation signals.
			if (completedCallbacks != null) {
				for (CancelationCallbackRegistration registration : completedCallbacks)
					registration.callback = null;
				completedCallbacks.clear();
			}
		}

		private boolean isCompleted() {
			synchronized (this.lock) {
				return this.completed || this.reservation != null && this.reservation.isProductionComplete();
			}
		}

		private void runCallback(@NonNull Runnable callback) {
			try {
				callback.run();
			} catch (Throwable throwable) {
				try {
					this.callbackFailureConsumer.accept(throwable);
				} catch (Throwable ignored) {
					// Diagnostic observers cannot suppress remaining callbacks.
				}
			}
		}

		private final class CancelationCallbackRegistration implements CallbackRegistration {
			@Nullable
			private volatile Runnable callback;

			private CancelationCallbackRegistration(@NonNull Runnable callback) {
				this.callback = callback;
			}

			@Override
			public void close() {
				synchronized (SimulatorCancelationToken.this.lock) {
					this.callback = null;
					if (SimulatorCancelationToken.this.callbacks != null)
						SimulatorCancelationToken.this.callbacks.remove(this);
				}
			}

			private void invoke() {
				Runnable callback;
				synchronized (SimulatorCancelationToken.this.lock) {
					callback = this.callback;
					this.callback = null;
				}
				if (callback != null)
					runCallback(callback);
			}
		}
	}

	/**
	 * Mock server that doesn't touch the network at all, useful for testing.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	static class MockHttpServer implements HttpServer {
		@NonNull
		private final TransportIdentity transportIdentity = TransportIdentity.create();
		private final int streamingLifecycleCapacity;
		private final int streamingCallbackConcurrency;
		@NonNull
		private final Duration streamingCleanupTimeout;
		@Nullable
		private volatile SokletConfig sokletConfig;
		private volatile HttpServer.@Nullable RequestHandler requestHandler;

		MockHttpServer() {
			this(null);
		}

		MockHttpServer(@Nullable HttpServer sourceHttpServer) {
			if (sourceHttpServer instanceof DefaultHttpServer defaultHttpServer) {
				this.streamingLifecycleCapacity = defaultHttpServer.getStreamingLifecycleCapacity();
				this.streamingCallbackConcurrency = defaultHttpServer.getStreamingCallbackConcurrency();
				this.streamingCleanupTimeout = defaultHttpServer.getStreamingCleanupTimeout();
			} else if (sourceHttpServer instanceof MockHttpServer mockHttpServer) {
				this.streamingLifecycleCapacity = mockHttpServer.streamingLifecycleCapacity;
				this.streamingCallbackConcurrency = mockHttpServer.streamingCallbackConcurrency;
				this.streamingCleanupTimeout = mockHttpServer.streamingCleanupTimeout;
			} else {
				this.streamingLifecycleCapacity = DefaultHttpServer.DEFAULT_STREAMING_LIFECYCLE_CAPACITY;
				this.streamingCallbackConcurrency = DefaultHttpServer.DEFAULT_STREAMING_CALLBACK_CONCURRENCY;
				this.streamingCleanupTimeout = DefaultHttpServer.DEFAULT_STREAMING_CLEANUP_TIMEOUT;
			}
		}

		@NonNull
		@Override
		public TransportIdentity getTransportIdentity() {
			return this.transportIdentity;
		}

		@NonNull
		@Override
		public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext attachmentContext,
				@NonNull StartupContext startupContext) {
			requireNonNull(startupContext);
			HttpTransportAttachmentContext exactContext = requireNonNull(
					attachmentContext);
			initialize(exactContext.getSokletConfig(),
					exactContext.getAdmissionFencedRequestHandler());
			TransportTerminationSignal signal = exactContext.getTransportTerminationSignal();
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					requireNonNull(context);
				}

				@Override
				public void shutdownGracefully(@NonNull ShutdownContext context) {
					requireNonNull(context);
					signal.signalTerminated();
				}

				@Override
				public void shutdownForcibly(@NonNull ShutdownContext context) {
					requireNonNull(context);
					signal.signalTerminated();
				}
			};
		}

		public void start() {
			// No-op
		}

		public void stop() {
			// No-op
		}

		@NonNull
		public Boolean isStarted() {
			return true;
		}

		public synchronized void initialize(@NonNull SokletConfig sokletConfig,
				@NonNull RequestHandler requestHandler) {
			requireNonNull(sokletConfig);
			requireNonNull(requestHandler);

			this.sokletConfig = sokletConfig;
			this.requestHandler = requestHandler;
		}

		synchronized void releaseSimulationScopeState() {
			this.sokletConfig = null;
			this.requestHandler = null;
		}

		@NonNull
		protected synchronized Optional<SokletConfig> getSokletConfig() {
			return Optional.ofNullable(this.sokletConfig);
		}

		@NonNull
		protected synchronized Optional<RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}
	}


	/**
	 * Mock Server-Sent Event unicaster that doesn't touch the network at all, useful for testing.
	 */
	@ThreadSafe
	static class MockSseUnicaster implements SseUnicaster {
		private final Request request;
		private final Consumer<SseEvent> eventConsumer;
		private final Consumer<SseComment> commentConsumer;
		private final Object initializerLock = new Object();
		private boolean initializing;

		MockSseUnicaster(@NonNull Request request,
				@NonNull Consumer<SseEvent> eventConsumer, @NonNull Consumer<SseComment> commentConsumer) {
			this.request = requireNonNull(request);
			this.eventConsumer = requireNonNull(eventConsumer);
			this.commentConsumer = requireNonNull(commentConsumer);
		}

		void beginInitializer() {
			synchronized (this.initializerLock) { this.initializing = true; }
		}
		void finishInitializer() {
			synchronized (this.initializerLock) { this.initializing = false; }
		}

		@Override public void unicastEvent(@NonNull SseEvent event) {
			synchronized (this.initializerLock) {
				if (!this.initializing)
					throw new IllegalStateException("The SSE unicaster is available only during client initialization");
				this.eventConsumer.accept(requireNonNull(event));
			}
		}
		@Override public void unicastComment(@NonNull SseComment comment) {
			synchronized (this.initializerLock) {
				if (!this.initializing)
					throw new IllegalStateException("The SSE unicaster is available only during client initialization");
				this.commentConsumer.accept(requireNonNull(comment));
			}
		}
		@Override public @NonNull ResourcePath getResourcePath() { return this.request.getResourcePath(); }
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
		private final Set<HandshakeAccepted> connections = ConcurrentHashMap.newKeySet();
		@NonNull
		private final AtomicReference<Consumer<Throwable>> broadcastErrorHandler;
		@NonNull
		private final Consumer<LogEvent> logEventConsumer;

		public MockSseBroadcaster(@NonNull ResourcePath resourcePath,
															@NonNull AtomicReference<Consumer<Throwable>> broadcastErrorHandler,
															@NonNull Consumer<LogEvent> logEventConsumer) {
			requireNonNull(resourcePath);
			requireNonNull(broadcastErrorHandler);
			requireNonNull(logEventConsumer);

			this.resourcePath = resourcePath;
			this.eventConsumers = new ConcurrentHashMap<>();
			this.commentConsumers = new ConcurrentHashMap<>();
			this.broadcastErrorHandler = broadcastErrorHandler;
			this.logEventConsumer = logEventConsumer;
		}

		@NonNull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@NonNull
		@Override
		public synchronized Long getClientCount() {
			return Long.valueOf(getEventConsumers().size() + getCommentConsumers().size() - this.connections.size());
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
					Object clientContext = sameInstance(context, NULL_CONTEXT_SENTINEL)
							? null : context;
					T key = keySelector.apply(clientContext);

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
					Object clientContext = sameInstance(context, NULL_CONTEXT_SENTINEL)
							? null : context;
					T key = keySelector.apply(clientContext);

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

		synchronized void registerConnection(HandshakeAccepted connection, Consumer<SseEvent> events,
				Consumer<SseComment> comments, @Nullable Object context) {
			this.connections.add(connection);
			registerEventConsumer(events, context);
			registerCommentConsumer(comments, context);
		}

		synchronized void unregisterConnection(HandshakeAccepted connection, Consumer<SseEvent> events,
				Consumer<SseComment> comments) {
			unregisterEventConsumer(events);
			unregisterCommentConsumer(comments);
			this.connections.remove(connection);
		}

		synchronized void releaseSimulationScopeState() {
			this.connections.clear();
			this.eventConsumers.clear();
			this.commentConsumers.clear();
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

			safelyLog(LogEvent.with(LogEventType.SSE_SERVER_INTERNAL_ERROR,
							"SSE simulator broadcast consumer failed")
					.throwable(throwable)
					.build());
		}

		protected void safelyLog(@NonNull LogEvent logEvent) {
			requireNonNull(logEvent);

			try {
				this.logEventConsumer.accept(logEvent);
			} catch (Throwable ignored) {
				// No safe fallback sink is available here.
			}
		}
	}

	/**
	 * Mock Server-Sent Event server that doesn't touch the network at all, useful for testing.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	static class MockSseServer implements SseServer {
		@NonNull
		private final TransportIdentity transportIdentity;
		final int streamingLifecycleCapacity;
		final int connectionQueueCapacity;
		@Nullable
		private volatile SokletConfig sokletConfig;
		private volatile SseServer.@Nullable RequestHandler requestHandler;
		@NonNull
		private volatile Set<@NonNull ResourcePathDeclaration>
				resourcePathDeclarations;
		private volatile boolean started;
		@NonNull
		private final ConcurrentHashMap<@NonNull ResourcePath, @NonNull MockSseBroadcaster> broadcastersByResourcePath;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> broadcastErrorHandler;
		@NonNull
		private final AtomicReference<Consumer<Throwable>> unicastErrorHandler;

		public MockSseServer() { this(null); }

		MockSseServer(@Nullable SseServer sourceSseServer) {
			if (sourceSseServer instanceof DefaultSseServer server) {
				this.streamingLifecycleCapacity = server.getStreamingLifecycleCapacity();
				this.connectionQueueCapacity = server.getConnectionQueueCapacity();
			} else if (sourceSseServer instanceof MockSseServer server) {
				this.streamingLifecycleCapacity = server.streamingLifecycleCapacity;
				this.connectionQueueCapacity = server.connectionQueueCapacity;
			} else {
				this.streamingLifecycleCapacity = DefaultSseServer.DEFAULT_STREAMING_LIFECYCLE_CAPACITY;
				this.connectionQueueCapacity = 128;
			}
			this.transportIdentity = TransportIdentity.create();
			this.broadcastersByResourcePath = new ConcurrentHashMap<>();
			this.broadcastErrorHandler = new AtomicReference<>();
			this.unicastErrorHandler = new AtomicReference<>();
			this.resourcePathDeclarations = Set.of();
		}

		@NonNull
		@Override
		public TransportIdentity getTransportIdentity() {
			return this.transportIdentity;
		}

		@NonNull
		@Override
		public TransportRuntime attach(
				@NonNull SseTransportAttachmentContext attachmentContext,
				@NonNull StartupContext startupContext) {
			requireNonNull(startupContext);
			SseTransportAttachmentContext exactContext = requireNonNull(
					attachmentContext);
			initialize(exactContext.getSokletConfig(),
					exactContext.getAdmissionFencedRequestHandler());
			TransportTerminationSignal signal = exactContext.getTransportTerminationSignal();
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					requireNonNull(context);
					MockSseServer.this.started = true;
				}

				@Override
				public void shutdownGracefully(@NonNull ShutdownContext context) {
					requireNonNull(context);
					MockSseServer.this.started = false;
					signal.signalTerminated();
				}

				@Override
				public void shutdownForcibly(@NonNull ShutdownContext context) {
					requireNonNull(context);
					MockSseServer.this.started = false;
					signal.signalTerminated();
				}
			};
		}

		public void start() {
			this.started = true;
		}

		public void stop() {
			this.started = false;
		}

		@NonNull
		public Boolean isStarted() {
			return this.started;
		}

		@NonNull
		@Override
		public synchronized Optional<? extends @NonNull SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) {
			if (resourcePath == null || !this.started
					|| this.sokletConfig == null)
				return Optional.empty();
			if (this.resourcePathDeclarations.stream().noneMatch(
					declaration -> declaration.matches(resourcePath)))
				return Optional.empty();

			MockSseBroadcaster broadcaster = getBroadcastersByResourcePath()
					.computeIfAbsent(resourcePath, rp -> new MockSseBroadcaster(rp, broadcastErrorHandler, this::safelyLog));

			return Optional.of(broadcaster);
		}

		void registerConnection(HandshakeAccepted connection, ResourcePath resourcePath,
				Consumer<SseEvent> events, Consumer<SseComment> comments, @Nullable Object context) {
			this.broadcastersByResourcePath.computeIfAbsent(resourcePath,
					rp -> new MockSseBroadcaster(rp, this.broadcastErrorHandler, this::safelyLog))
					.registerConnection(connection, events, comments, context);
		}

		void unregisterConnection(HandshakeAccepted connection, ResourcePath resourcePath,
				Consumer<SseEvent> events, Consumer<SseComment> comments) {
			MockSseBroadcaster broadcaster = this.broadcastersByResourcePath.get(resourcePath);
			if (broadcaster != null)
				broadcaster.unregisterConnection(connection, events, comments);
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
					.computeIfAbsent(resourcePath, rp -> new MockSseBroadcaster(rp, broadcastErrorHandler, this::safelyLog));

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
					.computeIfAbsent(resourcePath, rp -> new MockSseBroadcaster(rp, broadcastErrorHandler, this::safelyLog));

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

		public synchronized void initialize(@NonNull SokletConfig sokletConfig,
				SseServer.@NonNull RequestHandler requestHandler) {
			requireNonNull(sokletConfig);
			requireNonNull(requestHandler);

			this.sokletConfig = sokletConfig;
			this.requestHandler = requestHandler;
			this.resourcePathDeclarations = Set.copyOf(sokletConfig
					.getResourceMethodResolver().getResourceMethods().stream()
					.filter(ResourceMethod::isSseEventSource)
					.map(ResourceMethod::getResourcePathDeclaration)
					.toList());
		}

		synchronized void releaseSimulationScopeState() {
			this.started = false;
			this.sokletConfig = null;
			this.requestHandler = null;
			this.resourcePathDeclarations = Set.of();
			for (MockSseBroadcaster broadcaster
					: this.broadcastersByResourcePath.values())
				broadcaster.releaseSimulationScopeState();
			this.broadcastersByResourcePath.clear();
			this.broadcastErrorHandler.set(null);
			this.unicastErrorHandler.set(null);
		}

		public void onBroadcastError(@Nullable Consumer<Throwable> onBroadcastError) {
			this.broadcastErrorHandler.set(onBroadcastError);
		}

		public void onUnicastError(@Nullable Consumer<Throwable> onUnicastError) {
			this.unicastErrorHandler.set(onUnicastError);
		}

		void safelyLog(@NonNull LogEvent logEvent) {
			requireNonNull(logEvent);

			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig == null)
				return;

			try {
				sokletConfig.getAggregateLifecycleObserver().didReceiveLogEvent(logEvent);
			} catch (Throwable observerFailure) {
				LifecycleObserverLogFallback.report(observerFailure);
			}
		}

		@NonNull
		protected synchronized Optional<SokletConfig> getSokletConfig() {
			return Optional.ofNullable(this.sokletConfig);
		}

		@NonNull
		protected synchronized Optional<SseServer.RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}

		@NonNull
		protected ConcurrentHashMap<@NonNull ResourcePath, @NonNull MockSseBroadcaster> getBroadcastersByResourcePath() {
			return this.broadcastersByResourcePath;
		}

		@NonNull
		protected AtomicReference<Consumer<Throwable>> getBroadcastErrorHandler() {
			return this.broadcastErrorHandler;
		}

		@NonNull
		protected AtomicReference<Consumer<Throwable>> getUnicastErrorHandler() {
			return this.unicastErrorHandler;
		}
	}

}
