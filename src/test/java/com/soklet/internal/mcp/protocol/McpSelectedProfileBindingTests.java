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

package com.soklet.internal.mcp.protocol;

import com.soklet.CorsAuthorizer;
import com.soklet.HttpMethod;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationOptions;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

public class McpSelectedProfileBindingTests {
	static final String CURRENT = "2026-07-28";
	static final String FAKE = "2099-01-01";
	static final String RESULT_MARKER = "com.example/profileResult";
	static final String NOTIFICATION_MARKER =
			"com.example/profileNotification";
	private static final String FAKE_NOTIFICATION_METADATA_KEY =
			"io.modelcontextprotocol/fake/reserved";
	private static final int PREFLIGHT_OUTPUT_LIMIT = 64 * 1_024;

	@Test
	public void everyOwnedRenderingKindIsExplicitAndProgressRetainsTheExactProfile()
			throws Exception {
		TrackingProfile fake = new TrackingProfile();
		McpWireResult canonicalResult = McpWireResult.complete(McpJsonObject.empty());
		for (McpProfileFrameworkResultKind kind
				: McpProfileFrameworkResultKind.values())
			Assertions.assertEquals(kind.name(), marker(
					fake.renderFrameworkResult(kind, canonicalResult).toJsonObject(),
					RESULT_MARKER));

		McpJsonRpcMessage.Notification canonicalNotification =
				new McpJsonRpcMessage.Notification("notifications/test",
						Optional.empty(), McpJsonObject.empty());
		for (McpProfileFrameworkNotificationKind kind
				: McpProfileFrameworkNotificationKind.values())
			Assertions.assertEquals(kind.name(), marker(fake
					.renderFrameworkNotification(kind, canonicalNotification)
					.extensionFields(), NOTIFICATION_MARKER));

		McpJsonRpcError canonicalError = new McpJsonRpcError(
				McpJsonRpcError.INTERNAL_ERROR, "Internal error", Optional.empty());
		for (McpProfileErrorKind kind : McpProfileErrorKind.values())
			Assertions.assertTrue(fake.renderFrameworkError(kind, canonicalError)
					.message().endsWith("[" + kind.name() + "]"));

		AtomicReference<McpJsonRpcMessage.Notification> progress =
				new AtomicReference<>();
		McpApplicationInvocation invocation = invocation(fake, notification -> {
			progress.set(notification);
			return true;
		});
		McpServerRuntimeBridge.ProgressEmitter emitter = McpServerRuntimeBridge
				.progressEmitterFor(invocation, McpInputRequestPlan.empty())
				.orElseThrow();
		Assertions.assertTrue(emitter.emit(0.5d, Optional.of(1.0d),
				Optional.of("half")));
		Assertions.assertSame(fake, invocation.protocolProfile());
		Assertions.assertEquals("PROGRESS", marker(
				progress.get().extensionFields(), NOTIFICATION_MARKER));
		Assertions.assertEquals(EnumSet.allOf(McpProfileFrameworkResultKind.class),
				fake.resultKinds());
		Assertions.assertEquals(
				EnumSet.allOf(McpProfileFrameworkNotificationKind.class),
				fake.notificationKinds());
		Assertions.assertEquals(EnumSet.allOf(McpProfileErrorKind.class),
				fake.errorKinds());
	}

	@Test
	public void protectedStateBindingRequiresMappedRevisionToMatchSelectedProfile() {
		TrackingProfile fake = new TrackingProfile();
		McpApplicationInvocation matching = invocation(fake, notification -> true);
		Assertions.assertEquals(FAKE,
				McpServerRuntimeBridge.selectedProtocolRevision(matching));

		McpApplicationInvocation mismatched = invocation(fake, CURRENT,
				notification -> true);
		IllegalStateException failure = Assertions.assertThrows(
				IllegalStateException.class,
				() -> McpServerRuntimeBridge.selectedProtocolRevision(mismatched));
		Assertions.assertEquals(
				"The selected MCP profile and mapped request revision disagree.",
				failure.getMessage());
	}

	@Test
	public void everyInstalledProfileStaticResultMustPassItsOwnOutputPreflight() {
		TrackingProfile fake = new TrackingProfile(
				McpProfileFrameworkResultKind.TOOLS_LIST);
		McpProtocolProfileRegistry profiles = new McpProtocolProfileRegistry(
				List.of(Mcp20260728ProtocolProfile.INSTANCE, fake));
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"selected-profile-preflight-test", "4.0.0-SNAPSHOT"))
				.tool(McpNormalizedOperation.named("lookup"))
				.build();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				McpProtocolAdmissionController.acceptAllInstance());
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy, endpoint,
				McpApplicationRequestRouter.empty());

		IllegalArgumentException failure = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> runtime(binding, profiles, preflightLimits()));
		Assertions.assertTrue(failure.getMessage().contains("'tools/list'"),
				failure.getMessage());
		Assertions.assertTrue(failure.getMessage().contains("profile '" + FAKE + "'"),
				failure.getMessage());
		Assertions.assertTrue(failure.getMessage().contains(
				"maximum UTF-8 bytes: " + PREFLIGHT_OUTPUT_LIMIT),
				failure.getMessage());
	}

	@Test
	public void selectedFakeProfileOwnsRuntimeMappingStaticResultsAndErrorsWhileBootstrapStaysCommon()
			throws Exception {
		TrackingProfile fake = new TrackingProfile();
		AtomicInteger fakeNotificationAdmissions = new AtomicInteger();
		AtomicReference<McpProtocolProfile> handlerProfile = new AtomicReference<>();
		RecordingObservationSink observations = new RecordingObservationSink();
		McpProtocolProfileRegistry profiles = new McpProtocolProfileRegistry(
				List.of(Mcp20260728ProtocolProfile.INSTANCE, fake));
		McpNormalizedEndpoint endpoint = fullEndpoint();
		McpHttpEndpointPolicy policy = new McpHttpEndpointPolicy("/mcp", Set.of(),
				McpAbsentOriginPolicy.ALLOW, CorsAuthorizer.rejectAllInstance(),
				context -> {
					if (context.notification() && context.requestMetadata()
							.map(metadata -> metadata.members().containsKey(
									FAKE_NOTIFICATION_METADATA_KEY))
							.orElse(false))
						fakeNotificationAdmissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				}).withRequestRateLimiter(context -> context.request().getHeader(
					"X-Profile-Control").isPresent()
					? McpRateLimitDecision.denied(Duration.ofSeconds(1))
					: McpRateLimitDecision.allowed());
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromHandlers(
				Map.of(
						"custom/profile", invocation -> {
							handlerProfile.set(invocation.protocolProfile());
							return McpWireResult.complete(new McpJsonObject(Map.of(
									"exactProfile", McpJsonBoolean.TRUE)));
						},
						"custom/application-error", invocation -> {
							throw new McpApplicationJsonRpcException(new McpJsonRpcError(
									1_001, "Application-owned", Optional.empty()));
						},
						"custom/oversized", invocation -> McpWireResult.complete(
								new McpJsonObject(Map.of("oversized",
										new McpJsonString("x".repeat(1_100_000)))))));
		McpSubscriptionEventSource source = new McpSubscriptionEventSource(
				new Object(), listener -> () -> {});
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy, endpoint,
				router, observations,
				Optional.of(source));

		try (McpHttpServerRuntime runtime = runtime(binding, profiles)) {
			Assertions.assertEquals(EnumSet.allOf(McpProfileFrameworkResultKind.class),
					fake.resultKinds(),
					"Construction must render and preflight every reachable result kind.");
			int port = runtime.start().getPort();

			String discovery = request(port, FAKE, "server/discover", "", List.of(),
					"");
			Assertions.assertTrue(discovery.contains(
					"\"" + RESULT_MARKER + "\":\"DISCOVERY\""), discovery);
			Assertions.assertEquals(1, fake.mappingCount());

			for (Map.Entry<String, McpProfileFrameworkResultKind> entry : Map.of(
					"tools/list", McpProfileFrameworkResultKind.TOOLS_LIST,
					"prompts/list", McpProfileFrameworkResultKind.PROMPTS_LIST,
					"resources/list", McpProfileFrameworkResultKind.RESOURCES_LIST,
					"resources/templates/list",
					McpProfileFrameworkResultKind.RESOURCE_TEMPLATES_LIST).entrySet()) {
				String list = request(port, FAKE, entry.getKey(), "", List.of(), "");
				Assertions.assertTrue(list.contains("\"" + RESULT_MARKER + "\":\""
						+ entry.getValue().name() + "\""), list);
			}
			Assertions.assertTrue(fake.applicationResultKinds().isEmpty(),
					"Framework-owned static lists must bypass application-result rendering.");

			String applicationResult = request(port, FAKE, "custom/profile", "",
					List.of(), "");
			Assertions.assertSame(fake, handlerProfile.get(),
					"The HTTP-selected profile instance must reach the handler unchanged.");
			Assertions.assertTrue(applicationResult.contains("\"exactProfile\":true"),
					applicationResult);
			Assertions.assertFalse(applicationResult.contains(RESULT_MARKER),
					"MCP-2B must not transform application-owned result payloads.");

			String mapperError = request(port, FAKE, "server/discover", "", List.of(),
					",\"com.example/failMapper\":true");
			Assertions.assertTrue(mapperError.contains("[REQUEST_MAPPER]"), mapperError);

			String operationError = request(port, FAKE, "server/discover",
					",\"unexpected\":true", List.of(), "");
			String productionOperationError = request(port, CURRENT,
					"server/discover", ",\"unexpected\":true", List.of(), "");
			Assertions.assertTrue(productionOperationError.contains("\"code\":-32602"),
					productionOperationError);
			Assertions.assertTrue(operationError.contains("\"code\":-32602"),
					operationError);
			Assertions.assertFalse(productionOperationError.contains("[OPERATION]"),
					productionOperationError);
			Assertions.assertTrue(operationError.contains("[OPERATION]"), operationError);

			int controlRenders = fake.errorRenderCount(McpProfileErrorKind.CONTROL);
			String controlError = request(port, FAKE, "server/discover", "",
					List.of(new McpChunkedHttpClient.RequestHeader(
							"X-Profile-Control", "yes")), "");
			Assertions.assertTrue(controlError.contains("[CONTROL]"), controlError);
			McpJsonRpcError observedControlError = observations.awaitError();
			Assertions.assertSame(fake.lastRenderedError(McpProfileErrorKind.CONTROL),
					observedControlError,
					"Observation and wire encoding must share one rendered error object.");
			Assertions.assertEquals(controlRenders + 1,
					fake.errorRenderCount(McpProfileErrorKind.CONTROL),
					"An observed framework error must be rendered exactly once.");
			Assertions.assertEquals("Rate limited [CONTROL]",
					observedControlError.message());

			String unsupported = request(port, "2099-12-31", "server/discover", "",
					List.of(), "");
			Assertions.assertTrue(unsupported.contains("\"code\":-32022"), unsupported);
			Assertions.assertFalse(unsupported.contains("[REQUEST_MAPPER]"), unsupported);
			Assertions.assertFalse(unsupported.contains("[OPERATION]"), unsupported);
			Assertions.assertFalse(unsupported.contains("[CONTROL]"), unsupported);

			int mappingsBeforeCommonBootstrap = fake.mappingCount();
			String malformed = rawRequest(port, FAKE, "server/discover", "{");
			Assertions.assertTrue(malformed.contains("\"code\":-32700"), malformed);
			assertNoProfileMarker(malformed);
			Assertions.assertEquals(mappingsBeforeCommonBootstrap,
					fake.mappingCount(),
					"JSON decoding must fail before profile-owned request mapping.");

			String mirroredMethodMismatch = rawRequest(port, FAKE, "tools/list",
					"{\"jsonrpc\":\"2.0\",\"id\":\"mirrored\","
							+ "\"method\":\"server/discover\",\"params\":{\"_meta\":{"
							+ "\"io.modelcontextprotocol/protocolVersion\":\"" + FAKE
							+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}");
			Assertions.assertTrue(mirroredMethodMismatch.contains("\"code\":-32020"),
					mirroredMethodMismatch);
			assertNoProfileMarker(mirroredMethodMismatch);
			Assertions.assertEquals(mappingsBeforeCommonBootstrap,
					fake.mappingCount(),
					"Mirrored-header agreement must be checked before profile mapping.");

			String mismatch = request(port, FAKE, "server/discover", "", List.of(),
					",\"com.example/bodyMismatch\":true");
			Assertions.assertTrue(mismatch.contains("\"code\":-32020"), mismatch);
			Assertions.assertFalse(mismatch.contains("["), mismatch);

			String applicationError = request(port, FAKE,
					"custom/application-error", "", List.of(), "");
			Assertions.assertTrue(applicationError.contains("Application-owned"),
					applicationError);
			Assertions.assertFalse(applicationError.contains("[OPERATION]"),
					applicationError);
			Assertions.assertTrue(fake.applicationResultKinds().isEmpty(),
					"Application-owned errors must bypass application-result rendering.");

			String renderFallback = request(port, FAKE, "custom/oversized", "",
					List.of(), "");
			Assertions.assertTrue(renderFallback.contains("[CONTROL]"),
					renderFallback);

			String notification = "{\"jsonrpc\":\"2.0\","
					+ "\"method\":\"notifications/test\",\"params\":{\"_meta\":{"
					+ "\"" + FAKE_NOTIFICATION_METADATA_KEY + "\":true}}}";
			Assertions.assertEquals(400, notificationStatus(port, CURRENT, notification));
			Assertions.assertEquals(0, fakeNotificationAdmissions.get(),
					"Production metadata validation must reject before admission.");
			Assertions.assertEquals(400, notificationStatus(port, FAKE, notification));
			Assertions.assertEquals(1, fakeNotificationAdmissions.get());
			Assertions.assertTrue(fake.notificationValidationCount() > 0);
			Assertions.assertTrue(fake.errorKinds().containsAll(
					EnumSet.allOf(McpProfileErrorKind.class)));
		}
	}

	@Test
	@Timeout(120)
	public void subscriptionAndSimulationRetainTheSelectedProfileForTheirWholeLifetime()
			throws Exception {
		TrackingProfile fake = new TrackingProfile();
		McpProtocolProfileRegistry profiles = new McpProtocolProfileRegistry(
				List.of(Mcp20260728ProtocolProfile.INSTANCE, fake));
		TestEventSource events = new TestEventSource();
		McpHttpEndpointPolicy policy = new McpHttpEndpointPolicy("/mcp", Set.of(),
				McpAbsentOriginPolicy.ALLOW, CorsAuthorizer.rejectAllInstance(),
				McpProtocolAdmissionController.acceptAllInstance());
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy,
				fullEndpoint(), McpApplicationRequestRouter.empty(),
				McpRuntimeObservationSink.disabledInstance(),
				Optional.of(events.source()));

		try (McpHttpServerRuntime runtime = runtime(binding, profiles)) {
			int port = runtime.start().getPort();
			McpChunkedHttpClient subscription = listen(port, FAKE, "retained");
			Thread stopThread = null;
			try {
				Assertions.assertEquals(200, subscription.readHead().status());
				String acknowledgment = subscription.readChunkText();
				Assertions.assertTrue(acknowledgment.contains(
						"\"" + NOTIFICATION_MARKER
								+ "\":\"SUBSCRIPTION_ACKNOWLEDGEMENT\""),
						acknowledgment);
				events.publish(new McpSubscriptionEventSource.Event
						.ResourcesListChanged());
				String event = subscription.readChunkText();
				Assertions.assertTrue(event.contains("\"" + NOTIFICATION_MARKER
						+ "\":\"SUBSCRIPTION_EVENT\""), event);

				stopThread = new Thread(runtime::stop,
						"mcp-selected-profile-subscription-stop");
				stopThread.start();
				String terminal = subscription.readChunkText();
				Assertions.assertTrue(terminal.contains("\"" + RESULT_MARKER
						+ "\":\"SUBSCRIPTION_TERMINAL\""), terminal);
				Assertions.assertNull(subscription.readChunk());
				stopThread.join(5_000L);
				Assertions.assertFalse(stopThread.isAlive());
			} finally {
				subscription.close();
				if (stopThread != null)
					stopThread.join(5_000L);
				runtime.stop();
			}

			try (McpHttpServerRuntime.SimulationSession session =
					runtime.openSimulationSession();
				McpSimulation simulation = session.start(
						simulationDiscoveryRequest(FAKE),
						McpSimulationOptions.builder().build())) {
				String body = new String(simulation.awaitResponse(
						Duration.ofSeconds(5)).orElseThrow().getBody().orElseThrow(),
						StandardCharsets.UTF_8);
				Assertions.assertTrue(body.contains("\"" + RESULT_MARKER
						+ "\":\"DISCOVERY\""), body);
				Assertions.assertTrue(simulation.awaitCompletion(
						Duration.ofSeconds(5)).isPresent());
			}
		}
		Assertions.assertTrue(fake.notificationKinds().contains(
				McpProfileFrameworkNotificationKind.SUBSCRIPTION_ACKNOWLEDGEMENT));
		Assertions.assertTrue(fake.notificationKinds().contains(
				McpProfileFrameworkNotificationKind.SUBSCRIPTION_EVENT));
		Assertions.assertTrue(fake.resultKinds().contains(
				McpProfileFrameworkResultKind.SUBSCRIPTION_TERMINAL));
		Assertions.assertTrue(fake.applicationResultKinds().isEmpty(),
				"Subscription control and terminal results must bypass application-result rendering.");
	}

	@Test
	public void catalogLengthCacheIsProfileAwareAndLocalizationConsumesRenderedBytes()
			throws Exception {
		TrackingProfile fake = new TrackingProfile();
		RecordingLocalizer localizer = new RecordingLocalizer();
		McpProtocolProfileRegistry profiles = new McpProtocolProfileRegistry(
				List.of(Mcp20260728ProtocolProfile.INSTANCE, fake));
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"profile-localization-test", "4.0.0-SNAPSHOT"))
				.build();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				McpProtocolAdmissionController.acceptAllInstance())
				.withCatalogLocalizer(localizer);
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy, endpoint,
				McpApplicationRequestRouter.empty(), observationWithPublicContext(),
				Optional.empty());

		try (McpHttpServerRuntime runtime = runtime(binding, profiles)) {
			int port = runtime.start().getPort();
			request(port, CURRENT, "server/discover", "", List.of(), "");
			request(port, FAKE, "server/discover", "", List.of(), "");
			request(port, CURRENT, "server/discover", "", List.of(), "");
			request(port, FAKE, "server/discover", "", List.of(), "");
		}

		Assertions.assertEquals(4, localizer.inputs().size());
		Set<Long> lengths = new java.util.LinkedHashSet<>();
		for (McpRuntimeCatalogLocalizer.Input input : localizer.inputs()) {
			long actual = input.encodedLength().applyAsLong(input.canonicalDocument());
			Assertions.assertEquals(actual, input.canonicalEncodedBytes(),
					"A cached canonical length must belong to the selected profile.");
			lengths.add(actual);
		}
		Assertions.assertEquals(2, lengths.size(),
				"The fake profile deliberately changes the canonical document length.");
		Assertions.assertTrue(localizer.inputs().stream().anyMatch(input ->
				input.canonicalDocument().members().containsKey(RESULT_MARKER)));
	}

	static McpHttpServerRuntime runtime(@NonNull McpHttpEndpointBinding binding,
			@NonNull McpProtocolProfileRegistry profiles) {
		return runtime(binding, profiles, McpJsonLimits.productionDefaults());
	}

	static McpHttpServerRuntime runtime(@NonNull McpHttpEndpointBinding binding,
			@NonNull McpProtocolProfileRegistry profiles,
			@NonNull McpJsonLimits jsonLimits) {
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), List.of(binding),
				jsonLimits,
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(), ignored -> {},
				ignored -> {}, Optional.empty(),
				McpFrameworkRequestStateRuntime.disabledInstance(),
				McpSubscriptionRuntimeConfiguration.productionDefaults(),
				McpApplicationExecutionObserver.disabledInstance(), profiles);
	}

	private static McpJsonLimits preflightLimits() {
		McpJsonLimits production = McpJsonLimits.productionDefaults();
		return new McpJsonLimits(production.maximumInputBytes(),
				production.maximumNestingDepth(),
				production.maximumTokenLengthInCharacters(),
				production.maximumStringLengthInCharacters(),
				production.maximumNumberLengthInCharacters(),
				production.maximumExponentMagnitude(), production.maximumNodeCount(),
				PREFLIGHT_OUTPUT_LIMIT);
	}

	private static McpNormalizedEndpoint fullEndpoint() {
		return McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"selected-profile-binding-test", "4.0.0-SNAPSHOT"))
				.tool(McpNormalizedOperation.named("lookup"))
				.prompt(McpNormalizedOperation.named("summarize"))
				.exactResource("test://profile/resource")
				.resourceTemplate("test://profile/{id}")
				.subscriptions(McpNormalizedSubscriptionConfiguration.supporting(
						McpResourceNotificationType.RESOURCES_LIST_CHANGED))
				.build();
	}

	private static String request(int port, @NonNull String revision,
			@NonNull String method, @NonNull String additionalParams,
			@NonNull List<McpChunkedHttpClient.@NonNull RequestHeader> extraHeaders,
			@NonNull String envelopeExtensions) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"profile\","
				+ "\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + revision
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParams + "}" + envelopeExtensions + "}";
		List<McpChunkedHttpClient.RequestHeader> headers = new java.util.ArrayList<>();
		headers.add(new McpChunkedHttpClient.RequestHeader(
				"MCP-Protocol-Version", revision));
		headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Method", method));
		headers.addAll(extraHeaders);
		try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(
				port, body, List.copyOf(headers))) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			return client.readFixedBody(head);
		}
	}

	private static int notificationStatus(int port, @NonNull String revision,
			@NonNull String notification) throws Exception {
		try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(
				port, notification, List.of(
						new McpChunkedHttpClient.RequestHeader(
								"MCP-Protocol-Version", revision),
						new McpChunkedHttpClient.RequestHeader(
								"Mcp-Method", "notifications/test")))) {
			return client.readHead().status();
		}
	}

	private static String rawRequest(int port, @NonNull String revision,
			@NonNull String mirroredMethod, @NonNull String body) throws Exception {
		try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(
				port, body, List.of(
						new McpChunkedHttpClient.RequestHeader(
								"MCP-Protocol-Version", revision),
						new McpChunkedHttpClient.RequestHeader(
								"Mcp-Method", mirroredMethod)))) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			return client.readFixedBody(head);
		}
	}

	private static McpApplicationInvocation invocation(
			@NonNull McpProtocolProfile profile,
			@NonNull McpApplicationNotificationWriter notificationWriter) {
		return invocation(profile, profile.revision(), notificationWriter);
	}

	private static McpApplicationInvocation invocation(
			@NonNull McpProtocolProfile profile,
			@NonNull String mappedRevision,
			@NonNull McpApplicationNotificationWriter notificationWriter) {
		McpRequestMetadata metadata = new McpRequestMetadata(mappedRevision,
				McpClientCapabilities.empty(), Optional.empty(), Optional.empty(),
				Optional.of(new McpProgressToken.StringToken("progress")),
				McpJsonObject.empty());
		McpJsonRpcMessage.Request request = new McpJsonRpcMessage.Request(
				new McpJsonRpcId.StringId("progress"), "tools/call",
				new McpRequestParameters(metadata, McpJsonObject.empty()),
				McpJsonObject.empty());
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"profile-progress-test", "4.0.0-SNAPSHOT"))
				.build();
		McpEffectiveAdmissionIdentity identity = McpEffectiveAdmissionIdentity
				.resolve(endpoint, "/mcp", McpAdmissionIdentity.anonymousInstance());
		return new McpApplicationInvocation(null, null, request, profile, identity,
				new McpApplicationCancellationState(), notificationWriter, () -> {});
	}

	static McpChunkedHttpClient listen(int port, String revision,
			String id) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + revision
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"resourcesListChanged\":true}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", revision),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen")));
	}

	static Request simulationDiscoveryRequest(String revision) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"simulation\","
				+ "\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + revision
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		return Request.withPath(HttpMethod.POST, "/mcp")
				.headers(Map.of(
						"Host", Set.of("127.0.0.1:0"),
						"Content-Type", Set.of("application/json; charset=UTF-8"),
						"Accept", Set.of("application/json, text/event-stream"),
						"MCP-Protocol-Version", Set.of(revision),
						"Mcp-Method", Set.of("server/discover")))
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	static McpRuntimeObservationSink observationWithPublicContext() {
		McpRequestContext context = (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class}, (proxy, method, arguments) -> {
					if (method.getReturnType() == Optional.class)
						return Optional.empty();
					if (method.getReturnType() == Map.class)
						return Map.of();
					if (method.getReturnType() == String.class)
						return "profile-localization-test";
					if (method.getReturnType() == boolean.class)
						return false;
					return null;
				});
		return ignored -> new McpRuntimeRequestObservation() {
			@Override
			public @NonNull Optional<@NonNull McpRequestContext> publicContext() {
				return Optional.of(context);
			}

			@Override
			public void didFinish(@NonNull McpRequestOutcome outcome,
					McpJsonRpcError error, @NonNull Duration duration,
					@NonNull List<@NonNull Throwable> throwables) {
			}
		};
	}

	static final class RecordingLocalizer
			implements McpRuntimeCatalogLocalizer {
		private final List<Input> inputs = new java.util.concurrent.CopyOnWriteArrayList<>();

		@Override
		public @NonNull Outcome localizeCatalog(@NonNull Input input) {
			inputs.add(input);
			return Outcome.canonical(input.canonicalDocument());
		}

		@Override
		public @NonNull Set<@NonNull ResponseKind> localizedResponseKinds() {
			return Set.of(ResponseKind.DISCOVERY);
		}

		List<Input> inputs() {
			return List.copyOf(inputs);
		}
	}

	static final class TestEventSource {
		private final AtomicReference<McpSubscriptionEventSource.Listener> listener =
				new AtomicReference<>();

		McpSubscriptionEventSource source() {
			return new McpSubscriptionEventSource(this, next -> {
				listener.set(next);
				return () -> listener.compareAndSet(next, null);
			});
		}

		void publish(McpSubscriptionEventSource.Event event) {
			requireNonNull(listener.get()).onEvent(requireNonNull(event));
		}
	}

	private static final class RecordingObservationSink
			implements McpRuntimeObservationSink {
		private final BlockingQueue<McpJsonRpcError> errors =
				new LinkedBlockingQueue<>();

		@Override
		public @NonNull McpRuntimeRequestObservation didStartRequest(
				@NonNull McpRuntimeRequestInput input) {
			boolean recordError = input.request().getHeader(
					"X-Profile-Control").isPresent();
			return new McpRuntimeRequestObservation() {
				@Override
				public @NonNull Optional<@NonNull McpRequestContext> publicContext() {
					return Optional.empty();
				}

				@Override
				public void didFinish(@NonNull McpRequestOutcome outcome,
						McpJsonRpcError error, @NonNull Duration duration,
						@NonNull List<@NonNull Throwable> throwables) {
					if (recordError && error != null)
						errors.add(error);
				}
			};
		}

		private McpJsonRpcError awaitError() throws InterruptedException {
			return requireNonNull(errors.poll(5, TimeUnit.SECONDS),
					"The request observation did not finish with an error.");
		}
	}

	private static String marker(@NonNull McpJsonObject object,
			@NonNull String name) {
		return ((McpJsonString) object.members().get(name)).value();
	}

	private static void assertNoProfileMarker(@NonNull String body) {
		Assertions.assertFalse(body.contains("[REQUEST_MAPPER]"), body);
		Assertions.assertFalse(body.contains("[OPERATION]"), body);
		Assertions.assertFalse(body.contains("[CONTROL]"), body);
		Assertions.assertFalse(body.contains(RESULT_MARKER), body);
	}

	static final class TrackingProfile implements McpProtocolProfile {
		private final EnumSet<McpProfileFrameworkResultKind> resultKinds =
				EnumSet.noneOf(McpProfileFrameworkResultKind.class);
		private final EnumSet<McpProfileFrameworkNotificationKind> notificationKinds =
				EnumSet.noneOf(McpProfileFrameworkNotificationKind.class);
		private final EnumSet<McpProfileErrorKind> errorKinds =
				EnumSet.noneOf(McpProfileErrorKind.class);
		private final EnumSet<McpProfileApplicationResultKind> applicationResultKinds =
				EnumSet.noneOf(McpProfileApplicationResultKind.class);
		private final EnumMap<McpProfileErrorKind, Integer> errorRenderCounts =
				new EnumMap<>(McpProfileErrorKind.class);
		private final EnumMap<McpProfileErrorKind, McpJsonRpcError>
				lastRenderedErrors = new EnumMap<>(McpProfileErrorKind.class);
		private final AtomicInteger mappings = new AtomicInteger();
		private final AtomicInteger notificationValidations = new AtomicInteger();
		private final Optional<McpProfileFrameworkResultKind> oversizedResultKind;
		private final Optional<String> applicationResultMarker;
		private final AtomicReference<McpJsonRpcMessage.Request> lastMappedRequest =
				new AtomicReference<>();
		private final List<McpResultType> applicationResultTypes =
				new java.util.concurrent.CopyOnWriteArrayList<>();

		TrackingProfile() {
			this(Optional.empty(), Optional.empty());
		}

		TrackingProfile(
				@NonNull McpProfileFrameworkResultKind oversizedResultKind) {
			this(Optional.of(oversizedResultKind), Optional.empty());
		}

		static TrackingProfile withApplicationResultMarker(
				@NonNull String applicationResultMarker) {
			return new TrackingProfile(Optional.empty(),
					Optional.of(requireNonNull(applicationResultMarker)));
		}

		private TrackingProfile(
				@NonNull Optional<McpProfileFrameworkResultKind> oversizedResultKind,
				@NonNull Optional<String> applicationResultMarker) {
			this.oversizedResultKind = requireNonNull(oversizedResultKind);
			this.applicationResultMarker = requireNonNull(applicationResultMarker);
		}

		@Override
		public @NonNull String revision() {
			return FAKE;
		}

		@Override
		public McpJsonRpcMessage.@NonNull Request mapRequest(
				@NonNull McpRequestWireMapper mapper,
				McpJsonRpcEnvelope.@NonNull Request wireRequest) {
			mappings.incrementAndGet();
			if (wireRequest.extensionFields().members().containsKey(
					"com.example/failMapper"))
				throw McpWireDecodingException.invalidRequest("Fake mapper failure",
						Optional.of(wireRequest.id()), Optional.of(wireRequest.method()));
			McpJsonRpcMessage.Request mapped = mapper.map(wireRequest);
			if (wireRequest.extensionFields().members().containsKey(
					"com.example/bodyMismatch")) {
				McpRequestMetadata metadata = mapped.params().metadata();
				McpRequestMetadata mismatched = new McpRequestMetadata(CURRENT,
						metadata.clientCapabilities(), metadata.clientInformation(),
						metadata.deprecatedLogLevel(), metadata.progressToken(),
						metadata.extensionFields());
				mapped = new McpJsonRpcMessage.Request(mapped.id(), mapped.method(),
						new McpRequestParameters(mismatched,
								mapped.params().fields()), mapped.extensionFields());
			}
			lastMappedRequest.set(mapped);
			return mapped;
		}

		@Override
		public @NonNull McpNotificationMetadataValidation
				validateNotificationMetadata(
						McpJsonRpcEnvelope.@NonNull Notification notification) {
			notificationValidations.incrementAndGet();
			if (notification.params().orElse(null) instanceof McpJsonObject params
					&& params.members().get("_meta") instanceof McpJsonObject metadata
					&& metadata.members().containsKey(
							FAKE_NOTIFICATION_METADATA_KEY))
				return new McpNotificationMetadataValidation(true,
						Optional.of(metadata));
			return Mcp20260728ProtocolProfile.INSTANCE
					.validateNotificationMetadata(notification);
		}

		@Override
		public synchronized @NonNull McpWireResult renderFrameworkResult(
				@NonNull McpProfileFrameworkResultKind kind,
				@NonNull McpWireResult canonicalResult) {
			resultKinds.add(kind);
			Map<String, McpJsonValue> fields = new LinkedHashMap<>(
					canonicalResult.toJsonObject().members());
			fields.put(RESULT_MARKER, new McpJsonString(kind.name()));
			if (oversizedResultKind.filter(kind::equals).isPresent())
				fields.put("com.example/oversizedPreflight", new McpJsonString(
						"x".repeat(PREFLIGHT_OUTPUT_LIMIT * 2)));
			return McpWireResult.withPrecomputedJsonObject(canonicalResult,
					new McpJsonObject(fields));
		}

		@Override
		public synchronized @NonNull McpWireResult renderApplicationResult(
				@NonNull McpProfileApplicationResultKind kind,
				@NonNull McpWireResult canonicalResult) {
			applicationResultKinds.add(kind);
			applicationResultTypes.add(canonicalResult.resultType());
			return applicationResultMarker
					.map(marker -> marked(canonicalResult, marker, kind.name()))
					.orElse(canonicalResult);
		}

		@Override
		public synchronized McpJsonRpcMessage.@NonNull Notification
				renderFrameworkNotification(
						@NonNull McpProfileFrameworkNotificationKind kind,
						McpJsonRpcMessage.@NonNull Notification canonical) {
			notificationKinds.add(kind);
			Map<String, McpJsonValue> extensions = new LinkedHashMap<>(
					canonical.extensionFields().members());
			extensions.put(NOTIFICATION_MARKER, new McpJsonString(kind.name()));
			return new McpJsonRpcMessage.Notification(canonical.method(),
					canonical.params(), new McpJsonObject(extensions));
		}

		@Override
		public synchronized @NonNull McpJsonRpcError renderFrameworkError(
				@NonNull McpProfileErrorKind kind,
				@NonNull McpJsonRpcError canonicalError) {
			errorKinds.add(kind);
			errorRenderCounts.merge(kind, 1, Integer::sum);
			McpJsonRpcError rendered = new McpJsonRpcError(canonicalError.code(),
					canonicalError.message() + " [" + kind.name() + "]",
					canonicalError.data());
			lastRenderedErrors.put(kind, rendered);
			return rendered;
		}

		synchronized EnumSet<McpProfileFrameworkResultKind> resultKinds() {
			return EnumSet.copyOf(resultKinds);
		}

		synchronized EnumSet<McpProfileFrameworkNotificationKind>
				notificationKinds() {
			return EnumSet.copyOf(notificationKinds);
		}

		synchronized EnumSet<McpProfileErrorKind> errorKinds() {
			return EnumSet.copyOf(errorKinds);
		}

		private synchronized EnumSet<McpProfileApplicationResultKind>
				applicationResultKinds() {
			return EnumSet.copyOf(applicationResultKinds);
		}

		private synchronized int errorRenderCount(@NonNull McpProfileErrorKind kind) {
			return errorRenderCounts.getOrDefault(kind, 0);
		}

		private synchronized McpJsonRpcError lastRenderedError(
				@NonNull McpProfileErrorKind kind) {
			return requireNonNull(lastRenderedErrors.get(kind));
		}

		int mappingCount() {
			return mappings.get();
		}

		McpJsonRpcMessage.Request lastMappedRequest() {
			return requireNonNull(lastMappedRequest.get());
		}

		List<McpResultType> applicationResultTypes() {
			return List.copyOf(applicationResultTypes);
		}

		private int notificationValidationCount() {
			return notificationValidations.get();
		}

		private static McpWireResult marked(@NonNull McpWireResult canonical,
				@NonNull String marker, @NonNull String value) {
			Map<String, McpJsonValue> fields = new LinkedHashMap<>(
					canonical.toJsonObject().members());
			fields.put(marker, new McpJsonString(value));
			return McpWireResult.withPrecomputedJsonObject(canonical,
					new McpJsonObject(fields));
		}
	}
}
