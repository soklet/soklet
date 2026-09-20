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

package com.soklet;

import com.soklet.McpAppResourceMetadata.ContentSecurityPolicy;
import com.soklet.McpAppResourceMetadata.Permission;
import com.soklet.internal.mcp.protocol.McpAppMimeType;
import com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Shared validation and wire composition of typed and raw Apps metadata.
 *
 * <p>Effective values are an internal validation view. Public getters continue
 * to expose the supplied typed configuration separately from raw metadata.
 * Unknown extension fields remain in the original immutable JSON object.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpAppMetadataSupport {

	private McpAppMetadataSupport() {}

	/**
	 * Composes a wire view without changing the separately retained raw and
	 * typed values. A non-Apps projection removes only recognized presentation
	 * fields, preserving unrelated extension members even inside {@code ui}.
	 */
	@NonNull
	static McpJsonObject toolMetadata(@NonNull McpJsonObject raw,
			@Nullable McpAppToolMetadata typed, boolean supportsApps) {
		Optional<McpAppToolMetadata> effective = effectiveToolMetadata(raw, typed);
		if (effective.isEmpty())
			return raw;

		McpJsonObject rawUi = ui(raw);
		Map<String, McpJsonValue> fields = new LinkedHashMap<>(rawUi.getMembers());
		fields.remove("resourceUri");
		fields.remove("visibility");
		if (supportsApps) {
			McpAppToolMetadata value = effective.orElseThrow();
			value.getResourceUri().ifPresent(uri -> fields.put("resourceUri",
					McpJsonString.fromValue(uri.toASCIIString())));
			if (typed != null || rawUi.getMembers().containsKey("visibility")) {
				fields.put("visibility", McpJsonArray.fromElements(value.getVisibility().stream()
						.map(audience -> McpJsonString.fromValue(switch (audience) {
							case MODEL -> "model";
							case APP -> "app";
						})).toList()));
			}
		}
		return withUi(raw, McpJsonObject.fromMembers(fields), supportsApps);
	}

	/** Composes canonical resource security fields and preserves raw extensions. */
	@NonNull
	static McpJsonObject resourceMetadata(@NonNull McpJsonObject raw,
			@Nullable McpAppResourceMetadata typed) {
		Optional<McpAppResourceMetadata> effective = effectiveResourceMetadata(raw, typed);
		if (effective.isEmpty())
			return raw;

		McpJsonObject rawUi = ui(raw);
		Map<String, McpJsonValue> fields = new LinkedHashMap<>(rawUi.getMembers());
		fields.remove("csp");
		fields.remove("permissions");
		fields.remove("domain");
		fields.remove("prefersBorder");
		McpAppResourceMetadata value = effective.orElseThrow();
		value.getContentSecurityPolicy().ifPresent(policy -> fields.put("csp",
				contentSecurityPolicyMetadata(policy, rawUi, typed != null)));
		if (!value.getPermissions().isEmpty() || rawUi.getMembers().containsKey("permissions")) {
			McpJsonObject rawPermissions = rawUi.find("permissions")
					.map(McpAppMetadataSupport::object).orElse(McpJsonObject.emptyInstance());
			Map<String, McpJsonValue> permissionFields = new LinkedHashMap<>();
			for (Permission permission : value.getPermissions()) {
				String name = switch (permission) {
					case CAMERA -> "camera";
					case MICROPHONE -> "microphone";
					case GEOLOCATION -> "geolocation";
					case CLIPBOARD_WRITE -> "clipboardWrite";
				};
				permissionFields.put(name, rawPermissions.find(name).orElse(McpJsonObject.emptyInstance()));
			}
			fields.put("permissions", McpJsonObject.fromMembers(permissionFields));
		}
		value.getDomain().ifPresent(domain -> fields.put("domain", McpJsonString.fromValue(domain)));
		value.getPrefersBorder().ifPresent(border -> fields.put("prefersBorder", McpJsonBoolean.fromValue(border)));
		return withUi(raw, McpJsonObject.fromMembers(fields), true);
	}

	@NonNull
	private static McpJsonObject contentSecurityPolicyMetadata(@NonNull ContentSecurityPolicy policy,
			@NonNull McpJsonObject rawUi, boolean typed) {
		McpJsonObject rawCsp = rawUi.find("csp")
				.map(McpAppMetadataSupport::object).orElse(McpJsonObject.emptyInstance());
		Map<String, McpJsonValue> fields = new LinkedHashMap<>(rawCsp.getMembers());
		fields.remove("connectDomains");
		fields.remove("resourceDomains");
		fields.remove("frameDomains");
		fields.remove("baseUriDomains");
		putOrigins(fields, rawCsp, "connectDomains", policy.getConnectDomains(), typed);
		putOrigins(fields, rawCsp, "resourceDomains", policy.getResourceDomains(), typed);
		putOrigins(fields, rawCsp, "frameDomains", policy.getFrameDomains(), typed);
		putOrigins(fields, rawCsp, "baseUriDomains", policy.getBaseUriDomains(), typed);
		return McpJsonObject.fromMembers(fields);
	}

	private static void putOrigins(@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull McpJsonObject rawCsp, @NonNull String name,
			@NonNull Set<@NonNull String> origins, boolean typed) {
		if (typed || rawCsp.getMembers().containsKey(name)) {
			McpPublicJsonValueConverter.requireCollectionCouldFitProductionNodeBudget(
					origins.size(), 1L, 2L, "MCP Apps CSP origins");
			fields.put(name, McpJsonArray.fromElements(origins.stream().map(McpJsonString::fromValue).toList()));
		}
	}

	@NonNull
	private static McpJsonObject withUi(@NonNull McpJsonObject raw,
			@NonNull McpJsonObject ui, boolean retainEmptyUi) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>(raw.getMembers());
		if (retainEmptyUi || !ui.getMembers().isEmpty())
			fields.put("ui", ui);
		else
			fields.remove("ui");
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	static Optional<@NonNull McpAppToolMetadata> effectiveToolMetadata(
			@NonNull McpJsonObject raw, @Nullable McpAppToolMetadata typed) {
		McpJsonObject ui = ui(raw);
		boolean recognized = ui.getMembers().containsKey("resourceUri")
				|| ui.getMembers().containsKey("visibility");
		if (typed != null) {
			if (recognized)
				throw new IllegalArgumentException("Typed and raw MCP Apps tool fields must not overlap.");
			return Optional.of(typed);
		}
		if (!recognized)
			return Optional.empty();

		McpAppToolMetadata.Builder builder = McpAppToolMetadata.builder();
		McpJsonValue resourceUri = ui.getMembers().get("resourceUri");
		if (resourceUri != null) {
			String value = string(resourceUri);
			try {
				builder.resourceUri(URI.create(value));
			} catch (IllegalArgumentException exception) {
				// URI syntax exceptions contain the supplied URI; do not retain one
				// in the cause chain at this configuration boundary.
				throw new IllegalArgumentException("MCP Apps resource URIs must be normalized absolute ASCII ui:// identifiers.");
			}
		}
		McpJsonValue visibility = ui.getMembers().get("visibility");
		if (visibility != null) {
			Set<McpAppToolMetadata.Visibility> audiences = EnumSet.noneOf(McpAppToolMetadata.Visibility.class);
			for (String audience : strings(visibility)) {
				audiences.add(switch (audience) {
					case "model" -> McpAppToolMetadata.Visibility.MODEL;
					case "app" -> McpAppToolMetadata.Visibility.APP;
					default -> throw new IllegalArgumentException("MCP Apps visibility contains an unsupported audience.");
				});
			}
			builder.visibility(audiences);
		}
		return Optional.of(builder.build());
	}

	@NonNull
	static Optional<@NonNull McpAppResourceMetadata> effectiveResourceMetadata(
			@NonNull McpJsonObject raw, @Nullable McpAppResourceMetadata typed) {
		McpJsonObject ui = ui(raw);
		boolean recognized = ui.getMembers().containsKey("csp")
				|| ui.getMembers().containsKey("permissions")
				|| ui.getMembers().containsKey("domain")
				|| ui.getMembers().containsKey("prefersBorder");
		if (typed != null) {
			if (recognized)
				throw new IllegalArgumentException("Typed and raw MCP Apps resource fields must not overlap.");
			return Optional.of(typed);
		}
		if (!recognized)
			return Optional.empty();

		McpAppResourceMetadata.Builder builder = McpAppResourceMetadata.builder();
		McpJsonValue csp = ui.getMembers().get("csp");
		if (csp != null)
			builder.contentSecurityPolicy(contentSecurityPolicy(object(csp)));
		McpJsonValue permissions = ui.getMembers().get("permissions");
		if (permissions != null)
			builder.permissions(permissions(object(permissions)));
		McpJsonValue domain = ui.getMembers().get("domain");
		if (domain != null)
			builder.domain(string(domain));
		McpJsonValue prefersBorder = ui.getMembers().get("prefersBorder");
		if (prefersBorder != null) {
			if (!(prefersBorder instanceof McpJsonBoolean bool))
				throw new IllegalArgumentException("MCP Apps border preference must be a boolean.");
			builder.prefersBorder(bool.getValue());
		}
		return Optional.of(builder.build());
	}

	static void requireResourceContents(@NonNull URI uri, @Nullable String mimeType,
			@NonNull McpJsonObject raw, @Nullable McpAppResourceMetadata typed) {
		requireNonNull(uri);
		if (effectiveResourceMetadata(raw, typed).isEmpty())
			return;
		McpAppMetadataValidation.requireResourceUri(uri);
		if (mimeType == null || !McpAppMimeType.isAppsProfile(mimeType))
			throw new IllegalArgumentException("MCP Apps resource metadata requires the exact Apps HTML MIME profile.");
	}

	@NonNull
	private static McpJsonObject ui(@NonNull McpJsonObject metadata) {
		McpJsonValue value = requireNonNull(metadata).getMembers().get("ui");
		return value == null ? McpJsonObject.emptyInstance() : object(value);
	}

	@NonNull
	private static ContentSecurityPolicy contentSecurityPolicy(
			@NonNull McpJsonObject csp) {
		McpAppResourceMetadata.ContentSecurityPolicy.Builder builder =
				McpAppResourceMetadata.ContentSecurityPolicy.builder();
		McpJsonValue connect = csp.getMembers().get("connectDomains");
		if (connect != null)
			builder.connectDomains(strings(connect));
		McpJsonValue resource = csp.getMembers().get("resourceDomains");
		if (resource != null)
			builder.resourceDomains(strings(resource));
		McpJsonValue frame = csp.getMembers().get("frameDomains");
		if (frame != null)
			builder.frameDomains(strings(frame));
		McpJsonValue baseUri = csp.getMembers().get("baseUriDomains");
		if (baseUri != null)
			builder.baseUriDomains(strings(baseUri));
		return builder.build();
	}

	@NonNull
	private static Set<@NonNull Permission> permissions(
			@NonNull McpJsonObject permissions) {
		Set<McpAppResourceMetadata.Permission> result = EnumSet.noneOf(McpAppResourceMetadata.Permission.class);
		permissions.getMembers().forEach((name, marker) -> {
			object(marker);
			result.add(switch (name) {
				case "camera" -> McpAppResourceMetadata.Permission.CAMERA;
				case "microphone" -> McpAppResourceMetadata.Permission.MICROPHONE;
				case "geolocation" -> McpAppResourceMetadata.Permission.GEOLOCATION;
				case "clipboardWrite" -> McpAppResourceMetadata.Permission.CLIPBOARD_WRITE;
				default -> throw new IllegalArgumentException("MCP Apps permissions contain an unsupported permission.");
			});
		});
		return result;
	}

	@NonNull
	private static McpJsonObject object(@NonNull McpJsonValue value) {
		if (value instanceof McpJsonObject object)
			return object;
		throw new IllegalArgumentException("MCP Apps metadata field must be an object.");
	}

	@NonNull
	private static String string(@NonNull McpJsonValue value) {
		if (value instanceof McpJsonString string)
			return string.getValue();
		throw new IllegalArgumentException("MCP Apps metadata field must be a string.");
	}

	@NonNull
	private static Set<@NonNull String> strings(@NonNull McpJsonValue value) {
		if (!(value instanceof McpJsonArray array))
			throw new IllegalArgumentException("MCP Apps metadata field must be a string array.");
		Set<String> result = new LinkedHashSet<>();
		for (McpJsonValue element : array.getElements()) {
			if (!result.add(string(element)))
				throw new IllegalArgumentException("MCP Apps metadata arrays must not contain duplicate entries.");
		}
		return result;
	}
}
