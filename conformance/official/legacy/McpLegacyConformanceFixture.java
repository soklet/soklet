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

package com.soklet.conformance.legacy;

import com.soklet.CorsAuthorizer;
import com.soklet.McpAbsentOriginPolicy;
import com.soklet.McpAdmissionController;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpProtocolVersion;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRateLimiter;
import com.soklet.McpServer;
import com.soklet.McpToolRegistration;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public-API-only fixture for a bounded 2025 synchronous-tool conformance subset.
 * Each process serves exactly one requested revision and exits on stdin EOF.
 */
public final class McpLegacyConformanceFixture {
	private static final String HOST = "127.0.0.1";
	private static final String PATH = "/mcp";

	private McpLegacyConformanceFixture() {
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 2 || !"--version".equals(arguments[0]))
			throw new IllegalArgumentException(
					"Usage: McpLegacyConformanceFixture --version <2025-06-18|2025-11-25>");
		McpProtocolVersion version = switch (arguments[1]) {
			case "2025-06-18" -> McpProtocolVersion.V2025_06_18;
			case "2025-11-25" -> McpProtocolVersion.V2025_11_25;
			default -> throw new IllegalArgumentException(
					"Unsupported legacy fixture revision: " + arguments[1]);
		};
		Set<McpProtocolVersion> versions = Set.of(version);
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion(
						"soklet-legacy-conformance", "4.0.0")
						.description("Bounded legacy tool fixture")
						.build(), versions)
				.toolRegistrations(List.of(
					McpToolRegistration.withName("test_simple_text", versions)
							.jsonObjectArguments()
							.handler((requestContext, argumentsContext,
									invocationFeatures) ->
									McpCompleteResult.fromToolText(
											"This is a simple text response for testing."))
							.description("Returns deterministic text content.")
							.build(),
					McpToolRegistration.withName("test_error_handling", versions)
							.jsonObjectArguments()
							.handler((requestContext, argumentsContext,
									invocationFeatures) ->
									McpCompleteResult.fromToolErrorText(
											"This tool intentionally returns an error for testing"))
							.description("Returns a deterministic application-level error.")
							.build()))
				.build();
		AtomicInteger boundPort = new AtomicInteger(-1);
		CorsAuthorizer corsAuthorizer = CorsAuthorizer.fromWhitelistAuthorizer(
				origin -> origin.equals("http://" + HOST + ":" + boundPort.get()));
		McpRateLimiter allowLimiter = context -> McpRateLimitDecision.allowed();
		McpServer server = McpServer.withPort(0)
				.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(allowLimiter)
				.toolRateLimiter(allowLimiter)
				.corsAuthorizer(corsAuthorizer)
				.absentOriginPolicy(McpAbsentOriginPolicy.ALLOW)
				.allowedHosts(Set.of(HOST))
				.build();
		SokletConfig configuration = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();

		try (Soklet soklet = Soklet.fromConfig(configuration)) {
			soklet.start();
			InetSocketAddress address = server.getDiagnostics().getBoundAddress()
					.orElseThrow();
			if (!address.getAddress().isLoopbackAddress())
				throw new IllegalStateException("Fixture escaped loopback.");
			boundPort.set(address.getPort());
			System.out.println("{\"format\":1,\"event\":\"ready\",\"host\":\""
					+ HOST + "\",\"port\":" + address.getPort()
					+ ",\"path\":\"" + PATH + "\",\"revision\":\""
					+ version.getWireValue() + "\"}");
			System.out.flush();
			while (System.in.read() >= 0) {
				// EOF from the supervised runner requests graceful shutdown.
			}
			soklet.shutdown();
			soklet.awaitShutdown();
		}
		System.out.println("{\"format\":1,\"event\":\"stopped\",\"clean\":true}");
		System.out.flush();
	}
}
