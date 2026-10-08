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
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SseMemoizedBroadcastTests {
	private static final ResourcePath PATH = ResourcePath.fromPath("/memoized");
	// Two clients per group, including the valid null grouping key.
	private static final String[] GROUPS = {"bad", "bad", null, null, "ok", "ok"};

	@TestFactory
	Stream<DynamicTest> simulationCachesProviderFailuresAndNullResultsOnlyForOneBroadcast() {
		return cases().map(testCase -> DynamicTest.dynamicTest(testCase.toString(), () -> {
			AtomicReference<SseServer> server = new AtomicReference<>();
			SokletSimulator.run(simulatorConfig(server), simulator -> {
				List<Throwable> failures = new ArrayList<>();
				simulator.onBroadcastError(failures::add);
				List<List<String>> deliveries = connectSimulation(simulator, testCase.comments(), false);
				SseBroadcaster broadcaster = server.get().acquireBroadcaster(PATH).orElseThrow();
				Map<GroupKey, Integer> calls = new HashMap<>();
				RuntimeException failure = new IllegalStateException("controlled provider failure");
				broadcast(broadcaster, testCase.comments(), SseMemoizedBroadcastTests::key, groupKey -> {
					calls.merge(groupKey, 1, Integer::sum);
					if (failedGroup(groupKey)) {
						if (testCase.nullResult()) return null;
						throw failure;
					}
					return "first";
				});
				assertOncePerKey(calls);
				assertEquals(2, failures.size(), "One failure notification per key, even when both keys throw the same instance");
				for (Throwable actual : failures) {
					if (testCase.nullResult()) assertInstanceOf(NullPointerException.class, actual);
					else assertSame(failure, actual);
				}
				assertDeliveries(deliveries, "first", false);
				calls.clear();
				broadcast(broadcaster, testCase.comments(), SseMemoizedBroadcastTests::key, groupKey -> {
					calls.merge(groupKey, 1, Integer::sum);
					return "recovered";
				});
				assertOncePerKey(calls);
				assertDeliveries(deliveries, "recovered", true);
				assertEquals(2, failures.size());
			});
		}));
	}

	@TestFactory
	@EnabledForJreRange(min = JRE.JAVA_21)
	Stream<DynamicTest> liveBroadcastCachesProviderAndSerializationFailuresAndRecoversOnTheNextCall() {
		return cases().map(testCase -> DynamicTest.dynamicTest(testCase.toString(), () -> {
			try (LiveFixture fixture = new LiveFixture()) {
				SseBroadcaster broadcaster = fixture.server.acquireBroadcaster(PATH).orElseThrow();
				broadcast(broadcaster, testCase.comments(), context -> { fail("No key selection without clients"); return null; },
						groupKey -> { fail("No generation without clients"); return null; });
				fixture.connect();
				Map<GroupKey, Integer> calls = new HashMap<>();
				RuntimeException failure = new IllegalStateException("controlled provider failure");
				broadcast(broadcaster, testCase.comments(), SseMemoizedBroadcastTests::key, groupKey -> {
					calls.merge(groupKey, 1, Integer::sum);
					if (failedGroup(groupKey)) {
						if (testCase.nullResult()) return null;
						throw failure;
					}
					return "first";
				});
				assertOncePerKey(calls);
				List<LogEvent> failures = fixture.generationFailures();
				assertEquals(2, failures.size());
				for (LogEvent log : failures) {
					assertTrue(log.getMessage().contains("2 connections"), log.getMessage());
					if (testCase.nullResult()) assertInstanceOf(NullPointerException.class, log.getThrowable().orElseThrow());
					else assertSame(failure, log.getThrowable().orElseThrow());
				}
				assertEquals(List.of(new Outcome(testCase.comments(), 2, 2, 0)), fixture.outcomes);
				for (int i = 4; i < GROUPS.length; i++)
					assertEquals(frame(testCase.comments(), "first"), readThrough(fixture.sockets.get(i), "\n\n"));
				calls.clear();
				broadcast(broadcaster, testCase.comments(), SseMemoizedBroadcastTests::key, groupKey -> {
					calls.merge(groupKey, 1, Integer::sum);
					return "recovered";
				});
				assertOncePerKey(calls);
				for (Socket socket : fixture.sockets)
					assertEquals(frame(testCase.comments(), "recovered"), readThrough(socket, "\n\n"));
				assertEquals(2, fixture.generationFailures().size());
				assertEquals(new Outcome(testCase.comments(), 6, 6, 0), fixture.outcomes.get(1));
				assertEquals(6, broadcaster.getClientCount());
			}
		}));
	}

	@TestFactory
	Stream<DynamicTest> simulationLogsGenerationFailuresOncePerKeyWhenTheHandlerIsAbsentOrFails() {
		return Stream.of(false, true).flatMap(comments -> Stream.of(false, true).map(failingHandler ->
				DynamicTest.dynamicTest((comments ? "comments" : "events") + (failingHandler ? " failing handler" : " default log"), () -> {
					AtomicReference<SseServer> server = new AtomicReference<>();
					List<LogEvent> logs = new ArrayList<>();
					SimulatorConfig config = SimulatorConfig.builder().sseServer(server::set)
							.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
							.lifecycleObserver(new LifecycleObserver() {
								@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
							}).build();
					SokletSimulator.run(config, simulator -> {
						if (failingHandler) simulator.onBroadcastError(failure -> { throw new IllegalStateException("failed error handler"); });
						List<List<String>> deliveries = connectSimulation(simulator, comments, false);
						RuntimeException failure = new IllegalStateException("controlled provider failure");
						broadcast(server.get().acquireBroadcaster(PATH).orElseThrow(), comments, SseMemoizedBroadcastTests::key,
								groupKey -> { if (failedGroup(groupKey)) throw failure; return "payload"; });
						List<LogEvent> generationFailures = logs.stream()
								.filter(log -> log.getLogEventType() == LogEventType.SSE_SERVER_BROADCAST_GENERATION_FAILED).toList();
						assertEquals(2, generationFailures.size());
						for (LogEvent log : generationFailures) {
							assertTrue(log.getMessage().contains("2 connections"), log.getMessage());
							assertSame(failure, log.getThrowable().orElseThrow());
						}
						assertDeliveries(deliveries, "payload", false);
					});
				})));
	}

	@TestFactory
	Stream<DynamicTest> simulationConsumerFailuresDoNotInvalidateTheSharedPayload() {
		return Stream.of(false, true).map(comments -> DynamicTest.dynamicTest(comments ? "comments" : "events", () -> {
			AtomicReference<SseServer> server = new AtomicReference<>();
			SokletSimulator.run(simulatorConfig(server), simulator -> {
				List<Throwable> failures = new ArrayList<>();
				simulator.onBroadcastError(failures::add);
				List<List<String>> deliveries = connectSimulation(simulator, comments, true);
				Map<GroupKey, Integer> calls = new HashMap<>();
				broadcast(server.get().acquireBroadcaster(PATH).orElseThrow(), comments, SseMemoizedBroadcastTests::key, groupKey -> {
					calls.merge(groupKey, 1, Integer::sum);
					return "payload";
				});
				assertOncePerKey(calls);
				assertEquals(4, failures.size(), "Consumer failures remain per client");
				assertDeliveries(deliveries, "payload", false);
			});
		}));
	}

	@TestFactory
	Stream<DynamicTest> simulationKeySelectionFailureRemainsPerClient() {
		return Stream.of(false, true).map(comments -> DynamicTest.dynamicTest(comments ? "comments" : "events", () -> {
			AtomicReference<SseServer> server = new AtomicReference<>();
			SokletSimulator.run(simulatorConfig(server), simulator -> {
				List<Throwable> failures = new ArrayList<>();
				simulator.onBroadcastError(failures::add);
				List<List<String>> deliveries = connectSimulation(simulator, comments, false);
				Map<GroupKey, Integer> calls = new HashMap<>();
				RuntimeException failure = new IllegalStateException("controlled key selection failure");
				broadcast(server.get().acquireBroadcaster(PATH).orElseThrow(), comments,
						context -> { if (!"ok".equals(context)) throw failure; return key(context); }, groupKey -> {
							calls.merge(groupKey, 1, Integer::sum);
							return "payload";
						});
				assertEquals(4, failures.size());
				failures.forEach(actual -> assertSame(failure, actual));
				assertEquals(Map.of(new GroupKey("ok"), 1), calls);
				assertDeliveries(deliveries, "payload", false);
			});
		}));
	}

	@TestFactory
	@EnabledForJreRange(min = JRE.JAVA_21)
	Stream<DynamicTest> liveKeySelectionFailureDoesNotAffectOtherClients() {
		return Stream.of(false, true).map(comments -> DynamicTest.dynamicTest(comments ? "comments" : "events", () -> {
			try (LiveFixture fixture = new LiveFixture()) {
				fixture.connect();
				RuntimeException failure = new IllegalStateException("controlled key selection failure");
				Map<GroupKey, Integer> calls = new HashMap<>();
				broadcast(fixture.server.acquireBroadcaster(PATH).orElseThrow(), comments,
						context -> { if (!"ok".equals(context)) throw failure; return key(context); }, groupKey -> {
							calls.merge(groupKey, 1, Integer::sum);
							return "payload";
						});
				assertEquals(4, fixture.generationFailures().size());
				fixture.generationFailures().forEach(log -> assertSame(failure, log.getThrowable().orElseThrow()));
				assertEquals(Map.of(new GroupKey("ok"), 1), calls);
				for (int i = 4; i < GROUPS.length; i++)
					assertEquals(frame(comments, "payload"), readThrough(fixture.sockets.get(i), "\n\n"));
			}
		}));
	}

	private record Case(boolean comments, boolean nullResult) {
		@Override public String toString() { return (comments ? "comments" : "events") + (nullResult ? " null result" : " thrown failure"); }
	}
	private record GroupKey(String value) {
		@Override public String toString() { throw new AssertionError("Grouping keys must not be rendered"); }
	}
	private record Outcome(boolean comments, int attempted, int enqueued, int dropped) {}
	private static Stream<Case> cases() {
		return Stream.of(new Case(false, false), new Case(false, true), new Case(true, false), new Case(true, true));
	}
	private static GroupKey key(Object context) { return context == null ? null : new GroupKey((String) context); }
	private static boolean failedGroup(GroupKey key) { return key == null || key.value().equals("bad"); }
	private static void assertOncePerKey(Map<GroupKey, Integer> calls) {
		assertEquals(3, calls.size());
		assertEquals(1, calls.get(null));
		assertEquals(1, calls.get(new GroupKey("bad")));
		assertEquals(1, calls.get(new GroupKey("ok")));
	}
	private static void broadcast(SseBroadcaster broadcaster, boolean comments,
			Function<Object, GroupKey> keySelector, Function<GroupKey, String> generator) {
		if (comments) {
			broadcaster.broadcastComment(keySelector, key -> {
				String payload = generator.apply(key);
				return payload == null ? null : SseComment.fromComment(payload);
			});
		} else {
			broadcaster.broadcastEvent(keySelector, key -> {
				String payload = generator.apply(key);
				return payload == null ? null : SseEvent.withData(payload).build();
			});
		}
	}
	private static SimulatorConfig simulatorConfig(AtomicReference<SseServer> server) {
		return SimulatorConfig.builder().sseServer(server::set)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class))).build();
	}
	private static List<List<String>> connectSimulation(Simulator simulator, boolean comments, boolean failingConsumers) {
		List<List<String>> deliveries = new ArrayList<>();
		for (String group : GROUPS) {
			List<String> client = new ArrayList<>();
			deliveries.add(client);
			SseRequestResult.HandshakeAccepted accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class,
					simulator.performSseRequest(Request.withPath(HttpMethod.GET, "/memoized")
							.queryParameters(group == null ? Map.of() : Map.of("group", List.of(group))).build()));
			accepted.registerEventConsumer(event -> {
				if (comments) return;
				if (failingConsumers && !"ok".equals(group)) throw new IllegalStateException("controlled client failure");
				client.add(event.getData().orElseThrow());
			});
			accepted.registerCommentConsumer(comment -> {
				if (!comments) return;
				if (failingConsumers && !"ok".equals(group)) throw new IllegalStateException("controlled client failure");
				client.add(comment.getComment().orElseThrow());
			});
		}
		return deliveries;
	}
	private static void assertDeliveries(List<List<String>> deliveries, String payload, boolean recovered) {
		for (int i = 0; i < GROUPS.length; i++)
			assertEquals(i < 4 ? (recovered ? List.of(payload) : List.of())
					: (recovered ? List.of("first", payload) : List.of(payload)), deliveries.get(i), "client " + i);
	}
	private static String path(String group) { return "/memoized" + (group == null ? "" : "?group=" + group); }
	private static String frame(boolean comments, String payload) { return (comments ? ": " : "data: ") + payload + "\n\n"; }
	private static String readThrough(Socket socket, String delimiter) throws Exception {
		StringBuilder bytes = new StringBuilder();
		int value;
		while ((value = socket.getInputStream().read()) >= 0) {
			bytes.append((char) value);
			if (bytes.toString().endsWith(delimiter)) return bytes.toString();
		}
		fail("Socket closed before " + delimiter);
		return bytes.toString();
	}
	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + 3_000_000_000L;
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue(condition.getAsBoolean());
	}
	private static final class LiveFixture implements AutoCloseable {
		final int port = findFreePort();
		final DefaultSseServer server;
		final Soklet app;
		final List<Socket> sockets = new ArrayList<>();
		final List<LogEvent> logs = new CopyOnWriteArrayList<>();
		final List<Outcome> outcomes = new CopyOnWriteArrayList<>();
		LiveFixture() throws Exception {
			server = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1")
					.heartbeatInterval(Duration.ofMinutes(10)).verifyConnectionOnceEstablished(false)
					.connectionQueueCapacity(8).build();
			app = Soklet.fromConfig(SokletConfig.withSseServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(300))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); } })
					.metricsCollector(new MetricsCollector() {
						@Override public void didBroadcastSseEvent(ResourcePathDeclaration route, Integer attempted, Integer enqueued, Integer dropped) {
							outcomes.add(new Outcome(false, attempted, enqueued, dropped));
						}
						@Override public void didBroadcastSseComment(ResourcePathDeclaration route, SseComment.CommentType type,
								Integer attempted, Integer enqueued, Integer dropped) {
							outcomes.add(new Outcome(true, attempted, enqueued, dropped));
						}
					}).build());
			app.start();
		}
		void connect() throws Exception {
			for (String group : GROUPS) {
				Socket socket = connectWithRetry("127.0.0.1", port, 2000);
				sockets.add(socket);
				socket.setSoTimeout(3000);
				socket.getOutputStream().write(("GET " + path(group) + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
						.getBytes(StandardCharsets.ISO_8859_1));
				assertTrue(readThrough(socket, "\r\n\r\n").startsWith("HTTP/1.1 200"));
			}
			await(() -> server.acquireBroadcaster(PATH).orElseThrow().getClientCount() == GROUPS.length);
		}
		List<LogEvent> generationFailures() {
			return logs.stream().filter(log -> log.getLogEventType() == LogEventType.SSE_SERVER_BROADCAST_GENERATION_FAILED).toList();
		}
		@Override public void close() throws Exception {
			try { for (Socket socket : sockets) socket.close(); }
			finally { app.close(); }
			assertTrue(app.getShutdownResult().orElseThrow().isComplete());
		}
	}
	public static final class Resource {
		@SseEventSource("/memoized")
		public SseHandshakeResult events(Request request) {
			String group = request.getQueryParameters().getOrDefault("group", List.of()).stream().findFirst().orElse(null);
			return SseHandshakeResult.Accepted.builder().clientContext(group).build();
		}
	}
}
