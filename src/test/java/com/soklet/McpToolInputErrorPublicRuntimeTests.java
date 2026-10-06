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

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Revision-specific validation results through the public, real-listener pipeline. */
@Timeout(30)
class McpToolInputErrorPublicRuntimeTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);
	private static final String SAFE_MESSAGE = "Arguments do not match the tool's inputSchema.";
	private static final String SECRET = "private-input-or-exception-secret";

	@TestFactory
	Stream<DynamicTest> toolInputFailuresFollowTheSelectedRevision() {
		return VERSIONS.stream().map(version -> DynamicTest.dynamicTest(version.getWireValue(),
				() -> verifyValidationAndRecovery(version)));
	}

	private void verifyValidationAndRecovery(McpProtocolVersion version) throws Exception {
		AtomicInteger handlerCalls = new AtomicInteger();
		AtomicInteger sanitizations = new AtomicInteger();
		AtomicInteger interceptions = new AtomicInteger();
		AtomicBoolean sanitizerFails = new AtomicBoolean();
		McpToolRegistration<Arguments> typed = McpToolRegistration.withName("typed", VERSIONS)
				.argumentAndOutputTypes(Arguments.class, Answer.class)
				.handler((requestContext, arguments, invocationFeatures) -> {
					handlerCalls.incrementAndGet();
					return new Answer(arguments.getConvertedArguments().count());
				}).build();
		McpToolRegistration<McpJsonObject> authored = McpToolRegistration.withName("authored", VERSIONS)
				.inputSchema(McpJsonObject.builder().put("type", "object")
						.put("properties", McpJsonObject.builder().put("count", McpJsonObject.builder()
								.put("type", "integer").put("minimum", 1).put("maximum", 2).build()).build())
						.put("required", McpJsonArray.builder().add("count").build())
						.put("additionalProperties", false).build())
				.handler((requestContext, arguments, invocationFeatures) -> {
					handlerCalls.incrementAndGet();
					return McpCompleteResult.fromToolText("accepted");
				}).build();
		McpToolRegistration<McpJsonObject> applicationFailure = McpToolRegistration.withName("application", VERSIONS)
				.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) -> {
					throw new IllegalArgumentException(SECRET);
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("tool-input-error-test", "1").build(), VERSIONS)
				.toolRegistrations(List.of(typed, authored, applicationFailure)).build();
		McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed())
				.handlerInterceptor((requestContext, invocationFeatures, continuation) -> {
					interceptions.incrementAndGet();
					return continuation.proceed();
				})
				.toolResultSanitizer((requestContext, toolName, rawArguments, result) -> {
					sanitizations.incrementAndGet();
					McpToolOutput output = assertInstanceOf(McpToolOutput.class, result.getPayload());
					if (output.isError()) {
						assertEquals(List.of(McpTextContent.fromText(SAFE_MESSAGE)), output.getContent());
						assertTrue(output.getStructuredContent().isEmpty());
						assertEquals(McpJsonObject.emptyInstance(), result.getMetadata());
					}
					if (sanitizerFails.get())
						throw new IllegalStateException(SECRET);
					return result;
				}).build();
		try (Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build())) {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			int invalidCalls = 0;
			for (String arguments : List.of("{}", "{\"count\":\"" + SECRET + "\"}",
					"{\"count\":2147483648}", "{\"count\":1,\"" + SECRET + "\":true}",
					"{\"count\":1,\"note\":null}", "{\"count\":7}")) {
				assertInputFailure(call(port, version, "invalid", "typed", arguments), version);
				invalidCalls++;
			}
			// An omitted arguments member is a valid CallToolRequest envelope;
			// required properties are still checked against the tool's schema.
			assertInputFailure(call(port, version, "omitted", "typed", null), version);
			invalidCalls++;
			for (String arguments : List.of("{}", "{\"count\":0}", "{\"count\":3}",
					"{\"count\":\"" + SECRET + "\"}", "{\"count\":1,\"" + SECRET + "\":true}")) {
				assertInputFailure(call(port, version, "invalid", "authored", arguments), version);
				invalidCalls++;
			}
			assertEquals(0, handlerCalls.get());
			assertEquals(invalidCalls, interceptions.get());
			assertEquals(version == McpProtocolVersion.V2025_06_18 ? 0 : invalidCalls, sanitizations.get());

			int beforeMalformed = interceptions.get();
			for (String arguments : List.of("[]", "null", "1"))
				assertProtocolFailure(call(port, version, "malformed", "typed", arguments), version, -32602);
			assertProtocolFailure(call(port, version, "unknown", "absent", "{}"), version, -32602);
			assertProtocolFailure(call(port, version, "missing-name", null, "{}"), version,
					version == McpProtocolVersion.V2026_07_28 ? -32020 : -32602);
			assertEquals(beforeMalformed, interceptions.get());
			assertEquals(0, handlerCalls.get());

			Response application = call(port, version, "application", "application", "{}");
			assertEquals(version == McpProtocolVersion.V2026_07_28 ? 500 : 200, application.status(), application.body());
			assertTrue(application.body().contains("\"code\":-32603"), application.body());
			assertFalse(application.body().contains(SECRET), application.body());
			assertFalse(application.body().contains("\"isError\""), application.body());

			if (version != McpProtocolVersion.V2025_06_18) {
				sanitizerFails.set(true);
				Response failedSanitizer = call(port, version, "sanitizer-failure", "typed", "{}");
				assertEquals(version == McpProtocolVersion.V2026_07_28 ? 500 : 200,
						failedSanitizer.status(), failedSanitizer.body());
				assertTrue(failedSanitizer.body().contains("\"code\":-32603"), failedSanitizer.body());
				assertFalse(failedSanitizer.body().contains(SECRET), failedSanitizer.body());
				assertFalse(failedSanitizer.body().contains(SAFE_MESSAGE), failedSanitizer.body());
				sanitizerFails.set(false);
			}
			Response recovered = call(port, version, "recovered", "typed", "{\"count\":1,\"note\":\"exact\"}");
			assertEquals(200, recovered.status(), recovered.body());
			assertTrue(recovered.body().contains("\"structuredContent\":{\"count\":1}"), recovered.body());
			Response validAuthored = call(port, version, "authored-ok", "authored", "{\"count\":2}");
			assertEquals(200, validAuthored.status(), validAuthored.body());
			assertTrue(validAuthored.body().contains("\"text\":\"accepted\""), validAuthored.body());
			assertEquals(2, handlerCalls.get());
		}
	}

	private static Response call(int port, McpProtocolVersion version, String id,
			String name, String arguments) throws Exception {
		List<String> fields = new ArrayList<>();
		if (name != null)
			fields.add("\"name\":\"" + name + "\"");
		if (arguments != null)
			fields.add("\"arguments\":" + arguments);
		if (version == McpProtocolVersion.V2026_07_28)
			fields.add("\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{}}");
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{" + String.join(",", fields) + "}}";
		List<HeaderValue> headers = new ArrayList<>();
		headers.add(new HeaderValue("MCP-Protocol-Version", version.getWireValue()));
		if (version == McpProtocolVersion.V2026_07_28) {
			headers.add(new HeaderValue("Mcp-Method", "tools/call"));
			if (name != null)
				headers.add(new HeaderValue("Mcp-Name", name));
		}
		try (RawClient client = new RawClient(port, "POST", "/mcp", body, headers)) {
			Head head = client.readHead();
			return new Response(head.status(), client.readBody(head));
		}
	}

	private static void assertInputFailure(Response response, McpProtocolVersion version) {
		if (version == McpProtocolVersion.V2025_06_18) {
			assertProtocolFailure(response, version, -32602);
			return;
		}
		assertEquals(200, response.status(), response.body());
		assertFalse(response.body().contains("\"error\""), response.body());
		assertTrue(response.body().contains("\"isError\":true"), response.body());
		assertTrue(response.body().contains("\"content\":[{\"type\":\"text\",\"text\":\"" + SAFE_MESSAGE + "\"}]"), response.body());
		assertEquals(version == McpProtocolVersion.V2026_07_28,
				response.body().contains("\"resultType\":\"complete\""), response.body());
		assertFalse(response.body().contains("\"structuredContent\""), response.body());
		assertFalse(response.body().contains(SECRET), response.body());
	}

	private static void assertProtocolFailure(Response response, McpProtocolVersion version, int code) {
		assertEquals(version == McpProtocolVersion.V2026_07_28 ? 400 : 200, response.status(), response.body());
		assertTrue(response.body().contains("\"code\":" + code), response.body());
		assertFalse(response.body().contains("\"isError\""), response.body());
		assertFalse(response.body().contains(SECRET), response.body());
	}

	private record Arguments(int count, Optional<String> note) {
		Arguments {
			if (count == 7) {
				IllegalStateException exception = new IllegalStateException(SECRET, new RuntimeException(SECRET));
				exception.addSuppressed(new IllegalArgumentException(SECRET));
				throw exception;
			}
		}
	}
	private record Answer(int count) {}
	private record Response(int status, String body) {}
}
