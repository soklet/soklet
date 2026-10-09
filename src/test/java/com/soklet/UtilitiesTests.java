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

import com.soklet.EffectiveOriginResolver.TrustPolicy;
import com.soklet.exception.IllegalRequestException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class UtilitiesTests {
	@Test
	public void extractPathFromUrl() {
		assertEquals("/", Utilities.extractPathFromUrl("https://www.google.com/", true));
		assertEquals("/", Utilities.extractPathFromUrl("https://www.google.com", true));
		assertEquals("/", Utilities.extractPathFromUrl("", true));
		assertEquals("/", Utilities.extractPathFromUrl("/", true));
		assertEquals("/test", Utilities.extractPathFromUrl("/test", true));
		assertEquals("/test", Utilities.extractPathFromUrl("/test/", true));
		assertEquals("/test", Utilities.extractPathFromUrl("/test//", true));
	}

	@Test
	public void acceptLanguages() {
		String acceptLanguageHeaderValue = "fr-CH, fr;q=0.9, en;q=0.8, de;q=0.7, *;q=0.5";
		List<Locale> locales = Utilities.extractLocalesFromAcceptLanguageHeaderValue(acceptLanguageHeaderValue);

		assertEquals(List.of(
				Locale.forLanguageTag("fr-CH"),
				Locale.forLanguageTag("fr"),
				Locale.forLanguageTag("en"),
				Locale.forLanguageTag("de")
		), locales, "Locales don't match");

		locales = Utilities.extractLocalesFromAcceptLanguageHeaderValue("");

		assertEquals(List.of(), locales, "Blank locale string mishandled");

		locales = Utilities.extractLocalesFromAcceptLanguageHeaderValue("en_US");

		assertEquals(List.of(), locales, "Invalid locale string mishandled");
	}

	@Test
	public void acceptLanguagesRespectWeights() {
		List<Locale> locales = Utilities.extractLocalesFromAcceptLanguageHeaderValue("fr;q=0.9, en;q=1.0");

		assertEquals(List.of(
				Locale.forLanguageTag("en"),
				Locale.forLanguageTag("fr")
		), locales, "Locales don't match");
	}

	@Test
	public void acceptLanguagesDeduplicate() {
		List<Locale> locales = Utilities.extractLocalesFromAcceptLanguageHeaderValue("en, en;q=0.8");

		assertEquals(List.of(Locale.forLanguageTag("en")), locales, "Locales don't match");
	}

	@Test
	public void acceptLanguagesNormalizeCaseAndWhitespace() {
		List<Locale> locales = Utilities.extractLocalesFromAcceptLanguageHeaderValue(" EN-us , fr ");

		assertEquals(List.of(
				Locale.forLanguageTag("en-US"),
				Locale.forLanguageTag("fr")
		), locales, "Locales don't match");
	}

	@Test
	public void acceptLanguagesSupportLanguageWildcard() {
		List<Locale> locales = Utilities.extractLocalesFromAcceptLanguageHeaderValue("en-*, fr;q=0.9");

		assertEquals(List.of(
				Locale.forLanguageTag("en"),
				Locale.forLanguageTag("fr")
		), locales, "Locales don't match");
	}

	@Test
	public void acceptLanguagesRejectZeroWeight() {
		List<Locale> locales = Utilities.extractLocalesFromAcceptLanguageHeaderValue("en;q=0, fr");

		assertEquals(List.of(Locale.forLanguageTag("fr")), locales, "Locales don't match");
	}

	@Test
	public void acceptLanguagesRejectInvalidQualityValues() {
		List<Locale> locales = Utilities.extractLocalesFromAcceptLanguageHeaderValue("fr;q=0.9,en;q=bogus");

		assertEquals(List.of(), locales, "Malformed locale string mishandled");
	}

	@Test
	public void responseRejectsNonAsciiHeaderName() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				Response.withStatusCode(200)
						.headers(Map.of("X-\u00C4", List.of("1")))
						.build());
	}

	@Test
	public void responseRejectsNonLatin1HeaderValue() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				Response.withStatusCode(200)
						.headers(Map.of("X-Test", List.of("\u2713")))
						.build());
	}

	@Test
	public void effectiveOriginFromHeaders() {
		String effectiveOrigin = extractEffectiveOrigin(Map.of()).orElse(null);
		assertEquals(null, effectiveOrigin, "Client URL prefix erroneously detected from incomplete header data");

		effectiveOrigin = extractEffectiveOrigin(Map.of(
				"Host", List.of("www.soklet.com")
		)).orElse(null);
		assertEquals(null, effectiveOrigin, "Client URL prefix erroneously detected from incomplete header data");

		effectiveOrigin = extractEffectiveOrigin(Map.of(
				"Host", List.of("www.soklet.com:443")
		)).orElse(null);
		assertEquals(null, effectiveOrigin, "Client URL prefix erroneously detected from incomplete header data");

		effectiveOrigin = extractEffectiveOrigin(Map.of(
				"Forwarded", List.of("for=12.34.56.78;host=example.com;proto=https, for=23.45.67.89")
		)).orElse(null);
		assertEquals("https://example.com", effectiveOrigin, "Client URL prefix was not correctly detected");

		effectiveOrigin = extractEffectiveOrigin(Map.of(
				"Host", List.of("www.soklet.com"),
				"X-Forwarded-Proto", List.of("https")
		)).orElse(null);
		assertEquals("https://www.soklet.com", effectiveOrigin, "Client URL prefix was not correctly detected");

		effectiveOrigin = extractEffectiveOrigin(Map.of(
				"Host", List.of("www.soklet.com"),
				"X-Forwarded-Proto", List.of("https")
		)).orElse(null);
		assertEquals("https://www.soklet.com", effectiveOrigin, "Client URL prefix was not correctly detected");

		effectiveOrigin = extractEffectiveOrigin(Map.of(
				"X-Forwarded-Host", List.of("www.soklet.com"),
				"X-Forwarded-Protocol", List.of("https")
		)).orElse(null);
		assertEquals("https://www.soklet.com", effectiveOrigin, "Client URL prefix was not correctly detected");

		effectiveOrigin = extractEffectiveOrigin(Map.of(
				"Host", List.of("internal.soklet.local"),
				"X-Forwarded-Host", List.of("www.soklet.com"),
				"X-Forwarded-Proto", List.of("https")
		)).orElse(null);
		assertEquals("https://www.soklet.com", effectiveOrigin, "Forwarded host should override Host header");

		effectiveOrigin = extractEffectiveOrigin(Map.of(
				"host", List.of("www.soklet.com"),
				"x-forwarded-proto", List.of("https")
		)).orElse(null);
		assertEquals("https://www.soklet.com", effectiveOrigin, "Header names should be treated as case-insensitive");

		effectiveOrigin = extractEffectiveOrigin(Map.of(
				"Host", List.of("www.soklet.com"),
				"X-Forwarded-Proto", List.of("https"),
				"X-Forwarded-Port", List.of("-1")
		)).orElse(null);
		assertEquals("https://www.soklet.com", effectiveOrigin, "Invalid ports should be ignored");
	}

	@Test
	public void effectiveOriginFromHeaders_respectsTrustPolicy() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"Host", List.of("internal.soklet.local"),
				"X-Forwarded-Host", List.of("public.soklet.com"),
				"X-Forwarded-Proto", List.of("https")
		);

		InetSocketAddress remoteAddress = new InetSocketAddress(InetAddress.getByName("203.0.113.10"), 1234);

		assertEquals(Optional.empty(),
				EffectiveOriginResolver.withHeaders(headers, EffectiveOriginResolver.TrustPolicy.TRUST_NONE)
						.remoteAddress(remoteAddress)
						.resolve());

		assertEquals(Optional.empty(),
				EffectiveOriginResolver.withHeaders(headers, EffectiveOriginResolver.TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(remoteAddress)
						.trustedProxyAddresses(Set.of(InetAddress.getByName("203.0.113.11")))
						.resolve());

		assertEquals(Optional.of("https://public.soklet.com"),
				EffectiveOriginResolver.withHeaders(headers, EffectiveOriginResolver.TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(remoteAddress)
						.trustedProxyAddresses(Set.of(InetAddress.getByName("203.0.113.10")))
						.resolve());
	}

	@Test
	public void effectiveOriginFromHeaders_originFallbackHonorsSettings() {
		Map<String, List<String>> headers = Map.of(
				"Origin", List.of("https://api.example.com:8443")
		);

		assertEquals(Optional.empty(),
				EffectiveOriginResolver.withHeaders(headers, EffectiveOriginResolver.TrustPolicy.TRUST_NONE)
						.resolve());

		assertEquals(Optional.of("https://api.example.com:8443"),
				EffectiveOriginResolver.withHeaders(headers, EffectiveOriginResolver.TrustPolicy.TRUST_NONE)
						.allowOriginFallback(true)
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_respectsTrustPolicy() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"X-Forwarded-For", List.of("198.51.100.10")
		);
		InetSocketAddress remoteAddress = remoteAddress("203.0.113.10");

		assertEquals(Optional.of(address("203.0.113.10")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_NONE)
						.remoteAddress(remoteAddress)
						.resolve());

		assertEquals(Optional.of(address("203.0.113.10")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(remoteAddress)
						.trustedProxyAddresses(Set.of(address("203.0.113.11")))
						.resolve());

		assertEquals(Optional.of(address("198.51.100.10")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(remoteAddress)
						.trustedProxyAddresses(Set.of(address("203.0.113.10")))
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_requiresAllowlistForAllowlistPolicy() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"X-Forwarded-For", List.of("198.51.100.10")
		);

		assertThrows(IllegalStateException.class, () ->
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(remoteAddress("203.0.113.10"))
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_usesRightmostUntrustedForwardedForAddress() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"X-Forwarded-For", List.of("198.51.100.10, 10.0.0.2, 10.0.0.3")
		);

		assertEquals(Optional.of(address("198.51.100.10")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(remoteAddress("10.0.0.4"))
						.trustedProxyAddresses(Set.of(
								address("10.0.0.2"),
								address("10.0.0.3"),
								address("10.0.0.4")))
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_doesNotCrossUnknownOrMalformedAllowlistHops() throws Exception {
		for (String invalid : List.of("unknown", "_hidden", "host.example", "bad:port", "")) {
			for (Map<String, List<String>> headers : List.of(
					Map.of("X-Forwarded-For", List.of("198.51.100.10, " + invalid + ", 10.0.0.2")),
					Map.of("Forwarded", List.of("for=198.51.100.10", "for=" + invalid + ", for=10.0.0.2"),
							"X-Forwarded-For", List.of("198.51.100.30")))) {
				assertEquals(Optional.of(address("10.0.0.3")), EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(remoteAddress("10.0.0.3"))
						.trustedProxyAddresses(Set.of(address("10.0.0.2"), address("10.0.0.3"))).resolve());
			}
		}
		assertEquals(Optional.of(address("203.0.113.99")), EffectiveClientIpResolver.withHeaders(Map.of(
				"X-Forwarded-For", List.of("198.51.100.10, unknown, 203.0.113.99, 10.0.0.2")), TrustPolicy.TRUST_PROXY_ALLOWLIST)
				.remoteAddress(remoteAddress("10.0.0.3"))
				.trustedProxyAddresses(Set.of(address("10.0.0.2"), address("10.0.0.3"))).resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_doesNotTrustSpoofedLeftmostForwardedForAddress() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"X-Forwarded-For", List.of("198.51.100.10, 203.0.113.99, 10.0.0.2")
		);

		assertEquals(Optional.of(address("203.0.113.99")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(remoteAddress("10.0.0.3"))
						.trustedProxyAddresses(Set.of(
								address("10.0.0.2"),
								address("10.0.0.3")))
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_returnsLeftmostAddressWhenAllForwardedForAddressesAreTrusted() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"X-Forwarded-For", List.of("10.0.0.1, 10.0.0.2")
		);

		assertEquals(Optional.of(address("10.0.0.1")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(remoteAddress("10.0.0.3"))
						.trustedProxyAddresses(Set.of(
								address("10.0.0.1"),
								address("10.0.0.2"),
								address("10.0.0.3")))
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_prefersForwardedForOverXForwardedFor() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"Forwarded", List.of("for=198.51.100.20; proto=https; host=example.com"),
				"X-Forwarded-For", List.of("198.51.100.30")
		);

		assertEquals(Optional.of(address("198.51.100.20")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_ALL)
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_fallsBackToXForwardedForWhenForwardedForIsInvalid() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"Forwarded", List.of("for=unknown; proto=https; host=example.com, for=_hidden"),
				"X-Forwarded-For", List.of("198.51.100.30")
		);

		assertEquals(Optional.of(address("198.51.100.30")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_ALL)
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_parsesQuotedBracketedIpv6ForwardedForWithPort() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"Forwarded", List.of("for=\"[2001:db8::1]:8443\"; proto=https; host=example.com")
		);

		assertEquals(Optional.of(address("2001:db8::1")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_ALL)
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_skipsUnknownObfuscatedAndMalformedValues() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"X-Forwarded-For", List.of("unknown, _hidden, not-a-host, 198.51.100.40:1234")
		);

		assertEquals(Optional.of(address("198.51.100.40")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_ALL)
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_combinesRepeatedForwardedForHeaderValuesBeforeTrustWalk() throws Exception {
		List<String> forwardedForHeaders = new ArrayList<>();
		forwardedForHeaders.add("198.51.100.10");
		forwardedForHeaders.add("10.0.0.2, 10.0.0.3");
		Map<String, List<String>> headers = Map.of("X-Forwarded-For", forwardedForHeaders);

		assertEquals(Optional.of(address("198.51.100.10")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(remoteAddress("10.0.0.4"))
						.trustedProxyAddresses(Set.of(
								address("10.0.0.2"),
								address("10.0.0.3"),
								address("10.0.0.4")))
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_trustAllUsesLeftmostForwardedForAddress() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"X-Forwarded-For", List.of("198.51.100.11, 203.0.113.11")
		);

		assertEquals(Optional.of(address("198.51.100.11")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_ALL)
						.remoteAddress(remoteAddress("10.0.0.10"))
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_fallsBackToRemoteAddress() throws Exception {
		assertEquals(Optional.of(address("203.0.113.50")),
				EffectiveClientIpResolver.withHeaders(Map.of(), TrustPolicy.TRUST_ALL)
						.remoteAddress(remoteAddress("203.0.113.50"))
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_rejectsNonIpv6TokensBeforeFallback()
			throws Exception {
		Map<String, List<String>> headers = Map.of(
				"Forwarded", List.of("for=\"[v1.example]\""),
				"X-Forwarded-For", List.of(".::1"));

		assertEquals(Optional.of(address("203.0.113.50")),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_ALL)
						.remoteAddress(remoteAddress("203.0.113.50"))
						.resolve());
	}

	@Test
	public void effectiveClientIpFromHeaders_unresolvedRemoteAddressDoesNotTrustForwardedHeaders() throws Exception {
		Map<String, List<String>> headers = Map.of(
				"X-Forwarded-For", List.of("198.51.100.10")
		);

		assertEquals(Optional.empty(),
				EffectiveClientIpResolver.withHeaders(headers, TrustPolicy.TRUST_PROXY_ALLOWLIST)
						.remoteAddress(InetSocketAddress.createUnresolved("proxy.example", 1234))
						.trustedProxyAddresses(Set.of(address("203.0.113.10")))
						.resolve());
	}

	@Test
	public void contentTypeFromHeaders() {
		String contentType = Utilities.extractContentTypeFromHeaderValue("text/html").orElse(null);
		assertEquals("text/html", contentType, "Content type was not correctly detected");

		contentType = Utilities.extractContentTypeFromHeaderValue("").orElse(null);
		assertEquals(null, contentType, "Absence of content type was not correctly detected");

		contentType = Utilities.extractContentTypeFromHeaderValue(null).orElse(null);
		assertEquals(null, contentType, "Absence of content type was not correctly detected");

		contentType = Utilities.extractContentTypeFromHeaderValue("text/html; charset=UTF-8").orElse(null);
		assertEquals("text/html", contentType, "Content type was not correctly detected");

		contentType = Utilities.extractContentTypeFromHeaderValue("text/html   ; charset=UTF-8").orElse(null);
		assertEquals("text/html", contentType, "Content type was not correctly detected");

		contentType = Utilities.extractContentTypeFromHeaderValue("text/html;charset=UTF-8").orElse(null);
		assertEquals("text/html", contentType, "Content type was not correctly detected");
	}

	@Test
	public void charsetFromHeaders() {
		Charset charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=UTF-8").orElse(null);
		assertEquals(StandardCharsets.UTF_8, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html").orElse(null);
		assertEquals(null, charset, "Absence of charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html;").orElse(null);
		assertEquals(null, charset, "Absence of charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue(";charset=UTF-8;").orElse(null);
		assertEquals(StandardCharsets.UTF_8, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue(";charset=UTF-8   ;  ").orElse(null);
		assertEquals(StandardCharsets.UTF_8, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("multipart/form-data; boundary=something").orElse(null);
		assertEquals(null, charset, "Absence of charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("multipart/form-data; charset=UTF-8; boundary=something").orElse(null);
		assertEquals(StandardCharsets.UTF_8, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=ISO-8859-1").orElse(null);
		assertEquals(StandardCharsets.ISO_8859_1, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=utf-16").orElse(null);
		assertEquals(StandardCharsets.UTF_16, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=ascii").orElse(null);
		assertEquals(StandardCharsets.US_ASCII, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=WINDOWS-1251").orElse(null);
		assertEquals(Charset.forName("windows-1251"), charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=KOI8-R").orElse(null);
		assertEquals(Charset.forName("koi8-r"), charset, "Charset was not correctly detected");
	}

	@Test
	public void plusIsPreservedInRfc3986Queries() {
		Map<String, List<String>> qp = Utilities.extractQueryParametersFromUrl("/?q=C++", QueryFormat.RFC_3986_STRICT);
		// Desired (URL semantics): "+" is literal, not a space
		assertEquals(List.of("C++"), qp.get("q"));
	}

	@Test
	public void percentEncodedPlusIsPreservedInRfc3986Queries() {
		Map<String, List<String>> qp = Utilities.extractQueryParametersFromUrl("/?q=C%2B%2B", QueryFormat.RFC_3986_STRICT);
		assertEquals(List.of("C++"), qp.get("q"));
	}

	@Test
	public void plusInFormBodyIsSpace() {
		// Form semantics (x-www-form-urlencoded) *do* translate '+' to space:
		Map<String, List<String>> qp = Utilities.extractQueryParametersFromQuery("q=C+Sharp", QueryFormat.X_WWW_FORM_URLENCODED);
		assertEquals(List.of("C Sharp"), qp.get("q"));
	}

	@Test
	public void emptyValueInRfc3986QueryIsPreserved() {
		Map<String, List<String>> qp = Utilities.extractQueryParametersFromUrl("/?a=", QueryFormat.RFC_3986_STRICT);
		assertTrue(qp.containsKey("a"), "Parameter name should exist");
		assertEquals(List.of(""), qp.get("a"), "Empty value should be preserved");
	}

	@Test
	public void emptyValueInFormIsPreserved() {
		Map<String, List<String>> form = Utilities.extractQueryParametersFromQuery("x=", QueryFormat.X_WWW_FORM_URLENCODED);
		assertTrue(form.containsKey("x"));
		assertEquals(List.of(""), form.get("x"));
	}

	@Test
	public void invalidEscapeThrows() {
		assertThrows(IllegalRequestException.class,
				() -> Utilities.extractQueryParametersFromUrl("/?a=%ZZ", QueryFormat.X_WWW_FORM_URLENCODED));
	}

	@Test
	public void invalidEscapeInQueryStringThrows() {
		assertThrows(IllegalRequestException.class,
				() -> Utilities.extractQueryParametersFromQuery("a=%ZZ", QueryFormat.X_WWW_FORM_URLENCODED));
	}

	@Test
	void cacheControl_singleLineAndMultipleLinesRetainTheirPhysicalOccurrences() {
		Map<String, List<String>> a = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Cache-Control: no-cache, no-store"
		));
		Map<String, List<String>> b = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Cache-Control: no-cache",
				"Cache-Control: no-store"
		));

		assertEquals(Map.of("cache-control", List.of("no-cache, no-store")), a);
		assertEquals(Map.of("cache-control", List.of("no-cache", "no-store")), b);
	}

	@Test
	void cacheControl_unfolds_obsFold_continuations() {
		Map<String, List<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Cache-Control: no-cache,",
				"  no-store"
		));

		assertEquals(List.of("no-cache, no-store"), m.get("cache-control"));
	}

	@Test
	void accept_preservesTheWholeFieldValueIncludingRepeatedCommaItems() {
		Map<String, List<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Accept: text/html, application/json, text/html  "
		));

		assertEquals(List.of("text/html, application/json, text/html"), m.get("accept"));
	}

	@Test
	void vary_mergesCaseInsensitiveNamesAndPreservesRepeatedValues() {
		Map<String, List<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Vary: Accept-Encoding",
				"vary: Accept-Encoding, Accept",
				"VARY: Accept" // repeated values remain visible
		));

		assertEquals(List.of("Accept-Encoding", "Accept-Encoding, Accept", "Accept"), m.get("vary"));
		assertEquals(1, m.size(), "vary entries should merge despite case differences");
	}

	@Test
	void setCookie_isNotCommaSplit_andMultipleLinesAreSeparateValues() {
		Map<String, List<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Set-Cookie: a=b; Path=/; HttpOnly; Secure",
				"Set-Cookie: session=xyz; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/"
		));

		List<String> values = m.get("set-cookie");
		assertNotNull(values);
		assertEquals(2, values.size(), "Each Set-Cookie line should remain intact");

		// Ensure the comma in Expires did not cause a split
		assertTrue(values.stream().anyMatch(v -> v.contains("Expires=Wed, 21 Oct 2015 07:28:00 GMT")));
	}

	@Test
	void quoted_commas_doNotSplit_values_for_joinable_headers() {
		Map<String, List<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Warning: 299 - \"Deprecated, will be removed soon\""
		));

		List<String> values = m.get("warning");
		assertNotNull(values);
		assertEquals(1, values.size());
		assertTrue(values.iterator().next().contains("\"Deprecated, will be removed soon\""));
	}

	@Test
	void quoted_escapes_are_respected_inside_quotes() {
		Map<String, List<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Warning: 199 example \"quote: \\\"inside\\\"\" , 299 example2 \"ok\""
		));

		// The comma outside quotes is still part of the single physical field value.
		List<String> values = m.get("warning");
		assertNotNull(values);
		assertEquals(1, values.size());
		assertTrue(values.stream().anyMatch(v -> v.contains("quote: \\\"inside\\\"")));
		assertTrue(values.get(0).contains(", 299 example2 \"ok\""));
	}

	@Test
	void outerWhitespaceIsTrimmedAndEmptyFieldValuesAreRetained() {
		Map<String, List<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Cache-Control:   no-cache   ",
				"Cache-Control:    ,   ,   no-store   ",
				"Cache-Control:",
				"Cache-Control:\t \t"
		));
		assertEquals(List.of("no-cache", ",   ,   no-store", "", ""), m.get("cache-control"));
	}

	@Test
	void malformedLinesAreSkipped_gracefully() {
		Map<String, List<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"X-JustNameNoColon",
				" : value",
				"Good: value"
		));
		assertEquals(List.of("value"), m.get("good"));
		assertEquals(1, m.size());
	}

	@Test
	void connectionAndTransferEncodingKeepTheirWholeFieldValues() {
		Map<String, List<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Connection: keep-alive, Upgrade",
				"Transfer-Encoding: chunked, gzip"
		));
		assertEquals(List.of("keep-alive, Upgrade"), m.get("connection"));
		assertEquals(List.of("chunked, gzip"), m.get("transfer-encoding"));
	}

	@Test
	public void hostAndProto_ipv6WithPort_isRecognized() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Host", List.of("[2001:db8::1]:8080"));
		headers.put("X-Forwarded-Proto", List.of("https"));

		var url = extractEffectiveOrigin(headers);
		Assertions.assertTrue(url.isPresent(), "URL prefix should be detected");
		Assertions.assertEquals("https://[2001:db8::1]:8080", url.get());
	}

	@Test
	public void forwarded_ipv6WithPort_isRecognized() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Forwarded", List.of("for=\"[2001:db8::1]\"; host=\"[2001:db8::1]:8443\"; proto=https"));

		var url = extractEffectiveOrigin(headers);
		Assertions.assertTrue(url.isPresent(), "URL prefix should be detected");
		Assertions.assertEquals("https://[2001:db8::1]:8443", url.get());
	}

	@Test
	public void origin_ipv6WithPort_isRecognized() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Origin", List.of("http://[2001:db8::1]:12345"));

		var url = extractEffectiveOrigin(headers);
		Assertions.assertTrue(url.isPresent(), "URL prefix should be detected");
		Assertions.assertEquals("http://[2001:db8::1]:12345", url.get());
	}

	@Test
	public void origin_doesNotOverrideHostWhenMismatch() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Host", List.of("api.example.com"));
		headers.put("X-Forwarded-Proto", List.of("https"));
		headers.put("Origin", List.of("https://evil.example.net"));

		var url = extractEffectiveOrigin(headers);
		Assertions.assertTrue(url.isPresent(), "URL prefix should be detected");
		Assertions.assertEquals("https://api.example.com", url.get());
	}

	@Test
	public void origin_fillsSchemeAndPortWhenHostMatches() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Host", List.of("api.example.com"));
		headers.put("Origin", List.of("https://api.example.com:8443"));

		var url = extractEffectiveOrigin(headers);
		Assertions.assertTrue(url.isPresent(), "URL prefix should be detected");
		Assertions.assertEquals("https://api.example.com:8443", url.get());
	}

	@Test
	public void forwardedQuotedValues_areHandled() {
		Map<String, List<String>> headers = Map.of(
				"Forwarded", List.of("for=203.0.113.60; proto=\"https\"; host=\"example.com:443\"")
		);

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isPresent());
		Assertions.assertEquals("https://example.com:443", prefix.get());
	}

	@Test
	public void forwardedHostValidation_rejectsSpoofedForwardedHost() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Forwarded", List.of("for=203.0.113.60; proto=https; host=\"public.example.com evil.example\""));

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isEmpty(), "Invalid Forwarded host must not become the effective origin");
	}

	@Test
	public void forwardedHostValidation_rejectsSpoofedForwardedHostAndFallsBackToHost() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Host", List.of("internal.soklet.local"));
		headers.put("Forwarded", List.of("for=203.0.113.60; proto=https; host=\"public.example.com\r\nX-Evil: yes\""));

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isPresent());
		Assertions.assertEquals("https://internal.soklet.local", prefix.get());
	}

	@Test
	public void forwardedHostValidation_rejectsSpoofedXForwardedHostAndFallsBackToHost() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Host", List.of("internal.soklet.local"));
		headers.put("X-Forwarded-Host", List.of("public.example.com\tbad"));
		headers.put("X-Forwarded-Proto", List.of("https"));

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isPresent());
		Assertions.assertEquals("https://internal.soklet.local", prefix.get());
	}

	@Test
	public void forwardedHostValidation_rejectsInvalidForwardedPorts() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Forwarded", List.of("for=203.0.113.60; proto=https; host=\"example.com:99999\""));

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isEmpty(), "Invalid Forwarded host ports must not be ignored into a host-only origin");
	}

	@Test
	public void forwardedHostValidation_rejectsUnbracketedIpv6() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Forwarded", List.of("for=203.0.113.60; proto=https; host=2001:db8::1"));

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isEmpty(), "Unbracketed IPv6 host values must not become the effective origin");
	}

	@Test
	public void forwardedHeaderLists_useFirstEntry() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Host", List.of("internal.soklet.local"));
		headers.put("X-Forwarded-Host", List.of("public.soklet.com, internal.soklet.local"));
		headers.put("X-Forwarded-Proto", List.of("https, http"));
		headers.put("X-Forwarded-Port", List.of("8443, 8080"));

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isPresent());
		Assertions.assertEquals("https://public.soklet.com:8443", prefix.get());
	}

	@Test
	public void forwardedHeaderLists_skipIncompleteEntries() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Forwarded", List.of("for=203.0.113.60, for=203.0.113.61; proto=https; host=example.com:8443"));

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isPresent());
		Assertions.assertEquals("https://example.com:8443", prefix.get());
	}

	@Test
	public void forwardedHeaderLists_supportMultipleHeaderValues() {
		Map<String, List<String>> headers = new HashMap<>();
		List<String> forwardedValues = new ArrayList<>();
		forwardedValues.add("for=203.0.113.60");
		forwardedValues.add("for=203.0.113.61; proto=https; host=example.com");
		headers.put("Forwarded", forwardedValues);

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isPresent());
		Assertions.assertEquals("https://example.com", prefix.get());
	}

	@Test
	public void forwardedHeaderLists_doNotMixEntries() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Forwarded", List.of("host=example.com, proto=https"));

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isEmpty(), "Forwarded entries should not be mixed");
	}

	@Test
	public void forwardedHeaderQuotedSemicolons_areHandled() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Forwarded", List.of("for=\"abc;def\"; host=example.com; proto=https"));

		Optional<String> prefix = extractEffectiveOrigin(headers);
		Assertions.assertTrue(prefix.isPresent());
		Assertions.assertEquals("https://example.com", prefix.get());
	}

	@Test
	public void commaListHeadersKeepTheirWholeFieldValues() {
		List<String> lines = List.of(
				"Cache-Control: no-cache, no-store",
				"Warning: \"c,omma inside quotes\", 199 Misc"
		);
		Map<String, List<String>> parsed = Utilities.extractHeadersFromRawHeaderLines(lines);
		Assertions.assertEquals(List.of("no-cache, no-store"), parsed.get("cache-control"));
		Assertions.assertEquals(List.of("\"c,omma inside quotes\", 199 Misc"), parsed.get("Warning"));
	}

	@Test
	public void contentType_parsesMediaTypeAndCharset() {
		Map<String, List<String>> h = Map.of("Content-Type", List.of("text/html; charset=\"UTF-8\""));
		Assertions.assertEquals(Optional.of("text/html"), Utilities.extractContentTypeFromHeaders(h));
		Assertions.assertEquals(Optional.of(StandardCharsets.UTF_8), Utilities.extractCharsetFromHeaders(h));
	}

	@Test
	public void cookieParsing_handlesQuotedAndEscaped() {
		Map<String, List<String>> h = Map.of("Cookie", List.of("a=\"b\\\";c\"; d=%20; e=; f=\"\""));
		Map<String, List<String>> cookies = Utilities.extractCookiesFromHeaders(h);
		Assertions.assertEquals(List.of("b\";c"), cookies.get("a"));
		Assertions.assertEquals(List.of(" "), cookies.get("d"));
		// TODO: Should we preserve empty cookies?
		// Assertions.assertEquals(List.of(""), cookies.get("e"));
		Assertions.assertEquals(List.of(""), cookies.get("f"));
	}

	@Test
	public void quotedForwarded_isUnquotedAndParsed() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Forwarded", List.of("for=\"[2001:db8::1]\"; host=\"example.com:8443\"; proto=\"https\""));

		var url = extractEffectiveOrigin(headers);
		Assertions.assertTrue(url.isPresent(), "URL prefix should be detected");
		Assertions.assertEquals("https://example.com:8443", url.get());
	}

	@Test
	void unfoldsObsFoldAndKeepsEveryFieldOccurrenceDistinct() {
		var raw = List.of(
				"Cache-Control: no-cache, no-store",
				"Set-Cookie: a=b; Path=/; HttpOnly",
				"Set-Cookie: c=d; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/",
				"X-Foo: first",
				" second-line" // obs-fold continuation for X-Foo
		);

		var headers = Utilities.extractHeadersFromRawHeaderLines(raw);

		// case-insensitive key access + insertion order preserved
		assertEquals(List.of("no-cache, no-store"), headers.get("Cache-Control"));
		assertEquals(
				List.of(
						"a=b; Path=/; HttpOnly",
						"c=d; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/"
				),
				new ArrayList<>(headers.get("Set-Cookie"))
		);
		assertEquals(List.of("first second-line"), headers.get("X-Foo"));
	}

	@Test
	void quotedCommasAreNotSplit() {
		var raw = List.of("Cache-Control: foo=\"a,b\", bar=c");
		var headers = Utilities.extractHeadersFromRawHeaderLines(raw);
		assertEquals(List.of("foo=\"a,b\", bar=c"), headers.get("Cache-Control"));
	}

	@Test
	void rejectsIllegalHeaderNameCharacters() {
		assertThrows(IllegalArgumentException.class,
				() -> Utilities.validateHeaderNameAndValue("X Foo", "ok")); // space not allowed in name
		assertThrows(IllegalArgumentException.class,
				() -> Utilities.validateHeaderNameAndValue(" X-Foo", "ok")); // validate the stored name, not a trimmed copy
		assertThrows(IllegalArgumentException.class,
				() -> Utilities.validateHeaderNameAndValue("X-Foo ", "ok"));
		assertThrows(IllegalArgumentException.class,
				() -> Utilities.validateHeaderNameAndValue("X\nFoo", "ok")); // CR/LF must be rejected
	}

	@Test
	void rejectsCRLFInHeaderValue() {
		assertThrows(IllegalArgumentException.class,
				() -> Utilities.validateHeaderNameAndValue("X-Foo", "bar\r\nInjected: evil"));
	}

	@Test
	void acceptsLegalHeaders() {
		Assertions.assertDoesNotThrow(() -> Utilities.validateHeaderNameAndValue("X-Foo", "bar"));
		Assertions.assertDoesNotThrow(() -> Utilities.validateHeaderNameAndValue(
				"X-Obs-Text", "\u0080\u0085\u00FF"));
		assertThrows(IllegalArgumentException.class,
				() -> Utilities.validateHeaderNameAndValue("X-Foo", "bad\u007Fvalue"));
		assertThrows(IllegalArgumentException.class,
				() -> Utilities.validateHeaderNameAndValue("X-Foo", "bad\u0100value"));
	}

	@Test
	void formMode_treatsPlusAsSpace_andDecodesPercentEscapes() {
		var q = "a=a+b%2B%20&empty=&name=%E2%9C%93";
		var m = Utilities.extractQueryParametersFromQuery(
				q, QueryFormat.X_WWW_FORM_URLENCODED);

		assertEquals(List.of("a b+ "), m.get("a"));  // '+' -> space; %2B -> '+'; %20 -> space
		assertEquals(List.of(""), m.get("empty"));   // empty preserved
		assertEquals(List.of("✓"), m.get("name"));   // UTF-8 percent-decoding
	}

	@Test
	void strictMode_leavesPlusAsPlus() {
		var q = "a=a+b%2B%20";
		var m = Utilities.extractQueryParametersFromQuery(q, QueryFormat.RFC_3986_STRICT);

		assertEquals(List.of("a+b+ "), m.get("a")); // '+' stays '+'
	}

	@Test
	void parsesQuotedAndEscapedCookieValues_andPercentDecoding() {
		var headers = new LinkedHashMap<String, List<String>>();
		headers.put("Cookie", List.of(
				"a=1; b=\"two;three\"; c=\"a\\\"b\\\\c\"; d=%E2%9C%93"
		));

		var cookies = Utilities.extractCookiesFromHeaders(headers);
		assertEquals(List.of("1"), cookies.get("a"));
		assertEquals(List.of("two;three"), cookies.get("b"));
		assertEquals(List.of("a\"b\\c"), cookies.get("c"));
		assertEquals(List.of("✓"), cookies.get("d"));
	}

	@Test
	void literalCookiePercentSignsArePreservedAlongsideEncodedValues() {
		var headers = new LinkedHashMap<String, List<String>>();
		headers.put("Cookie", List.of("session=%ZZ; percent=100%; short=%A; mixed=%25%ZZ; neighbor=ok; encoded=%E2%9C%93"));

		var cookies = Utilities.extractCookiesFromHeaders(headers);
		assertEquals(List.of("%ZZ"), cookies.get("session"));
		assertEquals(List.of("100%"), cookies.get("percent"));
		assertEquals(List.of("%A"), cookies.get("short"));
		assertEquals(List.of("%%ZZ"), cookies.get("mixed"));
		assertEquals(List.of("ok"), cookies.get("neighbor"));
		assertEquals(List.of("✓"), cookies.get("encoded"));
	}

	@Test
	void rawUnicodeCookieValuesArePreserved() {
		var headers = new LinkedHashMap<String, List<String>>();
		headers.put("Cookie", List.of("emoji=🍪"));

		var cookies = Utilities.extractCookiesFromHeaders(headers);
		assertEquals(List.of("🍪"), cookies.get("emoji"));
	}

	@Test
	void responseCookie_toSetCookieHeaderRepresentation_isWellFormed() {
		var cookie = ResponseCookie.with("session", "abc")
				.path("/")
				.domain("example.com")
				.maxAge(java.time.Duration.ofHours(1))
				.secure(true)
				.httpOnly(true)
				.sameSite(ResponseCookie.SameSite.LAX)
				.build();

		var header = cookie.toSetCookieHeaderRepresentation();
		assertTrue(header.contains("session=abc"));
		assertTrue(header.contains("Path=/"));
		assertTrue(header.contains("Domain=example.com"));
		assertTrue(header.contains("Max-Age="));
		assertTrue(header.contains("Secure"));
		assertTrue(header.contains("HttpOnly"));
		assertTrue(header.contains("SameSite=Lax"));
	}

	@Test
	void queryParamsAreUrlDecoded_andPlusBecomesSpace() {
		String url = "https://example.com/p?q=First+Last&x=%2F";
		Map<String, List<String>> qp = Utilities.extractQueryParametersFromUrl(url, QueryFormat.X_WWW_FORM_URLENCODED);

		assertEquals(List.of("First Last"), qp.get("q"));
		assertEquals(List.of("/"), qp.get("x"));
	}

	@Test
	void cookieHeaderNameIsCaseInsensitive_and_AllowsEqualsInValue() {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("COOKIE", List.of("token=abc==; theme=dark"));

		Map<String, List<String>> cookies = Utilities.extractCookiesFromHeaders(headers);

		assertEquals(List.of("abc=="), cookies.get("token"), "should keep trailing == in value");
		assertEquals(List.of("dark"), cookies.get("theme"));
	}

	@Test
	void quotedCharsetParameterIsSupported() {
		assertEquals(
				StandardCharsets.UTF_8,
				Utilities.extractCharsetFromHeaderValue("text/plain; charset=\"utf-8\"").orElseThrow()
		);
	}

	@Test
	void forwardedHeaderQuotedValuesProduceCleanPrefix() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Forwarded", List.of("proto=\"https\";host=\"www.example.com\""));

		assertEquals(
				Optional.of("https://www.example.com"),
				extractEffectiveOrigin(headers)
		);
	}

	private static Optional<String> extractEffectiveOrigin(Map<String, List<String>> headers) {
		return EffectiveOriginResolver.withHeaders(
				headers,
				EffectiveOriginResolver.TrustPolicy.TRUST_ALL
		).resolve();
	}

	private static InetAddress address(String address) throws Exception {
		return InetAddress.getByName(address);
	}

	private static InetSocketAddress remoteAddress(String address) throws Exception {
		return new InetSocketAddress(address(address), 1234);
	}

	// --- header parsing helpers ---
	private static List<String> lines(String... ls) {
		return Arrays.asList(ls);
	}

	private static List<String> setToList(Set<String> s) {
		return new ArrayList<>(s);
	}
}
