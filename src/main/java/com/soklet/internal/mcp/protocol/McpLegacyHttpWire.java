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
 * Classifies the two HTTP wire eras before semantic request mapping. A 2025
 * version header cannot turn arbitrary modern framing into a legacy request;
 * the few observed hybrid mirrors must match their JSON-RPC fields exactly.
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
			boolean argumentMirrorsPresent,
			@NonNull McpMirroredHeaderCodec mirroredHeaderCodec) {
		requireNonNull(envelope);
		requireNonNull(versionHeaders);
		requireNonNull(methodHeaders);
		requireNonNull(nameHeaders);
		requireNonNull(mirroredHeaderCodec);

		if (versionHeaders.size() > 1)
			return Era.MODERN;
		String headerVersion = versionHeaders.isEmpty() ? null : versionHeaders.get(0);
		boolean legacyHeader = headerVersion != null && isLegacyRevision(headerVersion);
		boolean modernHeader = McpProtocolVersion.CURRENT.equals(headerVersion);
		boolean mirroredHeaders = !methodHeaders.isEmpty() || !nameHeaders.isEmpty()
				|| argumentMirrorsPresent;
		boolean modernMetadata = modernMetadata(envelope);
		boolean initialization = isMethod(envelope, "initialize");
		// Some hosts mirror only the initialize method while negotiating a 2025
		// revision. Admit that one legacy bootstrap shape when the body explicitly
		// requests a supported 2025 revision. Any version selector, additional
		// mirror, or modern metadata still takes the strict modern path below.
		if (initialization && versionHeaders.isEmpty()
				&& methodHeaders.size() == 1
				&& "initialize".equals(methodHeaders.get(0))
				&& nameHeaders.isEmpty() && !argumentMirrorsPresent
				&& !modernMetadata && legacyInitializationVersion(envelope))
			return Era.LEGACY;
		// A 2025 client may keep mirroring its method and operation name after
		// initialize. This is legacy only when its explicit version selector is
		// a supported 2025 revision and every mirror agrees with the JSON body.
		// The initialize exception above stays separate: an initialize carrying
		// both a version selector and a mirror remains mixed-era framing.
		if (legacyHeader && !initialization && !modernMetadata
				&& !argumentMirrorsPresent
				&& matchingMethodMirror(envelope, methodHeaders,
						mirroredHeaderCodec)
				&& matchingOperationNameMirror(envelope, nameHeaders,
						mirroredHeaderCodec))
			return Era.LEGACY;

		// Unrecognized mirroring takes precedence over the selector. A legacy-valued
		// header on any other modern-framed request must reach the modern
		// mismatch/unsupported-version checks, not the 2025 mapper.
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

	private static boolean legacyInitializationVersion(
			@NonNull McpJsonRpcEnvelope envelope) {
		return envelope instanceof McpJsonRpcEnvelope.Request request
				&& request.params().orElse(null) instanceof McpJsonObject params
				&& params.members().get("protocolVersion") instanceof McpJsonString version
				&& isLegacyRevision(version.value());
	}

	private static boolean matchingMethodMirror(
			@NonNull McpJsonRpcEnvelope envelope,
			@NonNull List<@NonNull String> methodHeaders,
			@NonNull McpMirroredHeaderCodec mirroredHeaderCodec) {
		if (methodHeaders.size() != 1)
			return false;
		String method = methodHeaders.get(0);
		try {
			mirroredHeaderCodec.requirePlainString(method);
		} catch (IllegalArgumentException exception) {
			return false;
		}
		return (envelope instanceof McpJsonRpcEnvelope.Request request
				&& method.equals(request.method()))
				|| (envelope instanceof McpJsonRpcEnvelope.Notification notification
						&& method.equals(notification.method()));
	}

	private static boolean matchingOperationNameMirror(
			@NonNull McpJsonRpcEnvelope envelope,
			@NonNull List<@NonNull String> nameHeaders,
			@NonNull McpMirroredHeaderCodec mirroredHeaderCodec) {
		if (nameHeaders.isEmpty())
			return true;
		if (nameHeaders.size() != 1
				|| !(envelope instanceof McpJsonRpcEnvelope.Request request)
				|| !("tools/call".equals(request.method())
						|| "prompts/get".equals(request.method())
						|| "resources/read".equals(request.method()))
				|| !(request.params().orElse(null) instanceof McpJsonObject params))
			return false;
		String nameField = "resources/read".equals(request.method()) ? "uri" : "name";
		if (!(params.members().get(nameField) instanceof McpJsonString name))
			return false;
		try {
			return mirroredHeaderCodec.decodeString(nameHeaders.get(0))
					.equals(name.value());
		} catch (IllegalArgumentException exception) {
			return false;
		}
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
