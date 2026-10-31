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
import com.soklet.internal.microhttp.Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

final class Round18RequestParsingRegressionTests {
	@Test
	void formComponentsAreDecodedAfterParsingTheAsciiCarrier() {
		for (String charset : List.of("UTF-16BE", "UTF-16LE")) {
			String encoded = charset.equals("UTF-16BE") ? "%00%78=%00%61" : "%78%00=%61%00";
			byte[] bytes = encoded.getBytes(StandardCharsets.US_ASCII);
			Request request = form(bytes, charset);
			assertEquals(Map.of("x", List.of("a")), request.getFormParameters());
			assertEquals("a", request.getFormParameter("x").orElseThrow());
			assertArrayEquals(bytes, request.getBody().orElseThrow());
			// The encoded carrier is not itself UTF-16 text; body text access remains independent.
			assertThrows(IllegalRequestBodyException.class, request::getBodyAsString);
			assertEquals("a", request.getFormParameter("x").orElseThrow());
		}
	}

	@Test
	void literalAndEscapedBytesCanCompleteOneMultibyteCharacterInEitherOrder() {
		for (byte[] body : List.of(new byte[]{'x', '=', '%', 'C', '3', (byte) 0xa9},
				new byte[]{'x', '=', (byte) 0xc3, '%', 'A', '9'})) {
			assertEquals(Map.of("x", List.of("é")), form(body, "UTF-8").getFormParameters());
		}
		assertEquals(Map.of("é", List.of("a")), form(new byte[]{'%', 'C', '3', (byte) 0xa9, '=', 'a'}, "UTF-8").getFormParameters());
	}

	@Test
	void formsKeepDelimitersWhitespaceEmptyOccurrencesAndByteOwnership() {
		byte[] bytes = "&&=first&id&id=&id=a+b&id=a%2Bb&x=%26%3D%25&literal= # &id=&&".getBytes(StandardCharsets.UTF_8);
		byte[] original = bytes.clone();
		Request request = form(bytes, "UTF-8");
		Map<String, List<String>> parameters = request.getFormParameters();
		assertEquals(List.of("first"), parameters.get(""));
		assertEquals(List.of("", "", "a b", "a+b", ""), parameters.get("id"));
		assertEquals(List.of("&=%"), parameters.get("x"));
		assertEquals(List.of(" # "), parameters.get("literal"));
		assertArrayEquals(original, bytes);
		assertArrayEquals(original, request.getBody().orElseThrow());
		assertSame(parameters, request.getFormParameters());
		assertThrows(UnsupportedOperationException.class, parameters::clear);
		assertThrows(UnsupportedOperationException.class, () -> parameters.get("id").clear());
	}

	@Test
	void malformedFormEscapesAndDecodedTextRemainStrictAndRedacted() {
		for (String encoded : List.of("secret=%", "secret=%1", "secret=%GG", "secret=%FF", "secret=%C3x%A9", "%FF=secret"))
			assertRedactedComponent(() -> form(encoded.getBytes(StandardCharsets.US_ASCII), "UTF-8").getFormParameters());
		assertRedactedComponent(() -> form("secret=%00".getBytes(StandardCharsets.US_ASCII), "UTF-16BE").getFormParameters());
		assertRedactedComponent(() -> form("secret=%E9".getBytes(StandardCharsets.US_ASCII), "US-ASCII").getFormParameters());
		assertEquals("é", form("x=%E9".getBytes(StandardCharsets.US_ASCII), "ISO-8859-1").getFormParameter("x").orElseThrow());
		IllegalRequestBodyException rawFailure = assertThrows(IllegalRequestBodyException.class,
				() -> form(new byte[]{'x', '=', (byte) 0xff}, "UTF-8").getFormParameters());
		assertEquals("Invalid character encoding in request body.", rawFailure.getMessage());
		assertNull(rawFailure.getCause());
	}

	@Test
	void formPairLimitCountsRepeatedNamesAndIgnoresEmptySeparatorSegments() {
		String accepted = "&&" + String.join("&&", Collections.nCopies(1_000, "x=%61")) + "&&";
		assertEquals(Collections.nCopies(1_000, "a"), form(accepted.getBytes(StandardCharsets.US_ASCII), "UTF-8").getFormParameters().get("x"));
		IllegalRequestException failure = assertThrows(IllegalRequestException.class,
				() -> form((accepted + "x=a").getBytes(StandardCharsets.US_ASCII), "UTF-8").getFormParameters());
		assertEquals("Too many form parameters.", failure.getMessage());
		assertNull(failure.getCause());
	}

	@Test
	void bothRequestHeaderRepresentationsEnforceTheSamePreflightNameBoundary() {
		List<String> names = IntStream.range(0, 129).mapToObj(index -> "X-Header-" + index).toList();
		List<String> accepted = List.of(String.join(",", names.subList(0, 64)), String.join(",", names.subList(64, 128)));
		List<String> rejected = List.of(accepted.getFirst(), accepted.getLast() + "," + names.getLast());
		assertEquals(names.subList(0, 128), new ArrayList<>(requestPreflight(accepted, false).getAccessControlRequestHeaders()));
		assertEquals(names.subList(0, 128), new ArrayList<>(requestPreflight(accepted, true).getAccessControlRequestHeaders()));
		assertEquals(names.subList(0, 128), new ArrayList<>(CorsPreflight.fromHeaders(preflightHeaders(accepted)).orElseThrow().getAccessControlRequestHeaders()));
		for (boolean physical : List.of(false, true))
			assertPreflightLimit(() -> requestPreflight(rejected, physical));
		assertPreflightLimit(() -> CorsPreflight.fromHeaders(preflightHeaders(rejected)));
	}

	@Test
	void preflightCapCountsPhysicalNamesBeforeDeduplicationAcrossFieldLines() {
		List<String> accepted = List.of(String.join(",", Collections.nCopies(64, " \tX-Same\t ")), ",," + String.join(",", Collections.nCopies(64, "X-Same")) + ",\t,");
		List<String> rejected = List.of(accepted.getFirst(), accepted.getLast(), "X-Same");
		for (boolean physical : List.of(false, true)) {
			assertEquals(Set.of("X-Same"), requestPreflight(accepted, physical).getAccessControlRequestHeaders());
			assertPreflightLimit(() -> requestPreflight(rejected, physical));
		}
		assertEquals(Set.of("X-Same"), CorsPreflight.fromHeaders(preflightHeaders(accepted)).orElseThrow().getAccessControlRequestHeaders());
		assertPreflightLimit(() -> CorsPreflight.fromHeaders(preflightHeaders(rejected)));
	}

	private static Request form(byte[] body, String charset) {
		return Request.withRawUrl(HttpMethod.POST, "/form")
				.headers(Map.of("Content-Type", List.of("application/x-www-form-urlencoded; charset=" + charset)))
				.body(body).build();
	}

	private static Map<String, List<String>> preflightHeaders(List<String> names) {
		return Map.of("Origin", List.of("https://example.test"), "Access-Control-Request-Method", List.of("GET"), "Access-Control-Request-Headers", names);
	}

	private static CorsPreflight requestPreflight(List<String> names, boolean physical) {
		if (!physical)
			return Request.withRawUrl(HttpMethod.OPTIONS, "/").headers(preflightHeaders(names)).build().getCorsPreflight().orElseThrow();
		List<Header> headers = new ArrayList<>();
		headers.add(new Header("Origin", "https://example.test"));
		headers.add(new Header("Access-Control-Request-Method", "GET"));
		for (String value : names)
			headers.add(new Header("Access-Control-Request-Headers", value));
		return Request.withRawUrl(HttpMethod.OPTIONS, "/").microhttpHeaders(headers).build().getCorsPreflight().orElseThrow();
	}

	private static void assertRedactedComponent(Executable action) {
		IllegalRequestException failure = assertThrows(IllegalRequestException.class, action);
		assertFalse(failure.getMessage().contains("secret"));
		assertFalse(failure.getMessage().contains("%"));
		assertNull(failure.getCause());
	}

	private static void assertPreflightLimit(Executable action) {
		IllegalRequestException failure = assertThrows(IllegalRequestException.class, action);
		assertEquals("Too many CORS preflight request header names.", failure.getMessage());
		assertNull(failure.getCause());
	}
}
