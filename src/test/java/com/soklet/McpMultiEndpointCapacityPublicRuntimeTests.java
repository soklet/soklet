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

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Black-box coverage for server-wide public MCP execution bounds across
 * multiple endpoint paths.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpMultiEndpointCapacityPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String FIRST_PATH = "/mcp/first-capacity";
	private static final String SECOND_PATH = "/mcp/second-capacity";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String FIRST_TOOL = "first.blocking";
	private static final String SECOND_TOOL = "second.blocking";

	@Test
	public void handlerCapacityIsSharedAcrossEndpointPaths() throws Exception {
		CountDownLatch firstHandlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandlers = new CountDownLatch(1);
		AtomicInteger firstInvocations = new AtomicInteger();
		AtomicInteger secondInvocations = new AtomicInteger();
		AtomicInteger activeHandlers = new AtomicInteger();
		AtomicInteger maximumActiveHandlers = new AtomicInteger();

		McpToolRegistration<McpJsonObject> firstTool = blockingTool(FIRST_TOOL,
				firstHandlerEntered, releaseHandlers, firstInvocations,
				activeHandlers, maximumActiveHandlers);
		McpToolRegistration<McpJsonObject> secondTool = blockingTool(SECOND_TOOL,
				new CountDownLatch(0), releaseHandlers, secondInvocations,
				activeHandlers, maximumActiveHandlers);
		McpEndpoint firstEndpoint = endpoint(FIRST_PATH,
				"multi-endpoint-first-capacity", firstTool);
		McpEndpoint secondEndpoint = endpoint(SECOND_PATH,
				"multi-endpoint-second-capacity", secondTool);
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(firstEndpoint, secondEndpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.requestHandlerConcurrency(1)
				.requestHandlerQueueCapacity(1)
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			CompletableFuture<HttpResponse<String>> first = sendAsync(port,
					FIRST_PATH, "capacity-first", FIRST_TOOL);
			Assertions.assertTrue(firstHandlerEntered.await(5, TimeUnit.SECONDS),
					"The first endpoint handler did not start.");

			CompletableFuture<HttpResponse<String>> second = sendAsync(port,
					FIRST_PATH, "capacity-second", FIRST_TOOL);
			CompletableFuture<HttpResponse<String>> third = sendAsync(port,
					SECOND_PATH, "capacity-third", SECOND_TOOL);

			HttpResponse<String> rejected = second.applyToEither(third,
					response -> response).get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(503, rejected.statusCode(), rejected.body());
			assertContains(rejected.body(), "\"code\":-32603");
			assertContains(rejected.body(), "\"message\":\"Internal error\"");
			Assertions.assertFalse(rejected.body().contains("\"data\""),
					rejected.body());
			Assertions.assertTrue(rejected.headers()
					.firstValue("Retry-After").isEmpty());
			boolean rejectedFirstEndpoint = rejected.body().contains(
					"\"id\":\"capacity-second\"");
			boolean rejectedSecondEndpoint = rejected.body().contains(
					"\"id\":\"capacity-third\"");
			Assertions.assertTrue(rejectedFirstEndpoint
					^ rejectedSecondEndpoint, rejected.body());

			releaseHandlers.countDown();
			HttpResponse<String> firstResponse = first.get(5, TimeUnit.SECONDS);
			HttpResponse<String> secondResponse = second.get(5, TimeUnit.SECONDS);
			HttpResponse<String> thirdResponse = third.get(5, TimeUnit.SECONDS);
			List<HttpResponse<String>> responses = List.of(firstResponse,
					secondResponse, thirdResponse);
			Assertions.assertEquals(2, responses.stream()
					.filter(response -> response.statusCode() == 200)
					.count());
			Assertions.assertEquals(1, responses.stream()
					.filter(response -> response.statusCode() == 503)
					.count());
			Assertions.assertEquals(2,
					firstInvocations.get() + secondInvocations.get());
			if (rejectedFirstEndpoint) {
				Assertions.assertEquals(1, firstInvocations.get());
				Assertions.assertEquals(1, secondInvocations.get());
			} else {
				Assertions.assertEquals(2, firstInvocations.get());
				Assertions.assertEquals(0, secondInvocations.get());
			}
			Assertions.assertEquals(1, maximumActiveHandlers.get(),
					"All endpoint paths must share the server-wide handler slot.");
		} finally {
			releaseHandlers.countDown();
			server.stop();
		}
	}

	@NonNull
	private static McpEndpoint endpoint(@NonNull String path,
			@NonNull String implementationName,
			@NonNull McpToolRegistration<McpJsonObject> tool) {
		return McpEndpoint.withPath(path)
				.serverInformation(McpImplementation.withNameAndVersion(
						implementationName, "4.0.0-SNAPSHOT").build())
				.tool(tool)
				.build();
	}

	@NonNull
	private static McpToolRegistration<McpJsonObject> blockingTool(
			@NonNull String name, @NonNull CountDownLatch entered,
			@NonNull CountDownLatch release,
			@NonNull AtomicInteger invocations,
			@NonNull AtomicInteger activeHandlers,
			@NonNull AtomicInteger maximumActiveHandlers) {
		return McpToolRegistration.withName(name)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					invocations.incrementAndGet();
					int active = activeHandlers.incrementAndGet();
					maximumActiveHandlers.accumulateAndGet(active, Math::max);
					entered.countDown();
					try {
						if (!release.await(10, TimeUnit.SECONDS))
							throw new IllegalStateException(
									"Timed out awaiting test release.");
						return McpCompleteResult.fromToolText("done");
					} finally {
						activeHandlers.decrementAndGet();
					}
				})
				.build();
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> sendAsync(int port,
			@NonNull String path, @NonNull String id,
			@NonNull String toolName) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + toolName + "\",\"arguments\":{}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + path))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", toolName)
				.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8))
				.build();
		return httpClient().sendAsync(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void assertContains(@NonNull String actual,
			@NonNull String expected) {
		Assertions.assertTrue(actual.contains(expected), () ->
				"Expected <" + actual + "> to contain <" + expected + ">.");
	}

	@NonNull
	private static HttpClient httpClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build();
	}
}
