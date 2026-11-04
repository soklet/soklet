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

package com.soklet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@NotThreadSafe
@Timeout(240)
public class McpCheckedStartupLifecycleTests {
	private static final String HOST = "127.0.0.1";
	private static final LifecyclePolicy POLICY = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(5))
			.startupCancelationTimeout(Duration.ofSeconds(1))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();

	@Test
	void simulatorStartupFailureClosesEarlierRegistrationAndAllowsFreshRun() {
		for (Throwable expected : startupFailures()) {
			RecordingPublisher first = new RecordingPublisher(null);
			RecordingPublisher failing = new RecordingPublisher(expected);
			AtomicInteger bodies = new AtomicInteger();
			SimulatorConfig config = simulatorConfig(first, failing);

			SokletStartupException observed = Assertions.assertThrows(
					SokletStartupException.class, () -> SokletSimulator.run(config,
							simulator -> bodies.incrementAndGet()));
			assertAndClearCallbackInterrupt(expected);
			assertCompleteFailure(expected, observed);
			Assertions.assertEquals(0, bodies.get());
			Assertions.assertEquals(1, first.closes.get(),
					"Startup rollback must close the earlier successful registration");
			Assertions.assertEquals(0, failing.closes.get(),
					"The failed subscribe call did not transfer a registration");

			ShutdownResult retry = SokletSimulator.run(simulatorConfig(first, failing),
					simulator -> bodies.incrementAndGet());
			Assertions.assertTrue(retry.isComplete());
			Assertions.assertEquals(StartupDisposition.READY,
					retry.getStartupDisposition());
			Assertions.assertEquals(1, bodies.get());
			Assertions.assertEquals(2, first.closes.get());
			Assertions.assertEquals(1, failing.closes.get());
		}
	}

	@Test
	void liveStartupFailurePublishesExactTerminalCauseAndReleasesListener()
			throws Exception {
		for (Throwable expected : startupFailures()) {
			RecordingPublisher first = new RecordingPublisher(null);
			RecordingPublisher failing = new RecordingPublisher(expected);
			McpServer failedServer = configure(McpServer.withPort(0), first, failing)
					.build();
			InetSocketAddress failedAddress;
			Soklet owner = Soklet.fromConfig(config(failedServer));
			try {
				SokletStartupException observed = Assertions.assertThrows(
						SokletStartupException.class, owner::start);
				Assertions.assertFalse(Thread.interrupted(),
						"A failure on the startup worker must not interrupt its caller");
				assertCompleteFailure(expected, observed);
				ShutdownResult terminal = owner.awaitShutdown();
				Assertions.assertTrue(terminal.isComplete());
				Assertions.assertSame(observed.getInternalShutdownResult(),
						terminal.internalResult());
				Assertions.assertEquals(1, first.closes.get());
				Assertions.assertEquals(0, failing.closes.get());
				failedAddress = failedServer.getDiagnostics().getBoundAddress()
						.orElseThrow();
				assertListenerReturned(failedAddress);
			} finally {
				owner.close();
			}
			assertListenerReturned(failedAddress);

			McpServer retryServer = configure(McpServer.withPort(0), first, failing)
					.build();
			AtomicInteger bodies = new AtomicInteger();
			ShutdownResult retry = Soklet.run(config(retryServer),
					bodies::incrementAndGet);
			Assertions.assertTrue(retry.isComplete());
			Assertions.assertEquals(StartupDisposition.READY,
					retry.getStartupDisposition());
			Assertions.assertEquals(1, bodies.get());
			Assertions.assertEquals(2, first.closes.get());
			Assertions.assertEquals(1, failing.closes.get());
			assertListenerReturned(retryServer.getDiagnostics().getBoundAddress()
					.orElseThrow());
		}
	}

	private static void assertAndClearCallbackInterrupt(Throwable expected) {
		Assertions.assertEquals(expected instanceof InterruptedException,
				Thread.interrupted(),
				"Only an interrupted startup restores the caller's interrupt status");
	}

	private static void assertCompleteFailure(Throwable expected,
			SokletStartupException observed) {
		Assertions.assertSame(expected, observed.getCause());
		Assertions.assertEquals(expected instanceof InterruptedException
				? StartupDisposition.CANCELED : StartupDisposition.FAILED,
				observed.getStartupDisposition());
		Assertions.assertTrue(observed.getShutdownResult().isComplete(),
				"A returned startup callback must not strand cleanup in STARTING");
		InternalLifecycleComponentShutdownResult mcp = observed
				.getInternalShutdownResult()
				.participantResult(InternalLifecycleComponentType.MCP).orElseThrow();
		Assertions.assertTrue(mcp.residualActivity().isEmpty());
		Assertions.assertSame(expected, mcp.failures().get(0));
	}

	private static McpServer.Builder configure(McpServer.Builder builder,
			RecordingPublisher first, RecordingPublisher failing) {
		return builder.host(HOST).port(0).allowedHosts(Set.of(HOST))
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(ignored -> McpRateLimitDecision.allowed())
				.toolRateLimiter(ignored -> McpRateLimitDecision.allowed())
				.subscriptionAuthorizer(McpSubscriptionAuthorizer.denyAllInstance())
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(
						endpoint("/first", first), endpoint("/failing", failing))));
	}

	private static McpEndpoint endpoint(String path,
			McpSubscriptionEventPublisher publisher) {
		Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2026_07_28);
		return McpEndpoint.withPath(path, McpImplementation
				.withNameAndVersion("checked-startup", "1").build(), versions)
				.subscriptionProtocolVersions(versions)
				.subscriptionConfig(McpSubscriptionConfig
						.withEventPublisherAndNotificationTypes(publisher,
								Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED))
						.build()).build();
	}

	private static SimulatorConfig simulatorConfig(RecordingPublisher first,
			RecordingPublisher failing) {
		return SimulatorConfig.builder()
				.configureMcpServer(builder -> configure(builder, first, failing))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() {})
				.lifecyclePolicy(POLICY).build();
	}

	private static SokletConfig config(McpServer server) {
		return SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() {})
				.lifecyclePolicy(POLICY).build();
	}

	private static List<Throwable> startupFailures() {
		return List.of(new Exception("checked callback"),
				new IOException("checked I/O callback"),
				new InterruptedException("checked interruption callback"),
				new IllegalStateException("runtime callback control"),
				new AssertionError("error callback control"));
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
		throw (T) failure;
	}

	private static final class RecordingPublisher
			implements McpSubscriptionEventPublisher {
		private final @Nullable Throwable firstFailure;
		private final AtomicInteger subscribes = new AtomicInteger();
		private final AtomicInteger closes = new AtomicInteger();

		private RecordingPublisher(@Nullable Throwable firstFailure) {
			this.firstFailure = firstFailure;
		}

		@Override
		public McpSubscriptionEventRegistration subscribe(
				@NonNull McpSubscriptionEventListener listener) {
			if (this.subscribes.getAndIncrement() == 0 && this.firstFailure != null)
				throw unchecked(this.firstFailure);
			return this.closes::incrementAndGet;
		}

		@Override
		public void publish(@NonNull McpSubscriptionEvent event) {
		}
	}
}
