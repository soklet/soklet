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
import com.soklet.LifecycleObserver;
import com.soklet.LogEvent;
import com.soklet.McpAdmissionController;
import com.soklet.McpClientCapability;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpInputRequestDeclaration;
import com.soklet.McpInputRequirement;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestContext;
import com.soklet.McpServer;
import com.soklet.MetricsCollector;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import org.jspecify.annotations.NonNull;
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
import java.util.concurrent.atomic.AtomicReference;

/** Functional coverage for Soklet's declined SEP-2577 warning SHOULD. */
@Timeout(60)
public class McpDeprecatedCapabilityNegotiationTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";

	@Test
	public void deprecatedCapabilityNegotiationRemainsFunctionalAndEmitsNoWarningEvent()
			throws Exception {
		List<LogEvent> logEvents = new CopyOnWriteArrayList<>();
		AtomicReference<McpRequestContext> observedContext = new AtomicReference<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didStartMcpRequestHandling(
					@NonNull McpRequestContext context) {
				observedContext.set(context);
			}

			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				logEvents.add(logEvent);
			}
		};
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"deprecated-capability-test", "4.0.0").build())
				.build();
		McpServer server = McpServer.withPort(0, McpEndpointRegistry.fromEndpoints(List.of(endpoint)), McpAdmissionController.acceptAllInstance())
				.host(LOOPBACK)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObservers(List.of(observer))
				.metricsCollector(MetricsCollector.disabledInstance())
				.build());

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			String body = """
					{"jsonrpc":"2.0","id":"deprecated-capabilities","method":"server/discover","params":{"_meta":{
					  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
					  "io.modelcontextprotocol/clientCapabilities":{
					    "roots":{},
					    "sampling":{"context":{},"tools":{}}
					  }
					}}}
					""";
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
					.timeout(Duration.ofSeconds(5))
					.header("Content-Type", "application/json; charset=UTF-8")
					.header("Accept", "application/json, text/event-stream")
					.header("MCP-Protocol-Version", PROTOCOL_VERSION)
					.header("Mcp-Method", "server/discover")
					.POST(HttpRequest.BodyPublishers.ofString(
							body, StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = HttpClient.newHttpClient().send(
					request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			Assertions.assertEquals(200, response.statusCode(), response.body());
			McpRequestContext context = observedContext.get();
			Assertions.assertNotNull(context);
			for (McpClientCapability capability : List.of(
					McpClientCapability.ROOTS,
					McpClientCapability.SAMPLING,
					McpClientCapability.SAMPLING_CONTEXT,
					McpClientCapability.SAMPLING_TOOLS))
				Assertions.assertTrue(context.getClientCapabilities().supports(capability));
			Assertions.assertEquals("roots/list",
					McpInputRequestDeclaration.fromRoots(McpInputRequirement.REQUIRED)
							.getJsonRpcMethod());
			Assertions.assertEquals("sampling/createMessage",
					McpInputRequestDeclaration.fromSampling(
							Set.of(McpClientCapability.SAMPLING_CONTEXT,
									McpClientCapability.SAMPLING_TOOLS),
							McpInputRequirement.REQUIRED).getJsonRpcMethod());
			Assertions.assertTrue(logEvents.isEmpty(),
					() -> "Deprecated capability negotiation emitted LogEvent(s): "
							+ logEvents);
		} finally {
			soklet.close();
		}
	}
}
