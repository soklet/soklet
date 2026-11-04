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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MultipartBoundaryLiteralTests {

	@Test
	public void encodedWordLookingBoundariesRemainLiteral() {
		for (String boundary : List.of("=?UTF-8?B?YWJj?=", "=?UTF-8?Q?abc?=")) {
			Request request = request(boundary, boundary, "form-data; name=\"field\"");
			assertEquals(Set.of("field"), request.getMultipartFields().keySet());
			assertEquals("value", request.getMultipartField("field").orElseThrow().getDataAsString().orElseThrow());
		}
	}

	@Test
	public void decodedBoundarySubstitutionDoesNotMatchTheDeclaredBoundary() {
		for (String boundary : List.of("=?UTF-8?B?YWJj?=", "=?UTF-8?Q?abc?=")) {
			Request request = request(boundary, "abc", "form-data; name=\"field\"");
			assertThrows(IllegalRequestBodyException.class, request::getMultipartFields);
		}
	}

	@Test
	public void ordinaryAndSpaceContainingBoundariesStillMatch() {
		for (String boundary : List.of("abc", " leading", "a b")) {
			Request request = request(boundary, boundary, "form-data; name=\"field\"");
			assertEquals("value", request.getMultipartField("field").orElseThrow().getDataAsString().orElseThrow());
		}
	}

	@Test
	public void contentTypeBoundaryQuotedPairsAreUnescaped() {
		for (String[] boundaries : List.of(new String[]{"a\\?b", "a?b"},
				new String[]{"a\\=b", "a=b"}, new String[]{"a\\ b", "a b"})) {
			Request request = request(boundaries[0], boundaries[1], "form-data; name=\"field\"");
			assertEquals("value", request.getMultipartField("field").orElseThrow().getDataAsString().orElseThrow());
		}
	}

	@Test
	public void malformedQuotedBoundariesAndInvalidDecodedCharactersAreRejected() {
		for (String parameter : List.of("\"abc\\", "\"abc", "\"abc\\\"", "\"abc\"junk",
				"\"a\\\\\"", "\"a\\\"b\"")) {
			Request request = requestWithContentType("multipart/form-data; boundary=" + parameter,
					"abc", "form-data; name=\"field\"");
			assertThrows(IllegalRequestBodyException.class, request::getMultipartFields);
		}
	}

	@Test
	public void contentDispositionRetainsLegacyEncodedWordDecoding() {
		Request request = request("abc", "abc", "form-data; name=\"=?UTF-8?B?ZmllbGQ=?=\"; "
				+ "filename=\"=?UTF-8?Q?r=C3=A9sum=C3=A9.txt?=\"");
		MultipartField field = request.getMultipartField("field").orElseThrow();
		assertEquals("résumé.txt", field.getFilename().orElseThrow());
		assertEquals("value", field.getDataAsString().orElseThrow());
	}

	@Test
	public void contentDispositionRetainsLiteralBrowserBackslashes() {
		String name = "field\\part";
		String filename = "back\\";
		Request request = request("abc", "abc", "form-data; name=\"" + name + "\"; filename=\"" + filename + "\"");
		MultipartField field = request.getMultipartField(name).orElseThrow();
		assertEquals(filename, field.getFilename().orElseThrow());
	}

	private static Request request(String declaredBoundary, String bodyBoundary, String disposition) {
		return requestWithContentType("multipart/form-data; boundary=\"" + declaredBoundary + "\"",
				bodyBoundary, disposition);
	}

	private static Request requestWithContentType(String contentType, String bodyBoundary, String disposition) {
		String body = "--" + bodyBoundary + "\r\nContent-Disposition: " + disposition
				+ "\r\n\r\nvalue\r\n--" + bodyBoundary + "--\r\n";
		return Request.withPath(HttpMethod.POST, "/upload")
				.headers(Map.of("Content-Type", List.of(contentType)))
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
	}
}
