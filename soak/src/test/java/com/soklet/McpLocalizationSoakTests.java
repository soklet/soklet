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

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Sustained off-network localization coverage for concurrent catalog rendering,
 * immutable revision snapshots, list-change invalidation, and scope cleanup.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpLocalizationSoakTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/localization/soak";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TOOL_NAME = "localization.soak";
	private static final String TOOL_TITLE = "Canonical localization soak tool";
	private static final String SERVER_TITLE = "Canonical localization soak server";
	private static final Locale LOCALIZED_LOCALE = Locale.CANADA_FRENCH;
	private static final LocalizationSoakProfile PROFILE =
			LocalizationSoakProfile.fromSelectedProfile();

	@Test
	void localizationRenderAndInvalidationChurnReturnsResourcesToBaseline()
			throws Exception {
		long startedAt = System.nanoTime();
		LocalizationState state = new LocalizationState();
		McpServer server = server(state);
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
		SoakResourceSnapshot baseline;
		SoakResourceSnapshot finalSnapshot;

		try {
			runSimulatorWorkload(config, server, state, 1, 1, "warmup");
			assertStoppedAndDrained(server);
			LocalizationCounts warmCounts = state.snapshot();
			baseline = SoakResourceSnapshot.captureAfterGc();

			runSimulatorWorkload(config, server, state,
					PROFILE.concurrentClients(), PROFILE.cyclesPerClient(),
					"measured");
			assertStoppedAndDrained(server);
			LocalizationCounts measured = state.snapshot().minus(warmCounts);
			int localizedCatalogResponses = PROFILE.concurrentClients()
					* PROFILE.cyclesPerClient();
			int localizationCapableResponses = localizedCatalogResponses + 1;

			Assertions.assertEquals(localizedCatalogResponses,
					measured.localizedCatalogResponses());
			Assertions.assertEquals(localizationCapableResponses,
					measured.contextsCreated(),
					"Every tools/list response and the subscription terminal must "
							+ "create exactly one context.");
			Assertions.assertEquals(localizationCapableResponses,
					measured.localizationLookups(),
					"The fixture exposes exactly one localizable field per response.");
			Assertions.assertEquals(localizationCapableResponses,
					measured.boundedPreferenceMatches(),
					"Every context must receive the bounded fr-CA preference.");
			Assertions.assertEquals(PROFILE.cyclesPerClient(),
					measured.invalidationsRequested());
			Assertions.assertEquals(measured.invalidationsRequested(),
					measured.invalidationsDelivered());

			finalSnapshot = SoakResourceSnapshot.assertReturnsNear(
					"MCP localization render and invalidation churn", baseline,
					PROFILE.settleTimeout(), PROFILE.resourceTolerance());
			SoakReport.recordPassedScenario(
					"MCP localization render and invalidation churn",
					"clients=%d, revisionWaves=%d, localizedCatalogResponses=%d"
							.formatted(PROFILE.concurrentClients(),
									PROFILE.cyclesPerClient(),
									localizedCatalogResponses),
					Duration.ofNanos(System.nanoTime() - startedAt), baseline,
					finalSnapshot, PROFILE.resourceTolerance(),
					SoakReport.observations(
							"Localized catalog responses",
							Integer.toString(localizedCatalogResponses),
							"Subscription terminals pre-rendered", "1",
							"Localization contexts created",
							Integer.toString(measured.contextsCreated()),
							"Localization lookups completed",
							Integer.toString(measured.localizationLookups()),
							"Bounded locale preferences matched",
							Integer.toString(measured.boundedPreferenceMatches()),
							"Catalog invalidations requested/delivered",
							measured.invalidationsRequested() + "/"
									+ measured.invalidationsDelivered(),
							"Final active handlers/queued/streams/subscriptions",
							"0/0/0/0",
							"Final MCP status",
							server.getDiagnostics().getStatus().name(),
							"Settle timeout", PROFILE.settleTimeout().toString()));
		} finally {
			server.stop();
		}
	}

	private static void runSimulatorWorkload(@NonNull SokletConfig config,
			@NonNull McpServer server, @NonNull LocalizationState state,
			int concurrentClients, int revisionWaves, @NonNull String runId) {
		requireNonNull(config);
		requireNonNull(server);
		requireNonNull(state);
		requireNonNull(runId);
		Soklet.runSimulator(config, simulator -> {
			try {
				performWorkload(simulator, server, state, concurrentClients,
						revisionWaves, runId);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Localization soak was interrupted.", e);
			} catch (Exception e) {
				throw new AssertionError("Localization soak workload failed.", e);
			}
		});
	}

	private static void performWorkload(@NonNull Simulator simulator,
			@NonNull McpServer server, @NonNull LocalizationState state,
			int concurrentClients, int revisionWaves, @NonNull String runId)
			throws Exception {
		state.installRevision(runId + "-subscription");
		long deadline = System.nanoTime() + PROFILE.runTimeout().toNanos();
		ExecutorService executor = Executors.newFixedThreadPool(concurrentClients);

		try (McpSimulation subscription = simulator.startMcpRequest(
				localizationRequest(runId + "-subscription",
						"subscriptions/listen",
						",\"notifications\":{\"toolsListChanged\":true}"))) {
			McpSimulationResponse response = awaitResponse(subscription);
			Assertions.assertEquals(200, response.getStatusCode());
			Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					response.getBodyMode());
			String acknowledgment = awaitItem(subscription);
			assertContains(acknowledgment,
					"\"toolsListChanged\":true",
					"localization subscription acknowledgement");

			for (int wave = 0; wave < revisionWaves; wave++) {
				String revision = "%s-r%04d".formatted(runId, wave + 1);
				state.installRevision(revision);
				server.getLocalizationControl().catalogsChanged();
				state.invalidationsRequested.incrementAndGet();
				String invalidation = awaitItem(subscription);
				assertContains(invalidation,
						"notifications/tools/list_changed",
						"localized-tools invalidation");
				state.invalidationsDelivered.incrementAndGet();

				List<Callable<Void>> tasks = new ArrayList<>(concurrentClients);
				for (int client = 0; client < concurrentClients; client++) {
					String requestId = "%s-%04d-%04d".formatted(runId,
							wave, client);
					tasks.add(() -> {
						performLocalizedToolsList(simulator, state, requestId,
								revision);
						return null;
					});
				}

				long remaining = deadline - System.nanoTime();
				Assertions.assertTrue(remaining > 0L,
						"Localization soak exceeded its run timeout.");
				List<Future<Void>> futures = executor.invokeAll(tasks, remaining,
						TimeUnit.NANOSECONDS);
				for (Future<Void> future : futures) {
					Assertions.assertFalse(future.isCancelled(),
							"Localization render wave exceeded its run timeout.");
					try {
						future.get();
					} catch (ExecutionException e) {
						throw new AssertionError(
								"Concurrent localization render failed.", e.getCause());
					}
				}
			}

			subscription.cancel();
			Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
					awaitCompletion(subscription).getReason());
			Assertions.assertTrue(subscription.nextStreamItem(Duration.ZERO)
					.isEmpty());
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(
					PROFILE.settleTimeout().toMillis(), TimeUnit.MILLISECONDS),
					"Localization soak workers did not terminate.");
		}
	}

	private static void performLocalizedToolsList(@NonNull Simulator simulator,
			@NonNull LocalizationState state, @NonNull String requestId,
			@NonNull String revision) throws InterruptedException {
		try (McpSimulation simulation = simulator.startMcpRequest(
				localizationRequest(requestId, "tools/list", ""))) {
			McpSimulationResponse response = awaitResponse(simulation);
			Assertions.assertEquals(200, response.getStatusCode());
			Assertions.assertEquals(McpSimulationBodyMode.JSON,
					response.getBodyMode());
			String body = new String(response.getBody().orElseThrow(),
					StandardCharsets.UTF_8);
			assertContains(body,
					"\"title\":\"FR[" + revision + "]:" + TOOL_TITLE + "\"",
					"localized tools/list title");
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitCompletion(simulation).getReason());
			Assertions.assertTrue(simulation.nextStreamItem(Duration.ZERO).isEmpty());
			state.localizedCatalogResponses.incrementAndGet();
		}
	}

	@NonNull
	private static McpServer server(@NonNull LocalizationState state) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("unused"))
				.title(TOOL_TITLE)
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(URI.create("soak://localization/resource"),
						"localization-soak-resource")
				.handler((request, read, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
								.content(McpTextResourceContents.withUriAndText(
										read.getUri(), "unused").build())
								.build()))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"soklet-mcp-localization-soak", "3.6.0-SNAPSHOT")
						.title(SERVER_TITLE)
						.build())
				.tool(tool)
				.resource(resource)
				.subscriptions(McpSubscriptionConfig
						.withEventPublisher(
								McpLocalSubscriptionEventPublisher.fromDefaults())
						.notificationTypes(Set.of(
								McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
						.build())
				.build();

		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.requestHandlerConcurrency(PROFILE.requestHandlerConcurrency())
				.requestHandlerQueueCapacity(PROFILE.requestHandlerQueueCapacity())
				.streamQueueCapacity(PROFILE.streamQueueCapacity())
				.keepAliveInterval(PROFILE.keepAliveInterval())
				.requestTimeout(PROFILE.requestTimeout())
				.writeTimeout(PROFILE.writeTimeout())
				.maximumSubscriptionsPerPrincipal(
						PROFILE.maximumSubscriptionsPerPrincipal())
				.maximumSubscriptionDuration(
						PROFILE.maximumSubscriptionDuration())
				.shutdownTimeout(PROFILE.shutdownTimeout())
				.localizer(state.localizer())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	@NonNull
	private static Request localizationRequest(@NonNull String id,
			@NonNull String method, @NonNull String additionalParameters) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + jsonString(id)
				+ ",\"method\":" + jsonString(method) + ",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParameters + "}}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Content-Type",
						Set.of("application/json; charset=UTF-8"),
						"Accept",
						Set.of("application/json, text/event-stream"),
						"Accept-Language", Set.of("fr-CA, en;q=0.8"),
						"MCP-Protocol-Version", Set.of(PROTOCOL_VERSION),
						"Mcp-Method", Set.of(method)))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	private static McpSimulationResponse awaitResponse(
			@NonNull McpSimulation simulation) throws InterruptedException {
		return simulation.awaitResponse(PROFILE.settleTimeout())
				.orElseThrow(() -> new AssertionError(
						"Timed out awaiting localization soak response."));
	}

	@NonNull
	private static String awaitItem(@NonNull McpSimulation simulation)
			throws InterruptedException {
		return new String(simulation.nextStreamItem(PROFILE.settleTimeout())
				.orElseThrow(() -> new AssertionError(
						"Timed out awaiting localization soak stream item."))
				.getEncodedBytes(), StandardCharsets.UTF_8);
	}

	@NonNull
	private static McpSimulationCompletion awaitCompletion(
			@NonNull McpSimulation simulation) throws InterruptedException {
		return simulation.awaitCompletion(PROFILE.settleTimeout())
				.orElseThrow(() -> new AssertionError(
						"Timed out awaiting localization soak completion."));
	}

	private static void assertStoppedAndDrained(@NonNull McpServer server) {
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.STOPPED, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isEmpty());
		Assertions.assertEquals(0, diagnostics.getActiveHandlerExecutions());
		Assertions.assertEquals(0, diagnostics.getQueuedRequests());
		Assertions.assertEquals(0, diagnostics.getActiveRequestStreams());
		Assertions.assertEquals(0, diagnostics.getActiveSubscriptions());
	}

	private static void assertContains(@NonNull String actual,
			@NonNull String expected, @NonNull String description) {
		Assertions.assertTrue(actual.contains(expected),
				() -> "Missing " + description + " '" + expected + "' in "
						+ actual);
	}

	@NonNull
	private static String jsonString(@NonNull String value) {
		return '"' + value.replace("\\", "\\\\")
				.replace("\"", "\\\"") + '"';
	}

	@ThreadSafe
	private record LocalizationSoakProfile(int concurrentClients,
			int cyclesPerClient,
			@NonNull Duration keepAliveInterval,
			int maximumSubscriptionsPerPrincipal,
			@NonNull Duration maximumSubscriptionDuration,
			int requestHandlerConcurrency,
			int requestHandlerQueueCapacity,
			@NonNull Duration requestTimeout,
			SoakResourceSnapshot.@NonNull ResourceTolerance resourceTolerance,
			@NonNull Duration runTimeout,
			@NonNull Duration settleTimeout,
			@NonNull Duration shutdownTimeout,
			int streamQueueCapacity,
			@NonNull Duration writeTimeout) {
		@NonNull
		private static LocalizationSoakProfile fromSelectedProfile() {
			SoakProfiles.SelectedProfile profile = SoakProfiles.selected();
			return new LocalizationSoakProfile(
					profile.integer("mcp.concurrentClients"),
					profile.integer("mcp.cyclesPerClient"),
					profile.durationMillis("mcp.keepAliveIntervalMillis"),
					profile.integer("mcp.maximumSubscriptionsPerPrincipal"),
					profile.durationMillis(
							"mcp.maximumSubscriptionDurationMillis"),
					profile.integer("mcp.requestHandlerConcurrency"),
					profile.integer("mcp.requestHandlerQueueCapacity"),
					profile.durationMillis("mcp.requestTimeoutMillis"),
					new SoakResourceSnapshot.ResourceTolerance(
							profile.number(
									"mcp.resourceTolerance.maxOpenFileDescriptorGrowth"),
							profile.number(
									"mcp.resourceTolerance.maxHeapGrowthBytes"),
							profile.integer(
									"mcp.resourceTolerance.maxLiveThreadGrowth")),
					profile.durationMillis("mcp.runTimeoutMillis"),
					profile.durationMillis("mcp.settleTimeoutMillis"),
					profile.durationMillis("mcp.shutdownTimeoutMillis"),
					profile.integer("mcp.streamQueueCapacity"),
					profile.durationMillis("mcp.writeTimeoutMillis"));
		}
	}

	private record LocalizationCounts(int contextsCreated,
			int localizationLookups, int boundedPreferenceMatches,
			int localizedCatalogResponses, int invalidationsRequested,
			int invalidationsDelivered) {
		@NonNull
		private LocalizationCounts minus(@NonNull LocalizationCounts baseline) {
			requireNonNull(baseline);
			return new LocalizationCounts(
					this.contextsCreated - baseline.contextsCreated,
					this.localizationLookups - baseline.localizationLookups,
					this.boundedPreferenceMatches
							- baseline.boundedPreferenceMatches,
					this.localizedCatalogResponses
							- baseline.localizedCatalogResponses,
					this.invalidationsRequested - baseline.invalidationsRequested,
					this.invalidationsDelivered - baseline.invalidationsDelivered);
		}
	}

	@ThreadSafe
	private static final class LocalizationState {
		@NonNull
		private final AtomicReference<McpLocalizationRevision> revision =
				new AtomicReference<>(McpLocalizationRevision.fromValue("initial"));
		@NonNull
		private final AtomicInteger contextsCreated = new AtomicInteger();
		@NonNull
		private final AtomicInteger localizationLookups = new AtomicInteger();
		@NonNull
		private final AtomicInteger boundedPreferenceMatches =
				new AtomicInteger();
		@NonNull
		private final AtomicInteger localizedCatalogResponses =
				new AtomicInteger();
		@NonNull
		private final AtomicInteger invalidationsRequested = new AtomicInteger();
		@NonNull
		private final AtomicInteger invalidationsDelivered = new AtomicInteger();

		private void installRevision(@NonNull String value) {
			this.revision.set(McpLocalizationRevision.fromValue(value));
		}

		@NonNull
		private McpLocalizer localizer() {
			return McpLocalizer.withFallbackLocale(Locale.ENGLISH)
					.contextProvider(request -> {
						McpLocalizationRevision captured = this.revision.get();
						this.contextsCreated.incrementAndGet();
						if (request.getLanguageRanges().stream()
								.anyMatch(range -> "fr-ca".equals(range.getRange())))
							this.boundedPreferenceMatches.incrementAndGet();
						return McpLocalizationContext
								.withLocale(LOCALIZED_LOCALE)
								.revision(captured)
								.localizer(text -> {
									localizationLookups.incrementAndGet();
									return McpLocalizationResult.localized(
											"FR[" + captured.getValue() + "]:"
													+ text.getDefaultText());
								})
								.build();
					})
					.build();
		}

		@NonNull
		private LocalizationCounts snapshot() {
			return new LocalizationCounts(this.contextsCreated.get(),
					this.localizationLookups.get(),
					this.boundedPreferenceMatches.get(),
					this.localizedCatalogResponses.get(),
					this.invalidationsRequested.get(),
					this.invalidationsDelivered.get());
		}
	}
}
