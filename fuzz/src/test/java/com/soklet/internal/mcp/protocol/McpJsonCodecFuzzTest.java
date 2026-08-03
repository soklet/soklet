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

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Assertions;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Coverage-guided checks for Soklet's strict, bounded JSON codec.
 */
@ThreadSafe
public class McpJsonCodecFuzzTest {
	private static final McpJsonCodec CODEC = new McpJsonCodec(new McpJsonLimits(
			16_384, 256, 8_192, 8_192, 512, 10_000, 4_096, 100_000));

	@FuzzTest(maxDuration = "2m")
	public void strictJsonFuzzRejectsInvalidBytesOnlyWithIllegalArgumentException(byte[] input) {
		try {
			CODEC.parse(input);
		} catch (IllegalArgumentException expected) {
			// Malformed and over-limit input is expected. Any other RuntimeException
			// or Error is a fuzz finding.
		}
	}

	@FuzzTest(maxDuration = "2m")
	public void strictJsonFuzzRoundTripsStructurally(byte[] input) {
		McpJsonValue parsed;

		try {
			parsed = CODEC.parse(input);
		} catch (IllegalArgumentException expected) {
			return;
		}

		byte[] serialized = CODEC.toUtf8Bytes(parsed);
		Assertions.assertEquals(parsed, CODEC.parse(serialized));
	}
}
