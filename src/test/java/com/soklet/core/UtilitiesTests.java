/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.core;

import org.junit.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

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
		List<Locale> locales = Utilities.localesFromAcceptLanguageHeaderValue(acceptLanguageHeaderValue);

		assertEquals("Locales don't match", List.of(
				Locale.forLanguageTag("fr-CH"),
				Locale.forLanguageTag("fr"),
				Locale.forLanguageTag("en"),
				Locale.forLanguageTag("de")
		), locales);

		locales = Utilities.localesFromAcceptLanguageHeaderValue("");

		assertEquals("Blank locale string mishandled", List.of(), locales);

		locales = Utilities.localesFromAcceptLanguageHeaderValue("xxxx");

		assertEquals("Junk locale string mishandled", List.of(), locales);
	}

	@Test
	public void clientUrlPrefixFromHeaders() {
		String clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of()).orElse(null);
		assertEquals("Client URL prefix erroneously detected from incomplete header data", null, clientUrlPrefix);

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com")
		)).orElse(null);
		assertEquals("Client URL prefix erroneously detected from incomplete header data", null, clientUrlPrefix);

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com:443")
		)).orElse(null);
		assertEquals("Client URL prefix erroneously detected from incomplete header data", null, clientUrlPrefix);

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Forwarded", Set.of("for=12.34.56.78;host=example.com;proto=https, for=23.45.67.89")
		)).orElse(null);
		assertEquals("Client URL prefix was not correctly detected", "https://example.com", clientUrlPrefix);

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com"),
				"X-Forwarded-Proto", Set.of("https")
		)).orElse(null);
		assertEquals("Client URL prefix was not correctly detected", "https://www.soklet.com", clientUrlPrefix);

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com"),
				"X-Forwarded-Proto", Set.of("https")
		)).orElse(null);
		assertEquals("Client URL prefix was not correctly detected", "https://www.soklet.com", clientUrlPrefix);

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"X-Forwarded-Host", Set.of("www.soklet.com"),
				"X-Forwarded-Protocol", Set.of("https")
		)).orElse(null);
		assertEquals("Client URL prefix was not correctly detected", "https://www.soklet.com", clientUrlPrefix);
	}

	@Test
	public void contentTypeFromHeaders() {
		String contentType = Utilities.extractContentTypeFromHeaderValue("text/html").orElse(null);
		assertEquals("Content type was not correctly detected", "text/html", contentType);

		contentType = Utilities.extractContentTypeFromHeaderValue("").orElse(null);
		assertEquals("Absence of content type was not correctly detected", null, contentType);

		contentType = Utilities.extractContentTypeFromHeaderValue(null).orElse(null);
		assertEquals("Absence of content type was not correctly detected", null, contentType);

		contentType = Utilities.extractContentTypeFromHeaderValue("text/html; charset=utf-8").orElse(null);
		assertEquals("Content type was not correctly detected", "text/html", contentType);

		contentType = Utilities.extractContentTypeFromHeaderValue("text/html   ; charset=utf-8").orElse(null);
		assertEquals("Content type was not correctly detected", "text/html", contentType);

		contentType = Utilities.extractContentTypeFromHeaderValue("text/html;charset=utf-8").orElse(null);
		assertEquals("Content type was not correctly detected", "text/html", contentType);
	}

	@Test
	public void charsetFromHeaders() {
		Charset charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=utf-8").orElse(null);
		assertEquals("Charset was not correctly detected", StandardCharsets.UTF_8, charset);

		charset = Utilities.extractCharsetFromHeaderValue("text/html").orElse(null);
		assertEquals("Absence of charset was not correctly detected", null, charset);

		charset = Utilities.extractCharsetFromHeaderValue("text/html;").orElse(null);
		assertEquals("Absence of charset was not correctly detected", null, charset);

		charset = Utilities.extractCharsetFromHeaderValue(";charset=utf-8;").orElse(null);
		assertEquals("Charset was not correctly detected", StandardCharsets.UTF_8, charset);

		charset = Utilities.extractCharsetFromHeaderValue(";charset=utf-8   ;  ").orElse(null);
		assertEquals("Charset was not correctly detected", StandardCharsets.UTF_8, charset);

		charset = Utilities.extractCharsetFromHeaderValue("multipart/form-data; boundary=something").orElse(null);
		assertEquals("Absence of charset was not correctly detected", null, charset);

		charset = Utilities.extractCharsetFromHeaderValue("multipart/form-data; charset=utf-8; boundary=something").orElse(null);
		assertEquals("Charset was not correctly detected", StandardCharsets.UTF_8, charset);

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=ISO-8859-1").orElse(null);
		assertEquals("Charset was not correctly detected", StandardCharsets.ISO_8859_1, charset);

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=utf-16").orElse(null);
		assertEquals("Charset was not correctly detected", StandardCharsets.UTF_16, charset);

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=ascii").orElse(null);
		assertEquals("Charset was not correctly detected", StandardCharsets.US_ASCII, charset);

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=WINDOWS-1251").orElse(null);
		assertEquals("Charset was not correctly detected", Charset.forName("windows-1251"), charset);

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=KOI8-R").orElse(null);
		assertEquals("Charset was not correctly detected", Charset.forName("koi8-r"), charset);
	}
}
