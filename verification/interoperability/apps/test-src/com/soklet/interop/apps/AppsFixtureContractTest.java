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

package com.soklet.interop.apps;

import com.soklet.HttpMethod;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationCompletion;
import com.soklet.McpSimulationResponse;
import com.soklet.Request;
import com.soklet.Simulator;
import com.soklet.SokletSimulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Candidate-JAR-backed public-API contract checks for the isolated Apps fixture.
 * These off-network checks do not establish actual host interoperability or
 * browser enforcement of the resource's CSP and permission hints.
 */
public final class AppsFixtureContractTest {

	private static final String PROTOCOL = "2026-07-28";
	private static final String APPS = "{\"extensions\":{\"io.modelcontextprotocol/ui\":"
			+ "{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}}}";
	private static final String WRONG_MIME = "{\"extensions\":{\"io.modelcontextprotocol/ui\":"
			+ "{\"mimeTypes\":[\"text/html\"]}}}";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final int EXPECTED_CASES = 12;
	private static final int MAXIMUM_REQUESTS = 60;
	private static final String TOKEN_EN = "disposable-contract-en";
	private static final String TOKEN_PT = "disposable-contract-pt";
	private static final String TOKEN_AR = "disposable-contract-ar";
	private static final String TOKEN_DENIED = "disposable-contract-denied";
	private static final String TOKEN_MUTABLE = "disposable-contract-mutable";
	private static final String TENANT_A = "alpha";
	private static final String TENANT_B = "beta";
	private final String shell;
	private final AppsFixture fixture;
	private int cases;
	private int requests;

	private AppsFixtureContractTest(String shell) {
		this.shell = shell;
		this.fixture = new AppsFixture(shell, Map.of(
				TOKEN_EN, caller("alice", TENANT_A, "en-US", true),
				TOKEN_PT, caller("beatriz", TENANT_B, "pt-BR", true),
				TOKEN_AR, caller("amal", TENANT_A, "ar", true),
				TOKEN_DENIED, caller("denied", TENANT_B, "en-US", false),
				TOKEN_MUTABLE, caller("mutable", TENANT_A, "en-US", true)));
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 1)
			throw new IllegalArgumentException("Expected the packaged catalog.html path.");
		String shell = Files.readString(Path.of(arguments[0]), StandardCharsets.UTF_8);
		check(!shell.isBlank(), "The packaged shell must not be blank.");
		AppsFixtureContractTest suite = new AppsFixtureContractTest(shell);
		SokletSimulator.run(suite.fixture.simulatorConfig(), suite::run);
		equal(EXPECTED_CASES, suite.cases, "The complete fixed scenario set must execute.");
		System.out.println("{\"status\":\"PASS\",\"cases\":" + EXPECTED_CASES
				+ ",\"requests\":" + suite.requests + ",\"scope\":\"candidate-public-api-simulator\"}");
	}

	private void run(Simulator simulator) {
		authorizedPrefetchBeforeAnyToolCall(simulator);
		capabilityOnCatalog(simulator);
		capabilityOffCatalogAndFallback(simulator);
		wrongMimeDoesNotEnableAppOnlyCalls(simulator);
		englishResultIsSanitized(simulator);
		portugueseAndArabicResults(simulator);
		appOnlyFollowupUsesCurrentCaller(simulator);
		requestArgumentsCannotOverrideIdentity(simulator);
		deniedCallerCannotDiscoverOrRead(simulator);
		invalidAndRevokedTokensAreRejected(simulator);
		repeatedIdentityChangesDoNotReuseTenantOrLocale(simulator);
		staticShellDoesNotVaryByCallerOrLocale(simulator);
	}

	private void authorizedPrefetchBeforeAnyToolCall(Simulator simulator) {
		Capture listing = execute(simulator, "resources/list", "", APPS, TOKEN_EN);
		success(listing);
		contains(listing.body(), "\"uri\":\"" + AppsFixture.UI_URI + "\"",
				"The authorized resource must be listed before any tool invocation.");
		Capture resource = read(simulator, TOKEN_EN, AppsFixture.UI_URI.toString());
		success(resource);
		equal(shell, stringField(resource.body(), "text"), "Prefetch must return the complete static shell.");
		equal(AppsFixture.MIME, stringField(resource.body(), "mimeType"), "Exact Apps MIME is required.");
		contains(resource.body(), "\"csp\":{", "An explicit empty CSP must be projected.");
		for (String allowlist : List.of("connectDomains", "resourceDomains", "frameDomains", "baseUriDomains"))
			contains(resource.body(), "\"" + allowlist + "\":[]", "CSP allowlists must all be empty.");
		absent(resource.body(), "\"camera\"", "The shell must not request camera permission.");
		absent(resource.body(), "\"microphone\"", "The shell must not request microphone permission.");
		absent(resource.body(), "\"geolocation\"", "The shell must not request location permission.");
		absent(resource.body(), "\"clipboardWrite\"", "The shell must not request clipboard permission.");
		++cases;
	}

	private void capabilityOnCatalog(Simulator simulator) {
		Capture result = execute(simulator, "tools/list", "", APPS, TOKEN_EN);
		success(result);
		contains(result.body(), "\"name\":\"" + AppsFixture.TOOL + "\"", "Model tool must be listed.");
		contains(result.body(), "\"name\":\"" + AppsFixture.REFRESH + "\"", "App follow-up must be listed.");
		equal(AppsFixture.UI_URI.toString(), stringField(result.body(), "resourceUri"),
				"The model tool must reference the exact versioned UI resource.");
		equal(1, occurrences(result.body(), "\"resourceUri\""),
				"The app-only follow-up must not recursively reference the UI shell.");
		contains(result.body(), "\"visibility\":[\"app\"]", "Follow-up visibility must be app-only.");
		equal(2, occurrences(result.body(), "\"additionalProperties\":false"),
				"Both no-argument tools must reject identity-spoofing input fields.");
		++cases;
	}

	private void capabilityOffCatalogAndFallback(Simulator simulator) {
		Capture listing = execute(simulator, "tools/list", "", "{}", TOKEN_PT);
		success(listing);
		equal("Mostrar catálogo", stringField(listing.body(), "title"),
				"Fallback catalog titles must follow the admitted locale, not Accept-Language.");
		contains(listing.body(), AppsFixture.TOOL, "The ordinary model tool must remain available.");
		absent(listing.body(), AppsFixture.REFRESH, "The app-only tool must be absent without Apps.");
		absent(listing.body(), "resourceUri", "Apps hints must not leak into the fallback catalog.");
		Capture result = call(simulator, AppsFixture.TOOL, "{}", "{}", TOKEN_PT);
		assertToolResult(result, "pt-BR", "ltr", TENANT_B);
		String fallback = stringField(result.body(), "text");
		equal(stringField(result.body(), "summary"), fallback,
				"The text fallback must contain only the localized safe summary.");
		absent(fallback, "example/view", "Result metadata must not be promoted into model text.");
		absent(fallback, "ui://", "The shell URI must not be promoted into model text.");
		absent(fallback, "<html", "The shell must not be promoted into model text.");
		++cases;
	}

	private void wrongMimeDoesNotEnableAppOnlyCalls(Simulator simulator) {
		Capture listing = execute(simulator, "tools/list", "", WRONG_MIME, TOKEN_EN);
		success(listing);
		absent(listing.body(), AppsFixture.REFRESH, "Generic HTML must not enable Apps projection.");
		Capture wrong = call(simulator, AppsFixture.REFRESH, "{}", WRONG_MIME, TOKEN_EN);
		applicationError(wrong);
		Capture absent = call(simulator, AppsFixture.REFRESH, "{}", "{}", TOKEN_EN);
		applicationError(absent);
		equal(numberField(wrong.body(), "code"), numberField(absent.body(), "code"),
				"Missing Apps and a wrong MIME must follow the same capability gate.");
		absent(wrong.body(), "structuredContent", "Rejected follow-ups must not return tenant data.");
		++cases;
	}

	private void englishResultIsSanitized(Simulator simulator) {
		assertToolResult(call(simulator, AppsFixture.TOOL, "{}", APPS, TOKEN_EN), "en-US", "ltr", TENANT_A);
		Capture listing = execute(simulator, "tools/list", "", APPS, TOKEN_EN);
		success(listing);
		equal("Show catalog", stringField(listing.body(), "title"), "English catalog title must be localized consistently.");
		++cases;
	}

	private void portugueseAndArabicResults(Simulator simulator) {
		Capture portuguese = call(simulator, AppsFixture.TOOL, "{}", APPS, TOKEN_PT);
		Capture arabic = call(simulator, AppsFixture.TOOL, "{}", APPS, TOKEN_AR);
		assertToolResult(portuguese, "pt-BR", "ltr", TENANT_B);
		assertToolResult(arabic, "ar", "rtl", TENANT_A);
		check(!stringField(portuguese.body(), "title").equals(stringField(arabic.body(), "title")),
				"The presentation strings must actually vary with the admitted locale.");
		Capture listing = execute(simulator, "tools/list", "", APPS, TOKEN_AR);
		success(listing);
		equal("عرض الكتالوج", stringField(listing.body(), "title"),
				"Arabic catalog titles must follow the admitted locale, not Accept-Language.");
		++cases;
	}

	private void appOnlyFollowupUsesCurrentCaller(Simulator simulator) {
		assertToolResult(call(simulator, AppsFixture.REFRESH, "{}", APPS, TOKEN_AR), "ar", "rtl", TENANT_A);
		++cases;
	}

	private void requestArgumentsCannotOverrideIdentity(Simulator simulator) {
		for (String tool : List.of(AppsFixture.TOOL, AppsFixture.REFRESH)) {
			Capture result = call(simulator, tool,
					"{\"tenant\":\"contract-attacker\",\"locale\":\"ar\",\"subject\":\"attacker\"}",
					APPS, TOKEN_EN);
			applicationError(result);
			absent(result.body(), "structuredContent", "Unexpected arguments must not produce tool data.");
		}
		assertToolResult(call(simulator, AppsFixture.TOOL, "{}", APPS, TOKEN_EN), "en-US", "ltr", TENANT_A);
		++cases;
	}

	private void deniedCallerCannotDiscoverOrRead(Simulator simulator) {
		Capture tools = execute(simulator, "tools/list", "", APPS, TOKEN_DENIED);
		success(tools);
		contains(tools.body(), "\"tools\":[]", "Denied callers must receive an empty tool catalog.");
		Capture resources = execute(simulator, "resources/list", "", APPS, TOKEN_DENIED);
		success(resources);
		contains(resources.body(), "\"resources\":[]", "Denied callers must receive an empty resource catalog.");
		Capture deniedTool = call(simulator, AppsFixture.TOOL, "{}", APPS, TOKEN_DENIED);
		Capture unknownTool = call(simulator, "unknown_catalog", "{}", APPS, TOKEN_DENIED);
		applicationError(deniedTool);
		applicationError(unknownTool);
		equal(unknownTool.body(), deniedTool.body(), "A denied tool must be indistinguishable from an unknown one.");
		Capture deniedRead = read(simulator, TOKEN_DENIED, AppsFixture.UI_URI.toString());
		Capture unknownRead = read(simulator, TOKEN_DENIED, "ui://soklet/unknown");
		applicationError(deniedRead);
		applicationError(unknownRead);
		equal(-31904L, numberField(deniedRead.body(), "code"),
				"Direct URI reads must independently apply the fixture's resource authorization.");
		absent(deniedRead.body(), "contents", "A denied direct resource read must not return the shell.");
		absent(deniedRead.body(), shell, "The shell must not leak in an authorization error.");
		absent(deniedRead.body(), TENANT_A, "A denied read must not expose tenant data.");
		absent(deniedRead.body(), TENANT_B, "A denied read must not expose tenant data.");
		absent(deniedRead.body(), "_meta", "A denied read must not expose resource metadata.");
		++cases;
	}

	private void invalidAndRevokedTokensAreRejected(Simulator simulator) {
		Capture invalid = call(simulator, AppsFixture.TOOL, "{}", APPS, "not-a-fixture-token");
		equal(401, invalid.status(), "Unrecognized bearer credentials must fail admission.");
		fixture.revoke(TOKEN_MUTABLE);
		Capture revokedTool = call(simulator, AppsFixture.TOOL, "{}", APPS, TOKEN_MUTABLE);
		Capture revokedRead = read(simulator, TOKEN_MUTABLE, AppsFixture.UI_URI.toString());
		equal(401, revokedTool.status(), "Revocation must affect the next tool request.");
		equal(401, revokedRead.status(), "Revocation must affect direct shell reads too.");
		absent(revokedTool.body(), "structuredContent", "Revoked callers must not receive tenant data.");
		absent(revokedRead.body(), "contents", "Revoked callers must not receive the shell.");
		++cases;
	}

	private void repeatedIdentityChangesDoNotReuseTenantOrLocale(Simulator simulator) {
		List<AppsFixture.Caller> identities = List.of(
				caller("first", TENANT_A, "en-US", true),
				caller("second", TENANT_B, "pt-BR", true),
				caller("third", TENANT_A, "ar", true));
		for (int index = 0; index < 3; ++index) {
			AppsFixture.Caller identity = identities.get(index);
			fixture.setCaller(TOKEN_MUTABLE, identity);
			Capture result = call(simulator, AppsFixture.REFRESH, "{}", APPS, TOKEN_MUTABLE);
			assertToolResult(result, identity.locale(), index == 2 ? "rtl" : "ltr", identity.tenant());
			absent(result.body(), identity.tenant().equals(TENANT_A) ? TENANT_B : TENANT_A,
					"A previous caller's tenant must not survive a later request.");
		}
		fixture.setCaller(TOKEN_MUTABLE, caller("same-token-now-denied", TENANT_A, "en-US", false));
		applicationError(call(simulator, AppsFixture.REFRESH, "{}", APPS, TOKEN_MUTABLE));
		applicationError(read(simulator, TOKEN_MUTABLE, AppsFixture.UI_URI.toString()));
		++cases;
	}

	private void staticShellDoesNotVaryByCallerOrLocale(Simulator simulator) {
		for (String token : List.of(TOKEN_EN, TOKEN_PT, TOKEN_AR)) {
			Capture resource = read(simulator, token, AppsFixture.UI_URI.toString());
			success(resource);
			String content = stringField(resource.body(), "text");
			equal(shell, content, "Resource bytes must stay identical across callers, tenants and locales.");
			absent(content, "Catalog alpha: 1 item.", "The static shell must not embed an English personalized result.");
			absent(content, "Catálogo beta: 1 item.", "The static shell must not embed a Portuguese personalized result.");
			absent(content, "الكتالوج alpha: عنصر واحد.", "The static shell must not embed an Arabic personalized result.");
			absent(content, TOKEN_EN, "The static shell must contain no bearer credentials.");
			absent(content, "alice", "The static shell must contain no admitted subject.");
			absent(content, AppsFixture.RAW_CANARY, "The static shell must contain no private canary.");
		}
		++cases;
	}

	private static void assertToolResult(Capture result, String locale, String direction, String tenant) {
		success(result);
		contains(result.body(), "\"structuredContent\":", "The App must receive structured data.");
		equal(locale, stringField(result.body(), "locale"), "Locale must come from the current admitted identity.");
		equal(direction, stringField(result.body(), "direction"), "Text direction must follow the admitted locale.");
		equal(tenant, stringField(result.body(), "tenant"), "Tenant must come from the current admitted identity.");
		for (String field : List.of("title", "refreshLabel", "summary", "itemLabel"))
			check(!stringField(result.body(), field).isBlank(), "The localized " + field + " must be populated.");
		equal("USD", stringField(result.body(), "currency"), "The currency must be explicit.");
		equal("UTC", stringField(result.body(), "timeZone"), "The time zone must be explicit.");
		equal("2026-09-19T12:00:00Z", stringField(result.body(), "updatedAt"), "Fixture time must be deterministic.");
		contains(result.body(), "\"amount\":1234.5", "Numeric amounts must not be preformatted on the server.");
		contains(result.body(), "\"example/view\"", "The sanitizer must retain the public result metadata marker.");
		absent(result.body(), "example/private", "The sanitizer must remove private result metadata.");
		absent(result.body(), AppsFixture.RAW_CANARY, "The sanitizer must remove private result content.");
		equal(stringField(result.body(), "summary"), stringField(result.body(), "text"),
				"The sanitized fallback must remain a localized summary, not UI metadata or private content.");
	}

	private Capture read(Simulator simulator, String token, String uri) {
		return execute(simulator, "resources/read", ",\"uri\":\"" + uri + "\"", APPS, token);
	}

	private Capture call(Simulator simulator, String tool, String arguments, String capabilities, String token) {
		return execute(simulator, "tools/call", ",\"name\":\"" + tool + "\",\"arguments\":" + arguments,
				capabilities, token);
	}

	private Capture execute(Simulator simulator, String method, String parameters, String capabilities, String token) {
		check(++requests <= MAXIMUM_REQUESTS, "The fixed suite request bound must not be exceeded.");
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"apps-contract\",\"method\":\"" + method
				+ "\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":" + capabilities + "}" + parameters + "}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>(Map.of(
				"Host", Set.of("127.0.0.1:0"), "Authorization", Set.of("Bearer " + token),
				"Content-Type", Set.of("application/json"), "Accept", Set.of("application/json, text/event-stream"),
				"MCP-Protocol-Version", Set.of(PROTOCOL), "Mcp-Method", Set.of(method),
				// Intentionally contradict the admitted identity: this must not select the locale.
				"Accept-Language", Set.of("de-DE")));
		if (method.equals("tools/call"))
			headers.put("Mcp-Name", Set.of(stringField("{" + parameters.substring(1) + "}", "name")));
		else if (method.equals("resources/read"))
			headers.put("Mcp-Name", Set.of(stringField("{" + parameters.substring(1) + "}", "uri")));
		Request request = Request.withPath(HttpMethod.POST, AppsFixture.PATH).headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = simulation.awaitResponse(WAIT)
					.orElseThrow(() -> new AssertionError("The fixture response exceeded the five-second bound."));
			String responseBody = new String(response.getBody()
					.orElseThrow(() -> new AssertionError("Expected a bounded nonstreaming JSON response.")),
					StandardCharsets.UTF_8);
			McpSimulationCompletion completion = simulation.awaitCompletion(WAIT)
					.orElseThrow(() -> new AssertionError("The fixture completion exceeded the five-second bound."));
			check(completion.getThrowables().isEmpty(), "Fixture requests must not produce terminal failures.");
			boolean noStore = response.getHeaders().entrySet().stream()
					.filter(entry -> entry.getKey().equalsIgnoreCase("Cache-Control"))
					.flatMap(entry -> entry.getValue().stream()).anyMatch(value -> value.contains("no-store"));
			check(noStore, "All fixture responses must prevent shared-cache reuse.");
			absent(responseBody, AppsFixture.RAW_CANARY, "No HTTP response may expose the private fixture canary.");
			return new Capture(response.getStatusCode(), responseBody);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted during the bounded fixture request.", exception);
		}
	}

	private static AppsFixture.Caller caller(String subject, String tenant, String locale, boolean allowed) {
		return new AppsFixture.Caller(subject, tenant, locale, allowed);
	}

	private static void success(Capture capture) {
		equal(200, capture.status(), "Successful fixture requests must use HTTP 200.");
		absent(capture.body(), "\"error\":", "Expected a successful JSON-RPC result.");
		contains(capture.body(), "\"result\":", "Expected a JSON-RPC result envelope.");
	}

	private static void applicationError(Capture capture) {
		contains(capture.body(), "\"error\":", "Expected a JSON-RPC application error.");
		absent(capture.body(), "\"structuredContent\":", "Errors must not expose successful tool output.");
	}

	/** Minimal field extraction for deterministic fixture responses, not a general JSON codec. */
	private static String stringField(String json, String field) {
		int start = valueStart(json, field);
		check(start < json.length() && json.charAt(start) == '"', "Expected a string field: " + field);
		StringBuilder value = new StringBuilder();
		for (int index = start + 1; index < json.length(); ++index) {
			char current = json.charAt(index);
			if (current == '"')
				return value.toString();
			if (current != '\\') {
				value.append(current);
				continue;
			}
			check(++index < json.length(), "Incomplete JSON string escape.");
			char escaped = json.charAt(index);
			switch (escaped) {
				case '"', '\\', '/' -> value.append(escaped);
				case 'b' -> value.append('\b');
				case 'f' -> value.append('\f');
				case 'n' -> value.append('\n');
				case 'r' -> value.append('\r');
				case 't' -> value.append('\t');
				case 'u' -> {
					check(index + 4 < json.length(), "Incomplete JSON unicode escape.");
					value.append((char) Integer.parseInt(json.substring(index + 1, index + 5), 16));
					index += 4;
				}
				default -> throw new AssertionError("Unexpected JSON string escape.");
			}
		}
		throw new AssertionError("Unterminated JSON string field: " + field);
	}

	private static long numberField(String json, String field) {
		int start = valueStart(json, field);
		int end = start;
		for (; end < json.length(); ++end) {
			char current = json.charAt(end);
			if (current != '-' && (current < '0' || current > '9'))
				break;
		}
		check(end > start, "Expected an integer field: " + field);
		return Long.parseLong(json.substring(start, end));
	}

	private static int valueStart(String json, String field) {
		String key = "\"" + field + "\"";
		for (int offset = 0; offset < json.length();) {
			int match = json.indexOf(key, offset);
			check(match >= 0, "Missing JSON field: " + field);
			int start = match + key.length();
			offset = start;
			for (; start < json.length() && Character.isWhitespace(json.charAt(start)); ++start) { }
			// For example, content.type="text" precedes the actual content.text key.
			if (start >= json.length() || json.charAt(start) != ':')
				continue;
			++start;
			for (; start < json.length() && Character.isWhitespace(json.charAt(start)); ++start) { }
			return start;
		}
		throw new AssertionError("Missing JSON field: " + field);
	}

	private static int occurrences(String text, String needle) {
		int count = 0;
		for (int offset = 0; offset < text.length();) {
			int match = text.indexOf(needle, offset);
			if (match < 0)
				break;
			++count;
			offset = match + needle.length();
		}
		return count;
	}

	private static void contains(String actual, String expected, String message) {
		check(actual.contains(expected), message);
	}

	private static void absent(String actual, String unexpected, String message) {
		check(!actual.contains(unexpected), message);
	}

	private static void equal(Object expected, Object actual, String message) {
		check(expected.equals(actual), message);
	}

	private static void check(boolean condition, String message) {
		if (!condition)
			throw new AssertionError(message);
	}

	private record Capture(int status, String body) { }
}
