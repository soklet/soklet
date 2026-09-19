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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.soklet.internal.mcp.protocol.McpApplicationMetadata.requireApplicationMetadata;
import static java.util.Objects.requireNonNull;

/**
 * Immutable ordered argument suggestions returned by an MCP completer.
 *
 * <p>Values remain exact application-provided strings. Soklet does not sort,
 * normalize, deduplicate, translate, or perform prefix matching on them.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpArgumentCompletionResult implements McpOperationResult {
	private static final int MAX_VALUES = 100;
	private static final long MAX_SAFE_TOTAL = 9_007_199_254_740_991L;

	@NonNull
	private final List<@NonNull String> values;
	@Nullable
	private final Long total;
	@Nullable
	private final Boolean hasMore;
	@NonNull
	private final McpJsonObject metadata;

	/**
	 * Creates a result containing only ordered suggestions.
	 *
	 * @param values zero to 100 exact, non-null suggestion strings
	 * @return immutable result
	 */
	@NonNull
	public static McpArgumentCompletionResult fromValues(
			@NonNull List<@NonNull String> values) {
		return withValues(values).build();
	}

	/**
	 * Begins a result with ordered suggestions and optional fields.
	 *
	 * @param values zero to 100 exact, non-null suggestion strings
	 * @return mutable builder
	 */
	@NonNull
	public static Builder withValues(@NonNull List<@NonNull String> values) {
		return new Builder(values);
	}

	private McpArgumentCompletionResult(@NonNull Builder builder) {
		this.values = builder.values;
		this.total = builder.total;
		this.hasMore = builder.hasMore;
		this.metadata = builder.metadata;
	}

	/** @return immutable suggestions in application order */
	@NonNull
	public List<@NonNull String> getValues() {
		return this.values;
	}

	/** @return exact match count, or empty when unknown or omitted */
	@NonNull
	public Optional<@NonNull Long> getTotal() {
		return Optional.ofNullable(this.total);
	}

	/** @return whether additional values exist, or empty when omitted */
	@NonNull
	public Optional<@NonNull Boolean> getHasMore() {
		return Optional.ofNullable(this.hasMore);
	}

	/** @return result-level protocol extension metadata */
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/** @return whether values, optional fields, and metadata match */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpArgumentCompletionResult result))
			return false;
		return this.values.equals(result.values)
				&& Objects.equals(this.total, result.total)
				&& Objects.equals(this.hasMore, result.hasMore)
				&& this.metadata.equals(result.metadata);
	}

	/** @return structural hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.values, this.total, this.hasMore,
				this.metadata);
	}

	/** @return diagnostic rendering without suggestions or metadata */
	@Override
	@NonNull
	public String toString() {
		return "McpArgumentCompletionResult{values=<redacted>, total=<redacted>, "
				+ "hasMore=<redacted>, metadata=<redacted>}";
	}

	/**
	 * Mutable builder for an immutable argument-completion result.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final List<@NonNull String> values;
		@Nullable
		private Long total;
		@Nullable
		private Boolean hasMore;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private Builder(@NonNull List<@NonNull String> values) {
			this.values = List.copyOf(requireNonNull(values));
			if (this.values.size() > MAX_VALUES)
				throw new IllegalArgumentException(
						"MCP completion results must not exceed 100 values.");
		}

		/**
		 * Sets the exact count of matching values.
		 *
		 * @param total count in the JavaScript-safe integer range
		 * @return this builder
		 */
		@NonNull
		public Builder total(@NonNull Long total) {
			this.total = requireNonNull(total);
			return this;
		}

		/**
		 * Sets whether additional values exist.
		 *
		 * @param hasMore true when results were truncated
		 * @return this builder
		 */
		@NonNull
		public Builder hasMore(@NonNull Boolean hasMore) {
			this.hasMore = requireNonNull(hasMore);
			return this;
		}

		/**
		 * Sets result-level protocol extension metadata.
		 *
		 * @param metadata application metadata without MCP-reserved prefixes
		 * @return this builder
		 */
		@NonNull
		public Builder metadata(@NonNull McpJsonObject metadata) {
			this.metadata = requireApplicationMetadata(metadata);
			return this;
		}

		/**
		 * Validates cross-field consistency and builds an immutable result.
		 *
		 * @return immutable result
		 */
		@NonNull
		public McpArgumentCompletionResult build() {
			if (this.total != null) {
				if (this.total < this.values.size()
						|| this.total > MAX_SAFE_TOTAL)
					throw new IllegalArgumentException(
							"MCP completion total is out of range.");
				if (this.hasMore != null
						&& this.hasMore != (this.total > this.values.size()))
					throw new IllegalArgumentException(
							"MCP completion total and hasMore disagree.");
			}
			return new McpArgumentCompletionResult(this);
		}
	}
}
