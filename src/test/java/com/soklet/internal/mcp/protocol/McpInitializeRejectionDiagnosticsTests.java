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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@NotThreadSafe
@Timeout(60)
public class McpInitializeRejectionDiagnosticsTests {
	private static final String INITIALIZE = "initialize";
	private static final String CURRENT_VERSION = "2026-07-28";
	private static final String FUTURE_VERSION = "2099-01-01";
	private static final String MODERN_DIAGNOSTIC =
			"\"data\":{\"supportedVersions\":[\"" + CURRENT_VERSION + "\"]}";
	private static final String UNSUPPORTED_DIAGNOSTIC =
			"\"data\":{\"supported\":[\"" + CURRENT_VERSION
					+ "\"],\"requested\":\"" + FUTURE_VERSION + "\"}";

	@Test
	public void decoder_preserves_a_readable_method_across_invalid_envelope_and_id_paths() {
		McpJsonRpcEnvelopeCodec codec = new McpJsonRpcEnvelopeCodec(
				new McpJsonCodec(McpJsonLimits.productionDefaults()));
		List<String> readableFailures = List.of(
				requestWithJsonRpc("\"codec-version\"", "1.0", validParams(CURRENT_VERSION)),
				request("\"codec-conflict\"", validParams(CURRENT_VERSION))
						.substring(0, request("\"codec-conflict\"",
								validParams(CURRENT_VERSION)).length() - 1)
						+ ",\"result\":{}}",
				"{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"initialize\","
						+ "\"params\":" + validParams(CURRENT_VERSION) + "}");

		for (String body : readableFailures) {
			McpWireDecodingException failure = Assertions.assertThrows(
					McpWireDecodingException.class, () -> codec.decode(body), body);
			Assertions.assertEquals(Optional.of(INITIALIZE), failure.readableMethod(), body);
		}

		for (String body : List.of(
				"{\"jsonrpc\":\"2.0\",\"id\":\"unreadable\",\"method\":1}",
				"{")) {
			McpWireDecodingException failure = Assertions.assertThrows(
					McpWireDecodingException.class, () -> codec.decode(body), body);
			Assertions.assertTrue(failure.readableMethod().isEmpty(), body);
		}

		String largeId = "\u2028".repeat(4);
		McpJsonRpcId.StringId id = new McpJsonRpcId.StringId(largeId);
		McpJsonRpcMessage.ErrorResponse fallback = new McpJsonRpcMessage.ErrorResponse(
				Optional.of(id),
				new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
						"Internal error", Optional.empty()),
				McpJsonObject.empty());
		int exactFallbackBytes = codec.encode(fallback).length;
		McpJsonRpcEnvelopeCodec oneByteShortCodec =
				codecWithOutputLimit(exactFallbackBytes - 1);
		String tooLargeToCorrelate = request("\"" + largeId + "\"",
				validParams(CURRENT_VERSION));
		McpWireDecodingException outputBoundFailure = Assertions.assertThrows(
				McpWireDecodingException.class,
				() -> oneByteShortCodec.decode(tooLargeToCorrelate));
		Assertions.assertEquals(Optional.of(INITIALIZE),
				outputBoundFailure.readableMethod());
		Assertions.assertTrue(outputBoundFailure.readableRequestId().isEmpty());
	}

	@Test
	public void stage_counters_are_live_on_a_valid_application_handler_control()
			throws Exception {
		StageCounters counters = new StageCounters();
		McpHttpServerRuntime runtime = runtime(counters);

		try {
			int port = runtime.start().getPort();
			FixedResponse response = send(port,
					requestForMethod("\"counter-control\"", "example/handler",
							validParams(CURRENT_VERSION)),
					headers(CURRENT_VERSION, "example/handler"));
			Assertions.assertEquals(200, response.head().status(), response.head().raw());
			Assertions.assertTrue(response.body().contains("\"resultType\":\"complete\""),
					response.body());
			counters.assertAllReachedOnce();
		} finally {
			runtime.close();
		}
	}

	@Test
	public void every_readable_initialize_rejection_names_only_the_modern_version()
			throws Exception {
		StageCounters counters = new StageCounters();
		McpHttpServerRuntime runtime = runtime(counters);

		try {
			int port = runtime.start().getPort();
			for (RejectionCase testCase : rejectionCases()) {
				FixedResponse response = send(port, testCase.body(), testCase.headers());
				assertRejection(response, testCase);
				counters.assertUntouched(testCase.description());
			}
		} finally {
			runtime.close();
		}
	}

	@Test
	public void unreadable_and_non_initialize_failures_do_not_inherit_the_diagnostic()
			throws Exception {
		StageCounters counters = new StageCounters();
		McpHttpServerRuntime runtime = runtime(counters);

		try {
			int port = runtime.start().getPort();
			List<NoDiagnosticCase> cases = List.of(
					new NoDiagnosticCase("non-initialize method",
							requestForMethod("\"other-method\"", "legacy/unknown",
									validParams(CURRENT_VERSION)),
							headers(CURRENT_VERSION, "legacy/unknown"), 404, -32_601),
					new NoDiagnosticCase("unreadable method",
							"{\"jsonrpc\":\"2.0\",\"id\":\"unreadable-method\","
									+ "\"method\":1,\"params\":"
									+ validParams(CURRENT_VERSION) + "}",
							headers(CURRENT_VERSION, INITIALIZE), 400, -32_600),
					new NoDiagnosticCase("unparseable body", "{",
							headers(CURRENT_VERSION, INITIALIZE), 400, -32_700));

			for (NoDiagnosticCase testCase : cases) {
				FixedResponse response = send(port, testCase.body(), testCase.headers());
				Assertions.assertEquals(testCase.status(), response.head().status(),
						testCase.description() + ": " + response.head().raw());
				Assertions.assertTrue(response.body().contains(
						"\"code\":" + testCase.code()), response.body());
				Assertions.assertFalse(response.body().contains("supportedVersions"),
						response.body());
				Assertions.assertFalse(response.body().contains("\"supported\":"),
						response.body());
				Assertions.assertEquals(0, occurrences(response.body(), CURRENT_VERSION),
						response.body());
				counters.assertUntouched(testCase.description());
			}

			FixedResponse preParseOriginRejection = send(port,
					request("\"pre-parse-origin\"", validParams(CURRENT_VERSION)),
					headers(CURRENT_VERSION, INITIALIZE,
							header("Origin", "https://rejected.example")));
			Assertions.assertEquals(403, preParseOriginRejection.head().status(),
					preParseOriginRejection.head().raw());
			Assertions.assertEquals("", preParseOriginRejection.body());
			Assertions.assertEquals(0,
					occurrences(preParseOriginRejection.body(), CURRENT_VERSION));
			counters.assertUntouched("pre-parse Origin rejection");
		} finally {
			runtime.close();
		}
	}

	private static List<RejectionCase> rejectionCases() {
		List<RejectionCase> cases = new ArrayList<>();

		cases.add(modern("wrong JSON-RPC version", "\"envelope-version\"",
				requestWithJsonRpc("\"envelope-version\"", "1.0",
						validParams(CURRENT_VERSION)),
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_600));
		String conflict = request("2", validParams(CURRENT_VERSION));
		cases.add(modern("conflicting envelope fields", "2",
				conflict.substring(0, conflict.length() - 1) + ",\"error\":{}}",
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_600));
		cases.add(modernWithoutId("invalid request ID",
				"{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"initialize\","
						+ "\"params\":" + validParams(CURRENT_VERSION) + "}",
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_600));

		cases.add(modern("missing protocol header", "\"missing-version\"",
				request("\"missing-version\"", validParams(CURRENT_VERSION)),
				List.of(header("Mcp-Method", INITIALIZE)), 400, -32_020));
		cases.add(modern("duplicate protocol header", "4",
				request("4", validParams(CURRENT_VERSION)),
				List.of(header("MCP-Protocol-Version", CURRENT_VERSION),
						header("mcp-protocol-version", CURRENT_VERSION),
						header("Mcp-Method", INITIALIZE)), 400, -32_020));
		cases.add(modern("malformed protocol header", "\"malformed-version\"",
				request("\"malformed-version\"", validParams(CURRENT_VERSION)),
				headers("2026-07-é", INITIALIZE), 400, -32_020,
				"2026-07-é"));
		cases.add(modern("mismatched protocol header", "6",
				request("6", validParams(CURRENT_VERSION)),
				headers("version-header-secret", INITIALIZE), 400, -32_020,
				"version-header-secret"));

		cases.add(modern("missing method header", "\"missing-method\"",
				request("\"missing-method\"", validParams(CURRENT_VERSION)),
				List.of(header("MCP-Protocol-Version", CURRENT_VERSION)), 400, -32_020));
		cases.add(modern("duplicate method header", "8",
				request("8", validParams(CURRENT_VERSION)),
				List.of(header("MCP-Protocol-Version", CURRENT_VERSION),
						header("Mcp-Method", INITIALIZE),
						header("mcp-method", INITIALIZE)), 400, -32_020));
		cases.add(modern("malformed method header", "\"malformed-method\"",
				request("\"malformed-method\"", validParams(CURRENT_VERSION)),
				headers(CURRENT_VERSION, "initializé"), 400, -32_020,
				"initializé"));
		cases.add(modern("mismatched method header", "10",
				request("10", validParams(CURRENT_VERSION)),
				headers(CURRENT_VERSION, "method-header-secret"), 400, -32_020,
				"method-header-secret"));
		cases.add(modern("forbidden name header", "\"forbidden-name\"",
				request("\"forbidden-name\"", validParams(CURRENT_VERSION)),
				headers(CURRENT_VERSION, INITIALIZE,
						header("Mcp-Name", "name-header-secret")), 400, -32_020,
				"name-header-secret"));
		cases.add(modern("strict unknown mirrored header", "12",
				request("12", validParams(CURRENT_VERSION)),
				headers(CURRENT_VERSION, INITIALIZE,
						header("Mcp-Param-Secret-Name", "mirrored-header-secret")),
				400, -31_998, "Secret-Name", "mirrored-header-secret"));

		cases.add(modern("missing metadata", "\"missing-meta\"",
				request("\"missing-meta\"", "{}"),
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_602));
		cases.add(modern("mistyped metadata", "14",
				request("14", "{\"_meta\":\"metadata-type-secret\"}"),
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_602,
				"metadata-type-secret"));
		cases.add(modern("missing protocol metadata", "\"missing-body-version\"",
				request("\"missing-body-version\"",
						"{\"_meta\":{\"io.modelcontextprotocol/clientCapabilities\":{}}}"),
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_602));
		cases.add(modern("missing client capabilities", "16",
				request("16", "{\"_meta\":{"
						+ "\"io.modelcontextprotocol/protocolVersion\":\""
						+ CURRENT_VERSION + "\"}}"),
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_602));
		cases.add(modern("mistyped client capabilities", "\"mistyped-capabilities\"",
				request("\"mistyped-capabilities\"", "{\"_meta\":{"
						+ "\"io.modelcontextprotocol/protocolVersion\":\""
						+ CURRENT_VERSION + "\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":[]}}"),
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_602));
		cases.add(modern("invalid metadata extension key", "18",
				request("18", "{\"_meta\":{"
						+ "\"io.modelcontextprotocol/protocolVersion\":\""
						+ CURRENT_VERSION + "\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":{},"
						+ "\"bad/key/again\":\"metadata-extension-secret\"}}"),
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_602,
				"metadata-extension-secret"));
		cases.add(modern("invalid capability extension identifier",
				"\"invalid-capability-extension\"",
				request("\"invalid-capability-extension\"", "{\"_meta\":{"
						+ "\"io.modelcontextprotocol/protocolVersion\":\""
						+ CURRENT_VERSION + "\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":{"
						+ "\"extensions\":{\"not-prefixed\":{"
						+ "\"value\":\"capability-extension-secret\"}}}}}"),
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_602,
				"capability-extension-secret"));

		cases.add(modern("body and header version mismatch", "20",
				request("20", validParams("body-version-secret")),
				headers(CURRENT_VERSION, INITIALIZE), 400, -32_020,
				"body-version-secret"));
		cases.add(new RejectionCase("unsupported version", Optional.of(
				"\"unsupported-version\""),
				request("\"unsupported-version\"", validParams(FUTURE_VERSION)),
				headers(FUTURE_VERSION, INITIALIZE), 400, -32_022,
				DiagnosticShape.UNSUPPORTED, List.of()));
		cases.add(modern("final removed method", "22",
				request("22", validParams(CURRENT_VERSION)),
				headers(CURRENT_VERSION, INITIALIZE), 404, -32_601));

		return List.copyOf(cases);
	}

	private static RejectionCase modern(String description, String idJson,
			String body, List<McpChunkedHttpClient.RequestHeader> headers,
			int status, int code, String... forbiddenValues) {
		return new RejectionCase(description, Optional.of(idJson), body, headers,
				status, code, DiagnosticShape.MODERN_ONLY, List.of(forbiddenValues));
	}

	private static RejectionCase modernWithoutId(String description, String body,
			List<McpChunkedHttpClient.RequestHeader> headers, int status, int code) {
		return new RejectionCase(description, Optional.empty(), body, headers,
				status, code, DiagnosticShape.MODERN_ONLY, List.of());
	}

	private static void assertRejection(FixedResponse response, RejectionCase testCase) {
		String context = testCase.description() + ": " + response.head().raw();
		Assertions.assertEquals(testCase.status(), response.head().status(), context);
		Assertions.assertEquals("application/json",
				response.head().singleHeader("Content-Type"), context);
		Assertions.assertEquals("no-store",
				response.head().singleHeader("Cache-Control"), context);
		Assertions.assertTrue(response.body().contains("\"code\":" + testCase.code()),
				response.body());
		if (testCase.idJson().isPresent())
			Assertions.assertTrue(response.body().contains(
					"\"id\":" + testCase.idJson().orElseThrow()), response.body());
		else
			Assertions.assertFalse(response.body().contains("\"id\":"), response.body());

		if (testCase.diagnosticShape() == DiagnosticShape.MODERN_ONLY) {
			Assertions.assertTrue(response.body().contains(MODERN_DIAGNOSTIC),
					response.body());
			Assertions.assertFalse(response.body().contains("\"requested\":"),
					response.body());
			Assertions.assertFalse(response.body().contains("\"supported\":"),
					response.body());
		} else {
			Assertions.assertTrue(response.body().contains(UNSUPPORTED_DIAGNOSTIC),
					response.body());
			Assertions.assertFalse(response.body().contains("supportedVersions"),
					response.body());
		}

		Assertions.assertEquals(1, occurrences(response.body(), CURRENT_VERSION),
				response.body());
		String lowerBody = response.body().toLowerCase(Locale.ROOT);
		for (String forbidden : List.of("2025-03-26", "2025-06-18", "2025-11-25",
				"mcp-session-id", "last-event-id"))
			Assertions.assertFalse(lowerBody.contains(forbidden), response.body());
		for (String forbiddenValue : testCase.forbiddenValues())
			Assertions.assertFalse(response.body().contains(forbiddenValue),
					testCase.description() + " reflected " + forbiddenValue + ": "
							+ response.body());
	}

	private static McpHttpServerRuntime runtime(StageCounters counters) {
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> {
					counters.admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				})
				.withRequestRateLimiter(ignored -> {
					counters.limiters.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.withRequestInterceptor((invocation, continuation) -> {
					counters.interceptors.incrementAndGet();
					return continuation.invoke();
				})
				.withUnknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS);
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"initialize-diagnostics-test", "4.0.0-SNAPSHOT"))
				.build();
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromHandlers(
				Map.of("example/handler", ignored -> {
					counters.handlers.incrementAndGet();
					return McpWireResult.complete(new McpJsonObject(
							Map.of("ok", McpJsonBoolean.TRUE)));
				}));
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

	private static List<McpChunkedHttpClient.RequestHeader> headers(String version,
			String method, McpChunkedHttpClient.RequestHeader... additional) {
		List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>();
		headers.add(header("MCP-Protocol-Version", version));
		headers.add(header("Mcp-Method", method));
		headers.addAll(List.of(additional));
		return List.copyOf(headers);
	}

	private static McpChunkedHttpClient.RequestHeader header(String name, String value) {
		return new McpChunkedHttpClient.RequestHeader(name, value);
	}

	private static String request(String idJson, String paramsJson) {
		return requestForMethod(idJson, INITIALIZE, paramsJson);
	}

	private static String requestForMethod(String idJson, String method,
			String paramsJson) {
		return requestWithJsonRpcAndMethod(idJson, "2.0", method, paramsJson);
	}

	private static String requestWithJsonRpc(String idJson, String jsonRpc,
			String paramsJson) {
		return requestWithJsonRpcAndMethod(idJson, jsonRpc, INITIALIZE, paramsJson);
	}

	private static String requestWithJsonRpcAndMethod(String idJson, String jsonRpc,
			String method, String paramsJson) {
		return "{\"jsonrpc\":\"" + jsonRpc + "\",\"id\":" + idJson
				+ ",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
	}

	private static String validParams(String protocolVersion) {
		return "{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ protocolVersion + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}";
	}

	private static int occurrences(String value, String target) {
		int count = 0;
		int offset = 0;
		while ((offset = value.indexOf(target, offset)) >= 0) {
			count++;
			offset += target.length();
		}
		return count;
	}

	private static McpJsonRpcEnvelopeCodec codecWithOutputLimit(
			int maximumOutputBytes) {
		return new McpJsonRpcEnvelopeCodec(new McpJsonCodec(new McpJsonLimits(
				65_536, 256, 16_384, 16_384, 512, 10_000, 16_384,
				maximumOutputBytes)));
	}

	private enum DiagnosticShape {
		MODERN_ONLY,
		UNSUPPORTED
	}

	private record RejectionCase(String description, Optional<String> idJson,
			String body, List<McpChunkedHttpClient.RequestHeader> headers,
			int status, int code, DiagnosticShape diagnosticShape,
			List<String> forbiddenValues) {
	}

	private record NoDiagnosticCase(String description, String body,
			List<McpChunkedHttpClient.RequestHeader> headers, int status, int code) {
	}

	private record FixedResponse(McpChunkedHttpClient.HttpResponseHead head,
			String body) {
	}

	private static final class StageCounters {
		private final AtomicInteger admissions = new AtomicInteger();
		private final AtomicInteger limiters = new AtomicInteger();
		private final AtomicInteger interceptors = new AtomicInteger();
		private final AtomicInteger handlers = new AtomicInteger();

		private void assertUntouched(String context) {
			Assertions.assertEquals(0, admissions.get(), context + " reached admission");
			Assertions.assertEquals(0, limiters.get(), context + " reached limiting");
			Assertions.assertEquals(0, interceptors.get(), context + " reached interception");
			Assertions.assertEquals(0, handlers.get(), context + " reached a handler");
		}

		private void assertAllReachedOnce() {
			Assertions.assertEquals(1, admissions.get());
			Assertions.assertEquals(1, limiters.get());
			Assertions.assertEquals(1, interceptors.get());
			Assertions.assertEquals(1, handlers.get());
		}
	}
}
