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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class McpFinalTagGoldenWireProductionTests {
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Path GOLDEN_ROOT = Path.of(
			"conformance", "official", "golden-wire", "phase-3");

	@Test
	public void checked_in_phase_3_messages_match_the_production_listener() throws Exception {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"soklet-final-schema-golden", "3.6.0-SNAPSHOT"))
				.includeServerInformation(true)
				.build();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous());

		try (McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), policy, endpoint)) {
			int port = runtime.start().getPort();

			assertExchange(port, fixture("discover-request.json"), List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION),
					new McpChunkedHttpClient.RequestHeader("Mcp-Method", "server/discover")),
					200, fixture("discover-response.json"));

			assertExchange(port, fixture("discover-request.json"), List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION)),
					400, fixture("header-mismatch-error.json"));

			assertExchange(port, fixture("unsupported-version-request.json"), List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", "2025-11-25"),
					new McpChunkedHttpClient.RequestHeader("Mcp-Method", "server/discover")),
					400, fixture("unsupported-version-error.json"));
		}
	}

	private static void assertExchange(int port, String request,
			List<McpChunkedHttpClient.RequestHeader> headers, int expectedStatus,
			String expectedResponse) throws Exception {
		try (McpChunkedHttpClient client =
					McpChunkedHttpClient.postMcpMessage(port, request, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			Assertions.assertEquals(expectedStatus, head.status(), head.raw());
			Assertions.assertEquals("application/json", head.singleHeader("Content-Type"));
			Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
			Assertions.assertEquals(expectedResponse, client.readFixedBody(head));
		}
	}

	private static String fixture(String filename) throws Exception {
		String text = Files.readString(GOLDEN_ROOT.resolve(filename), StandardCharsets.UTF_8);
		Assertions.assertFalse(text.contains("\r"), filename + " must use LF");
		Assertions.assertTrue(text.endsWith("\n"), filename + " must end with LF");
		Assertions.assertFalse(text.substring(0, text.length() - 1).contains("\n"),
				filename + " must contain one compact JSON line");
		return text.substring(0, text.length() - 1);
	}
}
