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

package com.soklet.core;

import com.soklet.Utilities;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class UtilitiesTests {
	@Test
	public void normalizedPathForUrl() {
		Assertions.assertEquals("/", Utilities.normalizedPathForUrl("https://www.google.com/"));
		Assertions.assertEquals("/", Utilities.normalizedPathForUrl("https://www.google.com"));
		Assertions.assertEquals("/", Utilities.normalizedPathForUrl(""));
		Assertions.assertEquals("/", Utilities.normalizedPathForUrl("/"));
		Assertions.assertEquals("/test", Utilities.normalizedPathForUrl("/test"));
		Assertions.assertEquals("/test", Utilities.normalizedPathForUrl("/test/"));
		Assertions.assertEquals("/test", Utilities.normalizedPathForUrl("/test//"));
	}

	@Test
	public void acceptLanguages() {
		String acceptLanguageHeaderValue = "fr-CH, fr;q=0.9, en;q=0.8, de;q=0.7, *;q=0.5";
		List<Locale> locales = Utilities.localesFromAcceptLanguageHeaderValue(acceptLanguageHeaderValue);

		Assertions.assertEquals(List.of(
				Locale.forLanguageTag("fr-CH"),
				Locale.forLanguageTag("fr"),
				Locale.forLanguageTag("en"),
				Locale.forLanguageTag("de")
		), locales, "Locales don't match");

		locales = Utilities.localesFromAcceptLanguageHeaderValue("");

		Assertions.assertEquals(List.of(), locales, "Blank locale string mishandled");

		locales = Utilities.localesFromAcceptLanguageHeaderValue("xxxx");

		Assertions.assertEquals(List.of(), locales, "Junk locale string mishandled");
	}

	@Test
	public void clientUrlPrefixFromHeaders() {
		String clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of()).orElse(null);
		Assertions.assertEquals(null, clientUrlPrefix, "Client URL prefix erroneously detected from incomplete header data");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com")
		)).orElse(null);
		Assertions.assertEquals(null, clientUrlPrefix, "Client URL prefix erroneously detected from incomplete header data");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com:443")
		)).orElse(null);
		Assertions.assertEquals(null, clientUrlPrefix, "Client URL prefix erroneously detected from incomplete header data");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Forwarded", Set.of("for=12.34.56.78;host=example.com;proto=https, for=23.45.67.89")
		)).orElse(null);
		Assertions.assertEquals("https://example.com", clientUrlPrefix, "Client URL prefix was not correctly detected");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com"),
				"X-Forwarded-Proto", Set.of("https")
		)).orElse(null);
		Assertions.assertEquals("https://www.soklet.com", clientUrlPrefix, "Client URL prefix was not correctly detected");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"Host", Set.of("www.soklet.com"),
				"X-Forwarded-Proto", Set.of("https")
		)).orElse(null);
		Assertions.assertEquals("https://www.soklet.com", clientUrlPrefix, "Client URL prefix was not correctly detected");

		clientUrlPrefix = Utilities.extractClientUrlPrefixFromHeaders(Map.of(
				"X-Forwarded-Host", Set.of("www.soklet.com"),
				"X-Forwarded-Protocol", Set.of("https")
		)).orElse(null);
		Assertions.assertEquals("https://www.soklet.com", clientUrlPrefix, "Client URL prefix was not correctly detected");
	}

	@Test
	public void contentTypeFromHeaders() {
		String contentType = Utilities.extractContentTypeFromHeaderValue("text/html").orElse(null);
		Assertions.assertEquals("text/html", contentType, "Content type was not correctly detected");

		contentType = Utilities.extractContentTypeFromHeaderValue("").orElse(null);
		Assertions.assertEquals(null, contentType, "Absence of content type was not correctly detected");

		contentType = Utilities.extractContentTypeFromHeaderValue(null).orElse(null);
		Assertions.assertEquals(null, contentType, "Absence of content type was not correctly detected");

		contentType = Utilities.extractContentTypeFromHeaderValue("text/html; charset=UTF-8").orElse(null);
		Assertions.assertEquals("text/html", contentType, "Content type was not correctly detected");

		contentType = Utilities.extractContentTypeFromHeaderValue("text/html   ; charset=UTF-8").orElse(null);
		Assertions.assertEquals("text/html", contentType, "Content type was not correctly detected");

		contentType = Utilities.extractContentTypeFromHeaderValue("text/html;charset=UTF-8").orElse(null);
		Assertions.assertEquals("text/html", contentType, "Content type was not correctly detected");
	}

	@Test
	public void charsetFromHeaders() {
		Charset charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=UTF-8").orElse(null);
		Assertions.assertEquals(StandardCharsets.UTF_8, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html").orElse(null);
		Assertions.assertEquals(null, charset, "Absence of charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html;").orElse(null);
		Assertions.assertEquals(null, charset, "Absence of charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue(";charset=UTF-8;").orElse(null);
		Assertions.assertEquals(StandardCharsets.UTF_8, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue(";charset=UTF-8   ;  ").orElse(null);
		Assertions.assertEquals(StandardCharsets.UTF_8, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("multipart/form-data; boundary=something").orElse(null);
		Assertions.assertEquals(null, charset, "Absence of charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("multipart/form-data; charset=UTF-8; boundary=something").orElse(null);
		Assertions.assertEquals(StandardCharsets.UTF_8, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=ISO-8859-1").orElse(null);
		Assertions.assertEquals(StandardCharsets.ISO_8859_1, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=utf-16").orElse(null);
		Assertions.assertEquals(StandardCharsets.UTF_16, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=ascii").orElse(null);
		Assertions.assertEquals(StandardCharsets.US_ASCII, charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=WINDOWS-1251").orElse(null);
		Assertions.assertEquals(Charset.forName("windows-1251"), charset, "Charset was not correctly detected");

		charset = Utilities.extractCharsetFromHeaderValue("text/html; charset=KOI8-R").orElse(null);
		Assertions.assertEquals(Charset.forName("koi8-r"), charset, "Charset was not correctly detected");
	}
}
