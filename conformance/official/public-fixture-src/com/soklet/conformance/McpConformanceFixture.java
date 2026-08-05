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

package com.soklet.conformance;

import com.soklet.CorsAuthorizer;
import com.soklet.LifecycleObserver;
import com.soklet.McpEndpoint;
import com.soklet.McpHandlerResolver;
import com.soklet.McpImplementation;
import com.soklet.McpRequestAdmissionPolicy;
import com.soklet.McpServer;
import com.soklet.McpServerStatus;
import com.soklet.McpShutdownOutcome;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public-API-only black-box fixture for Soklet's official MCP conformance run.
 */
public final class McpConformanceFixture {
	private static final String SUPPORTED_SCENARIO = "dns-rebinding-protection";

	private McpConformanceFixture() {
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 2 || !"--scenario".equals(arguments[0])
				|| !SUPPORTED_SCENARIO.equals(arguments[1]))
			throw new IllegalArgumentException(
					"Usage: McpConformanceFixture --scenario " + SUPPORTED_SCENARIO);

		AtomicInteger effectivePort = new AtomicInteger(-1);
		AtomicReference<McpShutdownOutcome> shutdownOutcome = new AtomicReference<>();
		CorsAuthorizer corsAuthorizer = CorsAuthorizer.fromWhitelistAuthorizer(origin ->
				origin.equals("http://127.0.0.1:" + effectivePort.get()));
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"soklet-public-conformance", "3.6.0-SNAPSHOT").build())
				.build();
		McpServer mcpServer = McpServer.withPort(0)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(McpRequestAdmissionPolicy.acceptAllInstance())
				.corsAuthorizer(corsAuthorizer)
				.build();
		LifecycleObserver lifecycleObserver = new LifecycleObserver() {
			@Override
			public void didStopMcpServer(McpServer server, McpShutdownOutcome outcome) {
				shutdownOutcome.set(outcome);
			}
		};
		SokletConfig config = SokletConfig.withMcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			InetSocketAddress address = mcpServer.getDiagnostics().getBoundAddress()
					.orElseThrow(() -> new IllegalStateException(
							"The public MCP server did not publish its bound address."));
			if (!address.getAddress().isLoopbackAddress())
				throw new IllegalStateException(
						"The conformance fixture did not bind a loopback address.");
			effectivePort.set(address.getPort());
			writeControlLine("{\"format\":1,\"event\":\"ready\","
					+ "\"host\":\"127.0.0.1\",\"port\":" + address.getPort()
					+ ",\"path\":\"/mcp\"}");

			while (System.in.read() >= 0) {
				// The parent owns this pipe. EOF is the graceful shutdown request.
			}
		}

		if (mcpServer.isStarted()
				|| mcpServer.getDiagnostics().getStatus() != McpServerStatus.STOPPED
				|| shutdownOutcome.get() != McpShutdownOutcome.CLEAN)
			throw new IllegalStateException(
					"The public MCP conformance fixture did not shut down cleanly.");

		writeControlLine("{\"format\":1,\"event\":\"stopped\",\"clean\":true}");
	}

	private static void writeControlLine(String line) throws Exception {
		System.out.write((line + '\n').getBytes(StandardCharsets.UTF_8));
		System.out.flush();
	}
}
