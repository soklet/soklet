package com.soklet;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SokletListenerAuthorityTests {
	private static final int PORT = 32123;

	@Test
	void wildcardAndSpecificListenersCannotSplitTrafficOnTheSamePort() {
		assertOverlap("HTTP", "MCP", () -> config(HttpServer.withPort(PORT).build(), null, mcp(PORT, "127.0.0.1")));
		assertOverlap("SSE", "MCP", () -> config(null, SseServer.withPort(PORT).build(), mcp(PORT, "127.0.0.1")));
		assertOverlap("HTTP", "SSE", () -> config(HttpServer.withPort(PORT).build(), SseServer.withPort(PORT).host("127.0.0.1").build(), null));
		assertOverlap("HTTP", "MCP", () -> config(HttpServer.withPort(PORT).host("::").build(), null, mcp(PORT, "127.0.0.1")));
		assertOverlap("HTTP", "MCP", () -> config(HttpServer.withPort(PORT).host("127.0.0.1").build(), null, mcp(PORT, "0.0.0.0")));
	}

	@Test
	void canonicalAddressAliasesAreOverlappingAuthorities() {
		assertOverlap("HTTP", "MCP", () -> config(HttpServer.withPort(PORT).host("::1").build(), null, mcp(PORT, "0:0:0:0:0:0:0:1")));
		assertOverlap("HTTP", "MCP", () -> config(HttpServer.withPort(PORT).host("127.0.0.1").build(), null, mcp(PORT, "::ffff:127.0.0.1")));
		assertOverlap("HTTP", "MCP", () -> config(HttpServer.withPort(PORT).host("[::1]").build(), null, mcp(PORT, "::1")));
	}

	@Test
	void ephemeralPortsAndDemonstrablyDistinctSpecificAddressesAreAllowed() {
		assertDoesNotThrow(() -> config(HttpServer.withPort(0).build(), SseServer.withPort(0).build(), mcp(0, "127.0.0.1")));
		assertDoesNotThrow(() -> config(HttpServer.withPort(PORT).build(), SseServer.withPort(PORT + 1).build(), mcp(PORT + 2, "127.0.0.1")));
		assertDoesNotThrow(() -> config(HttpServer.withPort(PORT).host("127.0.0.1").build(), SseServer.withPort(PORT).host("127.0.0.2").build(), mcp(PORT, "127.0.0.3")));
		assertDoesNotThrow(() -> config(HttpServer.withPort(PORT).host("[2001:db8::1]").build(), null, mcp(PORT, "2001:db8::2")));
		assertDoesNotThrow(() -> config(HttpServer.withPort(PORT).host("127.0.0.1").build(), null, mcp(PORT, "::1")));
	}

	@Test
	void unresolvedHostnamesAreRejectedConservativelyWithoutResolution() {
		assertOverlap("HTTP", "MCP", () -> config(HttpServer.withPort(PORT).host("one.invalid").build(), null, mcp(PORT, "two.invalid")));
		assertOverlap("HTTP", "MCP", () -> config(HttpServer.withPort(PORT).host("localhost").build(), null, mcp(PORT, "127.0.0.1")));
		assertOverlap("HTTP", "MCP", () -> config(HttpServer.withPort(PORT).host("fe80::1%1").build(), null, mcp(PORT, "fe80::2%2")));
	}

	@Test
	void aSharedHttpAndSseObjectRetainsTheExistingIdentityDiagnostic() {
		class SharedServer implements HttpServer, SseServer {
			private final TransportIdentity identity = TransportIdentity.create();
			@Override public TransportIdentity getTransportIdentity() { return this.identity; }
			@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) { throw new AssertionError("Must reject before attachment"); }
			@Override public TransportRuntime attach(SseTransportAttachmentContext context, StartupContext startup) { throw new AssertionError("Must reject before attachment"); }
			@Override public Optional<SseBroadcaster> acquireBroadcaster(ResourcePath path) { return Optional.empty(); }
		}
		SharedServer server = new SharedServer();
		SokletConfig config = config(server, server, mcp(PORT, "127.0.0.1"));
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Soklet.fromConfig(config));
		assertEquals("HTTP and SSE transport slots require distinct transport identities; one transport identity was configured more than once", failure.getMessage());
	}

	private static void assertOverlap(String first, String second, Runnable build) {
		IllegalStateException failure = assertThrows(IllegalStateException.class, build::run);
		assertTrue(failure.getMessage().contains(first + " listener"), failure.getMessage());
		assertTrue(failure.getMessage().contains(second + " listener"), failure.getMessage());
		assertTrue(failure.getMessage().contains(Integer.toString(PORT)), failure.getMessage());
		assertTrue(failure.getMessage().contains("distinct specific IP address literals"), failure.getMessage());
	}

	private static SokletConfig config(HttpServer http, SseServer sse, McpServer mcp) {
		SokletConfig.Builder builder = http != null ? SokletConfig.withHttpServer(http)
				: sse != null ? SokletConfig.withSseServer(sse) : SokletConfig.withMcpServer(mcp);
		return builder.httpServer(http).sseServer(sse).mcpServer(mcp)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build();
	}

	private static McpServer mcp(int port, String host) {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("listener-authority", "1").build(), Set.of(McpProtocolVersion.V2026_07_28)).build();
		return McpServer.withPort(port).host(host).allowedHosts(Set.of("listener-authority.example"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).build();
	}
}
