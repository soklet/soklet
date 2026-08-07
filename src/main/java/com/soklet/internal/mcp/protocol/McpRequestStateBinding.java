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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable canonical operation binding for protected request state.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpRequestStateBinding {
	private static final int SHA_256_BYTES = 32;
	private static final int MAXIMUM_AUTHORIZATION_PARTITION_BYTES = 256;
	private static final int ANONYMOUS_AUTHORIZATION_KIND = 0;
	private static final int APPLICATION_AUTHORIZATION_KIND = 1;
	private static final byte @NonNull [] PARAMETERS_DOMAIN =
			"soklet-mcp-request-state-params-v1\0"
					.getBytes(StandardCharsets.US_ASCII);
	private static final byte @NonNull [] BINDING_DOMAIN =
			"soklet-mcp-request-state-binding-v1\0"
					.getBytes(StandardCharsets.US_ASCII);
	private static final byte @NonNull [] GCM_AAD_DOMAIN =
			"soklet-mcp-request-state-gcm-aad-v1\0"
					.getBytes(StandardCharsets.US_ASCII);
	private static final Set<@NonNull String> TRANSIENT_PARAMETER_FIELDS =
			Set.of("inputResponses", "requestState");
	private static final Set<@NonNull String> TRANSIENT_METADATA_FIELDS =
			Set.of("progressToken", "traceparent", "tracestate", "baggage");

	private final byte @NonNull [] parametersDigest;
	private final byte @NonNull [] bytes;
	private final byte @NonNull [] digest;

	@NonNull
	static McpRequestStateBinding create(
			@NonNull String endpointPath,
			@NonNull String protocolVersion,
			@NonNull String method,
			@NonNull Optional<@NonNull String> authorizationPartitionKey,
			@NonNull McpJsonObject completeValidatedParameters) {
		requireNonNull(endpointPath);
		requireNonNull(protocolVersion);
		requireNonNull(method);
		requireNonNull(authorizationPartitionKey);
		requireNonNull(completeValidatedParameters);

		byte[] endpointUtf8 = requiredBindingText(endpointPath, "Endpoint path");
		byte[] protocolUtf8 = requiredBindingText(protocolVersion, "Protocol version");
		byte[] methodUtf8 = requiredBindingText(method, "JSON-RPC method");
		byte[] authorizationUtf8 = authorizationPartitionKey
				.map(value -> authorizationPartitionUtf8(value))
				.orElseGet(() -> new byte[0]);
		int authorizationKind = authorizationPartitionKey.isPresent()
				? APPLICATION_AUTHORIZATION_KIND : ANONYMOUS_AUTHORIZATION_KIND;

		McpJsonObject filteredParameters =
				filterParameters(completeValidatedParameters);
		byte[] canonicalParameters = McpRequestStateCanonicalJson.canonicalize(
				filteredParameters,
				McpJsonLimits.productionDefaults().maximumOutputBytes());
		byte[] parametersDigest = digest(framed(
				PARAMETERS_DOMAIN, canonicalParameters));

		ByteArrayOutputStream binding = outputStreamFor(
				(long) BINDING_DOMAIN.length
						+ Integer.BYTES + endpointUtf8.length
						+ Integer.BYTES + protocolUtf8.length
						+ Integer.BYTES + methodUtf8.length
						+ 1L + Integer.BYTES + authorizationUtf8.length
						+ SHA_256_BYTES,
				"Request-state binding");
		binding.writeBytes(BINDING_DOMAIN);
		writeFramed(binding, endpointUtf8);
		writeFramed(binding, protocolUtf8);
		writeFramed(binding, methodUtf8);
		binding.write(authorizationKind);
		writeFramed(binding, authorizationUtf8);
		binding.writeBytes(parametersDigest);
		return new McpRequestStateBinding(parametersDigest, binding.toByteArray());
	}

	private McpRequestStateBinding(byte @NonNull [] parametersDigest,
			byte @NonNull [] bytes) {
		this.parametersDigest = requireDigest(parametersDigest).clone();
		this.bytes = requireNonNull(bytes).clone();
		this.digest = digest(this.bytes);
	}

	byte @NonNull [] parametersDigest() {
		return parametersDigest.clone();
	}

	byte @NonNull [] bytes() {
		return bytes.clone();
	}

	byte @NonNull [] digest() {
		return digest.clone();
	}

	static byte @NonNull [] builtInAssociatedData(
			byte @NonNull [] header, byte @NonNull [] binding) {
		requireNonNull(header);
		requireNonNull(binding);
		ByteArrayOutputStream associatedData = outputStreamFor(
				(long) GCM_AAD_DOMAIN.length + Integer.BYTES + header.length
						+ Integer.BYTES + binding.length,
				"Built-in request-state associated data");
		associatedData.writeBytes(GCM_AAD_DOMAIN);
		writeFramed(associatedData, header);
		writeFramed(associatedData, binding);
		return associatedData.toByteArray();
	}

	@NonNull
	static McpJsonObject filterParameters(
			@NonNull McpJsonObject completeValidatedParameters) {
		requireNonNull(completeValidatedParameters);
		Map<String, McpJsonValue> filtered = new LinkedHashMap<>();
		for (Map.Entry<String, McpJsonValue> entry
				: completeValidatedParameters.members().entrySet()) {
			if (TRANSIENT_PARAMETER_FIELDS.contains(entry.getKey()))
				continue;
			if ("_meta".equals(entry.getKey())) {
				if (!(entry.getValue() instanceof McpJsonObject metadata))
					throw new IllegalArgumentException(
							"Validated request metadata must be an object.");
				filtered.put(entry.getKey(), filterMetadata(metadata));
			} else {
				filtered.put(entry.getKey(), entry.getValue());
			}
		}
		return new McpJsonObject(filtered);
	}

	@NonNull
	private static McpJsonObject filterMetadata(@NonNull McpJsonObject metadata) {
		Map<String, McpJsonValue> filtered = new LinkedHashMap<>();
		for (Map.Entry<String, McpJsonValue> entry : metadata.members().entrySet())
			if (!TRANSIENT_METADATA_FIELDS.contains(entry.getKey()))
				filtered.put(entry.getKey(), entry.getValue());
		return new McpJsonObject(filtered);
	}

	private static byte @NonNull [] requiredBindingText(
			@NonNull String value, @NonNull String description) {
		if (value.isEmpty())
			throw new IllegalArgumentException(description + " must not be empty.");
		return McpRequestStateCanonicalJson.strictUtf8(
				value, Integer.MAX_VALUE, description);
	}

	private static byte @NonNull [] authorizationPartitionUtf8(
			@NonNull String value) {
		if (value.isEmpty())
			throw new IllegalArgumentException(
					"Authorization partition key must not be empty.");
		return McpRequestStateCanonicalJson.strictUtf8(value,
				MAXIMUM_AUTHORIZATION_PARTITION_BYTES,
				"Authorization partition key");
	}

	private static byte @NonNull [] framed(
			byte @NonNull [] domain, byte @NonNull [] value) {
		ByteArrayOutputStream framed = outputStreamFor(
				(long) domain.length + Integer.BYTES + value.length,
				"Request-state digest input");
		framed.writeBytes(domain);
		writeFramed(framed, value);
		return framed.toByteArray();
	}

	private static void writeFramed(@NonNull ByteArrayOutputStream output,
			byte @NonNull [] value) {
		requireNonNull(output);
		requireNonNull(value);
		writeU32(output, value.length);
		output.writeBytes(value);
	}

	private static void writeU32(@NonNull ByteArrayOutputStream output, int value) {
		output.write((value >>> 24) & 0xFF);
		output.write((value >>> 16) & 0xFF);
		output.write((value >>> 8) & 0xFF);
		output.write(value & 0xFF);
	}

	@NonNull
	private static ByteArrayOutputStream outputStreamFor(
			long size, @NonNull String description) {
		if (size > Integer.MAX_VALUE)
			throw new IllegalArgumentException(
					description + " exceeds the implementation size limit.");
		return new ByteArrayOutputStream((int) size);
	}

	private static byte @NonNull [] digest(byte @NonNull [] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static byte @NonNull [] requireDigest(byte @NonNull [] digest) {
		requireNonNull(digest);
		if (digest.length != SHA_256_BYTES)
			throw new IllegalArgumentException(
					"Request-state digest must contain 32 bytes.");
		return digest;
	}
}
