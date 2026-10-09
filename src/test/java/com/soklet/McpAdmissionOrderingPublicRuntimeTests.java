/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class McpAdmissionOrderingPublicRuntimeTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(McpProtocolVersion.V2025_06_18,
			McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);

	@Test
	void rejectedKnownUnknownAndDescriptorInvalidTargetsHaveTheSameChallenge() throws Exception {
		try (Fixture fixture = new Fixture()) {
			for (McpProtocolVersion version : VERSIONS) {
				String expected = null;
				for (List<String> call : List.of(
						List.of("tools/call", "known-tool", "\"name\":\"known-tool\",\"arguments\":{}"),
						List.of("tools/call", "absent-tool", "\"name\":\"absent-tool\",\"arguments\":{}"),
						List.of("prompts/get", "known-prompt", "\"name\":\"known-prompt\",\"arguments\":{\"subject\":\"x\"}"),
						List.of("prompts/get", "absent-prompt", "\"name\":\"absent-prompt\",\"arguments\":{}"),
						List.of("prompts/get", "known-prompt", "\"name\":\"known-prompt\",\"arguments\":{}"),
						List.of("prompts/get", "known-prompt", "\"name\":\"known-prompt\",\"arguments\":{\"unknown\":\"x\"}"),
						List.of("resources/read", "test://catalog/known", "\"uri\":\"test://catalog/known\""),
						List.of("resources/read", "test://items/42", "\"uri\":\"test://items/42\""),
						List.of("resources/read", "test://absent/unknown", "\"uri\":\"test://absent/unknown\""))) {
					HttpResponse<String> response = fixture.send(version, call.get(0), call.get(1), call.get(2));
					assertEquals(401, response.statusCode(), response.body());
					assertEquals("Bearer realm=\"catalog\"", response.headers().firstValue("WWW-Authenticate").orElseThrow());
					if (expected == null) expected = response.body();
					else assertEquals(expected, response.body());
				}
			}
			assertEquals(27, fixture.admissions.get());
			assertEquals(0, fixture.handlers.get());
			assertEquals(0, fixture.charges.get());
			assertEquals(0, fixture.requestCharges.get());
		}
	}

	@Test
	void acceptedTargetsKeepTheirRevisionSpecificSuccessAndMissingErrors() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.accepted.set(true);
			for (McpProtocolVersion version : VERSIONS) {
				for (List<String> call : List.of(
						List.of("tools/call", "known-tool", "\"name\":\"known-tool\",\"arguments\":{}"),
						List.of("prompts/get", "known-prompt", "\"name\":\"known-prompt\",\"arguments\":{\"subject\":\"x\"}"),
						List.of("resources/read", "test://catalog/known", "\"uri\":\"test://catalog/known\""),
						List.of("resources/read", "test://items/42", "\"uri\":\"test://items/42\""))) {
					HttpResponse<String> response = fixture.send(version, call.get(0), call.get(1), call.get(2));
					assertEquals(200, response.statusCode(), response.body());
					assertTrue(response.body().contains("\"result\":"), response.body());
				}
				HttpResponse<String> prompt = fixture.send(version, "prompts/get", "known-prompt", "\"name\":\"known-prompt\"");
				assertEquals(version == McpProtocolVersion.V2026_07_28 ? 400 : 200, prompt.statusCode());
				assertTrue(prompt.body().contains("\"code\":-32602"), prompt.body());
				HttpResponse<String> resource = fixture.send(version, "resources/read", "test://absent/unknown", "\"uri\":\"test://absent/unknown\"");
				assertTrue(resource.body().contains("\"code\":" + (version == McpProtocolVersion.V2026_07_28 ? -32602 : -32002)), resource.body());
			}
			assertEquals(18, fixture.admissions.get());
			assertEquals(12, fixture.handlers.get());
		}
	}

	@Test
	void rejectedCallersCannotProbeRequiredCapabilitiesOrCustomMirroredHeaders() throws Exception {
		try (Fixture fixture = new Fixture()) {
			McpProtocolVersion version = McpProtocolVersion.V2026_07_28;
			for (String name : List.of("mirrored-tool", "absent-tool")) {
				HttpResponse<String> response = fixture.send(version, "tools/call", name,
						"\"name\":\"" + name + "\",\"arguments\":{\"tenant\":\"acme\"}", Map.of("Mcp-Param-Tenant", "other"));
				assertEquals(401, response.statusCode(), response.body());
			}
			HttpResponse<String> required = fixture.send(version, "tools/call", "required-tool",
					"\"name\":\"required-tool\",\"arguments\":{}");
			assertEquals(401, required.statusCode(), required.body());
			assertEquals(3, fixture.admissions.get());
			assertEquals(0, fixture.charges.get());
			assertEquals(0, fixture.requestCharges.get());
			fixture.accepted.set(true);
			HttpResponse<String> accepted = fixture.send(version, "tools/call", "mirrored-tool",
					"\"name\":\"mirrored-tool\",\"arguments\":{\"tenant\":\"acme\"}", Map.of("Mcp-Param-Tenant", "other"));
			assertTrue(accepted.body().contains("\"code\":-32020"), accepted.body());
			HttpResponse<String> missingCapability = fixture.send(version, "tools/call", "required-tool",
					"\"name\":\"required-tool\",\"arguments\":{}");
			assertTrue(missingCapability.body().contains("\"code\":-32021"), missingCapability.body());
			assertEquals(5, fixture.admissions.get());
			assertEquals(0, fixture.handlers.get());
		}
	}

	@Test
	void unsupportedVersionsCannotProbeRegisteredCustomHeaders() throws Exception {
		for (McpUnknownMirroredHeaderPolicy policy : McpUnknownMirroredHeaderPolicy.values())
			try (Fixture fixture = new Fixture(policy)) {
				String expected = null;
				for (String name : List.of("mirrored-tool", "absent-tool")) {
					HttpResponse<String> response = fixture.send("2099-01-01", "tools/call", name,
							"\"name\":\"" + name + "\",\"arguments\":{\"tenant\":\"acme\"}",
							Map.of("Mcp-Param-Tenant", "other"));
					assertEquals(400, response.statusCode(), response.body());
					assertTrue(response.body().contains("\"code\":" + -32022), response.body());
					if (expected == null) expected = response.body();
					else assertEquals(expected, response.body());
				}
				assertEquals(0, fixture.admissions.get());
				assertEquals(0, fixture.handlers.get());
				assertEquals(0, fixture.charges.get());
				assertEquals(0, fixture.requestCharges.get());
			}
	}

	@Test
	void wireShapeFailuresStillPrecedeAdmission() throws Exception {
		try (Fixture fixture = new Fixture()) {
			for (McpProtocolVersion version : VERSIONS)
				for (List<String> call : List.of(List.of("tools/call", "", "\"name\":42"),
						List.of("prompts/get", "known-prompt", "\"name\":\"known-prompt\",\"arguments\":{\"subject\":42}"),
						List.of("resources/read", "relative", "\"uri\":\"relative\""))) {
					HttpResponse<String> response = fixture.send(version, call.get(0), call.get(1), call.get(2));
					assertTrue(response.body().contains("\"code\":-32602") || response.body().contains("\"code\":-32020"), response.body());
				}
			assertEquals(0, fixture.admissions.get());
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final AtomicBoolean accepted = new AtomicBoolean();
		private final AtomicInteger admissions = new AtomicInteger();
		private final AtomicInteger handlers = new AtomicInteger();
		private final AtomicInteger charges = new AtomicInteger();
		private final AtomicInteger requestCharges = new AtomicInteger();
		private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
		private final McpServer server;
		private final Soklet owner;

		Fixture() {
			this(McpUnknownMirroredHeaderPolicy.IGNORE);
		}

		Fixture(McpUnknownMirroredHeaderPolicy unknownMirroredHeaderPolicy) {
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("known-tool", VERSIONS).jsonObjectArguments()
					.handler((requestContext, arguments, invocationFeatures) -> { handlers.incrementAndGet(); return McpCompleteResult.fromToolText("ok"); }).build();
			McpPromptRegistration prompt = McpPromptRegistration.withName("known-prompt", VERSIONS)
					.handler((requestContext, promptGetContext, invocationFeatures) -> { handlers.incrementAndGet(); return McpCompleteResult.fromPromptOutput(McpPromptOutput.builder().messages(List.of(McpPromptMessage.fromUserText("ok"))).build()); })
					.arguments(List.of(McpPromptArgumentDeclaration.withName("subject").required(true).build())).build();
			McpResourceRegistration exact = McpResourceRegistration.withUriAndName(URI.create("test://catalog/known"), "exact", VERSIONS)
					.handler((requestContext, resourceReadContext, invocationFeatures) -> resourceResult(resourceReadContext)).build();
			McpResourceRegistration template = McpResourceRegistration.withUriTemplateAndName("test://items/{id}", "template", VERSIONS)
					.handler((requestContext, resourceReadContext, invocationFeatures) -> resourceResult(resourceReadContext)).build();
			McpToolRegistration<Tenant> mirrored = McpToolRegistration.withName("mirrored-tool", Set.of(McpProtocolVersion.V2026_07_28))
					.argumentType(Tenant.class).handler((requestContext, arguments, invocationFeatures) -> { handlers.incrementAndGet(); return McpCompleteResult.fromToolText("ok"); }).build();
			McpToolRegistration<McpJsonObject> required = McpToolRegistration.withName("required-tool", Set.of(McpProtocolVersion.V2026_07_28)).jsonObjectArguments()
					.handler((requestContext, arguments, invocationFeatures) -> { handlers.incrementAndGet(); return McpCompleteResult.fromToolText("ok"); })
					.inputRequestDeclarations(List.of(McpInputRequestDeclaration.fromElicitationForm(McpInputRequirement.REQUIRED))).build();
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("admission-test", "1").build(), VERSIONS)
					.toolRegistrations(List.of(tool, mirrored, required)).promptRegistrations(List.of(prompt)).resourceRegistrations(List.of(exact, template)).build();
			McpAdmissionRejection rejection = McpAdmissionRejection.withStatusCodeAndError(401, McpJsonRpcError.fromApplication(-31903, "Authentication required"))
					.addHeader("WWW-Authenticate", "Bearer realm=\"catalog\"").build();
			server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
					.unknownMirroredHeaderPolicy(unknownMirroredHeaderPolicy)
					.admissionController(context -> { admissions.incrementAndGet(); return accepted.get() ? McpAdmissionDecision.accepted() : McpAdmissionDecision.rejected(rejection); })
					.requestRateLimiter(context -> { requestCharges.incrementAndGet(); return McpRateLimitDecision.allowed(); })
					.toolRateLimiter(context -> { charges.incrementAndGet(); return McpRateLimitDecision.allowed(); }).build();
			owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(10))
							.startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.lifecycleObserver(new LifecycleObserver() {}).build());
			owner.start();
		}

		private McpCompleteResult resourceResult(McpResourceReadContext context) {
			handlers.incrementAndGet();
			return McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(McpTextResourceContents.withUriAndText(context.getUri(), "ok").build()).build());
		}

		HttpResponse<String> send(McpProtocolVersion version, String method, String name, String fields) throws Exception {
			return send(version, method, name, fields, Map.of());
		}

		HttpResponse<String> send(McpProtocolVersion version, String method, String name, String fields, Map<String, String> headers) throws Exception {
			return send(version.getWireValue(), method, name, fields, headers);
		}

		HttpResponse<String> send(String protocolVersion, String method, String name, String fields, Map<String, String> headers) throws Exception {
			boolean modern = !protocolVersion.startsWith("2025-");
			String metadata = modern ? "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + protocolVersion + "\",\"io.modelcontextprotocol/clientCapabilities\":{}}," : "";
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"same\",\"method\":\"" + method + "\",\"params\":{" + metadata + fields + "}}";
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getDiagnostics().getBoundAddress().orElseThrow().getPort() + "/mcp"))
					.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
					.header("MCP-Protocol-Version", protocolVersion);
			if (modern) { request.header("Mcp-Method", method); if (!name.isEmpty()) request.header("Mcp-Name", name); }
			headers.forEach(request::header);
			return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		}

		@Override public void close() { owner.close(); }
	}

	private record Tenant(@com.soklet.annotation.McpHeader(name = "Tenant") String tenant) {}
}
