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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class McpRequestStateBindingTests {
	@Test
	public void matchesDeterministicDigestBindingAndAadVectors() {
		McpRequestStateBinding binding = McpRequestStateBinding.create(
				"/mcp", "2026-07-28", "tools/call",
				Optional.of("tenant-α"), vectorParameters());

		Assertions.assertEquals(
				"4778e654fe03096860d95a3d51c33f2be4c55bb3bb8ba6fff52134359378b1b3",
				HexFormat.of().formatHex(binding.parametersDigest()));
		Assertions.assertEquals(
				"736f6b6c65742d6d63702d726571756573742d73746174652d62696e64696e672d7631"
						+ "00000000042f6d63700000000a323032362d30372d32380000000a746f6f6c732f"
						+ "63616c6c010000000974656e616e742dceb14778e654fe03096860d95a3d51c33f"
						+ "2be4c55bb3bb8ba6fff52134359378b1b3",
				HexFormat.of().formatHex(binding.bytes()));
		Assertions.assertEquals(
				"8d871928521f4752efb617506f25485df68f98e2b0c11acd9194c64df9d7e326",
				HexFormat.of().formatHex(binding.digest()));
		Assertions.assertEquals(
				"736f6b6c65742d6d63702d726571756573742d73746174652d67636d2d6161642d7631"
						+ "00000000040102030400000076736f6b6c65742d6d63702d726571756573742d7374"
						+ "6174652d62696e64696e672d763100000000042f6d63700000000a323032362d3037"
						+ "2d32380000000a746f6f6c732f63616c6c010000000974656e616e742dceb14778e6"
						+ "54fe03096860d95a3d51c33f2be4c55bb3bb8ba6fff52134359378b1b3",
				HexFormat.of().formatHex(McpRequestStateBinding.builtInAssociatedData(
						HexFormat.of().parseHex("01020304"), binding.bytes())));
	}

	@Test
	public void bindsAcceptedParametersBeyondTheResponseByteLimit() {
		String chunk = "x".repeat(1_000_000);
		String parametersJson = "{\"arguments\":{\"a\":\"" + chunk
				+ "\",\"b\":\"" + chunk + "\",\"c\":\"" + chunk
				+ "\",\"d\":\"" + chunk + "\",\"e\":\"" + chunk + "\"}}";
		Assertions.assertTrue(parametersJson.length() > 4 * 1_024 * 1_024);
		Assertions.assertTrue(parametersJson.length() < 10 * 1_024 * 1_024);
		McpJsonObject parameters = (McpJsonObject) transportCodec()
				.parse(parametersJson);
		McpRequestStateBinding original = Assertions.assertDoesNotThrow(
				() -> binding(parameters));
		Map<String, McpJsonValue> retry = new LinkedHashMap<>(parameters.members());
		retry.put("requestState", new McpJsonString("transient"));
		Assertions.assertArrayEquals(original.digest(),
				binding(new McpJsonObject(retry)).digest());
		McpJsonObject changed = (McpJsonObject) transportCodec().parse(
				parametersJson.substring(0, parametersJson.length() - 4) + "y\"}}");
		Assertions.assertFalse(MessageDigest.isEqual(original.parametersDigest(),
				binding(changed).parametersDigest()),
				"Stable bytes beyond the response ceiling must still affect binding.");
	}

	@Test
	public void acceptedParameterCanonicalizationHasHeadroomBeyondTheTransportCeiling()
			throws Exception {
		// A legal short exponent becomes a longer canonical plain decimal.
		// 90,000 numeric leaves and 16 bounded strings fit the production node,
		// token and scalar limits while canonical bytes exceed the wire ceiling.
		String chunk = "x".repeat(1_000_000);
		String strings = "[" + ("\"" + chunk + "\",").repeat(15)
				+ "\"" + chunk + "\"]";
		String numbers = "[" + "1e-6,".repeat(89_999) + "1e-6]";
		String wire = "{\"arguments\":{\"strings\":" + strings
				+ ",\"numbers\":" + numbers + "}}";
		Assertions.assertTrue(wire.length() < 16 * 1_024 * 1_024);
		McpJsonObject parameters = (McpJsonObject) transportCodec().parse(wire);
		McpRequestStateBinding actual = Assertions.assertDoesNotThrow(
				() -> binding(parameters));

		// Independent canonical construction pins the existing length-framed
		// digest, including every byte after 16 MiB; no production writer used.
		String canonical = "{\"arguments\":{\"numbers\":["
				+ "0.000001,".repeat(89_999) + "0.000001],\"strings\":"
				+ strings + "}}";
		byte[] canonicalUtf8 = canonical.getBytes(StandardCharsets.US_ASCII);
		Assertions.assertTrue(canonicalUtf8.length > 16 * 1_024 * 1_024);
		MessageDigest expected = MessageDigest.getInstance("SHA-256");
		expected.update("soklet-mcp-request-state-params-v1\0"
				.getBytes(StandardCharsets.US_ASCII));
		expected.update(java.nio.ByteBuffer.allocate(4)
				.putInt(canonicalUtf8.length).array());
		Assertions.assertArrayEquals(expected.digest(canonicalUtf8),
				actual.parametersDigest());
	}

	private static McpJsonCodec transportCodec() {
		McpJsonLimits defaults = McpJsonLimits.productionDefaults();
		return new McpJsonCodec(new McpJsonLimits(16 * 1_024 * 1_024,
				defaults.maximumNestingDepth(), defaults.maximumTokenLengthInCharacters(),
				defaults.maximumStringLengthInCharacters(), defaults.maximumNumberLengthInCharacters(),
				defaults.maximumExponentMagnitude(), defaults.maximumNodeCount(),
				defaults.maximumOutputBytes()));
	}

	@Test
	public void removesOnlyTheFrozenTransientLocations() {
		McpRequestStateBinding baseline = binding(parameters(
				"response-a", "wire-a", "progress-a", "trace-a",
				"stable", "nested-a"));
		McpRequestStateBinding transientVariant = binding(parameters(
				"response-b", "wire-b", "progress-b", "trace-b",
				"stable", "nested-a"));
		McpRequestStateBinding stableVariant = binding(parameters(
				"response-b", "wire-b", "progress-b", "trace-b",
				"changed", "nested-a"));
		McpRequestStateBinding nestedVariant = binding(parameters(
				"response-b", "wire-b", "progress-b", "trace-b",
				"stable", "nested-b"));

		Assertions.assertArrayEquals(
				baseline.parametersDigest(), transientVariant.parametersDigest());
		Assertions.assertFalse(java.security.MessageDigest.isEqual(
				baseline.parametersDigest(), stableVariant.parametersDigest()));
		Assertions.assertFalse(java.security.MessageDigest.isEqual(
				baseline.parametersDigest(), nestedVariant.parametersDigest()));
	}

	@Test
	public void everyBindingDimensionAndAuthorizationKindChangesTheBinding() {
		McpJsonObject parameters = vectorParameters();
		McpRequestStateBinding baseline = McpRequestStateBinding.create(
				"/mcp", "2026-07-28", "tools/call",
				Optional.empty(), parameters);

		for (McpRequestStateBinding variant : java.util.List.of(
				McpRequestStateBinding.create("/other", "2026-07-28", "tools/call",
						Optional.empty(), parameters),
				McpRequestStateBinding.create("/mcp", "future", "tools/call",
						Optional.empty(), parameters),
				McpRequestStateBinding.create("/mcp", "2026-07-28", "prompts/get",
						Optional.empty(), parameters),
				McpRequestStateBinding.create("/mcp", "2026-07-28", "tools/call",
						Optional.of("anonymous"), parameters)))
			Assertions.assertFalse(java.security.MessageDigest.isEqual(
					baseline.bytes(), variant.bytes()));
	}

	@Test
	public void validatesBindingTextAndReturnsDefensiveCopies() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateBinding.create("", "2026-07-28", "tools/call",
						Optional.empty(), vectorParameters()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateBinding.create("/mcp", "bad\uD800", "tools/call",
						Optional.empty(), vectorParameters()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateBinding.create("/mcp", "2026-07-28", "tools/call",
						Optional.of("x".repeat(257)), vectorParameters()));
		String exactly256Utf8Bytes = "é".repeat(127) + "ab";
		String exactly257Utf8Bytes = "é".repeat(127) + "abc";
		Assertions.assertDoesNotThrow(() -> McpRequestStateBinding.create(
				"/mcp", "2026-07-28", "tools/call",
				Optional.of(exactly256Utf8Bytes), vectorParameters()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateBinding.create(
						"/mcp", "2026-07-28", "tools/call",
						Optional.of(exactly257Utf8Bytes), vectorParameters()));

		McpRequestStateBinding binding = binding(vectorParameters());
		byte[] bytes = binding.bytes();
		byte[] digest = binding.digest();
		byte[] parametersDigest = binding.parametersDigest();
		bytes[0] ^= 0x7F;
		digest[0] ^= 0x7F;
		parametersDigest[0] ^= 0x7F;
		Assertions.assertNotEquals(bytes[0], binding.bytes()[0]);
		Assertions.assertNotEquals(digest[0], binding.digest()[0]);
		Assertions.assertNotEquals(
				parametersDigest[0], binding.parametersDigest()[0]);
	}

	private static McpRequestStateBinding binding(McpJsonObject parameters) {
		return McpRequestStateBinding.create(
				"/mcp", "2026-07-28", "tools/call",
				Optional.of("tenant"), parameters);
	}

	private static McpJsonObject vectorParameters() {
		Map<String, McpJsonValue> metadata = new LinkedHashMap<>();
		metadata.put("progressToken", new McpJsonString("discard"));
		metadata.put("stable", new McpJsonString("yes"));
		Map<String, McpJsonValue> parameters = new LinkedHashMap<>();
		parameters.put("requestState", new McpJsonString("discard"));
		parameters.put("name", new McpJsonString("echo"));
		parameters.put("inputResponses", new McpJsonObject(Map.of()));
		parameters.put("_meta", new McpJsonObject(metadata));
		return new McpJsonObject(parameters);
	}

	private static McpJsonObject parameters(String inputResponses,
			String requestState, String progressToken, String traceparent,
			String stable, String nestedRequestState) {
		Map<String, McpJsonValue> nested = new LinkedHashMap<>();
		nested.put("requestState", new McpJsonString(nestedRequestState));
		nested.put("traceparent", new McpJsonString("nested-trace"));
		Map<String, McpJsonValue> metadata = new LinkedHashMap<>();
		metadata.put("progressToken", new McpJsonString(progressToken));
		metadata.put("traceparent", new McpJsonString(traceparent));
		metadata.put("tracestate", new McpJsonString("discarded"));
		metadata.put("baggage", new McpJsonString("discarded"));
		metadata.put("com.acme/requestId", new McpJsonString(stable));
		metadata.put("com.acme/nested", new McpJsonObject(nested));
		Map<String, McpJsonValue> parameters = new LinkedHashMap<>();
		parameters.put("inputResponses", new McpJsonString(inputResponses));
		parameters.put("requestState", new McpJsonString(requestState));
		parameters.put("_meta", new McpJsonObject(metadata));
		parameters.put("arguments", new McpJsonObject(Map.of(
				"requestState", new McpJsonString(nestedRequestState))));
		return new McpJsonObject(parameters);
	}
}
