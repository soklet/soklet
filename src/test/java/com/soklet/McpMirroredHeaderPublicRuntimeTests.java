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
import org.jspecify.annotations.NonNull;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.TestSupport.findFreePort;

/**
 * Black-box real-listener coverage for public MCP mirrored-header wiring.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(60)
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
		AtomicReference<McpJsonObject> observedRaw = new AtomicReference<>();
		McpToolRegistration<MirroredArguments> tool = mirroredTool(TOOL_NAME,
				handlers, observed, observedRaw);
		McpServer server = serverBuilder(List.of(endpoint(MCP_PATH, tool)),
				admissions, CorsAuthorizer.rejectAllInstance()).build();
		Soklet soklet = lifecycleSoklet(server, new CopyOnWriteArrayList<>());

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> valid = call(port, MCP_PATH, "valid", TOOL_NAME,
					mirroredArgumentsJson(), validHeaders());
			assertSuccess(valid, "valid");
			Assertions.assertEquals(1, admissions.get());
			Assertions.assertEquals(1, handlers.get());
			assertBodyAuthoritativeArguments(observed, observedRaw);

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
			unknownHeaders.put("Mcp-Param-Privilege", "administrator-canary");
			HttpResponse<String> ignored = call(port, MCP_PATH, "ignored",
					TOOL_NAME, mirroredArgumentsJson(), unknownHeaders);
			assertSuccess(ignored, "ignored");
			Assertions.assertEquals(2, admissions.get());
			Assertions.assertEquals(2, handlers.get());
			assertBodyAuthoritativeArguments(observed, observedRaw);
			Assertions.assertFalse(ignored.body().contains("administrator-canary"),
					ignored.body());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void unknownNameDiagnosticsAreDefaultOffOnThePublicListener()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		List<LogEvent> events = new CopyOnWriteArrayList<>();
		McpToolRegistration<MirroredArguments> tool = mirroredTool(TOOL_NAME,
				handlers, new AtomicReference<>());
		McpServer server = serverBuilder(List.of(endpoint(MCP_PATH, tool)),
				admissions, CorsAuthorizer.rejectAllInstance()).build();
		Soklet soklet = lifecycleSoklet(server, events);

		try {
			soklet.start();
			Map<String, String> headers = validHeaders();
			headers.put("Mcp-Param-Default-Off", "must-not-be-logged");
			HttpResponse<String> response = call(boundPort(server), MCP_PATH,
					"default-off", TOOL_NAME, mirroredArgumentsJson(), headers);

			assertSuccess(response, "default-off");
			Assertions.assertEquals(1, admissions.get());
			Assertions.assertEquals(1, handlers.get());
			Assertions.assertTrue(nameDiagnostics(events).isEmpty(), events.toString());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void optedInUnknownNameDiagnosticsReachTheSharedLifecycleObserver()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		List<LogEvent> events = new CopyOnWriteArrayList<>();
		McpToolRegistration<MirroredArguments> tool = mirroredTool(TOOL_NAME,
				handlers, new AtomicReference<>());
		McpServer server = serverBuilder(List.of(endpoint(MCP_PATH, tool)),
				admissions, CorsAuthorizer.rejectAllInstance())
				.unknownMirroredHeaderNameDiagnostics(true)
				.build();
		Soklet soklet = lifecycleSoklet(server, events);

		try {
			soklet.start();
			Map<String, String> headers = validHeaders();
			headers.put("mCp-PaRaM-Super-Secret-Name", "super-secret-value");
			HttpResponse<String> response = call(boundPort(server), MCP_PATH,
					"diagnostic", TOOL_NAME, mirroredArgumentsJson(), headers);

			assertSuccess(response, "diagnostic");
			Assertions.assertEquals(1, admissions.get());
			Assertions.assertEquals(1, handlers.get());
			assertNameDiagnostic(events, "mCp-PaRaM-Super-Secret-Name",
					"super-secret-value");

			Map<String, String> notificationHeaders = new LinkedHashMap<>();
			notificationHeaders.put("Mcp-Param-Notification-Only",
					"notification-secret-value");
			HttpResponse<String> notification = notifyCancellation(
					boundPort(server), notificationHeaders);
			Assertions.assertEquals(202, notification.statusCode(),
					notification.body());
			Assertions.assertEquals("", notification.body());
			Assertions.assertEquals(2, admissions.get());
			Assertions.assertEquals(1, handlers.get());
			Assertions.assertEquals(1, nameDiagnostics(events).size(),
					events.toString());
			Assertions.assertFalse(events.toString().contains(
					"notification-secret-value"), events.toString());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void diagnosticQuotaIsSharedAcrossEndpointsAndIsolatedAcrossOwners()
			throws Exception {
		String firstPath = "/first";
		String secondPath = "/second";
		String firstToolName = "catalog.first";
		String secondToolName = "catalog.second";
		int port = findFreePort();
		AtomicInteger firstAdmissions = new AtomicInteger();
		AtomicInteger firstHandlers = new AtomicInteger();
		List<LogEvent> firstEvents = new CopyOnWriteArrayList<>();
		McpServer firstServer = serverBuilder(port, List.of(
				endpoint(firstPath, mirroredTool(firstToolName, firstHandlers,
						new AtomicReference<>())),
				endpoint(secondPath, mirroredTool(secondToolName, firstHandlers,
						new AtomicReference<>()))),
				firstAdmissions, CorsAuthorizer.rejectAllInstance())
				.unknownMirroredHeaderNameDiagnostics(true)
				.build();
		Soklet firstSoklet = lifecycleSoklet(firstServer, firstEvents);

		try {
			firstSoklet.start();
			Assertions.assertEquals(port, boundPort(firstServer));
			for (int index = 0; index < 10; index++) {
				String path = index % 2 == 0 ? firstPath : secondPath;
				String toolName = index % 2 == 0
						? firstToolName : secondToolName;
				Map<String, String> headers = validHeaders();
				headers.put("Mcp-Param-Quota-" + index, "private-" + index);
				HttpResponse<String> response = call(port, path, "quota-" + index,
						toolName, mirroredArgumentsJson(), headers);
				assertSuccess(response, "quota-" + index);
			}

			List<LogEvent> diagnostics = nameDiagnostics(firstEvents);
			Assertions.assertEquals(10, diagnostics.size(), firstEvents.toString());
			for (int index = 0; index < 10; index++) {
				String path = index % 2 == 0 ? firstPath : secondPath;
				Assertions.assertEquals(
						"Unknown MCP mirrored header: endpointPath=" + path
								+ ", headerName=Mcp-Param-Quota-" + index,
						diagnostics.get(index).getMessage());
			}

			Map<String, String> overBudgetHeaders = validHeaders();
			overBudgetHeaders.put("Mcp-Param-Over-Budget", "private-over-budget");
			HttpResponse<String> overBudget = call(port, firstPath, "over-budget",
					firstToolName, mirroredArgumentsJson(), overBudgetHeaders);
			assertSuccess(overBudget, "over-budget");
			Assertions.assertEquals(10, nameDiagnostics(firstEvents).size(),
					firstEvents.toString());
			Assertions.assertEquals(11, firstAdmissions.get());
			Assertions.assertEquals(11, firstHandlers.get());
		} finally {
			firstSoklet.close();
		}

		Assertions.assertEquals(SokletStatus.CLOSED, firstSoklet.getStatus());
		Assertions.assertEquals(McpServerStatus.TERMINATED,
				firstServer.getDiagnostics().getStatus());

		AtomicInteger secondAdmissions = new AtomicInteger();
		AtomicInteger secondHandlers = new AtomicInteger();
		List<LogEvent> secondEvents = new CopyOnWriteArrayList<>();
		McpServer secondServer = serverBuilder(port, List.of(
				endpoint(firstPath, mirroredTool(firstToolName, secondHandlers,
						new AtomicReference<>())),
				endpoint(secondPath, mirroredTool(secondToolName, secondHandlers,
						new AtomicReference<>()))),
				secondAdmissions, CorsAuthorizer.rejectAllInstance())
				.unknownMirroredHeaderNameDiagnostics(true)
				.build();
		Soklet secondSoklet = lifecycleSoklet(secondServer, secondEvents);

		try {
			secondSoklet.start();
			Assertions.assertEquals(port, boundPort(secondServer),
					"The first owner must release its exact fixed port before returning.");
			Assertions.assertEquals(McpServerStatus.TERMINATED,
					firstServer.getDiagnostics().getStatus(),
					"The second owner must not revive the first runtime graph.");
			Map<String, String> secondOwnerHeaders = validHeaders();
			secondOwnerHeaders.put("Mcp-Param-Second-Owner",
					"private-second-owner");
			HttpResponse<String> secondOwnerResponse = call(port, secondPath,
					"second-owner", secondToolName, mirroredArgumentsJson(),
					secondOwnerHeaders);
			assertSuccess(secondOwnerResponse, "second-owner");
			Assertions.assertEquals(1, nameDiagnostics(secondEvents).size(),
					secondEvents.toString());
			Assertions.assertEquals(1, secondAdmissions.get());
			Assertions.assertEquals(1, secondHandlers.get());
			Assertions.assertEquals(10, nameDiagnostics(firstEvents).size(),
					"The second owner must not mutate the first owner's quota state.");
		} finally {
			secondSoklet.close();
		}

		Assertions.assertEquals(SokletStatus.CLOSED, secondSoklet.getStatus());
		Assertions.assertEquals(McpServerStatus.TERMINATED,
				secondServer.getDiagnostics().getStatus());
	}

	@Test
	public void strictUnknownPolicyRejectsBeforeAdmissionWithoutReflection()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		List<LogEvent> events = new CopyOnWriteArrayList<>();
		McpToolRegistration<MirroredArguments> tool = mirroredTool(TOOL_NAME,
				handlers, new AtomicReference<>());
		McpServer server = serverBuilder(List.of(endpoint(MCP_PATH, tool)),
				admissions, CorsAuthorizer.rejectAllInstance())
				.unknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS)
				.unknownMirroredHeaderNameDiagnostics(true)
				.build();
		Soklet soklet = lifecycleSoklet(server, events);

		try {
			soklet.start();
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
			assertNameDiagnostic(events, "Mcp-Param-Super-Secret-Name",
					"super-secret-value");
		} finally {
			soklet.close();
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
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("tenant"))
						.build());
		McpEndpoint regionEndpoint = endpoint("/region",
				McpToolRegistration.withName("region.lookup")
						.argumentType(RegionArguments.class)
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("region"))
						.build());
		McpServer server = serverBuilder(
				List.of(tenantEndpoint, regionEndpoint), admissions,
				CorsAuthorizer.fromWhitelistedOrigins(Set.of(origin))).build();
		Soklet soklet = lifecycleSoklet(server, new CopyOnWriteArrayList<>());

		try {
			soklet.start();
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
			soklet.close();
		}
	}

	private static McpToolRegistration<MirroredArguments> mirroredTool(
			String name, AtomicInteger handlers,
			AtomicReference<MirroredArguments> observed) {
		return mirroredTool(name, handlers, observed, new AtomicReference<>());
	}

	private static McpToolRegistration<MirroredArguments> mirroredTool(
			String name, AtomicInteger handlers,
			AtomicReference<MirroredArguments> observed,
			AtomicReference<McpJsonObject> observedRaw) {
		return McpToolRegistration.withName(name)
				.argumentType(MirroredArguments.class)
				.handler((request, arguments, features) -> {
					handlers.incrementAndGet();
					observed.set(arguments.getConvertedArguments());
					observedRaw.set(arguments.getRawArguments());
					return McpCompleteResult.fromToolText("done");
				})
				.build();
	}

	private static void assertBodyAuthoritativeArguments(
			AtomicReference<MirroredArguments> observed,
			AtomicReference<McpJsonObject> observedRaw) {
		Assertions.assertEquals(new MirroredArguments("acme",
				new Routing(true, 42), "reader"), observed.get());
		McpJsonObject raw = observedRaw.get();
		Assertions.assertNotNull(raw);
		Assertions.assertEquals(Set.of("tenant", "routing", "privilege"),
				raw.getMembers().keySet());
		Assertions.assertEquals(McpJsonString.fromValue("reader"),
				raw.find("privilege").orElseThrow());
	}

	private static McpEndpoint endpoint(String path,
			McpToolRegistration<?> tool) {
		return McpEndpoint.withPath(path)
				.serverInformation(McpImplementation.withNameAndVersion(
						"mirrored-header-public-runtime-test",
						"4.0.0-SNAPSHOT").build())
				.tool(tool)
				.build();
	}

	private static McpServer.Builder serverBuilder(List<McpEndpoint> endpoints,
			AtomicInteger admissions, CorsAuthorizer corsAuthorizer) {
		return serverBuilder(0, endpoints, admissions, corsAuthorizer);
	}

	private static McpServer.Builder serverBuilder(int port,
			List<McpEndpoint> endpoints, AtomicInteger admissions,
			CorsAuthorizer corsAuthorizer) {
		return McpServer.withPort(port)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints))
				.admissionController(context -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.accepted();
				})
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(corsAuthorizer)
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static Soklet lifecycleSoklet(McpServer server, List<LogEvent> events) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						events.add(logEvent);
					}
				})
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancellationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownDuration(Duration.ofSeconds(2))
						.forcedShutdownDuration(Duration.ofSeconds(1))
						.build())
				.build());
	}

	private static List<LogEvent> nameDiagnostics(List<LogEvent> events) {
		return events.stream().filter(event -> event.getLogEventType()
				== LogEventType.MCP_UNKNOWN_MIRRORED_HEADER).toList();
	}

	private static void assertNameDiagnostic(List<LogEvent> events,
			String expectedHeaderName, String forbiddenHeaderValue) {
		List<LogEvent> diagnostics = nameDiagnostics(events);
		Assertions.assertEquals(1, diagnostics.size(), events.toString());
		LogEvent diagnostic = diagnostics.get(0);
		Assertions.assertEquals("Unknown MCP mirrored header: endpointPath="
				+ MCP_PATH + ", headerName=" + expectedHeaderName,
				diagnostic.getMessage());
		Assertions.assertFalse(diagnostic.getMessage().contains(forbiddenHeaderValue));
		Assertions.assertFalse(diagnostic.toString().contains(forbiddenHeaderValue));
		Assertions.assertTrue(diagnostic.getThrowable().isEmpty());
		Assertions.assertTrue(diagnostic.getRequest().isEmpty());
		Assertions.assertTrue(diagnostic.getResourceMethod().isEmpty());
		Assertions.assertTrue(diagnostic.getMarshaledResponse().isEmpty());
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

	private static HttpResponse<String> notifyCancellation(int port,
			Map<String, String> headers) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION);
		headers.forEach(request::header);
		return httpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(
				"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
						+ "\"params\":{\"requestId\":\"unknown\"}}",
				StandardCharsets.UTF_8)).build(),
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
				+ "\"dryRun\":true,\"shard\":42},\"privilege\":\"reader\"}";
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
			Routing routing, String privilege) {
	}

	private record Routing(@McpHeader("Dry-Run") boolean dryRun,
			@McpHeader("Shard") int shard) {
	}

	private record TenantArguments(@McpHeader("Tenant") String tenant) {
	}

	private record RegionArguments(@McpHeader("Region") String region) {
	}
}
