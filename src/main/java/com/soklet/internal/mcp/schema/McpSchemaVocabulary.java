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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Standard vocabularies active in Soklet's Draft 2020-12 support target.
 */
enum McpSchemaVocabulary {
	CORE("core"),
	APPLICATOR("applicator"),
	UNEVALUATED("unevaluated"),
	VALIDATION("validation"),
	META_DATA("meta-data"),
	FORMAT_ANNOTATION("format-annotation"),
	CONTENT("content");

	private final URI uri;

	McpSchemaVocabulary(String name) {
		this.uri = URI.create("https://json-schema.org/draft/2020-12/vocab/" + name);
	}

	URI uri() {
		return uri;
	}

	static Optional<McpSchemaVocabulary> fromUri(URI uri) {
		requireNonNull(uri);
		for (McpSchemaVocabulary vocabulary : values()) {
			if (vocabulary.uri.equals(uri))
				return Optional.of(vocabulary);
		}
		return Optional.empty();
	}
}
