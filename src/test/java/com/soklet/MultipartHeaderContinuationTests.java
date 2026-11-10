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
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class MultipartHeaderContinuationTests {
	@Test
	void foldedDispositionPreservesFieldNameFilenameAndFollowingHeaders() {
		for (String whitespace : List.of(" ", "\t")) {
			String headers = "Content-Disposition: form-data;\r\n" + whitespace
					+ "name=\"upload\";\r\n" + whitespace + "filename=\"report.txt\"\r\n"
					+ "Content-Type: text/plain\r\n\r\n";
			Request request = request(headers, "payload".getBytes(StandardCharsets.US_ASCII));
			assertEquals(Set.of("upload"), request.getMultipartFields().keySet());
			MultipartField field = request.getMultipartField("upload").orElseThrow();
			assertEquals("report.txt", field.getFilename().orElseThrow());
			assertEquals("text/plain", field.getContentType().orElseThrow());
			assertEquals("payload", field.getDataAsString().orElseThrow());
		}
	}

	@Test
	void unfoldingPreservesQuotedWhitespaceAndLiteralBrowserBackslashes() {
		for (String whitespace : List.of(" ", "  ", "\t")) {
			String headers = "Content-Disposition: form-data; name=\"upload\r\n"
					+ whitespace + "part\"; filename=\"long\r\n" + whitespace
					+ "filename\\part.txt\"\r\n\r\n";
			String name = "upload" + whitespace + "part";
			MultipartField field = request(headers, new byte[]{1}).getMultipartField(name).orElseThrow();
			assertEquals(name, field.getName());
			assertEquals("long" + whitespace + "filename\\part.txt", field.getFilename().orElseThrow());
			assertArrayEquals(new byte[]{1}, field.getData().orElseThrow());
		}
	}

	@Test
	void foldedContentTypeKeepsExplicitCharsetAndDoesNotConsumeBodyLines() {
		String headers = "Content-Disposition: form-data; name=\"upload\"\r\n"
				+ "Content-Type: text/plain;\r\n\tcharset=ISO-8859-1\r\n\r\n";
		byte[] body = "é\r\n\tContent-Type: body text".getBytes(StandardCharsets.ISO_8859_1);
		MultipartField field = request(headers, body).getMultipartField("upload").orElseThrow();
		assertEquals(StandardCharsets.ISO_8859_1, field.getCharset().orElseThrow());
		assertEquals("é\r\n\tContent-Type: body text", field.getDataAsString().orElseThrow());
		assertArrayEquals(body, field.getData().orElseThrow());
	}

	@Test
	void rawTenKibibyteHeaderLimitStillCountsFoldBytes() {
		String prefix = "Content-Disposition: form-data;\r\n\tname=\"upload\";\r\n"
				+ "\tfilename=\"report.txt\"\r\nX-Padding: ";
		String ending = "\r\n\r\n";
		String atLimit = prefix + "x".repeat(10_240 - prefix.length() - ending.length()) + ending;
		assertEquals(10_240, atLimit.getBytes(StandardCharsets.US_ASCII).length);
		assertEquals("report.txt", request(atLimit, new byte[]{1}).getMultipartField("upload")
				.orElseThrow().getFilename().orElseThrow());
		String overLimit = atLimit.replace("X-Padding: ", "X-Padding: x");
		assertTrue(overLimit.replace("\r\n\t", "\t").length() < 10_240,
				"Unfolded size must not substitute for the raw wire-header bound");
		IllegalRequestBodyException failure = assertThrows(IllegalRequestBodyException.class,
				() -> request(overLimit, new byte[]{1}).getMultipartFields());
		assertEquals("Multipart request body is malformed.", failure.getMessage());
		assertNull(failure.getCause());
	}

	@Test
	void foldedParametersShareTheExistingLogicalHeaderParameterLimit() {
		StringBuilder disposition = new StringBuilder("Content-Disposition: form-data; name=\"upload\"");
		// The disposition token and name consume two of the128 parameter-parser entries.
		for (int index = 0; index < 126; index++)
			disposition.append(";\r\n\tx").append(index).append("=value");
		assertTrue(request(disposition + "\r\n\r\n", new byte[]{1}).getMultipartField("upload").isPresent());
		IllegalRequestBodyException failure = assertThrows(IllegalRequestBodyException.class,
				() -> request(disposition + ";\r\n\toneTooMany=value\r\n\r\n", new byte[]{1}).getMultipartFields());
		assertEquals("Too many multipart header parameters.", failure.getMessage());
		assertNull(failure.getCause());
	}

	@Test
	void malformedContinuationsAndFoldedMetadataFailuresRemainRedacted() {
		for (String headers : List.of(
				" name=\"secret-orphan\"\r\nContent-Disposition: form-data; name=upload\r\n\r\n",
				"Content-Disposition: form-data;\n name=secret-lf\r\n\r\n",
				"Content-Disposition: form-data;\r name=secret-cr\r\n\r\n",
				"Content-Disposition: form-data; name=upload\r\nContent-Type: text/plain;\r\n"
						+ " charset=UTF-8;\r\n\tCHARSET=secret-duplicate\r\n\r\n")) {
			IllegalRequestBodyException failure = assertThrows(IllegalRequestBodyException.class,
					() -> request(headers, new byte[]{1}).getMultipartFields());
			assertEquals("Multipart part metadata is malformed.", failure.getMessage());
			assertNull(failure.getCause());
			assertFalse(failure.getMessage().contains("secret"));
		}
	}

	private static Request request(String headers, byte[] data) {
		byte[] prefix = ("--folded\r\n" + headers).getBytes(StandardCharsets.UTF_8);
		byte[] suffix = "\r\n--folded--\r\n".getBytes(StandardCharsets.US_ASCII);
		byte[] body = new byte[prefix.length + data.length + suffix.length];
		System.arraycopy(prefix, 0, body, 0, prefix.length);
		System.arraycopy(data, 0, body, prefix.length, data.length);
		System.arraycopy(suffix, 0, body, prefix.length + data.length, suffix.length);
		return Request.withPath(HttpMethod.POST, "/upload")
				.headers(Map.of("Content-Type", List.of("multipart/form-data; boundary=folded")))
				.body(body).build();
	}
}
