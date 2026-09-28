/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet;

import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * An exact MCP core protocol revision.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpProtocolVersion {
	/** MCP 2025-03-26. */
	V2025_03_26("2025-03-26"),
	/** MCP 2025-06-18. */
	V2025_06_18("2025-06-18"),
	/** MCP 2025-11-25. */
	V2025_11_25("2025-11-25"),
	/** MCP 2026-07-28. */
	V2026_07_28("2026-07-28");

	@NonNull
	private final String wireValue;

	McpProtocolVersion(@NonNull String wireValue) {
		this.wireValue = wireValue;
	}

	/** @return the exact MCP protocol revision sent on the wire */
	@NonNull
	public String getWireValue() {
		return this.wireValue;
	}

	/**
	 * Finds a known exact revision without selecting a fallback.
	 *
	 * @param wireValue wire revision
	 * @return the corresponding revision, or empty for an unknown value
	 */
	@NonNull
	public static Optional<@NonNull McpProtocolVersion> fromWireValue(
			@NonNull String wireValue) {
		requireNonNull(wireValue);
		for (McpProtocolVersion version : values()) {
			if (version.wireValue.equals(wireValue))
				return Optional.of(version);
		}
		return Optional.empty();
	}

	@NonNull
	static Set<@NonNull McpProtocolVersion> requiredSet(
			@NonNull Set<@NonNull McpProtocolVersion> protocolVersions) {
		Set<McpProtocolVersion> copy = optionalSet(protocolVersions);
		if (copy.isEmpty())
			throw new IllegalArgumentException(
					"At least one MCP protocol version is required.");
		return copy;
	}

	@NonNull
	static Set<@NonNull McpProtocolVersion> optionalSet(
			@NonNull Set<@NonNull McpProtocolVersion> protocolVersions) {
		requireNonNull(protocolVersions);
		EnumSet<McpProtocolVersion> selected = EnumSet.noneOf(McpProtocolVersion.class);
		for (McpProtocolVersion version : protocolVersions)
			selected.add(requireNonNull(version));
		return Collections.unmodifiableSet(new LinkedHashSet<>(selected));
	}
}
