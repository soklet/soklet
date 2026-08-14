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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Closed canonical plaintext codec for framework-protected request state.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpRequestStatePlaintextCodec {
	private static final int VERSION_1 = 1;
	private static final int VERSION_2 = 2;
	private static final int SHA_256_BYTES = 32;
	private static final int SHA_256_BASE64URL_CHARACTERS = 43;
	private static final int MAXIMUM_SELECTED_LOCALE_BYTES = 255;
	private static final Set<@NonNull String> VERSION_1_FIELDS = Set.of(
			"version", "bindingDigest", "issuedAtEpochSecond",
			"issuedAtNanoAdjustment", "expiresAtEpochSecond",
			"expiresAtNanoAdjustment", "round", "originatingRequestId", "state");
	@NonNull
	private static final Set<@NonNull String> VERSION_2_FIELDS;

	static {
		Set<String> version2Fields = new java.util.HashSet<>(VERSION_1_FIELDS);
		version2Fields.add("selectedLocale");
		VERSION_2_FIELDS = Set.copyOf(version2Fields);
	}

	private McpRequestStatePlaintextCodec() {
	}

	static byte @NonNull [] encode(
			@NonNull McpFrameworkRequestStateContinuation continuation,
			@NonNull McpRequestStateBinding binding,
			int maximumDecodedBytes,
			@NonNull Duration maximumLifetime,
			int maximumRounds) {
		requireNonNull(continuation);
		requireNonNull(binding);
		validateConfiguration(maximumDecodedBytes, maximumLifetime, maximumRounds);
		validateContinuation(continuation, maximumLifetime, maximumRounds);

		String selectedLocale = continuation.selectedLocale();

		if (selectedLocale != null)
			requireCanonicalSelectedLocale(selectedLocale);

		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		// A continuation without a selected locale emits the exact version-1
		// bytes, so localization-disabled emission never changes goldens.
		fields.put("version", new McpJsonNumber(
				selectedLocale == null ? VERSION_1 : VERSION_2));
		fields.put("bindingDigest", new McpJsonString(
				encodeDigest(binding.digest())));
		fields.put("issuedAtEpochSecond", new McpJsonNumber(
				continuation.issuedAt().epochSecond()));
		fields.put("issuedAtNanoAdjustment", new McpJsonNumber(
				continuation.issuedAt().nanoAdjustment()));
		fields.put("expiresAtEpochSecond", new McpJsonNumber(
				continuation.expiresAt().epochSecond()));
		fields.put("expiresAtNanoAdjustment", new McpJsonNumber(
				continuation.expiresAt().nanoAdjustment()));
		fields.put("round", new McpJsonNumber(continuation.round()));
		fields.put("originatingRequestId",
				continuation.originatingRequestId().toJsonValue());
		fields.put("state", continuation.state());

		if (selectedLocale != null)
			fields.put("selectedLocale", new McpJsonString(selectedLocale));

		return McpRequestStateCanonicalJson.canonicalize(
				new McpJsonObject(fields), maximumDecodedBytes);
	}

	@NonNull
	static McpFrameworkRequestStateContinuation decode(
			byte @NonNull [] plaintext,
			@NonNull McpRequestStateBinding binding,
			int maximumDecodedBytes,
			@NonNull Duration maximumLifetime,
			int maximumRounds,
			@NonNull Instant now,
			@NonNull McpJsonRpcId currentRequestId) {
		requireNonNull(plaintext);
		requireNonNull(binding);
		requireNonNull(now);
		requireNonNull(currentRequestId);
		validateConfiguration(maximumDecodedBytes, maximumLifetime, maximumRounds);
		try {
			return decodeValidated(plaintext, binding, maximumDecodedBytes,
					maximumLifetime, maximumRounds, now, currentRequestId);
		} catch (IllegalArgumentException exception) {
			throw invalidPlaintext();
		}
	}

	@NonNull
	private static McpFrameworkRequestStateContinuation decodeValidated(
			byte @NonNull [] plaintext,
			@NonNull McpRequestStateBinding binding,
			int maximumDecodedBytes,
			@NonNull Duration maximumLifetime,
			int maximumRounds,
			@NonNull Instant now,
			@NonNull McpJsonRpcId currentRequestId) {
		McpJsonValue parsed = McpRequestStateCanonicalJson.parseCanonical(
				plaintext, maximumDecodedBytes);
		if (!(parsed instanceof McpJsonObject object))
			throw invalidPlaintext();
		boolean version2 = object.members().keySet().equals(VERSION_2_FIELDS);
		if (!version2 && !object.members().keySet().equals(VERSION_1_FIELDS))
			throw invalidPlaintext();

		Map<String, McpJsonValue> fields = object.members();
		// The version discriminator and the field set must agree exactly: a
		// version-1 object smuggling selectedLocale, or a version-2 object
		// missing it, is tampering rather than a format variant.
		if (requireInt(fields, "version") != (version2 ? VERSION_2 : VERSION_1))
			throw invalidPlaintext();
		String selectedLocale = null;
		if (version2) {
			selectedLocale = requireString(fields, "selectedLocale");
			try {
				requireCanonicalSelectedLocale(selectedLocale);
			} catch (RuntimeException exception) {
				throw invalidPlaintext();
			}
		}
		byte[] encodedBindingDigest = decodeDigest(
				requireString(fields, "bindingDigest"));
		if (!MessageDigest.isEqual(encodedBindingDigest, binding.digest()))
			throw invalidPlaintext();

		McpRequestStateTimestamp issuedAt = new McpRequestStateTimestamp(
				requireLong(fields, "issuedAtEpochSecond"),
				requireInt(fields, "issuedAtNanoAdjustment"));
		McpRequestStateTimestamp expiresAt = new McpRequestStateTimestamp(
				requireLong(fields, "expiresAtEpochSecond"),
				requireInt(fields, "expiresAtNanoAdjustment"));
		int round = requireInt(fields, "round");
		McpJsonRpcId originatingRequestId = requireRequestId(
				requireField(fields, "originatingRequestId"));
		McpFrameworkRequestStateContinuation continuation =
				new McpFrameworkRequestStateContinuation(issuedAt, expiresAt,
						round, originatingRequestId, requireField(fields, "state"),
						selectedLocale);
		validateContinuation(continuation, maximumLifetime, maximumRounds);
		if (McpRequestStateTimestamp.fromInstant(now).compareTo(expiresAt) >= 0)
			throw invalidPlaintext();
		if (originatingRequestId.equals(currentRequestId))
			throw invalidPlaintext();
		return continuation;
	}

	/**
	 * Requires a canonical non-root ASCII BCP 47 tag of at most 255 bytes,
	 * mirroring the public localization locale rules exactly.
	 */
	private static void requireCanonicalSelectedLocale(@NonNull String tag) {
		requireNonNull(tag);

		if (tag.isEmpty() || tag.length() > MAXIMUM_SELECTED_LOCALE_BYTES)
			throw new IllegalArgumentException(
					"Request-state selected locale must be 1-255 bytes.");

		for (int index = 0; index < tag.length(); ++index)
			if (tag.charAt(index) > 0x7F)
				throw new IllegalArgumentException(
						"Request-state selected locale must be ASCII.");

		if (tag.equalsIgnoreCase("und")
				|| tag.regionMatches(true, 0, "und-", 0, 4))
			throw new IllegalArgumentException(
					"Request-state selected locale must not be root.");

		java.util.Locale locale = java.util.Locale.forLanguageTag(tag);

		if (!tag.equals(locale.toLanguageTag()))
			throw new IllegalArgumentException(
					"Request-state selected locale must be canonical.");

		try {
			new java.util.Locale.Builder().setLocale(locale).build();
		} catch (java.util.IllformedLocaleException exception) {
			throw new IllegalArgumentException(
					"Request-state selected locale must be well-formed.");
		}
	}

	private static void validateConfiguration(int maximumDecodedBytes,
			@NonNull Duration maximumLifetime, int maximumRounds) {
		if (maximumDecodedBytes < 1)
			throw new IllegalArgumentException(
					"Maximum decoded request-state bytes must be positive.");
		McpRequestStateTimestamp.requirePositiveDuration(maximumLifetime);
		if (maximumRounds < 1)
			throw new IllegalArgumentException(
					"Maximum request-state rounds must be positive.");
	}

	private static void validateContinuation(
			@NonNull McpFrameworkRequestStateContinuation continuation,
			@NonNull Duration maximumLifetime, int maximumRounds) {
		if (continuation.round() > maximumRounds)
			throw invalidPlaintext();
		if (!lifetimeAtMost(continuation.issuedAt(), continuation.expiresAt(),
				maximumLifetime))
			throw invalidPlaintext();
	}

	private static boolean lifetimeAtMost(
			@NonNull McpRequestStateTimestamp issuedAt,
			@NonNull McpRequestStateTimestamp expiresAt,
			@NonNull Duration maximumLifetime) {
		if (issuedAt.compareTo(expiresAt) >= 0)
			return false;

		long seconds;
		try {
			seconds = Math.subtractExact(
					expiresAt.epochSecond(), issuedAt.epochSecond());
		} catch (ArithmeticException exception) {
			return false;
		}
		int nanos = expiresAt.nanoAdjustment() - issuedAt.nanoAdjustment();
		if (nanos < 0) {
			seconds--;
			nanos += 1_000_000_000;
		}
		return seconds < maximumLifetime.getSeconds()
				|| (seconds == maximumLifetime.getSeconds()
				&& nanos <= maximumLifetime.getNano());
	}

	@NonNull
	private static McpJsonRpcId requireRequestId(@NonNull McpJsonValue value) {
		if (value instanceof McpJsonString string)
			return new McpJsonRpcId.StringId(string.value());
		if (value instanceof McpJsonNumber number)
			return new McpJsonRpcId.IntegerId(
					McpJsonIntegerSupport.toSerializableInteger(number.value(),
							McpJsonLimits.productionDefaults()));
		throw invalidPlaintext();
	}

	@NonNull
	private static BigInteger requireInteger(
			@NonNull Map<String, McpJsonValue> fields,
			@NonNull String fieldName) {
		McpJsonValue value = requireField(fields, fieldName);
		if (!(value instanceof McpJsonNumber number))
			throw invalidPlaintext();
		BigDecimal decimal = number.value();
		try {
			return decimal.toBigIntegerExact();
		} catch (ArithmeticException exception) {
			throw invalidPlaintext();
		}
	}

	private static int requireInt(
			@NonNull Map<String, McpJsonValue> fields,
			@NonNull String fieldName) {
		try {
			return requireInteger(fields, fieldName).intValueExact();
		} catch (ArithmeticException exception) {
			throw invalidPlaintext();
		}
	}

	private static long requireLong(
			@NonNull Map<String, McpJsonValue> fields,
			@NonNull String fieldName) {
		try {
			return requireInteger(fields, fieldName).longValueExact();
		} catch (ArithmeticException exception) {
			throw invalidPlaintext();
		}
	}

	@NonNull
	private static String requireString(
			@NonNull Map<String, McpJsonValue> fields,
			@NonNull String fieldName) {
		McpJsonValue value = requireField(fields, fieldName);
		if (!(value instanceof McpJsonString string))
			throw invalidPlaintext();
		return string.value();
	}

	@NonNull
	private static McpJsonValue requireField(
			@NonNull Map<String, McpJsonValue> fields,
			@NonNull String fieldName) {
		McpJsonValue value = fields.get(fieldName);
		if (value == null)
			throw invalidPlaintext();
		return value;
	}

	@NonNull
	private static String encodeDigest(byte @NonNull [] digest) {
		if (requireNonNull(digest).length != SHA_256_BYTES)
			throw new IllegalArgumentException(
					"Binding digest must contain 32 bytes.");
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}

	private static byte @NonNull [] decodeDigest(@NonNull String encoded) {
		requireNonNull(encoded);
		if (encoded.length() != SHA_256_BASE64URL_CHARACTERS)
			throw invalidPlaintext();
		for (int index = 0; index < encoded.length(); ++index) {
			char character = encoded.charAt(index);
			if (!isBase64UrlCharacter(character))
				throw invalidPlaintext();
		}

		byte[] decoded;
		try {
			decoded = Base64.getUrlDecoder().decode(encoded);
		} catch (IllegalArgumentException exception) {
			throw invalidPlaintext();
		}
		if (decoded.length != SHA_256_BYTES
				|| !encodeDigest(decoded).equals(encoded))
			throw invalidPlaintext();
		return decoded;
	}

	private static boolean isBase64UrlCharacter(char character) {
		if (character >= 'A' && character <= 'Z')
			return true;
		if (character >= 'a' && character <= 'z')
			return true;
		if (character >= '0' && character <= '9')
			return true;
		return character == '_' || character == '-';
	}

	@NonNull
	private static IllegalArgumentException invalidPlaintext() {
		return new IllegalArgumentException(
				"Framework request-state plaintext is invalid.");
	}
}
