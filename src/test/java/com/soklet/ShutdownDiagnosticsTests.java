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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ShutdownDiagnosticsTests {
	@Test
	void incompleteExceptionIdentifiesFrozenComponentStatesAndResidualCategories() {
		var internal = new InternalShutdownResult(InternalShutdownDisposition.INCOMPLETE, InternalStartupDisposition.READY,
				List.of(component(InternalLifecycleComponentType.SSE, InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY,
						List.of(), Set.of(InternalResidualActivityType.STREAM, InternalResidualActivityType.CALLBACK)),
						component(InternalLifecycleComponentType.HTTP, InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
								List.of(), Set.of())));
		var exception = new SokletShutdownIncompleteException(internal);
		String message = exception.getMessage();
		assertTrue(message.startsWith("Soklet shutdown could not prove complete termination: "));
		assertTrue(message.contains("shutdownDisposition=INCOMPLETE"));
		assertTrue(message.contains("startupDisposition=READY"));
		assertTrue(message.contains("shutdownComponentType=SSE"));
		assertTrue(message.contains("shutdownComponentDisposition=RESIDUAL_ACTIVITY"));
		assertTrue(message.contains("residualActivityTypes=[CALLBACK, STREAM]"));
		assertTrue(message.indexOf("shutdownComponentType=HTTP") < message.indexOf("shutdownComponentType=SSE"));
		assertNull(exception.getCause(), "Outstanding work does not invent a Throwable cause");
		assertSame(internal, exception.getInternalShutdownResult());
		assertFalse(exception.getShutdownResult().isComplete());
	}

	@Test
	void renderingNeverInvokesApplicationThrowablesOrTheRetainedGraph() {
		AtomicInteger attemptedReads = new AtomicInteger();
		HostileThrowable failure = new HostileThrowable(attemptedReads);
		Object graph = new Object() { @Override public String toString() { attemptedReads.incrementAndGet(); throw new AssertionError("graph rendered"); } };
		var internal = new InternalShutdownResult(InternalShutdownDisposition.INCOMPLETE, InternalStartupDisposition.FAILED,
				List.of(component(InternalLifecycleComponentType.MCP, InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
						List.of(failure), Set.of(InternalResidualActivityType.EXECUTOR_TASK))))
				.withRetentionAnchor(new LifecycleRetentionAnchor(graph, Map.of(InternalResidualActivityType.EXECUTOR_TASK, 7), "private framework note"));
		ShutdownResult result = ShutdownResult.fromInternal(internal, failure, ShutdownComponentType.MCP, failure);
		var exception = new SokletShutdownIncompleteException(result, graph, failure);
		assertSame(failure, exception.getCause());
		assertSame(failure, result.getStartupFailureCause().orElseThrow());
		assertSame(failure, result.getShutdownComponentResult(ShutdownComponentType.MCP).orElseThrow().getThrowables().get(0));
		String rendered = result.toString();
		assertTrue(rendered.contains("throwableCount=1"));
		assertTrue(rendered.contains("startupFailurePresent=true"));
		assertTrue(rendered.contains("unexpectedShutdownComponentType=MCP"));
		assertTrue(rendered.contains("residualComponentCounts={EXECUTOR_TASK=7}"));
		assertFalse(rendered.contains("private framework note"));
		assertEquals("Soklet shutdown could not prove complete termination: " + rendered, exception.getMessage());
		assertTrue(exception.retainsScopeEvidence(graph));
		assertEquals(0, attemptedReads.get());
	}

	@Test
	void renderedOutputIsBoundedWithLargeFailureListsAndResidualSummaries() {
		AtomicInteger attemptedReads = new AtomicInteger();
		var failures = Collections.nCopies(10_000, new HostileThrowable(attemptedReads));
		var results = new ArrayList<InternalLifecycleComponentShutdownResult>();
		for (var type : InternalLifecycleComponentType.values())
			results.add(component(type, InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN, failures,
					EnumSet.allOf(InternalResidualActivityType.class)));
		var internal = new InternalShutdownResult(InternalShutdownDisposition.INCOMPLETE, InternalStartupDisposition.READY, results)
				.withRetentionAnchor(new LifecycleRetentionAnchor(new Object(), Map.of(InternalResidualActivityType.CALLBACK, Integer.MAX_VALUE),
						"secret\n\u001B".repeat(20_000)));
		ShutdownResult result = ShutdownResult.fromInternal(internal);
		String rendered = result.toString();
		assertTrue(rendered.length() < 2_048);
		assertTrue(rendered.contains("throwableCount=10000"));
		assertFalse(rendered.contains("secret"));
		assertTrue(rendered.codePoints().noneMatch(Character::isISOControl));
		for (var component : result.getShutdownComponentResults()) {
			var evidence = component.getResidualActivityEvidence().orElseThrow();
			assertTrue(evidence.toString().startsWith("ResidualActivityEvidence{residualActivityTypes="));
			assertFalse(evidence.toString().contains("secret"));
			assertTrue(component.toString().length() < 512);
		}
		assertEquals(0, attemptedReads.get());
	}

	@Test
	void residualRenderingUsesAnImmutableEnumOrderedSnapshotAndOmitsTheFreeTextSummary() {
		var types = EnumSet.of(ResidualActivityType.LIFECYCLE_CALL, ResidualActivityType.CALLBACK);
		var evidence = new ResidualActivityEvidence(types, "sensitive\nrequest details");
		types.clear();
		assertEquals("ResidualActivityEvidence{residualActivityTypes=[CALLBACK, LIFECYCLE_CALL]}", evidence.toString());
		assertEquals("sensitive\nrequest details", evidence.getSummary());
		assertEquals("ResidualActivityEvidence{residualActivityTypes=[]}", new ResidualActivityEvidence(Set.of(), "").toString());
	}

	@TestFactory
	Stream<DynamicTest> allAggregateDispositionsHaveUsefulDeterministicRendering() {
		return Stream.of(InternalShutdownDisposition.values()).map(disposition -> DynamicTest.dynamicTest(disposition.name(), () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
			var result = ShutdownResult.fromInternal(new InternalShutdownResult(disposition, InternalStartupDisposition.NOT_ATTEMPTED, List.of()));
			String expected = "ShutdownResult{shutdownDisposition=" + disposition.name()
					+ ", startupDisposition=NOT_ATTEMPTED, shutdownComponentResults=[], startupFailurePresent=false, unexpectedShutdownComponentType=none}";
			assertEquals(expected, result.toString());
			assertEquals(expected, String.format("%s", result));
			if (disposition != InternalShutdownDisposition.INCOMPLETE)
				assertThrows(IllegalArgumentException.class, () -> new SokletShutdownIncompleteException(result));
		})));
	}

	@TestFactory
	Stream<DynamicTest> actualSimulatorResidualWorkIsExplainedWithoutChangingFailurePrecedence() {
		return Stream.of(false, true).flatMap(sse -> Stream.of(false, true).map(bodyFails ->
				DynamicTest.dynamicTest((sse ? "SSE initializer" : "HTTP handler") + (bodyFails ? ", body fails" : ", body succeeds"), () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
					Fixture fixture = new Fixture();
					AtomicReference<Thread> worker = new AtomicReference<>();
					AtomicReference<Throwable> workerFailure = new AtomicReference<>();
					RuntimeException bodyFailure = new IllegalStateException("original body failure");
					try {
						Class<? extends RuntimeException> expectedFailureType = bodyFails ? IllegalStateException.class : SokletShutdownIncompleteException.class;
						Throwable thrown = assertThrows(expectedFailureType,
								() -> SokletSimulator.run(config(fixture), simulator -> {
									Thread thread = new Thread(() -> {
										try {
											Request request = Request.withPath(HttpMethod.GET, sse ? "/held-sse" : "/held-http").build();
											if (sse) simulator.performSseRequest(request); else simulator.performHttpRequest(request);
										} catch (Throwable failure) { workerFailure.set(failure); }
									}, "diagnostic-held-" + (sse ? "sse" : "http"));
									worker.set(thread); thread.start(); await(fixture.entered);
									if (bodyFails) throw bodyFailure;
								}));
						SokletShutdownIncompleteException incomplete;
						if (bodyFails) {
							assertSame(bodyFailure, thrown); assertEquals(1, thrown.getSuppressed().length);
							incomplete = assertInstanceOf(SokletShutdownIncompleteException.class, thrown.getSuppressed()[0]);
						} else incomplete = assertInstanceOf(SokletShutdownIncompleteException.class, thrown);
						ShutdownResult result = incomplete.getShutdownResult(); String frozenMessage = incomplete.getMessage();
						var component = result.getShutdownComponentResult(sse ? ShutdownComponentType.SSE : ShutdownComponentType.HTTP).orElseThrow();
						assertFalse(result.isComplete()); assertTrue(worker.get().isAlive()); assertNull(incomplete.getCause());
						assertTrue(frozenMessage.contains("shutdownComponentType=" + (sse ? "SSE" : "HTTP")));
						assertTrue(frozenMessage.contains("shutdownComponentDisposition=" + component.getShutdownComponentDisposition().name()));
						for (var type : component.getResidualActivityEvidence().orElseThrow().getResidualActivityTypes())
							assertTrue(frozenMessage.contains(type.name()));
						assertTrue(frozenMessage.contains("residualActivityTypes="));
						assertFalse(frozenMessage.contains("original body failure"));
						fixture.release.countDown(); join(worker.get());
						assertEquals(frozenMessage, incomplete.getMessage()); assertFalse(result.isComplete());
						assertSame(result, incomplete.getShutdownResult());
						if (sse) assertInstanceOf(IllegalStateException.class, workerFailure.get()); else assertNull(workerFailure.get());
					} finally { fixture.release.countDown(); join(worker.get()); }
				}))));
	}

	private static InternalLifecycleComponentShutdownResult component(InternalLifecycleComponentType type,
			InternalLifecycleComponentShutdownDisposition disposition, List<? extends Throwable> failures,
			Set<InternalResidualActivityType> residual) {
		return new InternalLifecycleComponentShutdownResult(type, disposition, failures, residual);
	}
	private static SimulatorConfig config(Fixture fixture) {
		var builder = SimulatorConfig.builder().httpServer().sseServer();
		return builder.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Fixture.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type == Fixture.class ? type.cast(fixture) : InstanceProvider.defaultInstance().provide(type); }
				}).lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(20))
						.forcedShutdownTimeout(Duration.ofMillis(20)).build())
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} }).build();
	}
	private static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
	private static void hold(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) { try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
		if (interrupted) Thread.currentThread().interrupt();
	}
	private static void join(Thread thread) throws InterruptedException { if (thread != null) { thread.join(5000); assertFalse(thread.isAlive()); } }
	public static final class Fixture {
		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		@GET("/held-http") public String http() { entered.countDown(); hold(release); return "done"; }
		@SseEventSource("/held-sse") public SseHandshakeResult sse() {
			return SseHandshakeResult.Accepted.builder().clientInitializer(client -> { entered.countDown(); hold(release); }).build();
		}
	}
	private static final class HostileThrowable extends RuntimeException {
		private final AtomicInteger attemptedReads;
		private HostileThrowable(AtomicInteger attemptedReads) { this.attemptedReads = attemptedReads; }
		@Override public String getMessage() { attemptedReads.incrementAndGet(); throw new AssertionError("Throwable message read"); }
		@Override public String toString() { attemptedReads.incrementAndGet(); throw new AssertionError("Throwable rendered"); }
	}
}
