/*
 * Copyright 2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet.interop.apps;

import com.soklet.LifecycleObserver;
import com.soklet.LogEvent;
import com.soklet.McpServer;
import com.soklet.McpServerStatus;
import com.soklet.ShutdownComponentDisposition;
import com.soklet.ShutdownComponentType;
import com.soklet.ShutdownResult;
import com.soklet.Soklet;
import com.soklet.SokletConfig;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Live HTTP authorization checks for the public-API Apps fixture. The browser
 * profile checks rendering; this checks fresh Soklet admission and resource
 * authorization across caller changes on the same bearer credential.
 */
public final class AppsFixtureHttpAuthorizationTest {
	private static final String PROTOCOL = "2026-07-28";
	private static final String CAPABILITIES = "{\"extensions\":{\"io.modelcontextprotocol/ui\":"
			+ "{\"mimeTypes\":[\"text/html;profile=mcp-app\"],\"elicitation\":{}}}}";
	private static final String META = "{\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL
			+ "\",\"io.modelcontextprotocol/clientCapabilities\":" + CAPABILITIES + "}";
	private static final LifecycleObserver QUIET = new LifecycleObserver() {
		@Override
		public void didReceiveLogEvent(LogEvent event) {
			// The test reports only fixed structural outcomes.
		}
	};
	private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(3)).build();
	private final URI endpoint;
	private final String token;
	private int requests;

	private AppsFixtureHttpAuthorizationTest(URI endpoint, String token) {
		this.endpoint = endpoint;
		this.token = token;
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 1)
			throw new IllegalArgumentException("Expected one static shell path.");
		String shell = Files.readString(Path.of(arguments[0]), StandardCharsets.UTF_8);
		String token = "disposable-" + UUID.randomUUID().toString().replace("-", "");
		AppsFixture fixture = new AppsFixture(shell, Map.of(token,
				new AppsFixture.Caller("first", "alpha", "en-US", true)));
		SokletConfig config = fixture.serverConfig(0, QUIET);
		McpServer server = config.getMcpServer().orElseThrow();
		Soklet soklet = Soklet.fromConfig(config);
		soklet.start();
		int completedRequests;
		try {
			InetSocketAddress address = server.getDiagnostics().getBoundAddress().orElseThrow();
			check("127.0.0.1".equals(address.getAddress().getHostAddress()) && address.getPort() > 0,
					"The fixture must bind an ephemeral loopback port.");
			AppsFixtureHttpAuthorizationTest suite = new AppsFixtureHttpAuthorizationTest(
					URI.create("http://127.0.0.1:" + address.getPort() + AppsFixture.PATH), token);
			suite.run(fixture);
			completedRequests = suite.requests;
		} finally {
			ShutdownResult shutdown = soklet.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
			check(server.getDiagnostics().getStatus() == McpServerStatus.TERMINATED
					&& shutdown.getShutdownComponentResult(ShutdownComponentType.MCP).orElseThrow()
							.getShutdownComponentDisposition() == ShutdownComponentDisposition.GRACEFUL_TERMINATION,
					"The fixture must shut down cleanly.");
		}
		System.out.println("{\"status\":\"PASS\",\"requests\":" + completedRequests
				+ ",\"scope\":\"live-http-apps-authorization\"}");
	}

	private void run(AppsFixture fixture) throws Exception {
		Capture firstCall = call(AppsFixture.REFRESH, token);
		success(firstCall);
		contains(firstCall.body(), "\"tenant\":\"alpha\"", "Initial caller tenant missing.");
		contains(firstCall.body(), "\"locale\":\"en-US\"", "Initial caller locale missing.");
		Capture firstRead = read(AppsFixture.UI_URI.toString(), token);
		success(firstRead);
		contains(firstRead.body(), "\"uri\":\"" + AppsFixture.UI_URI + "\"", "UI URI missing.");
		contains(firstRead.body(), "\"csp\":{", "UI CSP metadata missing.");

		fixture.setCaller(token, new AppsFixture.Caller("denied", "alpha", "en-US", false));
		Capture hiddenTools = send("tools/list", "", token);
		success(hiddenTools);
		contains(hiddenTools.body(), "\"tools\":[]", "Denied caller saw a tool.");
		Capture hiddenResources = send("resources/list", "", token);
		success(hiddenResources);
		contains(hiddenResources.body(), "\"resources\":[]", "Denied caller saw a resource.");
		Capture deniedCall = call(AppsFixture.REFRESH, token);
		Capture unknownCall = call("unknown_catalog", token);
		check(deniedCall.status() == unknownCall.status()
				&& deniedCall.body().equals(unknownCall.body()), "Hidden and unknown tools diverged.");
		error(deniedCall);
		Capture deniedRead = read(AppsFixture.UI_URI.toString(), token);
		Capture unknownRead = read("ui://soklet/unknown", token);
		error(deniedRead);
		error(unknownRead);
		contains(deniedRead.body(), "\"code\":-31904", "Read policy error missing.");
		absent(deniedRead.body(), "\"contents\":", "Denied read exposed content.");
		absent(deniedRead.body(), "\"_meta\":", "Denied read exposed metadata.");
		absent(unknownRead.body(), "\"contents\":", "Unknown read exposed content.");

		fixture.setCaller(token, new AppsFixture.Caller("second", "beta", "pt-BR", true));
		Capture secondCall = call(AppsFixture.REFRESH, token);
		success(secondCall);
		contains(secondCall.body(), "\"tenant\":\"beta\"", "Changed caller tenant missing.");
		contains(secondCall.body(), "\"locale\":\"pt-BR\"", "Changed caller locale missing.");
		contains(secondCall.body(), "Catálogo beta: 1 item.", "Changed caller text missing.");
		absent(secondCall.body(), "Catalog alpha: 1 item.", "Old caller data was reused.");
		success(read(AppsFixture.UI_URI.toString(), token));

		fixture.revoke(token);
		Capture revokedCall = call(AppsFixture.REFRESH, token);
		Capture revokedRead = read(AppsFixture.UI_URI.toString(), token);
		check(revokedCall.status() == 401 && revokedRead.status() == 401,
				"Revocation did not affect the next tool call and resource read.");
		error(revokedCall);
		error(revokedRead);
		absent(revokedCall.body(), "\"structuredContent\":", "Revoked call exposed data.");
		absent(revokedRead.body(), "\"contents\":", "Revoked read exposed content.");
		check(requests == 12, "The fixed authorization matrix did not complete.");
	}

	private Capture call(String name, String credential) throws Exception {
		return send("tools/call", ",\"name\":\"" + name + "\",\"arguments\":{}", credential, name);
	}

	private Capture read(String uri, String credential) throws Exception {
		return send("resources/read", ",\"uri\":\"" + uri + "\"", credential, uri);
	}

	private Capture send(String method, String selection, String credential) throws Exception {
		return send(method, selection, credential, null);
	}

	private Capture send(String method, String selection, String credential, String name) throws Exception {
		check(++requests <= 16, "The fixed request bound was exceeded.");
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"apps-live\",\"method\":\"" + method
				+ "\",\"params\":{\"_meta\":" + META + selection + "}}";
		HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
				.header("Authorization", "Bearer " + credential).header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL).header("Mcp-Method", method)
				.header("Accept-Language", "de-DE");
		if (name != null)
			request.header("Mcp-Name", name);
		HttpResponse<String> response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(body))
				.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		check(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
				"A response could be shared across callers.");
		check(response.body().length() < 1024 * 1024, "The response exceeded the fixture bound.");
		absent(response.body(), AppsFixture.RAW_CANARY, "The raw private marker leaked.");
		return new Capture(response.statusCode(), response.body());
	}

	private static void success(Capture result) {
		check(result.status() == 200 && result.body().contains("\"result\":"),
				"A successful request failed.");
		absent(result.body(), "\"error\":", "Successful request returned an error.");
	}

	private static void error(Capture result) {
		contains(result.body(), "\"error\":", "Expected an authorization error.");
		absent(result.body(), "\"structuredContent\":", "An error exposed tool data.");
	}

	private static void contains(String value, String expected, String failure) {
		check(value.contains(expected), failure);
	}

	private static void absent(String value, String forbidden, String failure) {
		check(!value.contains(forbidden), failure);
	}

	private static void check(boolean condition, String failure) {
		if (!condition)
			throw new AssertionError(failure);
	}

	private record Capture(int status, String body) {
	}
}
