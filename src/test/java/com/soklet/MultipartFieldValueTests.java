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

import com.soklet.annotation.Multipart;
import com.soklet.annotation.POST;
import com.soklet.exception.IllegalMultipartFieldException;
import com.soklet.exception.IllegalRequestBodyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
public class MultipartFieldValueTests {

	@Test
	void independentlyBuiltFieldsAndCopiesCompareByValue() {
		MultipartField first = field(new byte[]{1, 2, 3});
		MultipartField second = field(new byte[]{1, 2, 3});
		MultipartField third = first.copy().data(new byte[]{1, 2, 3}).finish();
		assertNotSame(first.getData().orElseThrow(), second.getData().orElseThrow());
		assertEquals(first, first);
		assertEquals(first, second);
		assertEquals(second, first);
		assertEquals(second, third);
		assertEquals(first, third);
		assertEquals(first.hashCode(), second.hashCode());
		assertEquals(second.hashCode(), third.hashCode());
	}

	@Test
	void hashCollectionsFindEquivalentIndependentlyBuiltKeys() {
		MultipartField stored = field(new byte[]{1, 2, 3});
		MultipartField lookup = field(new byte[]{1, 2, 3});
		Set<MultipartField> fields = new HashSet<>();
		fields.add(stored);
		assertTrue(fields.contains(lookup));
		assertFalse(fields.add(lookup));
		assertEquals(1, fields.size());
		Map<MultipartField, String> values = new HashMap<>();
		values.put(stored, "found");
		assertEquals("found", values.get(lookup));
	}

	@Test
	void metadataAndDifferentByteContentsRemainSignificant() {
		MultipartField original = field(new byte[]{1, 2, 3});
		for (MultipartField different : List.of(
				original.copy().name("other").finish(),
				original.copy().filename("other.bin").finish(),
				original.copy().filename(null).finish(),
				original.copy().contentType("text/plain").finish(),
				original.copy().contentType(null).finish(),
				original.copy().charset(StandardCharsets.ISO_8859_1).finish(),
				original.copy().charset(null).finish(),
				original.copy().data(new byte[]{1, 2, 4}).finish(),
				original.copy().data(new byte[]{1, 2}).finish(),
				original.copy().data(null).finish())) {
			assertNotEquals(original, different);
			assertNotEquals(different, original);
		}
		assertNotEquals(null, original);
		assertNotEquals("upload", original);
		assertEquals(original, MultipartField.with(" upload ", new byte[]{1, 2, 3})
				.filename(" file.bin ").contentType(" application/octet-stream ")
				.charset(StandardCharsets.UTF_8).build());
	}

	@Test
	void absentAndEmptyDataKeepTheirExistingNormalization() {
		MultipartField absent = MultipartField.withName("upload").build();
		MultipartField empty = MultipartField.with("upload", new byte[0]).build();
		MultipartField cleared = absent.copy().data(new byte[]{1}).data(null).finish();
		for (MultipartField equivalent : List.of(empty, cleared, absent.copy().data(new byte[0]).finish())) {
			assertEquals(absent, equivalent);
			assertEquals(absent.hashCode(), equivalent.hashCode());
			assertTrue(equivalent.getData().isEmpty());
			assertTrue(equivalent.getDataAsString().isEmpty());
		}
		assertNotEquals(absent, MultipartField.with("upload", new byte[]{0}).build());
	}

	@Test
	void lazyStringDecodingDoesNotChangeEqualityOrHashing() {
		MultipartField decoded = field("héllo".getBytes(StandardCharsets.UTF_8));
		MultipartField untouched = field("héllo".getBytes(StandardCharsets.UTF_8));
		int before = decoded.hashCode();
		assertEquals("héllo", decoded.getDataAsString().orElseThrow());
		assertEquals(before, decoded.hashCode());
		assertEquals(decoded, untouched);
		assertEquals(decoded.hashCode(), untouched.hashCode());
		assertEquals("héllo", untouched.getDataAsString().orElseThrow());
		assertEquals(decoded, untouched);
	}

	@Test
	void binaryEqualityDoesNotRequireSuccessfulStringDecoding() {
		MultipartField first = field(new byte[]{(byte) 0xff, (byte) 0x80, 0});
		MultipartField second = field(new byte[]{(byte) 0xff, (byte) 0x80, 0});
		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
		assertThrows(IllegalRequestBodyException.class, first::getDataAsString);
		assertEquals(first, second);
	}

	@Test
	void equalParsedOccurrencesRemainOrderedAndReachListInjection() {
		Request request = multipartRequest("/uploads");
		List<MultipartField> fields = request.getMultipartFields().get("upload");
		assertEquals(3, fields.size());
		assertNotSame(fields.get(0), fields.get(1));
		assertEquals(fields.get(0), fields.get(1));
		assertEquals(fields.get(0).hashCode(), fields.get(1).hashCode());
		assertNotEquals(fields.get(1), fields.get(2));
		assertEquals(List.of("first", "first", "last"), fields.stream()
				.map(field -> field.getDataAsString().orElseThrow()).toList());
		assertThrows(IllegalMultipartFieldException.class, () -> request.getMultipartField("upload"));
		SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(UploadResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent logEvent) {}
				}).build(), simulator -> {
			HttpRequestResult list = simulator.performHttpRequest(request);
			assertEquals(200, list.getMarshaledResponse().getStatusCode());
			assertEquals("3:true:last", new String(list.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
			assertEquals(400, simulator.performHttpRequest(multipartRequest("/upload"))
					.getMarshaledResponse().getStatusCode());
		});
	}

	private static MultipartField field(byte[] data) {
		return MultipartField.with("upload", data).filename("file.bin")
				.contentType("application/octet-stream").charset(StandardCharsets.UTF_8).build();
	}

	private static Request multipartRequest(String path) {
		StringBuilder body = new StringBuilder();
		for (String value : List.of("first", "first", "last"))
			body.append("--test\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"file.txt\"\r\n")
					.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n").append(value).append("\r\n");
		body.append("--test--\r\n");
		return Request.withPath(HttpMethod.POST, path)
				.headers(Map.of("Content-Type", List.of("multipart/form-data; boundary=test")))
				.body(body.toString().getBytes(StandardCharsets.UTF_8)).build();
	}

	public static class UploadResource {
		@POST("/uploads") public String uploads(@Multipart(name="upload") List<MultipartField> fields) {
			return fields.size() + ":" + fields.get(0).equals(fields.get(1)) + ":" + fields.get(2).getDataAsString().orElseThrow();
		}
		@POST("/upload") public String upload(@Multipart(name="upload") MultipartField field) {
			return field.getDataAsString().orElseThrow();
		}
	}
}
