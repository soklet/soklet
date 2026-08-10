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
		EventLoop eventLoop = new EventLoop(Options.builder()
				.withHost("127.0.0.1")
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withConcurrency(2)
				.build(), NoopLogger.instance(), handler,
				NoopConnectionListener.instance(), failureObserver);
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
			Assertions.assertEquals(List.of("failure-began", "sibling-close-entered"),
					order);

			source.releaseClose.countDown();
			Assertions.assertTrue(observationClosed.await(3, TimeUnit.SECONDS),
					"The parent failure scope did not close after sibling cleanup.");
			Assertions.assertTrue(eventLoop.join(Duration.ofSeconds(3)));
			Assertions.assertEquals(List.of(
					MetricsCollector.TransportFailureReason.EVENT_LOOP_TERMINATED),
					reasons);
			Assertions.assertEquals(List.of("failure-began", "sibling-close-entered",
					"sibling-close-returned", "failure-closed"), order);
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
	void legacy_options_constructor_preserves_original_defaults() {
		Duration timeout = Duration.ofSeconds(1);
		Options options = new Options("127.0.0.1", 0, true, false, timeout,
				timeout, timeout, timeout, 1024, 16, 4096, 32, 2048,
				1024, 8, 2);

		Assertions.assertEquals(options.maxRequestSize(), options.maxRequestBodySize());
		Assertions.assertEquals(List.of(), options.earlyErrorResponseHeaders());
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
