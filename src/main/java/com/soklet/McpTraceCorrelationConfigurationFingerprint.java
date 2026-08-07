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

package com.soklet;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Secret-free deployment-comparison fingerprint for the active MCP
 * trace-correlation configuration.
 * <p>
 * This value is operational metadata only, not an authentication or token
 * derivation input.
 *
 * @param value unpadded Base64URL fingerprint value
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public record McpTraceCorrelationConfigurationFingerprint(
		@NonNull String value) {
	/** Fingerprint encoding version. */
	@NonNull
	public static final String VERSION = "v1";

	/**
	 * Validates the fingerprint value.
	 *
	 * @param value unpadded Base64URL fingerprint value
	 */
	public McpTraceCorrelationConfigurationFingerprint {
		requireNonNull(value);
		if (!isCanonicalSha256Base64Url(value))
			throw new IllegalArgumentException(
					"Trace-correlation fingerprint must be a canonical unpadded Base64URL SHA-256 value.");
	}

	private static boolean isCanonicalSha256Base64Url(@NonNull String value) {
		if (value.length() != 43)
			return false;
		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);
			if (!((character >= 'A' && character <= 'Z')
					|| (character >= 'a' && character <= 'z')
					|| (character >= '0' && character <= '9')
					|| character == '-' || character == '_'))
				return false;
		}
		char finalCharacter = value.charAt(value.length() - 1);
		return "AEIMQUYcgkosw048".indexOf(finalCharacter) >= 0;
	}
}
