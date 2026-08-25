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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@NotThreadSafe
@Timeout(30)
public class McpHttpServerCustomHeaderTests {
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String UNSUPPORTED_VERSION = "2099-01-01";
	private static final String METHOD = "tools/call";
	private static final String TOOL = "execute_sql";
	private static final McpMirroredHeaderPlan MIRRORED_HEADERS =
			new McpMirroredHeaderPlan(List.of(
					new McpMirroredHeaderDeclaration("Tenant", List.of("tenant"),
							McpMirroredHeaderValueType.STRING),
					new McpMirroredHeaderDeclaration("Dry-Run",
							List.of("routing", "dryRun"),
							McpMirroredHeaderValueType.BOOLEAN),
					new McpMirroredHeaderDeclaration("Shard",
							List.of("routing", "shard"),
							McpMirroredHeaderValueType.INTEGER),
					new McpMirroredHeaderDeclaration("Optional",
							List.of("optional"), McpMirroredHeaderValueType.STRING)));

	@Test
	public void custom_mirrors_decode_all_types_compare_numerically_and_strip_ows()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(acceptingPolicy(admissions), handlers);

		try {
			int port = runtime.start().getPort();
			FixedResponse response = send(port, "1",
					"{\"tenant\":\"Hello, 世界\",\"routing\":{"
							+ "\"dryRun\":true,\"shard\":42.0}}",
					customHeaders(
							new McpChunkedHttpClient.RequestHeader("Mcp-Param-Tenant",
									" \t=?base64?SGVsbG8sIOS4lueVjA==?= \t"),
							new McpChunkedHttpClient.RequestHeader("mcp-param-dry-run",
									"=?base64?dHJ1ZQ==?="),
							new McpChunkedHttpClient.RequestHeader("MCP-PARAM-SHARD",
									"=?base64?NDI=?=")));

			Assertions.assertEquals(200, response.head().status(), response.head().raw());
			Assertions.assertTrue(response.body().contains("\"resultType\":\"complete\""),
					response.body());

			FixedResponse nullOptional = send(port, "2",
					requiredArgumentsWithOptional("null"),
					validRequiredHeaders("tenant", "true", "42"));
			Assertions.assertEquals(200, nullOptional.head().status(),
					nullOptional.head().raw());
			Assertions.assertEquals(2, admissions.get());
			Assertions.assertEquals(2, handlers.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void custom_integer_accepts_the_safe_range_boundaries() throws Exception {
		AtomicInteger handlers = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(acceptingPolicy(new AtomicInteger()), handlers);

		try {
			int port = runtime.start().getPort();
			for (String integer : List.of(
					"-9007199254740991", "9007199254740991")) {
				FixedResponse response = send(port, integer.startsWith("-") ? "2" : "3",
						"{\"tenant\":\"t\",\"routing\":{"
								+ "\"dryRun\":false,\"shard\":" + integer + "}}",
						validRequiredHeaders("t", "false", integer));
				Assertions.assertEquals(200, response.head().status(), response.head().raw());
			}
			Assertions.assertEquals(2, handlers.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void custom_mirror_failures_are_header_mismatches_before_admission()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(acceptingPolicy(admissions), handlers);

		try {
			int port = runtime.start().getPort();
			List<FailureCase> cases = List.of(
					new FailureCase("10", requiredArguments(), customHeaders(
							boolHeader("true"), shardHeader("42"))),
					new FailureCase("11", requiredArguments(), customHeaders(
							tenantHeader("tenant"),
							new McpChunkedHttpClient.RequestHeader(
									"mcp-param-tenant", "tenant"),
							boolHeader("true"), shardHeader("42"))),
					new FailureCase("12", requiredArgumentsWithOptional("null"),
							customHeaders(tenantHeader("tenant"), boolHeader("true"),
									shardHeader("42"), optionalHeader("unexpected"))),
					new FailureCase("13", requiredArguments(),
							validRequiredHeaders("tenant", "TRUE", "42")),
					new FailureCase("14", requiredArguments(),
							validRequiredHeaders("tenant", "true", "42.0")),
					new FailureCase("15", "{\"tenant\":\"tenant\",\"routing\":{"
							+ "\"dryRun\":true,\"shard\":9007199254740992}}",
							validRequiredHeaders("tenant", "true",
									"9007199254740992")),
					new FailureCase("16", requiredArguments(),
							validRequiredHeaders("=?base64?***secret***?=", "true", "42")),
					new FailureCase("17", "{\"routing\":{\"dryRun\":true,"
							+ "\"shard\":42}}",
							validRequiredHeaders("tenant", "true", "42")));

			for (FailureCase failureCase : cases) {
				FixedResponse response = send(port, failureCase.id(),
						failureCase.argumentsJson(), failureCase.headers());
				assertError(response, failureCase.id(), -32_020);
				Assertions.assertFalse(response.body().contains("secret"));
			}
			Assertions.assertEquals(0, admissions.get());
			Assertions.assertEquals(0, handlers.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void unknown_custom_headers_are_untrusted_ignored_and_counted_by_default()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(acceptingPolicy(admissions), handlers);

		try {
			int port = runtime.start().getPort();
			List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>(
					validRequiredHeaders("tenant", "true", "42"));
			headers.add(new McpChunkedHttpClient.RequestHeader(
					"Mcp-Param-Unregistered", "=?base64?***secret***?="));
			headers.add(new McpChunkedHttpClient.RequestHeader(
					"mcp-param-unregistered", "another-secret"));
			FixedResponse response = send(port, "20", requiredArguments(), headers);

			Assertions.assertEquals(200, response.head().status(), response.head().raw());
			Assertions.assertEquals(1, admissions.get());
			Assertions.assertEquals(1, handlers.get());
			Assertions.assertEquals(2,
					runtime.requestExecutionSnapshot().unknownMirroredHeaderOccurrences());
			Assertions.assertFalse(response.body().contains("secret"));
		} finally {
			runtime.close();
		}
	}

	@Test
	public void strict_unknown_rejection_is_fixed_but_recognized_mismatch_wins()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		McpHttpEndpointPolicy strictPolicy = acceptingPolicy(admissions)
				.withUnknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS);
		McpHttpServerRuntime runtime = runtime(strictPolicy, handlers);

		try {
			int port = runtime.start().getPort();
			List<McpChunkedHttpClient.RequestHeader> unknown = new ArrayList<>(
					validRequiredHeaders("tenant", "true", "42"));
			unknown.add(new McpChunkedHttpClient.RequestHeader(
					"Mcp-Param-Super-Secret-Name", "super-secret-value"));
			FixedResponse strict = send(port, "30", requiredArguments(), unknown);
			assertError(strict, "30", -31_998);
			Assertions.assertTrue(strict.body().contains(
					"\"message\":\"Unknown mirrored header\""), strict.body());
			Assertions.assertFalse(strict.body().contains("Super-Secret"));
			Assertions.assertFalse(strict.body().contains("super-secret-value"));

			List<McpChunkedHttpClient.RequestHeader> compound = new ArrayList<>(unknown);
			compound.removeIf(header -> header.name().equalsIgnoreCase(
					"Mcp-Param-Tenant"));
			FixedResponse recognizedMismatch = send(port, "31", requiredArguments(),
					compound);
			assertError(recognizedMismatch, "31", -32_020);
			Assertions.assertEquals(0, admissions.get());
			Assertions.assertEquals(0, handlers.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void unsupportedSelectorAddsOnlyBoundedDataToCustomHeaderWinners()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		McpHttpEndpointPolicy strictPolicy = acceptingPolicy(admissions)
				.withUnknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS);
		McpHttpServerRuntime runtime = runtime(strictPolicy, handlers);

		try {
			int port = runtime.start().getPort();
			FixedResponse recognizedMismatch = send(port, "32", requiredArguments(),
					withProtocolVersion(customHeaders(
							boolHeader("true"), shardHeader("42")),
							UNSUPPORTED_VERSION));
			assertError(recognizedMismatch, "32", -32_020);
			assertSupportedVersionsDiagnostic(recognizedMismatch);
			Assertions.assertTrue(recognizedMismatch.body().contains(
					"\"message\":\"Header mismatch\""), recognizedMismatch.body());
			Assertions.assertFalse(recognizedMismatch.body().contains(
					UNSUPPORTED_VERSION));

			List<McpChunkedHttpClient.RequestHeader> strictHeaders = new ArrayList<>(
					withProtocolVersion(validRequiredHeaders("tenant", "true", "42"),
							UNSUPPORTED_VERSION));
			strictHeaders.add(new McpChunkedHttpClient.RequestHeader(
					"Mcp-Param-Super-Secret-Name", "super-secret-value"));
			FixedResponse strict = send(port, "33", requiredArguments(), strictHeaders);
			assertError(strict, "33", -31_998);
			assertSupportedVersionsDiagnostic(strict);
			Assertions.assertTrue(strict.body().contains(
					"\"message\":\"Unknown mirrored header\""), strict.body());
			Assertions.assertFalse(strict.body().contains("Super-Secret"));
			Assertions.assertFalse(strict.body().contains("super-secret-value"));
			Assertions.assertFalse(strict.body().contains(UNSUPPORTED_VERSION));
			Assertions.assertEquals(0, admissions.get());
			Assertions.assertEquals(0, handlers.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void custom_header_errors_preserve_authorized_cors_response_headers()
			throws Exception {
		String origin = "https://allowed.example";
		AtomicInteger admissions = new AtomicInteger();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.fromWhitelistedOrigins(Set.of(origin)), ignored -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				})
				.withUnknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS);
		McpHttpServerRuntime runtime = runtime(policy, new AtomicInteger());

		try {
			int port = runtime.start().getPort();
			List<McpChunkedHttpClient.RequestHeader> missingMirror = new ArrayList<>(
					customHeaders(boolHeader("true"), shardHeader("42")));
			missingMirror.add(new McpChunkedHttpClient.RequestHeader("Origin", origin));
			FixedResponse mismatch = send(port, "40", requiredArguments(), missingMirror);
			assertError(mismatch, "40", -32_020);
			assertCors(mismatch, origin);

			List<McpChunkedHttpClient.RequestHeader> unknown = new ArrayList<>(
					validRequiredHeaders("tenant", "true", "42"));
			unknown.add(new McpChunkedHttpClient.RequestHeader(
					"Mcp-Param-Unknown", "untrusted"));
			unknown.add(new McpChunkedHttpClient.RequestHeader("Origin", origin));
			FixedResponse strict = send(port, "41", requiredArguments(), unknown);
			assertError(strict, "41", -31_998);
			assertCors(strict, origin);
			Assertions.assertEquals(0, admissions.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void custom_mirror_registration_is_scoped_to_the_selected_tool()
			throws Exception {
		String otherTool = "other";
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"custom-header-scope-test", "4.0.0-SNAPSHOT"))
				.tool(new McpNormalizedOperation(TOOL,
						McpInputRequestPlan.empty(), MIRRORED_HEADERS))
				.tool(new McpNormalizedOperation(otherTool,
						McpInputRequestPlan.empty(), McpMirroredHeaderPlan.empty()))
				.build();
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		McpHttpEndpointPolicy defaultPolicy = acceptingPolicy(admissions);
		McpHttpServerRuntime permissive = runtime(
				defaultPolicy, handlers, endpoint);

		try {
			int port = permissive.start().getPort();
			FixedResponse ignored = sendTool(port, "50", otherTool, "{}", List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION),
					new McpChunkedHttpClient.RequestHeader("Mcp-Method", METHOD),
					new McpChunkedHttpClient.RequestHeader("Mcp-Name", otherTool),
					new McpChunkedHttpClient.RequestHeader(
							"Mcp-Param-Tenant", "belongs-to-another-tool")));
			Assertions.assertEquals(200, ignored.head().status(), ignored.head().raw());
			Assertions.assertEquals(1, admissions.get());
			Assertions.assertEquals(1, handlers.get());
			Assertions.assertEquals(1,
					permissive.requestExecutionSnapshot()
							.unknownMirroredHeaderOccurrences());
		} finally {
			permissive.close();
		}

		McpHttpServerRuntime strict = runtime(defaultPolicy
				.withUnknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS),
				handlers, endpoint);
		try {
			int port = strict.start().getPort();
			FixedResponse rejected = sendTool(port, "51", otherTool, "{}", List.of(
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", PROTOCOL_VERSION),
					new McpChunkedHttpClient.RequestHeader("Mcp-Method", METHOD),
					new McpChunkedHttpClient.RequestHeader("Mcp-Name", otherTool),
					new McpChunkedHttpClient.RequestHeader(
							"Mcp-Param-Tenant", "belongs-to-another-tool")));
			assertError(rejected, "51", -31_998);
			Assertions.assertEquals(1, admissions.get());
			Assertions.assertEquals(1, handlers.get());
		} finally {
			strict.close();
		}
	}

	private static McpHttpEndpointPolicy acceptingPolicy(AtomicInteger admissions) {
		return McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
				ignored -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				});
	}

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy,
			AtomicInteger handlers) {
		McpNormalizedOperation tool = new McpNormalizedOperation(TOOL,
				McpInputRequestPlan.empty(), MIRRORED_HEADERS);
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"custom-header-test", "4.0.0-SNAPSHOT"))
				.tool(tool)
				.build();
		return runtime(policy, handlers, endpoint);
	}

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy,
			AtomicInteger handlers, McpNormalizedEndpoint endpoint) {
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromHandlers(
				Map.of(METHOD, ignored -> {
					handlers.incrementAndGet();
					return McpWireResult.complete(new McpJsonObject(
							Map.of("ok", McpJsonBoolean.TRUE)));
				}));
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), policy, endpoint,
				router, McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM);
	}

	private static FixedResponse send(int port, String id, String argumentsJson,
			List<McpChunkedHttpClient.RequestHeader> headers) throws Exception {
		return sendTool(port, id, TOOL, argumentsJson, headers);
	}

	private static FixedResponse sendTool(int port, String id, String tool,
			String argumentsJson,
			List<McpChunkedHttpClient.RequestHeader> headers) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id
				+ ",\"method\":\"tools/call\",\"params\":{\"name\":\""
				+ tool + "\",\"arguments\":" + argumentsJson + ",\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL_VERSION
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		try (McpChunkedHttpClient client =
					McpChunkedHttpClient.postMcpMessage(port, body, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			return new FixedResponse(head, client.readFixedBody(head));
		}
	}

	private static List<McpChunkedHttpClient.RequestHeader> customHeaders(
			McpChunkedHttpClient.RequestHeader... customHeaders) {
		List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>();
		headers.add(new McpChunkedHttpClient.RequestHeader(
				"MCP-Protocol-Version", PROTOCOL_VERSION));
		headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Method", METHOD));
		headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Name", TOOL));
		headers.addAll(List.of(customHeaders));
		return List.copyOf(headers);
	}

	private static List<McpChunkedHttpClient.RequestHeader> validRequiredHeaders(
			String tenant, String dryRun, String shard) {
		return customHeaders(tenantHeader(tenant), boolHeader(dryRun), shardHeader(shard));
	}

	private static List<McpChunkedHttpClient.RequestHeader> withProtocolVersion(
			List<McpChunkedHttpClient.RequestHeader> headers, String version) {
		List<McpChunkedHttpClient.RequestHeader> copied = new ArrayList<>(headers);
		copied.set(0, new McpChunkedHttpClient.RequestHeader(
				"MCP-Protocol-Version", version));
		return List.copyOf(copied);
	}

	private static McpChunkedHttpClient.RequestHeader tenantHeader(String value) {
		return new McpChunkedHttpClient.RequestHeader("Mcp-Param-Tenant", value);
	}

	private static McpChunkedHttpClient.RequestHeader boolHeader(String value) {
		return new McpChunkedHttpClient.RequestHeader("Mcp-Param-Dry-Run", value);
	}

	private static McpChunkedHttpClient.RequestHeader shardHeader(String value) {
		return new McpChunkedHttpClient.RequestHeader("Mcp-Param-Shard", value);
	}

	private static McpChunkedHttpClient.RequestHeader optionalHeader(String value) {
		return new McpChunkedHttpClient.RequestHeader("Mcp-Param-Optional", value);
	}

	private static String requiredArguments() {
		return "{\"tenant\":\"tenant\",\"routing\":{"
				+ "\"dryRun\":true,\"shard\":42}}";
	}

	private static String requiredArgumentsWithOptional(String optionalJson) {
		return "{\"tenant\":\"tenant\",\"optional\":" + optionalJson
				+ ",\"routing\":{\"dryRun\":true,\"shard\":42}}";
	}

	private static void assertError(FixedResponse response, String id, int code) {
		Assertions.assertEquals(400, response.head().status(), response.head().raw());
		Assertions.assertTrue(response.body().contains("\"id\":" + id),
				response.body());
		Assertions.assertTrue(response.body().contains("\"code\":" + code),
				response.body());
		Assertions.assertEquals("no-store",
				response.head().singleHeader("Cache-Control"));
	}

	private static void assertSupportedVersionsDiagnostic(FixedResponse response) {
		Assertions.assertTrue(response.body().contains(
				"\"data\":{\"supportedVersions\":[\"" + PROTOCOL_VERSION
						+ "\"]}"), response.body());
	}

	private static void assertCors(FixedResponse response, String origin) {
		Assertions.assertEquals(origin,
				response.head().singleHeader("Access-Control-Allow-Origin"));
		Assertions.assertEquals("Origin", response.head().singleHeader("Vary"));
		Assertions.assertEquals("WWW-Authenticate",
				response.head().singleHeader("Access-Control-Expose-Headers"));
	}

	private record FailureCase(String id, String argumentsJson,
			List<McpChunkedHttpClient.RequestHeader> headers) {
	}

	private record FixedResponse(McpChunkedHttpClient.HttpResponseHead head,
			String body) {
	}
}
