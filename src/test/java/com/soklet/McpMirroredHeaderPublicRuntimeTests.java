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

import com.soklet.annotation.McpHeader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box real-listener coverage for public MCP mirrored-header wiring.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(30)
public class McpMirroredHeaderPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TOOL_NAME = "catalog.route";

	@Test
	public void publicPlansValidateBeforeAdmissionAndIgnoreUnknownsByDefault()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		AtomicReference<MirroredArguments> observed = new AtomicReference<>();
		McpToolRegistration<MirroredArguments> tool = mirroredTool(TOOL_NAME,
				handlers, observed);
		McpServer server = serverBuilder(List.of(endpoint(MCP_PATH, tool)),
				admissions, CorsAuthorizer.rejectAllInstance()).build();

		try {
			server.start();
			int port = boundPort(server);
			HttpResponse<String> valid = call(port, MCP_PATH, "valid", TOOL_NAME,
					mirroredArgumentsJson(), validHeaders());
			assertSuccess(valid, "valid");
			Assertions.assertEquals(1, admissions.get());
			Assertions.assertEquals(1, handlers.get());
			Assertions.assertEquals(new MirroredArguments("acme",
					new Routing(true, 42)), observed.get());

			Map<String, String> mismatchHeaders = validHeaders();
			mismatchHeaders.put("Mcp-Param-Tenant", "other");
			HttpResponse<String> mismatch = call(port, MCP_PATH, "mismatch",
					TOOL_NAME, mirroredArgumentsJson(), mismatchHeaders);
			assertError(mismatch, -32_020, "mismatch");
			Assertions.assertEquals(1, admissions.get(),
					"A recognized mismatch must fail before public admission.");
			Assertions.assertEquals(1, handlers.get(),
					"A recognized mismatch must fail before the tool handler.");

			Map<String, String> unknownHeaders = validHeaders();
			unknownHeaders.put("Mcp-Param-Unregistered", "untrusted");
			HttpResponse<String> ignored = call(port, MCP_PATH, "ignored",
					TOOL_NAME, mirroredArgumentsJson(), unknownHeaders);
			assertSuccess(ignored, "ignored");
			Assertions.assertEquals(2, admissions.get());
			Assertions.assertEquals(2, handlers.get());
		} finally {
			server.stop();
		}
	}

	@Test
	public void strictUnknownPolicyRejectsBeforeAdmissionWithoutReflection()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		McpToolRegistration<MirroredArguments> tool = mirroredTool(TOOL_NAME,
				handlers, new AtomicReference<>());
		McpServer server = serverBuilder(List.of(endpoint(MCP_PATH, tool)),
				admissions, CorsAuthorizer.rejectAllInstance())
				.unknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS)
				.build();

		try {
			server.start();
			Map<String, String> headers = validHeaders();
			headers.put("Mcp-Param-Super-Secret-Name", "super-secret-value");
			HttpResponse<String> response = call(boundPort(server), MCP_PATH,
					"strict", TOOL_NAME, mirroredArgumentsJson(), headers);

			assertError(response, -31_998, "strict");
			assertContains(response.body(),
					"\"message\":\"Unknown mirrored header\"");
			Assertions.assertFalse(response.body().contains("Super-Secret-Name"),
					response.body());
			Assertions.assertFalse(response.body().contains("super-secret-value"),
					response.body());
			Assertions.assertEquals(0, admissions.get());
			Assertions.assertEquals(0, handlers.get());
		} finally {
			server.stop();
		}
	}

	@Test
	public void corsMirroredHeaderAllowListsRemainEndpointLocal()
			throws Exception {
		String origin = "https://allowed.example";
		AtomicInteger admissions = new AtomicInteger();
		McpEndpoint tenantEndpoint = endpoint("/tenant",
				McpToolRegistration.withName("tenant.lookup")
						.argumentType(TenantArguments.class)
						.handler((request, call, features) ->
								McpCompleteResult.fromToolText("tenant"))
						.build());
		McpEndpoint regionEndpoint = endpoint("/region",
				McpToolRegistration.withName("region.lookup")
						.argumentType(RegionArguments.class)
						.handler((request, call, features) ->
								McpCompleteResult.fromToolText("region"))
						.build());
		McpServer server = serverBuilder(
				List.of(tenantEndpoint, regionEndpoint), admissions,
				CorsAuthorizer.fromWhitelistedOrigins(Set.of(origin))).build();

		try {
			server.start();
			int port = boundPort(server);
			Assertions.assertEquals(204, preflight(port, "/tenant", origin,
					"Mcp-Param-Tenant").statusCode());
			Assertions.assertEquals(403, preflight(port, "/tenant", origin,
					"Mcp-Param-Region").statusCode());
			Assertions.assertEquals(204, preflight(port, "/region", origin,
					"Mcp-Param-Region").statusCode());
			Assertions.assertEquals(403, preflight(port, "/region", origin,
					"Mcp-Param-Tenant").statusCode());
			Assertions.assertEquals(0, admissions.get(),
					"CORS preflight must not enter public admission.");
		} finally {
			server.stop();
		}
	}

	private static McpToolRegistration<MirroredArguments> mirroredTool(
			String name, AtomicInteger handlers,
			AtomicReference<MirroredArguments> observed) {
		return McpToolRegistration.withName(name)
				.argumentType(MirroredArguments.class)
				.handler((request, call, features) -> {
					handlers.incrementAndGet();
					observed.set(call.getArguments());
					return McpCompleteResult.fromToolText("done");
				})
				.build();
	}

	private static McpEndpoint endpoint(String path,
			McpToolRegistration<?> tool) {
		return McpEndpoint.withPath(path)
				.serverInformation(McpImplementation.withNameAndVersion(
						"mirrored-header-public-runtime-test",
						"3.6.0-SNAPSHOT").build())
				.tool(tool)
				.build();
	}

	private static McpServer.Builder serverBuilder(List<McpEndpoint> endpoints,
			AtomicInteger admissions, CorsAuthorizer corsAuthorizer) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(endpoints))
				.requestAdmissionPolicy(context -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.fromAnonymousIdentity();
				})
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.corsAuthorizer(corsAuthorizer)
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static HttpResponse<String> call(int port, String path, String id,
			String toolName, String argumentsJson, Map<String, String> headers)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + path))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", toolName);
		headers.forEach(request::header);
		return httpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(
				callBody(id, toolName, argumentsJson), StandardCharsets.UTF_8)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static HttpResponse<String> preflight(int port, String path,
			String origin, String requestedHeader) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + path))
				.timeout(Duration.ofSeconds(5))
				.header("Origin", origin)
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", requestedHeader)
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.build();
		return httpClient().send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static Map<String, String> validHeaders() {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Mcp-Param-Tenant", "acme");
		headers.put("Mcp-Param-Dry-Run", "true");
		headers.put("Mcp-Param-Shard", "42");
		return headers;
	}

	private static String mirroredArgumentsJson() {
		return "{\"tenant\":\"acme\",\"routing\":{"
				+ "\"dryRun\":true,\"shard\":42}}";
	}

	private static String callBody(String id, String toolName,
			String argumentsJson) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + toolName + "\",\"arguments\":"
				+ argumentsJson + "}}";
	}

	private static void assertSuccess(HttpResponse<String> response, String id) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		assertContains(response.body(), "\"id\":\"" + id + "\"");
	}

	private static void assertError(HttpResponse<String> response, int code,
			String id) {
		Assertions.assertEquals(400, response.statusCode(), response.body());
		assertContains(response.body(), "\"code\":" + code);
		assertContains(response.body(), "\"id\":\"" + id + "\"");
	}

	private static void assertContains(String value, String expected) {
		Assertions.assertTrue(value.contains(expected), value);
	}

	private static HttpClient httpClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build();
	}

	private record MirroredArguments(@McpHeader("Tenant") String tenant,
			Routing routing) {
	}

	private record Routing(@McpHeader("Dry-Run") boolean dryRun,
			@McpHeader("Shard") int shard) {
	}

	private record TenantArguments(@McpHeader("Tenant") String tenant) {
	}

	private record RegionArguments(@McpHeader("Region") String region) {
	}
}
