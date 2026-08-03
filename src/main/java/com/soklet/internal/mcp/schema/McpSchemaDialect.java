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

package com.soklet.internal.mcp.schema;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Immutable dialect identity and declared vocabulary requirements.
 */
record McpSchemaDialect(URI uri,
		Map<URI, Boolean> vocabularyRequirements) {
	McpSchemaDialect {
		requireNonNull(uri);
		Map<URI, Boolean> copy = new LinkedHashMap<>();
		List<Map.Entry<URI, Boolean>> entries = requireNonNull(
				vocabularyRequirements).entrySet().stream()
				.sorted(Map.Entry.comparingByKey(
						java.util.Comparator.comparing(URI::toASCIIString)))
				.toList();
		for (Map.Entry<URI, Boolean> entry : entries)
			copy.put(requireNonNull(entry.getKey()), requireNonNull(entry.getValue()));
		vocabularyRequirements = Collections.unmodifiableMap(copy);

		if (!Boolean.TRUE.equals(vocabularyRequirements.get(
				McpSchemaVocabulary.CORE.uri())))
			throw new IllegalArgumentException(
					"A JSON Schema dialect must require the Core vocabulary.");
	}

	boolean uses(McpSchemaVocabulary vocabulary) {
		return vocabularyRequirements.containsKey(
				requireNonNull(vocabulary).uri());
	}
}
