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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable exact-revision registry. Its order controls diagnostic rendering
 * only and never implies preference or fallback selection.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpProtocolProfileRegistry {
	@NonNull
	private final List<@NonNull McpProtocolProfile> profiles;
	@NonNull
	private final List<@NonNull String> revisions;
	@NonNull
	private final Map<@NonNull String, @NonNull McpProtocolProfile>
			profilesByRevision;

	McpProtocolProfileRegistry(
			@NonNull List<@NonNull McpProtocolProfile> profiles) {
		requireNonNull(profiles);
		if (profiles.isEmpty())
			throw new IllegalArgumentException(
					"At least one MCP protocol profile is required.");

		Map<String, McpProtocolProfile> profilesByRevision =
				new LinkedHashMap<>();
		for (McpProtocolProfile profile : profiles) {
			requireNonNull(profile);
			String revision = requireNonNull(profile.revision());
			if (revision.isBlank())
				throw new IllegalArgumentException(
						"MCP protocol-profile revisions must not be blank.");
			if (profilesByRevision.putIfAbsent(revision, profile) != null)
				throw new IllegalArgumentException(
						"Duplicate MCP protocol-profile revision '" + revision + "'.");
		}

		this.profiles = List.copyOf(profilesByRevision.values());
		this.revisions = List.copyOf(profilesByRevision.keySet());
		this.profilesByRevision = Collections.unmodifiableMap(profilesByRevision);
	}

	@NonNull
	List<@NonNull McpProtocolProfile> profiles() {
		return this.profiles;
	}

	@NonNull
	List<@NonNull String> revisions() {
		return this.revisions;
	}

	boolean supports(@NonNull String revision) {
		return this.profilesByRevision.containsKey(requireNonNull(revision));
	}

	@NonNull
	Optional<@NonNull McpProtocolProfile> resolve(@NonNull String revision) {
		return Optional.ofNullable(this.profilesByRevision.get(
				requireNonNull(revision)));
	}
}

/** One immutable production holder; no public or runtime-mutable discovery exists. */
@ThreadSafe
final class McpProductionProtocolProfiles {
	@NonNull
	static final McpProtocolProfileRegistry REGISTRY =
			new McpProtocolProfileRegistry(List.of(
					Mcp20260728ProtocolProfile.INSTANCE));

	private McpProductionProtocolProfiles() {
	}
}
