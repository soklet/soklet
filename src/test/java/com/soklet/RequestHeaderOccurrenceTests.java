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
import com.soklet.exception.IllegalRequestHeaderException;
import com.soklet.internal.microhttp.Header;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RequestHeaderOccurrenceTests {
	@Test
	void everyHeaderKeepsOneValuePerPhysicalFieldIncludingCommasAndEmptyValues() {
		for (String name : List.of("Accept", "Accept-Encoding", "Accept-Language", "Cache-Control", "Pragma", "Vary",
				"Connection", "Transfer-Encoding", "Upgrade", "Allow", "Via", "Warning", "Authorization", "Cookie", "Set-Cookie", "X-Custom")) {
			String value = "first, second=\"quoted,comma\", first";
			Map<String, List<String>> headers = Utilities.extractHeadersFromRawHeaderLines(List.of(
					name + ": " + value, name + ": " + value, name + ":\t "));
			assertEquals(List.of(value, value, ""), headers.get(name), name);
			assertThrows(UnsupportedOperationException.class, () -> headers.get(name).add("extra"));
			assertThrows(UnsupportedOperationException.class, headers::clear);
		}
	}

	@Test
	void lazyPhysicalAccessAndMaterializationAgreeWithBothMapBuildersAndCopies() {
		List<Header> physical = List.of(new Header("Accept-Language", "en-US,en;q=0.9"),
				new Header("X-Empty", ""), new Header("X-Repeat", "one,two"), new Header("x-repeat", "one,two"));
		Map<String, List<String>> mapped = new LinkedHashMap<>();
		mapped.put("Accept-Language", List.of("en-US,en;q=0.9"));
		mapped.put("X-Empty", List.of(""));
		mapped.put("X-Repeat", List.of("one,two"));
		mapped.put("x-repeat", List.of("one,two"));
		Request request = Request.withRawUrl(HttpMethod.GET, "/").microhttpHeaders(physical).build();
		assertEquals(Optional.of("en-US,en;q=0.9"), request.getHeader("Accept-Language"));
		assertEquals(Optional.of(""), request.getHeader("X-Empty"));
		assertThrows(IllegalRequestHeaderException.class, () -> request.getHeader("X-Repeat"));
		Map<String, List<String>> materialized = request.getHeaders();
		assertEquals(List.of("one,two", "one,two"), materialized.get("X-Repeat"));
		assertEquals(List.of("accept-language", "x-empty", "x-repeat"), materialized.keySet().stream().map(name -> name.toLowerCase(Locale.ROOT)).toList());
		assertEquals(materialized, Request.withPath(HttpMethod.GET, "/").headers(mapped).build().getHeaders());
		assertEquals(materialized, Request.withRawUrl(HttpMethod.GET, "/").headers(mapped).build().getHeaders());
		assertEquals(materialized, request.copy().finish().getHeaders());
		assertEquals(Optional.of("en-US,en;q=0.9"), request.getHeader("Accept-Language"));
		assertEquals(Optional.of(""), request.getHeader("X-Empty"));
	}

	@Test
	void optionalHeaderWhitespaceIsNormalizedWithoutDiscardingObsText() {
		String value = "\u00a0literal\u00a0";
		List<Header> physical = List.of(new Header("X-Value", " \t" + value + "\t "), new Header("X-Empty", "\t "));
		Request request = Request.withRawUrl(HttpMethod.GET, "/").microhttpHeaders(physical).build();
		Request mapped = Request.withPath(HttpMethod.GET, "/")
				.headers(Map.of("X-Value", List.of(" \t" + value + "\t "), "X-Empty", List.of("\t "))).build();
		assertEquals(Optional.of(value), request.getHeader("X-Value"));
		assertEquals(Optional.of(value), mapped.getHeader("X-Value"));
		assertEquals(Optional.of(""), request.getHeader("X-Empty"));
		assertEquals(Optional.of(""), mapped.getHeader("X-Empty"));
		assertEquals(mapped.getHeaders(), request.getHeaders());
		assertEquals(List.of(value), Utilities.extractHeadersFromRawHeaderLines(List.of("X-Value: \t" + value + "\t ")).get("X-Value"));
	}

	@Test
	void emptyOccurrencesCannotHideDuplicateAuthenticationOrFramingFields() {
		for (String name : List.of("Authorization", "Content-Length", "Transfer-Encoding", "traceparent")) {
			Request request = Request.withRawUrl(HttpMethod.GET, "/")
					.microhttpHeaders(List.of(new Header(name, ""), new Header(name, "value"))).build();
			assertThrows(IllegalRequestHeaderException.class, () -> request.getHeader(name), name);
			assertEquals(List.of("", "value"), request.getHeaders().get(name), name);
			if (name.equals("traceparent"))
				assertTrue(request.getTraceContext().isEmpty());
		}
	}

	@Test
	void fieldSpecificConsumersStillParseAcrossCommasAndOccurrences() {
		Request combined = Request.withRawUrl(HttpMethod.OPTIONS, "/")
				.microhttpHeaders(List.of(new Header("Accept", "text/plain;q=0.5, application/json"),
						new Header("Accept", "text/html;q=0.2"), new Header("Accept-Language", "en-US,en;q=0.9"),
						new Header("Accept-Language", "fr;q=0.5"), new Header("Origin", "https://example.com"),
						new Header("Access-Control-Request-Method", "POST"), new Header("Access-Control-Request-Headers", "X-First, X-Second"),
						new Header("Access-Control-Request-Headers", "X-Third"))).build();
		Request separated = Request.withPath(HttpMethod.OPTIONS, "/").headers(Map.of(
				"Accept", List.of("text/plain;q=0.5", "application/json", "text/html;q=0.2"),
				"Accept-Language", List.of("en-US", "en;q=0.9", "fr;q=0.5"), "Origin", List.of("https://example.com"),
				"Access-Control-Request-Method", List.of("POST"), "Access-Control-Request-Headers", List.of("X-First", "X-Second", "X-Third"))).build();
		assertFalse(combined.getMediaRanges().isEmpty());
		assertFalse(combined.getLocales().isEmpty());
		assertEquals(separated.getMediaRanges(), combined.getMediaRanges());
		assertEquals(separated.getLocales(), combined.getLocales());
		assertEquals(separated.getLanguageRanges(), combined.getLanguageRanges());
		assertEquals(Set.of("X-First", "X-Second", "X-Third"), combined.getCorsPreflight().orElseThrow().getAccessControlRequestHeaders());
	}

	@Test
	void mcpLanguagePreferencesIgnoreEmptyFieldsAndPreserveFirstOccurrenceWeights() {
		Request request = Request.withRawUrl(HttpMethod.GET, "/")
				.microhttpHeaders(List.of(new Header("Accept-Language", ""), new Header("Accept-Language", "en;q=0, fr;q=0.7"),
						new Header("Accept-Language", "\t "), new Header("Accept-Language", "en;q=1"))).build();
		List<Locale.LanguageRange> expected = List.of(new Locale.LanguageRange("fr", 0.7), new Locale.LanguageRange("en", 0));
		assertEquals(expected, McpLocaleSupport.boundedLanguageRanges(request.getHeaders().get("Accept-Language")));
		assertEquals(expected, McpLocaleSupport.boundedLanguageRanges(List.of("\t ", "en;q=0, fr;q=0.7", "", " en;q=1 \t")));
		assertEquals(List.of("", "en;q=0, fr;q=0.7", "", "en;q=1"), request.getHeaders().get("Accept-Language"));
	}

	@Test
	void sseParserAndOversizedRequestRecoveryPreservePhysicalOccurrences() throws Exception {
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
		String raw = "GET /events HTTP/1.1\r\nHost: example.com\r\nAccept: text/event-stream, */*\r\n"
				+ "Accept-Language: en-US,en;q=0.9\r\nX-Empty:\r\nX-Repeat: one,two\r\nX-Repeat: one,two\r\n\r\n";
		for (Request request : List.of(server.parseRequest(raw, null), server.parseTooLargeRequestForRawRequest(raw).orElseThrow())) {
			assertEquals(Optional.of("text/event-stream, */*"), request.getHeader("Accept"));
			assertEquals(Optional.of("en-US,en;q=0.9"), request.getHeader("Accept-Language"));
			assertEquals(Optional.of(""), request.getHeader("X-Empty"));
			assertEquals(List.of("one,two", "one,two"), request.getHeaders().get("X-Repeat"));
			assertThrows(IllegalRequestHeaderException.class, () -> request.getHeader("X-Repeat"));
		}
	}

	@Test
	void sseBodyHeaderValidationCannotIgnoreAnEmptyPhysicalFramingField() throws Exception {
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
		for (String fields : List.of("Content-Length:\r\n", "Transfer-Encoding:\r\n", "Content-Length:\r\nContent-Length: 0\r\n")) {
			Request request = server.parseRequest("GET /events HTTP/1.1\r\nHost: example.com\r\n" + fields + "\r\n", null);
			assertThrows(IllegalRequestException.class, () -> server.validateNoRequestBodyHeaders(request), fields);
		}
	}
}
