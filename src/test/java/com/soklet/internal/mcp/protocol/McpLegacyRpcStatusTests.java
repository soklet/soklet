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

import com.soklet.LifecyclePolicy;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpAdmissionRejection;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpIcon;
import com.soklet.McpJsonRpcError;
import com.soklet.McpProgressUpdate;
import com.soklet.McpProtocolVersion;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceLink;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpSessionConfig;
import com.soklet.McpTextResourceContents;
import com.soklet.McpToolRegistration;
import com.soklet.McpToolOutput;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Separates accepted RPC errors from HTTP/admission failures on each selected profile. */
@Timeout(60)
class McpLegacyRpcStatusTests {
	private static final List<McpProtocolVersion> LEGACY = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final Set<McpProtocolVersion> ALL = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);

	@Test
	void legacyOperationErrorsUseHttp200WithAndWithoutSessions() throws Exception {
		for (boolean sessions : List.of(false, true))
			try (Fixture fixture = new Fixture(sessions)) {
				for (McpProtocolVersion version : LEGACY) {
					String session = sessions ? fixture.initialize(version) : null;
					assertError(fixture.call(version, session, "tools/call",
							"\"name\":\"missing\",\"arguments\":{}"), 200, -32602);
					assertError(fixture.call(version, session, "tools/call",
							"\"name\":\"work\",\"arguments\":[]"), 200, -32602);
					assertError(fixture.call(version, session, "resources/read",
							"\"uri\":\"test://missing\""), 200, -32002);
					assertError(fixture.call(version, session, "tools/list",
							"\"cursor\":\"invalid\""), 200, -32602);
					assertError(fixture.call(version, session, "tools/call",
							"\"name\":\"fail\",\"arguments\":{}"), 200, -32603);
					Capture streamed = fixture.call(version, session, "tools/call",
							"\"name\":\"fail\",\"arguments\":{},\"_meta\":{\"progressToken\":\"p\"}");
					assertError(streamed, 200, -32603);
					assertEquals("text/event-stream", streamed.contentType());
					assertTrue(streamed.body().contains("notifications/progress"), streamed.body());
					assertFalse(streamed.body().contains("handler-private-detail"), streamed.body());
					assertEquals(200, fixture.call(version, session, "tools/call",
							"\"name\":\"work\",\"arguments\":{}").status(),
							"An RPC failure must leave the endpoint/session usable.");
				}
			}
	}

	@Test
	void legacyIconProjectionPreservesCompletedResultsAndRunsTheHandlerOnce() throws Exception {
		for (boolean sessions : List.of(false, true))
			try (Fixture fixture = new Fixture(sessions)) {
				for (McpProtocolVersion version : LEGACY) {
					String session = sessions ? fixture.initialize(version) : null;
					int before = fixture.linkCalls.get();
					Capture response = fixture.call(version, session, "tools/call", "\"name\":\"link\",\"arguments\":{}");
					assertEquals(200, response.status(), response.body());
					assertTrue(response.body().contains("\"type\":\"resource_link\""), response.body());
					assertFalse(response.body().contains("\"error\""), response.body());
					assertEquals(version == McpProtocolVersion.V2025_11_25, response.body().contains("\"icons\""), response.body());
					assertEquals(before + 1, fixture.linkCalls.get());
				}
			}
	}

	@Test
	void statelessAdmissionAndRateRejectionsKeepTheirHttpStatus() throws Exception {
		try (Fixture fixture = new Fixture(false)) {
			for (McpProtocolVersion version : LEGACY) {
				for (int status : List.of(400, 401, 403, 404, 405, 500, 503)) {
					Capture rejection = fixture.call(version, null, "tools/call",
							"\"name\":\"work\",\"arguments\":{}",
							List.of(new McpChunkedHttpClient.RequestHeader("X-Reject", Integer.toString(status))));
					assertEquals(status, rejection.status(), rejection.body());
				}
				assertEquals(429, fixture.call(version, null, "tools/call",
						"\"name\":\"work\",\"arguments\":{}",
						List.of(new McpChunkedHttpClient.RequestHeader("X-Limit", "deny"))).status());
			}
		}
	}

	@Test
	void admissionHookFailuresRemainHttpFailures() throws Exception {
		try (Fixture fixture = new Fixture(false)) {
			for (McpProtocolVersion version : LEGACY)
				for (String failure : List.of("throw", "null")) {
					assertEquals(500, fixture.call(version, null, "tools/call",
							"\"name\":\"work\",\"arguments\":{}",
							List.of(new McpChunkedHttpClient.RequestHeader("X-Reject", failure))).status());
					assertEquals(500, fixture.call(version, null, "tools/call",
							"\"name\":\"work\",\"arguments\":{}",
							List.of(new McpChunkedHttpClient.RequestHeader("X-Limit", failure))).status());
				}
		}
	}

	@Test
	void framingAndSessionFailuresRemainTransportErrors() throws Exception {
		try (Fixture fixture = new Fixture(true)) {
			for (McpProtocolVersion version : LEGACY) {
				assertEquals(400, fixture.call(version, null, "tools/call",
						"\"name\":\"work\",\"arguments\":{}").status());
				assertEquals(404, fixture.call(version, "unknown-valid", "tools/call",
						"\"name\":\"work\",\"arguments\":{}").status());
				assertEquals(400, fixture.post("{", List.of(new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", version.getWireValue()))).status());
			}
			assertEquals(400, fixture.post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
					List.of(new McpChunkedHttpClient.RequestHeader("MCP-Protocol-Version", "2025-03-26"))).status());
		}
	}

	@Test
	void modernOperationErrorsRetainTheirStatusTable() throws Exception {
		try (Fixture fixture = new Fixture(false)) {
			McpProtocolVersion version = McpProtocolVersion.V2026_07_28;
			assertError(fixture.call(version, null, "tools/call",
					"\"name\":\"missing\",\"arguments\":{}"), 400, -32602);
			assertError(fixture.call(version, null, "resources/read",
					"\"uri\":\"test://missing\""), 400, -32602);
			assertError(fixture.call(version, null, "tools/call",
					"\"name\":\"fail\",\"arguments\":{}"), 500, -32603);
		}
	}

	private static void assertError(Capture capture, int status, int code) {
		assertEquals(status, capture.status(), capture.body());
		assertTrue(capture.body().contains("\"id\":1"), capture.body());
		assertTrue(capture.body().contains("\"code\":" + code), capture.body());
	}

	private record Capture(int status, String contentType, String sessionId, String body) {}

	private static final class Fixture implements AutoCloseable {
		private final McpServer server;
		private final Soklet soklet;
		private final AtomicInteger linkCalls = new AtomicInteger();

		Fixture(boolean sessions) {
			URI uri = URI.create("test://resource");
			McpEndpoint.Builder endpointBuilder = McpEndpoint.withPath("/mcp",
					McpImplementation.withNameAndVersion("rpc-status", "test").build(), ALL)
					.toolRegistrations(List.of(
							McpToolRegistration.withName("work", ALL).jsonObjectArguments()
									.handler((requestContext, toolArguments, invocationFeatures) ->
											McpCompleteResult.fromToolText("ok")).build(),
							McpToolRegistration.withName("fail", ALL).jsonObjectArguments()
									.handler((requestContext, toolArguments, invocationFeatures) -> {
										invocationFeatures.getProgressReporter().ifPresent(reporter ->
												reporter.report(McpProgressUpdate.withProgress(1.0).build()));
										throw new IllegalStateException("handler-private-detail");
									}).build(),
							McpToolRegistration.withName("link", ALL).jsonObjectArguments()
									.handler((requestContext, toolArguments, invocationFeatures) -> {
										linkCalls.incrementAndGet();
										return McpCompleteResult.withToolOutput(McpToolOutput.builder().content(List.of(
												McpResourceLink.withUriAndName(uri, "resource")
														.addIcon(McpIcon.withSource(URI.create("https://example.test/icon.png")).build())
														.build())).build()).build();
									}).build()))
					.resourceRegistrations(List.of(McpResourceRegistration.withUriAndName(uri, "resource", ALL)
							.handler((requestContext, resourceReadContext, invocationFeatures) ->
									McpCompleteResult.withResourceOutput(McpResourceOutput.fromContents(
										List.of(McpTextResourceContents.withUriAndText(uri, "resource").build()))).build())
							.build()));
			if (sessions)
				endpointBuilder.sessionProtocolVersions(Set.copyOf(LEGACY));
			server = McpServer.withPort(0)
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpointBuilder.build())))
					.sessionConfig(sessions ? McpSessionConfig.withOwnerKeyResolver(identity -> "owner").build() : null)
					.admissionController(admissionContext -> {
						String rejection = admissionContext.getRequest().getHeaders().getOrDefault("X-Reject", List.of())
								.stream().findFirst().orElse(null);
						if ("throw".equals(rejection))
							throw new IllegalStateException("admission-private-detail");
						if ("null".equals(rejection))
							return null;
						return rejection == null ? McpAdmissionDecision.accepted(McpAdmissionIdentity.withRateLimitPartitionKey("owner")
								.authorizationPartitionKey("owner").principal("owner").build()) :
								McpAdmissionDecision.rejected(McpAdmissionRejection.withStatusCodeAndError(
										Integer.parseInt(rejection), McpJsonRpcError.fromApplication(-31903, "Denied")).build());
					})
					.requestRateLimiter(rateLimitContext -> {
						String limit = rateLimitContext.getRequest().getHeader("X-Limit").orElse(null);
						if ("throw".equals(limit))
							throw new IllegalStateException("limiter-private-detail");
						if ("null".equals(limit))
							return null;
						return limit == null ? McpRateLimitDecision.allowed() : McpRateLimitDecision.denied(Duration.ofSeconds(1));
					})
					.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
			soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
							.gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
			soklet.start();
		}

		String initialize(McpProtocolVersion version) throws Exception {
			Capture capture = post("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{"
					+ "\"protocolVersion\":\"" + version.getWireValue() + "\",\"capabilities\":{},"
					+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}", List.of());
			assertEquals(200, capture.status(), capture.body());
			assertNotNull(capture.sessionId(), capture.body());
			return capture.sessionId();
		}

		Capture call(McpProtocolVersion version, String session, String method, String params) throws Exception {
			return call(version, session, method, params, List.of());
		}

		Capture call(McpProtocolVersion version, String session, String method, String params,
				List<McpChunkedHttpClient.RequestHeader> additionalHeaders) throws Exception {
			List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>(additionalHeaders);
			headers.add(new McpChunkedHttpClient.RequestHeader("MCP-Protocol-Version", version.getWireValue()));
			if (session != null)
				headers.add(new McpChunkedHttpClient.RequestHeader("MCP-Session-Id", session));
			if (version == McpProtocolVersion.V2026_07_28) {
				headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Method", method));
				params += ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":{}}";
				if ("tools/call".equals(method)) {
					String name = params.substring(params.indexOf(':') + 2, params.indexOf("\",\"arguments"));
					headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Name", name));
				}
				if ("resources/read".equals(method))
					headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Name", "test://missing"));
			}
			return post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{" + params + "}}", headers);
		}

		Capture post(String body, List<McpChunkedHttpClient.RequestHeader> headers) throws Exception {
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(
					server.getDiagnostics().getBoundAddress().orElseThrow().getPort(), body, headers)) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				String response;
				if (head.headers().getOrDefault("transfer-encoding", List.of()).contains("chunked")) {
					StringBuilder text = new StringBuilder();
					byte[] chunk;
					while ((chunk = client.readChunk()) != null)
						text.append(new String(chunk, StandardCharsets.UTF_8));
					response = text.toString();
				} else {
					response = client.readFixedBody(head);
				}
				return new Capture(head.status(), head.headers().getOrDefault("content-type", List.of())
						.stream().findFirst().orElse(null), head.headers().getOrDefault("mcp-session-id", List.of())
						.stream().findFirst().orElse(null), response);
			}
		}

		@Override public void close() { soklet.close(); }
	}
}
