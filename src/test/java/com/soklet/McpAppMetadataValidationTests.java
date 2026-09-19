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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for non-resolving MCP Apps URI, domain, and CSP-origin validation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpAppMetadataValidationTests {
	@Test
	void resourceUrisPreserveAsciiConcreteIdentifiersWithoutDereferencing() {
		for (String value : List.of("ui://orders/dashboard", "UI://orders/dashboard",
				"ui://orders/dashboard?version=2#details", "ui://orders/%E2%98%83",
				"ui://owner@orders/dashboard")) {
			URI uri = URI.create(value);
			assertSame(uri, McpAppMetadataValidation.requireResourceUri(uri));
		}
	}

	@Test
	void resourceUrisRejectNonUiNonNormalizedNonAsciiAndAuthoritylessValues() {
		for (String value : List.of("https://orders/dashboard", "orders/dashboard",
				"ui:dashboard", "ui:/dashboard", "ui:///dashboard", "ui://orders/a/../b",
				"ui://orders/./dashboard", "ui://orders/☃"))
			assertThrows(IllegalArgumentException.class,
					() -> McpAppMetadataValidation.requireResourceUri(URI.create(value)), value);
		assertThrows(NullPointerException.class,
				() -> McpAppMetadataValidation.requireResourceUri(null));
	}

	@Test
	void domainBaselineUsesOnlyLowercaseAsciiLdhLabelsAndExactLengthBounds() {
		String longest = "a".repeat(63) + "." + "b".repeat(63) + "."
				+ "c".repeat(63) + "." + "d".repeat(61);
		assertEquals(253, longest.length());
		for (String domain : List.of("localhost", "app.example.com", "a-1.example",
				"xn--bcher-kva.example", "123.example", "app.123", longest))
			assertEquals(domain, McpAppMetadataValidation.requireDomain(domain));
		for (String domain : List.of("", "A.example", "a..example", ".example",
				"example.", "-a.example", "a-.example", "a_1.example", "*.example",
				"https://example", "example:443", "u@example", "example/path",
				"example?x", "example#x", "example,other", "example;other", "é.example",
				"example\n", " example", "example\t", "example\u0000", "a".repeat(64),
				longest + "a"))
			assertThrows(IllegalArgumentException.class,
					() -> McpAppMetadataValidation.requireDomain(domain), domain);
		assertThrows(NullPointerException.class, () -> McpAppMetadataValidation.requireDomain(null));
	}

	@Test
	void originSetsAreDefensiveImmutableSnapshotsInAsciiOrder() {
		Set<String> input = new LinkedHashSet<>(List.of("https://z.example",
				"https://a.example:443", "https://a.example", "https://*.example.com"));
		Set<String> result = McpAppMetadataValidation.immutableOrigins(input, false);
		assertEquals(List.of("https://*.example.com", "https://a.example",
				"https://a.example:443", "https://z.example"), new ArrayList<>(result));
		input.clear();
		assertEquals(4, result.size());
		assertThrows(UnsupportedOperationException.class, () -> result.add("https://other.example"));
		assertThrows(UnsupportedOperationException.class, result::clear);
		assertTrue(McpAppMetadataValidation.immutableOrigins(Set.of(), false).isEmpty());
	}

	@Test
	void canonicalHttpsDnsIpv4Ipv6WildcardAndPortOriginsAreAccepted() {
		for (String origin : List.of("https://example.com", "https://localhost",
				"https://*.example.com", "https://a-b.example:1", "https://example.com:65535",
				"https://127.0.0.1", "https://0.0.0.0", "https://255.255.255.255:443",
				"https://[::]", "https://[::1]", "https://[2001:db8::1]:443",
				"https://[2001:db8::]", "https://[1::2:0:0:3:4]",
				"https://[1:2:3:4:5:6:0:8]", "https://[::ffff:192.0.2.1]")) {
			assertEquals(Set.of(origin), McpAppMetadataValidation.immutableOrigins(Set.of(origin), false));
			assertEquals(Set.of(origin), McpAppMetadataValidation.immutableOrigins(Set.of(origin), true));
		}
	}

	@Test
	void insecureOriginsRequireLiteralLoopbackAndSocketsRequireConnectCollection() {
		for (String host : List.of("localhost", "127.0.0.1", "127.2.3.4", "127.255.255.255", "[::1]")) {
			String http = "http://" + host + ":8080";
			String ws = "ws://" + host + ":8080";
			assertEquals(Set.of(http), McpAppMetadataValidation.immutableOrigins(Set.of(http), false));
			assertEquals(Set.of(http), McpAppMetadataValidation.immutableOrigins(Set.of(http), true));
			assertEquals(Set.of(ws), McpAppMetadataValidation.immutableOrigins(Set.of(ws), true));
			assertInvalid(ws, false);
		}
		for (String host : List.of("example.com", "0.0.0.0", "128.0.0.1", "[::]",
				"[::ffff:127.0.0.1]", "sub.localhost", "*.localhost")) {
			assertInvalid("http://" + host, false);
			assertInvalid("http://" + host, true);
			assertInvalid("ws://" + host, true);
		}
		String secureSocket = "wss://socket.example:443";
		assertEquals(Set.of(secureSocket), McpAppMetadataValidation.immutableOrigins(Set.of(secureSocket), true));
		assertInvalid(secureSocket, false);
	}

	@Test
	void originGrammarRejectsCspFragmentsInjectionAndNonCanonicalForms() {
		for (String value : List.of("", "*", "https:", "data:text/html,abc", "blob:https://example.com/id",
				"'self'", "'none'", "'unsafe-inline'", "'unsafe-eval'", "HTTPS://example.com",
				"https://EXAMPLE.com", "https://example.com/", "https://example.com/path",
				"https://example.com?x", "https://example.com#x", "https://u@example.com",
				"https://example.com,https://other.example", "https://example.com;script-src",
				"https://example.com\n", "https://example.com\r", "https://example.com\t",
				"https://example.com\u007f", "https://example.com\u0000", " https://example.com",
				"https://example.com\\path", "https://ex%61mple.com", "https://é.example",
				"https://*.127.0.0.1", "https://*", "https://foo.*.example", "https://**.example",
				"https://*foo.example", "https://example.", "https://.example", "https://a..example",
				"https://-a.example", "https://a-.example", "https://a_b.example", "https://",
				"https://example.com:", "https://example.com:0", "https://example.com:01",
				"https://example.com:+1", "https://example.com:65536", "https://example.com:99999999999",
				"https://example.com:443:1", "https://256.0.0.1", "https://127.00.0.1",
				"https://127.1", "https://2130706433", "https://0x7f.0.0.1", "https://0x7f000001",
				"https://[2001:DB8::1]", "https://[2001:0db8::1]", "https://[0:0:0:0:0:0:0:1]",
				"https://[1:0:0:2::3:4]", "https://[1:2:3:4:5:6::8]", "https://[::ffff:c000:201]",
				"https://[::ffff:192.00.2.1]", "https://[2001:db8::192.0.2.1]",
				"https://[fe80::1%25eth0]", "https://[v1.example]", "https://[::1",
				"https://[::1]/", "https://[::1]:", "https://[::1]x", "https://::1",
				"https://[1:2:3:4:5:6:7]", "https://[1:2:3:4:5:6:7:8:9]",
				"https://[1::2::3]", "https://[:::1]", "https://[1:::]", "https://[]")) {
			assertInvalid(value, false);
			assertInvalid(value, true);
		}
	}

	@Test
	void originDnsBoundariesIncludeTheWildcardLabel() {
		String longest = "a".repeat(63) + "." + "b".repeat(63) + "."
				+ "c".repeat(63) + "." + "d".repeat(61);
		assertEquals(Set.of("https://" + longest),
				McpAppMetadataValidation.immutableOrigins(Set.of("https://" + longest), false));
		assertInvalid("https://" + longest + "a", false);
		assertInvalid("https://" + "a".repeat(64) + ".example", false);
		assertInvalid("https://*." + longest, false);
		String wildcard = "https://*." + longest.substring(2);
		assertEquals(Set.of(wildcard), McpAppMetadataValidation.immutableOrigins(Set.of(wildcard), false));
	}

	@Test
	void nullsAreRejectedAndErrorMessagesDoNotEchoSensitiveInputs() {
		assertThrows(NullPointerException.class,
				() -> McpAppMetadataValidation.immutableOrigins(null, false));
		Set<String> origins = new LinkedHashSet<>(Arrays.asList("https://example.com", null));
		assertThrows(NullPointerException.class,
				() -> McpAppMetadataValidation.immutableOrigins(origins, false));
		String sensitive = "secret-marker";
		IllegalArgumentException originError = assertThrows(IllegalArgumentException.class,
				() -> McpAppMetadataValidation.immutableOrigins(Set.of("https://" + sensitive + "@example.com"), false));
		IllegalArgumentException domainError = assertThrows(IllegalArgumentException.class,
				() -> McpAppMetadataValidation.requireDomain(sensitive + "/"));
		IllegalArgumentException uriError = assertThrows(IllegalArgumentException.class,
				() -> McpAppMetadataValidation.requireResourceUri(URI.create("https://" + sensitive)));
		for (IllegalArgumentException error : List.of(originError, domainError, uriError))
			assertFalse(error.getMessage().contains(sensitive));
	}

	private static void assertInvalid(String origin, boolean connectDomains) {
		assertThrows(IllegalArgumentException.class,
				() -> McpAppMetadataValidation.immutableOrigins(Set.of(origin), connectDomains), origin);
	}
}
