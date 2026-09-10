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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Open wire result. Core factories enforce the invariants of the result type
 * they emit; the extension factory supports explicitly negotiated protocol
 * extensions such as Tasks while keeping extension advertisement and
 * per-request capability enforcement at the owning runtime boundary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpWireResult {
	@NonNull
	private static final Set<@NonNull String> INPUT_REQUIRED_CLIENT_METHODS =
			Set.of("tools/call", "prompts/get", "resources/read");

	@NonNull
	private final McpResultType resultType;
	@NonNull
	private final McpJsonObject fields;
	@NonNull
	private final Optional<@NonNull McpResultMetadata> metadata;
	@Nullable
	private final McpJsonObject precomputedJsonObject;
	@Nullable
	private final McpJsonObject compatibilityMirrorFallbackFields;

	private McpWireResult(@NonNull McpResultType resultType,
			@NonNull McpJsonObject fields,
			@NonNull Optional<@NonNull McpResultMetadata> metadata) {
		this(resultType, fields, metadata, null, null);
	}

	private McpWireResult(@NonNull McpResultType resultType,
			@NonNull McpJsonObject fields,
			@NonNull Optional<@NonNull McpResultMetadata> metadata,
			@Nullable McpJsonObject precomputedJsonObject) {
		this(resultType, fields, metadata, precomputedJsonObject, null);
	}

	private McpWireResult(@NonNull McpResultType resultType,
			@NonNull McpJsonObject fields,
			@NonNull Optional<@NonNull McpResultMetadata> metadata,
			@Nullable McpJsonObject precomputedJsonObject,
			@Nullable McpJsonObject compatibilityMirrorFallbackFields) {
		this.resultType = requireNonNull(resultType);
		this.fields = McpProtocolSupport.requireExtensionFields(
				fields, Set.of("resultType", "_meta"));
		this.metadata = requireNonNull(metadata);
		this.precomputedJsonObject = precomputedJsonObject;
		this.compatibilityMirrorFallbackFields =
				compatibilityMirrorFallbackFields == null ? null
						: McpProtocolSupport.requireExtensionFields(
								compatibilityMirrorFallbackFields,
								Set.of("resultType", "_meta"));
	}

	/**
	 * Returns a result that publishes {@code precomputedJsonObject} verbatim.
	 * <p>
	 * Localized rendering replaces framework-owned strings in the fully composed
	 * result document, so the composed form is carried directly rather than
	 * recomposed from parts. Envelope composition is unchanged, which is what
	 * keeps the non-localized path byte-identical.
	 */
	@NonNull
	static McpWireResult withPrecomputedJsonObject(@NonNull McpWireResult source,
			@NonNull McpJsonObject precomputedJsonObject) {
		requireNonNull(source);
		requireNonNull(precomputedJsonObject);
		return new McpWireResult(source.resultType, source.fields, source.metadata,
				precomputedJsonObject, source.compatibilityMirrorFallbackFields);
	}

	/**
	 * Returns {@code source} with the framework-owned server identity merged into
	 * its result metadata. Application metadata is retained verbatim; the
	 * reserved-key validation in {@link McpResultMetadata} prevents it from
	 * impersonating the framework-owned identity.
	 */
	@NonNull
	static McpWireResult withServerInformation(@NonNull McpWireResult source,
			@NonNull Optional<@NonNull McpImplementationMetadata>
					serverInformation) {
		McpWireResult requiredSource = requireNonNull(source);
		Optional<McpImplementationMetadata> requiredServerInformation =
				requireNonNull(serverInformation);
		McpJsonObject extensionFields = requiredSource.metadata
				.map(McpResultMetadata::extensionFields)
				.orElseGet(McpJsonObject::empty);
		McpResultMetadata merged = new McpResultMetadata(
				requiredServerInformation, extensionFields);
		Optional<McpResultMetadata> optionalMerged = merged.isEmpty()
				? Optional.empty() : Optional.of(merged);
		return new McpWireResult(requiredSource.resultType, requiredSource.fields,
				optionalMerged, requiredSource.precomputedJsonObject,
				requiredSource.compatibilityMirrorFallbackFields);
	}

	@NonNull
	static McpWireResult complete(@NonNull McpJsonObject fields) {
		return complete(fields, Optional.empty());
	}

	@NonNull
	static McpWireResult complete(@NonNull McpJsonObject fields,
			@NonNull Optional<@NonNull McpResultMetadata> metadata) {
		return new McpWireResult(McpResultType.COMPLETE, fields, metadata);
	}

	/**
	 * Returns a complete result whose optional structured-content text mirror may
	 * be discarded if the fully composed response exceeds a wire limit.
	 */
	@NonNull
	static McpWireResult completeWithCompatibilityMirror(
			@NonNull McpJsonObject mirroredFields,
			@NonNull McpJsonObject unmirroredFields,
			@NonNull Optional<@NonNull McpResultMetadata> metadata) {
		return new McpWireResult(McpResultType.COMPLETE, mirroredFields, metadata,
				null, unmirroredFields);
	}

	@NonNull
	static McpWireResult inputRequired(@NonNull String clientRequestMethod,
			@NonNull Optional<@NonNull McpInputRequests> inputRequests,
			@NonNull Optional<@NonNull String> requestState,
			@NonNull Optional<@NonNull McpResultMetadata> metadata,
			@NonNull McpJsonObject extensionFields) {
		requireNonNull(clientRequestMethod);
		requireNonNull(inputRequests);
		requireNonNull(requestState);
		requireNonNull(metadata);

		if (!supportsInputRequired(clientRequestMethod))
			throw new IllegalArgumentException(
					"Input-required results are not permitted for " + clientRequestMethod + ".");

		if (inputRequests.isEmpty() && requestState.isEmpty())
			throw new IllegalArgumentException(
					"Input-required results need inputRequests, requestState, or both.");

		McpJsonObject fields = McpProtocolSupport.requireExtensionFields(
				extensionFields, Set.of("inputRequests", "requestState"));
		Map<String, McpJsonValue> values = new LinkedHashMap<>(fields.members());
		inputRequests.ifPresent(value -> values.put("inputRequests", value.toJsonObject()));
		requestState.ifPresent(value -> values.put("requestState", new McpJsonString(value)));
		return new McpWireResult(McpResultType.INPUT_REQUIRED,
				new McpJsonObject(values), metadata);
	}

	@NonNull
	static McpWireResult extension(@NonNull McpResultType resultType,
			@NonNull McpJsonObject fields,
			@NonNull Optional<@NonNull McpResultMetadata> metadata) {
		requireNonNull(resultType);

		if (resultType.isCore())
			throw new IllegalArgumentException(
					"Use a core result factory for result type '" + resultType.wireValue() + "'.");

		return new McpWireResult(resultType, fields, metadata);
	}

	static boolean supportsInputRequired(@NonNull String clientRequestMethod) {
		return INPUT_REQUIRED_CLIENT_METHODS.contains(requireNonNull(clientRequestMethod));
	}

	@NonNull
	McpResultType resultType() {
		return resultType;
	}

	@NonNull
	McpJsonObject fields() {
		return fields;
	}

	/** Returns an equivalent result without its optional compatibility mirror. */
	@NonNull
	Optional<@NonNull McpWireResult> withoutCompatibilityMirror() {
		if (compatibilityMirrorFallbackFields == null)
			return Optional.empty();
		return Optional.of(new McpWireResult(resultType,
				compatibilityMirrorFallbackFields, metadata));
	}

	@NonNull
	Optional<@NonNull McpResultMetadata> metadata() {
		return metadata;
	}

	@NonNull
	McpJsonObject toJsonObject() {
		if (precomputedJsonObject != null)
			return precomputedJsonObject;

		Map<String, McpJsonValue> values = new LinkedHashMap<>(fields.members());
		values.put("resultType", new McpJsonString(resultType.wireValue()));
		metadata.filter(value -> !value.isEmpty())
				.ifPresent(value -> values.put("_meta", value.toJsonObject()));
		return new McpJsonObject(values);
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpResultType(@NonNull String wireValue) {
	@NonNull
	static final McpResultType COMPLETE = new McpResultType("complete");
	@NonNull
	static final McpResultType INPUT_REQUIRED = new McpResultType("input_required");

	McpResultType {
		wireValue = McpProtocolSupport.requireNonBlank(wireValue, "Result type");
	}

	@NonNull
	static McpResultType extension(@NonNull String wireValue) {
		McpResultType resultType = new McpResultType(wireValue);

		if (resultType.isCore())
			throw new IllegalArgumentException("Core result type is not an extension.");

		return resultType;
	}

	boolean isCore() {
		return COMPLETE.equals(this) || INPUT_REQUIRED.equals(this);
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpEmbeddedInputRequest(@NonNull McpInputRequestDeclaration declaration,
		@NonNull McpJsonObject params, @NonNull McpJsonObject extensionFields) {
	McpEmbeddedInputRequest {
		requireNonNull(declaration);
		requireNonNull(params);
		McpEmbeddedInputRequestValidator.validate(declaration, params);
		extensionFields = McpProtocolSupport.requireExtensionFields(
				extensionFields, Set.of("method", "params"));
	}

	@NonNull
	static McpEmbeddedInputRequest fromDeclaration(
			@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpJsonObject params) {
		return new McpEmbeddedInputRequest(declaration, requireNonNull(params),
				McpJsonObject.empty());
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>(extensionFields.members());
		values.put("method", new McpJsonString(declaration.method()));
		values.put("params", params);
		return new McpJsonObject(values);
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpInputRequests {
	@NonNull
	private final Map<@NonNull String, @NonNull McpEmbeddedInputRequest> requests;

	private McpInputRequests(
			@NonNull Map<@NonNull String, @NonNull McpEmbeddedInputRequest> requests) {
		requireNonNull(requests);
		Map<String, McpEmbeddedInputRequest> copiedRequests =
				new LinkedHashMap<>(requests.size());

		for (Map.Entry<String, McpEmbeddedInputRequest> entry : requests.entrySet())
			copiedRequests.put(requireNonNull(entry.getKey()), requireNonNull(entry.getValue()));

		this.requests = Collections.unmodifiableMap(copiedRequests);
	}

	@NonNull
	static Builder builder() {
		return new Builder();
	}

	@NonNull
	Map<@NonNull String, @NonNull McpEmbeddedInputRequest> requests() {
		return requests;
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>(requests.size());

		for (Map.Entry<String, McpEmbeddedInputRequest> entry : requests.entrySet())
			values.put(entry.getKey(), entry.getValue().toJsonObject());

		return new McpJsonObject(values);
	}

	@NotThreadSafe
	static final class Builder {
		@NonNull
		private final Map<@NonNull String, @NonNull McpEmbeddedInputRequest> requests =
				new LinkedHashMap<>();

		@NonNull
		Builder inputRequest(@NonNull String key,
				@NonNull McpEmbeddedInputRequest inputRequest) {
			requireNonNull(key);
			requireNonNull(inputRequest);

			if (requests.putIfAbsent(key, inputRequest) != null)
				throw new IllegalArgumentException("Duplicate input-request key '" + key + "'.");

			return this;
		}

		@NonNull
		McpInputRequests build() {
			return new McpInputRequests(requests);
		}
	}
}
