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

import java.nio.charset.StandardCharsets;
import java.util.List;

public class MicrohttpResponseHeaderValidationTests {
	@Test
	public void serializationRejectsControlCharactersInEveryHeaderSource() {
		for (char character : new char[]{
				'\0', '\u0001', '\r', '\n', '\u007F', '\u0085'}) {
			Header invalidName = new Header("X-Test" + character, "safe");
			Header invalidValue = new Header("X-Test", "safe" + character + "unsafe");

			assertRejectedResponseHeader(invalidName);
			assertRejectedResponseHeader(invalidValue);
			assertRejectedConnectionHeader(invalidName);
			assertRejectedConnectionHeader(invalidValue);
		}
	}

	@Test
	public void serializationAcceptsTokenNamesAndHorizontalTabsInValues() {
		MicrohttpResponse response = new MicrohttpResponse(200, "OK", List.of(
				new Header("X-!#$%&'*+-.^_`|~", "one\ttwo\u00E9")), new byte[0]);

		String serialized = new String(response.serializeHead("HTTP/1.1", List.of()),
				StandardCharsets.ISO_8859_1);

		Assertions.assertEquals("HTTP/1.1 200 OK\r\n"
				+ "X-!#$%&'*+-.^_`|~: one\ttwo\u00E9\r\n\r\n", serialized);
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
