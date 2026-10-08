/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpAdmissionRejectionTests {
	@Test
	void frameworkAndCorsHeadersFailAtEitherSetterWithoutEchoingValues() {
		for (String name : List.of("Cache-Control", "Connection", "Content-Encoding", "Content-Length",
				"Content-Type", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "Proxy-Connection",
				"TE", "Trailer", "Transfer-Encoding", "Upgrade", "Retry-After", "Mcp-Session-Id",
				"Last-Event-Id", "Access-Control-Allow-Origin", "aCcEsS-CoNtRoL-private-name-canary")) {
			for (String spelling : List.of(name, name.toLowerCase(java.util.Locale.ROOT))) {
				assertPrivateFailure(() -> builder().addHeader(spelling, "private-value-canary"));
				assertPrivateFailure(() -> builder().headers(Map.of(spelling, List.of("private-value-canary"))));
			}
		}
	}

	@Test
	void malformedNamesValuesAndEmptyListsFailEagerlyWithPrivateDiagnostics() {
		for (String name : List.of("", " private-name-canary", "private-name-canary:", "private-name-canary\n", "café"))
			assertPrivateFailure(() -> builder().addHeader(name, "private-value-canary"));
		for (String value : List.of("private-value-canary\r", "private-value-canary\n", "private-value-canary\u0000",
				"private-value-canary\u007f", "private-value-canaryé", "private-value-canary🙂"))
			assertPrivateFailure(() -> builder().addHeader("X-private-name-canary", value));
		assertPrivateFailure(() -> builder().headers(Map.of("X-private-name-canary", List.of())));
	}

	@Test
	void duplicateCaseNamesAreRejectedWhileExactNameMultiplicityAndOrderAreRetained() {
		McpAdmissionRejection.Builder builder = builder().addHeader("WWW-Authenticate", "Bearer realm=\"one\"");
		assertPrivateFailure(() -> builder.addHeader("www-authenticate", "Bearer realm=\"private-value-canary\""));
		assertEquals(List.of("Bearer realm=\"one\""), builder.build().getHeaders().get("WWW-Authenticate"));
		builder.addHeader("WWW-Authenticate", "Basic realm=\"two\"");
		assertEquals(List.of("Bearer realm=\"one\"", "Basic realm=\"two\""), builder.build().getHeaders().get("WWW-Authenticate"));
		Map<String, List<String>> duplicate = new LinkedHashMap<>();
		duplicate.put("X-private-name-canary", List.of("one"));
		duplicate.put("x-private-name-canary", List.of("two"));
		assertPrivateFailure(() -> builder.headers(duplicate));
		assertEquals(2, builder.build().getHeaders().get("WWW-Authenticate").size());
	}

	@Test
	void existingCountAndByteLimitsAreInclusiveAndRejectedChangesAreAtomic() {
		McpAdmissionRejection.Builder count = builder().headers(Map.of("X-Test", new ArrayList<>(java.util.Collections.nCopies(100, ""))));
		assertPrivateFailure(() -> count.addHeader("X-Test", ""));
		assertEquals(100, count.build().getHeaders().get("X-Test").size());
		String exact = "a".repeat(65_536 - "X-Test".length() - 4);
		McpAdmissionRejection.Builder bytes = builder().addHeader("X-Test", exact);
		assertPrivateFailure(() -> bytes.addHeader("X-Other", ""));
		assertPrivateFailure(() -> bytes.headers(Map.of("X-Test", List.of(exact + "a"))));
		assertEquals(exact, bytes.build().getHeaders().get("X-Test").get(0));
	}

	@Test
	void acceptedHeadersKeepExactSpellingValuesAndImmutableSnapshots() {
		List<String> values = new ArrayList<>(List.of("", " spaced\tvalue "));
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("X-Test", values);
		headers.put("Vary", List.of("Origin"));
		McpAdmissionRejection.Builder builder = builder().headers(headers);
		values.clear(); headers.clear();
		McpAdmissionRejection first = builder.build();
		assertEquals(List.of("X-Test", "Vary"), List.copyOf(first.getHeaders().keySet()));
		assertEquals(List.of("", " spaced\tvalue "), first.getHeaders().get("X-Test"));
		assertThrows(UnsupportedOperationException.class, () -> first.getHeaders().clear());
		assertThrows(UnsupportedOperationException.class, () -> first.getHeaders().get("X-Test").clear());
		builder.headers(Map.of());
		assertTrue(builder.build().getHeaders().isEmpty());
		assertEquals(2, first.getHeaders().size());
	}

	@Test
	void bearerHelperUsesTheSameValidatedHeaderPath() {
		BearerAuthenticationChallenge challenge = BearerAuthenticationChallenge.withResourceMetadataUri(
				java.net.URI.create("https://example.com/.well-known/oauth-protected-resource")).build();
		McpAdmissionRejection rejection = McpAdmissionRejection.withBearerAuthenticationChallengeAndError(challenge, error()).build();
		assertEquals(challenge.getRecommendedStatusCode(), rejection.getStatusCode());
		assertEquals(List.of(challenge.getHeaderValue()), rejection.getHeaders().get("WWW-Authenticate"));
	}

	private static McpAdmissionRejection.Builder builder() {
		return McpAdmissionRejection.withStatusCodeAndError(401, error());
	}

	private static McpJsonRpcError error() {
		return McpJsonRpcError.fromApplication(-31903, "Authentication required");
	}

	private static void assertPrivateFailure(org.junit.jupiter.api.function.Executable action) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
		assertNotNull(failure.getMessage());
		assertNull(failure.getCause());
		assertFalse(failure.getMessage().contains("private-name-canary"));
		assertFalse(failure.getMessage().contains("private-value-canary"));
	}
}
