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

import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import static java.util.Objects.requireNonNull;

/**
 * Agent Skills field validation over an already parsed, bounded immutable header.
 * This does not copy, normalize, rewrite, or filter the document metadata.
 *
 * <p>The <a href="https://agentskills.io/specification">format specification</a>
 * permits Unicode names; its reference validator's i18n tests confirm that this
 * is not an ASCII-only grammar. Here alphanumeric means Unicode letter/number
 * categories, excluding uppercase/titlecase characters, with uncased letters
 * allowed. Lengths count Unicode code points, not UTF-16 code units.
 *
 * <p>Unlike the demonstration reference validator, this implementation does not
 * trim or NFKC-normalize names, reject unknown fields, or require descriptions
 * to contain non-whitespace text. Those are not the published literal format
 * rules. The <a href="https://github.com/modelcontextprotocol/ext-skills/blob/41e7c66db2510a3e98d9614eb1998f6b970006d7/specification/stable/skills.mdx#frontmatter">MCP binding</a>
 * requires the complete frontmatter unchanged. Directory/name agreement belongs
 * to registration URI validation, not this logical bundle metadata boundary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class SkillDocumentMetadata {
	private final String name;
	private final String description;
	private final McpJsonObject metadata;

	private SkillDocumentMetadata(String name, String description, McpJsonObject metadata) {
		this.name = name;
		this.description = description;
		this.metadata = metadata;
	}

	static SkillDocumentMetadata from(McpJsonObject metadata) {
		requireNonNull(metadata, "Skills document metadata is required.");
		String name = requireString(metadata.members().get("name"), "name");
		validateName(name);
		String description = requireString(metadata.members().get("description"), "description");
		validateLength(description, 1_024, "description");
		validateOptionalString(metadata, "license");
		validateOptionalString(metadata, "allowed-tools");
		if (metadata.members().containsKey("compatibility"))
			validateLength(requireString(metadata.members().get("compatibility"), "compatibility"), 500, "compatibility");
		if (metadata.members().containsKey("metadata")) {
			if (!(metadata.members().get("metadata") instanceof McpJsonObject additional))
				throw invalid("metadata", "must be an object");
			// The immutable JSON object has already validated string keys. Do not
			// coerce numeric/boolean/null values into strings or reject reserved keys.
			for (McpJsonValue value : additional.members().values())
				if (!(value instanceof McpJsonString))
					throw invalid("metadata", "must contain only string values");
		}
		return new SkillDocumentMetadata(name, description, metadata);
	}

	String name() { return this.name; }
	String description() { return this.description; }
	McpJsonObject metadata() { return this.metadata; }

	private static void validateName(String name) {
		validateLength(name, 64, "name");
		boolean previousHyphen = true;
		for (int offset = 0; offset < name.length();) {
			int codePoint = name.codePointAt(offset);
			offset += Character.charCount(codePoint);
			if (codePoint == '-') {
				if (previousHyphen || offset == name.length())
					throw invalid("name", "must not start or end with a hyphen or contain consecutive hyphens");
				previousHyphen = true;
			} else {
				int type = Character.getType(codePoint);
				boolean alphanumeric = Character.isLetter(codePoint)
						|| type == Character.DECIMAL_DIGIT_NUMBER
						|| type == Character.LETTER_NUMBER
						|| type == Character.OTHER_NUMBER;
				if (!alphanumeric || Character.isUpperCase(codePoint)
						|| Character.isTitleCase(codePoint))
					throw invalid("name", "must contain only lowercase letters, numbers, and hyphens");
				previousHyphen = false;
			}
		}
	}

	private static void validateLength(String value, int maximumCodePoints, String field) {
		if (value.isEmpty()) throw invalid(field, "must not be empty");
		if (value.length() > maximumCodePoints * 2)
			throw invalid(field, "exceeds " + maximumCodePoints + " code points");
		int count = 0;
		for (int offset = 0; offset < value.length(); ++offset) {
			char character = value.charAt(offset);
			if (Character.isHighSurrogate(character)) {
				if (++offset == value.length() || !Character.isLowSurrogate(value.charAt(offset)))
					throw invalid(field, "must contain valid Unicode scalar text");
			} else if (Character.isLowSurrogate(character)) {
				throw invalid(field, "must contain valid Unicode scalar text");
			}
			if (++count > maximumCodePoints)
				throw invalid(field, "exceeds " + maximumCodePoints + " code points");
		}
	}

	private static void validateOptionalString(McpJsonObject metadata, String key) {
		if (metadata.members().containsKey(key)) requireString(metadata.members().get(key), key);
	}

	private static String requireString(McpJsonValue value, String field) {
		if (value == null) throw invalid(field, "is required");
		if (!(value instanceof McpJsonString string)) throw invalid(field, "must be a string");
		return string.value();
	}

	/** Callers supply fixed field labels/rules and fixed limits, never authored keys or values. */
	private static IllegalArgumentException invalid(String field, String rule) {
		return new IllegalArgumentException("Skills field '" + field + "' " + rule + ".");
	}

	@Override
	public String toString() { return "SkillDocumentMetadata[redacted]"; }
}
