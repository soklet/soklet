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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent JSON and schema ceilings through each supported wire revision. */
@Timeout(30)
class McpJsonAndSchemaLimitPublicRuntimeTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);
	private static final String SAFE_MESSAGE = "Arguments do not match the tool's inputSchema.";
	private static final String CANARY = "private-limit-payload-canary";

	@TestFactory
	Stream<DynamicTest> limitsFailSafelyAndRecoverForEveryRevision() {
		return VERSIONS.stream().map(protocolVersion -> DynamicTest.dynamicTest(
				protocolVersion.getWireValue(), () -> verifyLimits(protocolVersion)));
	}

	private void verifyLimits(McpProtocolVersion protocolVersion) throws Exception {
		AtomicInteger handlerCalls = new AtomicInteger();
		AtomicBoolean largeOutput = new AtomicBoolean(true);
		Map<String, Integer> wide = new LinkedHashMap<>();
		StringBuilder arguments = new StringBuilder("{");
		for (int index = 0; index < 60_000; ++index) {
			String key = "k" + index;
			wide.put(key, 1);
			if (index > 0)
				arguments.append(',');
			arguments.append('"').append(key).append("\":1");
		}
		arguments.append('}');
		McpToolRegistration<McpJsonObject> input = McpToolRegistration.withName("input", VERSIONS)
				.inputSchema(McpJsonObject.builder().put("type", "object")
						.put("additionalProperties", McpJsonObject.builder()
								.put("type", "integer").build()).build())
				.handler((requestContext, toolArguments, invocationFeatures) -> {
					handlerCalls.incrementAndGet();
					return McpCompleteResult.fromToolText("accepted");
				}).build();
		McpToolRegistration<McpJsonObject> escaped = McpToolRegistration.withName("escaped", VERSIONS)
				.jsonObjectArguments().handler((requestContext, toolArguments, invocationFeatures) -> {
					handlerCalls.incrementAndGet();
					return McpCompleteResult.fromToolText(largeOutput.get()
							? CANARY + "\n".repeat(524_289) : "accepted");
				}).build();
		McpToolRegistration<Arguments> output = McpToolRegistration.withName("output", VERSIONS)
				.argumentAndOutputTypes(Arguments.class, Answer.class)
				.handler((requestContext, toolArguments, invocationFeatures) -> {
					handlerCalls.incrementAndGet();
					return new Answer(largeOutput.get() ? wide : Map.of("small", 1));
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("json-schema-limit-test", "1").build(), VERSIONS)
				.toolRegistrations(List.of(input, escaped, output)).build();
		McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
		try (Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build())) {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			Response parseFailure = call(port, protocolVersion, "escaped",
					"{\"value\":\"" + "\\n".repeat(524_289) + CANARY + "\"}");
			assertProtocolFailure(parseFailure, protocolVersion, -32700, 400);
			assertEquals(0, handlerCalls.get());
			Response inputFailure = call(port, protocolVersion, "input", arguments.toString());
			if (protocolVersion == McpProtocolVersion.V2025_06_18) {
				assertProtocolFailure(inputFailure, protocolVersion, -32602, 400);
			} else {
				assertEquals(200, inputFailure.status(), inputFailure.body());
				assertTrue(inputFailure.body().contains("\"isError\":true"), inputFailure.body());
				assertTrue(inputFailure.body().contains(SAFE_MESSAGE), inputFailure.body());
				assertFalse(inputFailure.body().contains("\"error\""), inputFailure.body());
			}
			assertEquals(0, handlerCalls.get());
			assertProtocolFailure(call(port, protocolVersion, "escaped", "{}"), protocolVersion, -32603, 500);
			assertProtocolFailure(call(port, protocolVersion, "output", "{}"), protocolVersion, -32603, 500);
			assertEquals(2, handlerCalls.get());
			largeOutput.set(false);
			for (String toolName : List.of("input", "escaped", "output")) {
				Response recovered = call(port, protocolVersion, toolName, "{}");
				assertEquals(200, recovered.status(), recovered.body());
				assertFalse(recovered.body().contains("\"error\""), recovered.body());
				assertFalse(recovered.body().contains("\"isError\":true"), recovered.body());
			}
			assertEquals(5, handlerCalls.get());
		}
	}

	private static Response call(int port, McpProtocolVersion protocolVersion,
			String toolName, String arguments) throws Exception {
		String metadata = protocolVersion == McpProtocolVersion.V2026_07_28
				? ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":{}}" : "";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"limit-test\",\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"" + toolName + "\",\"arguments\":" + arguments + metadata + "}}";
		List<HeaderValue> headers = new ArrayList<>();
		headers.add(new HeaderValue("MCP-Protocol-Version", protocolVersion.getWireValue()));
		if (protocolVersion == McpProtocolVersion.V2026_07_28) {
			headers.add(new HeaderValue("Mcp-Method", "tools/call"));
			headers.add(new HeaderValue("Mcp-Name", toolName));
		}
		try (RawClient client = new RawClient(port, "POST", "/mcp", body, headers)) {
			Head head = client.readHead();
			return new Response(head.status(), client.readBody(head));
		}
	}

	private static void assertProtocolFailure(Response response, McpProtocolVersion protocolVersion,
			int code, int modernStatus) {
		assertEquals(code == -32700 ? 400 : protocolVersion == McpProtocolVersion.V2026_07_28 ? modernStatus : 200,
				response.status(), response.body());
		assertTrue(response.body().contains("\"code\":" + code), response.body());
		assertFalse(response.body().contains("\"isError\""), response.body());
		assertFalse(response.body().contains(CANARY), response.body());
		assertFalse(response.body().contains("configured character limit"), response.body());
		assertFalse(response.body().contains("compiled tool schema"), response.body());
	}

	private record Arguments() {}
	private record Answer(Map<String, Integer> entries) {}
	private record Response(int status, String body) {}
}
