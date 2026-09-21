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

import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Private immutable snapshot of application-supplied logical files. No filesystem
 * access, URI registration, MIME inference, or resource publication occurs here.
 * Parser/JSON limits remain explicit private qualification inputs; they are not
 * a public budget API. Applications must not mutate inputs during construction.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class SkillBundle {
	static final int MAXIMUM_TOTAL_BYTES = 16 * 1024 * 1024;
	private final SkillPaths paths;
	private final Map<String, byte[]> files;
	private final SkillDocumentMetadata documentMetadata;
	private final List<Resource> resources;
	private final long sizeInBytes;

	private SkillBundle(SkillPaths paths, Map<String, byte[]> files,
			SkillDocumentMetadata documentMetadata, List<Resource> resources, long sizeInBytes) {
		this.paths = paths;
		this.files = Collections.unmodifiableMap(files);
		this.documentMetadata = documentMetadata;
		this.resources = List.copyOf(resources);
		this.sizeInBytes = sizeInBytes;
	}

	static SkillBundle fromFiles(Map<String, byte[]> files, SkillYamlLimits yamlLimits, McpJsonLimits jsonLimits) {
		requireNonNull(files, "Skills files are required.");
		requireNonNull(yamlLimits, "Skills YAML limits are required.");
		requireNonNull(jsonLimits, "Skills JSON limits are required.");
		if (files.size() > SkillPaths.MAXIMUM_FILES) throw invalid();

		// Capture references exactly once, bounding actual iteration rather than
		// trusting Map.size(). Check every path and byte length before any byte copy.
		List<String> keys = new ArrayList<>();
		List<byte[]> values = new ArrayList<>();
		long size = 0;
		for (Map.Entry<String, byte[]> entry : files.entrySet()) {
			if (keys.size() == SkillPaths.MAXIMUM_FILES) throw invalid();
			requireNonNull(entry, "A Skills file entry is required.");
			String key = requireNonNull(entry.getKey(), "A Skills logical path is required.");
			byte[] value = requireNonNull(entry.getValue(), "Skills file bytes are required.");
			size += value.length;
			if (size > MAXIMUM_TOTAL_BYTES) throw invalid();
			keys.add(key);
			values.add(value);
		}
		SkillPaths paths = SkillPaths.from(keys);
		Map<String, byte[]> supplied = new LinkedHashMap<>();
		for (int index = 0; index < keys.size(); ++index) supplied.put(keys.get(index), values.get(index));
		if (supplied.get(SkillPaths.ROOT).length > yamlLimits.maximumInputBytes())
			throw new SkillYamlException(SkillYamlException.Reason.INPUT_LIMIT, 1, 1);

		Map<String, byte[]> owned = new LinkedHashMap<>();
		byte[] root = supplied.get(SkillPaths.ROOT).clone();
		owned.put(SkillPaths.ROOT, root);
		// The parser defensively copies this snapshot too. Its decoded body/source
		// are temporary, not retained by the bundle; parsed and hashed bytes agree.
		SkillDocumentMetadata metadata = SkillDocumentMetadata.from(
				SkillFrontmatter.parse(root, yamlLimits, jsonLimits).metadata());
		for (String path : paths.paths()) {
			if (!path.equals(SkillPaths.ROOT)) owned.put(path, supplied.get(path).clone());
		}

		MessageDigest sha256 = sha256();
		List<Resource> resources = new ArrayList<>(owned.size());
		for (Map.Entry<String, byte[]> entry : owned.entrySet()) {
			byte[] bytes = entry.getValue();
			resources.add(new Resource(entry.getKey(),
					"sha256:" + HexFormat.of().formatHex(sha256.digest(bytes)), bytes.length));
		}
		return new SkillBundle(paths, owned, metadata, resources, size);
	}

	String name() { return this.documentMetadata.name(); }
	String description() { return this.documentMetadata.description(); }
	McpJsonObject documentMetadata() { return this.documentMetadata.metadata(); }
	List<String> filePaths() { return this.paths.paths(); }
	SkillPaths paths() { return this.paths; }
	List<Resource> resources() { return this.resources; }
	long sizeInBytes() { return this.sizeInBytes; }

	/** Construction-time conversion; owned arrays never leave this package boundary. */
	Map<String, SkillFileContents> fileContents(McpJsonLimits limits) {
		Map<String, SkillFileContents> contents = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> entry : this.files.entrySet())
			contents.put(entry.getKey(), SkillFileContents.from(entry.getKey(), entry.getValue(), limits));
		return Collections.unmodifiableMap(contents);
	}

	boolean contentEquals(SkillBundle other) {
		if (this == other) return true;
		if (!filePaths().equals(other.filePaths())) return false;
		for (String path : filePaths())
			if (!Arrays.equals(this.files.get(path), other.files.get(path))) return false;
		return true;
	}

	boolean fileContentEquals(String path, SkillBundle other, String otherPath) {
		return Arrays.equals(this.files.get(path), other.files.get(otherPath));
	}

	// Equal bytes necessarily have equal digests. Equality itself compares bytes;
	// digests are only a cached, bounded source for the value's hash code.
	int contentHashCode() { return this.resources.hashCode(); }

	Optional<byte[]> findFileBytes(String path) {
		requireNonNull(path, "A Skills logical path is required.");
		byte[] bytes = this.files.get(path);
		return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			// SHA-256 is mandatory in every supported Java implementation.
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Skills bundle files.");
	}

	@Override
	public String toString() { return "SkillBundle[redacted]"; }

	record Resource(String path, String digest, long size) {
		@Override public String toString() { return "SkillBundle.Resource[redacted]"; }
	}
}
