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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Black-box real-listener coverage for the MCP rate-limit identity boundary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpRateLimitIdentityPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TOOL_NAME = "rate-limit.identity";

	@Test
	public void clientReportedIdentityAndForwardedIpCannotReplaceAdmittedPartition()
			throws Exception {
		Object principal = new Object();
		Object applicationContext = new Object();
		McpAdmissionIdentity admittedIdentity = McpAdmissionIdentity
				.withRateLimitPartitionKey("admitted-rate-partition")
				.authorizationPartitionKey("admitted-authorization-partition")
				.principal(principal)
				.applicationContext(applicationContext)
				.build();
		List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		List<McpRateLimitContext> requestLimits = new CopyOnWriteArrayList<>();
		List<McpRateLimitContext> toolLimits = new CopyOnWriteArrayList<>();
		McpRateLimiter limiter = recordingLimiter(capacityOneLimiter(),
				requestLimits, toolLimits);
		McpServer server = server(context -> {
			admissions.add(context);
			return McpAdmissionDecision.accepted(admittedIdentity);
		}, limiter);
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> first = call(port, "first", "forged-a",
					"198.51.100.10");
			HttpResponse<String> second = call(port, "second", "forged-b",
					"203.0.113.20");

			assertSuccess(first, "first");
			assertRateLimited(second, "second");
			Assertions.assertEquals(2, admissions.size());
			Assertions.assertEquals("forged-a", admissions.get(0).getClientInfo()
					.orElseThrow().getName());
			Assertions.assertEquals("forged-b", admissions.get(1).getClientInfo()
					.orElseThrow().getName());
			Assertions.assertEquals("198.51.100.10", admissions.get(0)
					.getRequest().getHeader("X-Forwarded-For").orElseThrow());
			Assertions.assertEquals("203.0.113.20", admissions.get(1)
					.getRequest().getHeader("X-Forwarded-For").orElseThrow());
			admissions.forEach(admission -> {
				Request request = admission.getRequest();
				assertIpv4LoopbackPeer(request);
				Assertions.assertEquals(MCP_PATH, request.getRawPath());
				Assertions.assertEquals("source=identity%2Fboundary&empty=",
						request.getRawQuery().orElseThrow());
				Assertions.assertEquals(MCP_PATH
						+ "?source=identity%2Fboundary&empty=",
						request.getRawPathAndQuery());
			});

			Assertions.assertEquals(2, requestLimits.size());
			Assertions.assertEquals(1, toolLimits.size(),
					"Request denial must prevent the second tool acquisition.");
			assertSameRequestAndIdentity(admissions.get(0), requestLimits.get(0),
					admittedIdentity, McpRateLimitTarget.REQUEST);
			assertSameRequestAndIdentity(admissions.get(0), toolLimits.get(0),
					admittedIdentity, McpRateLimitTarget.TOOL);
			assertSameRequestAndIdentity(admissions.get(1), requestLimits.get(1),
					admittedIdentity, McpRateLimitTarget.REQUEST);
		} finally {
			owner.close();
		}
	}

	@Test
	public void allowlistedSocketPeerCanSelectForwardedIpPartitions()
			throws Exception {
		InetAddress trustedLoopback = InetAddress.getByName(LOOPBACK);
		InetAddress trustedForwarderOne = InetAddress.getByName("192.0.2.210");
		InetAddress trustedForwarderTwo = InetAddress.getByName("192.0.2.211");
		List<Request> untrustedRequests = new CopyOnWriteArrayList<>();
		List<String> untrustedPartitions = new CopyOnWriteArrayList<>();
		List<List<String>> untrustedForwardedHeaders =
				new CopyOnWriteArrayList<>();
		McpServer untrustedServer = server(ipPartitionAdmission(
				Set.of(InetAddress.getByName("192.0.2.200")), untrustedRequests,
				untrustedPartitions, untrustedForwardedHeaders), capacityOneLimiter());
		Soklet untrustedOwner = managedSoklet(untrustedServer);
		try {
			untrustedOwner.start();
			int port = untrustedServer.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> firstUntrusted = call(port, "first-untrusted",
					"untrusted-a", "198.51.100.50");
			HttpResponse<String> secondUntrusted = call(port, "second-untrusted",
					"untrusted-b", "203.0.113.60");

			assertSuccess(firstUntrusted, "first-untrusted");
			assertRateLimited(secondUntrusted, "second-untrusted");
			Assertions.assertEquals(List.of(
					"ip:127.0.0.1",
					"ip:127.0.0.1"), untrustedPartitions,
					"A non-allowlisted peer must not delegate partitions to X-Forwarded-For.");
			Assertions.assertEquals(List.of(
					List.of("198.51.100.50"),
					List.of("203.0.113.60")), untrustedForwardedHeaders);
			untrustedRequests.forEach(
					McpRateLimitIdentityPublicRuntimeTests::assertIpv4LoopbackPeer);
		} finally {
			untrustedOwner.close();
		}

		List<Request> trustedRequests = new CopyOnWriteArrayList<>();
		List<String> admittedPartitions = new CopyOnWriteArrayList<>();
		List<List<String>> trustedForwardedHeaders =
				new CopyOnWriteArrayList<>();
		McpRateLimiter limiter = capacityOneLimiter();
		McpServer server = server(ipPartitionAdmission(Set.of(trustedLoopback,
				trustedForwarderOne, trustedForwarderTwo),
				trustedRequests, admittedPartitions, trustedForwardedHeaders), limiter);
		Soklet owner = managedSoklet(server);
		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> firstAddress = call(port, "first-address",
					"client-a", "198.51.100.30");
			HttpResponse<String> repeatedAddress = call(port, "repeated-address",
					"client-b", "198.51.100.30");
			HttpResponse<String> secondAddress = call(port, "second-address",
					"client-c", "203.0.113.40");

			assertSuccess(firstAddress, "first-address");
			assertRateLimited(repeatedAddress, "repeated-address");
			assertSuccess(secondAddress, "second-address");

			List<String> orderedForwardedChain = List.of(
					"198.51.100.1",
					"203.0.113.2",
					"198.51.100.3",
					"203.0.113.4",
					"198.51.100.5",
					"203.0.113.6",
					"198.51.100.70",
					"192.0.2.210",
					"192.0.2.211");
			String orderedFirst = rawCall(port, "ordered-first", "raw-a",
					orderedForwardedChain);
			String orderedRepeated = rawCall(port, "ordered-repeated", "raw-b",
					orderedForwardedChain);
			assertRawResponse(orderedFirst, 200, "ordered-first");
			assertRawResponse(orderedRepeated, 429, "ordered-repeated");

			Assertions.assertEquals(List.of(
					"ip:198.51.100.30",
					"ip:198.51.100.30",
					"ip:203.0.113.40",
					"ip:198.51.100.70",
					"ip:198.51.100.70"), admittedPartitions);
			Assertions.assertEquals(orderedForwardedChain,
					trustedForwardedHeaders.get(3));
			Assertions.assertEquals(orderedForwardedChain,
					trustedForwardedHeaders.get(4));
			trustedRequests.forEach(
					McpRateLimitIdentityPublicRuntimeTests::assertIpv4LoopbackPeer);
		} finally {
			owner.close();
		}
	}

	private static McpRateLimiter recordingLimiter(McpRateLimiter delegate,
			List<McpRateLimitContext> requestLimits,
			List<McpRateLimitContext> toolLimits) {
		return context -> {
			if (context.getTarget() == McpRateLimitTarget.REQUEST)
				requestLimits.add(context);
			else
				toolLimits.add(context);
			return delegate.acquire(context);
		};
	}

	private static McpRateLimiter capacityOneLimiter() {
		return McpRateLimiter.fromInMemoryTokenBucket(McpTokenBucketConfig
				.withCapacity(1L)
				.refillTokens(1L)
				.refillPeriod(Duration.ofDays(1))
				.build());
	}

	private static McpAdmissionController ipPartitionAdmission(
			Set<InetAddress> trustedProxyAddresses, List<Request> requests,
			List<String> admittedPartitions,
			List<List<String>> forwardedHeaderOrders) {
		return context -> {
			requests.add(context.getRequest());
			Set<String> forwardedHeaders = context.getRequest().getHeaders()
					.get("X-Forwarded-For");
			forwardedHeaderOrders.add(forwardedHeaders == null
					? List.of() : List.copyOf(forwardedHeaders));
			InetAddress effectiveAddress = EffectiveClientIpResolver
					.withRequest(context.getRequest(),
							EffectiveOriginResolver.TrustPolicy.TRUST_PROXY_ALLOWLIST)
					.trustedProxyAddresses(trustedProxyAddresses)
					.resolve()
					.orElseThrow();
			String partition = "ip:" + effectiveAddress.getHostAddress();
			admittedPartitions.add(partition);
			return McpAdmissionDecision.accepted(McpAdmissionIdentity
					.withRateLimitPartitionKey(partition)
					.build());
		};
	}

	private static McpServer server(McpAdmissionController admissionController,
			McpRateLimiter limiter) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("allowed"))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"rate-limit-identity-public-runtime-test",
						"4.0.0").build())
				.tool(tool)
				.build();
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(admissionController)
				.requestRateLimiter(limiter)
				.toolRateLimiter(limiter)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownDuration(Duration.ofSeconds(2))
						.forcedShutdownDuration(Duration.ofSeconds(1))
						.build())
				.build());
	}

	private static HttpResponse<String> call(int port, String id,
			String clientName, String forwardedFor) throws Exception {
		String body = toolCallBody(id, clientName);
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH
						+ "?source=identity%2Fboundary&empty="))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", TOOL_NAME)
				.header("X-Forwarded-For", forwardedFor)
				.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	private static String rawCall(int port, String id, String clientName,
			List<String> forwardedForHeaders) throws Exception {
		byte[] body = toolCallBody(id, clientName)
				.getBytes(StandardCharsets.UTF_8);
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(LOOPBACK, port), 3_000);
			socket.setSoTimeout(5_000);
			StringBuilder head = new StringBuilder()
					.append("POST ").append(MCP_PATH).append(" HTTP/1.1\r\n")
					.append("Host: ").append(LOOPBACK).append(':').append(port)
					.append("\r\n")
					.append("Content-Type: ").append(JSON_MEDIA_TYPE)
					.append("; charset=UTF-8\r\n")
					.append("Accept: ").append(JSON_MEDIA_TYPE)
					.append(", text/event-stream\r\n")
					.append("MCP-Protocol-Version: ").append(PROTOCOL_VERSION)
					.append("\r\n")
					.append("Mcp-Method: tools/call\r\n")
					.append("Mcp-Name: ").append(TOOL_NAME).append("\r\n");
			for (String forwardedFor : forwardedForHeaders)
				head.append("X-Forwarded-For: ").append(forwardedFor)
						.append("\r\n");
			head.append("Content-Length: ").append(body.length).append("\r\n")
					.append("Connection: close\r\n\r\n");
			socket.getOutputStream().write(head.toString()
					.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().write(body);
			socket.getOutputStream().flush();
			return new String(socket.getInputStream().readAllBytes(),
					StandardCharsets.ISO_8859_1);
		}
	}

	private static String toolCallBody(String id, String clientName) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{},"
				+ "\"io.modelcontextprotocol/clientInfo\":{\"name\":\""
				+ clientName + "\",\"version\":\"1\","
				+ "\"rateLimitPartitionKey\":\"" + clientName + "\"}},"
				+ "\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}}}";
	}

	private static void assertSameRequestAndIdentity(
			McpAdmissionContext admission, McpRateLimitContext rateLimit,
			McpAdmissionIdentity expectedIdentity,
			McpRateLimitTarget expectedTarget) {
		Assertions.assertSame(admission.getRequest(), rateLimit.getRequest(),
				"Admission and rate limiting must observe the same HTTP request.");
		Assertions.assertEquals(expectedTarget, rateLimit.getTarget());
		Assertions.assertEquals("tools/call", rateLimit.getJsonRpcMethod());
		Assertions.assertEquals(TOOL_NAME,
				rateLimit.getOperationName().orElseThrow());
		McpAdmissionIdentity actualIdentity = rateLimit.getAdmissionIdentity();
		Assertions.assertEquals(expectedIdentity.getRateLimitPartitionKey(),
				actualIdentity.getRateLimitPartitionKey());
		Assertions.assertEquals(expectedIdentity.getAuthorizationPartitionKey(),
				actualIdentity.getAuthorizationPartitionKey());
		Assertions.assertSame(expectedIdentity.getPrincipal().orElseThrow(),
				actualIdentity.getPrincipal().orElseThrow());
		Assertions.assertSame(expectedIdentity.getApplicationContext().orElseThrow(),
				actualIdentity.getApplicationContext().orElseThrow());
	}

	private static void assertIpv4LoopbackPeer(Request request) {
		InetAddress peer = request.getRemoteAddress().orElseThrow().getAddress();
		Assertions.assertInstanceOf(Inet4Address.class, peer);
		Assertions.assertTrue(peer.isLoopbackAddress());
		Assertions.assertEquals(LOOPBACK, peer.getHostAddress());
	}

	private static void assertSuccess(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + expectedId + "\""), response.body());
	}

	private static void assertRateLimited(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(429, response.statusCode(), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + expectedId + "\""), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"message\":\"Rate limited\""), response.body());
	}

	private static void assertRawResponse(String response, int expectedStatus,
			String expectedId) {
		Assertions.assertTrue(response.startsWith(
				"HTTP/1.1 " + expectedStatus + " "), response);
		Assertions.assertTrue(response.contains("\r\n\r\n"), response);
		Assertions.assertTrue(response.contains(
				"\"id\":\"" + expectedId + "\""), response);
	}
}
