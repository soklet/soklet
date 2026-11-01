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

import com.soklet.annotation.GET;
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.Handler;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class HttpTransportResponseOutcomeTests {
	@Test
	void notWrittenFailsDirectAndOpaqueConsumersWithoutClaimingSuccessOrReplacement() {
		for (boolean opaque : List.of(false, true)) {
			Fixture fixture = new Fixture(RequestInterceptor.defaultInstance());
			Soklet.run(fixture.config, () -> {
				AtomicInteger afterWrite = new AtomicInteger();
				fixture.dispatch("/outcome", feedback -> {
					Consumer<HttpRequestResult> writer = HttpTransportResponseFeedback.fromWriter(feedback,
							result -> HttpTransportResponseOutcome.notWritten());
					return opaque ? result -> { writer.accept(result); afterWrite.incrementAndGet(); } : writer;
				});
				Throwable failure = assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
						fixture.observation.writeFailures.get("/outcome"));
				assertSame(failure, fixture.metricWriteFailures.get("/outcome"));
				assertEquals(List.of(failure), fixture.observation.failures.get("/outcome"));
				assertEquals(200, fixture.observation.finished.get("/outcome").getStatusCode());
				assertFalse(fixture.observation.written.containsKey("/outcome"));
				assertEquals(0, afterWrite.get());
			});
		}
	}

	@Test
	void notWrittenAfterFeedbackCloseStillThrowsItsFailureWithoutTheReplacementBridge() {
		HttpTransportResponseFeedback feedback = HttpTransportResponseFeedback.create();
		feedback.close();
		var writer = HttpTransportResponseFeedback.fromWriter(feedback,
				result -> HttpTransportResponseOutcome.notWritten());
		assertThrows(java.util.concurrent.RejectedExecutionException.class,
				() -> writer.accept(HttpRequestResult.fromMarshaledResponse(response(200))));
		assertInstanceOf(HttpTransportResponseOutcome.Accepted.class, feedback.finish());
	}

	@Test
	void freshResultDecoratorRetainsReplacementAndNextDeliveryStartsClean() {
		Fixture fixture = new Fixture(RequestInterceptor.defaultInstance());
		Soklet.run(fixture.config, () -> {
			fixture.dispatch("/outcome", feedback -> result -> HttpTransportResponseFeedback.fromWriter(feedback, ignored ->
					HttpTransportResponseOutcome.replaced(response(505), null, null))
					.accept(HttpRequestResult.fromMarshaledResponse(result.getMarshaledResponse())));
			assertEquals(505, fixture.observation.written.get("/outcome").getStatusCode());
			assertEquals(505, fixture.observation.finished.get("/outcome").getStatusCode());
			assertTrue(fixture.observation.failures.get("/outcome").isEmpty());
			fixture.dispatch("/outcome", feedback -> HttpTransportResponseFeedback.fromWriter(feedback, result -> HttpTransportResponseOutcome.accepted()));
			assertEquals(200, fixture.observation.finished.get("/outcome").getStatusCode());
		});
	}

	@Test
	void nestedDeliveryKeepsFeedbackBoundToEachRequest() {
		Fixture fixture = new Fixture(RequestInterceptor.defaultInstance());
		Soklet.run(fixture.config, () -> {
			fixture.dispatch("/outcome", feedback -> result -> {
				fixture.dispatch("/nested", nestedFeedback -> HttpTransportResponseFeedback.fromWriter(nestedFeedback, nested ->
						HttpTransportResponseOutcome.replaced(response(503), null, null)));
				HttpTransportResponseFeedback.fromWriter(feedback, outer -> HttpTransportResponseOutcome.replaced(response(505), null, null))
						.accept(HttpRequestResult.fromMarshaledResponse(result.getMarshaledResponse()));
			});
			assertEquals(503, fixture.observation.finished.get("/nested").getStatusCode());
			assertEquals(505, fixture.observation.finished.get("/outcome").getStatusCode());
			assertTrue(fixture.observation.failures.get("/outcome").isEmpty());
		});
	}

	@Test
	void opaqueDecoratorRetainsLegacyReplacementUnwindingAndPreparationFailure() {
		RuntimeException preparationFailure = new IllegalArgumentException("preparation failed");
		AtomicInteger callbacksAfterReplacement = new AtomicInteger();
		Fixture fixture = new Fixture(RequestInterceptor.defaultInstance());
		Soklet.run(fixture.config, () -> {
			fixture.dispatch("/outcome", feedback -> result -> {
				HttpTransportResponseFeedback.fromWriter(feedback, ignored ->
						HttpTransportResponseOutcome.replaced(response(503), preparationFailure, null)).accept(result);
				callbacksAfterReplacement.incrementAndGet();
			});
			assertEquals(0, callbacksAfterReplacement.get());
			assertTrue(fixture.observation.writeFailures.isEmpty());
			assertTrue(fixture.metricWriteFailures.isEmpty());
			assertEquals(503, fixture.observation.finished.get("/outcome").getStatusCode());
			assertEquals(List.of(preparationFailure), fixture.observation.failures.get("/outcome"));
		});
	}

	@Test
	void replacementWriteErrorIsObservedUnwrappedAfterThePreparationFailure() {
		RuntimeException preparationFailure = new IllegalArgumentException("preparation failed");
		AssertionError writeFailure = new AssertionError("write failed");
		Fixture fixture = new Fixture(RequestInterceptor.defaultInstance());
		Soklet.run(fixture.config, () -> {
			AtomicInteger callbacksAfterFailure = new AtomicInteger();
			fixture.dispatch("/outcome", feedback -> result -> {
				HttpTransportResponseFeedback.fromWriter(feedback, ignored ->
						HttpTransportResponseOutcome.replaced(response(500), preparationFailure, writeFailure)).accept(result);
				callbacksAfterFailure.incrementAndGet();
			});
			assertEquals(0, callbacksAfterFailure.get(), "A genuine write Error must interrupt the decorator immediately");
			assertSame(writeFailure, fixture.observation.writeFailures.get("/outcome"));
			assertSame(writeFailure, fixture.metricWriteFailures.get("/outcome"));
			assertEquals(500, fixture.observation.finished.get("/outcome").getStatusCode());
			assertEquals(List.of(preparationFailure, writeFailure), fixture.observation.failures.get("/outcome"));
			assertFalse(fixture.observation.written.containsKey("/outcome"));
		});
	}

	@Test
	void failedFallbackWriteReportsTheWriteFailureInsteadOfTheEarlierHandlingFailure() {
		RuntimeException handlingFailure = new IllegalStateException("request wrapping failed");
		AssertionError writeFailure = new AssertionError("fallback write failed");
		RequestInterceptor interceptor = new RequestInterceptor() {
			@Override public void wrapRequest(ServerType type, Request request, Consumer<Request> processor) {
				throw handlingFailure;
			}
		};
		Fixture fixture = new Fixture(interceptor);
		Soklet.run(fixture.config, () -> {
			fixture.dispatch("/outcome", feedback -> HttpTransportResponseFeedback.fromWriter(feedback, result -> {
				assertEquals(500, result.getMarshaledResponse().getStatusCode());
				return HttpTransportResponseOutcome.replaced(response(503), null, writeFailure);
			}));
			assertSame(writeFailure, fixture.observation.writeFailures.get("/outcome"));
			assertSame(writeFailure, fixture.metricWriteFailures.get("/outcome"));
			assertEquals(503, fixture.observation.finished.get("/outcome").getStatusCode());
		});
	}

	@Test
	void feedbackChannelsAreIsolatedAcrossConcurrentRequests() throws Exception {
		Fixture fixture = new Fixture(RequestInterceptor.defaultInstance());
		Soklet.run(fixture.config, () -> {
			try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
				CountDownLatch entered = new CountDownLatch(2);
				var first = executor.submit(() -> fixture.dispatch("/outcome", feedback -> HttpTransportResponseFeedback.fromWriter(feedback, result -> {
					entered.countDown(); await(entered);
					return HttpTransportResponseOutcome.replaced(response(503), null, null);
				})));
				var second = executor.submit(() -> fixture.dispatch("/nested", feedback -> HttpTransportResponseFeedback.fromWriter(feedback, result -> {
					entered.countDown(); await(entered);
					return HttpTransportResponseOutcome.replaced(response(505), null, null);
				})));
				first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
				assertEquals(503, fixture.observation.finished.get("/outcome").getStatusCode());
				assertEquals(505, fixture.observation.finished.get("/nested").getStatusCode());
			}
		});
	}

	@Test
	void unclaimedWriterRetainsExactFailureForCompatibilityBoundary() {
		AssertionError failure = new AssertionError("unscoped write failed");
		try (HttpTransportResponseFeedback feedback = HttpTransportResponseFeedback.create()) {
			var writer = HttpTransportResponseFeedback.fromWriter(feedback, result -> HttpTransportResponseOutcome.replaced(response(503), null, failure));
			var replacement = assertThrows(HttpTransportResponseFeedback.UnclaimedReplacement.class,
					() -> writer.accept(HttpRequestResult.fromMarshaledResponse(response(200))));
			assertSame(failure, assertThrows(AssertionError.class, () -> replacement.getOutcome().rethrowWriteFailure()));
		}
	}

	@Test
	void claimedFeedbackSurvivesTransportScopeClosingAfterReplacementWasRecorded() {
		HttpTransportResponseFeedback feedback = HttpTransportResponseFeedback.create();
		feedback.claimLifecycleConsumer();
		var writer = HttpTransportResponseFeedback.fromWriter(feedback, result ->
				HttpTransportResponseOutcome.replaced(response(505), null, null));
		assertDoesNotThrow(() -> writer.accept(HttpRequestResult.fromMarshaledResponse(response(200))));
		feedback.close();
		var replacement = assertInstanceOf(HttpTransportResponseOutcome.Replaced.class, feedback.finish());
		assertEquals(505, replacement.getMarshaledResponse().getStatusCode());
		assertInstanceOf(HttpTransportResponseOutcome.Accepted.class, feedback.finish());
	}

	@Test
	void claimedWriterUsesCompatibilityBridgeWhenChannelAlreadyFinished() {
		try (HttpTransportResponseFeedback feedback = HttpTransportResponseFeedback.create()) {
			feedback.claimLifecycleConsumer();
			feedback.finish();
			var writer = HttpTransportResponseFeedback.fromWriter(feedback, result ->
					HttpTransportResponseOutcome.replaced(response(505), null, null));
			var replacement = assertThrows(HttpTransportResponseFeedback.UnclaimedReplacement.class,
					() -> writer.accept(HttpRequestResult.fromMarshaledResponse(response(200))));
			assertEquals("HTTP transport replaced the response.", replacement.getMessage());
			assertEquals(0, replacement.getStackTrace().length);
			assertInstanceOf(HttpTransportResponseOutcome.Accepted.class, feedback.finish());
		}
	}

	@Test
	void nonJoinedHttpDecoratorReportsReplacementWhenHandlerStartsAfterTransportScopeCloses() throws Exception {
		assertNonJoinedReplacement(false, false);
	}

	@Test
	void nonJoinedHttpDecoratorReportsReplacementWhenClaimedHandlerOutlivesTransportScope() throws Exception {
		assertNonJoinedReplacement(true, false);
	}

	@Test
	void nonJoinedHttpDecoratorReportsTimeoutReplacement() throws Exception {
		assertNonJoinedReplacement(true, true);
	}

	@Test
	void lateHttpTimeoutResponseClosesOwnedFileChannel() throws Exception { assertLateHttpBodyOwnership(true); }

	@Test
	void lateHttpTimeoutResponsePreservesBorrowedFileChannel() throws Exception { assertLateHttpBodyOwnership(false); }

	private static void assertLateHttpBodyOwnership(boolean owned) throws Exception {
		var path = java.nio.file.Files.createTempFile("soklet-late-http-", ".txt");
		try {
			java.nio.file.Files.writeString(path, "discarded");
			try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ)) {
				MarshaledResponse late = MarshaledResponse.withStatusCode(200).body(channel, 0L, 9L, owned).build();
				assertNonJoinedReplacement(true, true, late);
				assertEquals(!owned, channel.isOpen(), "Only an owned suppressed channel must close");
			}
		} finally {
			java.nio.file.Files.deleteIfExists(path);
		}
	}

	private static void assertNonJoinedReplacement(boolean claimBeforeReturn, boolean timeout) throws Exception {
		assertNonJoinedReplacement(claimBeforeReturn, timeout, null);
	}

	private static void assertNonJoinedReplacement(boolean claimBeforeReturn, boolean timeout, MarshaledResponse lateResponse) throws Exception {
		CountDownLatch dispatchFinished = new CountDownLatch(1);
		CountDownLatch resourceEntered = new CountDownLatch(1);
		CountDownLatch releaseResource = new CountDownLatch(1);
		CountDownLatch wireWritten = new CountDownLatch(1);
		var transportExecutor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new java.util.concurrent.LinkedBlockingQueue<>(), Thread.ofVirtual().factory()) {
			@Override protected void afterExecute(Runnable task, Throwable failure) { dispatchFinished.countDown(); }
		};
		try (var decoratorExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			DefaultHttpServer delegate = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1")
					.requestHandlerTimeout(Duration.ofSeconds(timeout ? 2 : 15))
					.requestHandlerExecutorServiceSupplier(() -> transportExecutor).build();
			HttpServer decorator = new HttpServer() {
				@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
				@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
					RequestHandler upstream = context.getAdmissionFencedRequestHandler();
					return context.attachTransparentDelegate(delegate, (request, consumer) -> {
						decoratorExecutor.execute(() -> {
							if (!claimBeforeReturn) await(dispatchFinished);
							upstream.handleRequest(request, consumer);
						});
						if (claimBeforeReturn) await(resourceEntered);
					});
				}
			};
			Observation observation = new Observation();
			Resource resource = new Resource();
			resource.beforeResponse = () -> { resourceEntered.countDown(); await(releaseResource); };
			AtomicInteger statusOnWire = new AtomicInteger();
			AtomicInteger statusInMetrics = new AtomicInteger();
			String path = timeout ? "/outcome" : "/stream";
			int expected = timeout ? 503 : 505;
			try {
				Soklet.run(config(decorator, observation, resource)
					.requestInterceptor(new RequestInterceptor() {
						@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
								Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
							MarshaledResponse generated = generator.apply(request);
							writer.accept(lateResponse == null ? generated : lateResponse);
						}
					}).metricsCollector(new MetricsCollector() {
				@Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method,
						MarshaledResponse response, Duration duration) { statusInMetrics.set(response.getStatusCode()); }
			}).build(), () -> {
					handler(delegate).handle(new MicrohttpRequest("GET", path, timeout ? "HTTP/1.1" : "HTTP/1.0",
							List.of(new Header("Host", "localhost")), new byte[0], false, null), wire -> {
						statusOnWire.set(wire.status());
						wireWritten.countDown();
					});
					await(dispatchFinished);
					await(resourceEntered);
					if (timeout) await(wireWritten);
					releaseResource.countDown();
					await(wireWritten);
					await(observation.finishedLatch);
					assertEquals(expected, statusOnWire.get());
					assertEquals(expected, observation.written.get(path).getStatusCode());
					assertEquals(expected, observation.finished.get(path).getStatusCode());
					assertFalse(observation.finished.get(path).isStreaming());
					assertEquals(expected, statusInMetrics.get());
					assertTrue(observation.failures.get(path).isEmpty());
					assertEquals(0, resource.producerCalls.get());
			});
			} finally {
				releaseResource.countDown();
			}
		} finally {
			releaseResource.countDown();
			transportExecutor.shutdownNow();
			assertTrue(transportExecutor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void decoratedHttp10ReplacementWriteErrorReports505AndDisposesOriginalStreamOnce() throws Exception {
		DefaultHttpServer delegate = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1").build();
		HttpServer decorator = new HttpServer() {
			@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
			@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
				RequestHandler upstream = context.getAdmissionFencedRequestHandler();
				return context.attachTransparentDelegate(delegate, (request, consumer) -> upstream.handleRequest(request,
						result -> consumer.accept(HttpRequestResult.fromMarshaledResponse(result.getMarshaledResponse()))));
			}
		};
		Observation observation = new Observation();
		Resource resource = new Resource();
		AtomicInteger offers = new AtomicInteger();
		AssertionError writeFailure = new AssertionError("HTTP/1.0 wire callback failed");
		Soklet.run(config(decorator, observation, resource).build(), () -> {
			Handler handler = handler(delegate);
			handler.handle(new MicrohttpRequest("GET", "/stream", "HTTP/1.0", List.of(new Header("Host", "localhost")), new byte[0], false, null), wire -> {
				offers.incrementAndGet();
				assertEquals(505, wire.status());
				throw writeFailure;
			});
			await(observation.finishedLatch);
			await(observation.terminatedLatch);
			assertEquals(1, offers.get(), "A failed replacement write must never offer another response");
			assertEquals(505, observation.finished.get("/stream").getStatusCode());
			assertFalse(observation.finished.get("/stream").isStreaming());
			assertSame(writeFailure, observation.writeFailures.get("/stream"));
			assertEquals(0, resource.producerCalls.get());
			assertEquals(1, observation.terminations.get());
			assertEquals(StreamTerminationReason.PROTOCOL_UNSUPPORTED, observation.terminationReason);
		});
	}

	@Test
	void copiedRequestHandlerHopReplacement() throws Exception {
		assertJoinedReplacement(true, false, false, 0);
	}

	@Test
	void copiedRequestHandlerHopWriteRuntimeException() throws Exception {
		assertJoinedReplacement(true, false, false, 1);
	}

	@Test
	void copiedRequestHandlerHopWriteError() throws Exception {
		assertJoinedReplacement(true, false, false, 2);
	}

	@Test
	void copiedRequestCallbackHopReplacement() throws Exception {
		assertJoinedReplacement(false, true, false, 0);
	}

	@Test
	void copiedRequestCallbackHopWriteRuntimeException() throws Exception {
		assertJoinedReplacement(false, true, false, 1);
	}

	@Test
	void copiedRequestCallbackHopWriteError() throws Exception {
		assertJoinedReplacement(false, true, false, 2);
	}

	@Test
	void copiedRequestHandlerAndCallbackHopsReplacement() throws Exception {
		assertJoinedReplacement(true, true, false, 0);
	}

	@Test
	void copiedRequestHandlerAndCallbackHopsWriteRuntimeException() throws Exception {
		assertJoinedReplacement(true, true, false, 1);
	}

	@Test
	void copiedRequestHandlerAndCallbackHopsWriteError() throws Exception {
		assertJoinedReplacement(true, true, false, 2);
	}

	@Test
	void freshRequestSameThreadReplacement() throws Exception {
		assertJoinedReplacement(false, false, true, 0);
	}

	@Test
	void freshRequestSameThreadWriteRuntimeException() throws Exception {
		assertJoinedReplacement(false, false, true, 1);
	}

	@Test
	void freshRequestSameThreadWriteError() throws Exception {
		assertJoinedReplacement(false, false, true, 2);
	}

	@Test
	void freshRequestHandlerHopReplacement() throws Exception {
		assertJoinedReplacement(true, false, true, 0);
	}

	@Test
	void freshRequestHandlerHopWriteRuntimeException() throws Exception {
		assertJoinedReplacement(true, false, true, 1);
	}

	@Test
	void freshRequestHandlerHopWriteError() throws Exception {
		assertJoinedReplacement(true, false, true, 2);
	}

	@Test
	void freshRequestCallbackHopReplacement() throws Exception {
		assertJoinedReplacement(false, true, true, 0);
	}

	@Test
	void freshRequestCallbackHopWriteRuntimeException() throws Exception {
		assertJoinedReplacement(false, true, true, 1);
	}

	@Test
	void freshRequestCallbackHopWriteError() throws Exception {
		assertJoinedReplacement(false, true, true, 2);
	}

	@Test
	void freshRequestHandlerAndCallbackHopsReplacement() throws Exception {
		assertJoinedReplacement(true, true, true, 0);
	}

	@Test
	void freshRequestHandlerAndCallbackHopsWriteRuntimeException() throws Exception {
		assertJoinedReplacement(true, true, true, 1);
	}

	@Test
	void freshRequestHandlerAndCallbackHopsWriteError() throws Exception {
		assertJoinedReplacement(true, true, true, 2);
	}

	private static void assertJoinedReplacement(boolean handlerHop, boolean callbackHop, boolean freshRequest,
			int failureKind) throws Exception {
		Throwable writeFailure = switch (failureKind) {
			case 0 -> null;
			case 1 -> new IllegalStateException("joined wire write failed");
			default -> new AssertionError("joined wire write failed");
		};
		DefaultHttpServer delegate = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1").build();
		HttpServer decorator = new HttpServer() {
			@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
			@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
				RequestHandler upstream = context.getAdmissionFencedRequestHandler();
				return context.attachTransparentDelegate(delegate, (request, consumer) -> {
					Runnable handle = () -> upstream.handleRequest(freshRequest ? Request.fromPath(request.getHttpMethod(), request.getResourcePath().getPath())
							: request.copy().finish(), result -> {
						Runnable deliver = () -> consumer.accept(HttpRequestResult.fromMarshaledResponse(result.getMarshaledResponse()));
						if (callbackHop) runOnJoinedThread(deliver); else deliver.run();
					});
					if (handlerHop) runOnJoinedThread(handle); else handle.run();
				});
			}
		};
		Observation observation = new Observation();
		Resource resource = new Resource();
		AtomicInteger offers = new AtomicInteger();
		Map<String, Throwable> metricWriteFailures = new ConcurrentHashMap<>();
		Soklet.run(config(decorator, observation, resource)
				.metricsCollector(new MetricsCollector() {
					@Override public void didFailToWriteResponse(ServerType type, Request request, ResourceMethod method,
							MarshaledResponse response, Duration duration, Throwable failure) {
						assertEquals(505, response.getStatusCode());
						metricWriteFailures.put("/stream", failure);
					}
				}).build(), () -> {
			handler(delegate).handle(new MicrohttpRequest("GET", "/stream", "HTTP/1.0",
					List.of(new Header("Host", "localhost")), new byte[0], false, null), wire -> {
				offers.incrementAndGet();
				assertEquals(505, wire.status());
				if (writeFailure instanceof RuntimeException failure) throw failure;
				if (writeFailure instanceof Error failure) throw failure;
			});
			await(observation.finishedLatch);
			await(observation.terminatedLatch);
			assertEquals(1, offers.get());
			assertEquals(505, observation.finished.get("/stream").getStatusCode());
			assertFalse(observation.finished.get("/stream").isStreaming());
			if (writeFailure == null) {
				assertEquals(505, observation.written.get("/stream").getStatusCode());
				assertTrue(observation.failures.get("/stream").isEmpty());
				assertTrue(metricWriteFailures.isEmpty());
			} else {
				assertFalse(observation.written.containsKey("/stream"));
				assertSame(writeFailure, observation.writeFailures.get("/stream"));
				assertSame(writeFailure, metricWriteFailures.get("/stream"));
				assertEquals(List.of(writeFailure), observation.failures.get("/stream"));
			}
			assertEquals(0, resource.producerCalls.get());
			assertEquals(1, observation.terminations.get());
			assertEquals(StreamTerminationReason.PROTOCOL_UNSUPPORTED, observation.terminationReason);
		});
	}

	private static void runOnJoinedThread(Runnable action) {
		FutureTask<Void> task = new FutureTask<>(action, null);
		Thread thread = Thread.startVirtualThread(task);
		try {
			task.get(5, TimeUnit.SECONDS);
		} catch (ExecutionException failure) {
			if (failure.getCause() instanceof RuntimeException cause) throw cause;
			if (failure.getCause() instanceof Error cause) throw cause;
			throw new AssertionError(failure.getCause());
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new AssertionError(failure);
		} catch (TimeoutException failure) {
			throw new AssertionError(failure);
		} finally {
			task.cancel(true);
			thread.interrupt();
			try { assertTrue(thread.join(Duration.ofSeconds(5)), "Callback worker must terminate"); }
			catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
		}
	}

	private static Handler handler(DefaultHttpServer server) throws Exception {
		Field loops = EventLoop.class.getDeclaredField("connectionEventLoops");
		loops.setAccessible(true);
		Object connectionLoop = ((List<?>) loops.get(server.getEventLoop().orElseThrow())).get(0);
		Field handler = connectionLoop.getClass().getDeclaredField("handler");
		handler.setAccessible(true);
		return (Handler) handler.get(connectionLoop);
	}

	private static MarshaledResponse response(int status) { return MarshaledResponse.withStatusCode(status).build(); }
	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
		catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
	}
	private static SokletConfig.Builder config(HttpServer server, Observation observation, Resource resource) {
		return SokletConfig.withHttpServer(server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecycleObserver(observation).lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownTimeout(Duration.ofSeconds(2)).forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
				});
	}

	public static final class Resource {
		private final AtomicInteger producerCalls = new AtomicInteger();
		private Runnable beforeResponse = () -> {};
		@GET("/outcome") public String outcome() { this.beforeResponse.run(); return "ok"; }
		@GET("/nested") public String nested() { return "nested"; }
		@GET("/stream") public MarshaledResponse stream() {
			this.beforeResponse.run();
			return MarshaledResponse.withStatusCode(200).stream(writer -> this.producerCalls.incrementAndGet()).build();
		}
	}

	private static final class Fixture {
		private final Observation observation = new Observation();
		private final Map<String, Throwable> metricWriteFailures = new ConcurrentHashMap<>();
		private final CustomTransport transport = new CustomTransport();
		private final SokletConfig config;
		private Fixture(RequestInterceptor interceptor) {
			this.config = config(this.transport, this.observation, new Resource())
					.requestInterceptor(interceptor).metricsCollector(new MetricsCollector() {
						@Override public void didFailToWriteResponse(ServerType type, Request request, ResourceMethod method,
								MarshaledResponse response, Duration duration, Throwable failure) {
							metricWriteFailures.put(request.getResourcePath().getPath(), failure);
						}
					}).build();
		}
		private void dispatch(String path, Function<HttpTransportResponseFeedback, Consumer<HttpRequestResult>> consumerFactory) {
			try (HttpTransportResponseFeedback feedback = HttpTransportResponseFeedback.create()) {
				this.transport.handler.handleRequest(Request.fromPath(HttpMethod.GET, path), consumerFactory.apply(feedback));
			}
		}
	}

	private static final class CustomTransport implements HttpServer {
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
		private final Map<String, MarshaledResponse> written = new ConcurrentHashMap<>();
		private final Map<String, MarshaledResponse> finished = new ConcurrentHashMap<>();
		private final Map<String, List<Throwable>> failures = new ConcurrentHashMap<>();
		private final Map<String, Throwable> writeFailures = new ConcurrentHashMap<>();
		private final CountDownLatch finishedLatch = new CountDownLatch(1);
		private final CountDownLatch terminatedLatch = new CountDownLatch(1);
		private final AtomicInteger terminations = new AtomicInteger();
		private volatile StreamTerminationReason terminationReason;
		@Override public void didReceiveLogEvent(LogEvent event) {}
		@Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration) {
			this.written.put(request.getResourcePath().getPath(), response);
		}
		@Override public void didFailToWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration, Throwable failure) {
			this.writeFailures.put(request.getResourcePath().getPath(), failure);
		}
		@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration, List<Throwable> failures) {
			this.finished.put(request.getResourcePath().getPath(), response);
			this.failures.put(request.getResourcePath().getPath(), List.copyOf(failures));
			this.finishedLatch.countDown();
		}
		@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
			this.terminationReason = termination.getReason();
			this.terminations.incrementAndGet();
			this.terminatedLatch.countDown();
		}
	}
}
