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

import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class SseDurationBoundaryTests {

	@TestFactory
	Stream<DynamicTest> invalidHeartbeatsFailAtBuildRatherThanDuringAConnection() {
		return Stream.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofMillis(-1),
				Duration.ofNanos(1), Duration.ofNanos(999_999),
				Duration.ofMillis(Long.MAX_VALUE).plusMillis(1), ChronoUnit.FOREVER.getDuration())
				.map(duration -> DynamicTest.dynamicTest(duration.toString(), () ->
						assertThrows(IllegalArgumentException.class,
								() -> SseServer.withPort(0).heartbeatInterval(duration).build())));
	}

	@Test
	void heartbeatBoundsRetainTheSuppliedDurationAndNullRestoresTheDefault() {
		for (Duration duration : List.of(Duration.ofMillis(1), Duration.ofNanos(1_999_999),
				Duration.ofMillis(Long.MAX_VALUE), Duration.ofMillis(Long.MAX_VALUE).plusNanos(999_999))) {
			DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).heartbeatInterval(duration).build();
			assertEquals(duration, server.getHeartbeatInterval());
		}
		SseServer.Builder builder = SseServer.withPort(0).heartbeatInterval(Duration.ofMillis(1));
		DefaultSseServer original = (DefaultSseServer) builder.build();
		assertThrows(IllegalArgumentException.class, () -> builder.heartbeatInterval(Duration.ofNanos(1)).build());
		assertEquals(Duration.ofSeconds(15), ((DefaultSseServer) builder.heartbeatInterval(null).build()).getHeartbeatInterval());
		assertEquals(Duration.ofMillis(1), original.getHeartbeatInterval());
	}

	@TestFactory
	Stream<DynamicTest> invalidRetryDurationsFailWhenTheEventIsBuilt() {
		return Stream.of(Duration.ofNanos(-1), Duration.ofMillis(-1),
				Duration.ofMillis(Long.MAX_VALUE).plusMillis(1), ChronoUnit.FOREVER.getDuration())
				.map(duration -> DynamicTest.dynamicTest(duration.toString(), () ->
						assertThrows(IllegalArgumentException.class,
								() -> SseEvent.withData("payload").retry(duration).build())));
	}

	@Test
	void retryRetainsSuppliedPrecisionAndNullClearsTheValue() {
		Duration duration = Duration.ofMillis(Long.MAX_VALUE).plusNanos(999_999);
		SseEvent.Builder builder = SseEvent.withData("payload").retry(duration);
		SseEvent original = builder.build();
		assertEquals(duration, original.getRetry().orElseThrow());
		assertThrows(IllegalArgumentException.class,
				() -> builder.retry(Duration.ofMillis(Long.MAX_VALUE).plusMillis(1)).build());
		assertTrue(builder.retry(null).build().getRetry().isEmpty());
		assertEquals(duration, original.getRetry().orElseThrow());
	}

	@Test
	void disablingTheConnectionCapRetainsIndependentLifecycleAdmission() {
		DefaultSseServer defaults = (DefaultSseServer) SseServer.withPort(0).build();
		assertEquals(8_192, defaults.getConcurrentConnectionLimit());
		assertEquals(256, defaults.getStreamingLifecycleCapacity());
		DefaultSseServer noConnectionCap = (DefaultSseServer) SseServer.withPort(0).concurrentConnectionLimit(0).build();
		assertEquals(0, noConnectionCap.getConcurrentConnectionLimit());
		assertEquals(256, noConnectionCap.getStreamingLifecycleCapacity());
		DefaultSseServer raisedLifecycle = (DefaultSseServer) SseServer.withPort(0)
				.concurrentConnectionLimit(0).streamingLifecycleCapacity(512).build();
		assertEquals(512, raisedLifecycle.getStreamingLifecycleCapacity());
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void maximumHeartbeatStillAcceptsApplicationWritesAndStopsCompletely() throws Exception {
		try (LiveFixture fixture = new LiveFixture(Duration.ofMillis(Long.MAX_VALUE))) {
			fixture.broadcaster().broadcastEvent(SseEvent.withData("first").build());
			assertEquals("data: first\n\n", readThrough(fixture.socket, "\n\n"));
			fixture.broadcaster().broadcastComment(SseComment.fromComment("still open"));
			assertEquals(": still open\n\n", readThrough(fixture.socket, "\n\n"));
			assertEquals(1, fixture.server.getActiveConnectionCount());
		}
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void idleHeartbeatRemainsACommentAndDoesNotPreventLaterEvents() throws Exception {
		try (LiveFixture fixture = new LiveFixture(Duration.ofMillis(25))) {
			assertEquals(":\n\n", readThrough(fixture.socket, "\n\n"));
			fixture.broadcaster().broadcastEvent(SseEvent.withData("after heartbeat").build());
			String frame = "";
			for (int i = 0; i < 120; i++) {
				frame = readThrough(fixture.socket, "\n\n");
				if (!frame.equals(":\n\n")) break;
			}
			assertEquals("data: after heartbeat\n\n", frame);
		}
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void retryWireValuesUseNonnegativeWholeMillisecondsAtBothBounds() throws Exception {
		try (LiveFixture fixture = new LiveFixture(Duration.ofMinutes(10))) {
			for (RetryCase testCase : List.of(new RetryCase(Duration.ZERO, "0"),
					new RetryCase(Duration.ofNanos(999_999), "0"),
					new RetryCase(Duration.ofNanos(1_999_999), "1"),
					new RetryCase(Duration.ofMillis(Long.MAX_VALUE), "9223372036854775807"),
					new RetryCase(Duration.ofMillis(Long.MAX_VALUE).plusNanos(999_999), "9223372036854775807"))) {
				fixture.broadcaster().broadcastEvent(SseEvent.builder().retry(testCase.duration()).build());
				assertEquals("retry: " + testCase.milliseconds() + "\n\n", readThrough(fixture.socket, "\n\n"));
			}
		}
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void absentDataAndEmptyDataRemainDistinctOnTheWire() throws Exception {
		try (LiveFixture fixture = new LiveFixture(Duration.ofMinutes(10))) {
			fixture.broadcaster().broadcastEvent(SseEvent.withEvent("ready").build());
			assertEquals("event: ready\n\n", readThrough(fixture.socket, "\n\n"));
			fixture.broadcaster().broadcastEvent(SseEvent.withEvent("ready").data("").build());
			assertEquals("event: ready\ndata: \n\n", readThrough(fixture.socket, "\n\n"));
			fixture.broadcaster().broadcastEvent(SseEvent.builder().id("resume").build());
			assertEquals("id: resume\n\n", readThrough(fixture.socket, "\n\n"));
		}
	}

	private record RetryCase(Duration duration, String milliseconds) {}

	private static String readThrough(Socket socket, String delimiter) throws Exception {
		StringBuilder response = new StringBuilder();
		int value;
		while ((value = socket.getInputStream().read()) >= 0 && response.length() < 16_384) {
			response.append((char) value);
			if (response.toString().endsWith(delimiter)) return response.toString();
		}
		throw new AssertionError("Missing SSE frame delimiter");
	}

	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + 3_000_000_000L;
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue(condition.getAsBoolean());
	}

	private static final class LiveFixture implements AutoCloseable {
		final DefaultSseServer server;
		final Soklet app;
		final Socket socket;
		final List<LogEvent> logs = new CopyOnWriteArrayList<>();

		LiveFixture(Duration heartbeatInterval) throws Exception {
			int port = findFreePort();
			server = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1")
					.heartbeatInterval(heartbeatInterval).verifyConnectionOnceEstablished(false).build();
			app = Soklet.fromConfig(SokletConfig.withSseServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(300))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
					}).build());
			Socket connected = null;
			try {
				app.start();
				connected = connectWithRetry("127.0.0.1", port, 2000);
				connected.setSoTimeout(2000);
				connected.getOutputStream().write("GET /duration-events HTTP/1.1\r\nHost: localhost\r\n\r\n"
						.getBytes(StandardCharsets.ISO_8859_1));
				assertTrue(readThrough(connected, "\r\n\r\n").startsWith("HTTP/1.1 200"));
				await(() -> broadcaster().getClientCount() == 1);
				socket = connected;
			} catch (Exception | AssertionError failure) {
				if (connected != null) connected.close();
				app.close();
				throw failure;
			}
		}

		SseBroadcaster broadcaster() {
			return server.acquireBroadcaster(ResourcePath.fromPath("/duration-events")).orElseThrow();
		}

		@Override public void close() throws Exception {
			try { socket.close(); } finally { app.close(); }
			assertTrue(app.getShutdownResult().orElseThrow().isComplete());
			assertTrue(logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.SSE_SERVER_INTERNAL_ERROR
					|| event.getLogEventType() == LogEventType.SSE_SERVER_BROADCAST_GENERATION_FAILED), logs.toString());
		}
	}

	public static final class Resource {
		@SseEventSource("/duration-events")
		public SseHandshakeResult events() { return SseHandshakeResult.accept(); }
	}
}
