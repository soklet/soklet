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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Closed, immutable registry of locally understood schema dialects.
 */
final class McpSchemaDialectRegistry {
	static final URI DRAFT_2020_12_URI =
			URI.create("https://json-schema.org/draft/2020-12/schema");

	private static final McpSchemaDialectRegistry DRAFT_2020_12 =
			new McpSchemaDialectRegistry(Map.of(DRAFT_2020_12_URI,
					standardDialect()));
	private static final McpSchemaUriResolver URI_RESOLVER =
			new McpSchemaUriResolver();

	private final Map<URI, McpSchemaDialect> dialects;
	private final Map<URI, URI> aliases;
	private final Map<URI, McpSchemaDialect> dialectsByIdentifier;

	McpSchemaDialectRegistry(Map<URI, McpSchemaDialect> dialects) {
		this(dialects, Map.of());
	}

	McpSchemaDialectRegistry(Map<URI, McpSchemaDialect> dialects,
			Map<URI, URI> aliases) {
		requireNonNull(dialects);
		Map<URI, McpSchemaDialect> copy = new LinkedHashMap<>();
		for (Map.Entry<URI, McpSchemaDialect> entry : dialects.entrySet().stream()
				.sorted(Map.Entry.comparingByKey(
						java.util.Comparator.comparing(URI::toASCIIString)))
				.toList()) {
			URI identifier = requireNonNull(entry.getKey());
			McpSchemaDialect dialect = requireNonNull(entry.getValue());
			if (!identifier.equals(dialect.uri()))
				throw new IllegalArgumentException(
						"A dialect registry key must equal its dialect URI.");
			copy.put(identifier, dialect);
		}
		McpSchemaDialect builtIn = copy.get(DRAFT_2020_12_URI);
		if (builtIn == null || !builtIn.equals(standardDialect()))
			throw new IllegalArgumentException(
					"The exact built-in Draft 2020-12 dialect must be registered.");
		this.dialects = Collections.unmodifiableMap(copy);

		Map<URI, URI> aliasCopy = new LinkedHashMap<>();
		Map<URI, McpSchemaDialect> identifiers = new LinkedHashMap<>(copy);
		for (Map.Entry<URI, URI> entry : requireNonNull(aliases).entrySet().stream()
				.sorted(Map.Entry.comparingByKey(
						java.util.Comparator.comparing(URI::toASCIIString)))
				.toList()) {
			URI alias = requireNonNull(entry.getKey());
			URI canonicalUri = requireNonNull(entry.getValue());
			McpSchemaDialect dialect = copy.get(canonicalUri);
			if (dialect == null)
				throw new IllegalArgumentException(
						"A dialect alias must target a registered canonical dialect URI.");
			McpSchemaDialect existing = identifiers.putIfAbsent(alias, dialect);
			if (existing != null && !existing.equals(dialect))
				throw new IllegalArgumentException(
						"A dialect alias collides with another dialect identifier.");
			if (!alias.equals(canonicalUri))
				aliasCopy.put(alias, canonicalUri);
		}
		this.aliases = Collections.unmodifiableMap(aliasCopy);
		this.dialectsByIdentifier = Collections.unmodifiableMap(identifiers);
	}

	static McpSchemaDialectRegistry draft202012() {
		return DRAFT_2020_12;
	}

	McpSchemaDialect defaultDialect() {
		return dialects.get(DRAFT_2020_12_URI);
	}

	Optional<McpSchemaDialect> find(URI uri) {
		requireNonNull(uri);
		McpSchemaDialect dialect = dialectsByIdentifier.get(uri);
		if (dialect == null && "".equals(URI_RESOLVER.rawFragment(uri)))
			dialect = dialectsByIdentifier.get(URI_RESOLVER.withoutFragment(uri));
		return Optional.ofNullable(dialect);
	}

	Map<URI, McpSchemaDialect> dialects() {
		return dialects;
	}

	Map<URI, URI> aliases() {
		return aliases;
	}

	private static McpSchemaDialect standardDialect() {
		Map<URI, Boolean> vocabularies = new LinkedHashMap<>();
		for (McpSchemaVocabulary vocabulary : List.of(
				McpSchemaVocabulary.CORE,
				McpSchemaVocabulary.APPLICATOR,
				McpSchemaVocabulary.UNEVALUATED,
				McpSchemaVocabulary.VALIDATION,
				McpSchemaVocabulary.META_DATA,
				McpSchemaVocabulary.FORMAT_ANNOTATION,
				McpSchemaVocabulary.CONTENT))
			vocabularies.put(vocabulary.uri(), true);
		return new McpSchemaDialect(DRAFT_2020_12_URI, vocabularies);
	}
}
