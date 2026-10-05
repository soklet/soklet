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

import com.soklet.exception.IllegalFormParameterException;
import com.soklet.exception.IllegalMultipartFieldException;
import com.soklet.exception.IllegalQueryParameterException;
import com.soklet.exception.IllegalRequestCookieException;
import com.soklet.exception.IllegalRequestHeaderException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpValueListTests {
	@Test
	void queryListsPreserveDuplicatesAndPerNameOrderWhileRawQueryPreservesInterleaving() {
		Request request = Request.withRawUrl(HttpMethod.GET, "/?id=one&other=x&id=one&id=two").build();
		assertEquals(List.of("one", "one", "two"), request.getQueryParameters().get("id"));
		assertEquals("id=one&other=x&id=one&id=two", request.getRawQuery().orElseThrow());
		assertThrows(IllegalQueryParameterException.class, () -> request.getQueryParameter("id"));
		assertEquals("id=one&id=one&id=two&other=x", Utilities.encodeQueryParameters(request.getQueryParameters(), QueryFormat.RFC_3986_STRICT));
		assertThrows(UnsupportedOperationException.class, () -> request.getQueryParameters().get("id").clear());
		assertThrows(UnsupportedOperationException.class, () -> Utilities.extractQueryParametersFromUrl("/?id=x", QueryFormat.RFC_3986_STRICT).clear());
	}

	@Test
	void requestHeadersMergeCaseVariantsInEncounterOrderAndSnapshotNestedLists() {
		List<String> first = new ArrayList<>(List.of("one", "one"));
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("X-Value", first);
		headers.put("x-value", List.of("two"));
		Request request = Request.withPath(HttpMethod.GET, "/").headers(headers).build();
		first.clear();
		headers.clear();
		assertEquals(List.of("one", "one", "two"), request.getHeaders().get("X-VALUE"));
		assertEquals(1, request.getHeaders().size());
		assertThrows(IllegalRequestHeaderException.class, () -> request.getHeader("x-value"));
		assertThrows(UnsupportedOperationException.class, () -> request.getHeaders().get("x-value").add("three"));
	}

	@Test
	void identicalCookieAndFormOccurrencesArePreservedAndRejectedByScalarAccessors() {
		Request request = Request.withPath(HttpMethod.POST, "/")
				.headers(Map.of("Cookie", List.of("id=one; id=one; ID=other"), "Content-Type", List.of("application/x-www-form-urlencoded")))
				.body("id=one&id=one&ID=other".getBytes(StandardCharsets.UTF_8)).build();
		assertEquals(List.of("one", "one"), request.getCookies().get("id"));
		assertEquals(List.of("other"), request.getCookies().get("ID"));
		assertEquals(List.of("one", "one"), request.getFormParameters().get("id"));
		assertThrows(IllegalRequestCookieException.class, () -> request.getCookie("id"));
		assertThrows(IllegalFormParameterException.class, () -> request.getFormParameter("id"));
		assertThrows(UnsupportedOperationException.class, () -> request.getCookies().get("id").clear());
		assertThrows(UnsupportedOperationException.class, () -> request.getFormParameters().clear());
	}

	@Test
	void identicalMultipartPartsAndCustomParserListsArePreservedAndSnapshotted() {
		String part = "--test\r\nContent-Disposition: form-data; name=\"id\"\r\n\r\none\r\n";
		Request request = Request.withPath(HttpMethod.POST, "/")
				.headers(Map.of("Content-Type", List.of("multipart/form-data; boundary=test")))
				.body((part + part + "--test--\r\n").getBytes(StandardCharsets.UTF_8)).build();
		List<MultipartField> fields = request.getMultipartFields().get("id");
		assertEquals(2, fields.size());
		assertEquals(fields.get(0).getName(), fields.get(1).getName());
		assertArrayEquals(fields.get(0).getData().orElseThrow(), fields.get(1).getData().orElseThrow());
		assertThrows(IllegalMultipartFieldException.class, () -> request.getMultipartField("id"));
		List<MultipartField> supplied = new ArrayList<>(fields);
		Map<String, List<MultipartField>> suppliedMap = new LinkedHashMap<>();
		suppliedMap.put("id", supplied);
		Request custom = Request.withPath(HttpMethod.POST, "/").headers(request.getHeaders()).body(request.getBody().orElseThrow())
				.multipartParser(ignored -> suppliedMap).build();
		assertEquals(fields, custom.getMultipartFields().get("id"));
		supplied.clear();
		suppliedMap.clear();
		assertEquals(fields, custom.getMultipartFields().get("id"));
		assertThrows(UnsupportedOperationException.class, () -> custom.getMultipartFields().get("id").clear());
	}

	@Test
	void responseAndHandshakeCollectionsPreserveDuplicatesAndSnapshotCallerInputs() {
		ResponseCookie cookie = ResponseCookie.with("id", "one").build();
		List<ResponseCookie> cookies = new ArrayList<>(List.of(cookie, cookie));
		List<String> values = new ArrayList<>(List.of("second", "first", "first"));
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("X-Value", values);
		headers.put("x-value", List.of("third"));
		assertEquals(List.of("second", "first", "first", "third"), ConditionalRequests.validatorHeaders(null, null, headers).get("x-value"));
		Response response = Response.withStatusCode(200).headers(headers).cookies(cookies).build();
		MarshaledResponse marshaled = MarshaledResponse.withStatusCode(200).headers(headers).cookies(cookies).build();
		SseHandshakeResult.Accepted handshake = SseHandshakeResult.Accepted.builder().headers(headers).cookies(cookies).build();
		values.clear();
		headers.clear();
		cookies.clear();
		List<String> expected = List.of("second", "first", "first", "third");
		assertEquals(expected, response.getHeaders().get("x-value"));
		assertEquals(expected, marshaled.getHeaders().get("x-value"));
		assertEquals(expected, handshake.getHeaders().get("x-value"));
		assertEquals(List.of(cookie, cookie), response.getCookies());
		assertEquals(List.of(cookie, cookie), marshaled.getCookies());
		assertEquals(List.of(cookie, cookie), handshake.getCookies());
		assertThrows(UnsupportedOperationException.class, () -> response.getCookies().clear());
		assertThrows(UnsupportedOperationException.class, () -> handshake.getHeaders().get("x-value").clear());
	}

	@Test
	void copierConsumersCanEditListsWithoutMutatingOriginalOrSuppliedImmutableInputs() {
		Request request = Request.withPath(HttpMethod.GET, "/").queryParameters(Map.of("id", List.of("one"))).headers(Map.of("X-Value", List.of("one"))).build();
		Request copied = request.copy().queryParameters(Map.of("id", List.of("one")))
				.queryParameters(values -> values.get("id").add("one"))
				.headers(Map.of("X-Value", List.of("one"))).headers(values -> values.get("x-value").add("one")).finish();
		assertEquals(List.of("one"), request.getQueryParameters().get("id"));
		assertEquals(List.of("one", "one"), copied.getQueryParameters().get("id"));
		assertEquals(List.of("one", "one"), copied.getHeaders().get("X-Value"));
		ResponseCookie cookie = ResponseCookie.with("id", "one").build();
		Response response = Response.withStatusCode(200).headers(Map.of("X-Value", List.of("one"))).cookies(List.of(cookie)).build();
		Response responseCopy = response.copy().headers(values -> values.get("x-value").add("one"))
				.cookies(List.of(cookie)).cookies(values -> values.add(cookie)).finish();
		assertEquals(List.of("one"), response.getHeaders().get("x-value"));
		assertEquals(List.of("one", "one"), responseCopy.getHeaders().get("x-value"));
		assertEquals(List.of(cookie, cookie), responseCopy.getCookies());
		MarshaledResponse marshaled = MarshaledResponse.withStatusCode(200).headers(response.getHeaders()).cookies(response.getCookies()).build();
		MarshaledResponse marshaledCopy = marshaled.copy().headers(values -> values.get("x-value").add("one"))
				.cookies(List.of(cookie)).cookies(values -> values.add(cookie)).finish();
		assertEquals(List.of("one"), marshaled.getHeaders().get("x-value"));
		assertEquals(List.of("one", "one"), marshaledCopy.getHeaders().get("x-value"));
		assertEquals(List.of(cookie, cookie), marshaledCopy.getCookies());
	}

	@Test
	void rawHeaderParsingPreservesRepeatedLinesAndCommaSeparatedValues() {
		Map<String, List<String>> headers = Utilities.extractHeadersFromRawHeaderLines(List.of(
				"Accept: text/plain, text/plain", "accept: application/json", "X-Value: one", "x-value: one"));
		assertEquals(List.of("text/plain", "text/plain", "application/json"), headers.get("ACCEPT"));
		assertEquals(List.of("one", "one"), headers.get("x-value"));
		assertThrows(UnsupportedOperationException.class, headers::clear);
	}
}
