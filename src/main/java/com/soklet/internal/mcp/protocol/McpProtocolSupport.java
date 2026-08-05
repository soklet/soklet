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

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpProtocolSupport {
	private McpProtocolSupport() {
	}

	@NonNull
	static String requireNonBlank(@NonNull String value, @NonNull String description) {
		requireNonNull(value);

		if (value.isBlank())
			throw new IllegalArgumentException(description + " must not be blank.");

		return value;
	}

	@NonNull
	static URI requireAbsoluteUri(@NonNull URI uri, @NonNull String description) {
		requireNonNull(uri);
		requireNonNull(description);

		if (!uri.isAbsolute())
			throw new IllegalArgumentException(description + " must be an absolute URI.");

		return uri;
	}

	@NonNull
	static McpJsonObject requireExtensionFields(@NonNull McpJsonObject fields,
			@NonNull Set<@NonNull String> reservedNames) {
		requireNonNull(fields);
		requireNonNull(reservedNames);

		for (String name : fields.members().keySet()) {
			if (reservedNames.contains(name))
				throw new IllegalArgumentException("Extension field collides with reserved field '" + name + "'.");
		}

		return fields;
	}

	@NonNull
	static McpJsonObject requireInboundMetadataFields(
			@NonNull McpJsonObject fields,
			@NonNull Set<@NonNull String> reservedNames) {
		requireExtensionFields(fields, reservedNames);

		for (String name : fields.members().keySet())
			requireMetadataKey(name, false);

		return fields;
	}

	@NonNull
	static McpJsonObject requireApplicationMetadataFields(
			@NonNull McpJsonObject fields,
			@NonNull Set<@NonNull String> reservedNames) {
		requireInboundMetadataFields(fields, reservedNames);

		for (String name : fields.members().keySet()) {
			if (hasReservedMetadataPrefix(name))
				throw new IllegalArgumentException(
						"Application metadata must not use an MCP-reserved prefix: " + name);
		}

		return fields;
	}

	@NonNull
	static String requireExtensionIdentifier(@NonNull String identifier) {
		return requireMetadataKey(identifier, true);
	}

	@NonNull
	private static String requireMetadataKey(@NonNull String key,
			boolean prefixRequired) {
		requireNonNull(key);
		int slashIndex = key.indexOf('/');

		if (slashIndex != key.lastIndexOf('/'))
			throw new IllegalArgumentException("Invalid metadata key: " + key);

		if (slashIndex < 0) {
			if (prefixRequired || !validMetadataName(key))
				throw new IllegalArgumentException("Invalid metadata key: " + key);

			return key;
		}

		String prefix = key.substring(0, slashIndex);
		String name = key.substring(slashIndex + 1);

		if (prefix.isEmpty() || !validMetadataName(name))
			throw new IllegalArgumentException("Invalid metadata key: " + key);

		for (String label : prefix.split("\\.", -1)) {
			if (!validMetadataLabel(label))
				throw new IllegalArgumentException("Invalid metadata key: " + key);
		}

		return key;
	}

	private static boolean validMetadataLabel(@NonNull String label) {
		if (label.isEmpty() || !asciiLetter(label.charAt(0)))
			return false;

		if (label.length() > 1 && !asciiLetterOrDigit(label.charAt(label.length() - 1)))
			return false;

		for (int index = 1; index < label.length() - 1; ++index) {
			char character = label.charAt(index);

			if (!asciiLetterOrDigit(character) && character != '-')
				return false;
		}

		return true;
	}

	private static boolean validMetadataName(@NonNull String name) {
		if (name.isEmpty())
			return true;

		if (!asciiLetterOrDigit(name.charAt(0)))
			return false;

		if (name.length() > 1 && !asciiLetterOrDigit(name.charAt(name.length() - 1)))
			return false;

		for (int index = 1; index < name.length() - 1; ++index) {
			char character = name.charAt(index);

			if (!asciiLetterOrDigit(character) && character != '-'
					&& character != '_' && character != '.')
				return false;
		}

		return true;
	}

	private static boolean hasReservedMetadataPrefix(@NonNull String key) {
		int slashIndex = key.indexOf('/');

		if (slashIndex < 0)
			return false;

		String prefix = key.substring(0, slashIndex);
		int firstDotIndex = prefix.indexOf('.');

		if (firstDotIndex < 0)
			return false;

		int secondDotIndex = prefix.indexOf('.', firstDotIndex + 1);
		String secondLabel = secondDotIndex < 0
				? prefix.substring(firstDotIndex + 1)
				: prefix.substring(firstDotIndex + 1, secondDotIndex);
		return "mcp".equals(secondLabel) || "modelcontextprotocol".equals(secondLabel);
	}

	private static boolean asciiLetter(char character) {
		return (character >= 'A' && character <= 'Z')
				|| (character >= 'a' && character <= 'z');
	}

	private static boolean asciiLetterOrDigit(char character) {
		return asciiLetter(character) || (character >= '0' && character <= '9');
	}

	@NonNull
	static List<@NonNull String> immutableUniqueNames(
			@NonNull List<@NonNull String> names, @NonNull String description) {
		requireNonNull(names);
		List<@NonNull String> copiedNames = new ArrayList<>(names.size());
		Set<@NonNull String> uniqueNames = new LinkedHashSet<>();

		for (String name : names) {
			String normalizedName = requireNonBlank(name, description);

			if (!uniqueNames.add(normalizedName))
				throw new IllegalArgumentException("Duplicate " + description + " '" + normalizedName + "'.");

			copiedNames.add(normalizedName);
		}

		return List.copyOf(copiedNames);
	}

	@NonNull
	static <T extends @NonNull Object> Map<@NonNull String, @NonNull T> immutableOpenObjectMap(
			@NonNull Map<@NonNull String, @NonNull T> values) {
		requireNonNull(values);
		Map<@NonNull String, @NonNull T> copiedValues = new LinkedHashMap<>(values.size());

		for (Map.Entry<@NonNull String, @NonNull T> entry : values.entrySet())
			copiedValues.put(requireNonNull(entry.getKey()), requireNonNull(entry.getValue()));

		return Collections.unmodifiableMap(copiedValues);
	}
}
