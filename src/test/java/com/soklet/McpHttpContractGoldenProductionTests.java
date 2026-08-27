/*
 * Copyright 2022-2026 Revetware LLC.
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

package com.soklet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Independent complete-response goldens for the production MCP HTTP listener.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(60)
public class McpHttpContractGoldenProductionTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TOOL_NAME = "contract.tool";
	private static final String TYPED_TOOL_NAME = "contract.typed";
	private static final String CASE_HEADER = "X-Contract-Case";
	private static final String ALLOWED_ORIGIN = "https://allowed.example";
	private static final String REJECTED_ORIGIN = "https://rejected.example";
	private static final String INTERCEPTOR_SECRET = "INTERCEPTOR-SECRET-CANARY";
	private static final String HANDLER_SECRET = "HANDLER-SECRET-CANARY";
	private static final String OUTPUT_SECRET = "OUTPUT-SECRET-CANARY";
	private static final Path GOLDEN_ROOT = Path.of(
			"conformance", "golden-http-contract", "precedence-no-store");
	private static final Path PROTOCOL_SOURCE_ROOT = Path.of(
			"src", "main", "java", "com", "soklet", "internal", "mcp", "protocol");
	private static final String SUPERSEDED_ERROR_MAPPING_MANIFEST_DIGEST =
			"90fae4482e7d8560f421aa4edbc8a6459d72f42880b5351298d5b74ff3f8b780";
	private static final String SUPERSEDED_HTTP_CONTRACT_MANIFEST_DIGEST =
			"ec1bd3f13c70bec100b18e774bfbdf2d9e574c1d8df99f2acc4b36e85f51702c";
	private static final List<Path> CANDIDATE_DOCUMENTATION = List.of(
			Path.of("MCP.md"),
			Path.of("README.md"),
			Path.of("SECURITY.md"),
			Path.of("api", "mcp", "README.md"),
			Path.of("conformance", "official", "README.md"),
			Path.of("release", "README.md"));

	@Test
	public void requestPipelineFirstFailureWinnersMatchCompleteWireGoldens()
			throws Exception {
		FixtureState state = new FixtureState();
		McpServer server = server(state);
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = boundPort(server);
			List<RequestCase> cases = List.of(
					new RequestCase("early-parser",
							RequestSpec.raw("POST /mcp HTTP/1.1\r\n"
									+ "Content-Length: 0\r\n"
									+ "Connection: close\r\n\r\n"),
							"early-parser-400.http.hex", List.of()),
					new RequestCase("routing",
							post("routing", "/missing", "{", List.of(
									new HeaderLine("Host", "invalid.example"),
									new HeaderLine("Origin", REJECTED_ORIGIN),
									new HeaderLine("Content-Type", "text/plain"),
									acceptHeader())),
							"routing-404.http.hex", List.of()),
					new RequestCase("host",
							post("host", MCP_PATH, "{", List.of(
									new HeaderLine("Host", "invalid.example"),
									new HeaderLine("Origin", REJECTED_ORIGIN),
									new HeaderLine("Content-Type", "text/plain"),
									acceptHeader())),
							"host-421.http.hex", List.of()),
					new RequestCase("origin",
							post("origin", MCP_PATH, "{", List.of(
									new HeaderLine("Origin", REJECTED_ORIGIN),
									new HeaderLine("Content-Type", "text/plain"),
									acceptHeader())),
							"origin-403.http.hex", List.of()),
					new RequestCase("media",
							post("media", MCP_PATH, "{", List.of(
									new HeaderLine("Content-Type", "text/plain"),
									acceptHeader())),
							"media-415.http.hex", List.of()),
					new RequestCase("strict-json",
							post("strict-json", MCP_PATH, "{", baseMediaHeaders()),
							"json-parse-400.http.hex", List.of()),
					new RequestCase("envelope",
							post("envelope", MCP_PATH,
									"{\"jsonrpc\":\"2.0\",\"id\":\"contract\","
											+ "\"result\":{}}", baseMediaHeaders()),
							"invalid-envelope-400.http.hex", List.of()),
					new RequestCase("unsupported-selector-row1-non-initialize",
							post("unsupported-selector-row1-non-initialize", MCP_PATH,
									"{\"jsonrpc\":\"2.0\",\"id\":\"contract\","
											+ "\"method\":\"tools/call\",\"result\":{}}",
									toolHeaders(TOOL_NAME, "2099-01-01")),
							"unsupported-selector-invalid-envelope-non-initialize-400.http.hex",
							List.of()),
					new RequestCase("mirrored-header",
							post("mirrored-header", MCP_PATH,
									toolRequestWithoutMetadata(TOOL_NAME, "success"),
									baseMediaHeaders()),
							"header-mismatch-400.http.hex", List.of()),
					new RequestCase("metadata",
							post("metadata", MCP_PATH,
									toolRequestWithoutMetadata(TOOL_NAME, "success"),
									toolHeaders(TOOL_NAME, PROTOCOL_VERSION)),
							"invalid-params-400.http.hex", List.of()),
					new RequestCase("unsupported-missing-metadata",
							post("unsupported-missing-metadata", MCP_PATH,
									toolRequestWithoutMetadata(TOOL_NAME, "success"),
									toolHeaders(TOOL_NAME, "2099-01-01")),
							"unsupported-version-400.http.hex", List.of()),
					new RequestCase("version",
							post("version", MCP_PATH,
									toolRequest(TOOL_NAME, "schema-failure",
											"2099-01-01", false),
									toolHeaders(TOOL_NAME, "2099-01-01")),
							"unsupported-version-400.http.hex", List.of()),
					new RequestCase("structural",
							post("structural", MCP_PATH,
									discoveryRequest("{\"unexpected\":true}"),
									requestHeaders("server/discover", PROTOCOL_VERSION)),
							"invalid-params-400.http.hex", List.of()),
					new RequestCase("admission-denied",
							validToolPost("admission-denied", TOOL_NAME, "success", false),
							"admission-401.http.hex", List.of("admission")),
					new RequestCase("rate-denied",
							validToolPost("rate-denied", TOOL_NAME, "success", false),
							"rate-429.http.hex",
							List.of("admission", "request-limiter")),
					new RequestCase("policy-cache-control",
							validToolPost("policy-cache-control", TOOL_NAME, "success", false),
							"internal-500.http.hex", List.of("admission")),
					new RequestCase("interceptor-failure",
							validToolPost("interceptor-failure", TOOL_NAME,
									"schema-failure", false),
							"internal-500.http.hex", List.of("admission",
									"request-limiter", "tool-limiter", "interceptor")),
					new RequestCase("schema-failure",
							validToolPost("schema-failure", TOOL_NAME,
									"schema-failure", false),
							"invalid-params-400.http.hex", List.of("admission",
									"request-limiter", "tool-limiter", "interceptor")),
					new RequestCase("handler-failure",
							validToolPost("handler-failure", TOOL_NAME,
									"handler-failure", false),
							"internal-500.http.hex", List.of("admission",
									"request-limiter", "tool-limiter", "interceptor",
									"handler")),
					new RequestCase("invalid-output",
							validToolPost("invalid-output", TYPED_TOOL_NAME,
									"invalid-output", false),
							"internal-500.http.hex", List.of("admission",
									"request-limiter", "tool-limiter", "interceptor",
									"handler", "sanitizer")),
					new RequestCase("typed-success",
							validToolPost("typed-success", TYPED_TOOL_NAME,
									"typed-success", false),
							"typed-success-200.http.hex", List.of("admission",
									"request-limiter", "tool-limiter", "interceptor",
									"handler", "sanitizer")),
					new RequestCase("success",
							validToolPost("success", TOOL_NAME, "success", false),
							"success-200.http.hex", List.of("admission",
									"request-limiter", "tool-limiter", "interceptor",
									"handler", "sanitizer")));

			for (RequestCase testCase : cases) {
				try {
					WireResponse response = exchange(port, testCase.request());
					assertGolden(response, testCase.fixture());
					Assertions.assertEquals(testCase.expectedStages(),
							state.stages(testCase.name()), testCase.name());
				} catch (Exception | AssertionError failure) {
					throw new AssertionError("HTTP contract request case failed: "
							+ testCase.name(), failure);
				}
			}
			assertIdleStartedDiagnostics(server);
		} finally {
			state.releaseHeldHandlers();
			stopAndAssertClean(owner, server);
		}
	}

	@Test
	public void notificationPipelineAndPreflightMatchCompleteWireGoldens()
			throws Exception {
		FixtureState state = new FixtureState();
		McpServer server = server(state);
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = boundPort(server);
			List<RequestCase> cases = List.of(
					new RequestCase("notification-origin",
							post("notification-origin", MCP_PATH, "{", List.of(
									new HeaderLine("Origin", REJECTED_ORIGIN),
									new HeaderLine("Content-Type", "application/json"),
									acceptHeader())),
							"origin-403.http.hex", List.of()),
					new RequestCase("notification-json",
							post("notification-json", MCP_PATH, "{", baseMediaHeaders()),
							"json-parse-400.http.hex", List.of()),
					new RequestCase("notification-metadata",
							post("notification-metadata", MCP_PATH,
									notification("future/event", "{\"_meta\":\"invalid\"}"),
									baseMediaHeaders()),
							"early-parser-400.http.hex", List.of()),
					new RequestCase("notification-version",
							post("notification-version", MCP_PATH,
									notification("future/event", "{}"),
									baseMediaHeaders()),
							"early-parser-400.http.hex", List.of()),
					new RequestCase("notification-admission",
							post("notification-admission", MCP_PATH,
									notification("future/event", "{}"),
									notificationHeaders()),
							"notification-admission-401.http.hex", List.of("admission")),
					new RequestCase("notification-rate",
							post("notification-rate", MCP_PATH,
									notification("future/event", "{}"),
									notificationHeaders()),
							"notification-rate-429.http.hex",
							List.of("admission", "request-limiter")),
					new RequestCase("notification-unsupported",
							post("notification-unsupported", MCP_PATH,
									notification("future/event", "{}"),
									notificationHeaders()),
							"early-parser-400.http.hex",
							List.of("admission", "request-limiter")),
					new RequestCase("notification-cancelled",
							post("notification-cancelled", MCP_PATH,
									notification("notifications/cancelled",
											"{\"requestId\":{},\"_meta\":\"ignored\"}"),
									notificationHeaders()),
							"notification-accepted-202.http.hex",
							List.of("admission", "request-limiter")));

			for (RequestCase testCase : cases) {
				try {
					WireResponse response = exchange(port, testCase.request());
					assertGolden(response, testCase.fixture());
					Assertions.assertEquals(testCase.expectedStages(),
							state.stages(testCase.name()), testCase.name());
				} catch (Exception | AssertionError failure) {
					throw new AssertionError("HTTP contract notification case failed: "
							+ testCase.name(), failure);
				}
			}

			WireResponse preflight = exchange(port, new RequestSpec("OPTIONS", MCP_PATH,
					List.of(
							new HeaderLine(CASE_HEADER, "preflight"),
							new HeaderLine("Origin", ALLOWED_ORIGIN),
							new HeaderLine("Access-Control-Request-Method", "POST"),
							new HeaderLine("Access-Control-Request-Headers",
									"Content-Type, MCP-Protocol-Version, Mcp-Method, Mcp-Name")),
					new byte[0], null));
			assertGolden(preflight, "preflight-204.http.hex");
			Assertions.assertTrue(state.stages("preflight").isEmpty());
			assertIdleStartedDiagnostics(server);
		} finally {
			stopAndAssertClean(owner, server);
		}
	}

	@Test
	public void overloadAndSseAuthoritiesMatchCompleteWireGoldens()
			throws Exception {
		FixtureState state = new FixtureState();
		McpServer server = server(state);
		Soklet owner = managedSoklet(server);
		ExecutorService clients = Executors.newFixedThreadPool(2);

		try {
			owner.start();
			int port = boundPort(server);
			Future<WireResponse> held = clients.submit(() -> exchange(port,
					validToolPost("held", TOOL_NAME, "hold", false)));
			Assertions.assertTrue(state.handlerHeld.await(5, TimeUnit.SECONDS),
					"The active contract handler did not enter.");
			Future<WireResponse> queued = clients.submit(() -> exchange(port,
					validToolPost("queued", TOOL_NAME, "queued", false)));
			awaitCondition(() -> server.getDiagnostics().getQueuedRequests() == 1);

			WireResponse overload = exchange(port,
					validToolPost("overload", TOOL_NAME, "overload", false));
			assertGolden(overload, "overload-503.http.hex");
			Assertions.assertEquals(List.of("admission", "request-limiter",
					"tool-limiter"), state.stages("overload"));

			state.releaseHeldHandlers();
			assertGolden(held.get(5, TimeUnit.SECONDS), "success-200.http.hex");
			assertGolden(queued.get(5, TimeUnit.SECONDS), "success-200.http.hex");
			List<String> successfulStages = List.of("admission", "request-limiter",
					"tool-limiter", "interceptor", "handler", "sanitizer");
			Assertions.assertEquals(successfulStages, state.stages("held"));
			Assertions.assertEquals(successfulStages, state.stages("queued"));

			WireResponse sse = exchange(port,
					validToolPost("sse", TOOL_NAME, "sse", true));
			assertGolden(sse, "sse-200.http.hex");
			Assertions.assertEquals(List.of("admission", "request-limiter",
					"tool-limiter", "interceptor", "handler", "sanitizer"),
					state.stages("sse"));
			assertIdleStartedDiagnostics(server);
		} finally {
			state.releaseHeldHandlers();
			stopAndAssertClean(owner, server);
			clients.shutdownNow();
			Assertions.assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void sourceInventoryPinsEveryProductionNoStoreAuthority()
			throws Exception {
		Path runtime = PROTOCOL_SOURCE_ROOT.resolve("McpHttpServerRuntime.java");
		Path stream = PROTOCOL_SOURCE_ROOT.resolve("McpRequestSseStream.java");
		String runtimeSource = Files.readString(runtime, StandardCharsets.UTF_8);
		String streamSource = Files.readString(stream, StandardCharsets.UTF_8);

		Assertions.assertEquals(1, occurrences(runtimeSource,
				".withEarlyErrorResponseHeaders("));
		Assertions.assertEquals(1, occurrences(runtimeSource,
				"List.of(new Header(CACHE_CONTROL, CACHE_CONTROL_NO_STORE))"));
		Assertions.assertEquals(1, occurrences(runtimeSource,
				"return new MicrohttpResponse(status, reason, List.copyOf(headers), body);"));
		Assertions.assertEquals(1, occurrences(runtimeSource,
				"headers.add(new Header(CACHE_CONTROL, CACHE_CONTROL_NO_STORE));"));
		Assertions.assertEquals(1, occurrences(streamSource,
				"headers.add(new Header(\"Cache-Control\", \"no-store\"));"));
		Assertions.assertEquals(1, occurrences(streamSource,
				"return StreamingMicrohttpResponses.withWritableSourceBody("));

		Map<String, Integer> responseCreators = new LinkedHashMap<>();
		try (Stream<Path> paths = Files.walk(PROTOCOL_SOURCE_ROOT)) {
			for (Path path : paths.filter(value -> value.toString().endsWith(".java"))
					.sorted().toList()) {
				String source = Files.readString(path, StandardCharsets.UTF_8);
				recordOccurrences(responseCreators, path, "early-errors", source,
						".withEarlyErrorResponseHeaders(");
				recordOccurrences(responseCreators, path, "fixed-response", source,
						"new MicrohttpResponse(");
				recordOccurrences(responseCreators, path, "stream-response", source,
						"StreamingMicrohttpResponses.withWritableSourceBody(");
			}
		}
		Assertions.assertEquals(Map.of(
				"McpHttpServerRuntime.java:early-errors", 1,
				"McpHttpServerRuntime.java:fixed-response", 1,
				"McpRequestSseStream.java:stream-response", 1,
				"McpSimulationRuntime.java:fixed-response", 1), responseCreators,
				"A new response authority requires complete no-store golden coverage.");
		Assertions.assertEquals(1, creatorOccurrences(responseCreators,
				":early-errors"));
		Assertions.assertEquals(2, creatorOccurrences(responseCreators,
				":fixed-response"));
		Assertions.assertEquals(1, creatorOccurrences(responseCreators,
				":stream-response"));
		Assertions.assertEquals(3, responseCreators.entrySet().stream()
				.filter(entry -> !entry.getKey().startsWith("McpSimulationRuntime.java:"))
				.mapToInt(Map.Entry::getValue).sum(),
				"The production listener must retain exactly three response authorities.");
	}

	private static McpServer server(FixtureState state) {
		McpToolRegistration<ContractArguments> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.argumentType(ContractArguments.class)
				.handler((request, arguments, features) -> {
					String caseName = state.caseName(request.getRequest());
					state.record(caseName, "handler");
					String value = arguments.getConvertedArguments().value();
					if ("hold".equals(value)) {
						state.handlerHeld.countDown();
						if (!state.releaseHandlers.await(5, TimeUnit.SECONDS))
							throw new AssertionError("Held handler was not released.");
					}
					if ("handler-failure".equals(value))
						throw new IllegalStateException(HANDLER_SECRET);
					if ("sse".equals(value))
						features.require(McpProgressReporter.class).report(
								McpProgressUpdate.withProgress(1.0d).build());
					return McpCompleteResult.fromToolText("contract-ok");
				})
				.build();
		McpToolRegistration<ContractArguments> typedTool = McpToolRegistration
				.withName(TYPED_TOOL_NAME)
				.types(ContractArguments.class, ContractResult.class)
				.handler((request, arguments, features) -> {
					state.record(state.caseName(request.getRequest()), "handler");
					return new ContractResult("contract-ok");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"http-contract-golden", "4.0.0-SNAPSHOT").build())
				.tool(tool)
				.tool(typedTool)
				.build();
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> {
					String caseName = state.caseName(context.getRequest());
					state.record(caseName, "admission");
					if (Set.of("metadata", "version", "structural", "admission-denied",
							"notification-metadata", "notification-version",
							"notification-admission")
							.contains(caseName))
						return McpAdmissionDecision.rejected(McpAdmissionRejection
								.withStatusCodeAndError(401,
										McpJsonRpcError.fromApplication(
												4_101, "Contract denied"))
								.header("X-Contract-Policy", "denied")
								.build());
					if ("policy-cache-control".equals(caseName))
						return McpAdmissionDecision.rejected(McpAdmissionRejection
								.withStatusCodeAndError(401,
										McpJsonRpcError.fromApplication(
												4_102, "Must fail closed"))
								.header("Cache-Control", "public, max-age=3600")
								.build());
					return McpAdmissionDecision.accepted();
				})
				.requestRateLimiter(context -> {
					String caseName = state.caseName(context.getRequest());
					state.record(caseName, "request-limiter");
					return Set.of("rate-denied", "notification-rate")
							.contains(caseName)
							? McpRateLimitDecision.denied(Duration.ofMillis(1))
							: McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					state.record(state.caseName(context.getRequest()), "tool-limiter");
					return McpRateLimitDecision.allowed();
				})
				.handlerInterceptor((context, continuation) -> {
					String caseName = state.caseName(context.getRequest());
					state.record(caseName, "interceptor");
					if ("interceptor-failure".equals(caseName))
						throw new IllegalStateException(INTERCEPTOR_SECRET);
					return continuation.proceed();
				})
				.toolOutputSanitizer((request, toolName, rawArguments, output) -> {
					String caseName = state.caseName(request.getRequest());
					state.record(caseName, "sanitizer");
					if ("invalid-output".equals(caseName))
						return McpToolOutput.fromStructuredContent(
								McpJsonObject.builder().put("wrong", OUTPUT_SECRET).build());
					return output;
				})
				.corsAuthorizer(CorsAuthorizer.fromWhitelistedOrigins(
						Set.of(ALLOWED_ORIGIN)))
				.allowedHosts(Set.of(LOOPBACK))
				.requestHandlerConcurrency(1)
				.requestHandlerQueueCapacity(1)
				.build();
	}

	private static RequestSpec validToolPost(String caseName, String toolName,
			String value, boolean progress) {
		return post(caseName, MCP_PATH,
				toolRequest(toolName, value, PROTOCOL_VERSION, progress),
				toolHeaders(toolName, PROTOCOL_VERSION));
	}

	private static RequestSpec post(String caseName, String path, String body,
			List<HeaderLine> headers) {
		List<HeaderLine> copied = new ArrayList<>(headers.size() + 1);
		copied.add(new HeaderLine(CASE_HEADER, caseName));
		copied.addAll(headers);
		return new RequestSpec("POST", path, List.copyOf(copied),
				body.getBytes(StandardCharsets.UTF_8), null);
	}

	private static List<HeaderLine> baseMediaHeaders() {
		return List.of(new HeaderLine("Content-Type", "application/json"),
				acceptHeader());
	}

	private static List<HeaderLine> notificationHeaders() {
		List<HeaderLine> headers = new ArrayList<>(baseMediaHeaders());
		headers.add(new HeaderLine("MCP-Protocol-Version", PROTOCOL_VERSION));
		return List.copyOf(headers);
	}

	private static List<HeaderLine> requestHeaders(String method, String version) {
		List<HeaderLine> headers = new ArrayList<>(baseMediaHeaders());
		headers.add(new HeaderLine("MCP-Protocol-Version", version));
		headers.add(new HeaderLine("Mcp-Method", method));
		return List.copyOf(headers);
	}

	private static List<HeaderLine> toolHeaders(String toolName, String version) {
		List<HeaderLine> headers = new ArrayList<>(requestHeaders("tools/call", version));
		headers.add(new HeaderLine("Mcp-Name", toolName));
		return List.copyOf(headers);
	}

	private static HeaderLine acceptHeader() {
		return new HeaderLine("Accept", "application/json, text/event-stream");
	}

	private static String toolRequest(String toolName, String value,
			String version, boolean progress) {
		String arguments = "schema-failure".equals(value)
				? "{}" : "{\"value\":\"" + value + "\"}";
		String progressField = progress
				? ",\"progressToken\":\"contract-progress\"" : "";
		return "{\"jsonrpc\":\"2.0\",\"id\":\"contract\","
				+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + version + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}"
				+ progressField + "},\"name\":\"" + toolName + "\","
				+ "\"arguments\":" + arguments + "}}";
	}

	private static String toolRequestWithoutMetadata(String toolName, String value) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"contract\","
				+ "\"method\":\"tools/call\",\"params\":{\"name\":\""
				+ toolName + "\",\"arguments\":{\"value\":\"" + value + "\"}}}";
	}

	private static String discoveryRequest(String fields) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"contract\","
				+ "\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ fields.substring(1) + "}";
	}

	private static String notification(String method, String params) {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method
				+ "\",\"params\":" + params + "}";
	}

	private static WireResponse exchange(int port, RequestSpec request)
			throws Exception {
		byte[] rawRequest = request.rawRequest();
		if (rawRequest == null) {
			StringBuilder head = new StringBuilder()
					.append(request.method()).append(' ').append(request.path())
					.append(" HTTP/1.1\r\n");
			boolean hasHost = request.headers().stream()
					.anyMatch(header -> header.name().equalsIgnoreCase("Host"));
			if (!hasHost)
				head.append("Host: ").append(LOOPBACK).append(':').append(port)
						.append("\r\n");
			for (HeaderLine header : request.headers())
				head.append(header.name()).append(": ").append(header.value())
						.append("\r\n");
			head.append("Content-Length: ").append(request.body().length)
					.append("\r\nConnection: close\r\n\r\n");
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			bytes.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
			bytes.write(request.body());
			rawRequest = bytes.toByteArray();
		}

		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(LOOPBACK, port), 3_000);
			socket.setSoTimeout(5_000);
			socket.getOutputStream().write(rawRequest);
			socket.getOutputStream().flush();
			ByteArrayOutputStream response = new ByteArrayOutputStream();
			InputStream input = socket.getInputStream();
			byte[] buffer = new byte[4_096];
			int read;
			while ((read = input.read(buffer)) >= 0)
				response.write(buffer, 0, read);
			return WireResponse.parse(response.toByteArray());
		}
	}

	private static void assertGolden(WireResponse response, String fixture)
			throws Exception {
		Assertions.assertArrayEquals(readGolden(fixture), response.canonicalWire(),
				fixture + ":\n" + response.canonicalText());
		Assertions.assertEquals(List.of("no-store"),
				response.headerValues("Cache-Control"), fixture);
		Assertions.assertFalse(response.canonicalText().contains(INTERCEPTOR_SECRET),
				fixture);
		Assertions.assertFalse(response.canonicalText().contains(HANDLER_SECRET), fixture);
		Assertions.assertFalse(response.canonicalText().contains(OUTPUT_SECRET), fixture);
	}

	private static byte[] readGolden(String fixture) throws Exception {
		String encoded = Files.readString(GOLDEN_ROOT.resolve(fixture),
				StandardCharsets.US_ASCII);
		Assertions.assertFalse(encoded.contains("\r"), fixture);
		Assertions.assertTrue(encoded.endsWith("\n"), fixture);
		String hex = encoded.substring(0, encoded.length() - 1);
		Assertions.assertTrue(hex.matches("[0-9a-f]+"), fixture);
		Assertions.assertEquals(0, hex.length() % 2, fixture);
		return HexFormat.of().parseHex(hex);
	}

	@BeforeAll
	public static void goldenCorpusIsCompleteAndChecksumBound() throws Exception {
		Path manifest = GOLDEN_ROOT.resolve("manifest.sha256");
		Assertions.assertTrue(Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS));
		List<String> rows = Files.readAllLines(manifest, StandardCharsets.US_ASCII);
		Assertions.assertEquals(22, rows.size());
		List<String> manifested = new ArrayList<>();
		for (String row : rows) {
			String[] fields = row.split("  ", -1);
			Assertions.assertEquals(2, fields.length, row);
			Assertions.assertTrue(fields[0].matches("[0-9a-f]{64}"), row);
			Assertions.assertTrue(fields[1].matches("[a-z0-9-]+\\.http\\.hex"), row);
			Path fixture = GOLDEN_ROOT.resolve(fields[1]);
			Assertions.assertTrue(Files.isRegularFile(
					fixture, LinkOption.NOFOLLOW_LINKS), fields[1]);
			Assertions.assertEquals(fields[0], sha256(Files.readAllBytes(fixture)),
					fields[1]);
			manifested.add(fields[1]);
		}
		Assertions.assertEquals(manifested.stream().sorted().toList(), manifested,
				"HTTP contract manifest must be path-sorted.");
		try (Stream<Path> paths = Files.list(GOLDEN_ROOT)) {
			List<String> actual = paths.sorted()
					.peek(path -> Assertions.assertFalse(
							Files.isSymbolicLink(path), path.toString()))
					.peek(path -> Assertions.assertTrue(Files.isRegularFile(
							path, LinkOption.NOFOLLOW_LINKS),
							"Unexpected non-regular corpus entry: " + path))
					.map(path -> path.getFileName().toString())
					.toList();
			List<String> expected = new ArrayList<>(manifested);
			expected.add("manifest.sha256");
			expected.sort(String::compareTo);
			Assertions.assertEquals(expected, actual);
		}
	}

	@Test
	public void candidateDocumentationPinsCurrentGoldenManifestDigests()
			throws Exception {
		String errorMappingDigest = sha256(Files.readAllBytes(Path.of(
				"conformance", "golden-error-mapping", "live", "manifest.sha256")));
		String httpContractDigest = sha256(Files.readAllBytes(
				GOLDEN_ROOT.resolve("manifest.sha256")));
		StringBuilder completeDocumentation = new StringBuilder();
		for (Path documentation : CANDIDATE_DOCUMENTATION) {
			String source = Files.readString(documentation, StandardCharsets.UTF_8);
			completeDocumentation.append(source);
			Assertions.assertEquals(1, occurrences(source, errorMappingDigest),
					documentation + " must pin the current error-mapping manifest once.");
			Assertions.assertEquals(1, occurrences(source, httpContractDigest),
					documentation + " must pin the current HTTP-contract manifest once.");
		}
		String allSource = completeDocumentation.toString();
		Assertions.assertEquals(0, occurrences(allSource,
				SUPERSEDED_ERROR_MAPPING_MANIFEST_DIGEST));
		Assertions.assertEquals(0, occurrences(allSource,
				SUPERSEDED_HTTP_CONTRACT_MANIFEST_DIGEST));
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static int occurrences(String source, String needle) {
		int count = 0;
		int offset = 0;
		while ((offset = source.indexOf(needle, offset)) >= 0) {
			count++;
			offset += needle.length();
		}
		return count;
	}

	private static void recordOccurrences(Map<String, Integer> responseCreators,
			Path path, String kind, String source, String needle) {
		int count = occurrences(source, needle);
		if (count > 0)
			responseCreators.put(PROTOCOL_SOURCE_ROOT.relativize(path)
					+ ":" + kind, count);
	}

	private static int creatorOccurrences(Map<String, Integer> responseCreators,
			String kindSuffix) {
		return responseCreators.entrySet().stream()
				.filter(entry -> entry.getKey().endsWith(kindSuffix))
				.mapToInt(Map.Entry::getValue)
				.sum();
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static void assertIdleStartedDiagnostics(McpServer server)
			throws Exception {
		awaitCondition(() -> zeroLoad(server.getDiagnostics()));
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.STARTED, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isPresent());
		assertZeroLoad(diagnostics);
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static void stopAndAssertClean(Soklet owner, McpServer server) {
		owner.stop();
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.STOPPED, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isPresent());
		assertZeroLoad(diagnostics);
	}

	private static boolean zeroLoad(McpServerDiagnostics diagnostics) {
		return diagnostics.getActiveHandlerExecutions() == 0
				&& diagnostics.getQueuedRequests() == 0
				&& diagnostics.getActiveRequestStreams() == 0
				&& diagnostics.getActiveSubscriptions() == 0;
	}

	private static void assertZeroLoad(McpServerDiagnostics diagnostics) {
		Assertions.assertEquals(0, diagnostics.getActiveHandlerExecutions());
		Assertions.assertEquals(0, diagnostics.getQueuedRequests());
		Assertions.assertEquals(0, diagnostics.getActiveRequestStreams());
		Assertions.assertEquals(0, diagnostics.getActiveSubscriptions());
	}

	private static void awaitCondition(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		do {
			if (condition.getAsBoolean())
				return;
			Thread.onSpinWait();
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for MCP contract state.");
	}

	private record HeaderLine(String name, String value) {
	}

	private record RequestSpec(String method, String path, List<HeaderLine> headers,
			byte[] body, byte[] rawRequest) {
		private RequestSpec {
			headers = List.copyOf(headers);
			body = body.clone();
			rawRequest = rawRequest == null ? null : rawRequest.clone();
		}

		private static RequestSpec raw(String request) {
			return new RequestSpec("", "", List.of(), new byte[0],
					request.getBytes(StandardCharsets.ISO_8859_1));
		}
	}

	private record RequestCase(String name, RequestSpec request, String fixture,
			List<String> expectedStages) {
		private RequestCase {
			expectedStages = List.copyOf(expectedStages);
		}
	}

	private record ContractArguments(String value) {
	}

	private record ContractResult(String value) {
	}

	private static final class FixtureState {
		private final Map<String, CopyOnWriteArrayList<String>> stages =
				new java.util.concurrent.ConcurrentHashMap<>();
		private final CountDownLatch handlerHeld = new CountDownLatch(1);
		private final CountDownLatch releaseHandlers = new CountDownLatch(1);

		private String caseName(Request request) {
			return request.getHeader(CASE_HEADER).orElse("<missing-case>");
		}

		private void record(String caseName, String stage) {
			this.stages.computeIfAbsent(caseName,
					ignored -> new CopyOnWriteArrayList<>()).add(stage);
		}

		private List<String> stages(String caseName) {
			return List.copyOf(this.stages.getOrDefault(caseName,
					new CopyOnWriteArrayList<>()));
		}

		private void releaseHeldHandlers() {
			this.releaseHandlers.countDown();
		}
	}

	private record WireResponse(byte[] raw, String rawHead, int status,
			Map<String, List<String>> headers) {
		private WireResponse {
			raw = raw.clone();
			headers = Map.copyOf(headers);
		}

		private static WireResponse parse(byte[] raw) {
			byte[] delimiter = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
			int boundary = indexOf(raw, delimiter);
			if (boundary < 0)
				throw new AssertionError("Response did not contain a complete HTTP head.");
			String rawHead = new String(raw, 0, boundary + delimiter.length,
					StandardCharsets.ISO_8859_1);
			String[] lines = rawHead.substring(0, rawHead.length() - delimiter.length)
					.split("\r\n");
			String[] status = lines[0].split(" ", 3);
			Map<String, List<String>> mutable = new LinkedHashMap<>();
			for (int index = 1; index < lines.length; index++) {
				int colon = lines[index].indexOf(':');
				if (colon < 1)
					throw new AssertionError("Malformed response header: " + lines[index]);
				String name = lines[index].substring(0, colon)
						.toLowerCase(Locale.ROOT);
				String value = lines[index].substring(colon + 1).trim();
				mutable.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
			}
			Map<String, List<String>> copied = new LinkedHashMap<>();
			mutable.forEach((name, values) -> copied.put(name, List.copyOf(values)));
			return new WireResponse(raw, rawHead, Integer.parseInt(status[1]), copied);
		}

		private List<String> headerValues(String name) {
			return this.headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
		}

		private byte[] canonicalWire() {
			ByteArrayOutputStream canonical = new ByteArrayOutputStream(this.raw.length);
			for (int index = 0; index < this.raw.length; index++) {
				byte value = this.raw[index];
				if (value == '\r') {
					if (index + 1 >= this.raw.length || this.raw[index + 1] != '\n')
						throw new AssertionError("Response contains a bare CR byte.");
					canonical.write('\n');
					index++;
				} else {
					canonical.write(value);
				}
			}
			return canonical.toByteArray();
		}

		private String canonicalText() {
			return new String(canonicalWire(), StandardCharsets.UTF_8);
		}

		private static int indexOf(byte[] bytes, byte[] target) {
			outer:
			for (int offset = 0; offset <= bytes.length - target.length; offset++) {
				for (int index = 0; index < target.length; index++)
					if (bytes[offset + index] != target[index])
						continue outer;
				return offset;
			}
			return -1;
		}
	}
}
