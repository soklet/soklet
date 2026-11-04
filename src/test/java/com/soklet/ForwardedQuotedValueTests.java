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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.soklet.EffectiveOriginResolver.TrustPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class ForwardedQuotedValueTests {
	private static final InetAddress PEER = address(10, 0, 0, 3);
	private static final InetAddress PROXY = address(10, 0, 0, 2);
	private static final InetAddress CLIENT = address(198, 51, 100, 7);
	private static final InetAddress SPOOF = address(198, 51, 100, 66);

	@Test
	void quotedPairsDecodeBeforeOriginAndClientIpValidation() {
		Map<String, List<String>> headers = headers(List.of(
				"for=\"198.51.100.\\7\";host=\"exa\\mple.com:\\8443\";proto=\"h\\ttps\""));
		for (EffectiveOriginResolver.TrustPolicy policy : List.of(TRUST_ALL, TRUST_PROXY_ALLOWLIST)) {
			assertEquals(Optional.of("https://example.com:8443"), origin(headers, policy, true));
			assertEquals(Optional.of(CLIENT), clientIp(headers, policy, true));
		}

		Map<String, List<String>> ipv6 = headers(List.of(
				"for=\"[2001:db8::\\7]:443\";host=\"[2001:db8::\\1]:8443\";proto=\"h\\ttps\""));
		assertEquals(Optional.of("https://[2001:db8::1]:8443"), origin(ipv6, TRUST_PROXY_ALLOWLIST, true));
		assertEquals("2001:db8:0:0:0:0:0:7", clientIp(ipv6, TRUST_PROXY_ALLOWLIST, true).orElseThrow().getHostAddress());
	}

	@Test
	void validQuotedMetadataPreservesNearestAndLeftmostTrustPolicies() {
		String prefix = "for=198.51.100.66;host=spoof.example;proto=http";
		String nearest = "for=\"198.51.100.\\7\";host=\"exa\\mple.com:8443\";proto=\"h\\ttps\";ext=\"comma\\,semi\\;quote\\\"\"";
		for (List<String> forwarded : List.of(List.of(prefix + ", " + nearest), List.of(prefix, nearest))) {
			Map<String, List<String>> headers = headers(forwarded);
			assertEquals(Optional.of("https://example.com:8443"), origin(headers, TRUST_PROXY_ALLOWLIST, true));
			assertEquals(Optional.of(CLIENT), clientIp(headers, TRUST_PROXY_ALLOWLIST, true));
			assertEquals(Optional.of("http://spoof.example"), origin(headers, TRUST_ALL, true));
			assertEquals(Optional.of(SPOOF), clientIp(headers, TRUST_ALL, true));
			assertTrue(origin(headers, TRUST_NONE, true).isEmpty());
			assertEquals(Optional.of(PEER), clientIp(headers, TRUST_NONE, true));
			assertTrue(origin(headers, TRUST_PROXY_ALLOWLIST, false).isEmpty());
			assertEquals(Optional.of(PEER), clientIp(headers, TRUST_PROXY_ALLOWLIST, false));
		}
	}

	@Test
	void malformedClientPrefixCannotSwallowAppendedProxyAuthority() {
		for (String extension : List.of("ext", "by", "unrecognized")) {
			String prefix = "for=198.51.100.66;host=spoof.example;proto=http;" + extension + "=\"unterminated";
			for (String appended : List.of(
					"for=198.51.100.7;host=example.com;proto=https",
					"for=\"198.51.100.7:443\";host=\"example.com:8443\";proto=\"https\"")) {
				for (String earlier : List.of("", "for=192.0.2.10;host=earlier-spoof.example;proto=http, ")) {
					Map<String, List<String>> coalesced = headers(List.of(earlier + prefix + ", " + appended));
					assertTrue(origin(coalesced, TRUST_PROXY_ALLOWLIST, true).isEmpty());
					assertEquals(Optional.of(PEER), clientIp(coalesced, TRUST_PROXY_ALLOWLIST, true));
					assertTrue(origin(coalesced, TRUST_NONE, true).isEmpty());
					assertEquals(Optional.of(PEER), clientIp(coalesced, TRUST_NONE, true));
				}

				Map<String, List<String>> separate = headers(List.of(prefix, appended));
				String expected = appended.contains("8443") ? "https://example.com:8443" : "https://example.com";
				assertEquals(Optional.of(expected), origin(separate, TRUST_PROXY_ALLOWLIST, true));
				assertEquals(Optional.of(CLIENT), clientIp(separate, TRUST_PROXY_ALLOWLIST, true));
			}
		}
	}

	@Test
	void invalidUnknownParametersCannotLaunderAValidPrefixOrFallBackToXForwardedFor() {
		for (String invalid : List.of("\"unterminated", "\"dangling\\\"", "\"ok\"junk", "unquoted\\escape",
				"\"line\rbreak\"", "\"line\nbreak\"", "\"zero\u0000byte\"", "\"delete\u007fbyte\"")) {
			for (String entry : List.of(
					"for=198.51.100.66;host=spoof.example;proto=http;ext=" + invalid,
					"ext=" + invalid)) {
				Map<String, List<String>> headers = headers(List.of(entry));
				assertTrue(origin(headers, TRUST_PROXY_ALLOWLIST, true).isEmpty(), entry);
				assertEquals(Optional.of(PEER), clientIp(headers, TRUST_PROXY_ALLOWLIST, true), entry);
			}
		}
	}

	@Test
	void validEmptyElementsAndOpaqueQuotedExtensionsRemainSupported() {
		Map<String, List<String>> headers = headers(List.of(
				";;for=\"198.51.100.\\7\";;host=\"exa\\mple.com\";proto=\"h\\ttps\";ext=\"comma\\,semi\\;quote\\\"slash\\\\\ttext\";;"));
		assertEquals(Optional.of("https://example.com"), origin(headers, TRUST_PROXY_ALLOWLIST, true));
		assertEquals(Optional.of(CLIENT), clientIp(headers, TRUST_PROXY_ALLOWLIST, true));
	}

	@Test
	void decodedNodeAndHostValuesAreNotUnquotedOrTrimmedAgain() {
		for (String value : List.of("\"\\\"198.51.100.7\\\"\"", "\" 198.51.100.7 \"", "'198.51.100.7'")) {
			Map<String, List<String>> headers = headers(List.of("for=" + value + ";host=example.com;proto=https"));
			assertEquals(Optional.of(PEER), clientIp(headers, TRUST_PROXY_ALLOWLIST, true), value);
		}
		for (String value : List.of("\"\\\"spoof.example\\\"\"", "\" spoof.example \"", "\"spoof.example\t\"")) {
			Map<String, List<String>> headers = headers(List.of("for=198.51.100.7;host=" + value + ";proto=https"));
			assertEquals(Optional.of("https://internal.example"), origin(headers, TRUST_PROXY_ALLOWLIST, true), value);
		}
	}

	@Test
	void decodedProtocolsMustRemainUriSchemes() {
		for (String invalid : List.of("\"https\\://evil.example\"", "\"1https\"", "\"https\\;host=evil.example\"", "\"\\\"https\\\"\"")) {
			assertTrue(origin(headers(List.of("for=198.51.100.7;host=example.com;proto=" + invalid)),
					TRUST_PROXY_ALLOWLIST, true).isEmpty(), invalid);
		}
		assertEquals(Optional.of("custom+scheme-1.0://example.com"), origin(headers(List.of(
				"for=198.51.100.7;host=example.com;proto=\"custom+scheme-1.0\"")), TRUST_PROXY_ALLOWLIST, true));
	}

	@Test
	void decodedUnknownHopStillStopsTheTrustedChainWalk() {
		Map<String, List<String>> headers = headers(List.of("for=198.51.100.66, for=\"unk\\nown\", for=10.0.0.2"));
		assertEquals(Optional.of(PEER), clientIp(headers, TRUST_PROXY_ALLOWLIST, true));
		assertEquals(Optional.of(SPOOF), clientIp(headers, TRUST_ALL, true));
	}

	@Test
	void malformedNearestForwardedEntryDisablesLegacyOriginFallbacks() {
		String proxyEntry = "for=198.51.100.7;host=example.com;proto=https";
		for (String invalid : List.of("ext=\"unterminated", "ext=\"dangling\\\"", "ext=\"ok\"junk")) {
			String clientEntry = "for=198.51.100.66;host=spoof.example;proto=http;" + invalid;
			Map<String, List<String>> headers = new LinkedHashMap<>(Map.of(
					"Host", List.of("internal.example"),
					"X-Forwarded-Host", List.of("spoof.example"),
					"X-Forwarded-Proto", List.of("https"),
					"X-Forwarded-Protocol", List.of("https"),
					"X-Url-Scheme", List.of("https"),
					"Front-End-Https", List.of("on"),
					"X-Forwarded-Ssl", List.of("on"),
					"X-Forwarded-Port", List.of("8443")));
			for (List<String> forwarded : List.of(List.of(clientEntry), List.of(proxyEntry, clientEntry))) {
				headers.put("Forwarded", forwarded);
				assertTrue(origin(headers, TRUST_PROXY_ALLOWLIST, true).isEmpty(), forwarded.toString());
			}

			// A proxy's separate, valid nearest field is still authoritative.
			headers.put("Forwarded", List.of(clientEntry, proxyEntry));
			assertEquals(Optional.of("https://example.com:8443"), origin(headers, TRUST_PROXY_ALLOWLIST, true));
		}
		Map<String, List<String>> swallowed = Map.of("Host", List.of("internal.example"),
				"Forwarded", List.of("for=198.51.100.66;ext=\"unterminated, " + proxyEntry),
				"X-Forwarded-Host", List.of("spoof.example"), "X-Forwarded-Proto", List.of("https"));
		assertTrue(origin(swallowed, TRUST_PROXY_ALLOWLIST, true).isEmpty());
	}

	@Test
	void legacyOriginProtocolsMustBeUriSchemes() {
		for (String header : List.of("X-Forwarded-Proto", "X-Forwarded-Protocol", "X-Url-Scheme")) {
			for (EffectiveOriginResolver.TrustPolicy policy : List.of(TRUST_ALL, TRUST_PROXY_ALLOWLIST)) {
				for (String invalid : List.of("https://spoof.example/?", "https:", "1https", "https;host=spoof.example",
						"\"https", "\"\"", "h\u0442tps", "h\\ttps", "http\tsecure")) {
					Map<String, List<String>> headers = Map.of("Host", List.of("example.com"), header, List.of(invalid));
					assertTrue(origin(headers, policy, true).isEmpty(), header + ": " + invalid);
				}
				for (String valid : List.of("http", "https", "HTTPS", "custom+scheme-1.0")) {
					for (String value : List.of(valid, "\"" + valid + "\"", "'" + valid + "'")) {
						Map<String, List<String>> headers = Map.of("Host", List.of("example.com"), header, List.of(value));
						assertEquals(Optional.of(valid + "://example.com"), origin(headers, policy, true), header + ": " + value);
						assertTrue(origin(headers, TRUST_NONE, true).isEmpty());
						assertTrue(origin(headers, TRUST_PROXY_ALLOWLIST, false).isEmpty());
					}
				}
			}
		}
	}

	@Test
	void invalidLegacySchemeDoesNotShadowValidAlternateOrOverrideForwarded() {
		for (EffectiveOriginResolver.TrustPolicy policy : List.of(TRUST_ALL, TRUST_PROXY_ALLOWLIST)) {
			Map<String, List<String>> headers = new LinkedHashMap<>(Map.of("Host", List.of("example.com"),
					"X-Forwarded-Proto", List.of("https://spoof.example/?"),
					"X-Forwarded-Protocol", List.of("https")));
			assertEquals(Optional.of("https://example.com"), origin(headers, policy, true));
			headers.put("Forwarded", List.of("for=198.51.100.7;host=proxy.example;proto=http"));
			assertEquals(Optional.of("http://proxy.example"), origin(headers, policy, true));
		}
		Map<String, List<String>> invalidNearest = Map.of("Host", List.of("example.com"),
				"X-Forwarded-Proto", List.of("https, https://spoof.example/?"));
		assertTrue(origin(invalidNearest, TRUST_PROXY_ALLOWLIST, true).isEmpty());
		assertEquals(Optional.of("https://example.com"), origin(invalidNearest, TRUST_ALL, true));
	}

	@Test
	void legacyXForwardedFieldsRetainTheirExistingQuoteAndEscapeInterpretation() {
		Map<String, List<String>> escaped = Map.of("Host", List.of("internal.example"),
				"X-Forwarded-Host", List.of("\"exa\\mple.com\""), "X-Forwarded-Proto", List.of("https"),
				"X-Forwarded-For", List.of("\"198.51.100.\\7\""));
		assertEquals(Optional.of("https://internal.example"), origin(escaped, TRUST_PROXY_ALLOWLIST, true));
		assertEquals(Optional.of(PEER), clientIp(escaped, TRUST_PROXY_ALLOWLIST, true));

		Map<String, List<String>> quoted = Map.of("Host", List.of("internal.example"),
				"X-Forwarded-Host", List.of("\"example.com:8443\""), "X-Forwarded-Proto", List.of("\"https\""),
				"X-Forwarded-For", List.of("\"198.51.100.7\""));
		assertEquals(Optional.of("https://example.com:8443"), origin(quoted, TRUST_PROXY_ALLOWLIST, true));
		assertEquals(Optional.of(CLIENT), clientIp(quoted, TRUST_PROXY_ALLOWLIST, true));
	}

	private static Map<String, List<String>> headers(List<String> forwarded) {
		return Map.of("Host", List.of("internal.example"), "Forwarded", forwarded,
				"X-Forwarded-For", List.of("203.0.113.99"));
	}

	private static Optional<String> origin(Map<String, List<String>> headers, EffectiveOriginResolver.TrustPolicy policy, boolean trusted) {
		return EffectiveOriginResolver.withHeaders(headers, policy)
				.remoteAddress(new InetSocketAddress(PEER, 9000))
				.trustedProxyPredicate(address -> trusted && Set.of(PEER, PROXY).contains(address.getAddress())).resolve();
	}

	private static Optional<InetAddress> clientIp(Map<String, List<String>> headers, EffectiveOriginResolver.TrustPolicy policy, boolean trusted) {
		return EffectiveClientIpResolver.withHeaders(headers, policy)
				.remoteAddress(new InetSocketAddress(PEER, 9000))
				.trustedProxyPredicate(address -> trusted && Set.of(PEER, PROXY).contains(address.getAddress())).resolve();
	}

	private static InetAddress address(int first, int second, int third, int fourth) {
		try {
			return InetAddress.getByAddress(new byte[]{(byte) first, (byte) second, (byte) third, (byte) fourth});
		} catch (Exception impossible) {
			throw new AssertionError(impossible);
		}
	}
}
