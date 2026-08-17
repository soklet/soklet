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

import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 HTTP and application-boundary evidence: private/zero cache clamping,
 * Vary/Content-Language behavior across success/error/CORS/subscription
 * responses, and proof that dynamic application output is never post-processed.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpLocalizationHttpBoundaryTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/localization/http";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	void cacheableResultsAreClampedToPrivateZeroExactlyWhenLocalized() {
		// Without a localizer the configured positive public policies publish.
		String plainList = capture(null, CorsAuthorizer.rejectAllInstance(),
				request("resources/list", "plain-list", null, "", Set.of(), null))
				.body();
		assertTrue(plainList.contains("\"ttlMs\":60000"), plainList);
		assertTrue(plainList.contains("\"cacheScope\":\"public\""), plainList);

		String plainRead = capture(null, CorsAuthorizer.rejectAllInstance(),
				request("resources/read", "plain-read", "http://cache/text",
						",\"uri\":\"http://cache/text\"", Set.of(), null)).body();
		assertTrue(plainRead.contains("\"ttlMs\":45000"), plainRead);
		assertTrue(plainRead.contains("\"cacheScope\":\"public\""), plainRead);

		// With one, every cacheable localized-capable result is private/zero.
		for (String[] operation : new String[][]{
				{"resources/list", "clamped-list", null, ""},
				{"resources/templates/list", "clamped-templates", null, ""},
				{"resources/read", "clamped-read", "http://cache/text",
						",\"uri\":\"http://cache/text\""}}) {
			String body = capture(localizer(), CorsAuthorizer.rejectAllInstance(),
					request(operation[0], operation[1], operation[2], operation[3],
							Set.of("fr-CA"), null)).body();
			assertTrue(body.contains("\"ttlMs\":0"), operation[0] + ": " + body);
			assertTrue(body.contains("\"cacheScope\":\"private\""),
					operation[0] + ": " + body);
			assertFalse(body.contains("60000"), operation[0] + ": " + body);
			assertFalse(body.contains("45000"), operation[0] + ": " + body);
		}
	}

	@Test
	void sanitizedErrorsAndRejectionsCarryVaryExactlyWhenLocalized() {
		// An unknown-method error from a localization-enabled endpoint varies.
		Capture localizedError = capture(localizer(),
				CorsAuthorizer.rejectAllInstance(),
				request("tools/call", "vary-error", "missing.tool",
						",\"name\":\"missing.tool\",\"arguments\":{}",
						Set.of(), null));
		assertEquals(Set.of("Accept-Language"),
				localizedError.headers().get("Vary"), localizedError.toString());

		// The same request without a localizer carries no Vary at all.
		Capture plainError = capture(null, CorsAuthorizer.rejectAllInstance(),
				request("tools/call", "plain-error", "missing.tool",
						",\"name\":\"missing.tool\",\"arguments\":{}",
						Set.of(), null));
		assertFalse(plainError.headers().containsKey("Vary"),
				plainError.headers().toString());

		// CORS rejection occurs before semantic decoding and the ordinary shared
		// header path; endpoint-level response decoration must still apply Vary.
		Capture corsRejection = capture(localizer(),
				CorsAuthorizer.rejectAllInstance(),
				request("server/discover", "early-cors", null, "", Set.of(),
						"https://rejected.example"));
		assertEquals(403, corsRejection.statusCode());
		assertEquals(Set.of("Accept-Language"),
				corsRejection.headers().get("Vary"), corsRejection.toString());
	}

	@Test
	void corsOriginVaryMergesIntoASingleDuplicateFreeTokenList() {
		Capture capture = capture(localizer(), CorsAuthorizer.acceptAllInstance(),
				request("server/discover", "vary-cors", null, "", Set.of(),
						"https://cors.example"));

		assertEquals(200, capture.statusCode(), capture.body());
		assertEquals(Set.of("Origin, Accept-Language"),
				capture.headers().get("Vary"), capture.headers().toString());
	}

	@Test
	void admissionRejectionVaryFieldsNormalizeWithoutLosingWildcardSemantics() {
		McpAdmissionRejection wildcard = McpAdmissionRejection
				.withStatusCodeAndError(403,
						McpJsonRpcError.fromApplication(-31_001, "denied"))
				.header("Vary", "*")
				.build();
		Capture wildcardCapture = capture(localizer(),
				CorsAuthorizer.acceptAllInstance(),
				request("server/discover", "vary-wildcard", null, "", Set.of(),
						"https://cors.example"),
				context -> McpAdmissionDecision.rejected(wildcard));

		assertEquals(403, wildcardCapture.statusCode());
		assertEquals(Set.of("*"), wildcardCapture.headers().get("Vary"),
				wildcardCapture.headers().toString());

		McpAdmissionRejection duplicateTokens = McpAdmissionRejection
				.withStatusCodeAndError(403,
						McpJsonRpcError.fromApplication(-31_002, "denied"))
				.header("Vary",
						"X-Tenant, accept-language, ORIGIN, x-tenant")
				.build();
		Capture normalizedCapture = capture(localizer(),
				CorsAuthorizer.acceptAllInstance(),
				request("server/discover", "vary-normalized", null, "", Set.of(),
						"https://cors.example"),
				context -> McpAdmissionDecision.rejected(duplicateTokens));

		assertEquals(403, normalizedCapture.statusCode());
		assertEquals(Set.of("Origin, Accept-Language, X-Tenant"),
				normalizedCapture.headers().get("Vary"),
				normalizedCapture.headers().toString());
	}

	@Test
	void dynamicApplicationOutputIsNeverPostProcessed() {
		// The handler emits text that looks exactly like localizable JSON and
		// also localizes for itself from the exact provider context.
		AtomicReference<Locale> observedLocale = new AtomicReference<>();
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> context(Locale.FRENCH,
						text -> McpLocalizationResult.localized(
								"FR:" + text.getDefaultText())))
				.build();

		String body = capture(localizer, CorsAuthorizer.rejectAllInstance(),
				request("resources/read", "dynamic-owned", "http://cache/dynamic",
						",\"uri\":\"http://cache/dynamic\"", Set.of("fr-CA"),
						null), observedLocale).body();

		// The application's own payload survives byte-for-byte: no framework
		// replacement inside arbitrary JSON, even members named "title". On the
		// wire the payload is a JSON string, so its quotes appear escaped.
		assertTrue(body.contains("{\\\"title\\\":\\\"Dynamic title\\\","
				+ "\\\"description\\\":\\\"Dynamic\\\"}"), body);
		assertFalse(body.contains("FR:Dynamic"), body);
		// The handler localized for itself from the exact selected locale.
		assertTrue(body.contains("application-localized:fr"), body);
		assertEquals(Locale.FRENCH, observedLocale.get());
	}

	private static McpLocalizer localizer() {
		return McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> context(Locale.FRENCH,
						text -> McpLocalizationResult.useDefaultText()))
				.build();
	}

	private static McpLocalizationContext context(Locale locale,
			java.util.function.Function<McpLocalizableText,
					McpLocalizationResult> provider) {
		return new McpLocalizationContext() {
			@Override
			public Locale getLocale() {
				return locale;
			}

			@Override
			public McpLocalizationResult localize(McpLocalizableText text) {
				return provider.apply(text);
			}
		};
	}

	private record Capture(int statusCode, Map<String, Set<String>> headers,
			String body) {}

	private static Capture capture(McpLocalizer localizer,
			CorsAuthorizer corsAuthorizer, Request request) {
		return capture(localizer, corsAuthorizer, request,
				McpAdmissionController.acceptAllInstance(),
				new AtomicReference<>());
	}

	private static Capture capture(McpLocalizer localizer,
			CorsAuthorizer corsAuthorizer, Request request,
			AtomicReference<Locale> observedLocale) {
		return capture(localizer, corsAuthorizer, request,
				McpAdmissionController.acceptAllInstance(), observedLocale);
	}

	private static Capture capture(McpLocalizer localizer,
			CorsAuthorizer corsAuthorizer, Request request,
			McpAdmissionController admissionController) {
		return capture(localizer, corsAuthorizer, request, admissionController,
				new AtomicReference<>());
	}

	private static Capture capture(McpLocalizer localizer,
			CorsAuthorizer corsAuthorizer, Request request,
			McpAdmissionController admissionController,
			AtomicReference<Locale> observedLocale) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("localization-http", "1.0")
						.title("Canonical title")
						.build())
				.resourceListCachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofSeconds(60)))
				.resourceTemplateListCachePolicy(McpCachePolicy
						.fromPublicTimeToLive(Duration.ofSeconds(60)))
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("http://cache/text"), "text")
						.handler((resourceRequest, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.builder()
												.content(McpTextResourceContents
														.withUriAndText(resource.getUri(),
																"cacheable text")
														.build())
												.build()))
						.cachePolicy(McpCachePolicy.fromPublicTimeToLive(
								Duration.ofSeconds(45)))
						.build())
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("http://cache/dynamic"), "dynamic")
						.handler((resourceRequest, resource, features) -> {
							Locale locale = features
									.find(McpLocalizationContext.class)
									.map(McpLocalizationContext::getLocale)
									.orElse(Locale.ROOT);
							observedLocale.set(locale);
							return McpCompleteResult.fromResourceOutput(
									McpResourceOutput.builder()
											.content(McpTextResourceContents
													.withUriAndText(resource.getUri(),
															"{\"title\":\"Dynamic title\","
																	+ "\"description\":\"Dynamic\"}"
																	+ " application-localized:"
																	+ locale.toLanguageTag())
													.build())
											.build());
						})
						.build())
				.resource(McpResourceRegistration.withUriTemplateAndName(
						"http://cache/item/{id}", "item")
						.handler((resourceRequest, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.builder()
												.content(McpTextResourceContents
														.withUriAndText(URI.create(
																"http://cache/item/1"),
																"unused")
														.build())
												.build()))
						.build())
				.tool(McpToolRegistration.withName("cache.tool")
						.jsonArguments()
						.handler((toolRequest, arguments, features) ->
								McpCompleteResult.fromToolText("unused"))
						.build())
				.build();
		McpServer.Builder builder = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(admissionController)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(corsAuthorizer)
				.allowedHosts(Set.of(LOOPBACK));

		if (localizer != null)
			builder.localizer(localizer);

		SokletConfig config = SokletConfig.withMcpServer(builder.build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
		AtomicReference<Capture> captured = new AtomicReference<>();

		Soklet.runSimulator(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(request);

			try {
				McpSimulationResponse response = simulation.awaitResponse(WAIT)
						.orElseThrow(() -> new AssertionError("Timed out."));
				captured.set(new Capture(response.getStatusCode(),
						response.getHeaders(),
						new String(response.getBody().orElseThrow(),
								StandardCharsets.UTF_8)));
				simulation.awaitCompletion(WAIT);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		return captured.get();
	}

	private static Request request(String method, String id, String operationName,
			String paramsSuffix, Set<String> acceptLanguage, String origin) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ paramsSuffix + "}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));

		if (operationName != null)
			headers.put("Mcp-Name", Set.of(operationName));
		if (!acceptLanguage.isEmpty())
			headers.put("Accept-Language", acceptLanguage);
		if (origin != null)
			headers.put("Origin", Set.of(origin));

		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}
}
