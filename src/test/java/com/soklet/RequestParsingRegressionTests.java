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

import com.soklet.exception.IllegalRequestException;
import com.soklet.exception.IllegalFormParameterException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RequestParsingRegressionTests {
	@Test
	void browserQueryCharactersAreAcceptedWithoutChangingPathOrStrictDecoding() {
		for (String value : List.of("a|b", "{x}", "a^b", "a`b", "a\\b")) {
			for (String prefix : List.of("/files/a|b", "https://example.com/files/example")) {
				String target = prefix + "?selected=" + value + "&tail=%C3%A9";
				Request request = Request.fromRawUrl(HttpMethod.GET, target);
				assertEquals(prefix.startsWith("/") ? prefix : "/files/example", request.getRawPath());
				assertEquals(value, request.getQueryParameter("selected").orElseThrow());
				assertEquals("é", request.getQueryParameter("tail").orElseThrow());
				assertEquals(request.getQueryParameters(), Utilities.extractQueryParametersFromUrl(target, QueryFormat.RFC_3986_STRICT));
				assertEquals(request.getRawQuery(), Utilities.extractRawQueryFromUrl(target));
			}
		}
		for (String value : List.of("a b", "a\tb", "a\u0000b", "a\u007fb", "a\"b", "a<b", "%ZZ", "%FF", "%C3"))
			assertThrows(IllegalRequestException.class, () -> Request.fromRawUrl(HttpMethod.GET, "/?selected=ok&other=" + value));
		assertEquals(List.of("ÿ"), Utilities.extractQueryParametersFromUrl("/?selected=%FF", QueryFormat.RFC_3986_STRICT, StandardCharsets.ISO_8859_1).get("selected"));
		assertEquals(Optional.of("selected={x}"), Utilities.extractRawQueryFromUrl("/?selected={x}#fragment?ignored=yes"));
	}

	@Test
	void copiedEncodedQuestionMarkPathsPreserveTheDecodedAndRawForms() {
		Request original = Request.fromRawUrl(HttpMethod.HEAD, "/files/a%3Fb?selected=ok");
		Request copy = original.copy().httpMethod(HttpMethod.GET).finish();
		assertEquals("/files/a?b", copy.getPath());
		assertEquals("/files/a%3Fb", copy.getRawPath());
		assertEquals(original.getRawQuery(), copy.getRawQuery());
		assertThrows(IllegalRequestException.class, () -> original.copy().path("/files/a?b").finish());
		assertThrows(IllegalRequestException.class, () -> Request.fromPath(HttpMethod.GET, "/files/a?b"));
	}

	@Test
	void originFormPreservesEveryRawPathComponentAndExistingDecodedNormalization() {
		Map<String, String> paths = Map.of(
				"//x/admin", "/x/admin",
				"///x//admin/", "/x/admin",
				"//x/../admin", "/admin",
				"//x/%C3%A9", "/x/é",
				"//", "/");
		for (Map.Entry<String, String> entry : paths.entrySet()) {
			String target = entry.getKey() + "?selected=one&selected=two";
			Request request = Request.fromRawUrl(HttpMethod.GET, target);
			assertEquals(entry.getKey(), Utilities.extractPathFromUrl(target, false), target);
			assertEquals(entry.getValue(), Utilities.extractPathFromUrl(target, true), target);
			assertEquals(entry.getKey(), request.getRawPath(), target);
			assertEquals(entry.getValue(), request.getPath(), target);
			assertEquals(target, request.getRawPathAndQuery(), target);
			assertEquals(Optional.of("selected=one&selected=two"), request.getRawQuery(), target);
			assertEquals(List.of("one", "two"), request.getQueryParameters().get("selected"), target);
			assertEquals(Optional.of("selected=one&selected=two"), Utilities.extractRawQueryFromUrl(target), target);
			assertEquals(request.getQueryParameters(), Utilities.extractQueryParametersFromUrl(target, QueryFormat.RFC_3986_STRICT), target);
		}
	}

	@Test
	void absoluteFormAndOptionsAsteriskKeepExistingPathAndQueryBehavior() {
		Request request = Request.fromRawUrl(HttpMethod.GET, "https://example.com//x/admin?selected=ok");
		assertEquals("//x/admin", request.getRawPath());
		assertEquals("/x/admin", request.getPath());
		assertEquals(Optional.of("ok"), request.getQueryParameter("selected"));
		Request asterisk = Request.fromRawUrl(HttpMethod.OPTIONS, "*");
		assertEquals("*", asterisk.getRawPath());
		assertEquals("*", asterisk.getPath());
		assertEquals(Optional.empty(), asterisk.getRawQuery());
		assertEquals(Map.of(), asterisk.getQueryParameters());
	}

	@Test
	void leadingSlashesDoNotBypassEncodedSlashOrStrictTextValidation() {
		for (String target : List.of("//x%2Fadmin", "//x/%FF", "//x/admin?selected=ok&secret=%FF", "//?secret=%ZZ"))
			assertThrows(IllegalRequestException.class, () -> Request.fromRawUrl(HttpMethod.GET, target), target);
	}

	@Test
	void formLiteralsAndEveryLaterPairArePreserved() {
		String query = "id= one#two \"{}|\\?=+%2B%26%3D&id=last&tail=three";
		Map<String, List<String>> parameters = Utilities.extractQueryParametersFromQuery(query, QueryFormat.X_WWW_FORM_URLENCODED);
		assertEquals(List.of(" one#two \"{}|\\?= +&=", "last"), parameters.get("id"));
		assertEquals(List.of("three"), parameters.get("tail"));
		assertEquals(parameters, form(query, "application/x-www-form-urlencoded").getFormParameters());
		assertThrows(UnsupportedOperationException.class, () -> parameters.put("extra", List.of("value")));
		assertThrows(UnsupportedOperationException.class, () -> parameters.get("id").add("extra"));
	}

	@Test
	void rawPairsPreserveWhitespaceAndFastLookupMatchesTheFullMap() {
		String query = " id = value &id= one &id= one &tail= ";
		for (QueryFormat format : QueryFormat.values()) {
			Map<String, List<String>> parameters = Utilities.extractQueryParametersFromQuery(query, format);
			assertEquals(List.of(" value "), parameters.get(" id "));
			assertEquals(List.of(" one ", " one "), parameters.get("id"));
			assertEquals(List.of(" "), parameters.get("tail"));
			for (Map.Entry<String, List<String>> entry : parameters.entrySet())
				assertEquals(Optional.of(entry.getValue()), Utilities.extractQueryParameterValuesFromQuery(query, entry.getKey(), format, StandardCharsets.UTF_8));
			assertEquals(Optional.empty(), Utilities.extractQueryParameterValuesFromQuery(query, "absent", format, StandardCharsets.UTF_8));
		}
	}

	@Test
	void directPairsKeepBlankOccurrencesAndEmptyNames() {
		Request request = form("&=value&&bare&empty=&empty=&blank=+&", "application/x-www-form-urlencoded");
		assertEquals(Map.of("", List.of("value"), "bare", List.of(""), "empty", List.of("", ""), "blank", List.of(" ")), request.getFormParameters());
		assertEquals(Optional.of(""), request.getFormParameter("bare"));
		assertThrows(IllegalFormParameterException.class, () -> request.getFormParameter("empty"));
	}

	@Test
	void rawPairsAndUrlFragmentsRemainSeparate() {
		assertEquals(Map.of("id", List.of("one#two"), "tail", List.of("three")),
				Utilities.extractQueryParametersFromQuery("id=one#two&tail=three", QueryFormat.RFC_3986_STRICT));
		assertEquals(Map.of("id", List.of("one")),
				Utilities.extractQueryParametersFromUrl("https://example.com/?id=one#two&tail=three", QueryFormat.RFC_3986_STRICT));
		assertThrows(IllegalRequestException.class, () -> Utilities.extractQueryParametersFromUrl("/?id=one two", QueryFormat.RFC_3986_STRICT));
	}

	@Test
	void literalHashDoesNotHideMalformedTextInLaterFormPairs() {
		for (String invalid : List.of("%FF", "%ZZ", "\uD800")) {
			IllegalRequestException exception = assertThrows(IllegalRequestException.class,
					() -> Utilities.extractQueryParametersFromQuery("id=one#two&secret=" + invalid, QueryFormat.X_WWW_FORM_URLENCODED));
			assertFalse(exception.getMessage().contains("secret"));
			assertFalse(exception.getMessage().contains("%"));
			assertNull(exception.getCause());
		}
	}

	@Test
	void charsetCanFollowOtherParametersIncludingQuotedSemicolonsAndEscapedQuotes() {
		for (String contentType : List.of(
				"text/plain; profile=test; charset=ISO-8859-1",
				"text/plain; profile=\"charset=UTF-8; not-a-parameter\"; ChArSeT = \"ISO-8859-1\"; other=value",
				"text/plain; profile=\"escaped\\\";charset=UTF-8\"; charset=ISO-8859-1"))
			assertEquals(Optional.of(StandardCharsets.ISO_8859_1), Utilities.extractCharsetFromHeaderValue(contentType), contentType);
		assertEquals(Optional.empty(), Utilities.extractCharsetFromHeaderValue("text/plain; profile=\"charset=ISO-8859-1\""));
		assertEquals(Optional.of(StandardCharsets.UTF_8), Utilities.extractCharsetFromHeaderValue("text/plain; charset=\"UTF\\-8\""));
		assertEquals(Optional.of(StandardCharsets.UTF_8), Utilities.extractCharsetFromHeaderValue("text/plain; charset='UTF-8'"));
	}

	@Test
	void duplicateCharsetsAreRejectedWithoutExposingTheHeader() {
		for (String contentType : List.of(
				"text/plain; charset=UTF-8; charset=UTF-8; secret=value",
				"text/plain; charset=UTF8; CHARSET=ISO-8859-1; secret=value",
				"text/plain; charset=unknown-secret; charset=UTF-8",
				"text/plain; charset=; charset=UTF-8; secret=value")) {
			IllegalRequestException exception = assertThrows(IllegalRequestException.class,
					() -> Utilities.extractCharsetFromHeaderValue(contentType));
			assertEquals("Multiple charset parameters in Content-Type.", exception.getMessage());
			assertNull(exception.getCause());
			assertThrows(IllegalRequestException.class, () -> Request.withPath(HttpMethod.POST, "/")
					.headers(Map.of("Content-Type", List.of(contentType))).build());
		}
	}

	@Test
	void absentInvalidAndUnknownCharsetsKeepTheirDocumentedFallback() {
		for (String contentType : List.of("text/plain", "text/plain; profile=test", "text/plain; charset=unknown-charset",
				"text/plain; charset=", "text/plain; charset=\"UTF-8", "text/plain; charset=\"\"",
				"text/plain; charset=\"\\\"UTF-8\\\"\"", "text/plain; charset=not a charset"))
			assertEquals(Optional.empty(), Utilities.extractCharsetFromHeaderValue(contentType), contentType);
		assertEquals(Optional.empty(), Utilities.extractCharsetFromHeaderValue(null));
	}

	@Test
	void bodyAndFormUseTheLateCharsetForBothRawBytesAndPercentEscapes() {
		Request body = Request.withPath(HttpMethod.POST, "/")
				.headers(Map.of("Content-Type", List.of("text/plain; profile=test; charset=ISO-8859-1")))
				.body(new byte[]{(byte) 0xff}).build();
		assertEquals(Optional.of(StandardCharsets.ISO_8859_1), body.getCharset());
		assertEquals(Optional.of("ÿ"), body.getBodyAsString());
		String contentType = "application/x-www-form-urlencoded; profile=\"x;y\"; charset=ISO-8859-1";
		assertEquals(List.of("ÿ#tail", "ÿ"), form("id=ÿ#tail&id=%FF", contentType).getFormParameters().get("id"));
	}

	@Test
	void multipartFieldTextAlsoUsesItsLateCharset() {
		String body = "--test\r\nContent-Disposition: form-data; name=\"field\"\r\n"
				+ "Content-Type: text/plain; profile=\"x;y\"; charset=ISO-8859-1\r\n\r\nÿ\r\n--test--\r\n";
		Request request = Request.withPath(HttpMethod.POST, "/")
				.headers(Map.of("Content-Type", List.of("multipart/form-data; boundary=test")))
				.body(body.getBytes(StandardCharsets.ISO_8859_1)).build();
		MultipartField field = request.getMultipartField("field").orElseThrow();
		assertEquals(Optional.of(StandardCharsets.ISO_8859_1), field.getCharset());
		assertEquals(Optional.of("ÿ"), field.getDataAsString());
	}

	@Test
	void multipartPercentEscapeSpellingsRemainDistinctLiteralNames() {
		String body = "--test\r\nContent-Disposition: form-data; name=\"field%22name\"; filename=\"my%22quoted%22.txt\"\r\n\r\ndata\r\n--test--\r\n";
		Request request = Request.withPath(HttpMethod.POST, "/").headers(Map.of("Content-Type", List.of("multipart/form-data; boundary=test")))
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
		MultipartField field = request.getMultipartField("field%22name").orElseThrow();
		assertEquals(Optional.of("my%22quoted%22.txt"), field.getFilename());
		assertTrue(request.getMultipartField("field\"name").isEmpty());
	}

	private static Request form(String body, String contentType) {
		return Request.withPath(HttpMethod.POST, "/")
				.headers(Map.of("Content-Type", List.of(contentType)))
				.body(body.getBytes(StandardCharsets.ISO_8859_1)).build();
	}

	@Test
	void explicitEmptyNamesAreRetainedWhileSeparatorGapsAreIgnored() {
		for (QueryFormat format : QueryFormat.values()) {
			Map<String, List<String>> parameters = Utilities.extractQueryParametersFromQuery("&&=one&bare&=&=one&&", format);
			assertEquals(Map.of("", List.of("one", "", "one"), "bare", List.of("")), parameters);
			assertEquals(parameters.get(""), Utilities.extractQueryParameterValuesFromQuery("&&=one&bare&=&=one&&", "", format,
					StandardCharsets.UTF_8).orElseThrow());
			assertEquals(parameters, Utilities.extractQueryParametersFromQuery(Utilities.encodeQueryParameters(parameters, format), format));
		}
		Request query = Request.fromRawUrl(HttpMethod.GET, "/?&&=value&&");
		assertEquals(Optional.of("value"), query.getQueryParameter(""));
		assertEquals(Map.of("", List.of("value")), query.getQueryParameters());
		assertEquals(Optional.of("value"), query.getQueryParameter(""));
		assertEquals(query.getQueryParameters(), query.copy().finish().getQueryParameters());
		assertEquals(Map.of("", List.of("value", "")), form("=value&=", "application/x-www-form-urlencoded").getFormParameters());
		assertEquals(Map.of(), Utilities.extractQueryParametersFromQuery("&&", QueryFormat.RFC_3986_STRICT));
		assertEquals(Optional.empty(), Utilities.extractQueryParameterValuesFromQuery("&&", "", QueryFormat.RFC_3986_STRICT,
				StandardCharsets.UTF_8));
	}

	@Test
	void malformedValuesWithEmptyNamesAreNotSilentlyDiscarded() {
		assertThrows(IllegalRequestException.class, () -> Utilities.extractQueryParametersFromQuery("=%FF", QueryFormat.RFC_3986_STRICT));
		assertThrows(IllegalRequestException.class, () -> Utilities.extractQueryParameterValuesFromQuery("=%FF", "",
				QueryFormat.RFC_3986_STRICT, StandardCharsets.UTF_8));
	}
}
