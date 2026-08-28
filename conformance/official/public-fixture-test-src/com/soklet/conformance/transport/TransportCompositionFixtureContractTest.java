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

package com.soklet.conformance.transport;

import com.soklet.LifecyclePolicy;
import com.soklet.ParticipantKind;
import com.soklet.ParticipantShutdownDisposition;
import com.soklet.ParticipantShutdownResult;
import com.soklet.ResourceMethodResolver;
import com.soklet.ShutdownDisposition;
import com.soklet.ShutdownResult;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.StartupDisposition;
import com.soklet.TransportIdentity;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.soklet.conformance.transport.TransportCompositionFixture.Composition;
import static com.soklet.conformance.transport.TransportCompositionFixture.HttpGraph;
import static com.soklet.conformance.transport.TransportCompositionFixture.SseGraph;

/**
 * Candidate-JAR-only executable contract for the published transport fixture.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class TransportCompositionFixtureContractTest {
	private TransportCompositionFixtureContractTest() {
	}

	/** Executes every reviewed external graph shape. */
	public static void main(String[] arguments) throws Exception {
		for (Composition composition : Composition.values())
			runGraph(composition);
	}

	private static void runGraph(Composition composition) throws Exception {
		HttpGraph http = TransportCompositionFixture.httpGraph(composition);
		SseGraph sse = TransportCompositionFixture.sseGraph(composition);
		assertSame(http.leaf().getTransportIdentity(),
				http.outer().getTransportIdentity(),
				"HTTP decorators must preserve exact graph identity");
		assertSame(sse.leaf().getTransportIdentity(),
				sse.outer().getTransportIdentity(),
				"SSE decorators must preserve exact graph identity");
		assertFresh(http.outer().getTransportIdentity(),
				sse.outer().getTransportIdentity());

		LifecyclePolicy policy = LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(5))
				.startupCancellationTimeout(Duration.ofSeconds(2))
				.gracefulShutdownDuration(Duration.ofSeconds(5))
				.forcedShutdownDuration(Duration.ofSeconds(2))
				.build();
		SokletConfig config = SokletConfig.withHttpServer(http.outer())
				.sseServer(sse.outer())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(
								TransportCompositionFixture.FixtureResources.class)))
				.lifecyclePolicy(policy)
				.build();

		ShutdownResult result;
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			http.leaf().dispatchTestRequest();
			sse.leaf().dispatchTestRequest();
			sse.outer().acquireBroadcaster(null);
			soklet.shutdown();
			result = soklet.awaitShutdown();
		}

		assertEquals(StartupDisposition.READY, result.getStartupDisposition(),
				"Startup disposition for " + composition);
		assertEquals(ShutdownDisposition.GRACEFUL, result.getDisposition(),
				"Shutdown disposition for " + composition);
		assertTrue(result.isComplete(),
				"Shutdown must be complete for " + composition);
		assertParticipant(result, ParticipantKind.HTTP);
		assertParticipant(result, ParticipantKind.SSE);
		assertEquals(2, result.getParticipantResults().size(),
				"Configured participant count for " + composition);
		assertGraphEvents(composition, "http", http.probe().events());
		assertGraphEvents(composition, "sse", sse.probe().events());
	}

	private static void assertParticipant(ShutdownResult result,
			ParticipantKind kind) {
		ParticipantShutdownResult participant = result.getParticipantResult(kind)
				.orElseThrow(() -> new AssertionError(
						"Missing participant result for " + kind));
		assertEquals(ParticipantShutdownDisposition.GRACEFUL_TERMINATION,
				participant.getDisposition(), "Participant disposition for " + kind);
		assertEquals(List.of(), participant.getFailures(),
				"Participant failures for " + kind);
	}

	private static void assertGraphEvents(Composition composition, String kind,
			List<String> events) {
		assertTrue(events.contains(kind + "-leaf:attach"),
				"Leaf attachment for " + composition + ": " + events);
		assertTrue(events.contains(kind + "-leaf:start"),
				"Leaf startup for " + composition + ": " + events);
		assertTrue(events.contains(kind + "-leaf:quiesce"),
				"Leaf quiesce for " + composition + ": " + events);
		if ("sse".equals(kind))
			assertTrue(events.contains("sse-leaf:broadcaster"),
					"SSE broadcaster forwarding for " + composition + ": " + events);

		switch (composition) {
			case ALTERNATIVE -> assertEquals(3L + ("sse".equals(kind) ? 1L : 0L),
					(long) events.size(),
					"Alternative-engine event count for " + kind);
			case TRANSPARENT -> {
				String name = kind + "-transparent";
				assertTrue(events.contains(name + ":attach"),
						"Transparent attachment for " + kind);
				assertTrue(events.contains(name + ":handler"),
						"Transparent handler wrapping for " + kind);
				if ("sse".equals(kind))
					assertTrue(events.contains(name + ":broadcaster"),
							"Transparent broadcaster forwarding");
				assertFalse(events.contains(name + ":start"),
						"Transparent decorator must not own a runtime");
			}
			case LIFECYCLE_OWNING -> assertOwning(events, kind + "-owner");
			case NESTED_LIFECYCLE_OWNING -> {
				assertOwning(events, kind + "-inner");
				assertOwning(events, kind + "-outer");
			}
		}
	}

	private static void assertOwning(List<String> events, String name) {
		for (String suffix : List.of(":attach", ":start", ":quiesce",
				":handler", ":delegate-proof", ":cleanup", ":terminated"))
			assertTrue(events.contains(name + suffix),
					"Missing owning-decorator event " + name + suffix + ": " + events);
	}

	private static void assertFresh(TransportIdentity left,
			TransportIdentity right) {
		if (left == right)
			throw new AssertionError(
					"Independent HTTP and SSE graphs shared an identity");
	}

	private static void assertSame(Object expected, Object actual,
			String message) {
		if (expected != actual)
			throw new AssertionError(message);
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition)
			throw new AssertionError(message);
	}

	private static void assertFalse(boolean condition, String message) {
		if (condition)
			throw new AssertionError(message);
	}

	private static void assertEquals(Object expected, Object actual,
			String message) {
		if (!expected.equals(actual))
			throw new AssertionError(message + ": expected " + expected
					+ ", actual " + actual);
	}
}
