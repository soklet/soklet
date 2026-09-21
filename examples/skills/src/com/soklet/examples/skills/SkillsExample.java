/*
 * Copyright 2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.examples.skills;

import com.soklet.CorsAuthorizer;
import com.soklet.McpAdmissionController;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpServer;
import com.soklet.McpServerDiagnostics;
import com.soklet.McpServerStatus;
import com.soklet.McpSkillBundle;
import com.soklet.McpSkillRegistration;
import com.soklet.ResourceMethodResolver;
import com.soklet.ShutdownComponentDisposition;
import com.soklet.ShutdownComponentType;
import com.soklet.ShutdownDisposition;
import com.soklet.ShutdownResult;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.StartupDisposition;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Starts a small, loopback-only MCP server that publishes one synthetic Skill. */
public final class SkillsExample {
	private static final String HOST = "127.0.0.1";
	private static final String ENDPOINT_PATH = "/mcp";
	private static final URI SKILL_URI = URI.create(
			"skill://soklet.example/toy-catalog-guide/SKILL.md");
	private static final byte[] SAMPLE_BINARY = {
			0x53, 0x4f, 0x4b, 0x4c, 0x45, 0x54, 0x00, 0x01, (byte) 0xff
	};

	private SkillsExample() {
	}

	public static void main(String[] arguments) throws Exception {
		int requestedPort = requestedPort(arguments);
		McpSkillBundle bundle = McpSkillBundle.fromFiles(Map.of(
				"SKILL.md", requiredClasspathResource("/SKILL.md"),
				"references/catalog.csv", requiredClasspathResource(
						"/references/catalog.csv"),
				"assets/sample.bin", SAMPLE_BINARY));
		McpSkillRegistration skill = McpSkillRegistration
				.withUriAndSkillBundle(SKILL_URI, bundle)
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(ENDPOINT_PATH,
				McpImplementation.withNameAndVersion(
						"soklet-skills-example", "1.0.0").build())
				.instructions("Explore the synthetic toy catalog guide and its supporting files.")
				.skillRegistrations(List.of(skill))
				.build();
		McpServer server = McpServer.withPort(requestedPort)
				.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				// This is public demo data. Real applications normally authenticate here.
				.admissionController(McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST))
				.build();
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			InetSocketAddress address = runningAddress(server);
			control("{\"event\":\"ready\",\"host\":\"" + HOST + "\",\"port\":"
					+ address.getPort() + ",\"path\":\"" + ENDPOINT_PATH + "\"}");
			awaitNewlineOrEndOfFile();

			ShutdownResult result = soklet.shutdown().toCompletableFuture()
					.get(10, TimeUnit.SECONDS);
			verifyCleanShutdown(server, result);
		}

		control("{\"event\":\"stopped\",\"clean\":true}");
	}

	private static int requestedPort(String[] arguments) {
		if (arguments.length == 0)
			return 0;
		if (arguments.length != 1 || arguments[0].isEmpty()
				|| !arguments[0].chars().allMatch(character ->
						character >= '0' && character <= '9'))
			throw new IllegalArgumentException("Usage: SkillsExample [port]");

		try {
			int port = Integer.parseInt(arguments[0]);
			if (port > 65_535)
				throw new IllegalArgumentException("Port must be between 0 and 65535.");
			return port;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Port must be between 0 and 65535.",
					exception);
		}
	}

	private static byte[] requiredClasspathResource(String resourceName)
			throws IOException {
		try (InputStream input = SkillsExample.class.getResourceAsStream(resourceName)) {
			if (input == null)
				throw new IOException("Missing example classpath resource: " + resourceName);
			return input.readAllBytes();
		}
	}

	private static InetSocketAddress runningAddress(McpServer server) {
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		if (diagnostics.getStatus() != McpServerStatus.RUNNING)
			throw new IllegalStateException("The MCP listener did not reach RUNNING.");
		InetSocketAddress address = diagnostics.getBoundAddress()
				.orElseThrow(() -> new IllegalStateException(
						"The MCP listener did not publish a bound address."));
		if (!HOST.equals(address.getAddress().getHostAddress())
				|| address.getPort() < 1 || address.getPort() > 65_535)
			throw new IllegalStateException("The MCP listener did not bind to loopback.");
		return address;
	}

	private static void awaitNewlineOrEndOfFile() throws IOException {
		for (;;) {
			int next = System.in.read();
			if (next == '\n' || next == -1)
				return;
		}
	}

	private static void verifyCleanShutdown(McpServer server,
			ShutdownResult result) {
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		if (result.getStartupDisposition() != StartupDisposition.READY
				|| result.getShutdownDisposition() != ShutdownDisposition.GRACEFUL
				|| result.getShutdownComponentResult(ShutdownComponentType.MCP)
						.orElseThrow().getShutdownComponentDisposition()
						!= ShutdownComponentDisposition.GRACEFUL_TERMINATION
				|| diagnostics.getStatus() != McpServerStatus.TERMINATED)
			throw new IllegalStateException("The MCP listener did not stop cleanly.");
	}

	private static void control(String json) {
		System.out.println(json);
		System.out.flush();
		if (System.out.checkError())
			throw new IllegalStateException("Unable to write the control event.");
	}
}
