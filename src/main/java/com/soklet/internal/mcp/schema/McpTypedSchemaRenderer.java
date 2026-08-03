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
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Deterministic Profile 1 renderer for normalized typed Java shapes. */
final class McpTypedSchemaRenderer {
	private final McpSchemaCompilationLimits limits;

	McpTypedSchemaRenderer(McpSchemaCompilationLimits limits) {
		this.limits = requireNonNull(limits);
	}

	McpJsonObject render(McpTypedSchemaShape shape) {
		requireNonNull(shape);
		new Preflight().visit(shape, McpTypedSchemaPath.root(), 1, 0);
		return renderShape(shape);
	}

	private McpJsonObject renderShape(McpTypedSchemaShape shape) {
		if (shape instanceof McpTypedSchemaShape.Scalar scalar)
			return renderScalar(scalar.scalar());
		if (shape instanceof McpTypedSchemaShape.Enumeration enumeration)
			return renderEnumeration(enumeration);
		if (shape instanceof McpTypedSchemaShape.ArrayValue array)
			return objectOf("type", new McpJsonString("array"), "items",
					renderShape(array.elementShape()));
		if (shape instanceof McpTypedSchemaShape.MapValue map)
			return objectOf("type", new McpJsonString("object"),
					"additionalProperties", renderShape(map.valueShape()));
		if (shape instanceof McpTypedSchemaShape.RecordValue record)
			return renderRecord(record);
		throw new IllegalArgumentException("Unknown typed schema shape.");
	}

	private McpJsonObject renderScalar(McpTypedSchemaScalar scalar) {
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("type", new McpJsonString(scalar.jsonType()));
		scalar.minimum().ifPresent(minimum ->
				members.put("minimum", new McpJsonNumber(minimum)));
		scalar.maximum().ifPresent(maximum ->
				members.put("maximum", new McpJsonNumber(maximum)));
		return new McpJsonObject(members);
	}

	private McpJsonObject renderEnumeration(
			McpTypedSchemaShape.Enumeration enumeration) {
		List<McpJsonValue> constants = new ArrayList<>(
				enumeration.constants().size());
		for (String constant : enumeration.constants())
			constants.add(new McpJsonString(constant));
		return objectOf("type", new McpJsonString("string"), "enum",
				new McpJsonArray(constants));
	}

	private McpJsonObject renderRecord(McpTypedSchemaShape.RecordValue record) {
		Map<String, McpJsonValue> propertySchemas = new LinkedHashMap<>();
		List<McpJsonValue> required = new ArrayList<>();
		for (McpTypedSchemaShape.Property property : record.properties()) {
			McpJsonObject baseSchema = renderShape(property.shape());
			Map<String, McpJsonValue> propertySchema =
					new LinkedHashMap<>(baseSchema.members());
			property.title().ifPresent(title ->
					propertySchema.put("title", new McpJsonString(title)));
			property.description().ifPresent(description ->
					propertySchema.put("description", new McpJsonString(description)));
			property.headerName().ifPresent(header ->
					propertySchema.put("x-mcp-header", new McpJsonString(header)));
			propertySchemas.put(property.name(), new McpJsonObject(propertySchema));
			if (property.required())
				required.add(new McpJsonString(property.name()));
		}

		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("type", new McpJsonString("object"));
		members.put("properties", new McpJsonObject(propertySchemas));
		members.put("required", new McpJsonArray(required));
		members.put("additionalProperties", McpJsonBoolean.FALSE);
		return new McpJsonObject(members);
	}

	private McpJsonObject objectOf(String firstName, McpJsonValue firstValue,
			String secondName, McpJsonValue secondValue) {
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put(firstName, firstValue);
		members.put(secondName, secondValue);
		return new McpJsonObject(members);
	}

	private final class Preflight {
		private int nodeCount;
		private int keywordCount;

		private void visit(McpTypedSchemaShape shape, McpTypedSchemaPath path,
				int depth, int attachedAnnotationCount) {
			if (depth > limits.maximumSchemaDepth())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
						"Rendered typed schema depth exceeds its configured limit.",
						path);
			if (nodeCount >= limits.maximumSchemaNodeCount())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
						"Rendered typed schema node count exceeds its configured limit.",
						path);
			nodeCount++;

			if (shape instanceof McpTypedSchemaShape.Scalar scalar) {
				chargeKeywords(scalar.scalar().minimum().isPresent() ? 3 : 1,
						attachedAnnotationCount, path);
				return;
			}
			if (shape instanceof McpTypedSchemaShape.Enumeration enumeration) {
				checkCollectionSize(enumeration.constants().size(), path);
				chargeKeywords(2, attachedAnnotationCount, path);
				return;
			}
			if (shape instanceof McpTypedSchemaShape.ArrayValue array) {
				chargeKeywords(2, attachedAnnotationCount, path);
				visit(array.elementShape(), path.arrayElement(), depth + 1, 0);
				return;
			}
			if (shape instanceof McpTypedSchemaShape.MapValue map) {
				chargeKeywords(2, attachedAnnotationCount, path);
				visit(map.valueShape(), path.mapValue(), depth + 1, 0);
				return;
			}
			if (shape instanceof McpTypedSchemaShape.RecordValue record) {
				checkCollectionSize(record.properties().size(), path);
				long requiredCount = 0;
				for (McpTypedSchemaShape.Property property : record.properties()) {
					if (property.required())
						requiredCount++;
				}
				checkCollectionSize(requiredCount, path);
				chargeKeywords(4, attachedAnnotationCount, path);
				for (McpTypedSchemaShape.Property property : record.properties()) {
					McpTypedSchemaPath propertyPath = path.property(property.name());
					checkName(property.name(), propertyPath);
					property.headerName().ifPresent(header ->
							checkName(header, propertyPath));
					int annotations = (property.title().isPresent() ? 1 : 0)
							+ (property.description().isPresent() ? 1 : 0)
							+ (property.headerName().isPresent() ? 1 : 0);
					visit(property.shape(), propertyPath, depth + 1, annotations);
				}
				return;
			}
			throw new McpTypedSchemaException(
					McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
					"The normalized typed schema shape is unknown.", path);
		}

		private void chargeKeywords(int shapeKeywords, int attachedAnnotations,
				McpTypedSchemaPath path) {
			long requested = (long) keywordCount + shapeKeywords
					+ attachedAnnotations;
			if (requested > limits.maximumKeywordCount())
				throw limit(McpSchemaCompilationException.Limit.KEYWORD_COUNT,
						"Rendered typed schema keyword count exceeds its configured limit.",
						path);
			keywordCount = (int) requested;
		}

		private void checkCollectionSize(long size, McpTypedSchemaPath path) {
			if (size > limits.maximumCollectionEntryCount())
				throw limit(
						McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
						"Rendered typed schema collection width exceeds its configured limit.",
						path);
		}

		private void checkName(String name, McpTypedSchemaPath path) {
			if (name.length() > limits.maximumNameLengthInCharacters())
				throw limit(McpSchemaCompilationException.Limit.NAME_LENGTH,
						"A rendered typed schema name exceeds its configured limit.", path);
		}
	}

	private McpTypedSchemaException limit(
			McpSchemaCompilationException.Limit limit, String message,
			McpTypedSchemaPath path) {
		return new McpTypedSchemaException(limit, message, path);
	}
}
