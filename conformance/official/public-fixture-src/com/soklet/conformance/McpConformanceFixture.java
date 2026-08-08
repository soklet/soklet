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

package com.soklet.conformance;

import com.soklet.CorsAuthorizer;
import com.soklet.LifecycleObserver;
import com.soklet.McpAbsentOriginPolicy;
import com.soklet.McpAudioContent;
import com.soklet.McpBlobResourceContents;
import com.soklet.McpCachePolicy;
import com.soklet.McpCompleteResult;
import com.soklet.McpContentBlock;
import com.soklet.McpEmbeddedResource;
import com.soklet.McpEndpoint;
import com.soklet.McpHandlerResolver;
import com.soklet.McpImageContent;
import com.soklet.McpImplementation;
import com.soklet.McpInputRequest;
import com.soklet.McpInputRequestDeclaration;
import com.soklet.McpInputRequiredResult;
import com.soklet.McpInputRequirement;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcException;
import com.soklet.McpLocalSubscriptionEventPublisher;
import com.soklet.McpOfficialSchemaConformanceTool;
import com.soklet.McpPromptArgumentDefinition;
import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRateLimiter;
import com.soklet.McpRequestAdmissionPolicy;
import com.soklet.McpResourceContents;
import com.soklet.McpResourcePage;
import com.soklet.McpResourceRegistration;
import com.soklet.McpResourceOutput;
import com.soklet.McpServer;
import com.soklet.McpServerStatus;
import com.soklet.McpShutdownOutcome;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpTextContent;
import com.soklet.McpTextResourceContents;
import com.soklet.McpToolOutput;
import com.soklet.McpToolRegistration;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.annotation.McpHeader;
import com.soklet.annotation.McpToolArgument;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Candidate-artifact black-box fixture for a selected official MCP
 * conformance scenario.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class McpConformanceFixture {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final URI STATIC_TEXT_URI = URI.create("test://static-text");
	private static final URI STATIC_BINARY_URI =
			URI.create("test://static-binary");
	private static final String TEMPLATE_URI = "test://template/{id}/data";
	private static final McpCachePolicy CACHE_POLICY =
			McpCachePolicy.fromPublicTimeToLive(Duration.ofMinutes(5));
	private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl7r94AAAAASUVORK5CYII=");
	private static final byte[] WAV_BYTES = Base64.getDecoder().decode(
			"UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=");
	private static final Set<String> SUPPORTED_SCENARIOS = Set.of(
			"server-stateless",
			"tools-list",
			"tools-call-simple-text",
			"tools-call-image",
			"tools-call-audio",
			"tools-call-embedded-resource",
			"tools-call-mixed-content",
			"tools-call-error",
			"tools-call-with-progress",
			"json-schema-2020-12",
			"server-sse-multiple-streams",
			"resources-list",
			"resources-read-text",
			"resources-read-binary",
			"resources-templates-read",
			"sep-2164-resource-not-found",
			"prompts-list",
			"prompts-get-simple",
			"prompts-get-with-args",
			"prompts-get-embedded-resource",
			"prompts-get-with-image",
			"dns-rebinding-protection",
			"caching",
			"http-header-validation",
			"http-custom-header-server-validation");

	private McpConformanceFixture() {
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 2 || !"--scenario".equals(arguments[0])
				|| !SUPPORTED_SCENARIOS.contains(arguments[1]))
			throw new IllegalArgumentException(
					"Usage: McpConformanceFixture --scenario <supported scenario>");

		AtomicInteger effectivePort = new AtomicInteger(-1);
		AtomicReference<McpShutdownOutcome> shutdownOutcome =
				new AtomicReference<>();
		CorsAuthorizer corsAuthorizer = CorsAuthorizer.fromWhitelistAuthorizer(
				origin -> origin.equals("http://" + LOOPBACK + ":"
						+ effectivePort.get()));
		McpEndpoint endpoint = endpointForScenario(arguments[1]);
		McpRateLimiter allowLimiter = context ->
				McpRateLimitDecision.fromAllowed();
		McpServer mcpServer = McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance())
				.requestRateLimiter(allowLimiter)
				.toolRateLimiter(allowLimiter)
				.corsAuthorizer(corsAuthorizer)
				.absentOriginPolicy(McpAbsentOriginPolicy.ALLOW)
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		LifecycleObserver lifecycleObserver = new LifecycleObserver() {
			@Override
			public void didStopMcpServer(McpServer server,
					McpShutdownOutcome outcome) {
				shutdownOutcome.set(outcome);
			}
		};
		SokletConfig config = SokletConfig.withMcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			InetSocketAddress address = mcpServer.getDiagnostics().getBoundAddress()
					.orElseThrow(() -> new IllegalStateException(
							"The public MCP server did not publish its bound address."));
			if (!address.getAddress().isLoopbackAddress())
				throw new IllegalStateException(
						"The conformance fixture did not bind a loopback address.");
			effectivePort.set(address.getPort());
			writeControlLine("{\"format\":1,\"event\":\"ready\","
					+ "\"host\":\"" + LOOPBACK + "\",\"port\":"
					+ address.getPort() + ",\"path\":\"" + MCP_PATH + "\"}");

			while (System.in.read() >= 0) {
				// The parent owns this pipe. EOF is the graceful shutdown request.
			}
		}

		if (mcpServer.isStarted()
				|| mcpServer.getDiagnostics().getStatus()
				!= McpServerStatus.STOPPED
				|| shutdownOutcome.get() != McpShutdownOutcome.CLEAN)
			throw new IllegalStateException(
					"The public MCP conformance fixture did not shut down cleanly.");

		writeControlLine("{\"format\":1,\"event\":\"stopped\",\"clean\":true}");
	}

	private static McpEndpoint endpointForScenario(String scenario) {
		McpEndpoint.Builder builder = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"soklet-public-conformance", "3.6.0-SNAPSHOT")
						.description("Soklet MCP conformance fixture")
						.build())
				.includeServerInformation(true)
				.tools(tools(scenario))
				.prompts(prompts())
				.resources(resources())
				.resourceListHandler((request, list, features) -> {
					if (list.getCursor().isPresent())
						throw new McpJsonRpcException(
								McpJsonRpcError.fromInvalidParameters(
										"The resource-list cursor is invalid."));
					return McpResourcePage.builder()
							.resources(list.getRegisteredResourceDescriptors())
							.build();
				})
				.resourcesListCachePolicy(CACHE_POLICY)
				.resourceTemplatesListCachePolicy(CACHE_POLICY);
		if ("server-stateless".equals(scenario))
			builder.subscriptions(McpSubscriptionConfig.withEventPublisher(
					McpLocalSubscriptionEventPublisher.fromDefaults())
					.notificationType(
							McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)
					.notificationType(
							McpSubscriptionNotificationType.RESOURCE_UPDATED)
					.build());
		return builder.build();
	}

	private static List<McpToolRegistration<?>> tools(String scenario) {
		List<McpToolRegistration<?>> tools = new ArrayList<>(List.of(
				rawTool("test_simple_text",
						"Returns deterministic text content.",
						() -> McpCompleteResult.fromToolText(
								"This is a simple text response for testing.")),
				rawTool("test_image_content",
						"Returns deterministic image content.",
						() -> completeToolOutput(McpImageContent
								.withDataAndMimeType(PNG_BYTES, "image/png")
								.build())),
				rawTool("test_audio_content",
						"Returns deterministic audio content.",
						() -> completeToolOutput(McpAudioContent
								.withDataAndMimeType(WAV_BYTES, "audio/wav")
								.build())),
				rawTool("test_embedded_resource",
						"Returns deterministic embedded-resource content.",
						() -> completeToolOutput(embeddedTextResource(
								URI.create("test://embedded-resource"),
								"text/plain",
								"This is an embedded resource content."))),
				rawTool("test_multiple_content_types",
						"Returns deterministic mixed content.",
						McpConformanceFixture::mixedContentResult),
				rawTool("test_error_handling",
						"Returns a deterministic application-level tool error.",
						() -> McpCompleteResult.fromToolErrorText(
								"This tool intentionally returns an error for testing")),
				McpToolRegistration.withName("test_tool_with_progress")
						.jsonArguments()
						.handler((request, call, features) -> {
							McpProgressReporter reporter = features
									.find(McpProgressReporter.class)
									.orElseThrow(() -> new IllegalStateException(
											"The progress scenario omitted its progress token."));
							reporter.report(McpProgressUpdate.withProgress(0.0d)
									.total(100.0d).build());
							reporter.report(McpProgressUpdate.withProgress(50.0d)
									.total(100.0d).build());
							reporter.report(McpProgressUpdate.withProgress(100.0d)
									.total(100.0d).build());
							return McpCompleteResult.fromToolText(
									"Progress test completed.");
						})
						.description("Reports deterministic 0/50/100 progress.")
						.build(),
				McpOfficialSchemaConformanceTool.create(),
				McpToolRegistration.withName("test_custom_header")
						.argumentType(CustomHeaderArguments.class)
						.handler((request, call, features) ->
								McpCompleteResult.fromToolText(
										"Custom header accepted."))
						.description(
								"Validates one string-valued custom mirrored header.")
						.build()));
		if ("server-stateless".equals(scenario)) {
			McpInputRequestDeclaration sampling =
					McpInputRequestDeclaration.fromSampling(Set.of(),
							McpInputRequirement.REQUIRED);
			McpInputRequestDeclaration elicitation =
					McpInputRequestDeclaration.fromElicitationForm(
							McpInputRequirement.REQUIRED);
			McpJsonObject elicitationParameters = McpJsonObject.builder()
					.put("message", "Provide a conformance value")
					.put("requestedSchema", McpJsonObject.builder()
							.put("type", "object")
							.build())
					.build();
			tools.add(McpToolRegistration.withName("test_missing_capability")
					.jsonArguments()
					.handler((request, call, features) ->
							McpCompleteResult.fromToolText(
									"Sampling capability was declared."))
					.mayRequestInput(sampling)
					.description("Requires the base sampling capability.")
					.build());
			tools.add(McpToolRegistration.withName("test_streaming_elicitation")
					.jsonArguments()
					.handler((request, call, features) ->
							McpInputRequiredResult.builder()
									.inputRequest("conformance-value",
											McpInputRequest.fromDeclaration(
													elicitation,
													elicitationParameters))
									.build())
					.mayRequestInput(elicitation)
					.description("Returns one embedded elicitation input request.")
					.build());
			tools.add(rawTool("test_logging_tool",
					"Completes without emitting a log notification.",
					() -> McpCompleteResult.fromToolText(
							"No log notification was emitted.")));
		}
		return List.copyOf(tools);
	}

	private static McpToolRegistration<McpJsonObject> rawTool(String name,
			String description, Supplier<McpCompleteResult> resultSupplier) {
		return McpToolRegistration.withName(name)
				.jsonArguments()
				.handler((request, call, features) -> resultSupplier.get())
				.description(description)
				.build();
	}

	private static McpCompleteResult mixedContentResult() {
		return McpCompleteResult.fromToolOutput(McpToolOutput.builder()
				.content(McpTextContent.fromText("Multiple content types test:"))
				.content(McpImageContent.withDataAndMimeType(
						PNG_BYTES, "image/png").build())
				.content(embeddedTextResource(
						URI.create("test://mixed-content-resource"),
						"application/json", "{\"test\":\"data\",\"value\":123}"))
				.build());
	}

	private static McpCompleteResult completeToolOutput(
			McpContentBlock content) {
		return McpCompleteResult.fromToolOutput(
				McpToolOutput.builder().content(content).build());
	}

	private static McpEmbeddedResource embeddedTextResource(URI uri,
			String mimeType, String text) {
		return McpEmbeddedResource.withResource(McpTextResourceContents
				.withUriAndText(uri, text)
				.mimeType(mimeType)
				.build()).build();
	}

	private static List<McpPromptRegistration> prompts() {
		return List.of(
				McpPromptRegistration.withName("test_simple_prompt")
						.handler((request, prompt, features) -> completePrompt(
								McpPromptMessage.fromUserContent(
										McpTextContent.fromText(
												"This is a simple prompt for testing."))))
						.description("Returns a deterministic simple prompt.")
						.build(),
				McpPromptRegistration.withName("test_prompt_with_arguments")
						.handler((request, prompt, features) -> completePrompt(
								McpPromptMessage.fromUserContent(McpTextContent.fromText(
										"Prompt with arguments: arg1='"
												+ prompt.findArgument("arg1").orElseThrow()
												+ "', arg2='"
												+ prompt.findArgument("arg2").orElseThrow()
												+ "'"))))
						.description("Substitutes two required string arguments.")
						.argument(requiredPromptArgument("arg1",
								"First test argument"))
						.argument(requiredPromptArgument("arg2",
								"Second test argument"))
						.build(),
				McpPromptRegistration.withName(
						"test_prompt_with_embedded_resource")
						.handler((request, prompt, features) -> {
							URI uri = URI.create(prompt.findArgument(
									"resourceUri").orElseThrow());
							return completePrompt(
									McpPromptMessage.fromUserContent(
											embeddedTextResource(uri, "text/plain",
													"Embedded resource content for testing.")),
									McpPromptMessage.fromUserContent(
											McpTextContent.fromText(
													"Please process the embedded resource above.")));
						})
						.description("Embeds the requested text resource.")
						.argument(requiredPromptArgument("resourceUri",
								"URI of the resource to embed"))
						.build(),
				McpPromptRegistration.withName("test_prompt_with_image")
						.handler((request, prompt, features) -> completePrompt(
								McpPromptMessage.fromUserContent(McpImageContent
										.withDataAndMimeType(PNG_BYTES, "image/png")
										.build()),
								McpPromptMessage.fromUserContent(McpTextContent.fromText(
										"Please analyze the image above."))))
						.description("Returns deterministic image prompt content.")
						.build());
	}

	private static McpPromptArgumentDefinition requiredPromptArgument(
			String name, String description) {
		return McpPromptArgumentDefinition.withName(name)
				.description(description)
				.required(true)
				.build();
	}

	private static McpCompleteResult completePrompt(
			McpPromptMessage... messages) {
		return McpCompleteResult.fromPromptOutput(
				McpPromptOutput.fromMessages(messages));
	}

	private static List<McpResourceRegistration> resources() {
		return List.of(
				McpResourceRegistration.withUriAndName(
						STATIC_TEXT_URI, "Static text resource")
						.handler((request, resource, features) ->
								completeResource(McpTextResourceContents
										.withUriAndText(resource.getUri(),
												"This is the content of the static text resource.")
										.mimeType("text/plain")
										.build()))
						.description("A deterministic UTF-8 text resource.")
						.mimeType("text/plain")
						.cachePolicy(CACHE_POLICY)
						.build(),
				McpResourceRegistration.withUriAndName(
						STATIC_BINARY_URI, "Static binary resource")
						.handler((request, resource, features) ->
								completeResource(McpBlobResourceContents
										.withUriAndData(resource.getUri(), PNG_BYTES)
										.mimeType("image/png")
										.build()))
						.description("A deterministic PNG resource.")
						.mimeType("image/png")
						.cachePolicy(CACHE_POLICY)
						.build(),
				McpResourceRegistration.withUriTemplateAndName(
						TEMPLATE_URI, "Template data resource")
						.handler((request, resource, features) -> {
							String id = resource.getUriTemplateVariables()
									.get("id");
							String text = "{\"id\":\"" + id
									+ "\",\"templateTest\":true,\"data\":\"Data for ID: "
									+ id + "\"}";
							return completeResource(McpTextResourceContents
									.withUriAndText(resource.getUri(), text)
									.mimeType("application/json")
									.build());
						})
						.description("A deterministic RFC 6570 Level 1 template.")
						.mimeType("application/json")
						.cachePolicy(CACHE_POLICY)
						.build());
	}

	private static McpCompleteResult completeResource(
			McpResourceContents contents) {
		return McpCompleteResult.fromResourceOutput(
				McpResourceOutput.builder().content(contents).build());
	}

	private static void writeControlLine(String line) throws Exception {
		System.out.write((line + '\n').getBytes(StandardCharsets.UTF_8));
		System.out.flush();
	}

	/**
	 * Typed input used by the pinned custom mirrored-header scenario.
	 *
	 * @param value body value mirrored by {@code Mcp-Param-Value}
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public record CustomHeaderArguments(
			@McpToolArgument(description = "Mirrored test value")
			@McpHeader("Value") String value) {
	}
}
