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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authenticated official Draft 2020-12 meta-schema resources packaged with
 * Soklet for closed, offline resolution.
 */
final class McpSchemaDraft202012Bundle {
	private static final String RESOURCE_ROOT =
			"com/soklet/internal/mcp/schema/draft-2020-12/";
	private static final Map<String, URI> JSON_RESOURCES = resources();
	private static final McpJsonCodec CODEC = new McpJsonCodec(new McpJsonLimits(
			32_768, 64, 16_384, 16_384, 1_024, 100_000,
			20_000, 32_768));
	private static final List<McpSchemaDocument> DOCUMENTS = loadDocuments();

	private McpSchemaDraft202012Bundle() {
	}

	static List<McpSchemaDocument> documents() {
		return DOCUMENTS;
	}

	private static List<McpSchemaDocument> loadDocuments() {
		List<McpSchemaDocument> documents = new ArrayList<>(JSON_RESOURCES.size());
		for (Map.Entry<String, URI> entry : JSON_RESOURCES.entrySet()) {
			String resourceName = RESOURCE_ROOT + entry.getKey();
			try (InputStream input = McpSchemaDraft202012Bundle.class
					.getClassLoader().getResourceAsStream(resourceName)) {
				if (input == null)
					throw new IllegalStateException(
							"A packaged Draft 2020-12 resource is missing: " + resourceName);
				McpJsonValue value = CODEC.parse(input.readAllBytes());
				if (!(value instanceof McpJsonObject object)
						|| !(object.members().get("$id") instanceof McpJsonString id)
						|| !entry.getValue().toString().equals(id.value()))
					throw new IllegalStateException(
							"A packaged Draft 2020-12 resource has an unexpected $id: "
									+ resourceName);
				documents.add(new McpSchemaDocument(entry.getValue(), value));
			} catch (IOException | IllegalArgumentException exception) {
				throw new IllegalStateException(
						"A packaged Draft 2020-12 resource could not be loaded: "
								+ resourceName, exception);
			}
		}
		return List.copyOf(documents);
	}

	private static Map<String, URI> resources() {
		Map<String, URI> resources = new LinkedHashMap<>();
		resources.put("schema.json", uri("schema"));
		resources.put("meta/applicator.json", uri("meta/applicator"));
		resources.put("meta/content.json", uri("meta/content"));
		resources.put("meta/core.json", uri("meta/core"));
		resources.put("meta/format-annotation.json", uri("meta/format-annotation"));
		resources.put("meta/format-assertion.json", uri("meta/format-assertion"));
		resources.put("meta/meta-data.json", uri("meta/meta-data"));
		resources.put("meta/unevaluated.json", uri("meta/unevaluated"));
		resources.put("meta/validation.json", uri("meta/validation"));
		return Map.copyOf(resources);
	}

	private static URI uri(String suffix) {
		return URI.create("https://json-schema.org/draft/2020-12/" + suffix);
	}
}
