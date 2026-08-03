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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

record McpImplementationMetadata(String name, String version, Optional<String> title,
		Optional<String> description, Optional<URI> websiteUrl, List<Icon> icons,
		McpJsonObject extensionFields) {
	McpImplementationMetadata {
		requireNonNull(name);
		requireNonNull(version);
		requireNonNull(title);
		requireNonNull(description);
		websiteUrl = requireNonNull(websiteUrl).map(value ->
				McpProtocolSupport.requireAbsoluteUri(value, "Implementation website URL"));
		icons = List.copyOf(requireNonNull(icons));
		extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
				Set.of("name", "version", "title", "description", "websiteUrl", "icons"));
	}

	static McpImplementationMetadata withNameAndVersion(String name, String version) {
		return new McpImplementationMetadata(
				McpProtocolSupport.requireNonBlank(name, "Implementation name"),
				McpProtocolSupport.requireNonBlank(version, "Implementation version"),
				Optional.empty(), Optional.empty(),
				Optional.empty(), List.of(), McpJsonObject.empty());
	}

	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>(extensionFields.members());
		fields.put("name", new McpJsonString(name));
		fields.put("version", new McpJsonString(version));
		title.ifPresent(value -> fields.put("title", new McpJsonString(value)));
		description.ifPresent(value -> fields.put("description", new McpJsonString(value)));
		websiteUrl.ifPresent(value -> fields.put("websiteUrl", new McpJsonString(value.toString())));

		if (!icons.isEmpty()) {
			List<McpJsonValue> iconValues = new ArrayList<>(icons.size());

			for (Icon icon : icons)
				iconValues.add(icon.toJsonObject());

			fields.put("icons", new McpJsonArray(iconValues));
		}

		return new McpJsonObject(fields);
	}

	record Icon(URI source, Optional<String> mimeType, List<String> sizes,
			Optional<Theme> theme, McpJsonObject extensionFields) {
		Icon {
			source = McpProtocolSupport.requireAbsoluteUri(source, "Icon source");
			requireNonNull(mimeType);
			sizes = List.copyOf(requireNonNull(sizes));
			requireNonNull(theme);
			extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
					Set.of("src", "mimeType", "sizes", "theme"));
		}

		McpJsonObject toJsonObject() {
			Map<String, McpJsonValue> fields = new LinkedHashMap<>(extensionFields.members());
			fields.put("src", new McpJsonString(source.toString()));
			mimeType.ifPresent(value -> fields.put("mimeType", new McpJsonString(value)));

			if (!sizes.isEmpty()) {
				List<McpJsonValue> sizeValues = new ArrayList<>(sizes.size());

				for (String size : sizes)
					sizeValues.add(new McpJsonString(requireNonNull(size)));

				fields.put("sizes", new McpJsonArray(sizeValues));
			}

			theme.ifPresent(value -> fields.put("theme", new McpJsonString(value.wireValue())));
			return new McpJsonObject(fields);
		}
	}

	enum Theme {
		LIGHT("light"),
		DARK("dark");

		private final String wireValue;

		Theme(String wireValue) {
			this.wireValue = wireValue;
		}

		String wireValue() {
			return wireValue;
		}
	}
}

record McpResultMetadata(Optional<McpImplementationMetadata> serverInformation,
		McpJsonObject extensionFields) {
	static final String SERVER_INFORMATION_KEY = "io.modelcontextprotocol/serverInfo";

	McpResultMetadata {
		requireNonNull(serverInformation);
		extensionFields = McpProtocolSupport.requireApplicationMetadataFields(extensionFields,
				Set.of(SERVER_INFORMATION_KEY));
	}

	static McpResultMetadata withServerInformation(McpImplementationMetadata serverInformation) {
		return new McpResultMetadata(Optional.of(requireNonNull(serverInformation)), McpJsonObject.empty());
	}

	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>(extensionFields.members());
		serverInformation.ifPresent(value -> fields.put(SERVER_INFORMATION_KEY, value.toJsonObject()));
		return new McpJsonObject(fields);
	}

	boolean isEmpty() {
		return serverInformation.isEmpty() && extensionFields.members().isEmpty();
	}
}
