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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable MCP result indicating that an operation needs additional client
 * input before it can complete.
 *
 * <p>At least one input request or request-state value is required. Input
 * requests retain their insertion order and their keys are unique within the
 * result. Input-required results intentionally expose no cache controls.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpInputRequiredResult implements McpOperationResult {
	@NonNull
	private final Map<@NonNull String, @NonNull McpInputRequest> inputRequests;
	@Nullable
	private final McpRequestState requestState;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Vends an empty mutable builder.
	 *
	 * @return input-required-result builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	private McpInputRequiredResult(@NonNull Builder builder) {
		if (builder.inputRequests.isEmpty() && builder.requestState == null)
			throw new IllegalStateException(
					"An input-required result needs an input request, request state, or both.");
		this.inputRequests = Collections.unmodifiableMap(
				new LinkedHashMap<>(builder.inputRequests));
		this.requestState = builder.requestState;
		this.metadata = builder.metadata;
	}

	/**
	 * Returns the input requests in insertion order.
	 *
	 * @return immutable input-request map
	 */
	@NonNull
	public Map<@NonNull String, @NonNull McpInputRequest> getInputRequests() {
		return this.inputRequests;
	}

	/**
	 * Returns the state that the client must echo when retrying the operation.
	 *
	 * @return request state, or empty when no state is needed
	 */
	@NonNull
	public Optional<@NonNull McpRequestState> getRequestState() {
		return Optional.ofNullable(this.requestState);
	}

	/**
	 * Returns application-supplied protocol extension metadata.
	 *
	 * @return immutable result metadata
	 */
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/**
	 * Mutable builder for immutable {@link McpInputRequiredResult} values.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final Map<@NonNull String, @NonNull McpInputRequest>
				inputRequests = new LinkedHashMap<>();
		@Nullable
		private McpRequestState requestState;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private Builder() {
		}

		/**
		 * Appends one uniquely keyed input request.
		 *
		 * <p>Keys are exact server-assigned JSON object member names; empty and
		 * whitespace-only keys remain valid MCP values. Reusing a key fails
		 * without replacing the original request.
		 *
		 * @param key input-response correlation key
		 * @param request server-initiated request
		 * @return this builder
		 * @throws NullPointerException if an argument is null
		 * @throws IllegalArgumentException if the key is already present
		 */
		@NonNull
		public Builder inputRequest(@NonNull String key,
				@NonNull McpInputRequest request) {
			requireNonNull(key);
			requireNonNull(request);
			if (this.inputRequests.containsKey(key))
				throw new IllegalArgumentException(
						"Input-request keys must be unique within a result.");
			this.inputRequests.put(key, request);
			return this;
		}

		/**
		 * Supplies application JSON for Soklet to protect as opaque request
		 * state on the wire.
		 *
		 * <p>This replaces any request state supplied by an earlier builder
		 * call.
		 *
		 * @param state application-defined JSON state
		 * @return this builder
		 * @throws NullPointerException if {@code state} is null
		 */
		@NonNull
		public Builder frameworkRequestState(@NonNull McpJsonValue state) {
			this.requestState = new McpFrameworkRequestState(
					requireNonNull(state));
			return this;
		}

		/**
		 * Supplies opaque request state protected by the application.
		 *
		 * <p>This replaces any request state supplied by an earlier builder
		 * call.
		 *
		 * @param state nonempty opaque application-protected state
		 * @return this builder
		 * @throws NullPointerException if {@code state} is null
		 * @throws IllegalArgumentException if {@code state} is empty
		 */
		@NonNull
		public Builder applicationRequestState(@NonNull String state) {
			this.requestState = new McpApplicationRequestState(
					requireNonNull(state));
			return this;
		}

		/**
		 * Supplies protocol extension metadata.
		 *
		 * <p>This replaces metadata supplied by an earlier builder call.
		 *
		 * @param metadata immutable result metadata
		 * @return this builder
		 * @throws NullPointerException if {@code metadata} is null
		 */
		@NonNull
		public Builder metadata(@NonNull McpJsonObject metadata) {
			this.metadata = requireNonNull(metadata);
			return this;
		}

		/**
		 * Builds an immutable input-required result.
		 *
		 * @return immutable input-required result
		 * @throws IllegalStateException if neither an input request nor request
		 * state is present
		 */
		@NonNull
		public McpInputRequiredResult build() {
			return new McpInputRequiredResult(this);
		}
	}
}
