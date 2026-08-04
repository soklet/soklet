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

package com.soklet.internal.mcp.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class McpMirroredHeaderCodecTests {
	@Test
	public void decodes_plain_and_exact_lowercase_base64_values() {
		McpMirroredHeaderCodec codec = new McpMirroredHeaderCodec(1_024);

		Assertions.assertEquals("us-west1", codec.decodeString("us-west1"));
		Assertions.assertEquals("interior\ttab", codec.decodeString("interior\ttab"));
		Assertions.assertEquals("Hello, 世界",
				codec.decodeString("=?base64?SGVsbG8sIOS4lueVjA==?="));
		Assertions.assertEquals(" padded ",
				codec.decodeString("=?base64?IHBhZGRlZCA=?="));
		Assertions.assertEquals("line1\nline2",
				codec.decodeString("=?base64?bGluZTEKbGluZTI=?="));
		Assertions.assertEquals("", codec.decodeString("=?base64??="));
		Assertions.assertEquals("=?base64?literal?=",
				codec.decodeString("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?="));
	}

	@Test
	public void sentinel_markers_are_case_sensitive_and_partial_markers_are_plain() {
		McpMirroredHeaderCodec codec = new McpMirroredHeaderCodec(1_024);

		Assertions.assertEquals("=?Base64?SGVsbG8=?=",
				codec.decodeString("=?Base64?SGVsbG8=?="));
		Assertions.assertEquals("=?base64?not-closed",
				codec.decodeString("=?base64?not-closed"));
		Assertions.assertEquals("not-opened?=", codec.decodeString("not-opened?="));
	}

	@Test
	public void malformed_base64_and_utf8_fail_without_reflecting_the_value() {
		McpMirroredHeaderCodec codec = new McpMirroredHeaderCodec(1_024);
		for (String value : List.of(
				"=?base64?***secret***?=",
				"=?base64?_w==?=",
				"=?base64?SGVsbG8?=",
				"=?base64?Zh==?=",
				"=?base64?/w==?=")) {
			IllegalArgumentException exception = Assertions.assertThrows(
					IllegalArgumentException.class, () -> codec.decodeString(value));
			Assertions.assertEquals("Invalid mirrored header value.", exception.getMessage());
			Assertions.assertFalse(exception.getMessage().contains("secret"));
		}
	}

	@Test
	public void plain_values_enforce_ascii_whitespace_and_decoded_size_rules() {
		McpMirroredHeaderCodec codec = new McpMirroredHeaderCodec(4);

		Assertions.assertEquals("four", codec.decodeString("four"));
		Assertions.assertEquals("four", codec.decodeString("=?base64?Zm91cg==?="));
		for (String value : List.of(
				"five!",
				" leading",
				"trailing\t",
				"line\n",
				"café",
				"=?base64?Zml2ZSE=?="))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> codec.decodeString(value), value);
	}

	@Test
	public void construction_requires_a_positive_decoded_byte_limit() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpMirroredHeaderCodec(0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpMirroredHeaderCodec(-1));
	}
}
