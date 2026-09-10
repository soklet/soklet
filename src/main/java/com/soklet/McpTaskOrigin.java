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

import com.soklet.internal.mcp.protocol.McpTaskOriginPersistedStateCodec;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, non-wire origin of one MCP task-augmented operation.
 *
 * <p>Soklet derives this value before invoking a task-capable handler. The
 * persisted state is versioned framework data that lets a later request,
 * potentially handled by another process or deployment generation, recover
 * the original endpoint, operation, validated arguments, and output contract.
 * It is intentionally opaque so later Tasks revisions can add origin data
 * without widening this Java API.
 *
 * <p>An application that returns a task handle must store this complete value
 * atomically with its durable work description. Its configured
 * {@link McpTaskManager} must return a semantically equal origin with every
 * snapshot of that task. The safest persistence boundary is
 * {@link #toPersistedString()} and {@link #fromPersistedString(String)}; the
 * canonical representation tolerates JSON member reordering and equivalent
 * decimal scales. Soklet compares the origin before returning the task handle
 * and uses it to apply the original tool's output protections when the task
 * completes.
 *
 * <p>Origin state may contain tool arguments or other sensitive application
 * data. Store it with the same confidentiality and integrity protections as
 * the task itself, do not log it, and do not expose it to clients. It is not an
 * authorization decision or task-ownership binding; the task manager must
 * independently bind and authorize each task using the admitted request.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTaskOrigin {
	@NonNull
	private final McpJsonObject persistedState;
	@NonNull
	private final String persistedString;

	/**
	 * Reconstructs an origin from framework state previously supplied by Soklet.
	 *
	 * <p>Applications must preserve every member and value. Soklet canonicalizes
	 * object-member order and decimal scale for durable equality, then validates
	 * the supported version and semantics when the origin is used; construction
	 * establishes an immutable copy boundary.
	 *
	 * @param persistedState complete persisted origin state
	 * @return immutable task origin
	 * @throws NullPointerException if {@code persistedState} is null
	 * @throws IllegalArgumentException if the value cannot be represented by the
	 *                                  bounded canonical origin codec
	 */
	@NonNull
	public static McpTaskOrigin fromPersistedState(
			@NonNull McpJsonObject persistedState) {
		return new McpTaskOrigin(persistedState);
	}

	/**
	 * Reconstructs an origin from its bounded durable JSON representation.
	 *
	 * <p>The representation may be stored as an opaque text value. Parsing
	 * accepts equivalent JSON object representations, including insignificant
	 * whitespace, member reordering, and numerically equivalent decimal scales.
	 * {@link #toPersistedString()} always returns Soklet's canonical form.</p>
	 *
	 * @param persistedString persisted origin JSON
	 * @return immutable task origin
	 * @throws NullPointerException if {@code persistedString} is null
	 * @throws IllegalArgumentException if the value is not a bounded JSON object
	 */
	@NonNull
	public static McpTaskOrigin fromPersistedString(
			@NonNull String persistedString) {
		return new McpTaskOrigin(
				McpTaskOriginPersistedStateCodec.decode(
						requireNonNull(persistedString)));
	}

	private McpTaskOrigin(@NonNull McpJsonObject persistedState) {
		this.persistedState = requireNonNull(persistedState);
		this.persistedString = McpTaskOriginPersistedStateCodec.encode(
				persistedState);
	}

	/**
	 * Returns the complete opaque state that must be durably preserved.
	 *
	 * <p>The returned object may contain sensitive operation arguments. It is
	 * not MCP response metadata and must never be sent to a client.
	 *
	 * @return immutable persisted origin state
	 */
	@NonNull
	public McpJsonObject getPersistedState() {
		return this.persistedState;
	}

	/**
	 * Returns the bounded canonical JSON form for durable storage.
	 *
	 * <p>The returned text may contain sensitive operation arguments. Store it
	 * with the same confidentiality and integrity protections as the task, and
	 * do not log or expose it to clients.</p>
	 *
	 * @return canonical persisted origin JSON
	 */
	@NonNull
	public String toPersistedString() {
		return this.persistedString;
	}

	/** @return whether the other origin contains semantically equal JSON state */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpTaskOrigin origin))
			return false;
		return this.persistedString.equals(origin.persistedString);
	}

	/** @return semantic persisted-state hash code */
	@Override
	public int hashCode() {
		return this.persistedString.hashCode();
	}

	/** @return a diagnostic rendering that does not expose origin state */
	@Override
	@NonNull
	public String toString() {
		return "McpTaskOrigin{persistedState=<redacted>}";
	}
}
