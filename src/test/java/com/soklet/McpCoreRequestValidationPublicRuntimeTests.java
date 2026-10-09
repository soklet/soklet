package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpCoreRequestValidationPublicRuntimeTests {
	@Test
	void invalidDecodedQueryAndDuplicateCharsetRejectBeforeAdmissionForCurrentAndLegacyProtocols() throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		AtomicInteger starts = new AtomicInteger();
		List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		List<LogEvent> logs = new CopyOnWriteArrayList<>();
		CountDownLatch rejected = new CountDownLatch(6);
		Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2026_07_28, McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
		McpToolRegistration<?> tool = McpToolRegistration.withName("probe", versions).jsonObjectArguments()
				.handler((context, arguments, features) -> { handlers.incrementAndGet(); return McpCompleteResult.fromToolText("ok"); }).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("core-validation", "1").build(), versions)
				.toolRegistrations(List.of(tool)).build();
		McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.admissionController(context -> { admissions.incrementAndGet(); return McpAdmissionDecision.accepted(); }).build();
		MetricsCollector collector = new MetricsCollector() {
			@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				events.add(event); if (event instanceof McpMetricsEvent.RequestRejected) rejected.countDown();
			}
		};
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
			@Override public void didStartMcpRequestHandling(McpRequestContext context) { starts.incrementAndGet(); }
		};
		SokletConfig config = SokletConfig.withMcpServer(server).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector).lifecycleObserver(observer).build();
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start(); int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (McpProtocolVersion version : List.of(McpProtocolVersion.V2026_07_28, McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25)) {
				String revision = version.getWireValue();
				String body = body(revision);
				assertTrue(send(port, "/mcp?value=%C3%28", "application/json", revision, body).startsWith("HTTP/1.1 400"));
				assertTrue(send(port, "/mcp", "application/json; charset=UTF-8; charset=UTF-8", revision, body).startsWith("HTTP/1.1 400"));
			}
			assertTrue(rejected.await(5, TimeUnit.SECONDS));
			assertEquals(0, admissions.get()); assertEquals(0, handlers.get()); assertEquals(0, starts.get());
			assertEquals(6L, events.stream().filter(McpMetricsEvent.RequestRejected.class::isInstance).count());
			assertFalse(events.stream().filter(McpMetricsEvent.RequestFinished.class::isInstance).map(McpMetricsEvent.RequestFinished.class::cast)
					.anyMatch(event -> event.getOutcome() == McpRequestOutcome.INTERNAL_ERROR));
			assertFalse(logs.stream().anyMatch(event -> event.getLogEventType() == LogEventType.SERVER_INTERNAL_ERROR), logs.toString());
			// A valid dispatch proves the same listener, revision selection and application admission work.
			String control = send(port, "/mcp?value=%C3%A9", "application/json", "2026-07-28", body("2026-07-28"));
			assertTrue(control.startsWith("HTTP/1.1 200"), control); assertTrue(control.contains("\"text\":\"ok\""), control);
			assertEquals(1, admissions.get()); assertEquals(1, handlers.get());
		}
	}

	private static String body(String revision) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
				+ (revision.equals("2026-07-28") ? "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}," : "")
				+ "\"name\":\"probe\",\"arguments\":{}}}";
	}
	private static String send(int port, String path, String contentType, String revision, String body) throws Exception {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(5000);
			String headers = "POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: " + contentType
					+ "\r\nAccept: application/json, text/event-stream\r\nMCP-Protocol-Version: " + revision
					+ "\r\nMcp-Method: tools/call\r\nMcp-Name: probe\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
					+ "\r\nConnection: close\r\n\r\n";
			socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
			return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
