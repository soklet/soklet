/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import com.soklet.internal.mcp.protocol.McpSkillResourceCollisionValidator;
import com.soklet.internal.mcp.skills.McpSkillRuntimeBridge;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Endpoint-owned canonical Skills identities, not an authorization decision.
 * Owners are the exact configured registration instances in aggregate order.
 * Construction compares owned bytes in place; no file-copying inspection getter
 * or additional byte snapshot is needed. No filesystem or callback is consulted.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpSkillEndpointIndex {
	private static final int MAXIMUM_FILE_OWNERS = 16;
	private final List<McpSkillRegistration> registrations;
	private final Map<URI, McpSkillRegistration> registrationsByUri;
	private final Map<URI, File> files;

	private McpSkillEndpointIndex(List<McpSkillRegistration> registrations, Map<URI, File> files) {
		this.registrations = List.copyOf(registrations);
		Map<URI, McpSkillRegistration> byUri = new LinkedHashMap<>();
		for (McpSkillRegistration registration : registrations) byUri.put(registration.getUri(), registration);
		this.registrationsByUri = Collections.unmodifiableMap(byUri);
		this.files = Collections.unmodifiableMap(files);
	}

	static McpSkillEndpointIndex from(List<McpSkillRegistration> standalone, List<McpSkillGroup> groups,
			List<McpResourceRegistration> resources) {
		List<McpSkillRegistration> aggregate = new ArrayList<>();
		Set<String> names = new HashSet<>();
		Set<String> keys = new HashSet<>();
		Set<URI> roots = new HashSet<>();
		for (McpSkillRegistration registration : standalone) {
			if (!names.add(registration.getSkillBundle().getName())) throw invalid();
			addRegistration(registration, aggregate, roots);
		}
		for (McpSkillGroup group : groups) {
			if (!keys.add(group.getKey())) throw invalid();
			List<McpSkillRegistration> members = group.getSkillRegistrations();
			if (!members.isEmpty() && !names.add(members.get(0).getSkillBundle().getName())) throw invalid();
			for (McpSkillRegistration registration : members) addRegistration(registration, aggregate, roots);
		}

		Map<URI, PendingFile> pendingFiles = new LinkedHashMap<>();
		for (McpSkillRegistration registration : aggregate) {
			for (McpSkillRegistration.Resource resource : registration.getResources()) {
				URI uri = resource.getUri();
				PendingFile file = pendingFiles.get(uri);
				if (file == null) pendingFiles.put(uri, new PendingFile(uri, registration));
				else file.addOwner(registration);
			}
		}
		requireCompleteDescendants(aggregate);

		List<String> templates = new ArrayList<>();
		for (McpResourceRegistration resource : resources) {
			if (resource.getAddressType() == McpResourceAddressType.URI) {
				if (pendingFiles.containsKey(resource.getUri().orElseThrow())) throw invalid();
			} else templates.add(resource.getUriTemplate().orElseThrow());
		}
		// Leave ordinary endpoints' existing validation timing unchanged.
		if (!pendingFiles.isEmpty()) {
			try {
				McpSkillResourceCollisionValidator.requireNoTemplateCollisions(templates, pendingFiles.keySet());
			} catch (IllegalArgumentException ignored) {
				throw invalid();
			}
		}
		Map<URI, File> files = new LinkedHashMap<>();
		pendingFiles.forEach((uri, pending) -> files.put(uri, pending.freeze()));
		return new McpSkillEndpointIndex(aggregate, files);
	}

	private static void addRegistration(McpSkillRegistration registration,
			List<McpSkillRegistration> aggregate, Set<URI> roots) {
		if (!roots.add(registration.getUri())) throw invalid();
		aggregate.add(registration);
	}

	/**
	 * Build a temporary directory trie rather than scanning every pair or testing
	 * only overlapping files. An omitted child root is still an incomplete parent.
	 * Origin keys use URI.equals; path segments fold only percent-escape hex case,
	 * never encoded versus literal characters, path case, or registry authorities.
	 */
	private static void requireCompleteDescendants(List<McpSkillRegistration> registrations) {
		Map<URI, Directory> origins = new HashMap<>();
		for (McpSkillRegistration registration : registrations) {
			Directory directory = origins.computeIfAbsent(origin(registration.getUri()), ignored -> new Directory());
			for (String segment : directorySegments(registration.getUri()))
				directory = directory.children.computeIfAbsent(segment, ignored -> new Directory());
			directory.registration = registration;
		}
		for (McpSkillRegistration registration : registrations) {
			Directory directory = origins.get(origin(registration.getUri()));
			for (String segment : directorySegments(registration.getUri())) {
				if (directory.registration != null) {
					for (McpSkillRegistration.Resource resource : registration.getResources())
						if (!directory.registration.runtimeRegistration().hasSameFile(resource.getUri(),
								registration.runtimeRegistration())) throw invalid();
				}
				directory = directory.children.get(segment);
			}
		}
	}

	private static URI origin(URI uri) {
		String value = uri.toString();
		return URI.create(value.substring(0, value.length() - uri.getRawPath().length()) + "/");
	}

	private static List<String> directorySegments(URI uri) {
		String path = uri.getRawPath();
		String directory = path.substring(1, path.length() - "SKILL.md".length());
		if (directory.isEmpty()) return List.of();
		// Remove only the directory's final slash. Internal empty path segments
		// remain significant, just as they are to URI.equals.
		String[] segments = directory.substring(0, directory.length() - 1).split("/", -1);
		for (int index = 0; index < segments.length; ++index) {
			StringBuilder canonical = new StringBuilder(segments[index]);
			for (int offset = 0; offset < canonical.length(); ++offset) {
				if (canonical.charAt(offset) == '%') {
					canonical.setCharAt(offset + 1, Character.toUpperCase(canonical.charAt(offset + 1)));
					canonical.setCharAt(offset + 2, Character.toUpperCase(canonical.charAt(offset + 2)));
					offset += 2;
				}
			}
			segments[index] = canonical.toString();
		}
		return List.of(segments);
	}

	List<McpSkillRegistration> registrations() { return this.registrations; }
	List<File> files() { return List.copyOf(this.files.values()); }
	static void preflight(McpEndpoint endpoint) {
		List<List<McpSkillRuntimeBridge.Registration>> slots = new ArrayList<>();
		for (McpSkillRegistration registration : endpoint.getSkillRegistrations())
			slots.add(List.of(registration.runtimeRegistration()));
		for (McpSkillGroup group : endpoint.getSkillGroups())
			if (!group.getSkillRegistrations().isEmpty()) slots.add(group.getSkillRegistrations().stream()
					.map(McpSkillRegistration::runtimeRegistration).toList());
		McpSkillRuntimeBridge.preflightEndpoint(endpoint.getServerInfo(), endpoint.isServerInfoIncluded(),
				slots, endpoint.getSkillListHandler().isEmpty());
	}
	Optional<McpSkillRegistration> findRegistration(URI uri) {
		return Optional.ofNullable(this.registrationsByUri.get(uri));
	}
	Optional<File> findFile(URI uri) { return Optional.ofNullable(this.files.get(uri)); }

	/** Cache and representation are fixed over every owner, never just an allowed owner. */
	record File(URI uri, List<McpSkillRegistration> owners, McpCachePolicy cachePolicy, McpJsonObject readResult) {
		File { owners = List.copyOf(owners); }
		@Override public String toString() { return "McpSkillEndpointIndex.File[redacted]"; }
	}

	private static final class PendingFile {
		private final URI uri;
		private final List<McpSkillRegistration> owners = new ArrayList<>();
		private Duration timeToLive;
		private boolean privateScope;

		private PendingFile(URI uri, McpSkillRegistration owner) {
			this.uri = uri;
			this.owners.add(owner);
			this.timeToLive = owner.getCachePolicy().getTimeToLive();
			this.privateScope = owner.getCachePolicy().getScope() == McpCacheScope.PRIVATE;
		}

		private void addOwner(McpSkillRegistration owner) {
			if (this.owners.size() == MAXIMUM_FILE_OWNERS
					|| !this.owners.get(0).runtimeRegistration().hasSameFile(this.uri, owner.runtimeRegistration()))
				throw invalid();
			this.owners.add(owner);
			McpCachePolicy policy = owner.getCachePolicy();
			this.privateScope |= policy.getScope() == McpCacheScope.PRIVATE;
			if (policy.getTimeToLive().compareTo(this.timeToLive) < 0) this.timeToLive = policy.getTimeToLive();
		}

		private File freeze() {
			McpCachePolicy policy = this.privateScope ? McpCachePolicy.fromPrivateTimeToLive(this.timeToLive)
					: McpCachePolicy.fromPublicTimeToLive(this.timeToLive);
			McpJsonObject original = this.owners.get(0).runtimeRegistration().findReadResult(this.uri).orElseThrow();
			Map<String, McpJsonValue> fields = new LinkedHashMap<>(original.members());
			fields.put("ttlMs", new McpJsonNumber(this.timeToLive.toMillis()));
			fields.put("cacheScope", new McpJsonString(this.privateScope ? "private" : "public"));
			return new File(this.uri, this.owners, policy, new McpJsonObject(fields));
		}
	}

	private static final class Directory {
		private final Map<String, Directory> children = new HashMap<>();
		private McpSkillRegistration registration;
	}

	private static IllegalStateException invalid() {
		return new IllegalStateException("Conflicting or incomplete MCP Skills endpoint configuration.");
	}

	@Override public String toString() { return "McpSkillEndpointIndex[redacted]"; }
}
