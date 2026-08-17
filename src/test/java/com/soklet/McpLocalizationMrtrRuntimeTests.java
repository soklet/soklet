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
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-network localized MRTR continuity: two independently constructed
 * instances complete a framework-protected flow without affinity, the verified
 * continuation locale is a required selection, and a version-2 continuation on
 * a localization-disabled node fails through the sanitized path.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@Timeout(30)
class McpLocalizationMrtrRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/localization/mrtr";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TOOL = "mrtr.tool";
	private static final String KEY_MATERIAL =
			"localization-mrtr-key-material-0123456789abcdef";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	void twoIndependentInstancesCompleteALocalizedFlowWithoutAffinity() {
		List<Optional<Locale>> mintObserved = new CopyOnWriteArrayList<>();
		List<Optional<Locale>> retryObserved = new CopyOnWriteArrayList<>();

		// Instance one selects fr-CA freely and mints the continuation.
		Capture initial = call(server(localizer(mintObserved,
				continuation -> Locale.CANADA_FRENCH), new AtomicInteger()),
				request("mint", ""));
		assertEquals(200, initial.statusCode(), initial.body());
		assertTrue(initial.body().contains("\"resultType\":\"input_required\""),
				initial.body());
		String state = extractRequestState(initial.body());
		assertEquals(List.of(Optional.<Locale>empty()), mintObserved,
				"The first round has no continuation locale.");

		// A separately constructed instance with the same key material honors
		// the verified continuation exactly.
		Capture retry = call(server(localizer(retryObserved,
				continuation -> continuation.orElseThrow()), new AtomicInteger()),
				request("retry", ",\"requestState\":\"" + state + "\""));
		assertEquals(200, retry.statusCode(), retry.body());
		assertTrue(retry.body().contains("\"resultType\":\"complete\""),
				retry.body());
		assertTrue(retry.body().contains("locale:fr-CA"), retry.body());
		assertEquals(List.of(Optional.of(Locale.CANADA_FRENCH)), retryObserved,
				"The verified continuation locale must reach the provider.");
	}

	@Test
	void aContextReportingADifferentLocaleFailsBeforeHandlerEntry() {
		Capture initial = call(server(localizer(new CopyOnWriteArrayList<>(),
				continuation -> Locale.CANADA_FRENCH), new AtomicInteger()),
				request("mismatch-mint", ""));
		String state = extractRequestState(initial.body());

		AtomicInteger handlerInvocations = new AtomicInteger();
		Capture retry = call(server(localizer(new CopyOnWriteArrayList<>(),
				continuation -> Locale.GERMAN), handlerInvocations),
				request("mismatch-retry", ",\"requestState\":\"" + state + "\""));

		assertEquals(500, retry.statusCode(), retry.body());
		assertTrue(retry.body().contains("\"code\":-32603"), retry.body());
		assertTrue(retry.body().contains("\"message\":\"Internal error\""),
				retry.body());
		assertEquals(0, handlerInvocations.get(),
				"Language renegotiation must fail before handler entry.");
	}

	@Test
	void aVersionTwoContinuationOnADisabledNodeFailsSanitizedWithoutDowngrade() {
		Capture initial = call(server(localizer(new CopyOnWriteArrayList<>(),
				continuation -> Locale.CANADA_FRENCH), new AtomicInteger()),
				request("disabled-mint", ""));
		String state = extractRequestState(initial.body());

		AtomicInteger handlerInvocations = new AtomicInteger();
		Capture retry = call(server(null, handlerInvocations),
				request("disabled-retry", ",\"requestState\":\"" + state + "\""));

		assertEquals(500, retry.statusCode(), retry.body());
		assertTrue(retry.body().contains("\"code\":-32603"), retry.body());
		assertFalse(retry.body().contains("fr-CA"), retry.body());
		assertEquals(0, handlerInvocations.get(),
				"A disabled node cannot construct the required exact context.");
		assertFalse(retry.body().contains("input_required"),
				"The sanitized path must never re-emit a downgraded state.");
	}

	@Test
	void aVersionOneContinuationRemainsUsableOnALocalizedNode() {
		// Minted without localization: version-1 state.
		Capture initial = call(server(null, new AtomicInteger()),
				request("plain-mint", ""));
		assertEquals(200, initial.statusCode(), initial.body());
		String state = extractRequestState(initial.body());

		// A localized node continues it; the provider sees no continuation
		// locale and selects freely.
		List<Optional<Locale>> observed = new CopyOnWriteArrayList<>();
		Capture retry = call(server(localizer(observed,
				continuation -> Locale.CANADA_FRENCH), new AtomicInteger()),
				request("plain-retry", ",\"requestState\":\"" + state + "\""));

		assertEquals(200, retry.statusCode(), retry.body());
		assertTrue(retry.body().contains("\"resultType\":\"complete\""),
				retry.body());
		assertEquals(List.of(Optional.<Locale>empty()), observed,
				"A version-1 flow carries no required selection.");
	}

	private interface LocaleChoice {
		Locale select(Optional<Locale> continuationLocale);
	}

	private static McpLocalizer localizer(List<Optional<Locale>> observed,
			LocaleChoice choice) {
		return McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					observed.add(request.getContinuationLocale());
					Locale locale = choice.select(request.getContinuationLocale());
					return new McpLocalizationContext() {
						@Override
						public Locale getLocale() {
							return locale;
						}

						@Override
						public McpLocalizationResult localize(
								McpLocalizableText text) {
							return McpLocalizationResult.useDefaultText();
						}
					};
				})
				.build();
	}

	private static McpServer server(McpLocalizer localizer,
			AtomicInteger handlerInvocations) {
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.REQUIRED);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();

					if (request.getRequestState().isEmpty())
						return McpInputRequiredResult.builder()
								.inputRequest("roots", McpInputRequest
										.fromDeclaration(roots,
												McpJsonObject.emptyInstance()))
								.frameworkRequestState(McpJsonObject.builder()
										.put("phase", "waiting")
										.build())
								.build();

					String tag = features.find(McpLocalizationContext.class)
							.map(context -> context.getLocale().toLanguageTag())
							.orElse("none");
					return McpCompleteResult.fromToolText("locale:" + tag);
				})
				.mayRequestInput(roots)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("localization-mrtr", "1.0").build())
				.tool(tool)
				.build();
		McpServer.Builder builder = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.protectionConfig(McpProtectionConfig.withKeyRing(
						McpProtectionKeyRing.withActiveKey(
								McpProtectionKey.fromIdAndBytes("mrtr-key",
										KEY_MATERIAL.getBytes(
												StandardCharsets.US_ASCII)))
								.build())
						.build());

		if (localizer != null)
			builder.localizer(localizer);

		return builder.build();
	}

	private record Capture(int statusCode, String body) {}

	private static Capture call(McpServer server, Request request) {
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
		AtomicReference<Capture> captured = new AtomicReference<>();

		Soklet.runSimulator(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(request);

			try {
				McpSimulationResponse response = simulation.awaitResponse(WAIT)
						.orElseThrow(() -> new AssertionError("Timed out."));
				captured.set(new Capture(response.getStatusCode(),
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

	private static Request request(String id, String additionalParameters) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{\"roots\":{}}}"
				+ ",\"name\":\"" + TOOL + "\",\"arguments\":{}"
				+ additionalParameters + "}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of("tools/call"));
		headers.put("Mcp-Name", Set.of(TOOL));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static String extractRequestState(String body) {
		String marker = "\"requestState\":\"";
		int start = body.indexOf(marker);
		assertTrue(start >= 0, body);
		start += marker.length();
		int end = body.indexOf('"', start);
		assertTrue(end > start, body);
		return body.substring(start, end);
	}
}
