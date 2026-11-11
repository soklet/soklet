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

package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MicrohttpResponseHeaderValidationTests {
	@Test
	public void serializationRejectsControlCharactersInEveryHeaderSource() {
		for (char character : new char[]{
				'\0', '\u0001', '\r', '\n', '\u007F', '\u0100'}) {
			Header invalidName = new Header("X-Test" + character, "safe");
			Header invalidValue = new Header("X-Test", "safe" + character + "unsafe");

			assertRejectedResponseHeader(invalidName);
			assertRejectedResponseHeader(invalidValue);
			assertRejectedConnectionHeader(invalidName);
			assertRejectedConnectionHeader(invalidValue);
		}
	}

	@Test
	public void serializationAcceptsTokenNamesHorizontalTabsAndObsTextInValues() {
		MicrohttpResponse response = new MicrohttpResponse(200, "OK", List.of(
				new Header("X-!#$%&'*+-.^_`|~", "one\ttwo\u0080\u0085\u00E9\u00FF")), new byte[0]);

		String serialized = new String(response.serializeHead("HTTP/1.1", List.of()),
				StandardCharsets.ISO_8859_1);

		Assertions.assertEquals("HTTP/1.1 200 OK\r\n"
				+ "X-!#$%&'*+-.^_`|~: one\ttwo\u0080\u0085\u00E9\u00FF\r\n\r\n", serialized);
	}

	@Test
	public void headerGrowthHonorsTheExactSerializedSizeLimit() {
		String padding = "a".repeat(512);
		MicrohttpResponse response = new MicrohttpResponse(200, "OK",
				List.of(new Header("X-Padding", padding)), new byte[0]);
		byte[] expected = ("HTTP/1.1 200 OK\r\nX-Padding: " + padding + "\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII);

		Assertions.assertArrayEquals(expected,
				response.serialize("HTTP/1.1", List.of(), expected.length));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> response.serialize("HTTP/1.1", List.of(), expected.length - 1));
	}

	@Test
	public void headCapacityAboveTheIntRangeIsRejectedBeforeAllocation() throws Exception {
		Class<?> writerType = Class.forName(MicrohttpResponse.class.getName() + "$HeadWriter");
		var constructor = writerType.getDeclaredConstructor(int.class, int.class);
		constructor.setAccessible(true);
		Object writer = constructor.newInstance(0, Integer.MAX_VALUE);
		var size = writerType.getDeclaredField("size");
		size.setAccessible(true);
		size.setInt(writer, Integer.MAX_VALUE - 1);
		var bytes = writerType.getDeclaredField("bytes");
		bytes.setAccessible(true);
		byte[] original = (byte[]) bytes.get(writer);
		var ensureCapacity = writerType.getDeclaredMethod("ensureCapacity", int.class);
		ensureCapacity.setAccessible(true);

		InvocationTargetException failure = Assertions.assertThrows(InvocationTargetException.class,
				() -> ensureCapacity.invoke(writer, 2));
		Assertions.assertInstanceOf(IllegalArgumentException.class, failure.getCause());
		Assertions.assertSame(original, bytes.get(writer));
		Assertions.assertEquals(Integer.MAX_VALUE - 1, size.getInt(writer));
	}

	private static void assertRejectedResponseHeader(Header header) {
		MicrohttpResponse response = new MicrohttpResponse(
				200, "OK", List.of(header), new byte[0]);

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> response.serializeHead("HTTP/1.1", List.of()));
	}

	private static void assertRejectedConnectionHeader(Header header) {
		MicrohttpResponse response = new MicrohttpResponse(
				200, "OK", List.of(), new byte[0]);

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> response.serializeHead("HTTP/1.1", List.of(header)));
	}
}
