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
import java.util.concurrent.atomic.AtomicInteger;

@NotThreadSafe
@Timeout(30)
public class McpHttpServerModernHeaderTests {
	private static final String PROTOCOL_VERSION = "2026-07-28";

	@Test
	public void standard_name_mirrors_decode_and_match_for_all_required_methods()
			throws Exception {
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(
				McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
						ignored -> McpAdmissionDecision.acceptedAnonymous()),
				handlerInvocations);

		try {
			int port = runtime.start().getPort();
			List<SuccessfulCase> cases = List.of(
					new SuccessfulCase("tools/call", "name", "get_weather", "get_weather"),
					new SuccessfulCase("prompts/get", "name", "line1\nline2",
							"=?base64?bGluZTEKbGluZTI=?="),
					new SuccessfulCase("resources/read", "uri", "file:///世界",
							"=?base64?ZmlsZTovLy/kuJbnlYw=?="),
					new SuccessfulCase("tools/call", "name", "=?Base64?literal?=",
							"=?Base64?literal?="));

			for (int index = 0; index < cases.size(); index++) {
				SuccessfulCase successfulCase = cases.get(index);
				FixedResponse response = send(port,
						request(Integer.toString(index + 1), successfulCase.method(),
								successfulCase.bodyField(), successfulCase.bodyValue(), true),
						headers(successfulCase.method(),
								new McpChunkedHttpClient.RequestHeader(
										index == 2 ? "mCp-NaMe" : "Mcp-Name",
										successfulCase.headerValue())));
				Assertions.assertEquals(200, response.head().status(), response.head().raw());
				Assertions.assertTrue(response.body().contains("\"resultType\":\"complete\""),
						response.body());
			}
			Assertions.assertEquals(cases.size(), handlerInvocations.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void required_name_missing_duplicate_malformed_or_mismatched_is_header_mismatch()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				});
		McpHttpServerRuntime runtime = runtime(policy, handlerInvocations);

		try {
			int port = runtime.start().getPort();
			List<List<McpChunkedHttpClient.RequestHeader>> cases = List.of(
					headers("tools/call"),
					headers("tools/call",
							new McpChunkedHttpClient.RequestHeader("Mcp-Name", "get_weather"),
							new McpChunkedHttpClient.RequestHeader("mcp-name", "get_weather")),
					headers("tools/call",
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Name", "=?base64?***secret***?=")),
					headers("tools/call",
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Name", "=?base64?/w==?=")),
					headers("tools/call",
							new McpChunkedHttpClient.RequestHeader("Mcp-Name", "GET_WEATHER")));

			for (int index = 0; index < cases.size(); index++) {
				String id = Integer.toString(index + 10);
				FixedResponse response = send(port,
						request(id, "tools/call", "name", "get_weather", true),
						cases.get(index));
				assertHeaderMismatch(response, id);
				Assertions.assertFalse(response.body().contains("secret"));
			}
			Assertions.assertEquals(0, admissions.get());
			Assertions.assertEquals(0, handlerInvocations.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void name_source_failure_and_extraneous_standard_name_fail_before_admission()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				});
		McpHttpServerRuntime runtime = runtime(policy, new AtomicInteger());

		try {
			int port = runtime.start().getPort();
			List<RequestCase> cases = List.of(
					new RequestCase("20",
							request("20", "tools/call", null, null, true),
							headers("tools/call",
									new McpChunkedHttpClient.RequestHeader(
											"Mcp-Name", "get_weather"))),
					new RequestCase("21",
							requestWithRawSource("21", "tools/call", "name", "42"),
							headers("tools/call",
									new McpChunkedHttpClient.RequestHeader(
											"Mcp-Name", "42"))),
					new RequestCase("22",
							request("22", "server/discover", null, null, true),
							headers("server/discover",
									new McpChunkedHttpClient.RequestHeader(
											"Mcp-Name", "extraneous"))));

			for (RequestCase requestCase : cases)
				assertHeaderMismatch(send(port, requestCase.body(), requestCase.headers()),
						requestCase.id());
			Assertions.assertEquals(0, admissions.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void recognized_header_failure_precedes_missing_request_metadata()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				});
		McpHttpServerRuntime runtime = runtime(policy, new AtomicInteger());

		try {
			int port = runtime.start().getPort();
			String withoutMetadata = "{\"jsonrpc\":\"2.0\",\"id\":30,"
					+ "\"method\":\"tools/call\","
					+ "\"params\":{\"name\":\"get_weather\"}}";
			assertHeaderMismatch(send(port, withoutMetadata, headers("tools/call")), "30");

			FixedResponse metadataFailure = send(port, withoutMetadata,
					headers("tools/call", new McpChunkedHttpClient.RequestHeader(
							"Mcp-Name", "get_weather")));
			Assertions.assertEquals(400, metadataFailure.head().status(),
					metadataFailure.head().raw());
			Assertions.assertTrue(metadataFailure.body().contains("\"code\":-32602"),
					metadataFailure.body());
			Assertions.assertTrue(metadataFailure.body().contains("\"id\":30"),
					metadataFailure.body());
			Assertions.assertEquals(0, admissions.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void plain_method_and_protocol_mirrors_reject_obs_text_before_policy()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				}), new AtomicInteger());

		try {
			int port = runtime.start().getPort();
			String unicodeMethod = "café";
			FixedResponse methodFailure = send(port,
					request("40", unicodeMethod, null, null, true),
					List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", PROTOCOL_VERSION),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", unicodeMethod)));
			assertHeaderMismatch(methodFailure, "40");

			String unicodeVersion = "2026-07-é";
			FixedResponse versionFailure = send(port,
					requestWithProtocolVersion("41", "server/discover", unicodeVersion),
					List.of(
							new McpChunkedHttpClient.RequestHeader(
									"MCP-Protocol-Version", unicodeVersion),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Method", "server/discover")));
			assertHeaderMismatch(versionFailure, "41");
			Assertions.assertEquals(0, admissions.get());
		} finally {
			runtime.close();
		}
	}

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy,
			AtomicInteger handlerInvocations) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"modern-header-test", "3.6.0-SNAPSHOT"))
				.build();
		McpApplicationRequestHandler handler = ignored -> {
			handlerInvocations.incrementAndGet();
			return McpWireResult.complete(new McpJsonObject(
					Map.of("ok", McpJsonBoolean.TRUE)));
		};
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromHandlers(
				Map.of("tools/call", handler,
						"prompts/get", handler,
						"resources/read", handler));
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), policy, endpoint,
				router, McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM);
	}

	private static FixedResponse send(int port, String body,
			List<McpChunkedHttpClient.RequestHeader> headers) throws Exception {
		try (McpChunkedHttpClient client =
					McpChunkedHttpClient.postMcpMessage(port, body, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			return new FixedResponse(head, client.readFixedBody(head));
		}
	}

	private static List<McpChunkedHttpClient.RequestHeader> headers(String method,
			McpChunkedHttpClient.RequestHeader... additional) {
		List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>();
		headers.add(new McpChunkedHttpClient.RequestHeader(
				"MCP-Protocol-Version", PROTOCOL_VERSION));
		headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Method", method));
		headers.addAll(List.of(additional));
		return List.copyOf(headers);
	}

	private static String request(String id, String method, String bodyField,
			String bodyValue, boolean includeMetadata) {
		StringBuilder params = new StringBuilder("{");
		if (bodyField != null)
			params.append('\"').append(bodyField).append("\":\"")
					.append(jsonEscape(bodyValue)).append('\"');
		if (includeMetadata) {
			if (bodyField != null)
				params.append(',');
			params.append("\"_meta\":{")
					.append("\"io.modelcontextprotocol/protocolVersion\":\"")
					.append(PROTOCOL_VERSION).append("\",")
					.append("\"io.modelcontextprotocol/clientCapabilities\":{}}");
		}
		params.append('}');
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id
				+ ",\"method\":\"" + method + "\",\"params\":" + params + "}";
	}

	private static String requestWithRawSource(String id, String method,
			String bodyField, String rawValue) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id
				+ ",\"method\":\"" + method + "\",\"params\":{\""
				+ bodyField + "\":" + rawValue + ",\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL_VERSION
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
	}

	private static String requestWithProtocolVersion(String id, String method,
			String protocolVersion) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id
				+ ",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ jsonEscape(protocolVersion) + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
	}

	private static String jsonEscape(String value) {
		return value.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	private static void assertHeaderMismatch(FixedResponse response, String id) {
		Assertions.assertEquals(400, response.head().status(), response.head().raw());
		Assertions.assertTrue(response.body().contains("\"code\":-32020"),
				response.body());
		Assertions.assertTrue(response.body().contains("\"id\":" + id),
				response.body());
		Assertions.assertEquals("no-store",
				response.head().singleHeader("Cache-Control"));
	}

	private record SuccessfulCase(String method, String bodyField,
			String bodyValue, String headerValue) {
	}

	private record RequestCase(String id, String body,
			List<McpChunkedHttpClient.RequestHeader> headers) {
	}

	private record FixedResponse(McpChunkedHttpClient.HttpResponseHead head,
			String body) {
	}
}
