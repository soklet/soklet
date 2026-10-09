/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HttpResponseStreamEpochTests {
	@Test
	void sourceClockStepsCannotChangePreparedMonotonicLifetimeOrCapturedRequestDuration() {
		Request request = Request.fromPath(HttpMethod.GET, "/epoch");
		MarshaledResponse response = MarshaledResponse.withStatusCode(200).stream(stream -> {}).build();
		Instant preparedAt = Instant.parse("2026-10-09T00:00:00Z");
		StreamingResponseHandle prepared = new DefaultStreamingResponseHandle(ServerType.HTTP, request, null, response, preparedAt);
		Throwable cause = new IllegalStateException("producer failure");
		for (Duration sourceOffset : List.of(Duration.ofDays(-1), Duration.ofDays(1))) {
			HttpResponseStreamObservation observation = new HttpResponseStreamObservation(request, 100L);
			AtomicReference<StreamTermination> observed = new AtomicReference<>();
			MetricsCollector collector = new MetricsCollector() {
				@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination terminal,
						Duration duration, Long bytes) {
					assertSame(prepared, handle); assertEquals(Duration.ofNanos(250), duration); assertEquals(7L, bytes);
					observed.set(terminal);
				}
			};
			observation.prepare(prepared, 200L, collector, new LifecycleObserver() {}, event -> fail(event.getMessage()));
			observation.completeHandling(true);
			StreamingResponseHandle source = new DefaultStreamingResponseHandle(ServerType.HTTP, request, null, response,
					preparedAt.plus(sourceOffset));
			StreamTermination terminal = StreamTermination.with(StreamTerminationReason.PRODUCER_FAILED, Duration.ofSeconds(5))
					.cause(cause).build();
			HttpResponseStreamObservation.Delivery delivery = observation.deliver(source, terminal, 350L, 7L,
					collector, event -> fail(event.getMessage()));
			assertSame(prepared, delivery.handle()); assertSame(observed.get(), delivery.termination());
			assertEquals(Duration.ofNanos(150), delivery.termination().getDuration());
			assertEquals(preparedAt.plusNanos(150),
					delivery.handle().getEstablishedAt().plus(delivery.termination().getDuration()));
			assertEquals(StreamTerminationReason.PRODUCER_FAILED, delivery.termination().getReason());
			assertSame(cause, delivery.termination().getCause().orElseThrow());
		}
	}

	@Test
	void liveDelayedSourceSubmissionKeepsPreparedEpochAndActualTerminalInstantPaired() throws Exception {
		int port;
		try (ServerSocket reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
		GatedSubmissionExecutor producerExecutor = new GatedSubmissionExecutor();
		Observation observation = new Observation(false);
		Resource resource = new Resource(observation);
		HttpServer server = HttpServer.withPort(port).host("127.0.0.1")
				.streamingExecutorServiceSupplier(() -> producerExecutor).build();
		try (Soklet soklet = Soklet.fromConfig(config(server, observation, resource))) {
			soklet.start();
			try (Socket socket = new Socket("127.0.0.1", port)) {
				socket.setSoTimeout(5000);
				socket.getOutputStream().write("GET /epoch HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
						.getBytes(StandardCharsets.US_ASCII));
				await(producerExecutor.entered);
				assertFalse(observation.prepared.await(200, TimeUnit.MILLISECONDS), "Preparation must follow successful source submission");
				assertNull(observation.producerEnteredAt.get());
				Instant submissionReleasedAt = Instant.now();
				producerExecutor.release.countDown();
				String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
				assertTrue(wire.startsWith("HTTP/1.1 200"), wire); assertTrue(wire.contains("payload"), wire);
				await(observation.terminated);
				assertTrue(observation.preparedHandle.get().getEstablishedAt().isBefore(submissionReleasedAt),
						"The admitted epoch precedes source submission, including a possible pre-preparation cancellation");
				observation.assertTerminalEpoch();
			}
		} finally {
			producerExecutor.release.countDown(); producerExecutor.shutdownNow();
			assertTrue(producerExecutor.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	@Test
	void simulatorHandlingFinishDelayRemainsInPreparedStreamLifetime() throws Exception {
		Observation observation = new Observation(true);
		Resource resource = new Resource(observation);
		ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor();
		try {
			SokletSimulator.run(SimulatorConfig.fromSokletConfig(config(HttpServer.withPort(0).build(), observation, resource)), simulator -> {
				var result = dispatchExecutor.submit(() -> simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/epoch")));
				try {
					await(observation.handlingEntered);
					assertFalse(observation.terminated.await(200, TimeUnit.MILLISECONDS));
					assertNull(observation.producerEnteredAt.get());
					observation.releaseHandling.countDown();
					assertEquals(7L, result.get(5, TimeUnit.SECONDS).getMarshaledResponse().getBodyLength());
					await(observation.terminated); observation.assertTerminalEpoch();
				} catch (Exception exception) { throw new AssertionError(exception); }
				finally { observation.releaseHandling.countDown(); }
			});
		} finally {
			observation.releaseHandling.countDown(); dispatchExecutor.shutdownNow();
			assertTrue(dispatchExecutor.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	private static SokletConfig config(HttpServer server, Observation observation, Resource resource) {
		return SokletConfig.withHttpServer(server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.metricsCollector(observation).lifecycleObserver(observation.lifecycle).instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
				}).build();
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Controlled stream event did not arrive"); }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
	}

	public static final class Resource {
		private final Observation observation;
		Resource(Observation observation) { this.observation = observation; }
		@GET("/epoch") public MarshaledResponse stream() {
			return MarshaledResponse.withStatusCode(200).stream(stream -> {
				this.observation.producerEnteredAt.set(Instant.now());
				stream.write("payload".getBytes(StandardCharsets.US_ASCII));
			}).build();
		}
	}

	private static final class Observation implements MetricsCollector {
		final DefaultMetricsCollector defaults = DefaultMetricsCollector.defaultInstance();
		final CountDownLatch prepared = new CountDownLatch(1), handlingEntered = new CountDownLatch(1),
				releaseHandling = new CountDownLatch(1), terminated = new CountDownLatch(1);
		final AtomicReference<StreamingResponseHandle> preparedHandle = new AtomicReference<>();
		final AtomicReference<StreamTermination> metricsTerminal = new AtomicReference<>(), lifecycleTerminal = new AtomicReference<>();
		final AtomicReference<Instant> producerEnteredAt = new AtomicReference<>(), terminalCallbackAt = new AtomicReference<>();
		final boolean blockHandling;
		final LifecycleObserver lifecycle = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { fail(event.getMessage()); }
			@Override public void willWriteResponseStream(StreamingResponseHandle handle) { assertSame(preparedHandle.get(), handle); }
			@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
					MarshaledResponse response, Duration duration, List<Throwable> throwables) {
				if (blockHandling) { handlingEntered.countDown(); await(releaseHandling); }
			}
			@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination terminal) {
				terminalCallbackAt.set(Instant.now()); assertSame(preparedHandle.get(), handle);
				lifecycleTerminal.set(terminal); terminated.countDown();
			}
		};
		Observation(boolean blockHandling) { this.blockHandling = blockHandling; }
		@Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) {
			defaults.didStartRequestHandling(type, request, method);
		}
		@Override public void willWriteResponseStream(StreamingResponseHandle handle) {
			preparedHandle.set(handle); defaults.willWriteResponseStream(handle); prepared.countDown();
		}
		@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
				MarshaledResponse response, Duration duration, List<Throwable> throwables) {
			defaults.didFinishRequestHandling(type, request, method, response, duration, throwables);
		}
		@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination terminal,
				Duration duration, Long bytes) {
			assertSame(preparedHandle.get(), handle); metricsTerminal.set(terminal);
			defaults.didTerminateResponseStream(handle, terminal, duration, bytes);
		}
		void assertTerminalEpoch() {
			assertSame(metricsTerminal.get(), lifecycleTerminal.get());
			assertEquals(StreamTerminationReason.COMPLETED, lifecycleTerminal.get().getReason());
			Instant terminal = preparedHandle.get().getEstablishedAt().plus(lifecycleTerminal.get().getDuration());
			assertFalse(terminal.isBefore(producerEnteredAt.get()), "Terminal time must include the interval before production");
			assertFalse(terminal.isAfter(terminalCallbackAt.get()), "Terminal time must exclude subsequent observer dispatch");
			assertEquals(0L, defaults.snapshot().orElseThrow().getActiveRequests());
		}
	}

	private static final class GatedSubmissionExecutor extends AbstractExecutorService {
		final ExecutorService delegate = Executors.newSingleThreadExecutor();
		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		@Override public void execute(Runnable command) { entered.countDown(); await(release); delegate.execute(command); }
		@Override public void shutdown() { delegate.shutdown(); }
		@Override public List<Runnable> shutdownNow() { release.countDown(); return delegate.shutdownNow(); }
		@Override public boolean isShutdown() { return delegate.isShutdown(); }
		@Override public boolean isTerminated() { return delegate.isTerminated(); }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return delegate.awaitTermination(timeout, unit); }
	}
}
