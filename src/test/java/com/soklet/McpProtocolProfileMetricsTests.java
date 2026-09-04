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

import javax.annotation.concurrent.NotThreadSafe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@NotThreadSafe
@Timeout(60)
public class McpProtocolProfileMetricsTests {
	private static final String HOST = "127.0.0.1";
	private static final String UNSUPPORTED = "2099-01-01";

	@Test
	public void unsupportedMissingMetadataRecordsUnsupportedVersionNotInvalidParams()
			throws Exception {
		DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"profile-metrics-test", "4.0.0").build())
				.build();
		McpServer server = McpServer.withPort(0, McpEndpointRegistry.fromEndpoints(List.of(endpoint)), McpAdmissionController.acceptAllInstance())
				.host(HOST)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST))
				.build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metrics)
				.build());

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"metrics\","
					+ "\"method\":\"server/discover\",\"params\":{}}";
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("http://" + HOST + ":" + port + "/mcp"))
					.timeout(Duration.ofSeconds(5))
					.header("Content-Type", "application/json")
					.header("Accept", "application/json, text/event-stream")
					.header("MCP-Protocol-Version", UNSUPPORTED)
					.header("Mcp-Method", "server/discover")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> response = HttpClient.newHttpClient().send(
					request, HttpResponse.BodyHandlers.ofString());
			Assertions.assertEquals(400, response.statusCode());
			Assertions.assertTrue(response.body().contains("\"code\":-32022"),
					response.body());

			Map<Integer, Long> protocolErrors = awaitProtocolErrors(metrics, -32022);
			Assertions.assertEquals(1L, protocolErrors.get(-32022));
			Assertions.assertFalse(protocolErrors.containsKey(-32602));
		} finally {
			soklet.close();
		}
	}

	private static Map<Integer, Long> awaitProtocolErrors(
			DefaultMetricsCollector metrics, int expectedCode) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		Map<Integer, Long> protocolErrors;
		do {
			protocolErrors = metrics.snapshot().orElseThrow().getMcpMetrics()
					.getProtocolErrors();
			if (protocolErrors.containsKey(expectedCode))
				return protocolErrors;
			Thread.onSpinWait();
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for protocol-error metrics: "
				+ protocolErrors);
	}
}
