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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public request-state limits remain independent of request and response sizes. */
@Timeout(60)
class McpRequestStateBoundaryPublicRuntimeTests {
	private static final int MIB = 1_024 * 1_024;
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(McpProtocolVersion.V2026_07_28);

	@Test
	void defaultRequestLimitPermitsLargeArgumentsWithSmallPortableState() throws Exception {
		assertLargeArgumentsRoundTrip(5, 10 * MIB);
	}

	@Test
	void configuredRequestLimitPermitsArgumentsLargerThanTheDefault() throws Exception {
		assertLargeArgumentsRoundTrip(11, 16 * MIB);
	}

	private static void assertLargeArgumentsRoundTrip(int chunkCount, int requestLimit) throws Exception {
		String chunk = "x".repeat(1_000_000);
		String arguments = "{\"chunks\":[" + ("\"" + chunk + "\",").repeat(chunkCount - 1)
				+ "\"" + chunk + "\"],\"tail\":\"stable-tail\"}";
		assertTrue(arguments.length() > 4 * MIB);
		assertTrue(arguments.length() < requestLimit);
		if (chunkCount > 10)
			assertTrue(arguments.length() > 10 * MIB);
		try (Fixture first = new Fixture(requestLimit, 10); Fixture second = new Fixture(requestLimit, 10)) {
			String state = protectedState(first.post("initial", arguments, null, 200));
			assertTrue(state.length() < 65_536, "Only the small application state is retained in the envelope.");
			String changed = arguments.replace("stable-tail", "changed-tail");
			String invalid = second.post("changed", changed, state, 400);
			assertTrue(invalid.contains("\"code\":-32602"), invalid);
			assertEquals(0, second.calls.get(), "Changed stable input must fail before handler entry.");
			String complete = second.post("retry", arguments, state, 200);
			assertTrue(complete.contains("\"text\":\"verified\""), complete);
			assertEquals(1, first.calls.get());
			assertEquals(1, second.calls.get());
		}
	}

	@Test
	void finalAllowedRoundCanCompleteButCannotEmitMoreFrameworkState() throws Exception {
		try (Fixture fixture = new Fixture(10 * MIB, 1)) {
			String state = protectedState(fixture.post("initial", "{}", null, 200));
			fixture.reemit.set(true);
			String failure = fixture.post("exhausted", "{}", state, 500);
			assertTrue(failure.contains("\"code\":-32603"), failure);
			assertTrue(failure.contains("Internal error"), failure);
			assertFalse(failure.contains("maximum round"), failure);
			assertFalse(failure.contains("requestState"), failure);
			assertEquals(2, fixture.calls.get(), "Re-emission is rejected after the valid retry's handler runs.");
			fixture.reemit.set(false);
			String complete = fixture.post("complete", "{}", state, 200);
			assertTrue(complete.contains("\"text\":\"verified\""), complete);
			assertEquals(3, fixture.calls.get());
		}
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
		private final AtomicBoolean reemit = new AtomicBoolean();
		private final McpServer server;
		private final Soklet soklet;

		private Fixture(int requestLimit, int maximumRounds) {
			McpInputRequestDeclaration declaration = McpInputRequestDeclaration.fromElicitationForm(McpInputRequirement.REQUIRED);
			McpInputRequest form = McpInputRequest.fromDeclaration(declaration, McpJsonObject.builder()
					.put("mode", "form").put("message", "Continue")
					.put("requestedSchema", McpJsonObject.builder().put("type", "object")
							.put("properties", McpJsonObject.builder().build()).build()).build());
			McpJsonObject state = McpJsonObject.builder().put("step", 1).build();
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("state.boundary", VERSIONS)
					.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) -> {
						calls.incrementAndGet();
						if (arguments.getRawArguments().find("tail").isPresent())
							assertEquals(McpJsonString.fromValue("stable-tail"), arguments.getRawArguments().find("tail").orElseThrow());
						if (requestContext.getFrameworkRequestState().isPresent()) {
							assertEquals(state, requestContext.getFrameworkRequestState().orElseThrow());
							if (!reemit.get())
								return McpCompleteResult.fromToolText("verified");
						}
						return McpInputRequiredResult.withInputRequest("continue", form)
								.frameworkRequestState(state).build();
					}).inputRequestDeclarations(List.of(declaration))
					.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED).build();
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
					McpImplementation.withNameAndVersion("state-boundary-test", "1").build(), VERSIONS)
					.toolRegistrations(List.of(tool)).build();
			McpProtectionKeyring keyring = McpProtectionKeyring.withActiveKey(McpProtectionKey.fromIdAndBytes("test",
					"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII))).build();
			McpAdmissionIdentity identity = McpAdmissionIdentity.withRateLimitPartitionKey("test-rate")
					.authorizationPartitionKey("test-owner").build();
			server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.maximumRequestSizeInBytes(requestLimit == 10 * MIB ? null : requestLimit)
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.protectionConfig(McpProtectionConfig.withKeyring(keyring).maximumRequestStateRounds(maximumRounds).build())
					.admissionController(admissionContext -> McpAdmissionDecision.accepted(identity))
					.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
			soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build());
			soklet.start();
		}

		private String post(String id, String arguments, String state, int expectedStatus) throws Exception {
			String retry = state == null ? "" : ",\"requestState\":\"" + state
					+ "\",\"inputResponses\":{\"continue\":{\"action\":\"accept\",\"content\":{}}}";
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"tools/call\","
					+ "\"params\":{\"name\":\"state.boundary\",\"arguments\":" + arguments + retry
					+ ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{\"elicitation\":{\"form\":{}}}}}}";
			try (RawClient client = new RawClient(server.getDiagnostics().getBoundAddress().orElseThrow().getPort(),
					"POST", "/mcp", body, List.of(new HeaderValue("MCP-Protocol-Version", "2026-07-28"),
							new HeaderValue("Mcp-Method", "tools/call"), new HeaderValue("Mcp-Name", "state.boundary")))) {
				Head head = client.readHead();
				String response = client.readBody(head);
				assertEquals(expectedStatus, head.status(), response);
				return response;
			}
		}

		@Override public void close() { soklet.close(); }
	}
}
