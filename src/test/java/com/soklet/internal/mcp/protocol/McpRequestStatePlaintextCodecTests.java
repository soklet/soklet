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
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class McpRequestStatePlaintextCodecTests {
	private static final int MAXIMUM_BYTES = 4_096;
	private static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(15);
	private static final int MAXIMUM_ROUNDS = 3;

	@Test
	public void matchesDeterministicCanonicalPlaintextVector() {
		McpFrameworkRequestStateContinuation continuation = continuation(
				1_700_000_001L, 123_456_789,
				1_700_000_901L, 123_456_789, 2,
				new McpJsonRpcId.StringId("request-α"),
				new McpJsonObject(Map.of(
						"message", new McpJsonString("café"),
						"count", new McpJsonNumber(new BigDecimal("1000.00")))));

		byte[] plaintext = encode(continuation);
		Assertions.assertEquals(
				"{\"bindingDigest\":\"jYcZKFIfR1LvthdQbyVIXfaPmOKwwRrNkZTGTfnX4yY\","
						+ "\"expiresAtEpochSecond\":1700000901,"
						+ "\"expiresAtNanoAdjustment\":123456789,"
						+ "\"issuedAtEpochSecond\":1700000001,"
						+ "\"issuedAtNanoAdjustment\":123456789,"
						+ "\"originatingRequestId\":\"request-α\",\"round\":2,"
						+ "\"state\":{\"count\":1E+3,\"message\":\"café\"},\"version\":1}",
				new String(plaintext, StandardCharsets.UTF_8));

		McpFrameworkRequestStateContinuation decoded = decode(plaintext,
				Instant.ofEpochSecond(1_700_000_100L),
				new McpJsonRpcId.StringId("request-next"));
		Assertions.assertEquals(continuation.issuedAt(), decoded.issuedAt());
		Assertions.assertEquals(continuation.expiresAt(), decoded.expiresAt());
		Assertions.assertEquals(continuation.round(), decoded.round());
		Assertions.assertEquals(continuation.originatingRequestId(),
				decoded.originatingRequestId());
		Assertions.assertEquals(
				"{\"count\":1E+3,\"message\":\"café\"}",
				new String(McpRequestStateCanonicalJson.canonicalize(
						decoded.state(), MAXIMUM_BYTES), StandardCharsets.UTF_8));
	}

	@Test
	public void expiryBoundaryFutureIssuanceAndMaximumRoundAreExact() {
		McpFrameworkRequestStateContinuation futureIssued = continuation(
				2_000L, 250_000_000, 2_010L, 250_000_000,
				MAXIMUM_ROUNDS, new McpJsonRpcId.IntegerId(BigInteger.ONE),
				McpJsonNull.INSTANCE);
		byte[] plaintext = McpRequestStatePlaintextCodec.encode(futureIssued,
				binding(), MAXIMUM_BYTES, Duration.ofSeconds(10), MAXIMUM_ROUNDS);

		McpFrameworkRequestStateContinuation decoded =
				McpRequestStatePlaintextCodec.decode(plaintext, binding(),
						MAXIMUM_BYTES, Duration.ofSeconds(10), MAXIMUM_ROUNDS,
						Instant.ofEpochSecond(1_000L),
						new McpJsonRpcId.IntegerId(BigInteger.TWO));
		Assertions.assertEquals(MAXIMUM_ROUNDS, decoded.round());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> decoded.next(McpJsonNull.INSTANCE,
						new McpJsonRpcId.IntegerId(BigInteger.TWO), MAXIMUM_ROUNDS));

		Assertions.assertDoesNotThrow(() ->
				McpRequestStatePlaintextCodec.decode(plaintext, binding(),
						MAXIMUM_BYTES, Duration.ofSeconds(10), MAXIMUM_ROUNDS,
						Instant.ofEpochSecond(2_010L, 249_999_999),
						new McpJsonRpcId.IntegerId(BigInteger.TWO)));
		assertInvalid(() -> McpRequestStatePlaintextCodec.decode(
				plaintext, binding(), MAXIMUM_BYTES, Duration.ofSeconds(10),
				MAXIMUM_ROUNDS, Instant.ofEpochSecond(2_010L, 250_000_000),
				new McpJsonRpcId.IntegerId(BigInteger.TWO)));
	}

	@Test
	public void preservesArbitraryPrecisionIntegerIdsAndComparesWireType() {
		BigInteger hugeId = new BigInteger(
				"1234567890123456789012345678901234567890");
		McpFrameworkRequestStateContinuation integerContinuation = continuation(
				100L, 0, 110L, 0, 1,
				new McpJsonRpcId.IntegerId(hugeId), McpJsonBoolean.TRUE);
		byte[] integerPlaintext = McpRequestStatePlaintextCodec.encode(
				integerContinuation, binding(), MAXIMUM_BYTES,
				Duration.ofSeconds(10), MAXIMUM_ROUNDS);
		McpFrameworkRequestStateContinuation decoded =
				McpRequestStatePlaintextCodec.decode(integerPlaintext, binding(),
						MAXIMUM_BYTES, Duration.ofSeconds(10), MAXIMUM_ROUNDS,
						Instant.ofEpochSecond(105L),
						new McpJsonRpcId.StringId(hugeId.toString()));
		Assertions.assertEquals(
				new McpJsonRpcId.IntegerId(hugeId), decoded.originatingRequestId());
		assertInvalid(() -> McpRequestStatePlaintextCodec.decode(
				integerPlaintext, binding(), MAXIMUM_BYTES, Duration.ofSeconds(10),
				MAXIMUM_ROUNDS, Instant.ofEpochSecond(105L),
				new McpJsonRpcId.IntegerId(hugeId)));

		McpFrameworkRequestStateContinuation stringContinuation = continuation(
				100L, 0, 110L, 0, 1,
				new McpJsonRpcId.StringId("7"), McpJsonBoolean.TRUE);
		byte[] stringPlaintext = McpRequestStatePlaintextCodec.encode(
				stringContinuation, binding(), MAXIMUM_BYTES,
				Duration.ofSeconds(10), MAXIMUM_ROUNDS);
		Assertions.assertDoesNotThrow(() -> McpRequestStatePlaintextCodec.decode(
				stringPlaintext, binding(), MAXIMUM_BYTES, Duration.ofSeconds(10),
				MAXIMUM_ROUNDS, Instant.ofEpochSecond(105L),
				new McpJsonRpcId.IntegerId(BigInteger.valueOf(7L))));
	}

	@Test
	public void supportsTheFullSignedEpochSecondWidth() {
		McpFrameworkRequestStateContinuation continuation = continuation(
				Long.MAX_VALUE - 10L, 999_999_999,
				Long.MAX_VALUE - 1L, 999_999_999, 1,
				new McpJsonRpcId.StringId("origin"), McpJsonNull.INSTANCE);
		byte[] plaintext = McpRequestStatePlaintextCodec.encode(continuation,
				binding(), MAXIMUM_BYTES, Duration.ofSeconds(9), MAXIMUM_ROUNDS);

		McpFrameworkRequestStateContinuation decoded =
				McpRequestStatePlaintextCodec.decode(plaintext, binding(),
						MAXIMUM_BYTES, Duration.ofSeconds(9), MAXIMUM_ROUNDS,
						Instant.EPOCH, new McpJsonRpcId.StringId("next"));
		Assertions.assertEquals(Long.MAX_VALUE - 10L,
				decoded.issuedAt().epochSecond());
		Assertions.assertEquals(Long.MAX_VALUE - 1L,
				decoded.expiresAt().epochSecond());

		McpFrameworkRequestStateContinuation minimum = continuation(
				Long.MIN_VALUE, 0, Long.MIN_VALUE + 9L, 0, 1,
				new McpJsonRpcId.StringId("origin"), McpJsonNull.INSTANCE);
		String minimumPlaintext = new String(McpRequestStatePlaintextCodec.encode(
				minimum, binding(), MAXIMUM_BYTES, Duration.ofSeconds(9),
				MAXIMUM_ROUNDS), StandardCharsets.UTF_8);
		Assertions.assertTrue(minimumPlaintext.contains(
				"\"issuedAtEpochSecond\":-9223372036854775808"));
	}

	@Test
	public void validatesSubsecondMaximumLifetimeExactly() {
		McpFrameworkRequestStateContinuation continuation = continuation(
				100L, 900_000_000, 101L, 100_000_000, 1,
				new McpJsonRpcId.StringId("origin"), McpJsonNull.INSTANCE);
		byte[] plaintext = McpRequestStatePlaintextCodec.encode(continuation,
				binding(), MAXIMUM_BYTES, Duration.ofMillis(200L), MAXIMUM_ROUNDS);
		Assertions.assertDoesNotThrow(() -> McpRequestStatePlaintextCodec.decode(
				plaintext, binding(), MAXIMUM_BYTES, Duration.ofMillis(200L),
				MAXIMUM_ROUNDS, Instant.ofEpochSecond(100L, 950_000_000),
				new McpJsonRpcId.StringId("next")));
		assertInvalid(() -> McpRequestStatePlaintextCodec.decode(
				plaintext, binding(), MAXIMUM_BYTES, Duration.ofNanos(199_999_999L),
				MAXIMUM_ROUNDS, Instant.ofEpochSecond(100L, 950_000_000),
				new McpJsonRpcId.StringId("next")));
	}

	@Test
	public void rejectsWrongBindingNoncanonicalBytesAndSizeViolations() {
		byte[] plaintext = encode(normalContinuation());
		McpRequestStateBinding wrongBinding = McpRequestStateBinding.create(
				"/other", "2026-07-28", "tools/call",
				Optional.of("tenant-α"), vectorParameters());
		assertInvalid(() -> McpRequestStatePlaintextCodec.decode(
				plaintext, wrongBinding, MAXIMUM_BYTES, MAXIMUM_LIFETIME,
				MAXIMUM_ROUNDS, Instant.ofEpochSecond(1_700_000_100L),
				new McpJsonRpcId.StringId("next")));

		byte[] noncanonical = (" " + new String(plaintext, StandardCharsets.UTF_8))
				.getBytes(StandardCharsets.UTF_8);
		assertInvalid(() -> decode(noncanonical,
				Instant.ofEpochSecond(1_700_000_100L),
				new McpJsonRpcId.StringId("next")));
		assertInvalid(() -> McpRequestStatePlaintextCodec.decode(
				plaintext, binding(), plaintext.length - 1, MAXIMUM_LIFETIME,
				MAXIMUM_ROUNDS, Instant.ofEpochSecond(1_700_000_100L),
				new McpJsonRpcId.StringId("next")));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStatePlaintextCodec.encode(normalContinuation(),
						binding(), plaintext.length - 1, MAXIMUM_LIFETIME,
						MAXIMUM_ROUNDS));
	}

	@Test
	public void rejectsClosedObjectAndSemanticViolationsWithOneDiagnostic() {
		byte[] valid = encode(normalContinuation());
		Map<String, McpJsonValue> fields = validFields(valid);

		Map<String, McpJsonValue> extra = new LinkedHashMap<>(fields);
		extra.put("future", McpJsonNull.INSTANCE);
		assertInvalidPlaintext(extra);
		Map<String, McpJsonValue> missing = new LinkedHashMap<>(fields);
		missing.remove("state");
		assertInvalidPlaintext(missing);
		assertInvalidPlaintext(replacing(fields, "version", new McpJsonNumber(2L)));
		assertInvalidPlaintext(replacing(fields, "round", new McpJsonNumber(0L)));
		assertInvalidPlaintext(replacing(fields, "round", new McpJsonNumber(4L)));
		assertInvalidPlaintext(replacing(fields, "issuedAtNanoAdjustment",
				new McpJsonNumber(1_000_000_000L)));
		assertInvalidPlaintext(replacing(fields, "expiresAtEpochSecond",
				fields.get("issuedAtEpochSecond")));
		assertInvalidPlaintext(replacing(fields, "originatingRequestId",
				McpJsonNull.INSTANCE));
		assertInvalidPlaintext(replacing(fields, "issuedAtEpochSecond",
				new McpJsonNumber(new BigDecimal(
						BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)))));

		String digest = ((McpJsonString) fields.get("bindingDigest")).value();
		char last = digest.charAt(digest.length() - 1);
		String noncanonicalTrailingBits = digest.substring(0, digest.length() - 1)
				+ (last == 'Z' ? 'a' : (char) (last + 1));
		assertInvalidPlaintext(replacing(fields, "bindingDigest",
				new McpJsonString(noncanonicalTrailingBits)));

		McpFrameworkRequestStateContinuation longLifetime = continuation(
				100L, 0, 111L, 0, 1,
				new McpJsonRpcId.StringId("origin"), McpJsonNull.INSTANCE);
		byte[] longLifetimePlaintext = McpRequestStatePlaintextCodec.encode(
				longLifetime, binding(), MAXIMUM_BYTES,
				Duration.ofSeconds(11), MAXIMUM_ROUNDS);
		assertInvalid(() -> McpRequestStatePlaintextCodec.decode(
				longLifetimePlaintext, binding(), MAXIMUM_BYTES,
				Duration.ofSeconds(10), MAXIMUM_ROUNDS,
				Instant.ofEpochSecond(105L),
				new McpJsonRpcId.StringId("next")));
	}

	@Test
	public void initialAndNextPreserveTimestampsAndAdvanceCurrentId() {
		Instant now = Instant.ofEpochSecond(10L, 900_000_000);
		McpFrameworkRequestStateContinuation initial =
				McpFrameworkRequestStateContinuation.initial(
						new McpJsonString("one"), now,
						Duration.ofMillis(200L),
						new McpJsonRpcId.StringId("first"));
		Assertions.assertEquals(new McpRequestStateTimestamp(10L, 900_000_000),
				initial.issuedAt());
		Assertions.assertEquals(new McpRequestStateTimestamp(11L, 100_000_000),
				initial.expiresAt());

		McpFrameworkRequestStateContinuation next = initial.next(
				new McpJsonString("two"),
				new McpJsonRpcId.StringId("second"), MAXIMUM_ROUNDS);
		Assertions.assertEquals(initial.issuedAt(), next.issuedAt());
		Assertions.assertEquals(initial.expiresAt(), next.expiresAt());
		Assertions.assertEquals(2, next.round());
		Assertions.assertEquals(new McpJsonRpcId.StringId("second"),
				next.originatingRequestId());
		Assertions.assertEquals(new McpJsonString("two"), next.state());
	}

	private static byte[] encode(
			McpFrameworkRequestStateContinuation continuation) {
		return McpRequestStatePlaintextCodec.encode(continuation, binding(),
				MAXIMUM_BYTES, MAXIMUM_LIFETIME, MAXIMUM_ROUNDS);
	}

	private static McpFrameworkRequestStateContinuation decode(byte[] plaintext,
			Instant now, McpJsonRpcId currentRequestId) {
		return McpRequestStatePlaintextCodec.decode(plaintext, binding(),
				MAXIMUM_BYTES, MAXIMUM_LIFETIME, MAXIMUM_ROUNDS,
				now, currentRequestId);
	}

	private static McpFrameworkRequestStateContinuation normalContinuation() {
		return continuation(1_700_000_001L, 123_456_789,
				1_700_000_901L, 123_456_789, 2,
				new McpJsonRpcId.StringId("origin"),
				new McpJsonObject(Map.of("value", new McpJsonString("state"))));
	}

	private static McpFrameworkRequestStateContinuation continuation(
			long issuedSecond, int issuedNano,
			long expiresSecond, int expiresNano,
			int round, McpJsonRpcId originatingRequestId, McpJsonValue state) {
		return new McpFrameworkRequestStateContinuation(
				new McpRequestStateTimestamp(issuedSecond, issuedNano),
				new McpRequestStateTimestamp(expiresSecond, expiresNano),
				round, originatingRequestId, state);
	}

	private static McpRequestStateBinding binding() {
		return McpRequestStateBinding.create("/mcp", "2026-07-28", "tools/call",
				Optional.of("tenant-α"), vectorParameters());
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

	private static Map<String, McpJsonValue> validFields(byte[] valid) {
		return new LinkedHashMap<>(((McpJsonObject) new McpJsonCodec(
				McpJsonLimits.productionDefaults()).parse(valid)).members());
	}

	private static Map<String, McpJsonValue> replacing(
			Map<String, McpJsonValue> fields, String name, McpJsonValue value) {
		Map<String, McpJsonValue> replaced = new LinkedHashMap<>(fields);
		replaced.put(name, value);
		return replaced;
	}

	private static void assertInvalidPlaintext(Map<String, McpJsonValue> fields) {
		byte[] plaintext = McpRequestStateCanonicalJson.canonicalize(
				new McpJsonObject(fields), MAXIMUM_BYTES);
		assertInvalid(() -> decode(plaintext,
				Instant.ofEpochSecond(1_700_000_100L),
				new McpJsonRpcId.StringId("next")));
	}

	private static void assertInvalid(Executable executable) {
		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class, executable);
		Assertions.assertEquals(
				"Framework request-state plaintext is invalid.",
				exception.getMessage());
		Assertions.assertNull(exception.getCause());
	}
}
