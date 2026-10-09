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

import com.soklet.exception.IllegalRequestBodyException;
import com.soklet.exception.IllegalRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RequestTextDecodingTests {
	private static final List<String> INVALID_UTF8 = List.of(
			"%FF", "%FE", "%80", "%C3", "%E2%82", "%F0%9F%8D", "%C0%AF",
			"%E0%80%AF", "%ED%A0%80", "%F4%90%80%80", "%C3x%A9");

	@Test
	void malformedPathAndEveryQueryComponentAreRejectedBeforeLazySelection() {
		for (String encoded : INVALID_UTF8) {
			assertRedactedUrl(() -> Request.fromRawUrl(HttpMethod.GET, "/secret-" + encoded));
			assertRedactedUrl(() -> Request.fromRawUrl(HttpMethod.GET, "/?secret-" + encoded + "=ok"));
			assertRedactedUrl(() -> Request.fromRawUrl(HttpMethod.GET, "/?selected=ok&secret=" + encoded));
		}
	}

	@Test
	void malformedParameterNamesAndValuesAreRejectedInBothQueryFormats() {
		for (QueryFormat format : QueryFormat.values()) {
			assertRedactedUrl(() -> Utilities.extractQueryParametersFromQuery("%FF=a&%FE=b", format));
			assertRedactedUrl(() -> Utilities.extractQueryParametersFromQuery("id=%FF&id=%FE&id=%EF%BF%BD", format));
			for (String encoded : INVALID_UTF8) {
				assertRedactedUrl(() -> Utilities.extractQueryParametersFromQuery("secret-" + encoded + "=ok", format));
				assertRedactedUrl(() -> Utilities.extractQueryParametersFromQuery("secret=" + encoded, format));
			}
		}
	}

	@Test
	void validUnicodeIncludingReplacementCharacterRemainsDistinctAndUsable() {
		String encoded = "%C3%A9%F0%9F%8D%AA%EF%BF%BD";
		String decoded = "é🍪\uFFFD";
		Request request = Request.fromRawUrl(HttpMethod.GET, "/" + encoded + "?id=" + encoded);
		assertEquals("/" + decoded, request.getPath());
		assertEquals(decoded, request.getQueryParameter("id").orElseThrow());
		assertEquals(List.of(decoded), request.getQueryParameters().get("id"));
		assertEquals(List.of(decoded), Utilities.extractCookiesFromHeaders(Map.of("Cookie", List.of("id=" + encoded))).get("id"));
		assertEquals(List.of("a b"), Utilities.extractQueryParametersFromQuery("id=a+b", QueryFormat.X_WWW_FORM_URLENCODED).get("id"));
		assertEquals(List.of("a+b"), Utilities.extractQueryParametersFromQuery("id=a+b", QueryFormat.RFC_3986_STRICT).get("id"));
	}

	@Test
	void malformedCookieBytesAndUnpairedSurrogatesAreRejected() {
		for (String encoded : INVALID_UTF8)
			assertRedactedUrl(() -> Utilities.extractCookiesFromHeaders(Map.of("Cookie", List.of("secret=" + encoded))));
		for (String invalid : List.of("\uD800", "\uDC00")) {
			assertRedactedUrl(() -> Utilities.extractQueryParametersFromQuery("secret=" + invalid, QueryFormat.RFC_3986_STRICT));
			assertRedactedUrl(() -> Utilities.extractCookiesFromHeaders(Map.of("Cookie", List.of("secret=" + invalid))));
		}
		assertEquals(List.of("🍪"), Utilities.extractCookiesFromHeaders(Map.of("Cookie", List.of("id=🍪"))).get("id"));
		assertEquals(List.of("100%"), Utilities.extractCookiesFromHeaders(Map.of("Cookie", List.of("id=100%"))).get("id"));
	}

	@Test
	void namedCookieLookupsStrictlyDecodeOnlyTheConsumedName() {
		Request request = Request.withPath(HttpMethod.GET, "/").headers(Map.of("Cookie", List.of(
				"session=abc; legacy=Jos%E9; %73ession=%FF; Session=%C3; ordinary=é"))).build();
		assertEquals(Optional.of("abc"), request.getCookie("session"));
		assertEquals(Optional.of("é"), request.getCookie("ordinary"));
		assertEquals(Optional.empty(), request.getCookie("missing"));
		assertRedactedUrl(() -> request.getCookie("legacy"));
		assertRedactedUrl(() -> request.getCookie("%73ession"));
		assertRedactedUrl(() -> request.getCookie("Session"));
		assertRedactedUrl(request::getCookies);
		assertEquals(Optional.of("abc"), request.getCookie("session"));
		Request duplicates = Request.withPath(HttpMethod.GET, "/").headers(Map.of("Cookie", List.of(
				"session=abc; legacy=%FF", "session=abc"))).build();
		assertThrows(com.soklet.exception.IllegalRequestCookieException.class, () -> duplicates.getCookie("session"));
		Request encodedDuplicates = Request.withPath(HttpMethod.GET, "/").headers(Map.of("Cookie", List.of(
				"%73ession=abc; legacy=%FF; %73ession=abc"))).build();
		assertThrows(com.soklet.exception.IllegalRequestCookieException.class, () -> encodedDuplicates.getCookie("%73ession"));
	}

	@Test
	void bodyAndMultipartTextRejectMalformedBytesWithoutChangingRawData() {
		for (String encoded : INVALID_UTF8.subList(0, 10)) {
			byte[] bytes = percentBytes(encoded);
			Request request = Request.withPath(HttpMethod.POST, "/").body(bytes).build();
			MultipartField field = MultipartField.with("secret", bytes).build();
			assertArrayEquals(bytes, request.getBody().orElseThrow());
			assertArrayEquals(bytes, field.getData().orElseThrow());
			assertRedactedBody(request::getBodyAsString);
			assertRedactedBody(request::getBodyAsString);
			assertRedactedBody(field::getDataAsString);
		}
		byte[] valid = "é🍪\uFFFD".getBytes(StandardCharsets.UTF_8);
		assertEquals("é🍪\uFFFD", Request.withPath(HttpMethod.POST, "/").body(valid).build().getBodyAsString().orElseThrow());
		assertEquals("é🍪\uFFFD", MultipartField.with("field", valid).build().getDataAsString().orElseThrow());
	}

	@Test
	void formsRejectMalformedRawAndPercentEncodedNamesAndValues() {
		for (String encoded : INVALID_UTF8) {
			Request encodedForm = form(("secret=" + encoded).getBytes(StandardCharsets.US_ASCII));
			assertRedactedUrl(encodedForm::getFormParameters);
			assertRedactedUrl(() -> form(("secret-" + encoded + "=ok").getBytes(StandardCharsets.US_ASCII)).getFormParameter("selected"));
		}
		assertRedactedBody(() -> form(new byte[]{'i', 'd', '=', (byte) 0xff}).getFormParameters());
		assertEquals("\uFFFD", form("id=%EF%BF%BD".getBytes(StandardCharsets.US_ASCII)).getFormParameter("id").orElseThrow());
	}

	@Test
	void selectedCharsetsAreHonoredWithStrictErrorHandling() {
		Request latin = Request.withPath(HttpMethod.POST, "/")
				.headers(Map.of("Content-Type", List.of("text/plain; charset=ISO-8859-1"))).body(new byte[]{(byte) 0xff}).build();
		assertEquals("ÿ", latin.getBodyAsString().orElseThrow());
		assertEquals("ÿ", MultipartField.with("field", new byte[]{(byte) 0xff}).charset(StandardCharsets.ISO_8859_1).build().getDataAsString().orElseThrow());
		assertEquals(List.of("ÿ"), Utilities.extractQueryParametersFromQuery("id=%FF", QueryFormat.X_WWW_FORM_URLENCODED, StandardCharsets.ISO_8859_1).get("id"));
		assertRedactedUrl(() -> Utilities.extractQueryParametersFromQuery("secret=%FF", QueryFormat.RFC_3986_STRICT, StandardCharsets.US_ASCII));
		assertRedactedBody(() -> Request.withPath(HttpMethod.POST, "/")
				.headers(Map.of("Content-Type", List.of("text/plain; charset=UTF-16BE"))).body(new byte[]{0}).build().getBodyAsString());
	}

	@Test
	void multipartMetadataDecodingRejectsMalformedUtf8AndPreservesValidText() {
		for (String disposition : List.of(
				"form-data; name*=UTF-8''secret%FF",
				"form-data; name=\"=?UTF-8?B?/w==?=\"",
				"form-data; name=\"field\"; filename*=UTF-8''secret%FF"))
			assertRedactedBody(() -> multipart(disposition.getBytes(StandardCharsets.UTF_8)).getMultipartFields());
		byte[] rawInvalidHeader = "form-data; name=\"secret-ÿ\"".getBytes(StandardCharsets.ISO_8859_1);
		assertRedactedBody(() -> multipart(rawInvalidHeader).getMultipartFields());
		assertTrue(multipart("form-data; name*=UTF-8''%C3%A9".getBytes(StandardCharsets.US_ASCII)).getMultipartFields().containsKey("é"));
	}

	private static Request form(byte[] bytes) {
		return Request.withPath(HttpMethod.POST, "/")
				.headers(Map.of("Content-Type", List.of("application/x-www-form-urlencoded; charset=UTF-8"))).body(bytes).build();
	}

	private static Request multipart(byte[] disposition) {
		byte[] prefix = "--test\r\nContent-Disposition: ".getBytes(StandardCharsets.US_ASCII);
		byte[] suffix = "\r\n\r\nx\r\n--test--\r\n".getBytes(StandardCharsets.US_ASCII);
		byte[] body = new byte[prefix.length + disposition.length + suffix.length];
		System.arraycopy(prefix, 0, body, 0, prefix.length);
		System.arraycopy(disposition, 0, body, prefix.length, disposition.length);
		System.arraycopy(suffix, 0, body, prefix.length + disposition.length, suffix.length);
		return Request.withPath(HttpMethod.POST, "/")
				.headers(Map.of("Content-Type", List.of("multipart/form-data; boundary=test"))).body(body).build();
	}

	private static byte[] percentBytes(String encoded) {
		byte[] bytes = new byte[encoded.length() / 3];
		for (int index = 0; index < bytes.length; index++)
			bytes[index] = (byte) Integer.parseInt(encoded.substring(index * 3 + 1, index * 3 + 3), 16);
		return bytes;
	}

	private static void assertRedactedUrl(org.junit.jupiter.api.function.Executable action) {
		IllegalRequestException exception = assertThrows(IllegalRequestException.class, action);
		assertFalse(exception.getMessage().contains("secret"));
		assertFalse(exception.getMessage().contains("%"));
		assertNull(exception.getCause());
	}

	private static void assertRedactedBody(org.junit.jupiter.api.function.Executable action) {
		IllegalRequestBodyException exception = assertThrows(IllegalRequestBodyException.class, action);
		assertEquals("Invalid character encoding in request body.", exception.getMessage());
		assertNull(exception.getCause());
	}
}
