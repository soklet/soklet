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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Coverage-guided checks for closed canonical framework request-state plaintext.
 */
@ThreadSafe
public class McpRequestStatePlaintextCodecFuzzTest {
	private static final int MAXIMUM_BYTES = 4_096;
	private static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(15);
	private static final int MAXIMUM_ROUNDS = 3;
	private static final Instant NOW = Instant.ofEpochSecond(1_700_000_100L);
	private static final McpJsonRpcId CURRENT_REQUEST_ID =
			new McpJsonRpcId.StringId("request-next");
	private static final McpRequestStateBinding BINDING = binding();

	@FuzzTest(maxDuration = "2m")
	public void decodeOnlyRejectsWithUniformRedactedIllegalArgumentException(
			byte[] input) {
		decode(input);
		// Text resources conventionally end in LF. Exercise their exact bytes and
		// the intended canonical plaintext without committing opaque binary seeds.
		if (input.length > 0 && input.length <= MAXIMUM_BYTES + 1
				&& input[input.length - 1] == '\n')
			decode(Arrays.copyOf(input, input.length - 1));
	}

	@Test
	public void canonicalTextSeedReachesSuccessfulByteExactRoundTrip()
			throws IOException {
		byte[] input = readTextSeed("canonical.state");
		McpFrameworkRequestStateContinuation continuation =
				McpRequestStatePlaintextCodec.decode(input, BINDING,
						MAXIMUM_BYTES, MAXIMUM_LIFETIME, MAXIMUM_ROUNDS,
						NOW, CURRENT_REQUEST_ID);
		Assertions.assertArrayEquals(input,
				McpRequestStatePlaintextCodec.encode(continuation, BINDING,
						MAXIMUM_BYTES, MAXIMUM_LIFETIME, MAXIMUM_ROUNDS));
	}

	private static void decode(byte[] input) {
		try {
			McpFrameworkRequestStateContinuation continuation =
					McpRequestStatePlaintextCodec.decode(input, BINDING,
							MAXIMUM_BYTES, MAXIMUM_LIFETIME, MAXIMUM_ROUNDS,
							NOW, CURRENT_REQUEST_ID);
			Assertions.assertArrayEquals(input,
					McpRequestStatePlaintextCodec.encode(continuation, BINDING,
							MAXIMUM_BYTES, MAXIMUM_LIFETIME, MAXIMUM_ROUNDS));
		} catch (IllegalArgumentException expected) {
			Assertions.assertEquals(
					"Framework request-state plaintext is invalid.",
					expected.getMessage());
			Assertions.assertNull(expected.getCause());
		}
	}

	private static McpRequestStateBinding binding() {
		Map<String, McpJsonValue> metadata = new LinkedHashMap<>();
		metadata.put("progressToken", new McpJsonString("discard"));
		metadata.put("stable", new McpJsonString("yes"));
		Map<String, McpJsonValue> parameters = new LinkedHashMap<>();
		parameters.put("requestState", new McpJsonString("discard"));
		parameters.put("name", new McpJsonString("echo"));
		parameters.put("inputResponses", McpJsonObject.empty());
		parameters.put("_meta", new McpJsonObject(metadata));
		return McpRequestStateBinding.create("/mcp", "2026-07-28", "tools/call",
				Optional.of("tenant-α"), new McpJsonObject(parameters));
	}

	private static byte[] readTextSeed(String name) throws IOException {
		String resource = "McpRequestStatePlaintextCodecFuzzTestInputs/"
				+ "decodeOnlyRejectsWithUniformRedactedIllegalArgumentException/"
				+ name;
		try (InputStream stream = requireNonNull(
				McpRequestStatePlaintextCodecFuzzTest.class
						.getResourceAsStream(resource))) {
			byte[] value = stream.readAllBytes();
			return value.length > 0 && value[value.length - 1] == '\n'
					? Arrays.copyOf(value, value.length - 1) : value;
		}
	}
}
