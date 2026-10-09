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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real tool calls reject compact numeric amplification before application entry. */
@Timeout(60)
class McpTypedIntegerLimitPublicRuntimeTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);

	@TestFactory
	Stream<DynamicTest> boundedIntegerBindingForEachRevision() {
		return Stream.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28)
				.map(version -> DynamicTest.dynamicTest(version.getWireValue(),
						() -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> verifyIntegerLimitsAndRecovery(version))));
	}

	private void verifyIntegerLimitsAndRecovery(McpProtocolVersion version) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<List<BigInteger>> observed = new AtomicReference<>();
		McpToolRegistration<IntegerArguments> tool = McpToolRegistration.withName("integers", VERSIONS)
				.argumentAndOutputTypes(IntegerArguments.class, CountResult.class)
				.handler((requestContext, arguments, invocationFeatures) -> {
					calls.incrementAndGet();
					List<BigInteger> values = arguments.getConvertedArguments().values();
					observed.set(values);
					return new CountResult(values.size());
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("integer-limit-test", "1").build(), VERSIONS)
				.toolRegistrations(List.of(tool)).build();
		McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
		try (Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build())) {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			assertSuccess(call(port, version, "exact", "1e1023,-1e1022"), 2);
			assertEquals(List.of(BigInteger.TEN.pow(1023), BigInteger.TEN.pow(1022).negate()), observed.get());
			assertEquals(1, calls.get());
			for (String value : List.of("1e9999", "-1e1023")) {
				assertInvalid(call(port, version, "oversized", value), version);
				assertEquals(1, calls.get());
			}

			// Under 40 KiB on the wire; at most 4 MiB of expanded decimal
			// characters. This is a capped boundary, not a heap/load probe.
			String exactBudget = String.join(",", Collections.nCopies(4096, "1e1023"));
			assertSuccess(call(port, version, "budget-exact", exactBudget), 4096);
			assertEquals(4096, observed.get().size());
			assertEquals(BigInteger.TEN.pow(1023), observed.get().get(4095));
			assertEquals(2, calls.get());
			assertInvalid(call(port, version, "budget-over", exactBudget + ",1"), version);
			assertEquals(2, calls.get());
			assertSuccess(call(port, version, "recovery", "1.0,0e9999"), 2);
			assertEquals(List.of(BigInteger.ONE, BigInteger.ZERO), observed.get());
			assertEquals(3, calls.get());
		}
	}

	private static Response call(int port, McpProtocolVersion version, String id,
			String values) throws Exception {
		String metadata = version == McpProtocolVersion.V2026_07_28
				? ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":{}}" : "";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"name\":\"integers\","
				+ "\"arguments\":{\"values\":[" + values + "]}" + metadata + "}}";
		List<HeaderValue> headers = new ArrayList<>();
		headers.add(new HeaderValue("MCP-Protocol-Version", version.getWireValue()));
		if (version == McpProtocolVersion.V2026_07_28) {
			headers.add(new HeaderValue("Mcp-Method", "tools/call"));
			headers.add(new HeaderValue("Mcp-Name", "integers"));
		}
		try (RawClient client = new RawClient(port, "POST", "/mcp", body, headers)) {
			Head head = client.readHead();
			return new Response(head.status(), client.readBody(head));
		}
	}

	private static void assertSuccess(Response response, int count) {
		assertEquals(200, response.status(), response.body());
		assertTrue(response.body().contains("\"structuredContent\":{\"count\":" + count + "}"), response.body());
	}

	private static void assertInvalid(Response response, McpProtocolVersion version) {
		assertEquals(200, response.status(), response.body());
		if (version == McpProtocolVersion.V2025_06_18)
			assertTrue(response.body().contains("\"code\":-32602"), response.body());
		else {
			assertTrue(response.body().contains("\"isError\":true"), response.body());
			assertTrue(response.body().contains("Arguments do not match the tool's inputSchema."), response.body());
			assertFalse(response.body().contains("\"error\""), response.body());
		}
		assertFalse(response.body().contains("1e9999"), response.body());
	}

	private record IntegerArguments(List<BigInteger> values) {}
	private record CountResult(int count) {}
	private record Response(int status, String body) {}
}
