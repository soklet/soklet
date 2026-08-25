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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real-listener and negative-inventory coverage for self-reported MCP metadata.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpSelfReportedIdentityPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TOOL_NAME = "identity.boundary";

	@Test
	public void forgedClientInformationCannotAuthenticateAuthorizeOrRewriteIdentity()
			throws Exception {
		IdentityFixture alpha = identity("alpha");
		IdentityFixture beta = identity("beta");
		Map<String, IdentityFixture> identities = Map.of(
				"Bearer credential-alpha", alpha,
				"Bearer credential-beta", beta);
		List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		List<McpRateLimitContext> rateLimits = new CopyOnWriteArrayList<>();
		List<McpRequestContext> handlers = new CopyOnWriteArrayList<>();
		McpAdmissionRejection rejection = McpAdmissionRejection
				.withStatusCodeAndError(401,
						McpJsonRpcError.fromApplication(1_001, "Authentication required"))
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlers.add(request);
					return McpCompleteResult.fromToolText("allowed");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"self-report-identity-test", "4.0.0-SNAPSHOT").build())
				.tool(tool)
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> {
					admissions.add(context);
					IdentityFixture identity = context.getRequest()
							.getHeader("Authorization")
							.map(identities::get)
							.orElse(null);
					return identity == null
							? McpAdmissionDecision.rejected(rejection)
							: McpAdmissionDecision.accepted(identity.identity());
				})
				.requestRateLimiter(context -> {
					rateLimits.add(context);
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					rateLimits.add(context);
					return McpRateLimitDecision.allowed();
				})
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> alphaResponse = call(port, "alpha",
					"Bearer credential-alpha", "reported-beta", beta);
			HttpResponse<String> betaResponse = call(port, "beta",
					"Bearer credential-beta", "reported-alpha", alpha);
			HttpResponse<String> rejectedResponse = call(port, "rejected",
					"Bearer rejected", "reported-alpha", alpha);

			assertStatus(alphaResponse, 200, "alpha");
			assertStatus(betaResponse, 200, "beta");
			assertStatus(rejectedResponse, 401, "rejected");
			Assertions.assertFalse(rejectedResponse.body().contains("reported-alpha"),
					rejectedResponse.body());

			Assertions.assertEquals(3, admissions.size());
			assertSelfReport(admissions.get(0), "Bearer credential-alpha",
					"reported-beta");
			assertSelfReport(admissions.get(1), "Bearer credential-beta",
					"reported-alpha");
			assertSelfReport(admissions.get(2), "Bearer rejected", "reported-alpha");

			Assertions.assertEquals(4, rateLimits.size(),
					"Rejected admission must not reach either limiter.");
			assertRateLimit(rateLimits.get(0), McpRateLimitTarget.REQUEST, alpha);
			assertRateLimit(rateLimits.get(1), McpRateLimitTarget.TOOL, alpha);
			assertRateLimit(rateLimits.get(2), McpRateLimitTarget.REQUEST, beta);
			assertRateLimit(rateLimits.get(3), McpRateLimitTarget.TOOL, beta);

			Assertions.assertEquals(2, handlers.size(),
					"Rejected admission must not reach the handler.");
			assertHandler(handlers.get(0), "reported-beta", alpha);
			assertHandler(handlers.get(1), "reported-alpha", beta);
		} finally {
			server.stop();
		}
	}

	@Test
	public void admittedIdentitySurfaceHasNoSelfReportedMetadataShortcut()
			throws Exception {
		Set<Class<?>> selfReportedTypes = Set.of(
				McpImplementation.class,
				McpAdmissionContext.class,
				McpRequestContext.class);
		for (Class<?> type : List.of(
				McpAdmissionIdentity.class,
				McpAdmissionIdentity.Builder.class,
				McpAdmissionDecision.class,
				McpAdmissionDecision.Accepted.class,
				McpRateLimitContext.class)) {
			for (Method method : type.getDeclaredMethods()) {
				if (!Modifier.isPublic(method.getModifiers()))
					continue;
				Assertions.assertFalse(selfReportedTypes.contains(method.getReturnType()),
						() -> "Identity surface returns self-reported metadata: " + method);
				for (Class<?> parameterType : method.getParameterTypes())
					Assertions.assertFalse(selfReportedTypes.contains(parameterType),
							() -> "Identity surface accepts self-reported metadata: "
									+ method);
			}
		}
		Assertions.assertFalse(McpAdmissionIdentity.class
				.isAssignableFrom(McpImplementation.class));
		Assertions.assertFalse(McpImplementation.class
				.isAssignableFrom(McpAdmissionIdentity.class));
		Assertions.assertTrue(McpRequestContext.class
				.getMethod("getAdmissionIdentity").getReturnType()
					== McpAdmissionIdentity.class);
		Assertions.assertTrue(McpRequestContext.class
				.getMethod("getClientInfo").getReturnType() == java.util.Optional.class);

		String internalAdmission = source(
				"src/main/java/com/soklet/internal/mcp/protocol/McpAdmissionDecision.java");
		String effectiveIdentity = slice(internalAdmission,
				"record McpEffectiveAdmissionIdentity",
				"record McpEndpointPartitionIdentity");
		Assertions.assertTrue(effectiveIdentity.contains(
				"admittedIdentity.rateLimitPartitionKey()"), effectiveIdentity);
		Assertions.assertTrue(effectiveIdentity.contains(
				"admittedIdentity.authorizationPartitionKey()"), effectiveIdentity);
		assertNoSelfReportedIdentitySource(effectiveIdentity);

		String defaultServer = source("src/main/java/com/soklet/DefaultMcpServer.java");
		String rateLimitProjection = slice(defaultServer,
				"final class DefaultMcpRateLimitContext",
				"final class DefaultMcpRequestContext");
		Assertions.assertTrue(rateLimitProjection.contains(
				"this.input.admissionIdentity()"), rateLimitProjection);
		assertNoSelfReportedIdentitySource(rateLimitProjection);
	}

	private static IdentityFixture identity(String name) {
		Object principal = new Object();
		Object applicationContext = new Object();
		McpAdmissionIdentity identity = McpAdmissionIdentity
				.withRateLimitPartitionKey("rate-" + name)
				.authorizationPartitionKey("authorization-" + name)
				.principal(principal)
				.applicationContext(applicationContext)
				.build();
		return new IdentityFixture(name, identity, principal, applicationContext);
	}

	private static HttpResponse<String> call(int port, String id,
			String authorization, String reportedName, IdentityFixture forgedIdentity)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{},"
				+ "\"io.modelcontextprotocol/clientInfo\":{"
				+ "\"name\":\"" + reportedName + "\","
				+ "\"version\":\"self-reported\","
				+ "\"rateLimitPartitionKey\":\""
				+ forgedIdentity.identity().getRateLimitPartitionKey() + "\","
				+ "\"authorizationPartitionKey\":\""
				+ forgedIdentity.identity().getAuthorizationPartitionKey().orElseThrow()
				+ "\",\"principal\":\"" + forgedIdentity.name() + "\","
				+ "\"applicationContext\":\"" + forgedIdentity.name() + "\"}},"
				+ "\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("Authorization", authorization)
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", TOOL_NAME)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void assertStatus(HttpResponse<String> response,
			int expectedStatus, String expectedId) {
		Assertions.assertEquals(expectedStatus, response.statusCode(), response.body());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + expectedId + "\""), response.body());
	}

	private static void assertSelfReport(McpAdmissionContext context,
			String authorization, String expectedName) {
		Assertions.assertEquals(authorization,
				context.getRequest().getHeader("Authorization").orElseThrow());
		Assertions.assertEquals(expectedName,
				context.getClientInfo().orElseThrow().getName());
	}

	private static void assertRateLimit(McpRateLimitContext context,
			McpRateLimitTarget expectedTarget, IdentityFixture expectedIdentity) {
		Assertions.assertEquals(expectedTarget, context.getTarget());
		Assertions.assertEquals("tools/call", context.getJsonRpcMethod());
		Assertions.assertEquals(TOOL_NAME, context.getOperationName().orElseThrow());
		Assertions.assertEquals("Bearer credential-" + expectedIdentity.name(),
				context.getRequest().getHeader("Authorization").orElseThrow());
		assertIdentity(context.getAdmissionIdentity(), expectedIdentity);
	}

	private static void assertHandler(McpRequestContext context,
			String expectedClientName, IdentityFixture expectedIdentity) {
		Assertions.assertEquals(expectedClientName,
				context.getClientInfo().orElseThrow().getName());
		assertIdentity(context.getAdmissionIdentity(), expectedIdentity);
	}

	private static void assertIdentity(McpAdmissionIdentity actual,
			IdentityFixture expected) {
		Assertions.assertEquals(expected.identity().getRateLimitPartitionKey(),
				actual.getRateLimitPartitionKey());
		Assertions.assertEquals(expected.identity().getAuthorizationPartitionKey(),
				actual.getAuthorizationPartitionKey());
		Assertions.assertSame(expected.principal(), actual.getPrincipal().orElseThrow());
		Assertions.assertSame(expected.applicationContext(),
				actual.getApplicationContext().orElseThrow());
	}

	private static String source(String path) throws Exception {
		return Files.readString(Path.of(path), StandardCharsets.UTF_8);
	}

	private static String slice(String source, String startMarker, String endMarker) {
		int start = source.indexOf(startMarker);
		int end = source.indexOf(endMarker, start + startMarker.length());
		Assertions.assertTrue(start >= 0 && end > start,
				() -> "Missing source inventory boundary: " + startMarker + " -> "
						+ endMarker);
		return source.substring(start, end);
	}

	private static void assertNoSelfReportedIdentitySource(String source) {
		for (String forbidden : List.of(
				"clientInfo", "clientInformation", "serverInformation",
				"McpImplementation"))
			Assertions.assertFalse(source.contains(forbidden),
					() -> "Identity derivation references self-reported metadata: "
							+ forbidden + " in " + source);
	}

	private record IdentityFixture(String name, McpAdmissionIdentity identity,
			Object principal, Object applicationContext) {}
}
