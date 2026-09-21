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

import com.soklet.internal.mcp.skills.McpSkillRuntimeBridge;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable snapshot of application-supplied Skills files and document metadata.
 *
 * <p>Logical file paths are bundle-relative names, not filesystem locations.
 * Construction performs no file access, remote fetching, execution, or endpoint
 * publication. Applications own loading the bytes before supplying them here.
 * Metadata and path getters return cached immutable views; only file-byte
 * inspection creates defensive copies.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSkillBundle {
	private final McpSkillRuntimeBridge.@NonNull Bundle runtimeBundle;

	private McpSkillBundle(McpSkillRuntimeBridge.@NonNull Bundle runtimeBundle) {
		this.runtimeBundle = runtimeBundle;
	}

	/**
	 * Validates and snapshots a bundle, including its required root {@code SKILL.md}.
	 *
	 * <p>Keys must be unique NFC logical paths with no empty, dot, or dot-dot
	 * segments, backslashes, percent signs, or control characters. Each path is
	 * limited to 8,192 UTF-8 bytes. Bundles contain at most 512 files and 16 MiB of
	 * raw content; individual resource representations remain subject to the
	 * stricter MCP output limits. Supplied arrays are defensively copied. Do not
	 * mutate the map or its arrays while construction is in progress.
	 *
	 * <p>The complete root document, including its body, must be valid UTF-8 and
	 * fit within 4 MiB. Frontmatter parsing and resolution share fixed bounds:
	 * nesting depth 128; 1,000,000 syntax and resolved nodes; 1,048,576 UTF-16 code
	 * units per scalar; 4,194,304 aggregate charged scalar characters; and
	 * 50,000,000 metered work units. The production JSON profile independently
	 * constrains resolved metadata and resource delivery. These construction
	 * bounds are not configurable through this factory.
	 *
	 * @param files logical file paths and complete file contents
	 * @return immutable validated bundle
	 * @throws NullPointerException if the map, a path, or file bytes are null
	 * @throws IllegalArgumentException if paths, metadata, content, or limits are invalid
	 */
	@NonNull
	public static McpSkillBundle fromFiles(
			@NonNull Map<@NonNull String, byte @NonNull []> files) {
		return new McpSkillBundle(McpSkillRuntimeBridge.fromFiles(files));
	}

	/** @return validated skill name from the root document */
	@NonNull
	public String getName() { return this.runtimeBundle.name(); }

	/** @return skill description from the root document */
	@NonNull
	public String getDescription() { return this.runtimeBundle.description(); }

	/**
	 * Returns cached root frontmatter, including supported unknown fields.
	 *
	 * @return immutable complete document metadata, not protocol {@code _meta}
	 */
	@NonNull
	public McpJsonObject getDocumentMetadata() { return this.runtimeBundle.documentMetadata(); }

	/**
	 * Returns cached canonical logical paths without filesystem resolution.
	 *
	 * @return immutable ordered set, root {@code SKILL.md} first and remaining
	 * paths in unsigned UTF-8 byte order
	 */
	@NonNull
	public Set<@NonNull String> getFilePaths() { return this.runtimeBundle.filePaths(); }

	/**
	 * Copies the requested file without copying the complete bundle.
	 *
	 * @param filePath exact, case-sensitive logical file path
	 * @return defensive file-byte copy, or empty when the path is absent
	 * @throws NullPointerException if the path is null
	 */
	@NonNull
	public Optional<byte @NonNull []> findFileBytes(@NonNull String filePath) {
		return this.runtimeBundle.findFileBytes(filePath);
	}

	McpSkillRuntimeBridge.@NonNull Bundle runtimeBundle() { return this.runtimeBundle; }

	/** @return whether logical paths and exact snapshot bytes match */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || other instanceof McpSkillBundle bundle
				&& this.runtimeBundle.equals(bundle.runtimeBundle);
	}

	/** @return structural bundle hash code without copying inspection bytes */
	@Override
	public int hashCode() { return this.runtimeBundle.hashCode(); }

	/** @return diagnostic rendering without authored metadata, paths, or bytes */
	@Override
	@NonNull
	public String toString() { return "McpSkillBundle[redacted]"; }
}
