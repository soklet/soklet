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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Applies the tool-input and tool-output use constraints that are intentionally
 * separate from Profile 1 document compilation.
 */
final class McpSchemaUseValidator {
	void validateToolInput(McpToolSchemaProfileProgram program) {
		requireNonNull(program);
		McpToolSchemaProfileNode root = program.node(program.rootNodeId());
		if (root.directType().orElse(null) != McpSchemaType.OBJECT)
			throw failure(McpSchemaCompilationException.Kind.INVALID_SCHEMA,
					"A tool input schema must directly declare type object.",
					root.location(), "type");

		Set<McpSchemaNodeId> reachableProperties =
				staticallyReachableProperties(program);
		Map<String, McpSchemaLocation> locationsByLowercaseHeader =
				new LinkedHashMap<>();
		for (McpToolSchemaProfileNode node : program.nodes()) {
			String header = program.declaredHeadersBySchemaPointer().get(
					node.location().jsonPointer());
			if (header == null)
				continue;

			if (!reachableProperties.contains(node.id()))
				throw invalidHeader(node,
						"x-mcp-header is allowed only on a property reached from the schema root solely through properties chains.");
			if (header.isEmpty() || !isHttpToken(header))
				throw invalidHeader(node,
						"x-mcp-header must contain a non-empty RFC 9110 field-name token.");

			McpSchemaType directType = node.directType().orElse(null);
			if (directType != McpSchemaType.STRING
					&& directType != McpSchemaType.BOOLEAN
					&& directType != McpSchemaType.INTEGER)
				throw invalidHeader(node,
						"x-mcp-header requires the direct type string, boolean, or integer.");
			// Integer safety constrains the argument value that is eventually
			// mirrored, so invocation processing enforces it rather than requiring
			// the author to express an equivalent schema-level range proof here.
			String lowercaseHeader = header.toLowerCase(Locale.ROOT);
			McpSchemaLocation previous =
					locationsByLowercaseHeader.putIfAbsent(lowercaseHeader,
							node.location());
			if (previous != null)
				throw invalidHeader(node,
						"x-mcp-header names must be unique case-insensitively within one tool input schema.");
		}
	}

	void validateToolOutput(McpToolSchemaProfileProgram program) {
		requireNonNull(program);
		for (McpToolSchemaProfileNode node : program.nodes()) {
			if (program.declaredHeadersBySchemaPointer().containsKey(
					node.location().jsonPointer()))
				throw invalidHeader(node,
						"x-mcp-header is not permitted in a tool output schema.");
		}
	}

	private Set<McpSchemaNodeId> staticallyReachableProperties(
			McpToolSchemaProfileProgram program) {
		Set<McpSchemaNodeId> reachable = new LinkedHashSet<>();
		Deque<McpSchemaNodeId> pending = new ArrayDeque<>();
		addProperties(program.node(program.rootNodeId()), pending);

		while (!pending.isEmpty()) {
			McpSchemaNodeId nodeId = pending.removeFirst();
			if (!reachable.add(nodeId))
				continue;
			addProperties(program.node(nodeId), pending);
		}
		return Set.copyOf(reachable);
	}

	private void addProperties(McpToolSchemaProfileNode node,
			Deque<McpSchemaNodeId> destination) {
		for (McpSchemaNodeId property : node.propertySchemas().values())
			destination.addLast(property);
	}

	private boolean isHttpToken(String value) {
		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);
				boolean token = (character >= '0' && character <= '9')
						|| (character >= 'A' && character <= 'Z')
						|| (character >= 'a' && character <= 'z')
						|| "!#$%&'*+-.^_`|~".indexOf(character) >= 0;
			if (!token)
				return false;
		}
		return true;
	}

	private McpSchemaCompilationException invalidHeader(
			McpToolSchemaProfileNode node, String message) {
		return failure(McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
				message, node.location(), "x-mcp-header");
	}

	private McpSchemaCompilationException failure(
			McpSchemaCompilationException.Kind kind, String message,
			McpSchemaLocation location, String keyword) {
		return new McpSchemaCompilationException(kind, message, location, keyword);
	}
}
