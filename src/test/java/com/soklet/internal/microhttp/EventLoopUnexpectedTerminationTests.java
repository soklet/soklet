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

package com.soklet.internal.microhttp;

import com.soklet.MetricsCollector;
import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class EventLoopUnexpectedTerminationTests {
	@Test
	@org.junit.jupiter.api.Timeout(60)
	void ownedStartupCleanupDeliversCompletedObservationsOutsideStartLock() throws Exception {
		for (boolean failStart : List.of(false, true)) {
			EventLoop loop = new EventLoop(Options.builder().withHost("127.0.0.1")
					.withPort(0).withConcurrency(1).build(), (request, callback) -> {});
			int port = loop.getPort();
			Field lockField = EventLoop.class.getDeclaredField("lifecycleLock");
			lockField.setAccessible(true);
			Object frameworkLock = lockField.get(loop);
			CountDownLatch observationEntered = new CountDownLatch(1), releaseObservation = new CountDownLatch(1);
			java.util.concurrent.atomic.AtomicBoolean heldFrameworkLock = new java.util.concurrent.atomic.AtomicBoolean();
			// Isolate the outstanding observation's last-loop completion obligation.
			// Earlier transport cleanup may already have completed before this loop
			// closes or fails to start; that final notification owns scope delivery.
			Field cleanupComplete = EventLoop.class.getDeclaredField("unexpectedTerminationRuntimeCleanupComplete");
			cleanupComplete.setAccessible(true); cleanupComplete.setBoolean(loop, true);
			Field observationField = EventLoop.class.getDeclaredField("unexpectedTerminationObservation");
			observationField.setAccessible(true);
			observationField.set(loop, (TransportFailureObserver.Observation) () -> {
				heldFrameworkLock.set(Thread.holdsLock(frameworkLock));
				observationEntered.countDown();
				try { Assertions.assertTrue(releaseObservation.await(2, TimeUnit.SECONDS)); }
				catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
			});
			AssertionError nativeStartFailure = new AssertionError("native connection thread start failed");
			if (failStart) {
				Field loopsField = EventLoop.class.getDeclaredField("connectionEventLoops");
				loopsField.setAccessible(true);
				ConnectionEventLoop connectionLoop = (ConnectionEventLoop) ((List<?>) loopsField.get(loop)).get(0);
				Field threadField = ConnectionEventLoop.class.getDeclaredField("thread");
				threadField.setAccessible(true);
				threadField.set(connectionLoop, new Thread() {
					@Override public synchronized void start() { throw nativeStartFailure; }
				});
			}
			java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
			java.util.concurrent.Future<Throwable> cleanup = worker.submit(() -> {
				try { if (failStart) loop.start(); else loop.stop(); return null; }
				catch (Throwable failure) { return failure; }
			});
			try {
				Assertions.assertTrue(observationEntered.await(2, TimeUnit.SECONDS));
				Assertions.assertFalse(heldFrameworkLock.get(), "Application observation must run outside the start lock");
				loop.stopAccepting();
				Assertions.assertEquals(1L, releaseObservation.getCount());
				try (java.net.ServerSocket rebound = new java.net.ServerSocket()) {
					rebound.setReuseAddress(true); rebound.bind(new InetSocketAddress("127.0.0.1", port));
				}
				releaseObservation.countDown();
				if (failStart) Assertions.assertSame(nativeStartFailure, cleanup.get(2, TimeUnit.SECONDS));
				else Assertions.assertNull(cleanup.get(2, TimeUnit.SECONDS));
				Assertions.assertTrue(loop.join(Duration.ofSeconds(2)));
				Assertions.assertTrue(loop.resourcesClosed());
			} finally {
				releaseObservation.countDown(); cleanup.cancel(true); worker.shutdownNow();
				Assertions.assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
				loop.stop(); Assertions.assertTrue(loop.join(Duration.ofSeconds(2)));
			}
		}
	}

	@Test
	void intentionalListenerCloseRacingAcceptDoesNotElectUnexpectedTermination() throws Exception {
		CountDownLatch accepting = new CountDownLatch(1), releaseAccept = new CountDownLatch(1);
		AtomicInteger failures = new AtomicInteger();
		EventLoop loop = new EventLoop(Options.builder().withHost("127.0.0.1")
				.withPort(0).withConcurrency(1).build(), NoopLogger.instance(),
				(request, callback) -> {}, NoopConnectionListener.instance(), reason -> {
			failures.incrementAndGet(); return () -> {};
		}) {
			@Override boolean acceptReadyConnection() throws IOException {
				accepting.countDown();
				try { Assertions.assertTrue(releaseAccept.await(2, TimeUnit.SECONDS)); }
				catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
				throw new java.nio.channels.ClosedChannelException();
			}
		};
		try {
			loop.start();
			try (Socket client = new Socket("127.0.0.1", loop.getPort())) {
				Assertions.assertTrue(accepting.await(2, TimeUnit.SECONDS));
				loop.stopAccepting();
				releaseAccept.countDown();
				loop.stopConnections();
				Assertions.assertTrue(loop.join(Duration.ofSeconds(2)));
				Assertions.assertEquals(0, failures.get());
			}
		} finally {
			releaseAccept.countDown(); loop.stop();
			Assertions.assertTrue(loop.join(Duration.ofSeconds(2)));
		}
	}

	@Test
	void bounded_join_remaining_time_survives_signed_nano_time_wrap() {
		long beforeWrap = Long.MAX_VALUE - 5L;
		long afterWrap = Long.MIN_VALUE + 4L;

		Assertions.assertEquals(10L,
				EventLoop.remainingNanos(afterWrap, beforeWrap));
		Assertions.assertEquals(-10L,
				EventLoop.remainingNanos(beforeWrap, afterWrap));
	}

	@Test
	void fatal_connection_loop_failure_scope_includes_sibling_cleanup()
			throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();
		List<MetricsCollector.TransportFailureReason> reasons =
				new CopyOnWriteArrayList<>();
		CountDownLatch observationClosed = new CountDownLatch(1);
		TransportFailureObserver failureObserver = reason -> {
			if (reason != MetricsCollector.TransportFailureReason
					.EVENT_LOOP_TERMINATED)
				return () -> {
				};
			reasons.add(reason);
			order.add("failure-began");
			return () -> {
				order.add("failure-closed");
				observationClosed.countDown();
			};
		};
		BlockingCloseWritableSource source =
				new BlockingCloseWritableSource(order);
		Handler handler = (request, callback) -> callback.accept(
				StreamingMicrohttpResponses.withWritableSourceBody(
						200, "OK", List.of(), () -> source));
		ConnectionListener coordinatorListener = new ConnectionListener() {
			@Override
			public void willAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didTerminateEventLoop(@NonNull EventLoop eventLoop,
					@NonNull Throwable throwable) {
				order.add("coordinator-notified");
				eventLoop.stop();
			}
		};
		EventLoop eventLoop = new EventLoop(Options.builder()
				.withHost("127.0.0.1")
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withConcurrency(2)
				.build(), NoopLogger.instance(), handler,
				coordinatorListener, failureObserver);
		Socket client = null;

		try {
			eventLoop.start();
			client = new Socket("127.0.0.1", eventLoop.getPort());
			client.getOutputStream().write(("GET /held HTTP/1.1\r\n"
					+ "Host: 127.0.0.1\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
			client.getOutputStream().flush();
			Assertions.assertTrue(source.started.await(3, TimeUnit.SECONDS),
					"The sibling connection never installed its streaming source.");

			List<Selector> selectors = connectionSelectors(eventLoop);
			selectors.get(1).close();
			Assertions.assertTrue(source.closeEntered.await(3, TimeUnit.SECONDS),
					"Fatal cleanup never reached the sibling connection.");
			Assertions.assertEquals(1L, observationClosed.getCount(),
					"The parent failure scope closed before sibling cleanup completed.");
			Assertions.assertEquals(List.of("failure-began", "coordinator-notified",
					"sibling-close-entered"),
					order);

			source.releaseClose.countDown();
			Assertions.assertTrue(observationClosed.await(3, TimeUnit.SECONDS),
					"The parent failure scope did not close after sibling cleanup.");
			Assertions.assertTrue(eventLoop.join(Duration.ofSeconds(3)));
			Assertions.assertEquals(List.of(
					MetricsCollector.TransportFailureReason.EVENT_LOOP_TERMINATED),
					reasons);
			Assertions.assertEquals(List.of("failure-began", "coordinator-notified",
					"sibling-close-entered", "sibling-close-returned", "failure-closed"), order);
		} finally {
			source.releaseClose.countDown();
			if (client != null)
				client.close();
			eventLoop.stop();
			eventLoop.join(Duration.ofSeconds(3));
		}
	}

	@Test
	void fatal_connection_loop_exit_notifies_the_parent_exactly_once() throws Exception {
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicInteger notifications = new AtomicInteger();
		ConnectionListener listener = new ConnectionListener() {
			@Override
			public void willAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didFailToAcceptConnection(
					@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didTerminateEventLoop(@NonNull EventLoop eventLoop,
					@NonNull Throwable throwable) {
				notifications.incrementAndGet();
				terminated.countDown();
				eventLoop.stop();
			}
		};
		EventLoop eventLoop = new EventLoop(Options.builder()
				.withHost("127.0.0.1")
				.withPort(0)
				.withConcurrency(1)
				.build(), NoopLogger.instance(), (request, callback) -> {
		}, listener);

		try {
			eventLoop.start();
			Selector connectionSelector = connectionSelector(eventLoop);
			connectionSelector.close();

			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			Assertions.assertTrue(eventLoop.join(Duration.ofSeconds(2)));
			Assertions.assertTrue(eventLoop.isTerminated());
			Assertions.assertEquals(1, notifications.get());
		} finally {
			eventLoop.stop();
			eventLoop.join(Duration.ofSeconds(2));
		}
	}

	@Test
	void request_body_limit_is_validated_when_options_are_built() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> Options.builder()
				.withMaxRequestBodySize(0)
				.build());
		Assertions.assertThrows(IllegalArgumentException.class, () -> Options.builder()
				.withMaxRequestSize(100)
				.withMaxRequestBodySize(101)
				.build());
		Assertions.assertDoesNotThrow(() -> Options.builder()
				.withMaxRequestSize(100)
				.withMaxRequestBodySize(99)
				.build());
	}

	@Test
	void unparsed_request_limits_are_validated_when_options_are_built() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> Options.builder()
						.withUnparsedRequestCaptureLimitInBytes(-1).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> Options.builder()
						.withUnparsedResponseSizeLimitInBytes(0).build());
	}

	@Test
	void legacy_options_constructor_preserves_original_defaults() {
		Duration timeout = Duration.ofSeconds(1);
		Options options = new Options("127.0.0.1", 0, true, false, timeout,
				timeout, timeout, timeout, 1024, 16, 4096, 32, 2048,
				1024, 8, 2);

		Assertions.assertEquals(options.maxRequestSize(), options.maxRequestBodySize());
		Assertions.assertEquals(List.of(), options.earlyErrorResponseHeaders());
		Assertions.assertEquals(0, options.unparsedRequestCaptureLimitInBytes());
		Assertions.assertEquals(64 * 1_024,
				options.unparsedResponseSizeLimitInBytes());
	}

	private static Selector connectionSelector(EventLoop eventLoop) throws Exception {
		return connectionSelectors(eventLoop).get(0);
	}

	private static List<Selector> connectionSelectors(EventLoop eventLoop)
			throws Exception {
		Field loopsField = EventLoop.class.getDeclaredField("connectionEventLoops");
		loopsField.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<ConnectionEventLoop> loops = (List<ConnectionEventLoop>) loopsField.get(eventLoop);
		Field selectorField = ConnectionEventLoop.class.getDeclaredField("selector");
		selectorField.setAccessible(true);
		List<Selector> selectors = new java.util.ArrayList<>(loops.size());
		for (ConnectionEventLoop loop : loops)
			selectors.add((Selector) selectorField.get(loop));
		return List.copyOf(selectors);
	}

	private static final class BlockingCloseWritableSource
			implements WritableSource {
		private final List<String> order;
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch closeEntered = new CountDownLatch(1);
		private final CountDownLatch releaseClose = new CountDownLatch(1);

		private BlockingCloseWritableSource(List<String> order) {
			this.order = order;
		}

		@Override
		public void start() {
			this.started.countDown();
		}

		@Override
		public long writeTo(SocketChannel socketChannel, long maxBytes) {
			return 0L;
		}

		@Override
		public boolean hasRemaining() {
			return true;
		}

		@Override
		public boolean isReadyToWrite() {
			return false;
		}

		@Override
		public void close() throws IOException {
			close(null, null);
		}

		@Override
		public void close(@Nullable StreamTerminationReason reason,
				@Nullable Throwable cause) throws IOException {
			this.order.add("sibling-close-entered");
			this.closeEntered.countDown();
			try {
				if (!this.releaseClose.await(3, TimeUnit.SECONDS))
					throw new IOException("Timed out awaiting deterministic close release.");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted awaiting deterministic close release.",
						exception);
			}
			this.order.add("sibling-close-returned");
		}
	}
}
