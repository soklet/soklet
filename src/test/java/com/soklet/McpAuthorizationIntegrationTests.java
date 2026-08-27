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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Real-listener authorization challenge and independent CORS head goldens.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(30)
public class McpAuthorizationIntegrationTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TOOL_NAME = "weather.current";
	private static final String ALLOWED_ORIGIN = "https://allowed.example";
	private static final String REJECTED_ORIGIN = "https://rejected.example";
	private static final String RESOURCE_METADATA =
			"https://auth.example.test/.well-known/oauth-protected-resource/mcp";
	private static final String TOOL_CHALLENGE = "Bearer resource_metadata=\""
			+ RESOURCE_METADATA + "\", scope=\"mcp:tools:call "
			+ "mcp:tools:call:weather.current\"";
	private static final String NOTIFICATION_CHALLENGE = "Bearer resource_metadata=\""
			+ RESOURCE_METADATA + "\", scope=\"mcp:notifications\"";
	private static final Path GOLDEN_ROOT = Path.of(
			"conformance", "golden-http-head", "authorization-cors");

	@Test
	public void passesSafeBearerChallenge() throws Exception {
		FixtureState state = new FixtureState();
		McpServer server = server(state);
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			WireResponse request = exchange(port, "POST", toolRequest("auth-request"),
					toolHeaders());
			WireResponse notification = exchange(port, "POST", cancellationNotification(),
					notificationHeaders());

			Assertions.assertTrue(URI.create(RESOURCE_METADATA).isAbsolute());
			Assertions.assertEquals(401, request.status(), request.rawHead());
			Assertions.assertEquals("application/json",
					request.singleHeader("Content-Type"));
			Assertions.assertEquals("97", request.singleHeader("Content-Length"));
			Assertions.assertEquals("no-store",
					request.singleHeader("Cache-Control"));
			Assertions.assertEquals(TOOL_CHALLENGE,
					request.singleHeader("WWW-Authenticate"));
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"auth-request\","
					+ "\"error\":{\"code\":-31901,"
					+ "\"message\":\"Authentication required\"}}",
					request.bodyText());
			assertNoSessionHeaders(request);

			Assertions.assertEquals(401, notification.status(), notification.rawHead());
			Assertions.assertEquals("no-store",
					notification.singleHeader("Cache-Control"));
			Assertions.assertEquals(NOTIFICATION_CHALLENGE,
					notification.singleHeader("WWW-Authenticate"));
			Assertions.assertEquals("0",
					notification.singleHeader("Content-Length"));
			Assertions.assertEquals(0, notification.body().length);
			Assertions.assertFalse(notification.hasHeader("Content-Type"),
					notification.rawHead());
			assertNoSessionHeaders(notification);

			Assertions.assertEquals(2, state.admissions.size());
			McpAdmissionContext requestAdmission = state.admissions.get(0);
			Assertions.assertFalse(requestAdmission.isNotification());
			Assertions.assertEquals("tools/call", requestAdmission.getJsonRpcMethod());
			Assertions.assertEquals(TOOL_NAME,
					requestAdmission.getOperationName().orElseThrow());
			Assertions.assertEquals("auth-request", requestAdmission.getRequestId()
					.orElseThrow().asString().orElseThrow());
			McpAdmissionContext notificationAdmission = state.admissions.get(1);
			Assertions.assertTrue(notificationAdmission.isNotification());
			Assertions.assertEquals("notifications/cancelled",
					notificationAdmission.getJsonRpcMethod());
			Assertions.assertTrue(notificationAdmission.getOperationName().isEmpty());
			Assertions.assertTrue(notificationAdmission.getRequestId().isEmpty());
			state.assertNoDownstreamInvocations();
			assertIdleStartedDiagnostics(server);
		} finally {
			stopAndAssertClean(soklet, server);
		}
	}

	@Test
	public void corsResponseHeadsMatchIndependentGoldens() throws Exception {
		assertGoldenManifest();
		FixtureState state = new FixtureState();
		McpServer server = server(state);
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			WireResponse preflight = exchange(port, "OPTIONS", "", List.of(
					new HeaderLine("Origin", ALLOWED_ORIGIN),
					new HeaderLine("Access-Control-Request-Method", "POST"),
					new HeaderLine("Access-Control-Request-Headers",
							"Mcp-Param-Tenant, MCP-Protocol-Version, Authorization, "
									+ "Mcp-Name, Content-Type, Accept, Mcp-Method")));
			assertGoldenHead(preflight, "authorized-preflight.head");
			Assertions.assertEquals(204, preflight.status(), preflight.rawHead());
			Assertions.assertEquals(ALLOWED_ORIGIN,
					preflight.singleHeader("Access-Control-Allow-Origin"));
			Assertions.assertEquals("POST, OPTIONS",
					preflight.singleHeader("Access-Control-Allow-Methods"));
			Assertions.assertEquals("Accept, Authorization, Content-Type, Mcp-Method, "
					+ "Mcp-Name, Mcp-Param-Tenant, MCP-Protocol-Version",
					preflight.singleHeader("Access-Control-Allow-Headers"));
			Assertions.assertEquals("Origin, Access-Control-Request-Method, "
					+ "Access-Control-Request-Headers", preflight.singleHeader("Vary"));
			Assertions.assertEquals(0, preflight.body().length);
			Assertions.assertFalse(preflight.hasHeader("WWW-Authenticate"),
					preflight.rawHead());
			assertNoSessionHeaders(preflight);

			for (String legacyHeader : List.of("MCP-Session-Id", "Last-Event-ID")) {
				WireResponse legacyPreflight = exchange(port, "OPTIONS", "", List.of(
						new HeaderLine("Origin", ALLOWED_ORIGIN),
						new HeaderLine("Access-Control-Request-Method", "POST"),
						new HeaderLine("Access-Control-Request-Headers", legacyHeader)));
				assertGoldenHead(legacyPreflight, "empty-cors-rejection.head");
				Assertions.assertEquals(403, legacyPreflight.status(),
						legacyPreflight.rawHead());
				Assertions.assertEquals(0, legacyPreflight.body().length);
				Assertions.assertFalse(legacyPreflight.hasHeader(
						"Access-Control-Allow-Origin"), legacyPreflight.rawHead());
				Assertions.assertFalse(legacyPreflight.hasHeader(
						"Access-Control-Allow-Headers"), legacyPreflight.rawHead());
				Assertions.assertFalse(legacyPreflight.hasHeader("Content-Type"),
						legacyPreflight.rawHead());
				assertNoSessionHeaders(legacyPreflight);
			}

			List<HeaderLine> allowedHeaders = new ArrayList<>(toolHeaders());
			allowedHeaders.add(new HeaderLine("Origin", ALLOWED_ORIGIN));
			WireResponse challenged = exchange(port, "POST", toolRequest("auth-request"),
					List.copyOf(allowedHeaders));
			assertGoldenHead(challenged, "authorized-bearer-challenge.head");
			Assertions.assertEquals(401, challenged.status(), challenged.rawHead());
			Assertions.assertEquals(ALLOWED_ORIGIN,
					challenged.singleHeader("Access-Control-Allow-Origin"));
			Assertions.assertEquals("WWW-Authenticate",
					challenged.singleHeader("Access-Control-Expose-Headers"));
			Assertions.assertEquals("Origin", challenged.singleHeader("Vary"));
			Assertions.assertEquals(TOOL_CHALLENGE,
					challenged.singleHeader("WWW-Authenticate"));
			assertNoSessionHeaders(challenged);

			List<HeaderLine> rejectedHeaders = new ArrayList<>(toolHeaders());
			rejectedHeaders.add(new HeaderLine("Origin", REJECTED_ORIGIN));
			WireResponse rejected = exchange(port, "POST", toolRequest("rejected-origin"),
					List.copyOf(rejectedHeaders));
			assertGoldenHead(rejected, "empty-cors-rejection.head");
			Assertions.assertEquals(403, rejected.status(), rejected.rawHead());
			Assertions.assertEquals(0, rejected.body().length);
			Assertions.assertFalse(rejected.hasHeader("Access-Control-Allow-Origin"),
					rejected.rawHead());
			Assertions.assertFalse(rejected.hasHeader("Access-Control-Expose-Headers"),
					rejected.rawHead());
			Assertions.assertFalse(rejected.hasHeader("WWW-Authenticate"),
					rejected.rawHead());
			assertNoSessionHeaders(rejected);

			Assertions.assertEquals(1, state.admissions.size(),
					"Preflight and rejected Origin must not reach admission.");
			state.assertNoDownstreamInvocations();
			assertIdleStartedDiagnostics(server);
		} finally {
			stopAndAssertClean(soklet, server);
		}
	}

	private static McpServer server(FixtureState state) {
		McpToolRegistration<ScopedArguments> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.argumentType(ScopedArguments.class)
				.handler((request, arguments, features) -> {
					state.handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("must not run");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"authorization-integration-test", "4.0.0-SNAPSHOT").build())
				.tool(tool)
				.build();
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> {
					state.admissions.add(context);
					String challenge = context.isNotification()
							? NOTIFICATION_CHALLENGE : TOOL_CHALLENGE;
					return McpAdmissionDecision.rejected(McpAdmissionRejection
							.withStatusCodeAndError(401, McpJsonRpcError.fromApplication(
									-31901, "Authentication required"))
							.header("WWW-Authenticate", challenge)
							.build());
				})
				.requestRateLimiter(context -> {
					state.requestLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					state.toolLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.handlerInterceptor((context, continuation) -> {
					state.interceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.corsAuthorizer(CorsAuthorizer.fromWhitelistedOrigins(
						Set.of(ALLOWED_ORIGIN)))
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static List<HeaderLine> toolHeaders() {
		return List.of(
				new HeaderLine("Content-Type", "application/json; charset=UTF-8"),
				new HeaderLine("Accept", "application/json, text/event-stream"),
				new HeaderLine("MCP-Protocol-Version", PROTOCOL_VERSION),
				new HeaderLine("Mcp-Method", "tools/call"),
				new HeaderLine("Mcp-Name", TOOL_NAME),
				new HeaderLine("Mcp-Param-Tenant", "acme"));
	}

	private static List<HeaderLine> notificationHeaders() {
		return List.of(
				new HeaderLine("Content-Type", "application/json; charset=UTF-8"),
				new HeaderLine("Accept", "application/json, text/event-stream"),
				new HeaderLine("MCP-Protocol-Version", PROTOCOL_VERSION));
	}

	private static String toolRequest(String id) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + TOOL_NAME + "\","
				+ "\"arguments\":{\"tenant\":\"acme\"}}}";
	}

	private static String cancellationNotification() {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
				+ "\"params\":{\"requestId\":\"unknown\"}}";
	}

	private static WireResponse exchange(int port, String method, String body,
			List<HeaderLine> headers) throws Exception {
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(LOOPBACK, port), 3_000);
			socket.setSoTimeout(5_000);
			StringBuilder requestHead = new StringBuilder()
					.append(method).append(' ').append(MCP_PATH)
					.append(" HTTP/1.1\r\n")
					.append("Host: ").append(LOOPBACK).append(':').append(port)
					.append("\r\n");
			for (HeaderLine header : headers)
				requestHead.append(header.name()).append(": ")
						.append(header.value()).append("\r\n");
			requestHead.append("Content-Length: ").append(bodyBytes.length)
					.append("\r\nConnection: close\r\n\r\n");
			socket.getOutputStream().write(
					requestHead.toString().getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().write(bodyBytes);
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

	private static void assertGoldenHead(WireResponse response, String fixture)
			throws Exception {
		Assertions.assertEquals(readGolden(fixture), response.canonicalHead(),
				response.rawHead());
	}

	private static String readGolden(String fixture) throws Exception {
		String text = Files.readString(GOLDEN_ROOT.resolve(fixture),
				StandardCharsets.UTF_8);
		Assertions.assertFalse(text.contains("\r"), fixture + " must use LF");
		Assertions.assertTrue(text.endsWith("\n"),
				fixture + " must end after the final header line");
		Assertions.assertFalse(text.endsWith("\n\n"),
				fixture + " stores the head without its empty-line delimiter");
		return text;
	}

	private static void assertGoldenManifest() throws Exception {
		Path manifest = GOLDEN_ROOT.resolve("manifest.sha256");
		List<String> rows = Files.readAllLines(manifest, StandardCharsets.US_ASCII);
		Assertions.assertEquals(3, rows.size());
		List<String> manifested = new ArrayList<>();
		for (String row : rows) {
			String[] fields = row.split("  ", -1);
			Assertions.assertEquals(2, fields.length, row);
			Assertions.assertTrue(fields[0].matches("[0-9a-f]{64}"), row);
			Path fixture = GOLDEN_ROOT.resolve(fields[1]);
			Assertions.assertTrue(Files.isRegularFile(
					fixture, LinkOption.NOFOLLOW_LINKS), fields[1]);
			Assertions.assertEquals(fields[0], sha256(Files.readAllBytes(fixture)),
					fields[1]);
			manifested.add(fields[1]);
		}
		Assertions.assertEquals(manifested.stream().sorted().toList(), manifested,
				"Golden head manifest must be path-sorted.");
		try (Stream<Path> paths = Files.list(GOLDEN_ROOT)) {
			List<String> actual = paths
					.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.map(path -> path.getFileName().toString())
					.filter(name -> !name.equals("manifest.sha256"))
					.sorted()
					.toList();
			Assertions.assertEquals(manifested, actual);
		}
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static void assertNoSessionHeaders(WireResponse response) {
		Assertions.assertFalse(response.hasHeader("MCP-Session-Id"),
				response.rawHead());
		Assertions.assertFalse(response.hasHeader("Last-Event-ID"),
				response.rawHead());
	}

	private static void assertIdleStartedDiagnostics(McpServer server) {
		McpServerDiagnostics diagnostics = awaitIdleDiagnostics(server);
		Assertions.assertEquals(McpServerStatus.STARTED, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isPresent());
		assertZeroLoad(diagnostics);
	}

	private static McpServerDiagnostics awaitIdleDiagnostics(McpServer server) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpServerDiagnostics latest = server.getDiagnostics();
		while (System.nanoTime() - deadline < 0L) {
			latest = server.getDiagnostics();
			if (isZeroLoad(latest))
				return latest;
			Thread.onSpinWait();
		}
		Assertions.fail("Timed out waiting for idle MCP diagnostics; latest=" + latest);
		throw new AssertionError();
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static void stopAndAssertClean(Soklet soklet, McpServer server) {
		soklet.stop();
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.STOPPED, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isPresent());
		assertZeroLoad(diagnostics);
	}

	private static void assertZeroLoad(McpServerDiagnostics diagnostics) {
		Assertions.assertEquals(0, diagnostics.getActiveHandlerExecutions());
		Assertions.assertEquals(0, diagnostics.getQueuedRequests());
		Assertions.assertEquals(0, diagnostics.getActiveRequestStreams());
		Assertions.assertEquals(0, diagnostics.getActiveSubscriptions());
	}

	private static boolean isZeroLoad(McpServerDiagnostics diagnostics) {
		return diagnostics.getActiveHandlerExecutions() == 0
				&& diagnostics.getQueuedRequests() == 0
				&& diagnostics.getActiveRequestStreams() == 0
				&& diagnostics.getActiveSubscriptions() == 0;
	}

	private record HeaderLine(String name, String value) {
	}

	private record ScopedArguments(@McpHeader("Tenant") String tenant) {
	}

	private static final class FixtureState {
		private final List<McpAdmissionContext> admissions =
				new CopyOnWriteArrayList<>();
		private final AtomicInteger requestLimiterInvocations = new AtomicInteger();
		private final AtomicInteger toolLimiterInvocations = new AtomicInteger();
		private final AtomicInteger interceptorInvocations = new AtomicInteger();
		private final AtomicInteger handlerInvocations = new AtomicInteger();

		private void assertNoDownstreamInvocations() {
			Assertions.assertEquals(0, this.requestLimiterInvocations.get());
			Assertions.assertEquals(0, this.toolLimiterInvocations.get());
			Assertions.assertEquals(0, this.interceptorInvocations.get());
			Assertions.assertEquals(0, this.handlerInvocations.get());
		}
	}

	private record WireResponse(String rawHead, int status,
			Map<String, List<String>> headers, byte[] body) {
		private static WireResponse parse(byte[] bytes) {
			byte[] delimiter = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
			int boundary = indexOf(bytes, delimiter);
			if (boundary < 0)
				throw new AssertionError("Response did not contain a complete HTTP head.");

			String rawHead = new String(bytes, 0, boundary + delimiter.length,
					StandardCharsets.ISO_8859_1);
			String[] lines = rawHead.substring(0, rawHead.length() - delimiter.length)
					.split("\r\n");
			String[] statusParts = lines[0].split(" ", 3);
			Map<String, List<String>> headers = new LinkedHashMap<>();
			for (int index = 1; index < lines.length; index++) {
				int colon = lines[index].indexOf(':');
				if (colon < 1)
					throw new AssertionError("Malformed response header: " + lines[index]);
				String name = lines[index].substring(0, colon)
						.toLowerCase(Locale.ROOT);
				String value = lines[index].substring(colon + 1).trim();
				headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
			}
			Map<String, List<String>> copied = new LinkedHashMap<>();
			headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
			return new WireResponse(rawHead, Integer.parseInt(statusParts[1]),
					Map.copyOf(copied), Arrays.copyOfRange(bytes,
							boundary + delimiter.length, bytes.length));
		}

		private String canonicalHead() {
			Assertions.assertTrue(this.rawHead.endsWith("\r\n\r\n"), this.rawHead);
			String withoutCrlf = this.rawHead.replace("\r\n", "");
			Assertions.assertFalse(withoutCrlf.contains("\r")
					|| withoutCrlf.contains("\n"), this.rawHead);
			return this.rawHead.substring(0, this.rawHead.length() - 2)
					.replace("\r\n", "\n");
		}

		private String bodyText() {
			return new String(this.body, StandardCharsets.UTF_8);
		}

		private String singleHeader(String name) {
			List<String> values = this.headers.get(name.toLowerCase(Locale.ROOT));
			if (values == null || values.size() != 1)
				throw new AssertionError("Expected exactly one " + name
						+ " header, found " + values + "; response=" + this.rawHead);
			return values.get(0);
		}

		private boolean hasHeader(String name) {
			return this.headers.containsKey(name.toLowerCase(Locale.ROOT));
		}

		private static int indexOf(byte[] bytes, byte[] target) {
			outer:
			for (int offset = 0; offset <= bytes.length - target.length; offset++) {
				for (int index = 0; index < target.length; index++) {
					if (bytes[offset + index] != target[index])
						continue outer;
				}
				return offset;
			}
			return -1;
		}
	}
}
