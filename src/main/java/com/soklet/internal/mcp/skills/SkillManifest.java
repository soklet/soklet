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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Private URI-bound manifest projection, reusing construction-time digests.
 * Validates the complete skill entry, not a future JSON-RPC response envelope or
 * each file's text/base64 delivery representation. This does not publish resources.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class SkillManifest {
	private final URI uri;
	private final McpJsonObject entry;
	private final List<Resource> resources;

	private SkillManifest(URI uri, McpJsonObject entry, List<Resource> resources) {
		this.uri = uri;
		this.entry = entry;
		this.resources = List.copyOf(resources);
	}

	static SkillManifest from(URI rootUri, SkillBundle bundle, McpJsonLimits jsonLimits) {
		requireNonNull(rootUri, "A Skills root URI is required.");
		requireNonNull(bundle, "A Skills bundle is required.");
		requireNonNull(jsonLimits, "Skills JSON limits are required.");
		// URI bytes alone are a lower bound on entry output bytes. Reject excessive
		// expansion before allocating projected URIs, then check the actual entry.
		Map<String, URI> uris = SkillResourceUris.from(rootUri, bundle.name(), bundle.paths(),
				jsonLimits.maximumOutputBytes()).uris();
		List<Resource> resources = new ArrayList<>(bundle.resources().size());
		List<McpJsonValue> resourceValues = new ArrayList<>(bundle.resources().size());
		for (SkillBundle.Resource file : bundle.resources()) {
			URI uri = uris.get(file.path());
			resources.add(new Resource(uri, file.digest(), file.size()));
			Map<String, McpJsonValue> members = new LinkedHashMap<>();
			members.put("uri", new McpJsonString(uri.toString()));
			members.put("digest", new McpJsonString(file.digest()));
			members.put("size", new McpJsonNumber(BigDecimal.valueOf(file.size())));
			resourceValues.add(new McpJsonObject(members));
		}
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("uri", new McpJsonString(rootUri.toString()));
		members.put("frontmatter", bundle.documentMetadata());
		members.put("resources", new McpJsonArray(resourceValues));
		McpJsonObject entry = new McpJsonObject(members);
		try {
			new McpJsonCodec(jsonLimits).toUtf8Bytes(entry);
		} catch (IllegalArgumentException ignored) {
			throw new IllegalArgumentException("The Skills manifest exceeds the configured JSON profile.");
		}
		return new SkillManifest(rootUri, entry, resources);
	}

	URI uri() { return this.uri; }
	McpJsonObject entry() { return this.entry; }
	List<Resource> resources() { return this.resources; }

	@Override
	public String toString() { return "SkillManifest[redacted]"; }

	record Resource(URI uri, String digest, long size) {
		@Override public String toString() { return "SkillManifest.Resource[redacted]"; }
	}
}
