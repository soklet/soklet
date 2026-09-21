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
package com.soklet.internal.mcp.skills;

import com.soklet.McpCachePolicy;
import com.soklet.McpCacheScope;
import com.soklet.internal.mcp.protocol.McpApplicationMetadata;
import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Construction bridge for Skills values. Public only for the com.soklet facade;
 * internal packages are excluded from the supported application API.
 * This does not register routes, grant access, or advertise Skills support.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSkillRuntimeBridge {
	private static final McpJsonLimits JSON_LIMITS = McpJsonLimits.productionDefaults();
	// The exercised corpus profile, with independent production JSON constraints.
	// The input ceiling counts the complete root document, including its body.
	static final SkillYamlLimits YAML_LIMITS = new SkillYamlLimits(
			4 * 1_024 * 1_024, 128, 1_000_000, 1_048_576, 4_194_304L, 50_000_000L);

	private McpSkillRuntimeBridge() {}

	/** Creates a byte-owning, transport-profile-checked bundle without filesystem I/O. */
	@NonNull
	public static Bundle fromFiles(@NonNull Map<@NonNull String, byte @NonNull []> files) {
		SkillBundle snapshot = SkillBundle.fromFiles(files, YAML_LIMITS, JSON_LIMITS);
		return new Bundle(snapshot, snapshot.fileContents(JSON_LIMITS));
	}

	/** Binds a bundle to a root URI and validates canonical read/get/list wrappers. */
	@NonNull
	public static Registration register(@NonNull URI uri, @NonNull Bundle bundle,
			@NonNull McpCachePolicy cachePolicy) {
		return register(uri, bundle, cachePolicy, JSON_LIMITS);
	}

	// Explicit smaller profiles are test-only; public construction always uses the
	// same output profile as McpServerRuntimeBridge, regardless of request size.
	static Registration register(URI uri, Bundle bundle, McpCachePolicy cachePolicy, McpJsonLimits limits) {
		requireNonNull(uri, "A Skills root URI is required.");
		requireNonNull(bundle, "A Skills bundle is required.");
		requireNonNull(cachePolicy, "A Skills cache policy is required.");
		requireNonNull(limits, "Skills JSON limits are required.");
		SkillManifest manifest = SkillManifest.from(uri, bundle.snapshot, limits);
		List<Resource> resources = new ArrayList<>();
		Map<URI, McpJsonObject> reads = new LinkedHashMap<>();
		Map<URI, String> paths = new LinkedHashMap<>();
		for (int index = 0; index < manifest.resources().size(); ++index) {
			SkillManifest.Resource resource = manifest.resources().get(index);
			String path = bundle.snapshot.filePaths().get(index);
			McpJsonObject contents = bundle.contents.get(path).atUri(resource.uri());
			McpJsonObject read = result("contents", new McpJsonArray(List.of(contents)), cachePolicy);
			preflight(read, limits);
			reads.put(resource.uri(), read);
			paths.put(resource.uri(), path);
			resources.add(new Resource(resource.uri(), resource.digest(), resource.size()));
		}
		McpJsonObject get = result("skill", manifest.entry(), cachePolicy);
		preflight(get, limits);
		// A single entry must also fit atomically on a page, whose array adds depth
		// and a node. Automatic multi-entry page/server metadata checks belong to
		// endpoint construction, not this standalone registration value.
		preflight(result("skills", new McpJsonArray(List.of(manifest.entry())), cachePolicy), limits);
		return new Registration(bundle, manifest.entry(), get, resources, reads, paths);
	}

	private static McpJsonObject result(String field, McpJsonValue value, McpCachePolicy policy) {
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("resultType", new McpJsonString("complete"));
		members.put(field, value);
		members.put("ttlMs", new McpJsonNumber(policy.getTimeToLive().toMillis()));
		members.put("cacheScope", new McpJsonString(policy.getScope() == McpCacheScope.PUBLIC ? "public" : "private"));
		return new McpJsonObject(members);
	}

	private static void preflight(McpJsonObject result, McpJsonLimits limits) {
		// Allow either cache scope and any supported TTL, including later privacy
		// clamping. Match framework startup's representative numeric request ID.
		// Actual request IDs, server metadata and caller-specific page projections
		// must still pass the final runtime serializer; this is not their substitute.
		Map<String, McpJsonValue> fields = new LinkedHashMap<>(result.members());
		fields.put("ttlMs", new McpJsonNumber(Long.MAX_VALUE));
		fields.put("cacheScope", new McpJsonString("private"));
		Map<String, McpJsonValue> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", new McpJsonString("2.0"));
		envelope.put("id", new McpJsonNumber(0));
		envelope.put("result", new McpJsonObject(fields));
		try {
			new McpJsonCodec(limits).toUtf8Bytes(new McpJsonObject(envelope));
		} catch (IllegalArgumentException ignored) {
			throw new IllegalArgumentException("The Skills registration exceeds the configured JSON output profile.");
		}
	}

	/** Validates endpoint server metadata and conservative automatic page projections. */
	public static void preflightEndpoint(com.soklet.@NonNull McpImplementation info, boolean serverInfoIncluded,
			@NonNull List<@NonNull List<@NonNull Registration>> slots, boolean automatic) {
		McpJsonObject metadata = endpointMetadata(info, serverInfoIncluded);
		if (slots.isEmpty()) return;
		if (automatic && slots.size() > 32) throw paginationRequired();
		List<McpJsonValue> largestBytes = new ArrayList<>();
		List<McpJsonValue> largestNodes = new ArrayList<>();
		McpJsonCodec codec = new McpJsonCodec(JSON_LIMITS);
		try {
			for (List<Registration> slot : slots) {
				McpJsonObject byteWinner = null, nodeWinner = null;
				int maximumBytes = -1, maximumNodes = -1;
				for (Registration registration : slot) {
					// Public facade access is intentionally limited to immutable generated
					// values; no inspection byte arrays are copied or rehashed here.
					preflight(withMetadata(registration.getResult, metadata), JSON_LIMITS);
					for (McpJsonObject read : registration.reads.values())
						preflight(withMetadata(read, metadata), JSON_LIMITS);
					// This checks every member's depth/scalars with the real wrappers.
					McpJsonObject singlePage = result("skills", new McpJsonArray(List.of(registration.entry)),
							McpCachePolicy.privateNoCacheInstance());
					preflight(withMetadata(singlePage, metadata), JSON_LIMITS);
					if (automatic) {
						int bytes = codec.toUtf8Bytes(registration.entry).length;
						int nodes = nodeCount(registration.entry);
						if (bytes > maximumBytes) { maximumBytes = bytes; byteWinner = registration.entry; }
						if (nodes > maximumNodes) { maximumNodes = nodes; nodeWinner = registration.entry; }
					}
				}
				if (automatic) { largestBytes.add(byteWinner); largestNodes.add(nodeWinner); }
			}
		} catch (IllegalArgumentException ignored) {
			throw new IllegalArgumentException("An MCP Skills registration with endpoint metadata exceeds the JSON output profile.");
		}
		if (automatic) {
			try {
				// Byte and node maxima may belong to different alternatives. Check
				// separate worst-case pages; per-member checks above cover max depth
				// and all scalar/token/number limits independently of those choices.
				for (List<McpJsonValue> entries : List.of(largestBytes, largestNodes))
					preflight(withMetadata(result("skills", new McpJsonArray(entries),
							McpCachePolicy.privateNoCacheInstance()), metadata), JSON_LIMITS);
			} catch (IllegalArgumentException ignored) {
				throw paginationRequired();
			}
		}
	}

	/**
	 * Preflights a validated final page before allocating public copies of its
	 * entries. Immutable internal entry trees are shared, never copied. The exact
	 * request ID and endpoint identity are included; the longest supported TTL
	 * and cache scope conservatively cover the later security clamp. The protocol
	 * boundary still validates metadata grammar and the final response encoding.
	 */
	public static void preflightPage(@NonNull List<@NonNull Registration> registrations,
			com.soklet.@NonNull McpSkillPage page, com.soklet.@NonNull McpEndpoint endpoint,
			com.soklet.@NonNull McpRequestId requestId) {
		requireNonNull(registrations);
		requireNonNull(page);
		requireNonNull(endpoint);
		requireNonNull(requestId);
		try {
			McpJsonObject frameworkMetadata = endpointMetadata(endpoint.getServerInfo(), endpoint.isServerInfoIncluded());
			com.soklet.McpJsonObject pageMetadata = page.getMetadata();
			// First account without allocating a derived entry tree or metadata copy.
			// Eight nodes cover envelope/result objects and their fixed scalar/array values.
			long nodes = 8L + (page.getNextCursor().isPresent() ? 1L : 0L);
			for (Registration registration : registrations) {
				nodes += nodeCount(requireNonNull(registration).entry);
				McpPublicJsonValueConverter.requireProductionNodeCount(nodes, "MCP Skills page");
			}
			boolean hasPageMetadata = !pageMetadata.getMembers().isEmpty();
			boolean hasFrameworkMetadata = !frameworkMetadata.members().isEmpty();
			if (hasPageMetadata) nodes += McpPublicJsonValueConverter.productionNodeCount(pageMetadata);
			if (hasFrameworkMetadata) nodes += nodeCount(frameworkMetadata) - (hasPageMetadata ? 1L : 0L);
			McpPublicJsonValueConverter.requireProductionNodeCount(nodes, "MCP Skills page");
			// The same application-owned reserved-prefix rule used by final result
			// metadata prevents an application field from replacing server identity.
			McpApplicationMetadata.requireApplicationMetadata(pageMetadata);
			Map<String, McpJsonValue> metadata = new LinkedHashMap<>(
					McpPublicJsonValueConverter.toInternalObject(pageMetadata).members());
			metadata.putAll(frameworkMetadata.members());
			List<McpJsonValue> entries = new ArrayList<>();
			for (Registration registration : registrations) entries.add(registration.entry);
			Map<String, McpJsonValue> fields = new LinkedHashMap<>();
			fields.put("resultType", new McpJsonString("complete"));
			fields.put("skills", new McpJsonArray(entries));
			fields.put("ttlMs", new McpJsonNumber(Long.MAX_VALUE));
			fields.put("cacheScope", new McpJsonString("private"));
			page.getNextCursor().ifPresent(value -> fields.put("nextCursor", new McpJsonString(value)));
			if (!metadata.isEmpty()) fields.put("_meta", new McpJsonObject(metadata));
			McpJsonValue id = requestId.asString().<McpJsonValue>map(McpJsonString::new)
					.orElseGet(() -> new McpJsonNumber(new java.math.BigDecimal(requestId.asInteger().orElseThrow())));
			McpJsonObject envelope = new McpJsonObject(Map.of("jsonrpc", new McpJsonString("2.0"),
					"id", id, "result", new McpJsonObject(fields)));
			new McpJsonCodec(JSON_LIMITS).toUtf8Bytes(envelope);
		} catch (IllegalArgumentException ignored) {
			throw new IllegalArgumentException("The MCP Skills page cannot be delivered; use skillListHandler(...) to return a smaller valid page.");
		}
	}

	private static McpJsonObject endpointMetadata(com.soklet.McpImplementation info, boolean serverInfoIncluded) {
		if (!serverInfoIncluded) return McpJsonObject.empty();
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("name", new McpJsonString(info.getName()));
		fields.put("version", new McpJsonString(info.getVersion()));
		info.getTitle().ifPresent(value -> fields.put("title", new McpJsonString(value)));
		info.getDescription().ifPresent(value -> fields.put("description", new McpJsonString(value)));
		info.getWebsiteUrl().ifPresent(value -> fields.put("websiteUrl", new McpJsonString(value.toString())));
		return new McpJsonObject(Map.of("io.modelcontextprotocol/serverInfo", new McpJsonObject(fields)));
	}

	private static McpJsonObject withMetadata(McpJsonObject result, McpJsonObject metadata) {
		if (metadata.members().isEmpty()) return result;
		Map<String, McpJsonValue> fields = new LinkedHashMap<>(result.members());
		fields.put("_meta", metadata);
		return new McpJsonObject(fields);
	}

	private static int nodeCount(McpJsonValue value) {
		int count = 1;
		if (value instanceof McpJsonObject object)
			for (McpJsonValue child : object.members().values()) count += nodeCount(child);
		else if (value instanceof McpJsonArray array)
			for (McpJsonValue child : array.values()) count += nodeCount(child);
		return count;
	}

	private static IllegalArgumentException paginationRequired() {
		return new IllegalArgumentException("The automatic MCP Skills page exceeds the output profile; configure skillListHandler(...).");
	}

	/** Immutable owned bundle and its once-derived inspection/delivery values. */
	@ThreadSafe
	public static final class Bundle {
		private final SkillBundle snapshot;
		private final Map<String, SkillFileContents> contents;
		private final com.soklet.McpJsonObject documentMetadata;
		private final Set<String> filePaths;
		private final int hashCode;

		private Bundle(SkillBundle snapshot, Map<String, SkillFileContents> contents) {
			this.snapshot = snapshot;
			this.contents = contents;
			this.documentMetadata = (com.soklet.McpJsonObject) McpPublicJsonValueConverter.toPublic(snapshot.documentMetadata());
			this.filePaths = Collections.unmodifiableSet(new LinkedHashSet<>(snapshot.filePaths()));
			this.hashCode = snapshot.contentHashCode();
		}

		/** @return exact validated skill name */
		@NonNull public String name() { return this.snapshot.name(); }
		/** @return exact validated description */
		@NonNull public String description() { return this.snapshot.description(); }
		/** @return cached public immutable metadata */
		public com.soklet.@NonNull McpJsonObject documentMetadata() { return this.documentMetadata; }
		/** @return immutable canonical-order paths */
		@NonNull public Set<@NonNull String> filePaths() { return this.filePaths; }
		/** @return a defensive copy of only the requested file */
		@NonNull public Optional<byte @NonNull []> findFileBytes(@NonNull String filePath) {
			return this.snapshot.findFileBytes(filePath);
		}
		/** @return structural equality of paths and actual bytes, not only digests */
		@Override public boolean equals(@Nullable Object other) {
			return this == other || other instanceof Bundle bundle && this.snapshot.contentEquals(bundle.snapshot);
		}
		/** @return cached structural hash code */
		@Override public int hashCode() { return this.hashCode; }
		/** @return redacted rendering */
		@Override @NonNull public String toString() { return "McpSkillRuntimeBridge.Bundle[redacted]"; }
	}

	/** Immutable generated wire values for future authorized runtime routing. */
	@ThreadSafe
	public static final class Registration {
		private final Bundle bundle;
		private final McpJsonObject entry;
		private final McpJsonObject getResult;
		private final List<Resource> resources;
		private final Map<URI, McpJsonObject> reads;
		private final Map<URI, String> paths;

		private Registration(Bundle bundle, McpJsonObject entry, McpJsonObject getResult,
				List<Resource> resources, Map<URI, McpJsonObject> reads, Map<URI, String> paths) {
			this.bundle = bundle;
			this.entry = entry;
			this.getResult = getResult;
			this.resources = List.copyOf(resources);
			this.reads = Collections.unmodifiableMap(reads);
			this.paths = Collections.unmodifiableMap(paths);
		}

		/** Compares actual owned bytes and URI-independent representation metadata. */
		public boolean hasSameFile(@NonNull URI uri, @NonNull Registration other) {
			requireNonNull(uri, "A Skills file URI is required.");
			requireNonNull(other, "A Skills registration is required.");
			String path = this.paths.get(uri);
			String otherPath = other.paths.get(uri);
			if (path == null || otherPath == null) return false;
			SkillFileContents contents = this.bundle.contents.get(path);
			SkillFileContents otherContents = other.bundle.contents.get(otherPath);
			return contents.isText() == otherContents.isText()
					&& contents.mimeType().equals(otherContents.mimeType())
					&& this.bundle.snapshot.fileContentEquals(path, other.bundle.snapshot, otherPath);
		}

		/** @return immutable manifest entries in canonical order */
		@NonNull public List<@NonNull Resource> resources() { return this.resources; }
		/** @return skill entry without rewriting authored frontmatter */
		@NonNull public McpJsonObject entry() { return this.entry; }
		/** @return canonical complete skills/get result fields */
		@NonNull public McpJsonObject getResult() { return this.getResult; }
		/** @return canonical complete resources/read result fields, if owned */
		@NonNull public Optional<@NonNull McpJsonObject> findReadResult(@NonNull URI uri) {
			return Optional.ofNullable(this.reads.get(requireNonNull(uri, "A Skills file URI is required.")));
		}
		/** @return redacted rendering */
		@Override @NonNull public String toString() { return "McpSkillRuntimeBridge.Registration[redacted]"; }
	}

	/** Generated immutable file identity; not an application-authored registration. */
	@ThreadSafe
	public record Resource(@NonNull URI uri, @NonNull String digest, long sizeInBytes) {
		/** @return redacted rendering */
		@Override @NonNull public String toString() { return "McpSkillRuntimeBridge.Resource[redacted]"; }
	}
}
