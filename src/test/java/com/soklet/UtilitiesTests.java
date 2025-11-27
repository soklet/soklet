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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
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

import static com.soklet.Utilities.encodedPathAndQueryParameters;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class UtilitiesTests {
	@Test
	public void normalizedPathForUrl() {
		assertEquals("/", Utilities.normalizedPathForUrl("https://www.google.com/", true));
		assertEquals("/", Utilities.normalizedPathForUrl("https://www.google.com", true));
		assertEquals("/", Utilities.normalizedPathForUrl("", true));
		assertEquals("/", Utilities.normalizedPathForUrl("/", true));
		assertEquals("/test", Utilities.normalizedPathForUrl("/test", true));
		assertEquals("/test", Utilities.normalizedPathForUrl("/test/", true));
		assertEquals("/test", Utilities.normalizedPathForUrl("/test//", true));
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
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromUrl("/?q=C++", QueryStringFormat.RFC_3986_STRICT);
		// Desired (URL semantics): "+" is literal, not a space
		assertEquals(Set.of("C++"), qp.get("q"));
	}

	@Test
	public void percentEncodedPlusIsPreservedInRfc3986Queries() {
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromUrl("/?q=C%2B%2B", QueryStringFormat.RFC_3986_STRICT);
		assertEquals(Set.of("C++"), qp.get("q"));
	}

	@Test
	public void plusInFormBodyIsSpace() {
		// Form semantics (x-www-form-urlencoded) *do* translate '+' to space:
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromQueryString("q=C+Sharp", QueryStringFormat.X_WWW_FORM_URLENCODED);
		assertEquals(Set.of("C Sharp"), qp.get("q"));
	}

	@Test
	public void emptyValueInRfc3986QueryIsPreserved() {
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromUrl("/?a=", QueryStringFormat.RFC_3986_STRICT);
		assertTrue(qp.containsKey("a"), "Parameter name should exist");
		assertEquals(Set.of(""), qp.get("a"), "Empty value should be preserved");
	}

	@Test
	public void emptyValueInFormIsPreserved() {
		Map<String, Set<String>> form = Utilities.extractQueryParametersFromQueryString("x=", QueryStringFormat.X_WWW_FORM_URLENCODED);
		assertTrue(form.containsKey("x"));
		assertEquals(Set.of(""), form.get("x"));
	}

	@Test
	public void invalidEscapeDoesNotThrow() {
		Assertions.assertDoesNotThrow(() -> Utilities.extractQueryParametersFromUrl("/?a=%ZZ", QueryStringFormat.X_WWW_FORM_URLENCODED));
	}

	@Test
	public void invalidEscapeIsLeftLiteralOrSkipped() {
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromUrl("/?a=%ZZ", QueryStringFormat.X_WWW_FORM_URLENCODED);
		// Implementation choice: either literal "%ZZ" or skip parameter entirely.
		// For literal behavior:
		// Assertions.assertEquals(Set.of("%ZZ"), qp.get("a"));
		// For skip behavior:
		assertTrue(qp.isEmpty() || qp.get("a").contains("%ZZ"));
	}

	@Test
	void cacheControl_singleLineCommaSeparated_equals_multiLine() {
		Map<String, Set<String>> a = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Cache-Control: no-cache, no-store"
		));
		Map<String, Set<String>> b = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Cache-Control: no-cache",
				"Cache-Control: no-store"
		));

		Map<String, Set<String>> expected = Map.of("cache-control", Set.of("no-cache", "no-store"));

		assertEquals(expected, a);
		assertEquals(expected, b);
	}

	@Test
	void cacheControl_unfolds_obsFold_continuations() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Cache-Control: no-cache,",
				"  no-store"
		));

		assertEquals(Set.of("no-cache", "no-store"), m.get("cache-control"));
	}

	@Test
	void accept_isCommaSplit_deduped_preservingInsertionOrder() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Accept: text/html, application/json, text/html  "
		));

		assertEquals(Set.of("text/html", "application/json"), m.get("accept"));
		// insertion order check (LinkedHashSet)
		assertEquals(List.of("text/html", "application/json"), setToList(m.get("accept")));
	}

	@Test
	void vary_mergesAcrossLines_and_dedupes_caseInsensitiveNames() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Vary: Accept-Encoding",
				"vary: Accept-Encoding, Accept",
				"VARY: Accept" // duplicates should collapse
		));

		assertEquals(Set.of("Accept-Encoding", "Accept"), m.get("vary"));
		assertEquals(1, m.size(), "vary entries should merge despite case differences");
	}

	@Test
	void setCookie_isNotCommaSplit_andMultipleLinesAreSeparateValues() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
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
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Warning: 299 - \"Deprecated, will be removed soon\""
		));

		Set<String> values = m.get("warning");
		assertNotNull(values);
		assertEquals(1, values.size());
		assertTrue(values.iterator().next().contains("\"Deprecated, will be removed soon\""));
	}

	@Test
	void quoted_escapes_are_respected_inside_quotes() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
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
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Cache-Control:   no-cache   ",
				"Cache-Control:    ,   ,   no-store   "  // empties ignored
		));
		assertEquals(Set.of("no-cache", "no-store"), m.get("cache-control"));
	}

	@Test
	void malformedLinesAreSkipped_gracefully() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"X-JustNameNoColon",
				" : value",
				"Good: value"
		));
		assertEquals(Set.of("value"), m.get("good"));
		assertEquals(1, m.size());
	}

	@Test
	void connection_and_transferEncoding_are_joinable() {
		Map<String, Set<String>> m = Utilities.extractHeadersFromRawHeaderLines(lines(
				"Connection: keep-alive, Upgrade",
				"Transfer-Encoding: chunked, gzip"
		));
		assertEquals(Set.of("keep-alive", "Upgrade"), m.get("connection"));
		assertEquals(Set.of("chunked", "gzip"), m.get("transfer-encoding"));
	}

	@Test
	public void hostAndProto_ipv6WithPort_isRecognized() {
		Map<String, Set<String>> headers = new HashMap<>();
		headers.put("Host", Set.of("[2001:db8::1]:8080"));
		headers.put("X-Forwarded-Proto", Set.of("https"));

		var url = Utilities.extractClientUrlPrefixFromHeaders(headers);
		Assertions.assertTrue(url.isPresent(), "URL prefix should be detected");
		Assertions.assertEquals("https://[2001:db8::1]:8080", url.get());
	}

	@Test
	public void forwarded_ipv6WithPort_isRecognized() {
		Map<String, Set<String>> headers = new HashMap<>();
		headers.put("Forwarded", Set.of("for=\"[2001:db8::1]\"; host=\"[2001:db8::1]:8443\"; proto=https"));

		var url = Utilities.extractClientUrlPrefixFromHeaders(headers);
		Assertions.assertTrue(url.isPresent(), "URL prefix should be detected");
		Assertions.assertEquals("https://[2001:db8::1]:8443", url.get());
	}

	@Test
	public void origin_ipv6WithPort_isRecognized() {
		Map<String, Set<String>> headers = new HashMap<>();
		headers.put("Origin", Set.of("http://[2001:db8::1]:12345"));

		var url = Utilities.extractClientUrlPrefixFromHeaders(headers);
		Assertions.assertTrue(url.isPresent(), "URL prefix should be detected");
		Assertions.assertEquals("http://[2001:db8::1]:12345", url.get());
	}

	@Test
	public void forwardedQuotedValues_areHandled() {
		Map<String, Set<String>> headers = Map.of(
				"Forwarded", Set.of("for=203.0.113.60; proto=\"https\"; host=\"example.com:443\"")
		);

		Optional<String> prefix = Utilities.extractClientUrlPrefixFromHeaders(headers);
		Assertions.assertTrue(prefix.isPresent());
		Assertions.assertEquals("https://example.com:443", prefix.get());
	}

	@Test
	public void commaJoinableHeaders_splitOutsideQuotes() {
		List<String> lines = List.of(
				"Cache-Control: no-cache, no-store",
				"Warning: \"c,omma inside quotes\", 199 Misc"
		);
		Map<String, Set<String>> parsed = Utilities.extractHeadersFromRawHeaderLines(lines);
		Assertions.assertEquals(Set.of("no-cache", "no-store"), parsed.get("cache-control"));
	}

	@Test
	public void contentType_parsesMediaTypeAndCharset() {
		Map<String, Set<String>> h = Map.of("Content-Type", Set.of("text/html; charset=\"UTF-8\""));
		Assertions.assertEquals(Optional.of("text/html"), Utilities.extractContentTypeFromHeaders(h));
		Assertions.assertEquals(Optional.of(StandardCharsets.UTF_8), Utilities.extractCharsetFromHeaders(h));
	}

	@Test
	public void cookieParsing_handlesQuotedAndEscaped() {
		Map<String, Set<String>> h = Map.of("Cookie", Set.of("a=\"b\\\";c\"; d=%20; e=; f=\"\""));
		Map<String, Set<String>> cookies = Utilities.extractCookiesFromHeaders(h);
		Assertions.assertEquals(Set.of("b\";c"), cookies.get("a"));
		Assertions.assertEquals(Set.of(" "), cookies.get("d"));
		// TODO: Should we preserve empty cookies?
		// Assertions.assertEquals(Set.of(""), cookies.get("e"));
		Assertions.assertEquals(Set.of(""), cookies.get("f"));
	}

	@Test
	public void quotedForwarded_isUnquotedAndParsed() {
		Map<String, Set<String>> headers = new HashMap<>();
		headers.put("Forwarded", Set.of("for=\"[2001:db8::1]\"; host=\"example.com:8443\"; proto=\"https\""));

		var url = Utilities.extractClientUrlPrefixFromHeaders(headers);
		Assertions.assertTrue(url.isPresent(), "URL prefix should be detected");
		Assertions.assertEquals("https://example.com:8443", url.get());
	}

	@Test
	void unfoldsObsFold_andSplitsCommaJoinable_andKeepsSetCookieDistinct() {
		var raw = List.of(
				"Cache-Control: no-cache, no-store",
				"Set-Cookie: a=b; Path=/; HttpOnly",
				"Set-Cookie: c=d; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/",
				"X-Foo: first",
				" second-line" // obs-fold continuation for X-Foo
		);

		var headers = Utilities.extractHeadersFromRawHeaderLines(raw);

		// case-insensitive key access + insertion order preserved
		assertEquals(Set.of("no-cache", "no-store"), new LinkedHashSet<>(headers.get("Cache-Control")));
		assertEquals(
				List.of(
						"a=b; Path=/; HttpOnly",
						"c=d; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/"
				),
				new ArrayList<>(headers.get("Set-Cookie"))
		);
		assertEquals(Set.of("first second-line"), headers.get("X-Foo"));
	}

	@Test
	void quotedCommasAreNotSplit() {
		var raw = List.of("Cache-Control: foo=\"a,b\", bar=c");
		var headers = Utilities.extractHeadersFromRawHeaderLines(raw);
		assertEquals(Set.of("foo=\"a,b\"", "bar=c"), headers.get("Cache-Control"));
	}

	@Test
	void rejectsIllegalHeaderNameCharacters() {
		assertThrows(IllegalArgumentException.class,
				() -> Utilities.validateHeaderNameAndValue("X Foo", "ok")); // space not allowed in name
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
	}

	@Test
	void formMode_treatsPlusAsSpace_andDecodesPercentEscapes() {
		var q = "a=a+b%2B%20&empty=&name=%E2%9C%93";
		var m = Utilities.extractQueryParametersFromQueryString(
				q, QueryStringFormat.X_WWW_FORM_URLENCODED);

		assertEquals(Set.of("a b+ "), m.get("a"));  // '+' -> space; %2B -> '+'; %20 -> space
		assertEquals(Set.of(""), m.get("empty"));   // empty preserved
		assertEquals(Set.of("✓"), m.get("name"));   // UTF-8 percent-decoding
	}

	@Test
	void strictMode_leavesPlusAsPlus() {
		var q = "a=a+b%2B%20";
		var m = Utilities.extractQueryParametersFromQueryString(q, QueryStringFormat.RFC_3986_STRICT);

		assertEquals(Set.of("a+b+ "), m.get("a")); // '+' stays '+'
	}

	@Test
	void parsesQuotedAndEscapedCookieValues_andPercentDecoding() {
		var headers = new LinkedHashMap<String, Set<String>>();
		headers.put("Cookie", Set.of(
				"a=1; b=\"two;three\"; c=\"a\\\"b\\\\c\"; d=%E2%9C%93"
		));

		var cookies = Utilities.extractCookiesFromHeaders(headers);
		assertEquals(Set.of("1"), cookies.get("a"));
		assertEquals(Set.of("two;three"), cookies.get("b"));
		assertEquals(Set.of("a\"b\\c"), cookies.get("c"));
		assertEquals(Set.of("✓"), cookies.get("d"));
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
		Map<String, Set<String>> qp = Utilities.extractQueryParametersFromUrl(url, QueryStringFormat.X_WWW_FORM_URLENCODED);

		assertEquals(Set.of("First Last"), qp.get("q"));
		assertEquals(Set.of("/"), qp.get("x"));
	}

	@Test
	void cookieHeaderNameIsCaseInsensitive_and_AllowsEqualsInValue() {
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("COOKIE", Set.of("token=abc==; theme=dark"));

		Map<String, Set<String>> cookies = Utilities.extractCookiesFromHeaders(headers);

		assertEquals(Set.of("abc=="), cookies.get("token"), "should keep trailing == in value");
		assertEquals(Set.of("dark"), cookies.get("theme"));
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
		Map<String, Set<String>> headers = new HashMap<>();
		headers.put("Forwarded", Set.of("proto=\"https\";host=\"www.example.com\""));

		assertEquals(
				Optional.of("https://www.example.com"),
				Utilities.extractClientUrlPrefixFromHeaders(headers)
		);
	}

	// --- header parsing helpers ---
	private static List<String> lines(String... ls) {
		return Arrays.asList(ls);
	}

	private static List<String> setToList(Set<String> s) {
		return new ArrayList<>(s);
	}


	@Test
	void javadocExample_isEncodedAsExpected() {
		// path "/my path", params {a=[b], c=[d e]}
		String path = "/my path";

		Map<String, Set<String>> params = new LinkedHashMap<>();
		params.put("a", new LinkedHashSet<>(Set.of("b")));
		params.put("c", new LinkedHashSet<>(Set.of("d e")));

		String result = encodedPathAndQueryParameters(
				path,
				params,
				QueryStringFormat.RFC_3986_STRICT
		);

		assertEquals("/my%20path?a=b&c=d%20e", result);
	}

	@Test
	void emptyQueryParameters_returnsEncodedPathOnly() {
		String path = "/my path";
		Map<String, Set<String>> params = Map.of(); // empty

		String result = encodedPathAndQueryParameters(
				path,
				params,
				QueryStringFormat.RFC_3986_STRICT
		);

		// path should still be encoded, but no '?' suffix
		assertEquals("/my%20path", result);
	}

	@Test
	void asteriskPath_returnsAsteriskWhenNoQueryParameters() {
		String path = "*";
		Map<String, Set<String>> params = Map.of();

		String result = encodedPathAndQueryParameters(
				path,
				params,
				QueryStringFormat.RFC_3986_STRICT
		);

		assertEquals("*", result);
	}

	@Test
	void multiValueParameters_areRepeatedInQueryStringPreservingOrder() {
		String path = "/test";

		// Use LinkedHashMap/LinkedHashSet to get deterministic order
		Set<String> a = new LinkedHashSet<>();
		a.add("1");
		a.add("2");

		Map<String, Set<String>> params = new LinkedHashMap<>();
		params.put("a", a);
		params.put("b", Set.of("3"));

		String result = encodedPathAndQueryParameters(
				path,
				params,
				QueryStringFormat.RFC_3986_STRICT
		);

		// Order: a=1, a=2, b=3
		assertEquals("/test?a=1&a=2&b=3", result);
	}

	@Test
	void parameterNamesAndValues_areEncodedWithRfc3986Semantics() {
		String path = "/path with space";

		Map<String, Set<String>> params = new LinkedHashMap<>();
		params.put("na me", new LinkedHashSet<>(Set.of("va lue&more")));

		String result = encodedPathAndQueryParameters(
				path,
				params,
				QueryStringFormat.RFC_3986_STRICT
		);

		// Path spaces -> %20, query spaces -> %20 (no '+'), '&' encoded as %26
		assertEquals("/path%20with%20space?na%20me=va%20lue%26more", result);
	}

	@Test
	void strictFormat_replacesPlusWithPercent20InQuery() {
		String path = "/search";

		Map<String, Set<String>> params = Map.of(
				"q", Set.of("a b")
		);

		String result = encodedPathAndQueryParameters(
				path,
				params,
				QueryStringFormat.RFC_3986_STRICT
		);

		assertEquals("/search?q=a%20b", result);
		assertFalse(result.contains("+"), "RFC_3986_STRICT should not contain '+' for spaces");
	}

	@Test
	void pathWithMultipleAndTrailingSlashes_isEncodedSegmentWise() {
		String path = "/foo//bar baz/";

		Map<String, Set<String>> params = Map.of();

		String result = encodedPathAndQueryParameters(
				path,
				params,
				QueryStringFormat.RFC_3986_STRICT
		);

		// Expect: "/foo//bar%20baz/"
		assertEquals("/foo//bar%20baz/", result);
	}

	@Test
	void nullPath_throwsNullPointerException() {
		Map<String, Set<String>> params = Map.of();

		assertThrows(NullPointerException.class, () ->
				encodedPathAndQueryParameters(
						null,
						params,
						QueryStringFormat.RFC_3986_STRICT
				)
		);
	}

	@Test
	void nullQueryParameters_throwsNullPointerException() {
		assertThrows(NullPointerException.class, () ->
				encodedPathAndQueryParameters(
						"/test",
						null,
						QueryStringFormat.RFC_3986_STRICT
				)
		);
	}

	@Test
	void nullQueryStringFormat_throwsNullPointerException() {
		Map<String, Set<String>> params = Map.of("a", Set.of("b"));

		assertThrows(NullPointerException.class, () ->
				encodedPathAndQueryParameters(
						"/test",
						params,
						null
				)
		);
	}
}
