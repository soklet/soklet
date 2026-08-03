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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Compiles the bounded primitive and conjunctive assertion kernel.
 *
 * <p>This deliberately fails closed on active standard keywords not
 * implemented by this slice. It is internal evidence, not a partial public
 * validator mode.</p>
 */
final class McpSchemaValidationProgramCompiler {
	private static final Set<String> UNIMPLEMENTED_VALIDATION_KEYWORDS = Set.of(
			"dependentRequired", "maxContains", "minContains", "maxItems",
			"minItems", "uniqueItems", "maxProperties", "minProperties",
			"maximum", "exclusiveMaximum", "minimum",
			"exclusiveMinimum", "multipleOf", "maxLength", "minLength",
			"pattern");
	private static final Set<String> UNIMPLEMENTED_APPLICATOR_KEYWORDS = Set.of(
			"additionalProperties", "anyOf", "contains",
			"dependentSchemas", "else", "if", "items", "not", "oneOf",
			"patternProperties", "prefixItems", "propertyNames", "then");
	private static final Set<String> UNIMPLEMENTED_UNEVALUATED_KEYWORDS = Set.of(
			"unevaluatedItems", "unevaluatedProperties");
	private static final Set<String> UNIMPLEMENTED_REFERENCE_KEYWORDS = Set.of(
			"$dynamicRef");

	private final McpSchemaDialectRegistry dialectRegistry;

	McpSchemaValidationProgramCompiler() {
		this(McpSchemaDialectRegistry.draft202012());
	}

	McpSchemaValidationProgramCompiler(McpSchemaDialectRegistry dialectRegistry) {
		this.dialectRegistry = requireNonNull(dialectRegistry);
	}

	McpSchemaValidationProgram compile(McpSchemaResourceGraph graph) {
		requireNonNull(graph);
		Map<McpSchemaLocation, McpSchemaNodeId> nodesByLocation =
				indexNodesByLocation(graph);
		List<McpCompiledValidationNode> nodes = new ArrayList<>(graph.nodes().size());
		for (McpCompiledSchemaNode node : graph.nodes())
			nodes.add(compileNode(graph, node, nodesByLocation));
		return new McpSchemaValidationProgram(graph, nodes);
	}

	private McpCompiledValidationNode compileNode(McpSchemaResourceGraph graph,
			McpCompiledSchemaNode node,
			Map<McpSchemaLocation, McpSchemaNodeId> nodesByLocation) {
		McpSchemaDialect dialect = dialectRegistry.find(
				graph.resource(node.resourceId()).dialectUri()).orElseThrow(() ->
				new McpSchemaCompilationException(
						McpSchemaCompilationException.Kind.UNSUPPORTED_DIALECT,
						"The validation program does not understand the node's dialect.",
						node.location(), "$schema"));
		McpJsonValue schema = node.schema();
		if (schema instanceof McpJsonBoolean booleanSchema)
			return new McpCompiledValidationNode(node.id(), node.location(),
					Optional.of(booleanSchema), Set.of(), Optional.empty(),
					Optional.empty(), Map.of(), List.of(), List.of(),
					Optional.empty());

		McpJsonObject object = (McpJsonObject) schema;
		rejectUnimplementedKeywords(object, node.location(), dialect);
		Set<McpSchemaType> acceptedTypes = dialect.uses(McpSchemaVocabulary.VALIDATION)
				? readTypes(object, node.location()) : Set.of();
		Optional<McpJsonValue> constant = dialect.uses(McpSchemaVocabulary.VALIDATION)
				? Optional.ofNullable(object.members().get("const")) : Optional.empty();
		Optional<List<McpJsonValue>> enumeration =
				dialect.uses(McpSchemaVocabulary.VALIDATION)
						? readEnumeration(object, node.location()) : Optional.empty();
		Map<String, McpSchemaNodeId> propertySchemas =
				dialect.uses(McpSchemaVocabulary.APPLICATOR)
						? readPropertySchemas(object, node.location(), nodesByLocation)
						: Map.of();
		List<String> requiredProperties =
				dialect.uses(McpSchemaVocabulary.VALIDATION)
						? readRequiredProperties(object, node.location()) : List.of();
		List<McpSchemaNodeId> allOfSchemas =
				dialect.uses(McpSchemaVocabulary.APPLICATOR)
						? readAllOfSchemas(object, node.location(), nodesByLocation)
						: List.of();
		Optional<McpSchemaNodeId> referenceTarget = node.reference()
				.map(McpSchemaReference::initialTargetNodeId);
		return new McpCompiledValidationNode(node.id(), node.location(),
				Optional.empty(), acceptedTypes, constant, enumeration,
				propertySchemas, requiredProperties, allOfSchemas,
				referenceTarget);
	}

	private Map<McpSchemaLocation, McpSchemaNodeId> indexNodesByLocation(
			McpSchemaResourceGraph graph) {
		Map<McpSchemaLocation, McpSchemaNodeId> nodesByLocation =
				new LinkedHashMap<>();
		for (McpCompiledSchemaNode node : graph.nodes()) {
			McpSchemaNodeId previous = nodesByLocation.put(node.location(), node.id());
			if (previous != null)
				throw new IllegalStateException(
						"A compiled schema location is not unique.");
		}
		return Map.copyOf(nodesByLocation);
	}

	private void rejectUnimplementedKeywords(McpJsonObject schema,
			McpSchemaLocation location, McpSchemaDialect dialect) {
		List<String> keywords = new ArrayList<>(schema.members().keySet());
		Collections.sort(keywords);
		for (String keyword : keywords) {
			boolean unsupported = UNIMPLEMENTED_REFERENCE_KEYWORDS.contains(keyword)
					|| (dialect.uses(McpSchemaVocabulary.VALIDATION)
					&& UNIMPLEMENTED_VALIDATION_KEYWORDS.contains(keyword))
					|| (dialect.uses(McpSchemaVocabulary.APPLICATOR)
					&& UNIMPLEMENTED_APPLICATOR_KEYWORDS.contains(keyword))
					|| (dialect.uses(McpSchemaVocabulary.UNEVALUATED)
					&& UNIMPLEMENTED_UNEVALUATED_KEYWORDS.contains(keyword));
			if (unsupported)
				throw new McpSchemaCompilationException(
						McpSchemaCompilationException.Kind.UNSUPPORTED_KEYWORD,
						"A recognized active keyword is not implemented by this validation slice.",
						location, keyword);
		}
	}

	private Set<McpSchemaType> readTypes(McpJsonObject schema,
			McpSchemaLocation location) {
		McpJsonValue value = schema.members().get("type");
		if (value == null)
			return Set.of();

		EnumSet<McpSchemaType> types = EnumSet.noneOf(McpSchemaType.class);
		Set<String> names = new LinkedHashSet<>();
		if (value instanceof McpJsonString type) {
			addType(type, types, names, location);
		} else if (value instanceof McpJsonArray array && !array.values().isEmpty()) {
			for (McpJsonValue element : array.values()) {
				if (!(element instanceof McpJsonString type))
					throw invalidType(location);
				addType(type, types, names, location);
			}
		} else {
			throw invalidType(location);
		}
		return types;
	}

	private void addType(McpJsonString type, Set<McpSchemaType> types,
			Set<String> names, McpSchemaLocation location) {
		McpSchemaType parsed = McpSchemaType.fromSchemaName(type.value())
				.orElseThrow(() -> invalidType(location));
		if (!names.add(type.value()))
			throw invalidType(location);
		types.add(parsed);
	}

	private Optional<List<McpJsonValue>> readEnumeration(McpJsonObject schema,
			McpSchemaLocation location) {
		McpJsonValue value = schema.members().get("enum");
		if (value == null)
			return Optional.empty();
		if (!(value instanceof McpJsonArray array))
			throw new McpSchemaCompilationException(
					McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
					"The enum keyword must contain an array.", location, "enum");
		return Optional.of(array.values());
	}

	private Map<String, McpSchemaNodeId> readPropertySchemas(
			McpJsonObject schema, McpSchemaLocation location,
			Map<McpSchemaLocation, McpSchemaNodeId> nodesByLocation) {
		McpJsonValue value = schema.members().get("properties");
		if (value == null)
			return Map.of();
		if (!(value instanceof McpJsonObject properties))
			throw new McpSchemaCompilationException(
					McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
					"The properties keyword must contain an object of schemas.",
					location, "properties");

		Map<String, McpSchemaNodeId> propertySchemas = new LinkedHashMap<>();
		List<String> names = new ArrayList<>(properties.members().keySet());
		Collections.sort(names);
		for (String name : names) {
			McpSchemaNodeId child = nodesByLocation.get(
					location.child("properties", name));
			if (child == null)
				throw new IllegalStateException(
						"A discovered property schema node is missing.");
			propertySchemas.put(name, child);
		}
		return propertySchemas;
	}

	private List<String> readRequiredProperties(McpJsonObject schema,
			McpSchemaLocation location) {
		McpJsonValue value = schema.members().get("required");
		if (value == null)
			return List.of();
		if (!(value instanceof McpJsonArray required))
			throw invalidRequired(location);

		Set<String> names = new LinkedHashSet<>();
		for (McpJsonValue element : required.values()) {
			if (!(element instanceof McpJsonString name)
					|| !names.add(name.value()))
				throw invalidRequired(location);
		}
		List<String> sortedNames = new ArrayList<>(names);
		Collections.sort(sortedNames);
		return List.copyOf(sortedNames);
	}

	private List<McpSchemaNodeId> readAllOfSchemas(McpJsonObject schema,
			McpSchemaLocation location,
			Map<McpSchemaLocation, McpSchemaNodeId> nodesByLocation) {
		McpJsonValue value = schema.members().get("allOf");
		if (value == null)
			return List.of();
		if (!(value instanceof McpJsonArray allOf) || allOf.values().isEmpty())
			throw new McpSchemaCompilationException(
					McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
					"The allOf keyword must contain a non-empty array of schemas.",
					location, "allOf");

		List<McpSchemaNodeId> schemas = new ArrayList<>(allOf.values().size());
		for (int index = 0; index < allOf.values().size(); ++index) {
			McpSchemaNodeId child = nodesByLocation.get(
					location.child("allOf", Integer.toString(index)));
			if (child == null)
				throw new IllegalStateException(
						"A discovered allOf schema node is missing.");
			schemas.add(child);
		}
		return List.copyOf(schemas);
	}

	private McpSchemaCompilationException invalidRequired(
			McpSchemaLocation location) {
		return new McpSchemaCompilationException(
				McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
				"The required keyword must contain an array of unique strings.",
				location, "required");
	}

	private McpSchemaCompilationException invalidType(
			McpSchemaLocation location) {
		return new McpSchemaCompilationException(
				McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
				"The type keyword must contain a valid unique type name or a non-empty array of unique type names.",
				location, "type");
	}
}
