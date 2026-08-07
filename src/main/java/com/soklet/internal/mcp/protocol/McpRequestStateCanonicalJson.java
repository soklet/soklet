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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Canonical JSON used exclusively by protected MCP request state.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpRequestStateCanonicalJson {
	@NonNull
	private static final McpJsonLimits STRICT_LIMITS =
			McpJsonLimits.productionDefaults();

	private McpRequestStateCanonicalJson() {
	}

	static byte @NonNull [] canonicalize(
			@NonNull McpJsonValue value, int maximumBytes) {
		requireNonNull(value);
		McpJsonLimits limits = limitsFor(maximumBytes);
		McpJsonValue normalized = new Normalizer(limits).normalize(value);
		return new McpJsonCodec(limits).toUtf8Bytes(normalized);
	}

	@NonNull
	static McpJsonValue parseCanonical(
			byte @NonNull [] canonicalUtf8, int maximumBytes) {
		requireNonNull(canonicalUtf8);
		McpJsonLimits limits = limitsFor(maximumBytes);
		if (canonicalUtf8.length > maximumBytes)
			throw new IllegalArgumentException(
					"Canonical request-state JSON exceeds its byte limit.");

		McpJsonValue parsed = new McpJsonCodec(limits).parse(canonicalUtf8);
		byte[] reserialized = canonicalize(parsed, maximumBytes);
		if (!Arrays.equals(canonicalUtf8, reserialized))
			throw new IllegalArgumentException(
					"Request-state plaintext is not canonical JSON.");
		return parsed;
	}

	static byte @NonNull [] strictUtf8(@NonNull String value,
			int maximumBytes, @NonNull String description) {
		requireNonNull(value);
		requireNonNull(description);
		if (maximumBytes < 0)
			throw new IllegalArgumentException("Maximum UTF-8 bytes must not be negative.");

		long encodedLength = 0L;
		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);
			if (character <= 0x7F) {
				encodedLength++;
			} else if (character <= 0x7FF) {
				encodedLength += 2L;
			} else if (Character.isHighSurrogate(character)) {
				if (index + 1 >= value.length()
						|| !Character.isLowSurrogate(value.charAt(index + 1)))
					throw new IllegalArgumentException(
							description + " contains invalid Unicode.");
				encodedLength += 4L;
				index++;
			} else if (Character.isLowSurrogate(character)) {
				throw new IllegalArgumentException(
						description + " contains invalid Unicode.");
			} else {
				encodedLength += 3L;
			}

			if (encodedLength > maximumBytes)
				throw new IllegalArgumentException(
						description + " exceeds its UTF-8 byte limit.");
		}
		return value.getBytes(StandardCharsets.UTF_8);
	}

	@NonNull
	private static McpJsonLimits limitsFor(int maximumBytes) {
		if (maximumBytes < 1)
			throw new IllegalArgumentException(
					"Maximum canonical JSON bytes must be positive.");
		int boundedBytes = Math.min(maximumBytes,
				STRICT_LIMITS.maximumInputBytes());
		return new McpJsonLimits(boundedBytes,
				STRICT_LIMITS.maximumNestingDepth(),
				STRICT_LIMITS.maximumTokenLengthInCharacters(),
				STRICT_LIMITS.maximumStringLengthInCharacters(),
				STRICT_LIMITS.maximumNumberLengthInCharacters(),
				STRICT_LIMITS.maximumExponentMagnitude(),
				STRICT_LIMITS.maximumNodeCount(), boundedBytes);
	}

	private static int compareUnsigned(
			byte @NonNull [] left, byte @NonNull [] right) {
		int sharedLength = Math.min(left.length, right.length);
		for (int index = 0; index < sharedLength; ++index) {
			int comparison = Integer.compare(
					Byte.toUnsignedInt(left[index]),
					Byte.toUnsignedInt(right[index]));
			if (comparison != 0)
				return comparison;
		}
		return Integer.compare(left.length, right.length);
	}

	@NotThreadSafe
	private static final class Normalizer {
		@NonNull
		private final McpJsonLimits limits;
		private int nodeCount;

		private Normalizer(@NonNull McpJsonLimits limits) {
			this.limits = requireNonNull(limits);
		}

		@NonNull
		private McpJsonValue normalize(@NonNull McpJsonValue value) {
			return normalize(value, 1);
		}

		@NonNull
		private McpJsonValue normalize(@NonNull McpJsonValue value, int depth) {
			requireNonNull(value);
			if (depth > limits.maximumNestingDepth())
				throw new IllegalArgumentException(
						"Canonical JSON exceeds the configured depth limit.");
			if (++nodeCount > limits.maximumNodeCount())
				throw new IllegalArgumentException(
						"Canonical JSON exceeds the configured node limit.");

			if (value instanceof McpJsonObject object)
				return normalizeObject(object, depth);
			if (value instanceof McpJsonArray array) {
				List<McpJsonValue> elements = new ArrayList<>(array.values().size());
				for (McpJsonValue element : array.values())
					elements.add(normalize(element, depth + 1));
				return new McpJsonArray(elements);
			}
			if (value instanceof McpJsonNumber number) {
				BigDecimal decimal = number.value();
				return new McpJsonNumber(decimal.signum() == 0
						? BigDecimal.ZERO : decimal.stripTrailingZeros());
			}
			if (value instanceof McpJsonString
					|| value instanceof McpJsonBoolean
					|| value instanceof McpJsonNull)
				return value;
			throw new IllegalArgumentException(
					"Unsupported canonical JSON value implementation.");
		}

		@NonNull
		private McpJsonObject normalizeObject(
				@NonNull McpJsonObject object, int depth) {
			List<EncodedMember> members = new ArrayList<>(object.members().size());
			for (Map.Entry<String, McpJsonValue> entry : object.members().entrySet()) {
				String name = requireNonNull(entry.getKey());
				byte[] nameUtf8 = strictUtf8(name, limits.maximumOutputBytes(),
						"Canonical JSON object key");
				members.add(new EncodedMember(name, nameUtf8,
						normalize(requireNonNull(entry.getValue()), depth + 1)));
			}
			members.sort((left, right) ->
					compareUnsigned(left.nameUtf8, right.nameUtf8));

			Map<String, McpJsonValue> sorted = new LinkedHashMap<>(members.size());
			for (EncodedMember member : members)
				sorted.put(member.name(), member.value());
			return new McpJsonObject(sorted);
		}
	}

		@ThreadSafe
		private static final class EncodedMember {
			@NonNull
			private final String name;
			private final byte @NonNull [] nameUtf8;
			@NonNull
			private final McpJsonValue value;

			private EncodedMember(@NonNull String name,
					byte @NonNull [] nameUtf8, @NonNull McpJsonValue value) {
				this.name = requireNonNull(name);
				this.nameUtf8 = requireNonNull(nameUtf8).clone();
				this.value = requireNonNull(value);
			}

			@NonNull
			private String name() {
				return this.name;
			}

			@NonNull
			private McpJsonValue value() {
				return this.value;
			}
		}
}
