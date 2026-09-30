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

package com.soklet.conformance.legacy;

import com.soklet.CorsAuthorizer;
import com.soklet.McpAbsentOriginPolicy;
import com.soklet.McpAdmissionController;
import com.soklet.McpBlobResourceContents;
import com.soklet.McpCompleteResult;
import com.soklet.McpEmbeddedResource;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImageContent;
import com.soklet.McpImplementation;
import com.soklet.McpPromptArgumentDeclaration;
import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpProtocolVersion;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRateLimiter;
import com.soklet.McpResourceContents;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpTextContent;
import com.soklet.McpTextResourceContents;
import com.soklet.McpToolRegistration;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public-API-only fixture for a bounded 2025 synchronous tool, prompt, and resource conformance subset.
 * Each process serves exactly one requested revision and exits on stdin EOF.
 */
public final class McpLegacyConformanceFixture {
	private static final String HOST = "127.0.0.1";
	private static final String PATH = "/mcp";
	private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl7r94AAAAASUVORK5CYII=");

	private McpLegacyConformanceFixture() {
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 2 || !"--version".equals(arguments[0]))
			throw new IllegalArgumentException(
					"Usage: McpLegacyConformanceFixture --version <2025-06-18|2025-11-25>");
		McpProtocolVersion version = switch (arguments[1]) {
			case "2025-06-18" -> McpProtocolVersion.V2025_06_18;
			case "2025-11-25" -> McpProtocolVersion.V2025_11_25;
			default -> throw new IllegalArgumentException(
					"Unsupported legacy fixture revision: " + arguments[1]);
		};
		Set<McpProtocolVersion> versions = Set.of(version);
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion(
						"soklet-legacy-conformance", "4.0.0")
						.description("Bounded legacy tool, prompt, and resource fixture")
						.build(), versions)
				.toolRegistrations(List.of(
					McpToolRegistration.withName("test_simple_text", versions)
							.jsonObjectArguments()
							.handler((requestContext, argumentsContext,
									invocationFeatures) ->
									McpCompleteResult.fromToolText(
											"This is a simple text response for testing."))
							.description("Returns deterministic text content.")
							.build(),
					McpToolRegistration.withName("test_error_handling", versions)
							.jsonObjectArguments()
							.handler((requestContext, argumentsContext,
									invocationFeatures) ->
									McpCompleteResult.fromToolErrorText(
											"This tool intentionally returns an error for testing"))
							.description("Returns a deterministic application-level error.")
							.build()))
				.promptRegistrations(prompts(versions))
				.resourceRegistrations(resources(versions))
				.build();
		AtomicInteger boundPort = new AtomicInteger(-1);
		CorsAuthorizer corsAuthorizer = CorsAuthorizer.fromWhitelistAuthorizer(
				origin -> origin.equals("http://" + HOST + ":" + boundPort.get()));
		McpRateLimiter allowLimiter = context -> McpRateLimitDecision.allowed();
		McpServer server = McpServer.withPort(0)
				.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(allowLimiter)
				.toolRateLimiter(allowLimiter)
				.corsAuthorizer(corsAuthorizer)
				.absentOriginPolicy(McpAbsentOriginPolicy.ALLOW)
				.allowedHosts(Set.of(HOST))
				.build();
		SokletConfig configuration = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();

		try (Soklet soklet = Soklet.fromConfig(configuration)) {
			soklet.start();
			InetSocketAddress address = server.getDiagnostics().getBoundAddress()
					.orElseThrow();
			if (!address.getAddress().isLoopbackAddress())
				throw new IllegalStateException("Fixture escaped loopback.");
			boundPort.set(address.getPort());
			System.out.println("{\"format\":1,\"event\":\"ready\",\"host\":\""
					+ HOST + "\",\"port\":" + address.getPort()
					+ ",\"path\":\"" + PATH + "\",\"revision\":\""
					+ version.getWireValue() + "\"}");
			System.out.flush();
			while (System.in.read() >= 0) {
				// EOF from the supervised runner requests graceful shutdown.
			}
			soklet.shutdown();
			soklet.awaitShutdown();
		}
		System.out.println("{\"format\":1,\"event\":\"stopped\",\"clean\":true}");
		System.out.flush();
	}

	private static List<McpPromptRegistration> prompts(Set<McpProtocolVersion> versions) {
		return List.of(
				McpPromptRegistration.withName("test_simple_prompt", versions)
						.handler((requestContext, promptContext, invocationFeatures) ->
								completePrompt(McpPromptMessage.fromUserContent(
										McpTextContent.fromText("This is a simple prompt for testing."))))
						.description("Returns a deterministic simple prompt.")
						.build(),
				McpPromptRegistration.withName("test_prompt_with_arguments", versions)
						.handler((requestContext, promptContext, invocationFeatures) ->
								completePrompt(McpPromptMessage.fromUserContent(McpTextContent.fromText(
										"Prompt with arguments: arg1='"
												+ promptContext.findArgument("arg1").orElseThrow()
												+ "', arg2='" + promptContext.findArgument("arg2").orElseThrow() + "'"))))
						.description("Substitutes two required string arguments.")
						.arguments(List.of(requiredArgument("arg1"), requiredArgument("arg2")))
						.build(),
				McpPromptRegistration.withName("test_prompt_with_embedded_resource", versions)
						.handler((requestContext, promptContext, invocationFeatures) ->
								completePrompt(McpPromptMessage.fromUserContent(McpEmbeddedResource
										.withResource(McpTextResourceContents.withUriAndText(
												URI.create(promptContext.findArgument("resourceUri").orElseThrow()),
												"Embedded resource content for testing.").mimeType("text/plain").build())
										.build())))
						.description("Embeds the requested text resource.")
						.arguments(List.of(requiredArgument("resourceUri")))
						.build(),
				McpPromptRegistration.withName("test_prompt_with_image", versions)
						.handler((requestContext, promptContext, invocationFeatures) ->
								completePrompt(McpPromptMessage.fromUserContent(
										McpImageContent.withDataAndMimeType(PNG_BYTES, "image/png").build()),
										McpPromptMessage.fromUserContent(McpTextContent.fromText("Please analyze the image above."))))
						.description("Returns deterministic image prompt content.")
						.build());
	}

	private static McpPromptArgumentDeclaration requiredArgument(String name) {
		return McpPromptArgumentDeclaration.withName(name).required(true).build();
	}

	private static McpCompleteResult completePrompt(McpPromptMessage... messages) {
		return McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages(messages));
	}

	private static List<McpResourceRegistration> resources(Set<McpProtocolVersion> versions) {
		return List.of(
				McpResourceRegistration.withUriAndName(URI.create("test://static-text"), "Static text resource", versions)
						.handler((requestContext, resourceReadContext, invocationFeatures) ->
								completeResource(McpTextResourceContents.withUriAndText(resourceReadContext.getUri(),
										"This is the content of the static text resource.").mimeType("text/plain").build()))
						.description("A deterministic UTF-8 text resource.").mimeType("text/plain")
						.build(),
				McpResourceRegistration.withUriAndName(URI.create("test://static-binary"), "Static binary resource", versions)
						.handler((requestContext, resourceReadContext, invocationFeatures) ->
								completeResource(McpBlobResourceContents.withUriAndData(resourceReadContext.getUri(),
										PNG_BYTES).mimeType("image/png").build()))
						.description("A deterministic PNG resource.").mimeType("image/png")
						.build(),
				McpResourceRegistration.withUriTemplateAndName("test://template/{id}/data", "Template data resource", versions)
						.handler((requestContext, resourceReadContext, invocationFeatures) ->
								completeResource(McpTextResourceContents.withUriAndText(resourceReadContext.getUri(),
										"Data for ID: " + resourceReadContext.getUriTemplateVariables().get("id"))
										.mimeType("text/plain").build()))
						.description("A deterministic RFC 6570 Level 1 template.").mimeType("text/plain")
						.build());
	}

	private static McpCompleteResult completeResource(McpResourceContents contents) {
		return McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(contents).build());
	}
}
