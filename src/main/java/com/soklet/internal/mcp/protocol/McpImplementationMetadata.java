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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpImplementationMetadata(@NonNull String name, @NonNull String version,
		@NonNull Optional<@NonNull String> title,
		@NonNull Optional<@NonNull String> description,
		@NonNull Optional<@NonNull URI> websiteUrl,
		@NonNull List<@NonNull Icon> icons, @NonNull McpJsonObject extensionFields) {
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

	@NonNull
	static McpImplementationMetadata withNameAndVersion(@NonNull String name,
			@NonNull String version) {
		return new McpImplementationMetadata(
				McpProtocolSupport.requireNonBlank(name, "Implementation name"),
				McpProtocolSupport.requireNonBlank(version, "Implementation version"),
				Optional.empty(), Optional.empty(),
				Optional.empty(), List.of(), McpJsonObject.empty());
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<@NonNull String, @NonNull McpJsonValue> fields =
				new LinkedHashMap<>(extensionFields.members());
		fields.put("name", new McpJsonString(name));
		fields.put("version", new McpJsonString(version));
		title.ifPresent(value -> fields.put("title", new McpJsonString(value)));
		description.ifPresent(value -> fields.put("description", new McpJsonString(value)));
		websiteUrl.ifPresent(value -> fields.put("websiteUrl", new McpJsonString(value.toString())));

		if (!icons.isEmpty()) {
			List<@NonNull McpJsonValue> iconValues = new ArrayList<>(icons.size());

			for (Icon icon : icons)
				iconValues.add(icon.toJsonObject());

			fields.put("icons", new McpJsonArray(iconValues));
		}

		return new McpJsonObject(fields);
	}

	record Icon(@NonNull URI source, @NonNull Optional<@NonNull String> mimeType,
			@NonNull List<@NonNull String> sizes,
			@NonNull Optional<@NonNull Theme> theme,
			@NonNull McpJsonObject extensionFields) {
		Icon {
			source = McpProtocolSupport.requireAbsoluteUri(source, "Icon source");
			requireNonNull(mimeType);
			sizes = List.copyOf(requireNonNull(sizes));
			requireNonNull(theme);
			extensionFields = McpProtocolSupport.requireExtensionFields(extensionFields,
					Set.of("src", "mimeType", "sizes", "theme"));
		}

		@NonNull
		McpJsonObject toJsonObject() {
			Map<@NonNull String, @NonNull McpJsonValue> fields =
					new LinkedHashMap<>(extensionFields.members());
			fields.put("src", new McpJsonString(source.toString()));
			mimeType.ifPresent(value -> fields.put("mimeType", new McpJsonString(value)));

			if (!sizes.isEmpty()) {
				List<@NonNull McpJsonValue> sizeValues = new ArrayList<>(sizes.size());

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

		@NonNull
		private final String wireValue;

		Theme(@NonNull String wireValue) {
			this.wireValue = wireValue;
		}

		@NonNull
		String wireValue() {
			return wireValue;
		}
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpResultMetadata {
	@NonNull
	static final String SERVER_INFORMATION_KEY = "io.modelcontextprotocol/serverInfo";
	@NonNull
	static final String SUBSCRIPTION_ID_KEY =
			"io.modelcontextprotocol/subscriptionId";

	@NonNull
	private final Optional<@NonNull McpImplementationMetadata> serverInformation;
	@NonNull
	private final McpJsonObject extensionFields;

	McpResultMetadata(
			@NonNull Optional<@NonNull McpImplementationMetadata> serverInformation,
			@NonNull McpJsonObject extensionFields) {
		this(serverInformation, extensionFields, false);
	}

	private McpResultMetadata(
			@NonNull Optional<@NonNull McpImplementationMetadata> serverInformation,
			@NonNull McpJsonObject extensionFields, boolean frameworkOwned) {
		this.serverInformation = requireNonNull(serverInformation);
		if (frameworkOwned) {
			McpJsonValue subscriptionId = requireNonNull(extensionFields)
					.members().get(SUBSCRIPTION_ID_KEY);
			if (extensionFields.members().size() != 1 || subscriptionId == null
					|| (!(subscriptionId instanceof McpJsonString)
					&& !(subscriptionId instanceof McpJsonNumber)))
				throw new IllegalArgumentException(
						"Framework subscription metadata requires exactly one string or integer subscription ID.");
			this.extensionFields = McpProtocolSupport.requireInboundMetadataFields(
					extensionFields, Set.of(SERVER_INFORMATION_KEY));
		} else {
			this.extensionFields =
					McpProtocolSupport.requireApplicationMetadataFields(
							requireNonNull(extensionFields),
							Set.of(SERVER_INFORMATION_KEY));
		}
	}

	@NonNull
	static McpResultMetadata withServerInformation(
			@NonNull McpImplementationMetadata serverInformation) {
		return new McpResultMetadata(Optional.of(requireNonNull(serverInformation)), McpJsonObject.empty());
	}

	@NonNull
	static McpResultMetadata withSubscriptionId(
			@NonNull McpJsonRpcId subscriptionId,
			@NonNull Optional<@NonNull McpImplementationMetadata>
					serverInformation) {
		return new McpResultMetadata(requireNonNull(serverInformation),
				new McpJsonObject(Map.of(SUBSCRIPTION_ID_KEY,
						requireNonNull(subscriptionId).toJsonValue())), true);
	}

	@NonNull
	Optional<@NonNull McpImplementationMetadata> serverInformation() {
		return serverInformation;
	}

	@NonNull
	McpJsonObject extensionFields() {
		return extensionFields;
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<@NonNull String, @NonNull McpJsonValue> fields =
				new LinkedHashMap<>(extensionFields.members());
		serverInformation.ifPresent(value -> fields.put(SERVER_INFORMATION_KEY, value.toJsonObject()));
		return new McpJsonObject(fields);
	}

	boolean isEmpty() {
		return serverInformation.isEmpty() && extensionFields.members().isEmpty();
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;
		if (!(object instanceof McpResultMetadata other))
			return false;
		return serverInformation.equals(other.serverInformation)
				&& extensionFields.equals(other.extensionFields);
	}

	@Override
	public int hashCode() {
		return 31 * serverInformation.hashCode() + extensionFields.hashCode();
	}

	@Override
	@NonNull
	public String toString() {
		return "McpResultMetadata[serverInformation=" + serverInformation
				+ ", extensionFields=" + extensionFields + "]";
	}
}
