/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.soklet.TestSupport.findFreePort;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class HttpTransportResponseFeedbackIsolationTests {
	@Test
	void nestedAdmissionRejectedHttpWriteCannotReplaceAnIndependentResponse() throws Exception {
		CountDownLatch quiesced = new CountDownLatch(1);
		ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(2), Thread.ofVirtual().factory()) {
			@Override public void shutdown() { super.shutdown(); quiesced.countDown(); }
		};
		CustomHttpTransport outer = new CustomHttpTransport();
		HeldHttpDecorator inner = new HeldHttpDecorator((DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1")
				.requestHandlerTimeout(Duration.ofSeconds(15))
				.requestHandlerExecutorServiceSupplier(() -> executor).build());
		Observation observation = new Observation();
		AtomicInteger rejectionResponses = new AtomicInteger();
		try {
			Soklet.run(httpConfig(outer).lifecycleObserver(observation).build(), () -> {
				// This owner is stopped explicitly while the admitted inner dispatch is held.
				try (SokletTestScope innerAppScope = SokletTestScope.of(Soklet.fromConfig(httpConfig(inner)
						.responseMarshaler(ResponseMarshaler.builder().serviceUnavailableHandler((request, method) -> {
							rejectionResponses.incrementAndGet();
							// Force the built-in transport to replace this admission rejection.
							return MarshaledResponse.fromStatusCode(100);
						}).build()).build()))) {
					Soklet innerApp = innerAppScope.soklet();
					innerApp.start();
					try (Socket socket = request(inner.delegate.getEventLoop().orElseThrow().getPort(), "/outcome")) {
						await(inner.received);
						var shutdown = innerApp.shutdown().toCompletableFuture();
						await(quiesced);
						outer.handler.handleRequest(Request.fromPath(HttpMethod.GET, "/outcome"), result -> {
							try {
								// The fresh Request has no admission belonging to the held network dispatch.
								inner.upstream.handleRequest(Request.fromPath(HttpMethod.GET, "/outcome"), inner.writer.get());
							} catch (RuntimeException independentInnerFailure) {
								// This independent operation contains its own transport failures. With
								// the legacy exception signal, this also kept replacement feedback local.
							}
							// The outer transport accepts its own response unchanged.
						});
						String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
						assertTrue(wire.startsWith("HTTP/1.1 500"), wire);
						assertEquals(1, rejectionResponses.get());
						assertEquals(200, observation.written.get().getStatusCode());
						assertEquals(200, observation.finished.get().getStatusCode());
						assertTrue(observation.failures.get().isEmpty(), observation.failures.get().toString());
						inner.release.countDown();
						assertTrue(shutdown.get(5, TimeUnit.SECONDS).isComplete());
					} finally {
						inner.release.countDown();
					}
				}
			});
		} finally {
			inner.release.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
		}
	}

	@Test
	void sseCallbackThreadHopRetainsInvalidFramingReplacement() throws Exception {
		assertSseReplacementAcrossThreadHops(false, true, false);
	}

	@Test
	void sseHandlerAndCallbackThreadHopsRetainInvalidFramingReplacement() throws Exception {
		assertSseReplacementAcrossThreadHops(true, true, false);
	}

	@Test
	void sseFreshRequestRetainsInvalidFramingReplacement() throws Exception {
		assertSseReplacementAcrossThreadHops(false, false, true);
	}

	@Test
	void sseFreshRequestAcrossHandlerAndCallbackThreadHopsRetainsInvalidFramingReplacement() throws Exception {
		assertSseReplacementAcrossThreadHops(true, true, true);
	}

	@Test
	void sseNestedFreshRedispatchKeepsReplacementWithInnerRequest() throws Exception {
		assertSseReplacementAcrossThreadHops(false, false, false, true);
	}

	private static void assertSseReplacementAcrossThreadHops(boolean moveHandler, boolean moveCallback,
			boolean freshRequest) throws Exception {
		assertSseReplacementAcrossThreadHops(moveHandler, moveCallback, freshRequest, false);
	}

	private static void assertSseReplacementAcrossThreadHops(boolean moveHandler, boolean moveCallback,
			boolean freshRequest, boolean redispatchFresh) throws Exception {
		int port = findFreePort();
		DefaultSseServer delegate = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1")
				.requestHandlerTimeout(Duration.ofSeconds(15)).verifyConnectionOnceEstablished(false).build();
		SseServer decorator = new SseServer() {
			@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
			@Override public Optional<? extends SseBroadcaster> acquireBroadcaster(ResourcePath path) {
				return delegate.acquireBroadcaster(path);
			}
			@Override public TransportRuntime attach(SseTransportAttachmentContext context, StartupContext startup) {
				RequestHandler upstream = context.getAdmissionFencedRequestHandler();
				return context.attachTransparentDelegate(delegate, (request, consumer) -> {
					Consumer<HttpRequestResult> resultConsumer = result -> {
						Runnable deliver = () -> {
							assertInstanceOf(SseHandshakeResult.Accepted.class, result.getSseHandshakeResult().orElseThrow());
							Map<String, List<String>> headers = new LinkedHashMap<>(result.getMarshaledResponse().getHeaders());
							headers.put("Content-Length", List.of("1"));
							// Add invalid framing after interceptor validation so the actual SSE
							// writer must select its finite 500 fallback.
							consumer.accept(result.copy().marshaledResponse(result.getMarshaledResponse().copy()
									.headers(headers).finish()).finish());
						};
						if (moveCallback) runOnJoinedThread(deliver);
						else deliver.run();
					};
					Runnable handle = () -> upstream.handleRequest(freshRequest
							? Request.fromPath(request.getHttpMethod(), request.getPath()) : request.copy().finish(), result -> {
						if (redispatchFresh) {
							assertEquals(404, result.getMarshaledResponse().getStatusCode());
							upstream.handleRequest(Request.fromPath(HttpMethod.GET, "/events"), resultConsumer);
						} else resultConsumer.accept(result);
					});
					if (moveHandler) runOnJoinedThread(handle);
					else handle.run();
				});
			}
		};
		Observation observation = new Observation();
		SseResource resource = new SseResource();
		SokletConfig config = SokletConfig.withSseServer(decorator)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SseResource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == SseResource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}
				}).lifecycleObserver(observation).lifecyclePolicy(lifecyclePolicy()).build();
		ShutdownResult shutdownResult = Soklet.run(config, () -> {
			try (Socket socket = request(port, redispatchFresh ? "/missing" : "/events")) {
				String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				assertTrue(wire.startsWith("HTTP/1.1 500"), wire);
				await(observation.handlingFinished);
				await(observation.handshakeFailed);
				assertEquals(500, observation.writtenByPath.get("/events").getStatusCode());
				assertEquals(500, observation.finishedByPath.get("/events").getStatusCode());
				List<Throwable> failures = observation.failuresByPath.get("/events");
				assertEquals(1, failures.size());
				assertInstanceOf(IllegalArgumentException.class, failures.get(0));
				assertSame(failures.get(0), observation.handshakeFailure.get());
				if (redispatchFresh) {
					assertEquals(404, observation.writtenByPath.get("/missing").getStatusCode());
					assertEquals(404, observation.finishedByPath.get("/missing").getStatusCode());
					assertTrue(observation.failuresByPath.get("/missing").isEmpty());
				}
				assertEquals(SseConnection.HandshakeFailureReason.INTERNAL_ERROR, observation.handshakeFailureReason.get());
				assertEquals(0, resource.initializers.get());
				assertEquals(0, observation.established.get());
			}
		});
		assertTrue(shutdownResult.isComplete());
	}

	private static void runOnJoinedThread(Runnable action) {
		FutureTask<Void> task = new FutureTask<>(action, null);
		Thread thread = Thread.ofVirtual().name("sse-feedback-decorator").start(task);
		try {
			task.get(5, TimeUnit.SECONDS);
		} catch (ExecutionException failure) {
			if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
			if (failure.getCause() instanceof Error error) throw error;
			throw new IllegalStateException(failure.getCause());
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new AssertionError(failure);
		} catch (java.util.concurrent.TimeoutException failure) {
			throw new AssertionError(failure);
		} finally {
			task.cancel(true);
			try {
				thread.join(1000);
			} catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
				throw new AssertionError(failure);
			}
			assertFalse(thread.isAlive(), "The joined decorator worker must retire");
		}
	}

	private static SokletConfig.Builder httpConfig(HttpServer server) {
		return SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HttpResource.class)))
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(lifecyclePolicy());
	}

	private static LifecyclePolicy lifecyclePolicy() {
		return LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(2))
				.gracefulShutdownTimeout(Duration.ofSeconds(3)).forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	}

	private static Socket request(int port, String path) throws Exception {
		Socket socket = new Socket("127.0.0.1", port);
		socket.setSoTimeout(5000);
		socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
		socket.getOutputStream().flush();
		return socket;
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
		catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
	}

	private static final class HeldHttpDecorator implements HttpServer {
		private final DefaultHttpServer delegate;
		private final AtomicReference<Consumer<HttpRequestResult>> writer = new AtomicReference<>();
		private final CountDownLatch received = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private RequestHandler upstream;
		private HeldHttpDecorator(DefaultHttpServer delegate) { this.delegate = delegate; }
		@Override public TransportIdentity getTransportIdentity() { return this.delegate.getTransportIdentity(); }
		@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
			this.upstream = context.getAdmissionFencedRequestHandler();
			return context.attachTransparentDelegate(this.delegate, (request, consumer) -> {
				this.writer.set(consumer);
				this.received.countDown();
				await(this.release);
			});
		}
	}

	private static final class CustomHttpTransport implements HttpServer {
		private final TransportIdentity identity = TransportIdentity.create();
		private RequestHandler handler;
		@Override public TransportIdentity getTransportIdentity() { return this.identity; }
		@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
			this.handler = context.getAdmissionFencedRequestHandler();
			TransportTerminationSignal termination = context.getTransportTerminationSignal();
			return new TransportRuntime() {
				@Override public void start(StartupContext startup) {}
				@Override public void shutdownGracefully(ShutdownContext shutdown) { termination.signalTerminated(); }
				@Override public void shutdownForcibly(ShutdownContext shutdown) { termination.signalTerminated(); }
			};
		}
	}

	private static final class Observation implements LifecycleObserver {
		private final AtomicReference<MarshaledResponse> written = new AtomicReference<>();
		private final AtomicReference<MarshaledResponse> finished = new AtomicReference<>();
		private final AtomicReference<List<Throwable>> failures = new AtomicReference<>();
		private final Map<String, MarshaledResponse> writtenByPath = new ConcurrentHashMap<>();
		private final Map<String, MarshaledResponse> finishedByPath = new ConcurrentHashMap<>();
		private final Map<String, List<Throwable>> failuresByPath = new ConcurrentHashMap<>();
		private final AtomicReference<Throwable> handshakeFailure = new AtomicReference<>();
		private final AtomicReference<SseConnection.HandshakeFailureReason> handshakeFailureReason = new AtomicReference<>();
		private final AtomicInteger established = new AtomicInteger();
		private final CountDownLatch handlingFinished = new CountDownLatch(1);
		private final CountDownLatch handshakeFailed = new CountDownLatch(1);
		@Override public void didReceiveLogEvent(LogEvent event) {}
		@Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration) {
			this.written.set(response);
			this.writtenByPath.put(request.getPath(), response);
		}
		@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
				MarshaledResponse response, Duration duration, List<Throwable> failures) {
			this.finished.set(response);
			this.failures.set(List.copyOf(failures));
			this.finishedByPath.put(request.getPath(), response);
			this.failuresByPath.put(request.getPath(), List.copyOf(failures));
			this.handlingFinished.countDown();
		}
		@Override public void didEstablishSseConnection(SseConnection connection) { this.established.incrementAndGet(); }
		@Override public void didFailToEstablishSseConnection(Request request, ResourceMethod method,
				SseConnection.HandshakeFailureReason reason, Throwable failure) {
			this.handshakeFailureReason.set(reason);
			this.handshakeFailure.set(failure);
			this.handshakeFailed.countDown();
		}
	}

	public static final class HttpResource {
		@GET("/outcome") public String outcome() { return "ok"; }
	}

	public static final class SseResource {
		private final AtomicInteger initializers = new AtomicInteger();
		@SseEventSource("/events") public SseHandshakeResult events() {
			return SseHandshakeResult.Accepted.builder().clientInitializer(connection -> this.initializers.incrementAndGet()).build();
		}
	}
}
