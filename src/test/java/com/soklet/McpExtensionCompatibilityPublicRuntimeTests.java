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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public real-listener coverage for unsupported peer-extension fallback.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpExtensionCompatibilityPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String EXTENSION_ID = "com.example/client-extension";
	private static final String EXTENSION_SECRET = "extension-setting-secret";

	@Test
	public void validUnknownExtensionFallsBackToCoreWithoutInventedOrReflectedSupport()
			throws Exception {
		List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		McpServer server = server(context -> {
			admissions.add(context);
			return McpAdmissionDecision.accepted();
		});

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = discover(port, "valid-extension", "{"
					+ "\"extensions\":{\"" + EXTENSION_ID + "\":{"
					+ "\"enabled\":true,\"secret\":\"" + EXTENSION_SECRET + "\"}},"
					+ "\"futureCapability\":{\"enabled\":true}}");

			Assertions.assertEquals(200, response.statusCode(), response.body());
			Assertions.assertEquals("no-store",
					response.headers().firstValue("Cache-Control").orElseThrow());
			Assertions.assertTrue(response.body().contains("\"id\":\"valid-extension\""),
					response.body());
			Assertions.assertTrue(response.body().contains("\"capabilities\":{}"),
					response.body());
			Assertions.assertFalse(response.body().contains(EXTENSION_ID), response.body());
			Assertions.assertFalse(response.body().contains(EXTENSION_SECRET), response.body());
			Assertions.assertFalse(response.body().contains("\"extensions\""),
					response.body());
			Assertions.assertFalse(response.body().contains("futureCapability"),
					response.body());

			Assertions.assertEquals(1, admissions.size());
			McpClientCapabilities capabilities = admissions.get(0)
					.getClientCapabilities().orElseThrow();
			McpJsonObject extension = capabilities.findExtension(EXTENSION_ID)
					.orElseThrow();
			Assertions.assertEquals(McpJsonBoolean.fromValue(true),
					extension.getMembers().get("enabled"));
			Assertions.assertEquals(EXTENSION_SECRET,
					((McpJsonString) extension.getMembers().get("secret")).getValue());
			Assertions.assertEquals(Set.of(EXTENSION_ID),
					capabilities.getExtensions().keySet());
			for (McpClientCapability capability : McpClientCapability.values())
				Assertions.assertFalse(capabilities.supports(capability),
						() -> "Unknown extensions must not invent core support: "
								+ capability);
			Assertions.assertTrue(capabilities.toJson().getMembers()
					.containsKey("futureCapability"));
		} finally {
			server.stop();
		}
	}

	@Test
	public void malformedExtensionIdentifiersAndSettingsFailExplicitlyBeforeAdmission()
			throws Exception {
		List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		McpServer server = server(context -> {
			admissions.add(context);
			return McpAdmissionDecision.accepted();
		});
		List<String> malformedCapabilities = List.of(
				"{\"extensions\":[]}",
				"{\"extensions\":{\"not-prefixed\":{}}}",
				"{\"extensions\":{\"/missing-prefix\":{}}}",
				"{\"extensions\":{\"com.example/client-extension\":true}}");

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			for (int index = 0; index < malformedCapabilities.size(); index++) {
				String id = "malformed-extension-" + index;
				HttpResponse<String> response = discover(port, id,
						malformedCapabilities.get(index));
				Assertions.assertEquals(400, response.statusCode(), response.body());
				Assertions.assertEquals("no-store",
						response.headers().firstValue("Cache-Control").orElseThrow());
				Assertions.assertTrue(response.body().contains("\"id\":\"" + id + "\""),
						response.body());
				Assertions.assertTrue(response.body().contains("\"code\":-32602"),
						response.body());
				Assertions.assertFalse(response.body().contains("not-prefixed"),
						response.body());
				Assertions.assertFalse(response.body().contains("missing-prefix"),
						response.body());
			}
			Assertions.assertTrue(admissions.isEmpty(),
					"Malformed extension metadata must fail before admission.");
		} finally {
			server.stop();
		}
	}

	private static McpServer server(McpAdmissionController admissionController) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"extension-compatibility-test", "3.6.0-SNAPSHOT").build())
				.build();
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(admissionController)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	private static HttpResponse<String> discover(int port, String id,
			String clientCapabilities) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ clientCapabilities + "}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "server/discover")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}
}
