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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact encoded-byte boundaries for the checksum-bound result corpus emitted
 * by the production request router and listener tests.
 */
public class McpSerializedResultFamilyBoundaryTests {
	private static final Path GOLDEN_ROOT = Path.of(
			"conformance", "golden-result-envelope", "live");

	@Test
	public void everyProductionGoldenResultAcceptsItsExactEnvelopeAndRejectsOneByteLess()
			throws Exception {
		List<String> manifest = Files.readAllLines(
				GOLDEN_ROOT.resolve("manifest.sha256"), StandardCharsets.UTF_8);
		Set<String> names = new LinkedHashSet<>();

		for (String row : manifest) {
			String[] fields = row.split("  ", 2);
			Assertions.assertEquals(2, fields.length, row);
			Assertions.assertTrue(fields[0].matches("[0-9a-f]{64}"), row);
			String name = fields[1];
			Assertions.assertTrue(names.add(name), name);
			byte[] fixture = Files.readAllBytes(GOLDEN_ROOT.resolve(name));
			Assertions.assertEquals(fields[0], HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(fixture)), name);
			byte[] expected = responseJson(name, fixture);
			McpJsonRpcEnvelope.ResultResponse response = Assertions.assertInstanceOf(
					McpJsonRpcEnvelope.ResultResponse.class,
					codec(65_536).decode(expected), name);

			Assertions.assertArrayEquals(expected,
					codec(expected.length).encode(response), name);
			IllegalArgumentException exception = Assertions.assertThrows(
					IllegalArgumentException.class,
					() -> codec(expected.length - 1).encode(response), name);
			Assertions.assertEquals(
					"JSON output exceeds the configured UTF-8 byte limit.",
					exception.getMessage(), name);
		}

		Assertions.assertEquals(25, names.size());
	}

	private static byte[] responseJson(String name, byte[] fixture) {
		if (name.endsWith(".json")) {
			Assertions.assertTrue(fixture.length > 1, name);
			Assertions.assertEquals((byte) '\n', fixture[fixture.length - 1], name);
			Assertions.assertNotEquals((byte) '\n', fixture[fixture.length - 2], name);
			return Arrays.copyOf(fixture, fixture.length - 1);
		}

		Assertions.assertTrue(name.endsWith(".sse.hex"), name);
		String hex = new String(fixture, StandardCharsets.UTF_8);
		Assertions.assertTrue(hex.matches("[0-9a-f]+\\n"), name);
		byte[] frame = HexFormat.of().parseHex(hex.substring(0, hex.length() - 1));
		byte[] prefix = "data: ".getBytes(StandardCharsets.UTF_8);
		Assertions.assertTrue(frame.length > prefix.length + 2, name);
		Assertions.assertArrayEquals(prefix,
				Arrays.copyOf(frame, prefix.length), name);
		Assertions.assertEquals((byte) '\n', frame[frame.length - 1], name);
		Assertions.assertEquals((byte) '\n', frame[frame.length - 2], name);
		return Arrays.copyOfRange(frame, prefix.length, frame.length - 2);
	}

	private static McpJsonRpcEnvelopeCodec codec(int maximumOutputBytes) {
		return new McpJsonRpcEnvelopeCodec(new McpJsonCodec(new McpJsonLimits(
				65_536, 256, 16_384, 16_384, 512, 10_000, 16_384,
				maximumOutputBytes)));
	}
}
