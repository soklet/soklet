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
import com.soklet.McpAdmissionController;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpInputRequest;
import com.soklet.McpInputRequestDeclaration;
import com.soklet.McpInputRequiredResult;
import com.soklet.McpInputRequirement;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpLocalSubscriptionEventPublisher;
import com.soklet.McpLocalizationContext;
import com.soklet.McpLocalizationResult;
import com.soklet.McpLocalizer;
import com.soklet.McpOperationResult;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpProtectionConfig;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestId;
import com.soklet.McpRequestStateMode;
import com.soklet.McpRequestStateProtectionContext;
import com.soklet.McpRequestStateProtectionException;
import com.soklet.McpRequestStateProtector;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourcePage;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpTextContent;
import com.soklet.McpTextResourceContents;
import com.soklet.McpToolOutput;
import com.soklet.McpToolRegistration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent production-listener goldens for every core MCP result-envelope
 * authority and family.
 */
@Timeout(60)
public class McpResultEnvelopeGoldenProductionTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String SSE_MEDIA_TYPE = "text/event-stream";
	private static final String ROOTS_CAPABILITY = "{\"roots\":{}}";
	private static final String ROOTS_RESPONSE =
			"{\"roots\":[{\"uri\":\"file:///result-envelope/root\"}]}";
	private static final String TOOL_NAME = "result.complete";
	private static final String PROMPT_NAME = "result.prompt";
	private static final URI RESOURCE_URI =
			URI.create("result://envelope/resource");
	private static final String INPUT_TOOL_NAME = "result.input.tool";
	private static final String INPUT_PROMPT_NAME = "result.input.prompt";
	private static final URI INPUT_RESOURCE_URI =
			URI.create("result://envelope/input-resource");
	private static final String FRAMEWORK_STATE =
			"result-envelope-framework-state-v1";
	private static final Path GOLDEN_ROOT = Path.of(
			"conformance", "golden-result-envelope", "live");
	private static final String MANIFEST_NAME = "manifest.sha256";
	private static final Set<String> REQUEST_METHODS = Set.of(
			"server/discover", "tools/list", "tools/call", "prompts/list",
			"prompts/get", "resources/list", "resources/templates/list",
			"resources/read", "subscriptions/listen");
	private static final Set<String> INPUT_REQUIRED_METHODS = Set.of(
			"tools/call", "prompts/get", "resources/read");
	private static final Map<String, Set<String>> COMPLETE_METHOD_FIXTURES = Map.of(
			"server/discover", Set.of("complete-server-discover-string.json",
					"complete-server-discover-localized-string.json"),
			"tools/list", Set.of("complete-tools-list-integer.json",
					"complete-tools-list-typed-schema-integer.json"),
			"tools/call", Set.of("complete-tools-call-handler-string.json",
					"complete-tools-call-interceptor-integer.json",
					"complete-tools-call-input-retry-integer.json",
					"complete-tools-call-typed-sanitized-string.json",
					"complete-tools-call-request-sse-string.sse.hex",
					"complete-tools-call-request-sse-integer.sse.hex"),
			"prompts/list", Set.of("complete-prompts-list-string.json"),
			"prompts/get", Set.of("complete-prompts-get-handler-integer.json",
					"complete-prompts-get-state-retry-string.json"),
			"resources/list", Set.of(
					"complete-resources-list-framework-integer.json",
					"complete-resources-list-handler-string.json"),
			"resources/templates/list", Set.of(
					"complete-resources-templates-list-string.json"),
			"resources/read", Set.of(
					"complete-resources-read-handler-string.json",
					"complete-resources-read-combined-retry-integer.json"),
			"subscriptions/listen", Set.of(
					"complete-subscriptions-listen-integer.sse.hex",
					"complete-subscriptions-listen-localized-string.sse.hex"));
	private static final Map<String, Set<String>> INPUT_REQUIRED_METHOD_FIXTURES =
			Map.of(
					"tools/call", Set.of(
							"input-required-tools-call-input-requests-string.json",
							"input-required-tools-call-request-sse-string.sse.hex",
							"input-required-tools-call-request-sse-integer.sse.hex"),
					"prompts/get", Set.of(
							"input-required-prompts-get-state-integer.json"),
					"resources/read", Set.of(
							"input-required-resources-read-combined-string.json"));
	private static final Set<String> EXPECTED_FIXTURES = expectedFixtures();

	@BeforeAll
	public static void corpusIsCompleteChecksumBoundAndStructurallyExclusive()
			throws Exception {
		Assertions.assertEquals(REQUEST_METHODS,
				COMPLETE_METHOD_FIXTURES.keySet());
		Assertions.assertEquals(INPUT_REQUIRED_METHODS,
				INPUT_REQUIRED_METHOD_FIXTURES.keySet());
		Set<String> runtimeMethods = runtimeMethodInventory();
		Set<String> expectedRuntimeMethods = new LinkedHashSet<>(REQUEST_METHODS);
		expectedRuntimeMethods.add("notifications/cancelled");
		Assertions.assertEquals(expectedRuntimeMethods, runtimeMethods);
		for (String method : runtimeMethods)
			Assertions.assertEquals(INPUT_REQUIRED_METHODS.contains(method),
					McpWireResult.supportsInputRequired(method), method);
		Assertions.assertEquals(Set.of(
				McpRuntimeCatalogLocalizer.ResponseKind.DISCOVERY,
				McpRuntimeCatalogLocalizer.ResponseKind.TOOLS_LIST,
				McpRuntimeCatalogLocalizer.ResponseKind.PROMPTS_LIST,
				McpRuntimeCatalogLocalizer.ResponseKind.RESOURCES_LIST,
				McpRuntimeCatalogLocalizer.ResponseKind.RESOURCE_TEMPLATES_LIST,
				McpRuntimeCatalogLocalizer.ResponseKind.SUBSCRIPTION_TERMINAL),
				Set.copyOf(Arrays.asList(
						McpRuntimeCatalogLocalizer.ResponseKind.values())));
		String runtimeSource = Files.readString(Path.of("src", "main", "java",
				"com", "soklet", "internal", "mcp", "protocol",
				"McpHttpServerRuntime.java"), StandardCharsets.UTF_8);
		Assertions.assertEquals(5, occurrences(runtimeSource,
				"return catalogResponse("));
		Assertions.assertEquals(2, occurrences(runtimeSource,
				"McpWireResult.withPrecomputedJsonObject("));
		for (McpRuntimeCatalogLocalizer.ResponseKind kind
				: McpRuntimeCatalogLocalizer.ResponseKind.values())
			Assertions.assertTrue(runtimeSource.contains("ResponseKind." + kind.name()),
					kind.name());

		Assertions.assertTrue(Files.isDirectory(GOLDEN_ROOT, LinkOption.NOFOLLOW_LINKS),
				GOLDEN_ROOT.toString());
		Assertions.assertFalse(Files.isSymbolicLink(GOLDEN_ROOT),
				GOLDEN_ROOT.toString());
		Path manifest = GOLDEN_ROOT.resolve(MANIFEST_NAME);
		Assertions.assertTrue(Files.isRegularFile(
				manifest, LinkOption.NOFOLLOW_LINKS), manifest.toString());
		Assertions.assertFalse(Files.isSymbolicLink(manifest), manifest.toString());

		List<String> manifestLines = Files.readAllLines(
				manifest, StandardCharsets.UTF_8);
		Assertions.assertFalse(manifestLines.isEmpty());
		Pattern linePattern = Pattern.compile("([0-9a-f]{64})  ([^/]+)");
		Map<String, String> hashesByFilename = new LinkedHashMap<>();
		for (String line : manifestLines) {
			Matcher matcher = linePattern.matcher(line);
			Assertions.assertTrue(matcher.matches(), line);
			Assertions.assertNull(hashesByFilename.put(
					matcher.group(2), matcher.group(1)), matcher.group(2));
		}
		Assertions.assertEquals(hashesByFilename.keySet().stream().sorted().toList(),
				List.copyOf(hashesByFilename.keySet()),
				"The result-envelope manifest must be filename-sorted.");
		Assertions.assertEquals(EXPECTED_FIXTURES,
				Set.copyOf(hashesByFilename.keySet()));

		Set<String> directoryEntries = new LinkedHashSet<>();
		try (var entries = Files.list(GOLDEN_ROOT)) {
			for (Path entry : entries.sorted().toList()) {
				String filename = entry.getFileName().toString();
				directoryEntries.add(filename);
				Assertions.assertFalse(Files.isSymbolicLink(entry), filename);
				Assertions.assertTrue(Files.isRegularFile(
						entry, LinkOption.NOFOLLOW_LINKS), filename);
			}
		}
		Set<String> expectedEntries = new LinkedHashSet<>(EXPECTED_FIXTURES);
		expectedEntries.add(MANIFEST_NAME);
		Assertions.assertEquals(expectedEntries, directoryEntries);

		Set<String> observedAxes = new LinkedHashSet<>();
		for (Map.Entry<String, String> entry : hashesByFilename.entrySet()) {
			String filename = entry.getKey();
			Path path = GOLDEN_ROOT.resolve(filename);
			byte[] bytes = Files.readAllBytes(path);
			Assertions.assertEquals(entry.getValue(), sha256(bytes), filename);
			String fixture = new String(bytes, StandardCharsets.UTF_8);
			Assertions.assertFalse(fixture.contains("\r"), filename);
			Assertions.assertFalse(fixture.contains("RESULT-ENVELOPE-SECRET"),
					filename);
			boolean sse = filename.endsWith(".sse.hex");
			String wireFixture = sse ? sseFromHexFixture(fixture, filename)
					: fixture;
			Assertions.assertFalse(wireFixture.contains("RESULT-ENVELOPE-SECRET"),
					filename);
			String json = sse ? jsonFromSseFixture(wireFixture, filename)
					: jsonFromJsonFixture(fixture, filename);
			String expectedResultType = filename.startsWith("complete-")
					? "complete" : "input_required";
			McpJsonRpcEnvelope.ResultResponse response = assertResultEnvelope(
					json, expectedResultType);
			String idType = response.id() instanceof McpJsonRpcId.StringId
					? "string" : "integer";
			observedAxes.add((sse ? "sse" : "json") + ":"
					+ expectedResultType + ":" + idType);
		}
		Assertions.assertEquals(Set.of(
				"json:complete:string", "json:complete:integer",
				"json:input_required:string", "json:input_required:integer",
				"sse:complete:string", "sse:complete:integer",
				"sse:input_required:string", "sse:input_required:integer"),
				observedAxes);
	}

	@Test
	public void everyFrameworkAndApplicationCompleteAuthorityMatchesGoldens()
			throws Exception {
		AtomicInteger toolHandlerInvocations = new AtomicInteger();
		AtomicInteger promptHandlerInvocations = new AtomicInteger();
		AtomicInteger resourceHandlerInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					toolHandlerInvocations.incrementAndGet();
					return completeTool("handler tool complete", "handler-tool");
				})
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName(PROMPT_NAME)
				.handler((request, promptGet, features) -> {
					promptHandlerInvocations.incrementAndGet();
					return completePrompt("handler prompt complete", "handler-prompt");
				})
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "Result envelope resource")
				.handler((request, read, features) -> {
					resourceHandlerInvocations.incrementAndGet();
					return completeResource(read.getUri(),
							"handler resource complete", "handler-resource");
				})
				.build();
		McpEndpoint endpoint = endpointBuilder("result-envelope-complete")
				.tool(tool)
				.prompt(prompt)
				.resource(resource)
				.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, continuation) -> {
					interceptorInvocations.incrementAndGet();
					if (context.getRequestId().orElseThrow().equals(
							McpRequestId.fromInteger(BigInteger.valueOf(104L))))
						return completeTool("interceptor tool complete",
								"interceptor-tool");
					return continuation.proceed();
				})
				.build();

		try {
			server.start();
			int port = boundPort(server);
			assertJsonGolden(port, "complete-server-discover-string.json",
					"\"discover-complete\"", "server/discover", "", "", null,
					"complete");
			assertJsonGolden(port, "complete-tools-list-integer.json",
					"101", "tools/list", "", "", null, "complete");
			assertJsonGolden(port, "complete-prompts-list-string.json",
					"\"prompts-list-complete\"", "prompts/list", "", "", null,
					"complete");
			assertJsonGolden(port, "complete-resources-list-framework-integer.json",
					"102", "resources/list", "", "", null, "complete");
			assertJsonGolden(port,
					"complete-resources-templates-list-string.json",
					"\"resource-templates-complete\"", "resources/templates/list",
					"", "", null, "complete");
			assertJsonGolden(port, "complete-tools-call-handler-string.json",
					"\"tool-handler-complete\"", "tools/call",
					",\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}",
					"", TOOL_NAME, "complete");
			assertJsonGolden(port, "complete-prompts-get-handler-integer.json",
					"103", "prompts/get",
					",\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{}",
					"", PROMPT_NAME, "complete");
			assertJsonGolden(port, "complete-resources-read-handler-string.json",
					"\"resource-handler-complete\"", "resources/read",
					",\"uri\":\"" + RESOURCE_URI + "\"", "",
					RESOURCE_URI.toString(), "complete");
			assertJsonGolden(port, "complete-tools-call-interceptor-integer.json",
					"104", "tools/call",
					",\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}",
					"", TOOL_NAME, "complete");

			Assertions.assertEquals(1, toolHandlerInvocations.get());
			Assertions.assertEquals(1, promptHandlerInvocations.get());
			Assertions.assertEquals(1, resourceHandlerInvocations.get());
			Assertions.assertEquals(4, interceptorInvocations.get());
		} finally {
			server.stop();
		}

		AtomicInteger listHandlerInvocations = new AtomicInteger();
		AtomicInteger listInterceptorInvocations = new AtomicInteger();
		McpResourceRegistration listedResource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "Custom-listed result resource")
				.handler((request, read, features) -> completeResource(
						read.getUri(), "unused", "unused"))
				.build();
		McpEndpoint customListEndpoint = endpointBuilder(
				"result-envelope-custom-list")
				.resource(listedResource)
				.resourceListHandler((request, list, features) -> {
					listHandlerInvocations.incrementAndGet();
					return McpResourcePage.builder()
							.resources(list.getRegisteredResourceDescriptors())
							.metadata(metadata("custom-resource-list"))
							.build();
				})
				.build();
		McpServer customListServer = serverBuilder(customListEndpoint)
				.handlerInterceptor((context, continuation) -> {
					listInterceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.build();
		try {
			customListServer.start();
			assertJsonGolden(boundPort(customListServer),
					"complete-resources-list-handler-string.json",
					"\"resources-list-handler-complete\"", "resources/list",
					"", "", null, "complete");
			Assertions.assertEquals(1, listHandlerInvocations.get());
			Assertions.assertEquals(1, listInterceptorInvocations.get());
		} finally {
			customListServer.stop();
		}

		McpEndpoint localizedEndpoint = localizedEndpointBuilder(
				"result-envelope-localized-discovery")
				.instructions("Canonical result instructions")
				.build();
		McpServer localizedServer = serverBuilder(localizedEndpoint)
				.localizer(frenchLocalizer())
				.build();
		try {
			localizedServer.start();
			assertLocalizedJsonGolden(boundPort(localizedServer),
					"complete-server-discover-localized-string.json",
					"\"localized-discover-complete\"", "server/discover",
					"", "", null, "complete");
		} finally {
			localizedServer.stop();
		}
	}

	@Test
	public void everyInputRequiredPermutationAndFreshIdRetryMatchesGoldens()
			throws Exception {
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		AtomicInteger toolInvocations = new AtomicInteger();
		AtomicInteger promptInvocations = new AtomicInteger();
		AtomicInteger resourceInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(INPUT_TOOL_NAME)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					toolInvocations.incrementAndGet();
					if (request.getInputResponses().find("roots").isEmpty())
						return inputRequiredRoots(roots,
								"inputRequests-only", false);
					return completeTool("tool input retry complete",
							"tool-input-retry");
				})
				.mayRequestInput(roots)
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName(INPUT_PROMPT_NAME)
				.handler((request, promptGet, features) -> {
					promptInvocations.incrementAndGet();
					if (request.getApplicationRequestState().isEmpty())
						return McpInputRequiredResult.builder()
								.applicationRequestState("prompt-state-v1")
								.metadata(metadata("requestState-only"))
								.build();
					Assertions.assertEquals("prompt-state-v1",
							request.getApplicationRequestState().orElseThrow());
					return completePrompt("prompt state retry complete",
							"prompt-state-retry");
				})
				.requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED)
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(INPUT_RESOURCE_URI, "Combined input resource")
				.handler((request, read, features) -> {
					resourceInvocations.incrementAndGet();
					if (request.getFrameworkRequestState().isEmpty())
						return McpInputRequiredResult.builder()
								.inputRequest("roots", McpInputRequest.fromDeclaration(
										roots, McpJsonObject.emptyInstance()))
								.frameworkRequestState(McpJsonObject.builder()
										.put("phase", "combined")
										.build())
								.metadata(metadata("combined"))
								.build();
					Assertions.assertTrue(
							request.getInputResponses().find("roots").isPresent());
					McpJsonObject state = Assertions.assertInstanceOf(
							McpJsonObject.class,
							request.getFrameworkRequestState().orElseThrow());
					Assertions.assertEquals(McpJsonString.fromValue("combined"),
							state.find("phase").orElseThrow());
					return completeResource(read.getUri(),
							"resource combined retry complete",
							"resource-combined-retry");
				})
				.mayRequestInput(roots)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		McpEndpoint endpoint = endpointBuilder("result-envelope-input")
				.tool(tool)
				.prompt(prompt)
				.resource(resource)
				.build();
		DeterministicProtector protector = new DeterministicProtector();
		McpServer server = serverBuilder(endpoint)
				.protectionConfig(McpProtectionConfig
						.withRequestStateProtector(protector).build())
				.build();

		try {
			server.start();
			int port = boundPort(server);
			assertJsonGolden(port,
					"input-required-tools-call-input-requests-string.json",
					"\"tool-input-initial\"", "tools/call",
					",\"name\":\"" + INPUT_TOOL_NAME + "\",\"arguments\":{}",
					ROOTS_CAPABILITY, INPUT_TOOL_NAME, "input_required");
			assertJsonGolden(port, "complete-tools-call-input-retry-integer.json",
					"201", "tools/call", ",\"name\":\"" + INPUT_TOOL_NAME
							+ "\",\"arguments\":{},\"inputResponses\":{"
							+ "\"roots\":" + ROOTS_RESPONSE + "}",
					ROOTS_CAPABILITY, INPUT_TOOL_NAME, "complete");
			assertJsonGolden(port, "input-required-prompts-get-state-integer.json",
					"202", "prompts/get", ",\"name\":\"" + INPUT_PROMPT_NAME
							+ "\",\"arguments\":{}", "", INPUT_PROMPT_NAME,
					"input_required");
			assertJsonGolden(port, "complete-prompts-get-state-retry-string.json",
					"\"prompt-state-retry\"", "prompts/get",
					",\"name\":\"" + INPUT_PROMPT_NAME + "\",\"arguments\":{},"
							+ "\"requestState\":\"prompt-state-v1\"", "",
					INPUT_PROMPT_NAME, "complete");
			assertJsonGolden(port,
					"input-required-resources-read-combined-string.json",
					"\"resource-combined-initial\"", "resources/read",
					",\"uri\":\"" + INPUT_RESOURCE_URI + "\"",
					ROOTS_CAPABILITY, INPUT_RESOURCE_URI.toString(),
					"input_required");
			assertJsonGolden(port,
					"complete-resources-read-combined-retry-integer.json",
					"203", "resources/read", ",\"uri\":\"" + INPUT_RESOURCE_URI
							+ "\",\"inputResponses\":{\"roots\":" + ROOTS_RESPONSE
							+ "},\"requestState\":\"" + FRAMEWORK_STATE + "\"",
					ROOTS_CAPABILITY, INPUT_RESOURCE_URI.toString(), "complete");

			Assertions.assertEquals(2, toolInvocations.get());
			Assertions.assertEquals(2, promptInvocations.get());
			Assertions.assertEquals(2, resourceInvocations.get());
			Assertions.assertEquals(1, protector.seals.get());
			Assertions.assertEquals(1, protector.opens.get());
		} finally {
			server.stop();
		}
	}

	@Test
	public void requestScopedAndSubscriptionSseTerminalsMatchGoldens()
			throws Exception {
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		AtomicInteger completeInvocations = new AtomicInteger();
		AtomicInteger inputInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> complete = McpToolRegistration
				.withName("result.sse.complete")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					completeInvocations.incrementAndGet();
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					return completeTool("request SSE complete", "request-sse-complete");
				})
				.build();
		McpToolRegistration<McpJsonObject> input = McpToolRegistration
				.withName("result.sse.input")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					inputInvocations.incrementAndGet();
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					return inputRequiredRoots(roots, "request-sse-input", false);
				})
				.mayRequestInput(roots)
				.build();
		McpEndpoint endpoint = endpointBuilder("result-envelope-sse")
				.tool(complete)
				.tool(input)
				.build();
		McpServer server = serverBuilder(endpoint).build();

		try {
			server.start();
			int port = boundPort(server);
			assertRequestSseGolden(port,
					"complete-tools-call-request-sse-string.sse.hex",
					"\"sse-complete-string\"", "result.sse.complete", "",
					"\"progress-complete-string\"", "complete");
			assertRequestSseGolden(port,
					"complete-tools-call-request-sse-integer.sse.hex",
					"301", "result.sse.complete", "", "401", "complete");
			assertRequestSseGolden(port,
					"input-required-tools-call-request-sse-string.sse.hex",
					"\"sse-input-string\"", "result.sse.input",
					ROOTS_CAPABILITY, "\"progress-input-string\"",
					"input_required");
			assertRequestSseGolden(port,
					"input-required-tools-call-request-sse-integer.sse.hex",
					"302", "result.sse.input", ROOTS_CAPABILITY, "402",
					"input_required");
			Assertions.assertEquals(2, completeInvocations.get());
			Assertions.assertEquals(2, inputInvocations.get());
		} finally {
			server.stop();
		}

		McpLocalSubscriptionEventPublisher publisher =
				McpLocalSubscriptionEventPublisher.fromDefaults();
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher)
				.notificationTypes(Set.of(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "Subscription result resource")
				.handler((request, read, features) -> completeResource(
						read.getUri(), "subscription resource", "subscription-resource"))
				.build();
		McpEndpoint subscriptionEndpoint = endpointBuilder(
				"result-envelope-subscription")
				.resource(resource)
				.subscriptions(subscriptions)
				.build();
		McpServer subscriptionServer = serverBuilder(subscriptionEndpoint).build();
		assertSubscriptionTerminalGolden(subscriptionServer, "303",
				"complete-subscriptions-listen-integer.sse.hex", false);

		McpEndpoint localizedSubscriptionEndpoint = localizedEndpointBuilder(
				"result-envelope-localized-subscription")
				.resource(resource)
				.subscriptions(McpSubscriptionConfig
						.withEventPublisher(
								McpLocalSubscriptionEventPublisher.fromDefaults())
						.notificationTypes(Set.of(
								McpSubscriptionNotificationType
										.RESOURCES_LIST_CHANGED))
						.build())
				.build();
		McpServer localizedSubscriptionServer = serverBuilder(
				localizedSubscriptionEndpoint)
				.localizer(frenchLocalizer())
				.build();
		assertSubscriptionTerminalGolden(localizedSubscriptionServer,
				"\"localized-subscription\"",
				"complete-subscriptions-listen-localized-string.sse.hex", true);
	}

	@Test
	public void typedSchemaSanitizerMetadataAndRedactionControlsAreLive()
			throws Exception {
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpToolRegistration<TypedArguments> tool = McpToolRegistration
				.withName("result.typed")
				.types(TypedArguments.class, TypedResult.class)
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					if ("handler-fail".equals(
							arguments.getConvertedArguments().mode()))
						throw new IllegalStateException(
								"RESULT-ENVELOPE-SECRET-HANDLER");
					return new TypedResult("RESULT-ENVELOPE-SECRET-RAW");
				})
				.build();
		McpEndpoint endpoint = endpointBuilder("result-envelope-typed")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint)
				.toolOutputSanitizer((request, toolName, rawArguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					String mode = Assertions.assertInstanceOf(McpJsonString.class,
							rawArguments.find("mode").orElseThrow()).getValue();
					if ("sanitizer-fail".equals(mode))
						throw new IllegalStateException(
								"RESULT-ENVELOPE-SECRET-SANITIZER");
					return McpToolOutput.fromStructuredContent(
							McpJsonObject.builder()
									.put("value", "sanitized-visible")
									.build());
				})
				.build();

		try {
			server.start();
			int port = boundPort(server);
			assertJsonGolden(port, "complete-tools-list-typed-schema-integer.json",
					"500", "tools/list", "", "", null, "complete");
			String success = assertJsonGolden(port,
					"complete-tools-call-typed-sanitized-string.json",
					"\"typed-sanitized\"", "tools/call",
					",\"name\":\"result.typed\",\"arguments\":{"
							+ "\"mode\":\"success\"}", "", "result.typed",
					"complete");
			Assertions.assertFalse(success.contains("RESULT-ENVELOPE-SECRET"),
					success);

			assertRedactedInternalError(port, "501", "sanitizer-fail");
			assertRedactedInternalError(port, "\"typed-handler-fail\"",
					"handler-fail");
			Assertions.assertEquals(3, handlerInvocations.get());
			Assertions.assertEquals(2, sanitizerInvocations.get());
		} finally {
			server.stop();
		}
	}

	private static void assertRedactedInternalError(int port, String idJson,
			String mode) throws Exception {
		try (McpChunkedHttpClient client = send(port, idJson, "tools/call",
				",\"name\":\"result.typed\",\"arguments\":{\"mode\":\""
						+ mode + "\"}", "", "result.typed", null)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			assertJsonHead(head, 500);
			String body = client.readFixedBody(head);
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":" + idJson
					+ ",\"error\":{\"code\":-32603,"
					+ "\"message\":\"Internal error\"}}", body);
			Assertions.assertFalse(body.contains("RESULT-ENVELOPE-SECRET"), body);
			McpJsonRpcEnvelope.ErrorResponse response = Assertions.assertInstanceOf(
					McpJsonRpcEnvelope.ErrorResponse.class, codec().decode(body));
			Assertions.assertTrue(response.extensionFields().members().isEmpty());
		}
	}

	private static String assertJsonGolden(int port, String fixtureName,
			String idJson, String method, String additionalParamsJson,
			String capabilitiesJson, String operationName,
			String expectedResultType) throws Exception {
		try (McpChunkedHttpClient client = send(port, idJson, method,
				additionalParamsJson, capabilitiesJson, operationName, null)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			assertJsonHead(head, 200);
			String body = client.readFixedBody(head);
			assertGolden(body, fixtureName);
			McpJsonRpcEnvelope.ResultResponse response = assertResultEnvelope(
					body, expectedResultType);
			Assertions.assertEquals(idFromJson(idJson), response.id());
			return body;
		}
	}

	private static void assertLocalizedJsonGolden(int port, String fixtureName,
			String idJson, String method, String additionalParamsJson,
			String capabilitiesJson, String operationName,
			String expectedResultType) throws Exception {
		try (McpChunkedHttpClient client = send(port, idJson, method,
				additionalParamsJson, capabilitiesJson, operationName, null)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			assertJsonHead(head, 200);
			assertLocalizationHeaders(head);
			String body = client.readFixedBody(head);
			assertGolden(body, fixtureName);
			McpJsonRpcEnvelope.ResultResponse response = assertResultEnvelope(
					body, expectedResultType);
			Assertions.assertEquals(idFromJson(idJson), response.id());
			Assertions.assertTrue(body.contains("FR:"), body);
		}
	}

	private static void assertSubscriptionTerminalGolden(McpServer server,
			String idJson, String fixtureName, boolean localized) throws Exception {
		McpChunkedHttpClient client = null;
		Thread stopThread = null;
		try {
			server.start();
			client = send(boundPort(server), idJson, "subscriptions/listen",
					",\"notifications\":{\"resourcesListChanged\":true}",
					"", null, null);
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			assertSseHead(head);
			if (localized)
				assertLocalizationHeaders(head);
			assertNotificationFrame(client.readChunkText(),
					"notifications/subscriptions/acknowledged");
			stopThread = new Thread(server::stop,
					"result-envelope-subscription-stop");
			stopThread.start();
			String terminal = client.readChunkText();
			assertGolden(terminal, fixtureName);
			McpJsonRpcEnvelope.ResultResponse response = assertResultEnvelope(
					jsonFromSseFixture(terminal, fixtureName), "complete");
			Assertions.assertEquals(idFromJson(idJson), response.id());
			Assertions.assertEquals(localized, terminal.contains("FR:"), terminal);
			Assertions.assertNull(client.readChunk());
			stopThread.join(5_000L);
			Assertions.assertFalse(stopThread.isAlive());
		} finally {
			if (client != null)
				client.close();
			server.stop();
			if (stopThread != null && stopThread.isAlive())
				stopThread.join(5_000L);
		}
	}

	private static void assertRequestSseGolden(int port, String fixtureName,
			String idJson, String toolName, String capabilitiesJson,
			String progressTokenJson, String expectedResultType) throws Exception {
		try (McpChunkedHttpClient client = send(port, idJson, "tools/call",
				",\"name\":\"" + toolName + "\",\"arguments\":{}",
				capabilitiesJson, toolName, progressTokenJson)) {
			assertSseHead(client.readHead());
			assertNotificationFrame(client.readChunkText(),
					"notifications/progress");
			String terminal = client.readChunkText();
			assertGolden(terminal, fixtureName);
			McpJsonRpcEnvelope.ResultResponse response = assertResultEnvelope(
					jsonFromSseFixture(terminal, fixtureName), expectedResultType);
			Assertions.assertEquals(idFromJson(idJson), response.id());
			Assertions.assertNull(client.readChunk());
		}
	}

	private static void assertNotificationFrame(String frame, String method) {
		McpJsonRpcEnvelope.Notification notification = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.Notification.class,
				codec().decode(jsonFromSseFixture(frame, method)));
		Assertions.assertEquals(method, notification.method());
	}

	private static McpChunkedHttpClient send(int port, String idJson,
			String method, String additionalParamsJson, String capabilitiesJson,
			String operationName, String progressTokenJson) throws Exception {
		String capabilities = capabilitiesJson.isEmpty() ? "{}" : capabilitiesJson;
		String progress = progressTokenJson == null ? ""
				: ",\"progressToken\":"
						+ progressTokenJson;
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ capabilities + progress + "}" + additionalParamsJson + "}}";
		List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>(List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", method)));
		if (operationName != null)
			headers.add(new McpChunkedHttpClient.RequestHeader(
					"Mcp-Name", operationName));
		return McpChunkedHttpClient.postMcpMessage(port, body, headers);
	}

	private static void assertJsonHead(
			McpChunkedHttpClient.HttpResponseHead head, int expectedStatus) {
		Assertions.assertEquals(expectedStatus, head.status(), head.raw());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
	}

	private static void assertSseHead(
			McpChunkedHttpClient.HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals(SSE_MEDIA_TYPE,
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
		Assertions.assertFalse(head.hasHeader("Content-Length"));
	}

	private static void assertLocalizationHeaders(
			McpChunkedHttpClient.HttpResponseHead head) {
		Assertions.assertEquals("fr", head.singleHeader("Content-Language"));
		Assertions.assertEquals("Accept-Language", head.singleHeader("Vary"));
	}

	private static McpJsonRpcEnvelope.ResultResponse assertResultEnvelope(
			String json, String expectedResultType) {
		McpJsonRpcEnvelope.ResultResponse response = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.ResultResponse.class, codec().decode(json));
		Assertions.assertTrue(response.extensionFields().members().isEmpty());
		com.soklet.internal.mcp.protocol.McpJsonObject root =
				Assertions.assertInstanceOf(
						com.soklet.internal.mcp.protocol.McpJsonObject.class,
						new McpJsonCodec(McpJsonLimits.productionDefaults()).parse(json));
		Assertions.assertEquals(Set.of("jsonrpc", "id", "result"),
				root.members().keySet());
		com.soklet.internal.mcp.protocol.McpJsonObject result =
				Assertions.assertInstanceOf(
						com.soklet.internal.mcp.protocol.McpJsonObject.class,
						response.result());
		Assertions.assertEquals(
				new com.soklet.internal.mcp.protocol.McpJsonString(expectedResultType),
				result.members().get("resultType"));
		Assertions.assertFalse(result.members().containsKey("error"));
		Assertions.assertFalse(result.members().containsKey("method"));
		if (result.members().containsKey("_meta"))
			Assertions.assertInstanceOf(
					com.soklet.internal.mcp.protocol.McpJsonObject.class,
					result.members().get("_meta"));
		return response;
	}

	private static McpJsonRpcEnvelopeCodec codec() {
		return new McpJsonRpcEnvelopeCodec(
				new McpJsonCodec(McpJsonLimits.productionDefaults()));
	}

	private static McpJsonRpcId idFromJson(String idJson) {
		McpJsonRpcEnvelope.ResultResponse response = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.ResultResponse.class,
				codec().decode("{\"jsonrpc\":\"2.0\",\"id\":" + idJson
						+ ",\"result\":{}}"));
		return response.id();
	}

	private static void assertGolden(String actual, String fixtureName)
			throws Exception {
		Path path = GOLDEN_ROOT.resolve(fixtureName);
		Assertions.assertTrue(Files.isRegularFile(
				path, LinkOption.NOFOLLOW_LINKS), fixtureName);
		Assertions.assertFalse(Files.isSymbolicLink(path), fixtureName);
		String expected = Files.readString(path, StandardCharsets.UTF_8);
		if (fixtureName.endsWith(".json"))
			expected = jsonFromJsonFixture(expected, fixtureName);
		else if (fixtureName.endsWith(".sse.hex"))
			expected = sseFromHexFixture(expected, fixtureName);
		Assertions.assertEquals(expected, actual, fixtureName);
	}

	private static String jsonFromJsonFixture(String fixture, String filename) {
		Assertions.assertTrue(fixture.endsWith("\n"), filename);
		String json = fixture.substring(0, fixture.length() - 1);
		Assertions.assertFalse(json.contains("\n"), filename);
		return json;
	}

	private static String sseFromHexFixture(String fixture, String filename) {
		Assertions.assertTrue(fixture.endsWith("\n"), filename);
		String hex = fixture.substring(0, fixture.length() - 1);
		Assertions.assertFalse(hex.contains("\n"), filename);
		Assertions.assertTrue(hex.matches("[0-9a-f]+"), filename);
		Assertions.assertEquals(0, hex.length() % 2, filename);
		byte[] bytes = HexFormat.of().parseHex(hex);
		String sse = new String(bytes, StandardCharsets.UTF_8);
		Assertions.assertArrayEquals(bytes,
				sse.getBytes(StandardCharsets.UTF_8), filename);
		return sse;
	}

	private static String jsonFromSseFixture(String fixture, String filename) {
		Assertions.assertTrue(fixture.startsWith("data: "), filename);
		Assertions.assertTrue(fixture.endsWith("\n\n"), filename);
		String json = fixture.substring("data: ".length(), fixture.length() - 2);
		Assertions.assertFalse(json.contains("\n"), filename);
		return json;
	}

	private static McpCompleteResult completeTool(String text, String authority) {
		return McpCompleteResult.fromToolText(text)
				.withMetadata(metadata(authority));
	}

	private static McpCompleteResult completePrompt(String text,
			String authority) {
		return McpCompleteResult.fromPromptOutput(
				McpPromptOutput.fromMessages(
						McpPromptMessage.fromUserContent(
								McpTextContent.fromText(text))))
				.withMetadata(metadata(authority));
	}

	private static McpCompleteResult completeResource(URI uri, String text,
			String authority) {
		return McpCompleteResult.fromResourceOutput(
				McpResourceOutput.builder()
						.content(McpTextResourceContents
								.withUriAndText(uri, text)
								.mimeType("text/plain")
								.build())
						.build())
				.withMetadata(metadata(authority));
	}

	private static McpInputRequiredResult inputRequiredRoots(
			McpInputRequestDeclaration roots, String authority,
			boolean includeFrameworkState) {
		McpInputRequiredResult.Builder builder = McpInputRequiredResult.builder()
				.inputRequest("roots", McpInputRequest.fromDeclaration(
						roots, McpJsonObject.emptyInstance()))
				.metadata(metadata(authority));
		if (includeFrameworkState)
			builder.frameworkRequestState(McpJsonObject.builder()
					.put("phase", authority)
					.build());
		return builder.build();
	}

	private static McpJsonObject metadata(String authority) {
		return McpJsonObject.builder()
				.put("resultEnvelopeAuthority", authority)
				.build();
	}

	private static McpEndpoint.Builder endpointBuilder(String name) {
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						name, "4.0.0-SNAPSHOT").build());
	}

	private static McpEndpoint.Builder localizedEndpointBuilder(String name) {
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						name, "4.0.0-SNAPSHOT")
						.title("Canonical result title")
						.description("Canonical result description")
						.build());
	}

	private static McpLocalizer frenchLocalizer() {
		return McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> McpLocalizationContext
						.withLocale(Locale.FRENCH)
						.localizer(text -> McpLocalizationResult.localized(
								"FR:" + text.getDefaultText()))
						.build())
				.build();
	}

	private static McpServer.Builder serverBuilder(McpEndpoint endpoint) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static Set<String> expectedFixtures() {
		Set<String> fixtures = new LinkedHashSet<>();
		for (Set<String> methodFixtures : COMPLETE_METHOD_FIXTURES.values())
			fixtures.addAll(methodFixtures);
		for (Set<String> methodFixtures
				: INPUT_REQUIRED_METHOD_FIXTURES.values())
			fixtures.addAll(methodFixtures);
		return Set.copyOf(fixtures);
	}

	private static Set<String> runtimeMethodInventory() throws Exception {
		String source = Files.readString(Path.of("src", "main", "java", "com",
				"soklet", "DefaultMcpServer.java"), StandardCharsets.UTF_8);
		Matcher initializer = Pattern.compile(
				"BOUNDED_METRIC_METHODS\\s*=\\s*Set\\.of\\((.*?)\\);",
				Pattern.DOTALL).matcher(source);
		Assertions.assertTrue(initializer.find(),
				"missing BOUNDED_METRIC_METHODS initializer");
		String initializerSource = initializer.group(1);
		Assertions.assertFalse(initializer.find(),
				"duplicate BOUNDED_METRIC_METHODS initializer");
		Matcher string = Pattern.compile("\"([^\"]+)\"")
				.matcher(initializerSource);
		Set<String> methods = new LinkedHashSet<>();
		while (string.find())
			Assertions.assertTrue(methods.add(string.group(1)), string.group(1));
		return Set.copyOf(methods);
	}

	private static int occurrences(String source, String target) {
		int count = 0;
		int offset = 0;
		while ((offset = source.indexOf(target, offset)) >= 0) {
			count++;
			offset += target.length();
		}
		return count;
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private record TypedArguments(String mode) {
	}

	private record TypedResult(String value) {
	}

	private static final class DeterministicProtector
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
						"The result-envelope protector supports one state.");
			return FRAMEWORK_STATE;
		}

		@Override
		public byte[] open(McpRequestStateProtectionContext context,
				String protectedState)
				throws McpRequestStateProtectionException {
			this.opens.incrementAndGet();
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
