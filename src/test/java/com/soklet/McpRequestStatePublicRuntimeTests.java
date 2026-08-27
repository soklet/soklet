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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box real-listener coverage for public MCP request-state behavior.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpRequestStatePublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String APPLICATION_TOOL = "state.application";
	private static final String FRAMEWORK_TOOL = "state.framework";
	private static final String APPLICATION_STATE =
			"application-opaque-世界+/=_-state";
	private static final String FRAMEWORK_STATE =
			"deterministic-framework-state-v1";
	private static final String UNAVAILABLE_STATE =
			"deterministic-framework-state-unavailable";
	private static final String ROOTS_CAPABILITY = "{\"roots\":{}}";
	private static final URI RESOURCE_URI =
			URI.create("test://request-state/resource");

	@Test
	public void applicationProtectedStateRoundTripsExactlyWithOneSharedContext()
			throws Exception {
		RecordingProtector protector = new RecordingProtector();
		RecordingLifecycleObserver observer =
				new RecordingLifecycleObserver(2);
		List<McpRequestContext> interceptorContexts =
				Collections.synchronizedList(new ArrayList<>());
		List<McpRequestContext> handlerContexts =
				Collections.synchronizedList(new ArrayList<>());
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(APPLICATION_TOOL)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerContexts.add(request);
					int invocation = handlerInvocations.incrementAndGet();
					if (invocation == 1) {
						Assertions.assertTrue(
								request.getApplicationRequestState().isEmpty());
						Assertions.assertTrue(
								request.getFrameworkRequestState().isEmpty());
						return McpInputRequiredResult.builder()
								.applicationRequestState(APPLICATION_STATE)
								.build();
					}

					String requestState =
							request.getApplicationRequestState().orElseThrow();
					Assertions.assertEquals(APPLICATION_STATE, requestState);
					Assertions.assertTrue(
							request.getFrameworkRequestState().isEmpty());
					return McpCompleteResult.fromToolText(
							"application state accepted");
				})
				.requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED)
				.build();
		McpEndpoint endpoint = endpointBuilder("application-state-runtime-test")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint)
				.protectionConfig(McpProtectionConfig
						.withRequestStateProtector(protector).build())
				.handlerInterceptor((context, continuation) -> {
					interceptorContexts.add(context);
					return continuation.proceed();
				})
				.build();
		McpServerDiagnostics configuredDiagnostics = server.getDiagnostics();
		Assertions.assertEquals(McpProtectionMode.CUSTOM_PROTECTOR,
				configuredDiagnostics.getProtectionMode());
		Assertions.assertEquals(Boolean.TRUE, configuredDiagnostics
				.isApplicationRequestStateProtectorConfigured());
		Assertions.assertTrue(configuredDiagnostics
				.getProtectionKeyRingFingerprint().isEmpty());
		Assertions.assertTrue(configuredDiagnostics
				.getTraceCorrelationConfigurationFingerprint().isEmpty());
		Soklet soklet = managedSoklet(server, observer);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> initial = callTool(port, "application-initial",
					APPLICATION_TOOL, "", "");
			assertSuccess(initial, "application-initial");
			assertContains(initial.body(), "\"resultType\":\"input_required\"");
			assertContains(initial.body(), "\"requestState\":\""
					+ APPLICATION_STATE + "\"");

			HttpResponse<String> retry = callTool(port, "application-retry",
					APPLICATION_TOOL, ",\"requestState\":\""
							+ APPLICATION_STATE + "\"", "");
			assertSuccess(retry, "application-retry");
			assertContains(retry.body(), "\"resultType\":\"complete\"");
			assertContains(retry.body(), "application state accepted");
			observer.awaitFinished();

			Assertions.assertEquals(2, handlerInvocations.get());
			Assertions.assertEquals(2, observer.startedContexts.size());
			Assertions.assertEquals(2, observer.finishedContexts.size());
			Assertions.assertEquals(1, Collections.frequency(observer.outcomes,
					McpRequestOutcome.INPUT_REQUIRED));
			Assertions.assertEquals(1, Collections.frequency(observer.outcomes,
					McpRequestOutcome.COMPLETE));
			Assertions.assertEquals(0, observer.errorCount.get());
			Assertions.assertEquals(0, observer.negativeDurationCount.get());
			Assertions.assertEquals(0, observer.throwableCount.get());
			Assertions.assertEquals(2, interceptorContexts.size());
			Assertions.assertEquals(2, handlerContexts.size());
			for (McpRequestContext context : observer.startedContexts) {
				Assertions.assertTrue(observer.finishedContexts.stream()
						.anyMatch(candidate -> candidate == context));
				Assertions.assertTrue(interceptorContexts.stream()
						.anyMatch(candidate -> candidate == context));
				Assertions.assertTrue(handlerContexts.stream()
						.anyMatch(candidate -> candidate == context));
			}
			McpRequestContext initialContext = contextWithId(
					observer.startedContexts, "application-initial");
			McpRequestContext retryContext = contextWithId(
					observer.startedContexts, "application-retry");
			Assertions.assertTrue(
					initialContext.getApplicationRequestState().isEmpty());
			Assertions.assertTrue(
					initialContext.getFrameworkRequestState().isEmpty());
			String retryState = retryContext.getApplicationRequestState()
					.orElseThrow();
			Assertions.assertEquals(APPLICATION_STATE, retryState);
			Assertions.assertTrue(
					retryContext.getFrameworkRequestState().isEmpty());
			Assertions.assertEquals(0, protector.seals.get());
			Assertions.assertEquals(0, protector.opens.get());
			Assertions.assertFalse(server.getDiagnostics().toString()
					.contains(APPLICATION_STATE));
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void frameworkProtectedStateCompletesOnlyWithAFreshRetryId()
			throws Exception {
		RecordingProtector protector = new RecordingProtector();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.REQUIRED);
		McpJsonObject applicationState = McpJsonObject.builder()
				.put("phase", "awaiting-roots")
				.put("sequence", 1)
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(FRAMEWORK_TOOL)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					if (request.getFrameworkRequestState().isEmpty())
						return McpInputRequiredResult.builder()
								.inputRequest("roots", McpInputRequest.fromDeclaration(
										roots,
												McpJsonObject.emptyInstance()))
								.frameworkRequestState(applicationState)
								.build();

					McpJsonObject stateValue = Assertions.assertInstanceOf(
							McpJsonObject.class,
							request.getFrameworkRequestState().orElseThrow());
					Assertions.assertTrue(
							request.getApplicationRequestState().isEmpty());
					Assertions.assertEquals("awaiting-roots",
							Assertions.assertInstanceOf(McpJsonString.class,
									stateValue.find("phase").orElseThrow()).getValue());
					Assertions.assertEquals(1,
							Assertions.assertInstanceOf(McpJsonNumber.class,
									stateValue.find("sequence").orElseThrow())
									.getValue().intValueExact());
					return McpCompleteResult.fromToolText(
							"framework state accepted");
				})
				.mayRequestInput(roots)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		McpEndpoint endpoint = endpointBuilder("framework-state-runtime-test")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint)
				.protectionConfig(McpProtectionConfig
						.withRequestStateProtector(protector).build())
				.handlerInterceptor((context, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> initial = callTool(port, "framework-initial",
					FRAMEWORK_TOOL, "", ROOTS_CAPABILITY);
			assertSuccess(initial, "framework-initial");
			assertContains(initial.body(), "\"resultType\":\"input_required\"");
			assertContains(initial.body(), "\"requestState\":\""
					+ FRAMEWORK_STATE + "\"");
			Assertions.assertEquals(1, protector.seals.get());

			HttpResponse<String> sameId = callTool(port, "framework-initial",
					FRAMEWORK_TOOL, ",\"requestState\":\""
							+ FRAMEWORK_STATE + "\"", ROOTS_CAPABILITY);
			assertError(sameId, 400, -32602, "framework-initial");
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(1, interceptorInvocations.get());

			HttpResponse<String> retry = callTool(port, "framework-retry",
					FRAMEWORK_TOOL, ",\"requestState\":\""
							+ FRAMEWORK_STATE + "\"", ROOTS_CAPABILITY);
			assertSuccess(retry, "framework-retry");
			assertContains(retry.body(), "\"resultType\":\"complete\"");
			assertContains(retry.body(), "framework state accepted");
			Assertions.assertEquals(2, protector.opens.get());
			Assertions.assertEquals(2, handlerInvocations.get());
			Assertions.assertEquals(2, interceptorInvocations.get());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition()
			throws Exception {
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.REQUIRED);
		McpJsonObject applicationState = McpJsonObject.builder()
				.put("phase", "awaiting-roots")
				.put("origin", "server-a")
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(FRAMEWORK_TOOL)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					if (request.getFrameworkRequestState().isEmpty())
						return McpInputRequiredResult.builder()
								.inputRequest("roots", McpInputRequest.fromDeclaration(
										roots,
												McpJsonObject.emptyInstance()))
								.frameworkRequestState(applicationState)
								.build();

					McpJsonObject stateValue = Assertions.assertInstanceOf(
							McpJsonObject.class,
							request.getFrameworkRequestState().orElseThrow());
					Assertions.assertTrue(
							request.getApplicationRequestState().isEmpty());
					Assertions.assertEquals("server-a",
							Assertions.assertInstanceOf(McpJsonString.class,
									stateValue.find("origin").orElseThrow()).getValue());
					return McpCompleteResult.fromToolText(
							"cross-instance state accepted");
				})
				.mayRequestInput(roots)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		McpEndpoint endpoint = endpointBuilder("cross-instance-state-runtime-test")
				.tool(tool)
				.build();
		McpProtectionKeyRing sharedKeyRing = productionKeyRing(
				"fleet-key", "0123456789abcdef0123456789abcdef");
		McpProtectionKeyRing mismatchedKeyRing = productionKeyRing(
				"fleet-key", "fedcba9876543210fedcba9876543210");
		McpAdmissionController sharedPartition =
				partitionedAdmissionController("tenant-alpha");
		McpAdmissionController mismatchedPartition =
				partitionedAdmissionController("tenant-beta");
		McpHandlerInterceptor interceptor = (context, continuation) -> {
			interceptorInvocations.incrementAndGet();
			return continuation.proceed();
		};

		McpServer emittingServer = stateServer(endpoint, sharedKeyRing,
				sharedPartition, interceptor);
		Soklet emittingSoklet = managedSoklet(emittingServer);
		String protectedState;
		try {
			emittingSoklet.start();
			HttpResponse<String> initial = callTool(boundPort(emittingServer),
					"fleet-initial", FRAMEWORK_TOOL, "", ROOTS_CAPABILITY);
			assertSuccess(initial, "fleet-initial");
			assertContains(initial.body(), "\"resultType\":\"input_required\"");
			protectedState = extractRequestState(initial.body());
			Assertions.assertTrue(protectedState.startsWith(
					"soklet-mcp-request-state-v1."));
		} finally {
			emittingSoklet.stop();
		}
		Assertions.assertEquals(1, handlerInvocations.get());
		Assertions.assertEquals(1, interceptorInvocations.get());

		McpServer acceptingServer = stateServer(endpoint, sharedKeyRing,
				sharedPartition, interceptor);
		Soklet acceptingSoklet = managedSoklet(acceptingServer);
		try {
			acceptingSoklet.start();
			HttpResponse<String> retry = callTool(boundPort(acceptingServer),
					"fleet-retry", FRAMEWORK_TOOL,
					",\"requestState\":\"" + protectedState + "\"",
					ROOTS_CAPABILITY);
			assertSuccess(retry, "fleet-retry");
			assertContains(retry.body(), "\"resultType\":\"complete\"");
			assertContains(retry.body(), "cross-instance state accepted");
		} finally {
			acceptingSoklet.stop();
		}
		Assertions.assertEquals(2, handlerInvocations.get());
		Assertions.assertEquals(2, interceptorInvocations.get());

		McpServer wrongKeyServer = stateServer(endpoint, mismatchedKeyRing,
				sharedPartition, interceptor);
		Soklet wrongKeySoklet = managedSoklet(wrongKeyServer);
		try {
			wrongKeySoklet.start();
			HttpResponse<String> retry = callTool(boundPort(wrongKeyServer),
					"wrong-key-retry", FRAMEWORK_TOOL,
					",\"requestState\":\"" + protectedState + "\"",
					ROOTS_CAPABILITY);
			assertError(retry, 400, -32602, "wrong-key-retry");
		} finally {
			wrongKeySoklet.stop();
		}
		Assertions.assertEquals(2, handlerInvocations.get());
		Assertions.assertEquals(2, interceptorInvocations.get());

		McpServer wrongPartitionServer = stateServer(endpoint, sharedKeyRing,
				mismatchedPartition, interceptor);
		Soklet wrongPartitionSoklet = managedSoklet(wrongPartitionServer);
		try {
			wrongPartitionSoklet.start();
			HttpResponse<String> retry = callTool(boundPort(wrongPartitionServer),
					"wrong-partition-retry", FRAMEWORK_TOOL,
					",\"requestState\":\"" + protectedState + "\"",
					ROOTS_CAPABILITY);
			assertError(retry, 400, -32602, "wrong-partition-retry");
		} finally {
			wrongPartitionSoklet.stop();
		}
		Assertions.assertEquals(2, handlerInvocations.get());
		Assertions.assertEquals(2, interceptorInvocations.get());
	}

	@Test
	public void malformedTamperedAndUnavailableStateHaveFixedPrecedence()
			throws Exception {
		RecordingProtector protector = new RecordingProtector();
		AtomicInteger admissionInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.REQUIRED);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(FRAMEWORK_TOOL)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("must not run");
				})
				.mayRequestInput(roots)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		McpEndpoint endpoint = endpointBuilder("state-errors-runtime-test")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint)
				.admissionController(context -> {
					admissionInvocations.incrementAndGet();
					return McpAdmissionDecision.accepted();
				})
				.protectionConfig(McpProtectionConfig
						.withRequestStateProtector(protector).build())
				.handlerInterceptor((context, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> malformed = callTool(port, "malformed",
					FRAMEWORK_TOOL, ",\"requestState\":7", "");
			assertError(malformed, 400, -32602, "malformed");
			assertStageCounts(0, 0, 0, 0, admissionInvocations,
					protector, interceptorInvocations, handlerInvocations);

			HttpResponse<String> missingCapability = callTool(port,
					"missing-capability", FRAMEWORK_TOOL,
					",\"requestState\":\"tampered\"", "");
			assertMissingRootsCapability(missingCapability,
					"missing-capability");
			assertStageCounts(0, 0, 0, 0, admissionInvocations,
					protector, interceptorInvocations, handlerInvocations);

			HttpResponse<String> tampered = callTool(port, "tampered",
					FRAMEWORK_TOOL, ",\"requestState\":\"tampered\"",
					ROOTS_CAPABILITY);
			assertError(tampered, 400, -32602, "tampered");
			assertStageCounts(1, 1, 0, 0, admissionInvocations,
					protector, interceptorInvocations, handlerInvocations);

			HttpResponse<String> unavailable = callTool(port, "unavailable",
					FRAMEWORK_TOOL, ",\"requestState\":\""
							+ UNAVAILABLE_STATE + "\"", ROOTS_CAPABILITY);
			assertError(unavailable, 503, -32603, "unavailable");
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"unavailable\","
							+ "\"error\":{\"code\":-32603,"
							+ "\"message\":\"Internal error\"}}",
					unavailable.body());
			assertStageCounts(2, 2, 0, 0, admissionInvocations,
					protector, interceptorInvocations, handlerInvocations);
			Assertions.assertEquals(0, protector.seals.get());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void onlyFrameworkProtectedRegistrationsRequireProtectionConfig() {
		McpEndpoint frameworkEndpoint = endpointBuilder(
				"framework-config-runtime-test")
				.tool(noopTool("framework-config",
						McpRequestStateMode.FRAMEWORK_PROTECTED))
				.build();
		IllegalStateException exception = Assertions.assertThrows(
				IllegalStateException.class,
				() -> serverBuilder(frameworkEndpoint).build());
		Assertions.assertEquals(
				"Framework-protected MCP request state requires protection configuration.",
				exception.getMessage());

		McpEndpoint applicationEndpoint = endpointBuilder(
				"application-config-runtime-test")
				.tool(noopTool("application-config",
						McpRequestStateMode.APPLICATION_PROTECTED))
				.build();
		McpServer applicationServer = serverBuilder(applicationEndpoint).build();
		McpServerDiagnostics applicationDiagnostics =
				applicationServer.getDiagnostics();
		Assertions.assertEquals(McpProtectionMode.NO_FRAMEWORK_KEYS,
				applicationDiagnostics.getProtectionMode());
		Assertions.assertEquals(Boolean.FALSE, applicationDiagnostics
				.isApplicationRequestStateProtectorConfigured());
		Assertions.assertTrue(applicationDiagnostics
				.getProtectionKeyRingFingerprint().isEmpty());
		Assertions.assertTrue(applicationDiagnostics
				.getTraceCorrelationConfigurationFingerprint().isEmpty());
	}

	@Test
	public void resourceRetryStateForcesPrivateZeroTtlAndNoStore()
			throws Exception {
		AtomicReference<McpRequestContext> handlerContext = new AtomicReference<>();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "Request-state resource")
				.handler((request, read, features) -> {
					handlerContext.set(request);
					String state =
							request.getApplicationRequestState().orElseThrow();
					Assertions.assertEquals(APPLICATION_STATE, state);
					Assertions.assertTrue(
							request.getFrameworkRequestState().isEmpty());
					return McpCompleteResult.fromResourceOutput(
							McpResourceOutput.builder()
									.content(McpTextResourceContents
											.withUriAndText(read.getUri(),
													"stateful resource")
											.mimeType("text/plain")
											.build())
									.build());
				})
				.requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED)
				.cachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofHours(1)))
				.build();
		McpEndpoint endpoint = endpointBuilder("resource-state-runtime-test")
				.resource(resource)
				.build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> response = send(port,
					request("resource-retry", "resources/read",
							",\"uri\":\"" + RESOURCE_URI
									+ "\",\"requestState\":\""
									+ APPLICATION_STATE + "\"", ""),
					"resources/read", Optional.of(RESOURCE_URI.toString()));

			assertSuccess(response, "resource-retry");
			assertContains(response.body(), "\"text\":\"stateful resource\"");
			assertContains(response.body(), "\"ttlMs\":0");
			assertContains(response.body(), "\"cacheScope\":\"private\"");
			Assertions.assertEquals("no-store",
					response.headers().firstValue("Cache-Control").orElseThrow());
			Assertions.assertNotNull(handlerContext.get());
		} finally {
			soklet.stop();
		}
	}

	private static McpEndpoint.Builder endpointBuilder(String implementationName) {
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						implementationName, "4.0.0-SNAPSHOT").build());
	}

	private static McpServer.Builder serverBuilder(McpEndpoint endpoint) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static McpServer stateServer(McpEndpoint endpoint,
			McpProtectionKeyRing keyRing,
			McpAdmissionController admissionController,
			McpHandlerInterceptor interceptor) {
		return serverBuilder(endpoint)
				.admissionController(admissionController)
				.protectionConfig(McpProtectionConfig.withKeyRing(keyRing).build())
				.handlerInterceptor(interceptor)
				.build();
	}

	private static McpProtectionKeyRing productionKeyRing(String keyId,
			String keyMaterial) {
		return McpProtectionKeyRing.withActiveKey(
				McpProtectionKey.fromIdAndBytes(keyId,
						keyMaterial.getBytes(StandardCharsets.US_ASCII)))
				.build();
	}

	private static McpAdmissionController partitionedAdmissionController(
			String authorizationPartition) {
		return ignored -> McpAdmissionDecision.accepted(
				McpAdmissionIdentity.withRateLimitPartitionKey(
						"rate-" + authorizationPartition)
						.authorizationPartitionKey(authorizationPartition)
						.principal(authorizationPartition)
						.build());
	}

	private static McpToolRegistration<McpJsonObject> noopTool(String name,
			McpRequestStateMode requestStateMode) {
		return McpToolRegistration.withName(name)
				.jsonArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("complete"))
				.requestStateMode(requestStateMode)
				.build();
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static Soklet managedSoklet(McpServer server,
			LifecycleObserver observer) {
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObservers(List.of(observer))
				.build();
		return Soklet.fromConfig(config);
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static McpRequestContext contextWithId(
			List<McpRequestContext> contexts, String requestId) {
		return contexts.stream()
				.filter(context -> context.getRequestId().orElseThrow()
						.equals(McpRequestId.fromString(requestId)))
				.findFirst()
				.orElseThrow();
	}

	private static HttpResponse<String> callTool(int port, String requestId,
			String toolName, String additionalParameters,
			String clientCapabilities) throws Exception {
		return send(port, request(requestId, "tools/call",
				",\"name\":\"" + toolName + "\",\"arguments\":{}"
						+ additionalParameters, clientCapabilities),
				"tools/call", Optional.of(toolName));
	}

	private static HttpResponse<String> send(int port, String body,
			String method, Optional<String> operationName) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		operationName.ifPresent(value -> request.header("Mcp-Name", value));
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static String request(String id, String method,
			String additionalParameters, String clientCapabilities) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ (clientCapabilities.isEmpty() ? "{}" : clientCapabilities) + "}"
				+ additionalParameters + "}}";
	}

	private static String extractRequestState(String body) {
		String marker = "\"requestState\":\"";
		int start = body.indexOf(marker);
		Assertions.assertTrue(start >= 0,
				() -> "Expected a requestState in <" + body + ">.");
		start += marker.length();
		int end = body.indexOf('"', start);
		Assertions.assertTrue(end > start,
				() -> "Expected a nonempty requestState in <" + body + ">.");
		return body.substring(start, end);
	}

	private static void assertSuccess(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
	}

	private static void assertError(HttpResponse<String> response, int status,
			int code, String expectedId) {
		Assertions.assertEquals(status, response.statusCode(), response.body());
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
		assertContains(response.body(), "\"code\":" + code);
	}

	private static void assertMissingRootsCapability(
			HttpResponse<String> response, String expectedId) {
		Assertions.assertEquals(400, response.statusCode(), response.body());
		Assertions.assertEquals(
				"{\"jsonrpc\":\"2.0\",\"id\":\"" + expectedId
						+ "\",\"error\":{\"code\":-32021,"
						+ "\"message\":\"Missing required client capability\","
						+ "\"data\":{\"requiredCapabilities\":"
						+ ROOTS_CAPABILITY + "}}}", response.body());
	}

	private static void assertStageCounts(int admissions, int opens,
			int interceptors, int handlers, AtomicInteger admissionInvocations,
			RecordingProtector protector, AtomicInteger interceptorInvocations,
			AtomicInteger handlerInvocations) {
		Assertions.assertEquals(admissions, admissionInvocations.get());
		Assertions.assertEquals(opens, protector.opens.get());
		Assertions.assertEquals(interceptors, interceptorInvocations.get());
		Assertions.assertEquals(handlers, handlerInvocations.get());
	}

	private static void assertContains(String text, String expected) {
		Assertions.assertTrue(text.contains(expected), () ->
				"Expected <" + text + "> to contain <" + expected + ">.");
	}

	private static final class RecordingLifecycleObserver
			implements LifecycleObserver {
		private final CountDownLatch finished;
		private final List<McpRequestContext> startedContexts =
				Collections.synchronizedList(new ArrayList<>());
		private final List<McpRequestContext> finishedContexts =
				Collections.synchronizedList(new ArrayList<>());
		private final List<McpRequestOutcome> outcomes =
				Collections.synchronizedList(new ArrayList<>());
		private final AtomicInteger errorCount = new AtomicInteger();
		private final AtomicInteger negativeDurationCount = new AtomicInteger();
		private final AtomicInteger throwableCount = new AtomicInteger();

		private RecordingLifecycleObserver(int expectedFinishes) {
			this.finished = new CountDownLatch(expectedFinishes);
		}

		@Override
		public void didStartMcpRequestHandling(
				@NonNull McpRequestContext context) {
			this.startedContexts.add(context);
		}

		@Override
		public void didFinishMcpRequestHandling(
				@NonNull McpRequestContext context,
				@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error,
				@NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			this.finishedContexts.add(context);
			this.outcomes.add(outcome);
			if (error != null)
				this.errorCount.incrementAndGet();
			if (duration.isNegative())
				this.negativeDurationCount.incrementAndGet();
			this.throwableCount.addAndGet(throwables.size());
			this.finished.countDown();
		}

		private void awaitFinished() throws InterruptedException {
			Assertions.assertTrue(this.finished.await(5, TimeUnit.SECONDS),
					"The MCP request finish callbacks did not arrive.");
		}
	}

	private static final class RecordingProtector
			implements McpRequestStateProtector {
		private final AtomicInteger seals = new AtomicInteger();
		private final AtomicInteger opens = new AtomicInteger();
		private final AtomicReference<ProtectedSnapshot> snapshot =
				new AtomicReference<>();

		@Override
		public String seal(McpRequestStateProtectionContext context,
				byte[] plaintext) {
			this.seals.incrementAndGet();
			if (!this.snapshot.compareAndSet(null,
					new ProtectedSnapshot(context.getAssociatedData(), plaintext)))
				throw new IllegalStateException(
						"The deterministic protector supports one sealed state.");
			return FRAMEWORK_STATE;
		}

		@Override
		public byte[] open(McpRequestStateProtectionContext context,
				String protectedState)
				throws McpRequestStateProtectionException {
			this.opens.incrementAndGet();
			if (UNAVAILABLE_STATE.equals(protectedState))
				throw McpRequestStateProtectionException
						.fromProtectorUnavailable();
			ProtectedSnapshot protectedSnapshot = this.snapshot.get();
			if (!FRAMEWORK_STATE.equals(protectedState)
					|| protectedSnapshot == null
					|| !protectedSnapshot.matches(context.getAssociatedData()))
				throw McpRequestStateProtectionException.fromInvalidState();
			return protectedSnapshot.copyPlaintext();
		}
	}

	private static final class ProtectedSnapshot {
		private final byte[] associatedData;
		private final byte[] plaintext;

		private ProtectedSnapshot(byte[] associatedData, byte[] plaintext) {
			this.associatedData = associatedData.clone();
			this.plaintext = plaintext.clone();
		}

		private boolean matches(byte[] candidate) {
			return MessageDigest.isEqual(this.associatedData, candidate);
		}

		private byte[] copyPlaintext() {
			return this.plaintext.clone();
		}
	}
}
