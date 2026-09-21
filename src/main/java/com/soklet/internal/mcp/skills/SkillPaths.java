/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.mcp.skills;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Validated logical paths only: no filesystem access or file-byte ownership.
 * Construction is bounded independently of the caller's collection type.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class SkillPaths {
	static final String ROOT = "SKILL.md";
	static final int MAXIMUM_FILES = 512;
	static final int MAXIMUM_PATH_UTF_8_BYTES = 8_192;

	private final List<String> paths;

	private SkillPaths(List<String> paths) {
		this.paths = List.copyOf(paths);
	}

	/** Validates every key before a future bundle loader is allowed to copy bytes. */
	static SkillPaths from(Collection<String> logicalPaths) {
		requireNonNull(logicalPaths, "Skills logical paths are required.");
		if (logicalPaths.size() > MAXIMUM_FILES) throw invalid();
		List<Path> validated = new ArrayList<>();
		HashSet<String> unique = new HashSet<>();
		for (String path : logicalPaths) {
			if (validated.size() == MAXIMUM_FILES) throw invalid();
			requireNonNull(path, "A Skills logical path is required.");
			byte[] bytes = validate(path);
			if (!unique.add(path)) throw invalid();
			validated.add(new Path(path, bytes));
		}
		if (!unique.contains(ROOT)) throw invalid();
		validated.sort((left, right) -> {
			if (left.value().equals(ROOT)) return right.value().equals(ROOT) ? 0 : -1;
			if (right.value().equals(ROOT)) return 1;
			return Arrays.compareUnsigned(left.utf8(), right.utf8());
		});
		List<String> ordered = new ArrayList<>(validated.size());
		for (Path path : validated) ordered.add(path.value());
		return new SkillPaths(ordered);
	}

	List<String> paths() { return this.paths; }

	private static byte[] validate(String path) {
		// UTF-16 length is a lower bound on the UTF-8 size of valid scalar text.
		if (path.isEmpty() || path.length() > MAXIMUM_PATH_UTF_8_BYTES) throw invalid();
		int bytes = 0;
		int segmentStart = 0;
		for (int offset = 0; offset < path.length(); offset++) {
			char c = path.charAt(offset);
			if (c == '\\' || c == '%' || Character.isISOControl(c)) throw invalid();
			if (c == '/') {
				validateSegment(path, segmentStart, offset);
				segmentStart = offset + 1;
			}
			if (Character.isHighSurrogate(c)) {
				if (++offset == path.length() || !Character.isLowSurrogate(path.charAt(offset))) throw invalid();
				bytes += 4;
			} else if (Character.isLowSurrogate(c)) {
				throw invalid();
			} else {
				bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
			}
			if (bytes > MAXIMUM_PATH_UTF_8_BYTES) throw invalid();
		}
		validateSegment(path, segmentStart, path.length());
		if (!Normalizer.isNormalized(path, Normalizer.Form.NFC)) throw invalid();
		return path.getBytes(StandardCharsets.UTF_8);
	}

	private static void validateSegment(String path, int start, int end) {
		int length = end - start;
		if (length == 0 || length == 1 && path.charAt(start) == '.'
				|| length == 2 && path.charAt(start) == '.' && path.charAt(start + 1) == '.') throw invalid();
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Skills logical paths.");
	}

	@Override
	public String toString() { return "SkillPaths[redacted]"; }

	private record Path(String value, byte[] utf8) {}
}
