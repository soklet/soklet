/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.soklet.Utilities.QueryDecodingStrategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class UtilitiesTests {
	@Test
	public void normalizedPathForUrl() {
		assertEquals("/", Utilities.normalizedPathForUrl("https://www.google.com/"));
		assertEquals("/", Utilities.normalizedPathForUrl("https://www.google.com"));
		assertEquals("/", Utilities.normalizedPathForUrl(""));
		assertEquals("/", Utilities.normalizedPathForUrl("/"));
		assertEquals("/test", Utilities.normalizedPathForUrl("/test"));
		assertEquals("/test", Utilities.normalizedPathForUrl("/test/"));
		assertEquals("/test", Utilities.normalizedPathForUrl("/test//"));
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

		locales = Utilities.extractLocalesFromAcceptLanguageHeaderValue("xxxx");

		assertEquals(List.of(), locales, "Junk locale string mishandled");
	}

	@Test
	public void clientUrlPrefixFromHeaders() {
		String clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of()).orElse(null);
		assertEquals(null, clientUrlPrefix, "Client URL prefix erroneously detected from incomplete header data");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com")
		)).orElse(null);
		assertEquals(null, clientUrlPrefix, "Client URL prefix erroneously detected from incomplete header data");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com:443")
		)).orElse(null);
		assertEquals(null, clientUrlPrefix, "Client URL prefix erroneously detected from incomplete header data");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Forwarded", Set.of("for=12.34.56.78;host=example.com;proto=https, for=23.45.67.89")
		)).orElse(null);
		assertEquals("https://example.com", clientUrlPrefix, "Client URL prefix was not correctly detected");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com"),
				"X-Forwarded-Proto", Set.of("https")
		)).orElse(null);
		assertEquals("https://www.soklet.com", clientUrlPrefix, "Client URL prefix was not correctly detected");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com"),
				"X-Forwarded-Proto", Set.of("https")
		)).orElse(null);
		assertEquals("https://www.soklet.com", clientUrlPrefix, "Client URL prefix was not correctly detected");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"X-Forwarded-Host", Set.of("www.soklet.com"),
				"X-Forwarded-Protocol", Set.of("https")
		)).orElse(null);
		assertEquals("https://www.soklet.com", clientUrlPrefix, "Client URL prefix was not correctly detected");
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
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromUrl("/?q=C++", QueryDecodingStrategy.RFC_3986_STRICT);
		// Desired (URL semantics): "+" is literal, not a space
		assertEquals(Set.of("C++"), qp.get("q"));
	}

	@Test
	public void percentEncodedPlusIsPreservedInRfc3986Queries() {
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromUrl("/?q=C%2B%2B", QueryDecodingStrategy.RFC_3986_STRICT);
		assertEquals(Set.of("C++"), qp.get("q"));
	}

	@Test
	public void plusInFormBodyIsSpace() {
		// Form semantics (x-www-form-urlencoded) *do* translate '+' to space:
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromQuery("q=C+Sharp", QueryDecodingStrategy.X_WWW_FORM_URLENCODED);
		assertEquals(Set.of("C Sharp"), qp.get("q"));
	}

	@Test
	public void emptyValueInRfc3986QueryIsPreserved() {
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromUrl("/?a=", QueryDecodingStrategy.RFC_3986_STRICT);
		assertTrue(qp.containsKey("a"), "Parameter name should exist");
		assertEquals(Set.of(""), qp.get("a"), "Empty value should be preserved");
	}

	@Test
	public void emptyValueInFormIsPreserved() {
		Map<String, Set<String>> form = Utilities.extractQueryParametersFromQuery("x=", QueryDecodingStrategy.X_WWW_FORM_URLENCODED);
		assertTrue(form.containsKey("x"));
		assertEquals(Set.of(""), form.get("x"));
	}

	@Test
	public void invalidEscapeDoesNotThrow() {
		Assertions.assertDoesNotThrow(() -> Utilities.extractQueryParametersFromUrl("/?a=%ZZ", QueryDecodingStrategy.X_WWW_FORM_URLENCODED));
	}

	@Test
	public void invalidEscapeIsLeftLiteralOrSkipped() {
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromUrl("/?a=%ZZ", QueryDecodingStrategy.X_WWW_FORM_URLENCODED);
		// Implementation choice: either literal "%ZZ" or skip parameter entirely.
		// For literal behavior:
		// Assertions.assertEquals(Set.of("%ZZ"), qp.get("a"));
		// For skip behavior:
		assertTrue(qp.isEmpty() || qp.get("a").contains("%ZZ"));
	}

	@Test
	void cacheControl_singleLineCommaSeparated_equals_multiLine() {
		Map<String, Set<String>> a = Utilities.extractHeadersFromRawHeaders(lines(
				"Cache-Control: no-cache, no-store"
		));
		Map<String, Set<String>> b = Utilities.extractHeadersFromRawHeaders(lines(
				"Cache-Control: no-cache",
				"Cache-Control: no-store"
		));

		Map<String, Set<String>> expected = Map.of("cache-control", Set.of("no-cache", "no-store"));

		assertEquals(expected, a);
		assertEquals(expected, b);
	}

	@Test
	void cacheControl_unfolds_obsFold_continuations() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaders(lines(
				"Cache-Control: no-cache,",
				"  no-store"
		));

		assertEquals(Set.of("no-cache", "no-store"), m.get("cache-control"));
	}

	@Test
	void accept_isCommaSplit_deduped_preservingInsertionOrder() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaders(lines(
				"Accept: text/html, application/json, text/html  "
		));

		assertEquals(Set.of("text/html", "application/json"), m.get("accept"));
		// insertion order check (LinkedHashSet)
		assertEquals(List.of("text/html", "application/json"), setToList(m.get("accept")));
	}

	@Test
	void vary_mergesAcrossLines_and_dedupes_caseInsensitiveNames() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaders(lines(
				"Vary: Accept-Encoding",
				"vary: Accept-Encoding, Accept",
				"VARY: Accept" // duplicates should collapse
		));

		assertEquals(Set.of("Accept-Encoding", "Accept"), m.get("vary"));
		assertEquals(1, m.size(), "vary entries should merge despite case differences");
	}

	@Test
	void setCookie_isNotCommaSplit_andMultipleLinesAreSeparateValues() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaders(lines(
				"Set-Cookie: a=b; Path=/; HttpOnly; Secure",
				"Set-Cookie: session=xyz; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/"
		));

		Set<String> values = m.get("set-cookie");
		assertNotNull(values);
		assertEquals(2, values.size(), "Each Set-Cookie line should remain intact");

		// Ensure the comma in Expires did not cause a split
		assertTrue(values.stream().anyMatch(v -> v.contains("Expires=Wed, 21 Oct 2015 07:28:00 GMT")));
	}

	@Test
	void quoted_commas_doNotSplit_values_for_joinable_headers() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaders(lines(
				"Warning: 299 - \"Deprecated, will be removed soon\""
		));

		Set<String> values = m.get("warning");
		assertNotNull(values);
		assertEquals(1, values.size());
		assertTrue(values.iterator().next().contains("\"Deprecated, will be removed soon\""));
	}

	@Test
	void quoted_escapes_are_respected_inside_quotes() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaders(lines(
				"Warning: 199 example \"quote: \\\"inside\\\"\" , 299 example2 \"ok\""
		));

		// Two Warning values separated by a real comma outside quotes
		Set<String> values = m.get("warning");
		assertNotNull(values);
		assertEquals(2, values.size());
		assertTrue(values.stream().anyMatch(v -> v.contains("quote: \\\"inside\\\"")));
	}

	@Test
	void whitespace_isTrimmed_and_emptyValuesIgnored() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaders(lines(
				"Cache-Control:   no-cache   ",
				"Cache-Control:    ,   ,   no-store   "  // empties ignored
		));
		assertEquals(Set.of("no-cache", "no-store"), m.get("cache-control"));
	}

	@Test
	void malformedLinesAreSkipped_gracefully() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaders(lines(
				"X-JustNameNoColon",
				" : value",
				"Good: value"
		));
		assertEquals(Set.of("value"), m.get("good"));
		assertEquals(1, m.size());
	}

	@Test
	void connection_and_transferEncoding_are_joinable() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaders(lines(
				"Connection: keep-alive, Upgrade",
				"Transfer-Encoding: chunked, gzip"
		));
		assertEquals(Set.of("keep-alive", "Upgrade"), m.get("connection"));
		assertEquals(Set.of("chunked", "gzip"), m.get("transfer-encoding"));
	}

	// --- header parsing helpers ---
	private static List<String> lines(String... ls) {
		return Arrays.asList(ls);
	}

	private static List<String> setToList(Set<String> s) {
		return new ArrayList<>(s);
	}
}
