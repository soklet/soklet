/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import com.soklet.annotation.Multipart;
import com.soklet.annotation.POST;
import com.soklet.exception.IllegalRequestBodyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

// Longest matrix: 13 exchanges x (1s connect + 2s read) + 10s lifecycle bounds = 49s.
@Timeout(60)
public class ReleaseInputCloseoutTests {
	private static final String ORIGIN = "https://good.example";
	private static final byte[] RPC = ("{\"jsonrpc\":\"2.0\",\"id\":\"input\",\"method\":\"tools/call\","
			+ "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
			+ "\"io.modelcontextprotocol/clientCapabilities\":{}},\"name\":\"echo\",\"arguments\":{}}}").getBytes(StandardCharsets.UTF_8);

	@Test
	public void malformedInitialMultipartDelimitersAreRejected() {
		for (String body : List.of("no delimiter", "--other--\r\n", "--boundary", "--boundaryx\r\n",
				"--boundary \tX\r\n", "preamble\r\n--boundary\r", "--boundary-",
				"--boundary--garbage", "--boundary-- \tX", "--boundary--\r")) {
			IllegalRequestBodyException failure = assertThrows(IllegalRequestBodyException.class,
					() -> multipart(body).getMultipartFields(), body);
			assertEquals("Multipart request body is malformed.", failure.getMessage());
			assertNull(failure.getCause());
		}
	}

	@Test
	public void malformedMultipartCannotInvokeAnOptionalBindingHandler() {
		SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(OptionalUpload.class)))
				.build(), simulator -> {
			HttpRequestResult response = simulator.performHttpRequest(multipart("secret-body-with-no-delimiter"));
			assertEquals(400, response.getMarshaledResponse().getStatusCode());
			assertEquals("HTTP 400: Bad Request", new String(response.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
		});
	}

	public static class OptionalUpload {
		@POST("/upload")
		public String upload(@Multipart(name = "field", optional = true) Optional<String> value) {
			return "handler-invoked";
		}
	}

	@Test
	public void multipartDelimiterPaddingAndPreamblePreserveParts() {
		for (String padding : List.of("", " ", "\t", " \t ")) {
			String body = "preamble\r\n--boundary" + padding + "\r\n"
					+ "Content-Disposition: form-data; name=\"field\"\r\n\r\none\r\n"
					+ "--boundary" + padding + "\r\n"
					+ "Content-Disposition: form-data; name=\"field\"\r\n\r\ntwo\r\n"
					+ "--boundary--" + padding + "\r\nepilogue";
			List<MultipartField> fields = multipart(body).getMultipartFields().get("field");
			assertNotNull(fields, "Padding must not silently discard valid multipart fields");
			assertEquals(List.of("one", "two"), fields.stream().map(field -> field.getDataAsString().orElseThrow()).toList());
		}
	}

	@Test
	public void explicitEmptyMultipartAndAbsentBodyRemainEmpty() {
		for (String closing : List.of("--boundary--\r\n", "--boundary--", "--boundary-- \t"))
			assertTrue(multipart(closing).getMultipartFields().isEmpty());
		assertTrue(Request.withPath(HttpMethod.POST, "/upload").build().getMultipartFields().isEmpty());
	}

	@Test
	public void filenamePreservesNamedEntitiesAndRetainsNumericCompatibility() {
		String filename = "Tom&amp;Jerry &lt;1&gt; &GT; &#8239;&#x1F36A;.txt";
		String body = "--boundary\r\nContent-Disposition: form-data; name=\"field\"; filename=\""
				+ filename + "\"\r\n\r\nvalue\r\n--boundary--\r\n";
		assertEquals("Tom&amp;Jerry &lt;1&gt; &GT; \u202f🍪.txt",
				multipart(body).getMultipartFields().get("field").getFirst().getFilename().orElseThrow());
	}

	@Test
	public void protectedHtmlUtilitiesRetainsHistoricalSubclassCompatibility() {
		assertNotNull(new LegacyHtmlUtilities());
		assertEquals("Tom&Jerry <1> \u202f🍪.txt", LegacyHtmlUtilities.unescapeHtml(
				"Tom&amp;Jerry &lt;1&GT; &#8239;&#x1F36A;.txt"));
		assertEquals("&unknown; &amp", LegacyHtmlUtilities.unescapeHtml("&unknown; &amp"));
		assertThrows(IllegalRequestBodyException.class, () -> LegacyHtmlUtilities.unescapeHtml("&#0;"));
	}

	private static final class LegacyHtmlUtilities extends DefaultMultipartParser.HTMLUtilities {}

	@Test
	public void unsupportedMcpEncodingsRejectBeforeAdmissionAndDispatch() throws Exception {
		AtomicInteger admissions = new AtomicInteger(), handlers = new AtomicInteger();
		McpServer server = server(admissions, handlers, false);
		try (Soklet owner = owner(server)) {
			owner.start();
			for (String encodingHeaders : List.of("Content-Encoding: gzip\r\n", "Content-Encoding: br\r\n",
					"Content-Encoding: gzip, identity\r\n", "Content-Encoding: identity, GZIP\r\n",
					"Content-Encoding: identity\r\nContent-Encoding: deflate\r\n",
					"cOnTeNt-EnCoDiNg: \tBr \t\r\n")) {
				for (String version : List.of("2026-07-28", "2025-11-25")) {
					WireResponse response = send(server, "/mcp", "127.0.0.1", version,
							encodingHeaders + "Origin: " + ORIGIN + "\r\n", RPC);
					assertEquals(415, response.status(), encodingHeaders);
					assertEquals("identity", response.header("Accept-Encoding"));
					assertEquals(ORIGIN, response.header("Access-Control-Allow-Origin"));
				}
			}
			ByteArrayOutputStream compressed = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(RPC); }
			assertEquals(415, send(server, "/mcp", "127.0.0.1", "2026-07-28",
					"Content-Encoding: gzip\r\n", compressed.toByteArray()).status());
			assertEquals(0, admissions.get());
			assertEquals(0, handlers.get());
		}
	}

	@Test
	public void identityMcpEncodingsStillReachTheHandler() throws Exception {
		AtomicInteger admissions = new AtomicInteger(), handlers = new AtomicInteger();
		McpServer server = server(admissions, handlers, false);
		try (Soklet owner = owner(server)) {
			owner.start();
			for (String encodingHeaders : List.of("", "Content-Encoding: identity\r\n",
					"Content-Encoding: \tIDENTITY \t\r\n",
					"Content-Encoding: identity\r\nContent-Encoding: identity\r\n",
					"Content-Encoding: , identity, ,\r\n")) {
				WireResponse response = send(server, "/mcp", "127.0.0.1", "2026-07-28", encodingHeaders, RPC);
				assertEquals(200, response.status(), encodingHeaders + response.body());
			}
			assertEquals(5, admissions.get());
			assertEquals(5, handlers.get());
		}
	}

	@Test
	public void mcpHostOriginAndEndpointGuardsPrecedeContentEncoding() throws Exception {
		AtomicInteger admissions = new AtomicInteger(), handlers = new AtomicInteger();
		McpServer server = server(admissions, handlers, false);
		try (Soklet owner = owner(server)) {
			owner.start();
			String encoding = "Content-Encoding: gzip\r\n";
			assertEquals(404, send(server, "/unknown", "127.0.0.1", "2026-07-28", encoding, RPC).status());
			assertEquals(421, send(server, "/mcp", "bad.example", "2026-07-28", encoding, RPC).status());
			WireResponse denied = send(server, "/mcp", "127.0.0.1", "2026-07-28",
					encoding + "Origin: https://bad.example\r\n", RPC);
			assertEquals(403, denied.status());
			assertNull(denied.header("Access-Control-Allow-Origin"));
			assertNull(denied.header("Access-Control-Expose-Headers"));
			assertEquals(0, admissions.get());
			assertEquals(0, handlers.get());
		}
	}

	@Test
	public void approvedOriginCanReadRateLimitRetryAfter() throws Exception {
		AtomicInteger admissions = new AtomicInteger(), handlers = new AtomicInteger();
		McpServer server = server(admissions, handlers, true);
		try (Soklet owner = owner(server)) {
			owner.start();
			WireResponse response = send(server, "/mcp", "127.0.0.1", "2026-07-28", "Origin: " + ORIGIN + "\r\n", RPC);
			assertEquals(429, response.status());
			assertEquals("2", response.header("Retry-After"));
			assertEquals(ORIGIN, response.header("Access-Control-Allow-Origin"));
			assertTrue(Arrays.stream(response.header("Access-Control-Expose-Headers").split(","))
					.anyMatch(value -> value.trim().equalsIgnoreCase("Retry-After")));
			WireResponse noOrigin = send(server, "/mcp", "127.0.0.1", "2026-07-28", "", RPC);
			assertEquals(429, noOrigin.status());
			assertNull(noOrigin.header("Access-Control-Expose-Headers"));
			assertEquals(0, handlers.get());
		}
	}

	private static Request multipart(String body) {
		return Request.withPath(HttpMethod.POST, "/upload")
				.headers(Map.of("Content-Type", List.of("multipart/form-data; boundary=boundary")))
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static McpServer server(AtomicInteger admissions, AtomicInteger handlers, boolean deny) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("echo", Set.of(McpProtocolVersion.V2026_07_28))
				.jsonObjectArguments().handler((request, arguments, features) -> {
					handlers.incrementAndGet();
					return McpCompleteResult.fromToolText("ok");
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("input-closeout", "test").build(),
				Set.of(McpProtocolVersion.V2026_07_28)).toolRegistrations(List.of(tool)).build();
		return McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.corsAuthorizer(CorsAuthorizer.fromWhitelistedOrigins(Set.of(ORIGIN)))
				.admissionController(context -> { admissions.incrementAndGet(); return McpAdmissionDecision.accepted(); })
				.requestRateLimiter(context -> deny ? McpRateLimitDecision.denied(Duration.ofSeconds(2)) : McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed()).build();
	}

	private static Soklet owner(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2)).gracefulShutdownTimeout(Duration.ofSeconds(2))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
	}

	private static WireResponse send(McpServer server, String path, String host, String version, String headers, byte[] body) throws Exception {
		int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
			socket.setSoTimeout(2_000);
			String head = "POST " + path + " HTTP/1.1\r\nHost: " + host + ":" + port
					+ "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
					+ "MCP-Protocol-Version: " + version + "\r\nMcp-Method: tools/call\r\nMcp-Name: echo\r\n"
					+ headers + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
			socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().write(body);
			String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int end = response.indexOf("\r\n\r\n");
			assertTrue(end >= 0, response);
			return new WireResponse(Integer.parseInt(response.split(" ", 3)[1]), response.substring(0, end), response.substring(end + 4));
		}
	}

	private record WireResponse(int status, String head, String body) {
		String header(String name) {
			return head.lines().filter(line -> line.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + ":"))
					.map(line -> line.substring(line.indexOf(':') + 1).trim()).findFirst().orElse(null);
		}
	}
}
