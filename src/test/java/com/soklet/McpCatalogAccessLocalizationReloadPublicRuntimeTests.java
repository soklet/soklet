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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public-boundary coverage for authorization projection across localization
 * fallback and reload boundaries.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
class McpCatalogAccessLocalizationReloadPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/catalog-access/reload";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final Set<String> ALLOWED_TOOLS = Set.of(
			"reload.tool.shared", "reload.tool.tenant-a");
	private static final Set<String> ALLOWED_PROMPTS = Set.of(
			"reload.prompt.shared", "reload.prompt.tenant-a");
	private static final List<String> ALL_NAMES = List.of(
			"reload.tool.denied-first", "reload.tool.shared",
			"reload.tool.tenant-a", "reload.tool.denied-last",
			"reload.prompt.denied-first", "reload.prompt.shared",
			"reload.prompt.tenant-a", "reload.prompt.denied-last");
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	@Test
	void authorizationProjectionSurvivesMissingFallbackAndInvalidation()
			throws Exception {
		AtomicReference<TranslationMode> mode =
				new AtomicReference<>(TranslationMode.MISSING);
		AtomicInteger providerInvocations = new AtomicInteger();
		List<Lookup> lookups = new CopyOnWriteArrayList<>();
		McpEndpoint endpoint = endpoint();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) ->
						ALLOWED_TOOLS.contains(registration.getName()),
				(context, registration, features) ->
						ALLOWED_PROMPTS.contains(registration.getName()));
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> {
					providerInvocations.incrementAndGet();
					TranslationMode snapshot = mode.get();
					String method = request.getRequestContext().getJsonRpcMethod();
					return McpLocalizationContext.withLocale(Locale.FRENCH, text -> {
						String owner = text.getCoordinate().getSubjectId();
						lookups.add(new Lookup(snapshot, method, owner));
						return switch (snapshot) {
							case MISSING -> owner.endsWith(".shared")
									? McpLocalizationResult.useDefaultText()
									: McpLocalizationResult.localized(
											"MISSING:" + text.getDefaultText());
							case WHOLE_RESPONSE_FAILURE -> owner.endsWith(".tenant-a")
									? McpLocalizationResult.failure()
									: McpLocalizationResult.localized(
											"PARTIAL:" + text.getDefaultText());
							case RELOADED -> McpLocalizationResult.localized(
									"V2:" + text.getDefaultText());
						};
					}).build();
				}).build();
		McpServer server = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(LOOPBACK)
				.admissionController(context -> McpAdmissionDecision.accepted(
						McpAdmissionIdentity.withRateLimitPartitionKey("rate-tenant-a")
								.authorizationPartitionKey("tenant-a")
								.principal("tenant-a")
								.build()))
				.catalogAccessPolicy(policy)
				.localizer(localizer)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());

		try {
			owner.start();
			String missingTools = body(send(server, "missing-tools", "tools/list"));
			String missingPrompts = body(send(server, "missing-prompts",
					"prompts/list"));
			assertStableToolProjection(missingTools);
			assertStablePromptProjection(missingPrompts);
			Assertions.assertTrue(missingTools.contains(
					"\"title\":\"Shared tool\""), missingTools);
			Assertions.assertTrue(missingTools.contains(
					"\"title\":\"MISSING:Tenant tool\""), missingTools);
			Assertions.assertTrue(missingPrompts.contains(
					"\"title\":\"Shared prompt\""), missingPrompts);
			Assertions.assertTrue(missingPrompts.contains(
					"\"title\":\"MISSING:Tenant prompt\""), missingPrompts);

			mode.set(TranslationMode.WHOLE_RESPONSE_FAILURE);
			String fallbackTools = body(send(server, "fallback-tools",
					"tools/list"));
			String fallbackPrompts = body(send(server, "fallback-prompts",
					"prompts/list"));
			assertStableToolProjection(fallbackTools);
			assertStablePromptProjection(fallbackPrompts);
			Assertions.assertTrue(fallbackTools.contains(
					"\"title\":\"Shared tool\""), fallbackTools);
			Assertions.assertTrue(fallbackTools.contains(
					"\"title\":\"Tenant tool\""), fallbackTools);
			Assertions.assertTrue(fallbackPrompts.contains(
					"\"title\":\"Shared prompt\""), fallbackPrompts);
			Assertions.assertTrue(fallbackPrompts.contains(
					"\"title\":\"Tenant prompt\""), fallbackPrompts);
			Assertions.assertFalse(fallbackTools.contains("PARTIAL:"),
					fallbackTools);
			Assertions.assertFalse(fallbackPrompts.contains("PARTIAL:"),
					fallbackPrompts);

			mode.set(TranslationMode.RELOADED);
			server.getLocalizationControl().invalidateCatalogs();
			String reloadedTools = body(send(server, "reloaded-tools",
					"tools/list"));
			String reloadedPrompts = body(send(server, "reloaded-prompts",
					"prompts/list"));
			assertStableToolProjection(reloadedTools);
			assertStablePromptProjection(reloadedPrompts);
			Assertions.assertTrue(reloadedTools.contains(
					"\"title\":\"V2:Shared tool\""), reloadedTools);
			Assertions.assertTrue(reloadedTools.contains(
					"\"title\":\"V2:Tenant tool\""), reloadedTools);
			Assertions.assertTrue(reloadedPrompts.contains(
					"\"title\":\"V2:Shared prompt\""), reloadedPrompts);
			Assertions.assertTrue(reloadedPrompts.contains(
					"\"title\":\"V2:Tenant prompt\""), reloadedPrompts);

			Assertions.assertEquals(6, providerInvocations.get(),
					"Each list request must capture a fresh tenant snapshot.");
			for (Lookup lookup : lookups) {
				Set<String> allowed = "tools/list".equals(lookup.method())
						? ALLOWED_TOOLS : ALLOWED_PROMPTS;
				Assertions.assertTrue(allowed.contains(lookup.owner()),
						() -> "A filtered owner reached localization: " + lookup);
			}
		} finally {
			owner.close();
		}
	}

	private static McpEndpoint endpoint() {
		return McpEndpoint.withPath(MCP_PATH, McpImplementation
						.withNameAndVersion("catalog-access-reload", "4.0.0")
						.build())
				.serverInfoIncluded(false)
				.addTool(tool("reload.tool.denied-first", "Denied first tool"))
				.addTool(tool("reload.tool.shared", "Shared tool"))
				.addTool(tool("reload.tool.tenant-a", "Tenant tool"))
				.addTool(tool("reload.tool.denied-last", "Denied last tool"))
				.addPrompt(prompt("reload.prompt.denied-first",
						"Denied first prompt"))
				.addPrompt(prompt("reload.prompt.shared", "Shared prompt"))
				.addPrompt(prompt("reload.prompt.tenant-a", "Tenant prompt"))
				.addPrompt(prompt("reload.prompt.denied-last",
						"Denied last prompt"))
				.build();
	}

	private static McpToolRegistration<McpJsonObject> tool(String name,
			String title) {
		return McpToolRegistration.withName(name)
				.jsonObjectArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("unused"))
				.title(title)
				.build();
	}

	private static McpPromptRegistration prompt(String name, String title) {
		return McpPromptRegistration.withName(name)
				.handler((request, promptGet, features) ->
						McpCompleteResult.fromPromptOutput(
								McpPromptOutput.fromMessages()))
				.title(title)
				.build();
	}

	private static HttpResponse<String> send(McpServer server, String id,
			String method) throws Exception {
		int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
		String requestBody = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("Accept-Language", "fr")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method)
				.POST(HttpRequest.BodyPublishers.ofString(requestBody,
						StandardCharsets.UTF_8))
				.build();
		return HTTP_CLIENT.send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static String body(HttpResponse<String> response) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertTrue(response.body().contains(
				"\"resultType\":\"complete\""), response.body());
		return response.body();
	}

	private static void assertStableToolProjection(String body) {
		assertProjection(body, List.of("reload.tool.shared",
				"reload.tool.tenant-a"), ALLOWED_TOOLS);
		Assertions.assertTrue(body.contains("\"tools\":["), body);
	}

	private static void assertStablePromptProjection(String body) {
		assertProjection(body, List.of("reload.prompt.shared",
				"reload.prompt.tenant-a"), ALLOWED_PROMPTS);
		Assertions.assertTrue(body.contains("\"prompts\":["), body);
	}

	private static void assertProjection(String body, List<String> ordered,
			Set<String> allowed) {
		int previous = -1;
		for (String name : ordered) {
			int position = body.indexOf("\"name\":\"" + name + "\"");
			Assertions.assertTrue(position > previous,
					() -> "Expected filtered canonical order in " + body);
			previous = position;
		}
		for (String name : ALL_NAMES)
			if (!allowed.contains(name))
				Assertions.assertFalse(body.contains("\"name\":\"" + name + "\""),
						body);
	}

	private enum TranslationMode {
		MISSING,
		WHOLE_RESPONSE_FAILURE,
		RELOADED
	}

	private record Lookup(@NonNull TranslationMode mode,
			@NonNull String method, @NonNull String owner) {
	}
}
