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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Classifies the two HTTP wire eras before semantic request mapping. In
 * particular, a 2025 version header cannot turn a modern-framed request into a
 * legacy request, or vice versa.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLegacyHttpWire {
	enum Era {
		MODERN, LEGACY
	}

	private McpLegacyHttpWire() {
	}

	static Era classify(@NonNull McpJsonRpcEnvelope envelope,
			@NonNull List<@NonNull String> versionHeaders,
			@NonNull List<@NonNull String> methodHeaders,
			@NonNull List<@NonNull String> nameHeaders,
			boolean argumentMirrorsPresent) {
		requireNonNull(envelope);
		requireNonNull(versionHeaders);
		requireNonNull(methodHeaders);
		requireNonNull(nameHeaders);

		if (versionHeaders.size() > 1)
			return Era.MODERN;
		String headerVersion = versionHeaders.isEmpty() ? null : versionHeaders.get(0);
		boolean legacyHeader = headerVersion != null && isLegacyRevision(headerVersion);
		boolean modernHeader = McpProtocolVersion.CURRENT.equals(headerVersion);
		boolean mirroredHeaders = !methodHeaders.isEmpty() || !nameHeaders.isEmpty()
				|| argumentMirrorsPresent;
		boolean modernMetadata = modernMetadata(envelope);
		boolean initialization = isMethod(envelope, "initialize");

		// Framing takes precedence over the selector. A legacy-valued header on a
		// modern-framed request must reach the modern mismatch/unsupported-version
		// checks; it must never cause a 2025 mapper to accept that request.
		if (mirroredHeaders || modernMetadata || modernHeader)
			return Era.MODERN;
		if (legacyHeader || initialization)
			return Era.LEGACY;
		// An unframed or malformed message has no affirmative legacy signal.
		// Preserve the modern HTTP error mapping for that case.
		return Era.MODERN;
	}

	static boolean isLegacyRevision(@NonNull String revision) {
		return "2025-06-18".equals(requireNonNull(revision))
				|| "2025-11-25".equals(revision);
	}

	private static boolean isMethod(@NonNull McpJsonRpcEnvelope envelope,
			@NonNull String method) {
		return envelope instanceof McpJsonRpcEnvelope.Request request
				&& method.equals(request.method());
	}

	private static boolean modernMetadata(@NonNull McpJsonRpcEnvelope envelope) {
		Optional<McpJsonValue> rawParams = envelope instanceof McpJsonRpcEnvelope.Request request
				? request.params()
				: envelope instanceof McpJsonRpcEnvelope.Notification notification
					? notification.params() : Optional.empty();
		if (rawParams.isEmpty() || !(rawParams.orElseThrow() instanceof McpJsonObject params))
			return false;
		McpJsonValue metadata = params.members().get("_meta");
		if (!(metadata instanceof McpJsonObject object))
			return false;
		Map<String, McpJsonValue> fields = object.members();
		return fields.containsKey(McpRequestMetadata.PROTOCOL_VERSION_KEY)
				|| fields.containsKey(McpRequestMetadata.CLIENT_CAPABILITIES_KEY);
	}
}
