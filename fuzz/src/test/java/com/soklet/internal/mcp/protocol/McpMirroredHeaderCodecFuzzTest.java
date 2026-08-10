/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static java.util.Objects.requireNonNull;

/**
 * Coverage-guided checks for bounded and redacted mirrored-header decoding.
 */
@ThreadSafe
public class McpMirroredHeaderCodecFuzzTest {
	private static final McpMirroredHeaderCodec CODEC =
			new McpMirroredHeaderCodec(
					McpMirroredHeaderCodec.DEFAULT_MAXIMUM_DECODED_BYTES);
	private static volatile int sink;

	@FuzzTest(maxDuration = "2m")
	public void decodeStringOnlyRejectsWithRedactedIllegalArgumentException(
			byte[] input) {
		String value = new String(input, StandardCharsets.UTF_8);
		decode(value);
		// Text resources conventionally end in LF. Exercise their exact bytes and
		// the intended header value without requiring opaque binary seed files.
		if (value.endsWith("\n"))
			decode(value.substring(0, value.length() - 1));
	}

	@Test
	public void curatedTextSeedsReachPlainBase64AndRejectionBranches()
			throws IOException {
		Assertions.assertEquals("Region-1", CODEC.decodeString(
				readTextSeed("plain.header")));
		Assertions.assertEquals("café", CODEC.decodeString(
				readTextSeed("canonical-base64.header")));
		IllegalArgumentException invalidPadding = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> CODEC.decodeString(readTextSeed("invalid-padding.header")));
		Assertions.assertEquals("Invalid mirrored header value.",
				invalidPadding.getMessage());
		Assertions.assertNull(invalidPadding.getCause());
	}

	private static void decode(String value) {
		try {
			sink = CODEC.decodeString(value).length();
		} catch (IllegalArgumentException expected) {
			Assertions.assertEquals("Invalid mirrored header value.",
					expected.getMessage());
			Assertions.assertNull(expected.getCause());
		}
	}

	private static String readTextSeed(String name) throws IOException {
		String resource = "McpMirroredHeaderCodecFuzzTestInputs/"
				+ "decodeStringOnlyRejectsWithRedactedIllegalArgumentException/"
				+ name;
		try (InputStream stream = requireNonNull(
				McpMirroredHeaderCodecFuzzTest.class.getResourceAsStream(resource))) {
			String value = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			return value.endsWith("\n")
					? value.substring(0, value.length() - 1) : value;
		}
	}
}
