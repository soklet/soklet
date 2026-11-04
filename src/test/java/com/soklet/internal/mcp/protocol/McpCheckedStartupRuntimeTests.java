/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import com.soklet.CorsAuthorizer;
import com.soklet.McpEndpoint;
import com.soklet.McpImplementation;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEvent;
import com.soklet.McpSubscriptionEventListener;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionEventRegistration;
import com.soklet.McpSubscriptionNotificationType;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

@NotThreadSafe
@Timeout(600)
public class McpCheckedStartupRuntimeTests {
	private static final String HOST = "127.0.0.1";

	@Test
	void ownerlessSimulationFailureFencesListenerAndReleasesGeneration()
			throws Exception {
		for (Throwable expected : startupFailures()) {
			FailOncePublisher publisher = new FailOncePublisher(expected);
			try (RuntimeFixture fixture = new RuntimeFixture(publisher)) {
				McpServerRuntimeBridge bridge = fixture.bridge;
				McpHttpServerRuntime runtime = fixture.runtime;
				Throwable observed = Assertions.assertThrows(Throwable.class,
						bridge::openSimulationSession);
				Assertions.assertSame(expected, observed);
				assertListenerFenced(runtime, publisher.listener);
				awaitSimulationResidualTermination(runtime);

				try (McpServerRuntimeBridge.SimulationSession retry =
						bridge.openSimulationSession()) {
					Object before = invalidationToken(runtime);
					publisher.listener.onEvent(McpSubscriptionEvent.toolsListChanged());
					Assertions.assertNotSame(before, invalidationToken(runtime),
							"The successful retry listener must still deliver events");
					retry.force();
					Assertions.assertTrue(retry.awaitTermination(deadline(),
							System::nanoTime));
					retry.releaseLifecycleEvidence();
				}
				Assertions.assertEquals(1, publisher.closes.get());
				assertListenerFenced(runtime, publisher.listener);
				Assertions.assertFalse(bridge.getRuntimeState().stopRequired());
			}
		}
	}

	@Test
	void liveStartupFailureNormalizesCheckedCauseAndCompletesCleanup()
			throws Exception {
		for (Throwable expected : startupFailures()) {
			FailOncePublisher publisher = new FailOncePublisher(expected);
			try (RuntimeFixture fixture = new RuntimeFixture(publisher)) {
				McpServerRuntimeBridge bridge = fixture.bridge;
				McpHttpServerRuntime runtime = fixture.runtime;
				Throwable observed = Assertions.assertThrows(Throwable.class,
						bridge::start);
				if (expected instanceof IOException
						|| expected instanceof RuntimeException || expected instanceof Error)
					Assertions.assertSame(expected, observed);
				else {
					Assertions.assertInstanceOf(IOException.class, observed);
					Assertions.assertSame(expected, observed.getCause());
				}
				InetSocketAddress failedAddress = bridge.getBoundAddress().orElseThrow();
				Assertions.assertFalse(bridge.getRuntimeState().started());
				Assertions.assertFalse(bridge.getRuntimeState().stopRequired());
				Assertions.assertEquals(Boolean.FALSE,
						field(runtime, "lifecycleStartupInProgress"));
				Assertions.assertEquals(Boolean.FALSE,
						field(runtime, "lifecycleStartupClaimed"));
				assertListenerFenced(runtime, publisher.listener);
				assertListenerReturned(failedAddress);

				InetSocketAddress retryAddress = bridge.start();
				Assertions.assertTrue(bridge.isStarted());
				bridge.forceLifecycle();
				Assertions.assertTrue(bridge.awaitLifecycleTermination(deadline()));
				bridge.releaseLifecycleEvidence();
				Assertions.assertEquals(1, publisher.closes.get());
				assertListenerFenced(runtime, publisher.listener);
				assertListenerReturned(retryAddress);
			}
		}
	}

	private static final class RuntimeFixture implements AutoCloseable {
		private final McpServerRuntimeBridge bridge;
		private final McpHttpServerRuntime runtime;

		private RuntimeFixture(FailOncePublisher publisher) throws Exception {
			this.bridge = bridge(publisher);
			this.runtime = runtime(this.bridge);
		}

		@Override
		public void close() throws Exception {
			// Assertions precede this safeguard. If startup regresses, direct calls have
			// already returned: retire their stranded evidence so a failing test cannot
			// leak an event loop. Try-with-resources preserves the primary assertion.
			Object generation = field(this.runtime, "simulationGeneration");
			if (generation != null) {
				Method close = McpHttpServerRuntime.class.getDeclaredMethod(
						"closeSimulationGeneration", generation.getClass());
				close.setAccessible(true);
				close.invoke(this.runtime, generation);
			}
			for (String name : List.of("lifecycleStartupInProgress",
					"lifecycleStartupClaimed")) {
				Field field = McpHttpServerRuntime.class.getDeclaredField(name);
				field.setAccessible(true);
				field.setBoolean(this.runtime, false);
			}
			awaitSimulationResidualTermination(this.runtime);
			this.bridge.forceLifecycle();
			Assertions.assertTrue(this.bridge.awaitLifecycleTermination(deadline()),
					"The test must not retain an MCP listener or executor after failure");
			this.bridge.releaseLifecycleEvidence();
		}
	}

	private static List<Throwable> startupFailures() {
		return List.of(new Exception("checked callback"),
				new IOException("checked I/O callback"),
				new InterruptedException("checked interruption callback"),
				new IllegalStateException("runtime callback control"),
				new AssertionError("error callback control"));
	}

	private static McpServerRuntimeBridge bridge(FailOncePublisher publisher) {
		Set<com.soklet.McpProtocolVersion> versions =
				Set.of(com.soklet.McpProtocolVersion.V2026_07_28);
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("checked-startup", "1").build(),
				versions).subscriptionProtocolVersions(versions)
				.subscriptionConfig(McpSubscriptionConfig
						.withEventPublisherAndNotificationTypes(publisher,
								Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED))
						.build()).build();
		return new McpServerRuntimeBridge(HOST, 0, endpoint, Set.of(HOST), false,
				CorsAuthorizer.rejectAllInstance(), true,
				ignored -> com.soklet.McpAdmissionDecision.accepted(), ignored -> {});
	}

	private static McpHttpServerRuntime runtime(McpServerRuntimeBridge bridge)
			throws Exception {
		return (McpHttpServerRuntime) field(bridge, "runtime");
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static Object invalidationToken(McpHttpServerRuntime runtime)
			throws Exception {
		Method method = McpHttpServerRuntime.class.getDeclaredMethod(
				"catalogInvalidationToken", String.class);
		method.setAccessible(true);
		return method.invoke(runtime, "/mcp");
	}

	private static void awaitSimulationResidualTermination(
			McpHttpServerRuntime runtime) throws Exception {
		Object lifecycleLock = field(runtime, "lifecycleLock");
		long deadline = deadline();
		while (true) {
			synchronized (lifecycleLock) {
				Assertions.assertNull(field(runtime, "simulationGeneration"),
						"Ownerless failed startup must retire its simulation generation");
				ThreadPoolExecutor processor = (ThreadPoolExecutor) field(runtime,
						"residualSimulationRequestProcessor");
				McpApplicationExecution application = (McpApplicationExecution) field(
						runtime, "residualSimulationApplicationExecution");
				if ((processor == null || processor.isTerminated())
						&& (application == null || application.isTerminated()))
					return;
			}
			long remaining = deadline - System.nanoTime();
			Assertions.assertTrue(remaining > 0L,
					"Failed-start executors must terminate before a fresh simulation");
			LockSupport.parkNanos(Math.min(remaining, Duration.ofMillis(1).toNanos()));
		}
	}

	private static void assertListenerFenced(McpHttpServerRuntime runtime,
			McpSubscriptionEventListener listener) throws Exception {
		Object before = invalidationToken(runtime);
		listener.onEvent(McpSubscriptionEvent.toolsListChanged());
		Assertions.assertSame(before, invalidationToken(runtime),
				"A listener retained by a failed or closed publisher must be inert");
	}

	private static long deadline() {
		return System.nanoTime() + Duration.ofSeconds(5).toNanos();
	}

	private static void assertListenerReturned(InetSocketAddress address)
			throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(true);
			socket.bind(address);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Throwable> RuntimeException unchecked(Throwable failure)
			throws T {
		// Java generic erasure models callbacks from JVM languages without checked throws.
		throw (T) failure;
	}

	private static final class FailOncePublisher
			implements McpSubscriptionEventPublisher {
		private final Throwable failure;
		private final AtomicInteger subscribes = new AtomicInteger();
		private final AtomicInteger closes = new AtomicInteger();
		private McpSubscriptionEventListener listener;

		private FailOncePublisher(Throwable failure) {
			this.failure = failure;
		}

		@Override
		public McpSubscriptionEventRegistration subscribe(
				@NonNull McpSubscriptionEventListener listener) {
			this.listener = listener;
			if (this.subscribes.getAndIncrement() == 0)
				throw unchecked(this.failure);
			return this.closes::incrementAndGet;
		}

		@Override
		public void publish(@NonNull McpSubscriptionEvent event) {
		}
	}
}
