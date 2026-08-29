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
import com.soklet.McpRequestStateMode;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationOptions;
import com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.RecordingLocalizer;
import com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.TestEventSource;
import com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.TrackingProfile;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Objects.requireNonNull;
import static com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.CURRENT;
import static com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.FAKE;
import static com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.NOTIFICATION_MARKER;
import static com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.RESULT_MARKER;
import static com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.listen;
import static com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.observationWithPublicContext;
import static com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.runtime;
import static com.soklet.internal.mcp.protocol.McpSelectedProfileBindingTests.simulationDiscoveryRequest;

public class McpProtocolProfileIsolationTests {
	private static final String ALLOWED_ORIGIN = "https://allowed.example";
	private static final String TOOL = "profile.lookup";
	private static final String MRTR_STATE = "profile-round-one";
	private static final String APPLICATION_MARKER =
			"com.example/profileApplicationResult";

	@Test
	public void sharedRulesChooseTheSameMapperAndOperationFailuresBeforeRendering()
			throws Exception {
		TrackingProfile fake = TrackingProfile.withApplicationResultMarker(
				APPLICATION_MARKER);
		McpProtocolProfileRegistry profiles = profiles(fake);
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy(),
				minimalEndpoint(), McpApplicationRequestRouter.empty());

		try (McpHttpServerRuntime runtime = runtime(binding, profiles)) {
			int port = runtime.start().getPort();

			HttpResponse currentMapper = request(port, CURRENT, "mapper",
					"server/discover", "[]", "", Optional.empty());
			HttpResponse fakeMapper = request(port, FAKE, "mapper",
					"server/discover", "[]", "", Optional.empty());
			assertSameDecision(currentMapper, fakeMapper,
					McpJsonRpcError.INVALID_PARAMS);
			assertNoProfileMarker(currentMapper.body());
			assertOnlyErrorMarker(fakeMapper.body(), McpProfileErrorKind.REQUEST_MAPPER);
			Assertions.assertEquals(currentMapper.body(), fakeMapper.body().replace(
					" [REQUEST_MAPPER]", ""),
					"Profile rendering must be the mapper failure's only difference.");

			HttpResponse currentOperation = request(port, CURRENT, "operation",
					"server/discover", "{}",
					",\"futureOptionalMember\":true", Optional.empty());
			HttpResponse fakeOperation = request(port, FAKE, "operation",
					"server/discover", "{}",
					",\"futureOptionalMember\":true", Optional.empty());
			assertSameDecision(currentOperation, fakeOperation,
					McpJsonRpcError.INVALID_PARAMS);
			assertNoProfileMarker(currentOperation.body());
			assertOnlyErrorMarker(fakeOperation.body(), McpProfileErrorKind.OPERATION);
			Assertions.assertEquals(currentOperation.body(), fakeOperation.body().replace(
					" [OPERATION]", ""),
					"Profile rendering must be the operation failure's only difference.");

			HttpResponse currentDeferredMethod = request(port, CURRENT,
					"deferred-method", "future/deferred-method", "{}", "",
					Optional.empty());
			HttpResponse fakeDeferredMethod = request(port, FAKE,
					"deferred-method", "future/deferred-method", "{}", "",
					Optional.empty());
			assertSameDecision(currentDeferredMethod, fakeDeferredMethod, 404,
					McpJsonRpcError.METHOD_NOT_FOUND);
			assertNoProfileMarker(currentDeferredMethod.body());
			assertOnlyErrorMarker(fakeDeferredMethod.body(),
					McpProfileErrorKind.OPERATION);
			Assertions.assertEquals(currentDeferredMethod.body(),
					fakeDeferredMethod.body().replace(" [OPERATION]", ""),
					"Deferred-R2C method vocabulary must make the same decision; only the selected error renderer may differ.");

			int mappingsBeforeCommonBootstrap = fake.mappingCount();
			HttpResponse currentParse = rawRequest(port, CURRENT,
					"server/discover", "{");
			HttpResponse fakeParse = rawRequest(port, FAKE,
					"server/discover", "{");
			Assertions.assertEquals(currentParse.status(), fakeParse.status());
			Assertions.assertEquals(McpJsonRpcError.PARSE_ERROR,
					errorCode(currentParse.body()));
			Assertions.assertEquals(errorCode(currentParse.body()),
					errorCode(fakeParse.body()));
			Assertions.assertEquals(currentParse.body(), fakeParse.body(),
					"Common bootstrap errors must bypass selected-profile rendering.");
			assertNoProfileMarker(fakeParse.body());
			Assertions.assertEquals(mappingsBeforeCommonBootstrap,
					fake.mappingCount());
		}

		Assertions.assertEquals(3, fake.mappingCount(),
				"The fake must delegate every selected request to the shared mapper.");
		Assertions.assertEquals(
				EnumSet.of(McpProfileErrorKind.REQUEST_MAPPER,
						McpProfileErrorKind.OPERATION), fake.errorKinds());
	}

	@Test
	@Timeout(120)
	public void profileKeyedCatalogBudgetLocalizationAndSimulationUseSelectedHandle()
			throws Exception {
		TrackingProfile fake = TrackingProfile.withApplicationResultMarker(
				APPLICATION_MARKER);
		RecordingLocalizer localizer = new RecordingLocalizer();
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(
				policy().withCatalogLocalizer(localizer), minimalEndpoint(),
				McpApplicationRequestRouter.empty(), observationWithPublicContext(),
				Optional.empty());

		try (McpHttpServerRuntime runtime = runtime(binding, profiles(fake))) {
			int port = runtime.start().getPort();
			HttpResponse currentFirst = request(port, CURRENT, "current-first",
					"server/discover", "{}", "", Optional.empty());
			HttpResponse fakeFirst = request(port, FAKE, "fake-first",
					"server/discover", "{}", "", Optional.empty());
			HttpResponse currentSecond = request(port, CURRENT, "current-second",
					"server/discover", "{}", "", Optional.empty());
			HttpResponse fakeSecond = request(port, FAKE, "fake-second",
					"server/discover", "{}", "", Optional.empty());

			for (HttpResponse response : List.of(currentFirst, fakeFirst,
					currentSecond, fakeSecond))
				Assertions.assertEquals(200, response.status());
			assertNoProfileMarker(currentFirst.body());
			assertNoProfileMarker(currentSecond.body());
			assertResultMarker(fakeFirst.body(), RESULT_MARKER,
					McpProfileFrameworkResultKind.DISCOVERY.name());
			assertResultMarker(fakeSecond.body(), RESULT_MARKER,
					McpProfileFrameworkResultKind.DISCOVERY.name());

			Assertions.assertEquals(4, localizer.inputs().size());
			Set<Long> canonicalLengths = new java.util.LinkedHashSet<>();
			List<Boolean> fakeDocuments = new ArrayList<>();
			for (McpRuntimeCatalogLocalizer.Input input : localizer.inputs()) {
				long encodedLength = input.encodedLength()
						.applyAsLong(input.canonicalDocument());
				Assertions.assertEquals(encodedLength,
						input.canonicalEncodedBytes(),
						"Each cached length must be computed from the selected profile's rendered document.");
				canonicalLengths.add(encodedLength);
				fakeDocuments.add(input.canonicalDocument().members()
						.containsKey(RESULT_MARKER));
			}
			Assertions.assertEquals(List.of(false, true, false, true),
					fakeDocuments,
					"Localization must receive the canonical document for each request's selected profile.");
			Assertions.assertEquals(2, canonicalLengths.size(),
					"Profile-keyed canonical length caches must not contaminate one another.");

			runtime.stop();
			int mappingsBeforeSimulation = fake.mappingCount();
			try (McpHttpServerRuntime.SimulationSession session =
					runtime.openSimulationSession();
					McpSimulation simulation = session.start(
							simulationDiscoveryRequest(FAKE),
							McpSimulationOptions.builder().build())) {
				String body = new String(simulation.awaitResponse(
						Duration.ofSeconds(5)).orElseThrow().getBody()
						.orElseThrow(), StandardCharsets.UTF_8);
				assertResultMarker(body, RESULT_MARKER,
						McpProfileFrameworkResultKind.DISCOVERY.name());
				Assertions.assertTrue(simulation.awaitCompletion(
						Duration.ofSeconds(5)).isPresent());
			}
			Assertions.assertEquals(mappingsBeforeSimulation + 1,
					fake.mappingCount(),
					"Simulation must resolve and retain the injected fake profile.");
		}
	}

	@Test
	@Timeout(120)
	public void selectedHandleCrossesHttpR3cMrtrAndSubscriptionLifecycle()
			throws Exception {
		TrackingProfile fake = TrackingProfile.withApplicationResultMarker(
				APPLICATION_MARKER);
		List<McpProtocolProfile> handlerProfiles = new CopyOnWriteArrayList<>();
		List<McpJsonObject> handlerFields = new CopyOnWriteArrayList<>();
		McpApplicationToolRoute route = new McpApplicationToolRoute(invocation -> {
			handlerProfiles.add(invocation.protocolProfile());
			handlerFields.add(invocation.request().params().fields());
			if (!invocation.request().params().fields().members()
					.containsKey("requestState"))
				return McpWireResult.inputRequired("tools/call", Optional.empty(),
						Optional.of(MRTR_STATE), Optional.empty(),
						new McpJsonObject(Map.of("com.example/round",
								new McpJsonString("initial"))));
			return McpWireResult.complete(new McpJsonObject(Map.of(
					"content", new McpJsonArray(List.of(new McpJsonObject(Map.of(
							"type", new McpJsonString("text"),
							"text", new McpJsonString("complete"))))))));
		}, ignored -> McpRateLimitDecision.allowed(), McpInputRequestPlan.empty(),
				McpRequestStateMode.APPLICATION_PROTECTED);
		McpApplicationRequestRouter router =
				McpApplicationRequestRouter.fromToolRoutes(Map.of(TOOL, route));
		TestEventSource events = new TestEventSource();
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy(),
				fullEndpoint(), router, McpRuntimeObservationSink.disabledInstance(),
				Optional.of(events.source()));

		try (McpHttpServerRuntime runtime = runtime(binding, profiles(fake))) {
			Assertions.assertTrue(fake.resultKinds().containsAll(EnumSet.of(
					McpProfileFrameworkResultKind.DISCOVERY,
					McpProfileFrameworkResultKind.TOOLS_LIST,
					McpProfileFrameworkResultKind.SUBSCRIPTION_TERMINAL)),
					"Static preflight must render each reachable result for the fake.");
			int port = runtime.start().getPort();
			HttpResponse supportBeforeUnknown = request(port, FAKE,
					"support-snapshot", "server/discover", "{}", "",
					Optional.empty());
			Assertions.assertEquals(200, supportBeforeUnknown.status());
			Assertions.assertEquals(204, preflightStatus(port,
					"Content-Type, MCP-Protocol-Version, Mcp-Method"));
			Assertions.assertEquals(403, preflightStatus(port,
					"Mcp-Param-FutureOptionalMember"));

			HttpResponse discovery = request(port, FAKE, "fake-discovery",
					"server/discover", "{}", "", Optional.empty());
			Assertions.assertEquals(200, discovery.status());
			assertResultMarker(discovery.body(), RESULT_MARKER,
					McpProfileFrameworkResultKind.DISCOVERY.name());

			String initialParams = ",\"name\":\"" + TOOL
					+ "\",\"arguments\":{},\"futureOptionalMember\":{"
					+ "\"name\":\"not-a-mirrored-header\"}";
			HttpResponse currentInitial = request(port, CURRENT, "current-initial",
					"tools/call", "{}", initialParams, Optional.of(TOOL));
			HttpResponse fakeInitial = request(port, FAKE, "fake-initial",
					"tools/call", "{}", initialParams, Optional.of(TOOL));
			Assertions.assertEquals(200, currentInitial.status());
			Assertions.assertEquals(200, fakeInitial.status());
			assertResultType(currentInitial.body(), McpResultType.INPUT_REQUIRED);
			assertResultType(fakeInitial.body(), McpResultType.INPUT_REQUIRED);
			Assertions.assertFalse(currentInitial.body().contains(APPLICATION_MARKER),
					currentInitial.body());
			assertResultMarker(fakeInitial.body(), APPLICATION_MARKER,
					McpProfileApplicationResultKind.TOOL.name());
			Assertions.assertEquals(MRTR_STATE, stringMember(
					resultObject(fakeInitial.body()), "requestState"));
			Assertions.assertInstanceOf(McpJsonObject.class,
					fake.lastMappedRequest().params().fields().members()
							.get("futureOptionalMember"));
			Assertions.assertEquals(McpClientCapabilities.empty(),
					fake.lastMappedRequest().params().metadata().clientCapabilities());

			HttpResponse fakeRetry = request(port, FAKE, "fake-retry",
					"tools/call", "{}", ",\"name\":\"" + TOOL
							+ "\",\"arguments\":{},\"requestState\":\""
							+ MRTR_STATE + "\"", Optional.of(TOOL));
			Assertions.assertEquals(200, fakeRetry.status());
			assertResultType(fakeRetry.body(), McpResultType.COMPLETE);
			assertResultMarker(fakeRetry.body(), APPLICATION_MARKER,
					McpProfileApplicationResultKind.TOOL.name());
			Assertions.assertEquals(List.of(
					Mcp20260728ProtocolProfile.INSTANCE, fake, fake), handlerProfiles);
			for (int index : List.of(0, 1))
				Assertions.assertInstanceOf(McpJsonObject.class,
						handlerFields.get(index).members().get("futureOptionalMember"));
			Assertions.assertEquals(List.of(
					McpResultType.INPUT_REQUIRED, McpResultType.COMPLETE),
					fake.applicationResultTypes());

			HttpResponse supportAfterUnknown = request(port, FAKE,
					"support-snapshot", "server/discover", "{}", "",
					Optional.empty());
			Assertions.assertEquals(supportBeforeUnknown, supportAfterUnknown,
					"An unknown request member must not mutate advertised server support.");
			Assertions.assertFalse(supportAfterUnknown.body().contains(
					"futureOptionalMember"), supportAfterUnknown.body());
			Assertions.assertFalse(supportAfterUnknown.body().contains(
					"not-a-mirrored-header"), supportAfterUnknown.body());
			Assertions.assertEquals(403, preflightStatus(port,
					"Mcp-Param-FutureOptionalMember"),
					"An unknown request member must not become a mirrored-header advertisement.");

			try (McpChunkedHttpClient subscription = listen(port, FAKE,
					"fake-subscription")) {
				Assertions.assertEquals(200, subscription.readHead().status());
				String acknowledgement = subscription.readChunkText();
				Assertions.assertTrue(acknowledgement.contains("\""
						+ NOTIFICATION_MARKER
						+ "\":\"SUBSCRIPTION_ACKNOWLEDGEMENT\""),
						acknowledgement);
				events.publish(new McpSubscriptionEventSource.Event
						.ResourcesListChanged());
				String event = subscription.readChunkText();
				Assertions.assertTrue(event.contains("\"" + NOTIFICATION_MARKER
						+ "\":\"SUBSCRIPTION_EVENT\""), event);

				Thread stopThread = new Thread(runtime::stop,
						"mcp-protocol-profile-isolation-stop");
				stopThread.start();
				String terminal = subscription.readChunkText();
				Assertions.assertTrue(terminal.contains("\"" + RESULT_MARKER
						+ "\":\"SUBSCRIPTION_TERMINAL\""), terminal);
				Assertions.assertNull(subscription.readChunk());
				stopThread.join(5_000L);
				Assertions.assertFalse(stopThread.isAlive());
			}
		}

		Assertions.assertTrue(fake.notificationKinds().containsAll(EnumSet.of(
				McpProfileFrameworkNotificationKind.SUBSCRIPTION_ACKNOWLEDGEMENT,
				McpProfileFrameworkNotificationKind.SUBSCRIPTION_EVENT)));
		Assertions.assertTrue(fake.resultKinds().contains(
				McpProfileFrameworkResultKind.SUBSCRIPTION_TERMINAL));
	}

	@Test
	public void fakeProfileRemainsOutsideProductionRegistryEvidenceAndDiscovery()
			throws Exception {
		Assertions.assertEquals(List.of(CURRENT),
				McpProductionProtocolProfiles.REGISTRY.revisions());
		Assertions.assertTrue(
				McpProductionProtocolProfiles.REGISTRY.resolve(FAKE).isEmpty());
		Path evidence = Path.of("conformance", "official",
				"protocol-profile-evidence.json");
		Assertions.assertFalse(Files.readString(evidence).contains(FAKE));

		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy(),
				minimalEndpoint(), McpApplicationRequestRouter.empty());
		try (McpHttpServerRuntime runtime = runtime(binding,
				McpProductionProtocolProfiles.REGISTRY)) {
			HttpResponse discovery = request(runtime.start().getPort(), CURRENT,
					"production-discovery", "server/discover", "{}", "",
					Optional.empty());
			Assertions.assertEquals(200, discovery.status());
			Assertions.assertTrue(discovery.body().contains(
					"\"supportedVersions\":[\"" + CURRENT + "\"]"),
					discovery.body());
			Assertions.assertFalse(discovery.body().contains(FAKE), discovery.body());
			Assertions.assertFalse(discovery.body().contains(RESULT_MARKER),
					discovery.body());
		}
	}

	private static McpProtocolProfileRegistry profiles(
			@NonNull McpProtocolProfile fake) {
		return new McpProtocolProfileRegistry(List.of(
				Mcp20260728ProtocolProfile.INSTANCE, fake));
	}

	private static McpHttpEndpointPolicy policy() {
		return new McpHttpEndpointPolicy("/mcp", Set.of(),
				McpAbsentOriginPolicy.ALLOW,
				CorsAuthorizer.fromWhitelistedOrigins(Set.of(ALLOWED_ORIGIN)),
				McpProtocolAdmissionController.acceptAllInstance());
	}

	private static McpNormalizedEndpoint minimalEndpoint() {
		return McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"profile-isolation-test", "4.0.0-SNAPSHOT"))
				.build();
	}

	private static McpNormalizedEndpoint fullEndpoint() {
		return McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"profile-isolation-test", "4.0.0-SNAPSHOT"))
				.tool(McpNormalizedOperation.named(TOOL))
				.exactResource("test://profile/resource")
				.subscriptions(McpNormalizedSubscriptionConfiguration.supporting(
						McpResourceNotificationType.RESOURCES_LIST_CHANGED))
				.build();
	}

	private static HttpResponse request(int port, @NonNull String revision,
			@NonNull String id, @NonNull String method,
			@NonNull String capabilitiesJson,
			@NonNull String additionalParams,
			@NonNull Optional<@NonNull String> operationName) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + revision
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":"
				+ capabilitiesJson + "}" + additionalParams + "}}";
		List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>(List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", revision),
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", method)));
		operationName.ifPresent(name -> headers.add(
				new McpChunkedHttpClient.RequestHeader("Mcp-Name", name)));
		return rawRequest(port, body, List.copyOf(headers));
	}

	private static HttpResponse rawRequest(int port, @NonNull String revision,
			@NonNull String method, @NonNull String body) throws Exception {
		return rawRequest(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", revision),
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", method)));
	}

	private static HttpResponse rawRequest(int port, @NonNull String body,
			@NonNull List<McpChunkedHttpClient.@NonNull RequestHeader> headers)
			throws Exception {
		try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(
				port, body, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			return new HttpResponse(head.status(), client.readFixedBody(head));
		}
	}

	private static int preflightStatus(int port,
			@NonNull String requestedHeaders) throws Exception {
		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(5)).build();
		HttpRequest request = HttpRequest.newBuilder(
				URI.create("http://127.0.0.1:" + port + "/mcp"))
				.timeout(Duration.ofSeconds(5))
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.header("Origin", ALLOWED_ORIGIN)
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", requestedHeaders)
				.build();
		return client.send(request,
				java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private static void assertSameDecision(@NonNull HttpResponse current,
			@NonNull HttpResponse fake, int expectedCode) {
		assertSameDecision(current, fake, 400, expectedCode);
	}

	private static void assertSameDecision(@NonNull HttpResponse current,
			@NonNull HttpResponse fake, int expectedStatus, int expectedCode) {
		Assertions.assertEquals(expectedStatus, current.status());
		Assertions.assertEquals(current.status(), fake.status());
		Assertions.assertEquals(expectedCode, errorCode(current.body()));
		Assertions.assertEquals(errorCode(current.body()), errorCode(fake.body()));
	}

	private static int errorCode(@NonNull String body) {
		McpJsonRpcEnvelope.ErrorResponse response = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.ErrorResponse.class, codec().decode(body));
		McpJsonObject error = Assertions.assertInstanceOf(McpJsonObject.class,
				response.error());
		return Assertions.assertInstanceOf(McpJsonNumber.class,
				error.members().get("code")).value().intValueExact();
	}

	private static void assertOnlyErrorMarker(@NonNull String body,
			@NonNull McpProfileErrorKind expected) {
		for (McpProfileErrorKind kind : McpProfileErrorKind.values())
			Assertions.assertEquals(kind == expected,
					body.contains("[" + kind.name() + "]"), body);
	}

	private static void assertNoProfileMarker(@NonNull String body) {
		for (McpProfileErrorKind kind : McpProfileErrorKind.values())
			Assertions.assertFalse(body.contains("[" + kind.name() + "]"), body);
		Assertions.assertFalse(body.contains(RESULT_MARKER), body);
		Assertions.assertFalse(body.contains(APPLICATION_MARKER), body);
	}

	private static void assertResultType(@NonNull String body,
			@NonNull McpResultType expected) {
		Assertions.assertEquals(expected.wireValue(),
				stringMember(resultObject(body), "resultType"));
	}

	private static void assertResultMarker(@NonNull String body,
			@NonNull String marker, @NonNull String expected) {
		Assertions.assertEquals(expected, stringMember(resultObject(body), marker));
	}

	private static McpJsonObject resultObject(@NonNull String body) {
		McpJsonRpcEnvelope.ResultResponse response = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.ResultResponse.class, codec().decode(body));
		return Assertions.assertInstanceOf(McpJsonObject.class, response.result());
	}

	private static String stringMember(@NonNull McpJsonObject object,
			@NonNull String name) {
		return Assertions.assertInstanceOf(McpJsonString.class,
				object.members().get(name)).value();
	}

	private static McpJsonRpcEnvelopeCodec codec() {
		McpJsonLimits limits = McpJsonLimits.productionDefaults();
		return new McpJsonRpcEnvelopeCodec(new McpJsonCodec(limits));
	}

	private record HttpResponse(int status, @NonNull String body) {
		private HttpResponse {
			requireNonNull(body);
		}
	}

}
