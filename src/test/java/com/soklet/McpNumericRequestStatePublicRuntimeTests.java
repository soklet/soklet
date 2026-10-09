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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Numeric state survives verified retries across independent Soklet instances. */
@Timeout(60)
class McpNumericRequestStatePublicRuntimeTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(McpProtocolVersion.V2026_07_28);
	private static final McpJsonObject ORIGINAL = McpJsonObject.builder()
			.put("integer", new BigDecimal("100"))
			.put("decimal", new BigDecimal("1.50"))
			.put("zero", new BigDecimal("-0.000"))
			.put("tiny", new BigDecimal("0.00000010"))
			.put("nested", McpJsonArray.fromElements(List.of(
					McpJsonObject.builder().put("other", new BigDecimal("1000.00")).build(),
					McpJsonString.fromValue("007"))))
			.put("identifier", "5000").put("priceText", "1.50").build();

	@Test
	void canonicalNumericStateHasStableEqualityAndHashLookupsAcrossTwoRetries() throws Exception {
		try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
			String initial = first.post("initial", null, false, 200);
			String firstState = protectedState(initial);
			String sameId = second.post("initial", firstState, true, 400);
			assertTrue(sameId.contains("\"code\":-32602"), sameId);
			assertEquals(0, second.calls.get(), "An invalid prior request ID must fail before handler entry.");
			String middle = second.post("middle", firstState, true, 200);
			assertCanonicalState(second.observed.get());
			String completed = first.post("final", protectedState(middle), false, 200);
			assertTrue(completed.contains("\"text\":\"verified numeric state\""), completed);
			assertCanonicalState(first.observed.get());
			assertEquals(2, first.calls.get());
			assertEquals(1, second.calls.get());
			assertEquals(new BigDecimal("100"), number(ORIGINAL, "integer"));
			assertEquals(new BigDecimal("1.50"), number(ORIGINAL, "decimal"));
			assertEquals(new BigDecimal("-0.000"), number(ORIGINAL, "zero"));
		}
	}

	private static void assertCanonicalState(McpJsonValue state) {
		assertEquals(ORIGINAL, state);
		assertEquals(ORIGINAL.hashCode(), state.hashCode());
		McpJsonObject object = assertInstanceOf(McpJsonObject.class, state);
		assertEquals(new BigDecimal("1E+2"), number(object, "integer"));
		assertEquals(new BigDecimal("1.5"), number(object, "decimal"));
		assertEquals(BigDecimal.ZERO, number(object, "zero"));
		assertEquals(new BigDecimal("1E-7"), number(object, "tiny"));
		assertEquals(McpJsonString.fromValue("5000"), object.find("identifier").orElseThrow());
		assertEquals(McpJsonString.fromValue("1.50"), object.find("priceText").orElseThrow());
	}

	private static BigDecimal number(McpJsonObject object, String name) {
		return assertInstanceOf(McpJsonNumber.class, object.find(name).orElseThrow()).getValue();
	}

	private static String protectedState(String response) {
		assertTrue(response.contains("\"resultType\":\"input_required\""), response);
		String marker = "\"requestState\":\"";
		int start = response.indexOf(marker);
		assertTrue(start >= 0, response);
		start += marker.length();
		return response.substring(start, response.indexOf('"', start));
	}

	private static final class Fixture implements AutoCloseable {
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicReference<McpJsonValue> observed = new AtomicReference<>();
		private final McpServer server;
		private final Soklet soklet;

		private Fixture() {
			Map<McpJsonValue, String> lookup = new HashMap<>();
			lookup.put(ORIGINAL, "matched");
			McpInputRequestDeclaration declaration = McpInputRequestDeclaration.fromElicitationForm(McpInputRequirement.REQUIRED);
			McpInputRequest form = McpInputRequest.fromDeclaration(declaration, McpJsonObject.builder()
					.put("mode", "form").put("message", "Continue the numeric state test")
					.put("requestedSchema", McpJsonObject.builder().put("type", "object")
							.put("properties", McpJsonObject.builder().put("again", McpJsonObject.builder()
									.put("type", "boolean").build()).build()).build()).build());
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("numeric.state", VERSIONS)
					.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) -> {
						calls.incrementAndGet();
						if (requestContext.getFrameworkRequestState().isPresent()) {
							McpJsonValue verified = requestContext.getFrameworkRequestState().orElseThrow();
							observed.set(verified);
							assertEquals(ORIGINAL, verified);
							assertEquals("matched", lookup.get(verified));
							McpJsonObject response = assertInstanceOf(McpJsonObject.class,
									requestContext.getInputResponses().find("continue").orElseThrow());
							McpJsonObject content = assertInstanceOf(McpJsonObject.class, response.find("content").orElseThrow());
							if (McpJsonBoolean.fromValue(false).equals(content.find("again").orElseThrow()))
								return McpCompleteResult.fromToolText("verified numeric state");
						}
						return McpInputRequiredResult.withInputRequest("continue", form)
								.frameworkRequestState(ORIGINAL).build();
					}).inputRequestDeclarations(List.of(declaration))
					.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED).build();
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
					McpImplementation.withNameAndVersion("numeric-state-test", "1").build(), VERSIONS)
					.toolRegistrations(List.of(tool)).build();
			McpProtectionKeyring keyring = McpProtectionKeyring.withActiveKey(McpProtectionKey.fromIdAndBytes("test",
					"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII))).build();
			McpAdmissionIdentity identity = McpAdmissionIdentity.withRateLimitPartitionKey("test-rate")
					.authorizationPartitionKey("test-owner").build();
			server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.protectionConfig(McpProtectionConfig.withKeyring(keyring).build())
					.admissionController(admissionContext -> McpAdmissionDecision.accepted(identity))
					.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
			soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build());
			soklet.start();
		}

		private String post(String id, String state, boolean again, int expectedStatus) throws Exception {
			String retry = state == null ? "" : ",\"requestState\":\"" + state
					+ "\",\"inputResponses\":{\"continue\":{\"action\":\"accept\",\"content\":{\"again\":" + again + "}}}";
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"tools/call\","
					+ "\"params\":{\"name\":\"numeric.state\",\"arguments\":{}" + retry
					+ ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{\"elicitation\":{\"form\":{}}}}}}";
			try (RawClient client = new RawClient(server.getDiagnostics().getBoundAddress().orElseThrow().getPort(),
					"POST", "/mcp", body, List.of(new HeaderValue("MCP-Protocol-Version", "2026-07-28"),
							new HeaderValue("Mcp-Method", "tools/call"), new HeaderValue("Mcp-Name", "numeric.state")))) {
				Head head = client.readHead();
				String response = client.readBody(head);
				assertEquals(expectedStatus, head.status(), response);
				return response;
			}
		}

		@Override public void close() { soklet.close(); }
	}
}
