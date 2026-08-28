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
import com.soklet.McpAdmissionController;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpInputRequest;
import com.soklet.McpInputRequestDeclaration;
import com.soklet.McpInputRequiredResult;
import com.soklet.McpInputRequirement;
import com.soklet.McpJsonObject;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpServer;
import com.soklet.McpServerDiagnostics;
import com.soklet.McpServerStatus;
import com.soklet.McpToolRegistration;
import com.soklet.McpUnknownMirroredHeaderPolicy;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
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
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Independent complete-response goldens for the eight fixed modern error
 * mapping families emitted by the production MCP listener.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(60)
public class McpErrorMappingGoldenProductionTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String CASE_HEADER = "X-Error-Mapping-Case";
	private static final String REGULAR_TOOL = "error.regular";
	private static final String REQUIRED_TOOL = "error.required-roots";
	private static final String CONDITIONAL_TOOL = "error.conditional-roots";
	private static final String HOLD_TOOL = "error.hold";
	private static final String RATE_SECRET = "RATE-LIMIT-REQUEST-SECRET";
	private static final String STRICT_NAME_SECRET =
			"Mcp-Param-Strict-Secret-Name";
	private static final String STRICT_VALUE_SECRET =
			"STRICT-UNKNOWN-VALUE-SECRET";
	private static final String HEADER_SECRET = "HEADER-MISMATCH-NAME-SECRET";
	private static final String CONDITIONAL_SECRET =
			"CONDITIONAL-CAPABILITY-RESULT-SECRET";
	private static final String INVALID_PARAMS_SECRET =
			"INVALID-PARAMS-SECRET";
	private static final String UNKNOWN_METHOD_SECRET =
			"future/UNKNOWN-METHOD-SECRET";
	private static final Path GOLDEN_ROOT = Path.of(
			"conformance", "golden-error-mapping", "live");
	private static final String MANIFEST_NAME = "manifest.sha256";
	private static final Map<String, FixtureContract> FIXTURES = fixtures();

	@BeforeAll
	public static void corpusIsCompleteChecksumBoundAndExhaustsEightFamilies()
			throws Exception {
		Assertions.assertEquals(12, FIXTURES.size());
		Assertions.assertTrue(Files.isDirectory(
				GOLDEN_ROOT, LinkOption.NOFOLLOW_LINKS), GOLDEN_ROOT.toString());
		Assertions.assertFalse(Files.isSymbolicLink(GOLDEN_ROOT),
				GOLDEN_ROOT.toString());
		Path manifest = GOLDEN_ROOT.resolve(MANIFEST_NAME);
		Assertions.assertTrue(Files.isRegularFile(
				manifest, LinkOption.NOFOLLOW_LINKS), manifest.toString());
		Assertions.assertFalse(Files.isSymbolicLink(manifest), manifest.toString());

		List<String> lines = Files.readAllLines(manifest, StandardCharsets.US_ASCII);
		Assertions.assertEquals(FIXTURES.size(), lines.size());
		Pattern rowPattern = Pattern.compile("([0-9a-f]{64})  ([^/]+)");
		Map<String, String> hashes = new LinkedHashMap<>();
		for (String line : lines) {
			Matcher matcher = rowPattern.matcher(line);
			Assertions.assertTrue(matcher.matches(), line);
			String filename = matcher.group(2);
			Assertions.assertTrue(filename.matches(
					"[a-z0-9-]+\\.http\\.hex"), filename);
			Assertions.assertNull(hashes.put(filename, matcher.group(1)), filename);
		}
		Assertions.assertEquals(hashes.keySet().stream().sorted().toList(),
				List.copyOf(hashes.keySet()),
				"The error-mapping manifest must be path-sorted.");
		Assertions.assertEquals(FIXTURES.keySet(), hashes.keySet());

		Set<String> actualEntries = new LinkedHashSet<>();
		try (Stream<Path> entries = Files.list(GOLDEN_ROOT)) {
			for (Path entry : entries.sorted().toList()) {
				String filename = entry.getFileName().toString();
				actualEntries.add(filename);
				Assertions.assertFalse(Files.isSymbolicLink(entry), filename);
				Assertions.assertTrue(Files.isRegularFile(
						entry, LinkOption.NOFOLLOW_LINKS), filename);
			}
		}
		Set<String> expectedEntries = new LinkedHashSet<>(FIXTURES.keySet());
		expectedEntries.add(MANIFEST_NAME);
		Assertions.assertEquals(expectedEntries, actualEntries);

		EnumSet<MappingFamily> families = EnumSet.noneOf(MappingFamily.class);
		Set<String> idTypes = new LinkedHashSet<>();
		for (Map.Entry<String, FixtureContract> entry : FIXTURES.entrySet()) {
			String filename = entry.getKey();
			Path path = GOLDEN_ROOT.resolve(filename);
			byte[] encodedFixture = Files.readAllBytes(path);
			Assertions.assertEquals(hashes.get(filename), sha256(encodedFixture),
					filename);
			String hex = new String(encodedFixture, StandardCharsets.US_ASCII);
			Assertions.assertTrue(hex.endsWith("\n"), filename);
			Assertions.assertFalse(hex.contains("\r"), filename);
			Assertions.assertTrue(hex.substring(0, hex.length() - 1)
					.matches("[0-9a-f]+"), filename);
			WireResponse golden = WireResponse.parseCanonical(
					readGolden(filename));
			assertResponseContract(golden, entry.getValue(), filename);
			for (String secret : allSecrets())
				Assertions.assertFalse(golden.canonicalText().contains(secret),
						filename);
			families.add(entry.getValue().family());
			idTypes.add(entry.getValue().stringId() ? "string" : "integer");
		}
		Assertions.assertEquals(EnumSet.allOf(MappingFamily.class), families);
		Assertions.assertEquals(Set.of("string", "integer"), idTypes);
		Assertions.assertEquals(2, FIXTURES.values().stream()
				.filter(value -> value.family() == MappingFamily.MISSING_CAPABILITY)
				.count(), "Required and conditional capability paths need goldens.");
	}

	@Test
	public void ordinaryMappingFamiliesMatchProductionListenerGoldens()
			throws Exception {
		FixtureState state = new FixtureState();
		McpServer server = server(state);
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = boundPort(server);
			List<LiveCase> cases = List.of(
					new LiveCase("rate-limit-string-429.http.hex",
							post("rate-limit", requestBody("\"rate-limit\"",
									"tools/call", PROTOCOL_VERSION, "{}",
									",\"name\":\"" + REGULAR_TOOL
											+ "\",\"arguments\":{}"),
									withHeader(toolHeaders(REGULAR_TOOL,
											PROTOCOL_VERSION), "X-Request-Canary",
											RATE_SECRET))),
					new LiveCase("strict-unknown-integer-400.http.hex",
							post("strict-unknown", requestBody("31998",
									"tools/call", PROTOCOL_VERSION, "{}",
									",\"name\":\"" + REGULAR_TOOL
											+ "\",\"arguments\":{}"),
									withHeader(toolHeaders(REGULAR_TOOL,
											PROTOCOL_VERSION), STRICT_NAME_SECRET,
											STRICT_VALUE_SECRET))),
					new LiveCase("header-mismatch-integer-400.http.hex",
							post("header-mismatch", requestBody("32020",
									"tools/call", PROTOCOL_VERSION, "{}",
									",\"name\":\"" + REGULAR_TOOL
											+ "\",\"arguments\":{}"),
									toolHeaders(HEADER_SECRET, PROTOCOL_VERSION))),
					new LiveCase(
							"unsupported-selector-name-mismatch-integer-400.http.hex",
							post("unsupported-selector-name-mismatch",
									requestBody("32020", "tools/call", "2099-01-01",
											"{}", ",\"name\":\"" + REGULAR_TOOL
													+ "\",\"arguments\":{}"),
									toolHeaders(HEADER_SECRET, "2099-01-01"))),
					new LiveCase(
							"unsupported-selector-strict-unknown-integer-400.http.hex",
							post("unsupported-selector-strict-unknown",
									requestBody("31998", "tools/call", "2099-01-01",
											"{}", ",\"name\":\"" + REGULAR_TOOL
													+ "\",\"arguments\":{}"),
									withHeader(toolHeaders(REGULAR_TOOL, "2099-01-01"),
											STRICT_NAME_SECRET, STRICT_VALUE_SECRET))),
					new LiveCase(
							"unsupported-selector-body-version-mismatch-string-400.http.hex",
							post("unsupported-selector-body-version-mismatch",
									requestBody("\"unsupported-body-version\"",
											"tools/call", PROTOCOL_VERSION, "{}",
											",\"name\":\"" + REGULAR_TOOL
													+ "\",\"arguments\":{}"),
									toolHeaders(REGULAR_TOOL, "2099-01-01"))),
					new LiveCase("missing-capability-required-string-400.http.hex",
							post("missing-required", requestBody(
									"\"missing-required\"", "tools/call",
									PROTOCOL_VERSION, "{}", ",\"name\":\""
											+ REQUIRED_TOOL + "\",\"arguments\":{}"),
									toolHeaders(REQUIRED_TOOL, PROTOCOL_VERSION))),
					new LiveCase("missing-capability-conditional-integer-400.http.hex",
							post("missing-conditional", requestBody("32021",
									"tools/call", PROTOCOL_VERSION, "{}",
									",\"name\":\"" + CONDITIONAL_TOOL
											+ "\",\"arguments\":{}"),
									toolHeaders(CONDITIONAL_TOOL, PROTOCOL_VERSION))),
					new LiveCase("unsupported-version-string-400.http.hex",
							post("unsupported-version", requestBody(
									"\"unsupported-version\"", "tools/call",
									"2099-01-01", "{}", ",\"name\":\""
											+ REGULAR_TOOL + "\",\"arguments\":{}"),
									toolHeaders(REGULAR_TOOL, "2099-01-01"))),
					new LiveCase("invalid-params-integer-400.http.hex",
							post("invalid-params", requestBody("32602",
									"tools/call", PROTOCOL_VERSION, "{}",
									",\"name\":\"" + REGULAR_TOOL
											+ "\",\"arguments\":\""
											+ INVALID_PARAMS_SECRET + "\""),
									toolHeaders(REGULAR_TOOL, PROTOCOL_VERSION))),
					new LiveCase("method-not-found-string-404.http.hex",
							post("method-not-found", requestBody(
									"\"unknown-method\"", UNKNOWN_METHOD_SECRET,
									PROTOCOL_VERSION, "{}", ""),
									requestHeaders(UNKNOWN_METHOD_SECRET,
											PROTOCOL_VERSION, null))));

			for (LiveCase testCase : cases) {
				WireResponse response = exchange(port, testCase.request());
				assertGolden(response, testCase.fixture());
			}
			Assertions.assertEquals(0, state.regularHandlerInvocations.get());
			Assertions.assertEquals(0, state.requiredHandlerInvocations.get());
			Assertions.assertEquals(1, state.conditionalHandlerInvocations.get());
			awaitIdle(server);
		} finally {
			state.releaseHandlers.countDown();
			stopAndAssertClean(owner, server);
		}
	}

	@Test
	@Timeout(120)
	public void overloadMappingMatchesProductionListenerGolden() throws Exception {
		FixtureState state = new FixtureState();
		McpServer server = server(state);
		Soklet owner = managedSoklet(server);
		ExecutorService clients = Executors.newFixedThreadPool(2);
		try {
			owner.start();
			int port = boundPort(server);
			Future<WireResponse> active = clients.submit(() -> exchange(port,
					post("overload-active", requestBody("\"active\"",
							"tools/call", PROTOCOL_VERSION, "{}",
							",\"name\":\"" + HOLD_TOOL
									+ "\",\"arguments\":{}"),
							toolHeaders(HOLD_TOOL, PROTOCOL_VERSION))));
			Assertions.assertTrue(state.handlerHeld.await(5, TimeUnit.SECONDS),
					"The active overload fixture did not enter its handler.");
			Future<WireResponse> queued = clients.submit(() -> exchange(port,
					post("overload-queued", requestBody("\"queued\"",
							"tools/call", PROTOCOL_VERSION, "{}",
							",\"name\":\"" + HOLD_TOOL
									+ "\",\"arguments\":{}"),
							toolHeaders(HOLD_TOOL, PROTOCOL_VERSION))));
			awaitCondition(() -> server.getDiagnostics().getQueuedRequests() == 1);

			WireResponse overload = exchange(port,
					post("overload", requestBody("\"overload\"",
							"tools/call", PROTOCOL_VERSION, "{}",
							",\"name\":\"" + HOLD_TOOL
									+ "\",\"arguments\":{}"),
							toolHeaders(HOLD_TOOL, PROTOCOL_VERSION)));
			assertGolden(overload, "overload-string-503.http.hex");
			Assertions.assertEquals(1, state.holdHandlerInvocations.get(),
					"The overloaded request must not enter the handler.");

			state.releaseHandlers.countDown();
			Assertions.assertEquals(200, active.get(5, TimeUnit.SECONDS).status());
			Assertions.assertEquals(200, queued.get(5, TimeUnit.SECONDS).status());
			Assertions.assertEquals(2, state.holdHandlerInvocations.get());
			awaitIdle(server);
		} finally {
			state.releaseHandlers.countDown();
			stopAndAssertClean(owner, server);
			clients.shutdownNow();
			Assertions.assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	private static McpServer server(FixtureState state) {
		McpInputRequestDeclaration requiredRoots =
				McpInputRequestDeclaration.fromRoots(McpInputRequirement.REQUIRED);
		McpInputRequestDeclaration conditionalRoots =
				McpInputRequestDeclaration.fromRoots(McpInputRequirement.CONDITIONAL);
		McpToolRegistration<McpJsonObject> regular = McpToolRegistration
				.withName(REGULAR_TOOL)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					state.regularHandlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("unused");
				})
				.build();
		McpToolRegistration<McpJsonObject> required = McpToolRegistration
				.withName(REQUIRED_TOOL)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					state.requiredHandlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("must-not-run");
				})
				.mayRequestInput(requiredRoots)
				.build();
		McpToolRegistration<McpJsonObject> conditional = McpToolRegistration
				.withName(CONDITIONAL_TOOL)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					state.conditionalHandlerInvocations.incrementAndGet();
					return McpInputRequiredResult.builder()
							.inputRequest("roots-" + CONDITIONAL_SECRET,
									McpInputRequest.fromDeclaration(conditionalRoots,
											McpJsonObject.emptyInstance()))
							.metadata(McpJsonObject.builder()
									.put("secret", CONDITIONAL_SECRET).build())
							.build();
				})
				.mayRequestInput(conditionalRoots)
				.build();
		McpToolRegistration<McpJsonObject> hold = McpToolRegistration
				.withName(HOLD_TOOL)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					state.holdHandlerInvocations.incrementAndGet();
					if ("overload-active".equals(request.getRequest()
							.getHeader(CASE_HEADER).orElse(""))) {
						state.handlerHeld.countDown();
						if (!state.releaseHandlers.await(5, TimeUnit.SECONDS))
							throw new AssertionError(
									"The active overload handler was not released.");
					}
					return McpCompleteResult.fromToolText("released");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"error-mapping-golden", "4.0.0-SNAPSHOT").build())
				.tools(List.of(regular, required, conditional, hold))
				.build();
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> "rate-limit".equals(context.getRequest()
						.getHeader(CASE_HEADER).orElse(""))
						? McpRateLimitDecision.denied(Duration.ofMillis(1))
						: McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.unknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.requestHandlerConcurrency(1)
				.requestHandlerQueueCapacity(1)
				.build();
	}

	private static String requestBody(String idJson, String method,
			String version, String clientCapabilities, String additionalFields) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + version
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":"
				+ clientCapabilities + "}" + additionalFields + "}}";
	}

	private static RequestSpec post(String caseName, String body,
			List<HeaderLine> headers) {
		List<HeaderLine> allHeaders = new ArrayList<>(headers.size() + 1);
		allHeaders.add(new HeaderLine(CASE_HEADER, caseName));
		allHeaders.addAll(headers);
		return new RequestSpec("POST", MCP_PATH, List.copyOf(allHeaders),
				body.getBytes(StandardCharsets.UTF_8));
	}

	private static List<HeaderLine> toolHeaders(String name, String version) {
		return requestHeaders("tools/call", version, name);
	}

	private static List<HeaderLine> requestHeaders(String method, String version,
			String operationName) {
		List<HeaderLine> headers = new ArrayList<>();
		headers.add(new HeaderLine("Content-Type", "application/json"));
		headers.add(new HeaderLine("Accept",
				"application/json, text/event-stream"));
		headers.add(new HeaderLine("MCP-Protocol-Version", version));
		headers.add(new HeaderLine("Mcp-Method", method));
		if (operationName != null)
			headers.add(new HeaderLine("Mcp-Name", operationName));
		return List.copyOf(headers);
	}

	private static List<HeaderLine> withHeader(List<HeaderLine> headers,
			String name, String value) {
		List<HeaderLine> merged = new ArrayList<>(headers.size() + 1);
		merged.addAll(headers);
		merged.add(new HeaderLine(name, value));
		return List.copyOf(merged);
	}

	private static WireResponse exchange(int port, RequestSpec request)
			throws Exception {
		StringBuilder head = new StringBuilder()
				.append(request.method()).append(' ').append(request.path())
				.append(" HTTP/1.1\r\nHost: ").append(LOOPBACK).append(':')
				.append(port).append("\r\n");
		for (HeaderLine header : request.headers())
			head.append(header.name()).append(": ").append(header.value())
					.append("\r\n");
		head.append("Content-Length: ").append(request.body().length)
				.append("\r\nConnection: close\r\n\r\n");
		ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
		requestBytes.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
		requestBytes.write(request.body());

		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(LOOPBACK, port), 3_000);
			socket.setSoTimeout(5_000);
			socket.getOutputStream().write(requestBytes.toByteArray());
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
		FixtureContract contract = FIXTURES.get(fixture);
		Assertions.assertNotNull(contract, fixture);
		Assertions.assertArrayEquals(readGolden(fixture), response.canonicalWire(),
				fixture + ":\n" + response.canonicalText());
		assertResponseContract(response, contract, fixture);
		for (String secret : allSecrets())
			Assertions.assertFalse(response.canonicalText().contains(secret), fixture);
	}

	private static void assertResponseContract(WireResponse response,
			FixtureContract contract, String fixture) {
		Assertions.assertEquals(contract.status(), response.status(), fixture);
		Assertions.assertEquals(List.of("no-store"),
				response.headerValues("Cache-Control"), fixture);
		Assertions.assertEquals(List.of("application/json"),
				response.headerValues("Content-Type"), fixture);
		Assertions.assertEquals(contract.retryAfter()
				? List.of("1") : List.of(), response.headerValues("Retry-After"),
				fixture);
		Assertions.assertEquals(response.body().getBytes(StandardCharsets.UTF_8).length,
				Integer.parseInt(response.singleHeader("Content-Length")), fixture);
		com.soklet.internal.mcp.protocol.McpJsonObject root =
				Assertions.assertInstanceOf(
						com.soklet.internal.mcp.protocol.McpJsonObject.class,
				new McpJsonCodec(McpJsonLimits.productionDefaults())
						.parse(response.body()));
		Assertions.assertEquals(Set.of("jsonrpc", "id", "error"),
				root.members().keySet(), fixture);
		com.soklet.internal.mcp.protocol.McpJsonObject error =
				Assertions.assertInstanceOf(
						com.soklet.internal.mcp.protocol.McpJsonObject.class,
				root.members().get("error"));
		Assertions.assertEquals(new McpJsonNumber(contract.code()),
				error.members().get("code"), fixture);
		Assertions.assertFalse(root.members().containsKey("result"), fixture);
		Assertions.assertFalse(root.members().containsKey("method"), fixture);
		McpJsonRpcEnvelope.ErrorResponse envelope = Assertions.assertInstanceOf(
				McpJsonRpcEnvelope.ErrorResponse.class, codec().decode(response.body()));
		McpJsonRpcId id = envelope.id().orElseThrow();
		Assertions.assertEquals(contract.stringId(),
				id instanceof McpJsonRpcId.StringId, fixture);
	}

	private static McpJsonRpcEnvelopeCodec codec() {
		return new McpJsonRpcEnvelopeCodec(
				new McpJsonCodec(McpJsonLimits.productionDefaults()));
	}

	private static byte[] readGolden(String fixture) throws Exception {
		String encoded = Files.readString(GOLDEN_ROOT.resolve(fixture),
				StandardCharsets.US_ASCII);
		Assertions.assertTrue(encoded.endsWith("\n"), fixture);
		String hex = encoded.substring(0, encoded.length() - 1);
		Assertions.assertEquals(0, hex.length() % 2, fixture);
		return HexFormat.of().parseHex(hex);
	}

	private static Map<String, FixtureContract> fixtures() {
		Map<String, FixtureContract> fixtures = new LinkedHashMap<>();
		fixtures.put("header-mismatch-integer-400.http.hex",
				new FixtureContract(MappingFamily.HEADER_MISMATCH, 400, -32020,
						false, false));
		fixtures.put("invalid-params-integer-400.http.hex",
				new FixtureContract(MappingFamily.INVALID_PARAMS, 400, -32602,
						false, false));
		fixtures.put("method-not-found-string-404.http.hex",
				new FixtureContract(MappingFamily.METHOD_NOT_FOUND, 404, -32601,
						true, false));
		fixtures.put("missing-capability-conditional-integer-400.http.hex",
				new FixtureContract(MappingFamily.MISSING_CAPABILITY, 400, -32021,
						false, false));
		fixtures.put("missing-capability-required-string-400.http.hex",
				new FixtureContract(MappingFamily.MISSING_CAPABILITY, 400, -32021,
						true, false));
		fixtures.put("overload-string-503.http.hex",
				new FixtureContract(MappingFamily.OVERLOAD, 503, -32603,
						true, false));
		fixtures.put("rate-limit-string-429.http.hex",
				new FixtureContract(MappingFamily.RATE_LIMIT, 429, -31999,
						true, true));
		fixtures.put("strict-unknown-integer-400.http.hex",
				new FixtureContract(MappingFamily.STRICT_UNKNOWN, 400, -31998,
						false, false));
		fixtures.put("unsupported-selector-body-version-mismatch-string-400.http.hex",
				new FixtureContract(MappingFamily.HEADER_MISMATCH, 400, -32020,
						true, false));
		fixtures.put("unsupported-selector-name-mismatch-integer-400.http.hex",
				new FixtureContract(MappingFamily.HEADER_MISMATCH, 400, -32020,
						false, false));
		fixtures.put("unsupported-selector-strict-unknown-integer-400.http.hex",
				new FixtureContract(MappingFamily.STRICT_UNKNOWN, 400, -31998,
						false, false));
		fixtures.put("unsupported-version-string-400.http.hex",
				new FixtureContract(MappingFamily.UNSUPPORTED_VERSION, 400, -32022,
						true, false));
		return Collections.unmodifiableMap(fixtures);
	}

	private static Set<String> allSecrets() {
		return Set.of(RATE_SECRET, STRICT_NAME_SECRET, STRICT_VALUE_SECRET,
				HEADER_SECRET, CONDITIONAL_SECRET, INVALID_PARAMS_SECRET,
				UNKNOWN_METHOD_SECRET);
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static void awaitIdle(McpServer server) throws Exception {
		awaitCondition(() -> zeroLoad(server.getDiagnostics()));
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static void stopAndAssertClean(Soklet owner, McpServer server) {
		owner.close();
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.TERMINATED, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isPresent());
		Assertions.assertTrue(zeroLoad(diagnostics));
	}

	private static boolean zeroLoad(McpServerDiagnostics diagnostics) {
		return diagnostics.getActiveHandlerExecutions() == 0
				&& diagnostics.getQueuedRequests() == 0
				&& diagnostics.getActiveRequestStreams() == 0
				&& diagnostics.getActiveSubscriptions() == 0;
	}

	private static void awaitCondition(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		do {
			if (condition.getAsBoolean())
				return;
			Thread.onSpinWait();
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for MCP error-mapping state.");
	}

	private enum MappingFamily {
		RATE_LIMIT,
		STRICT_UNKNOWN,
		OVERLOAD,
		HEADER_MISMATCH,
		MISSING_CAPABILITY,
		UNSUPPORTED_VERSION,
		INVALID_PARAMS,
		METHOD_NOT_FOUND
	}

	private record FixtureContract(MappingFamily family, int status, int code,
			boolean stringId, boolean retryAfter) {
	}

	private record HeaderLine(String name, String value) {
	}

	private record RequestSpec(String method, String path,
			List<HeaderLine> headers, byte[] body) {
		private RequestSpec {
			headers = List.copyOf(headers);
			body = body.clone();
		}

		@Override
		public byte[] body() {
			return body.clone();
		}
	}

	private record LiveCase(String fixture, RequestSpec request) {
	}

	private static final class FixtureState {
		private final AtomicInteger regularHandlerInvocations = new AtomicInteger();
		private final AtomicInteger requiredHandlerInvocations = new AtomicInteger();
		private final AtomicInteger conditionalHandlerInvocations = new AtomicInteger();
		private final AtomicInteger holdHandlerInvocations = new AtomicInteger();
		private final CountDownLatch handlerHeld = new CountDownLatch(1);
		private final CountDownLatch releaseHandlers = new CountDownLatch(1);
	}

	private record WireResponse(byte[] raw, int status,
			Map<String, List<String>> headers, String body) {
		private WireResponse {
			raw = raw.clone();
			headers = Map.copyOf(headers);
		}

		@Override
		public byte[] raw() {
			return raw.clone();
		}

		private static WireResponse parse(byte[] raw) {
			return parse(raw, "\r\n");
		}

		private static WireResponse parseCanonical(byte[] raw) {
			return parse(raw, "\n");
		}

		private static WireResponse parse(byte[] raw, String newline) {
			String text = new String(raw, StandardCharsets.UTF_8);
			String delimiter = newline + newline;
			int boundary = text.indexOf(delimiter);
			if (boundary < 0)
				throw new AssertionError("Response did not contain a complete HTTP head.");
			String[] lines = text.substring(0, boundary).split(
					Pattern.quote(newline));
			String[] status = lines[0].split(" ", 3);
			Map<String, List<String>> mutable = new LinkedHashMap<>();
			for (int index = 1; index < lines.length; index++) {
				int colon = lines[index].indexOf(':');
				if (colon < 1)
					throw new AssertionError(
							"Malformed response header: " + lines[index]);
				String name = lines[index].substring(0, colon)
						.toLowerCase(Locale.ROOT);
				String value = lines[index].substring(colon + 1).trim();
				mutable.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
			}
			Map<String, List<String>> copied = new LinkedHashMap<>();
			mutable.forEach((name, values) ->
					copied.put(name, List.copyOf(values)));
			return new WireResponse(raw, Integer.parseInt(status[1]), copied,
					text.substring(boundary + delimiter.length()));
		}

		private List<String> headerValues(String name) {
			return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
		}

		private String singleHeader(String name) {
			List<String> values = headerValues(name);
			Assertions.assertEquals(1, values.size(), name);
			return values.get(0);
		}

		private byte[] canonicalWire() {
			ByteArrayOutputStream canonical = new ByteArrayOutputStream(raw.length);
			for (int index = 0; index < raw.length; index++) {
				byte value = raw[index];
				if (value == '\r') {
					if (index + 1 >= raw.length || raw[index + 1] != '\n')
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
	}
}
