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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** One traversal and policy implementation shared by all Java type adapters. */
final class McpTypedSchemaResolver<T> {
	private final McpTypedTypeModel<T> typeModel;
	private final McpSchemaCompilationLimits limits;

	McpTypedSchemaResolver(McpTypedTypeModel<T> typeModel,
			McpSchemaCompilationLimits limits) {
		this.typeModel = requireNonNull(typeModel);
		this.limits = requireNonNull(limits);
	}

	McpTypedSchemaShape resolveSchema(T type) {
		return new Resolution().resolve(requireNonNull(type),
				McpTypedSchemaPath.root(), 1);
	}

	McpTypedSchemaShape.RecordValue resolveToolInputProperties(
			List<McpTypedTypeDescriptor.RecordComponent<T>> properties) {
		McpTypedTypeDescriptor.RecordValue<T> descriptor =
				new McpTypedTypeDescriptor.RecordValue<>(
						"\u0000soklet-annotated-tool-input",
						requireNonNull(properties));
		return (McpTypedSchemaShape.RecordValue) new Resolution().resolve(
				descriptor, McpTypedSchemaPath.root(), 1);
	}

	McpTypedSchemaShape resolveToolInput(T type) {
		requireNonNull(type);
		McpTypedTypeDescriptor<T> descriptor = describe(type,
				McpTypedSchemaPath.root());
		if (!(descriptor instanceof McpTypedTypeDescriptor.RecordValue<T>)
				&& !(descriptor instanceof McpTypedTypeDescriptor.MapValue<T>)
				&& !(descriptor instanceof McpTypedTypeDescriptor.Unsupported<T>)
				&& !(descriptor instanceof McpTypedTypeDescriptor.OptionalValue<T>))
			throw failure(McpTypedSchemaException.Reason.INPUT_ROOT_NOT_OBJECT,
					"A typed tool input root must be a record or Map<String, T>.",
					McpTypedSchemaPath.root());
		return new Resolution().resolve(descriptor,
				McpTypedSchemaPath.root(), 1);
	}

	McpTypedSchemaShape resolveToolOutput(T type) {
		requireNonNull(type);
		McpTypedTypeDescriptor<T> descriptor = describe(type,
				McpTypedSchemaPath.root());
		if (descriptor instanceof McpTypedTypeDescriptor.Scalar<T> scalar
				&& scalar.scalar() == McpTypedSchemaScalar.STRING)
			throw failure(McpTypedSchemaException.Reason.AMBIGUOUS_OUTPUT_STRING,
					"A bare typed String result is ambiguous.",
					McpTypedSchemaPath.root());
		return new Resolution().resolve(descriptor,
				McpTypedSchemaPath.root(), 1);
	}

	private McpTypedTypeDescriptor<T> describe(T type,
			McpTypedSchemaPath path) {
		McpTypedTypeDescriptor<T> descriptor;
		try {
			descriptor = typeModel.describe(requireNonNull(type));
		} catch (McpTypedSchemaException exception) {
			throw exception;
		} catch (McpTypedTypeModelLimitException exception) {
			String message = exception.getMessage();
			throw limit(exception.limit(), message == null
					? "The Java type adapter exceeded a configured limit."
					: message, path);
		} catch (RuntimeException | LinkageError exception) {
			throw failure(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
					"The Java type adapter could not describe the type.", path);
		}
		if (descriptor == null)
			throw failure(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
					"The Java type adapter returned no descriptor.", path);
		return descriptor;
	}

	private McpTypedSchemaException failure(
			McpTypedSchemaException.Reason reason, String message,
			McpTypedSchemaPath path) {
		return new McpTypedSchemaException(reason, message, path);
	}

	private McpTypedSchemaException limit(
			McpSchemaCompilationException.Limit limit, String message,
			McpTypedSchemaPath path) {
		return new McpTypedSchemaException(limit, message, path);
	}

	private final class Resolution {
		private final Map<String, Integer> activeRecordComplexities =
				new LinkedHashMap<>();
		private int nodeCount;

		private McpTypedSchemaShape resolve(T type, McpTypedSchemaPath path,
				int depth) {
			return resolve(describe(type, path), path, depth);
		}

		private McpTypedSchemaShape resolve(
				McpTypedTypeDescriptor<T> descriptor, McpTypedSchemaPath path,
				int depth) {
			checkDepth(depth, path);
			if (descriptor instanceof McpTypedTypeDescriptor.Unsupported<T> unsupported)
				throw failure(unsupported.reason(), messageFor(unsupported.reason()),
						path);
			if (descriptor instanceof McpTypedTypeDescriptor.OptionalValue<T>)
				throw failure(
						McpTypedSchemaException.Reason.OPTIONAL_OUTSIDE_PROPERTY,
						"Optional is supported only at an object-property boundary.",
						path);

			chargeNode(path);
			if (descriptor instanceof McpTypedTypeDescriptor.Scalar<T> scalar)
				return new McpTypedSchemaShape.Scalar(scalar.scalar());
			if (descriptor instanceof McpTypedTypeDescriptor.Enumeration<T> enumeration)
				return resolveEnumeration(enumeration, path);
			if (descriptor instanceof McpTypedTypeDescriptor.ArrayValue<T> array)
				return new McpTypedSchemaShape.ArrayValue(resolve(array.elementType(),
						path.arrayElement(), depth + 1));
			if (descriptor instanceof McpTypedTypeDescriptor.ListValue<T> list)
				return new McpTypedSchemaShape.ArrayValue(resolve(list.elementType(),
						path.arrayElement(), depth + 1));
			if (descriptor instanceof McpTypedTypeDescriptor.MapValue<T> map)
				return resolveMap(map, path, depth);
			if (descriptor instanceof McpTypedTypeDescriptor.RecordValue<T> record)
				return resolveRecord(record, path, depth);
			throw failure(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
					"The Java type adapter returned an unknown descriptor.", path);
		}

		private McpTypedSchemaShape resolveEnumeration(
				McpTypedTypeDescriptor.Enumeration<T> enumeration,
				McpTypedSchemaPath path) {
			checkCollectionSize(enumeration.constants().size(), path);
			Set<String> constants = new LinkedHashSet<>();
			for (String constant : enumeration.constants()) {
				if (!constants.add(constant))
					throw failure(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
							"Enum constant names must be unique.", path);
			}
			return new McpTypedSchemaShape.Enumeration(enumeration.constants());
		}

		private McpTypedSchemaShape resolveMap(
				McpTypedTypeDescriptor.MapValue<T> map, McpTypedSchemaPath path,
				int depth) {
			McpTypedTypeDescriptor<T> keyDescriptor = describe(map.keyType(), path);
			if (!(keyDescriptor instanceof McpTypedTypeDescriptor.Scalar<T> scalar)
					|| scalar.scalar() != McpTypedSchemaScalar.STRING)
				throw failure(McpTypedSchemaException.Reason.MAP_KEY_NOT_STRING,
						"A typed map must have exactly String keys.", path);
			return new McpTypedSchemaShape.MapValue(resolve(map.valueType(),
					path.mapValue(), depth + 1));
		}

		private McpTypedSchemaShape resolveRecord(
				McpTypedTypeDescriptor.RecordValue<T> record,
				McpTypedSchemaPath path, int depth) {
			if (record.declarationIdentity().isEmpty())
				throw failure(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
						"A record descriptor must have a declaration identity.", path);
			if (record.genericArgumentStructuralComplexity()
					> limits.maximumSchemaNodeCount())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
						"Generic-argument structural complexity exceeds its configured limit.",
						path);

			Integer previousComplexity = activeRecordComplexities.get(
					record.declarationIdentity());
			if (previousComplexity != null
					&& record.genericArgumentStructuralComplexity()
					>= previousComplexity)
				throw failure(McpTypedSchemaException.Reason.RECURSIVE_TYPE,
						"A repeated Java record declaration must have strictly decreasing generic-argument structural complexity.",
						path);
			activeRecordComplexities.put(record.declarationIdentity(),
					record.genericArgumentStructuralComplexity());
			try {
				checkCollectionSize(record.components().size(), path);
				checkCollectionSize(record.screeningOnlyGenericArguments().size(), path);
				for (int index = 0;
						index < record.screeningOnlyGenericArguments().size(); ++index)
					resolve(record.screeningOnlyGenericArguments().get(index),
							path.genericArgument(index), depth + 1);
				Set<String> names = new LinkedHashSet<>();
				for (McpTypedTypeDescriptor.RecordComponent<T> component
						: record.components()) {
					McpTypedSchemaPath componentPath =
							path.property(component.name());
					checkName(component.name(), componentPath);
					component.headerName().ifPresent(header -> checkName(header,
							componentPath));
					if (!names.add(component.name()))
						throw failure(
								McpTypedSchemaException.Reason.DUPLICATE_PROPERTY,
								"Record property names must be unique.",
								componentPath);
				}

				List<McpTypedSchemaShape.Property> properties =
						new ArrayList<>(record.components().size());
				for (McpTypedTypeDescriptor.RecordComponent<T> component
						: record.components()) {
					McpTypedSchemaPath propertyPath = path.property(component.name());
					McpTypedSchemaPath valuePath = propertyPath;
					McpTypedTypeDescriptor<T> componentDescriptor =
							describe(component.type(), propertyPath);
					boolean required = true;
					T valueType = component.type();
					if (componentDescriptor
							instanceof McpTypedTypeDescriptor.OptionalValue<T> optional) {
						required = false;
						valueType = optional.valueType();
						valuePath = propertyPath.optionalValue();
						componentDescriptor = describe(valueType, valuePath);
					}
					McpTypedSchemaShape componentShape = resolve(
							componentDescriptor, valuePath, depth + 1);
					properties.add(new McpTypedSchemaShape.Property(
							component.name(), componentShape, required,
							component.title(), component.description(),
							component.headerName()));
				}
				return new McpTypedSchemaShape.RecordValue(properties);
			} finally {
				if (previousComplexity == null)
					activeRecordComplexities.remove(record.declarationIdentity());
				else
					activeRecordComplexities.put(record.declarationIdentity(),
							previousComplexity);
			}
		}

		private void chargeNode(McpTypedSchemaPath path) {
			if (nodeCount >= limits.maximumSchemaNodeCount())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
						"Typed schema node count exceeds its configured limit.", path);
			nodeCount++;
		}

		private void checkDepth(int depth, McpTypedSchemaPath path) {
			if (depth > limits.maximumSchemaDepth())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
						"Typed schema depth exceeds its configured limit.", path);
		}

		private void checkCollectionSize(int size, McpTypedSchemaPath path) {
			if (size > limits.maximumCollectionEntryCount())
				throw limit(
						McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
						"Typed schema collection width exceeds its configured limit.",
						path);
		}

		private void checkName(String name, McpTypedSchemaPath path) {
			if (name.isEmpty())
				throw failure(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
						"A typed schema property name must not be empty.", path);
			if (name.length() > limits.maximumNameLengthInCharacters())
				throw limit(McpSchemaCompilationException.Limit.NAME_LENGTH,
						"A typed schema name exceeds its configured limit.", path);
		}
	}

	private static String messageFor(McpTypedSchemaException.Reason reason) {
		return switch (reason) {
			case RAW_GENERIC -> "Raw generic Java types are not supported.";
			case WILDCARD -> "Wildcard Java types are not supported.";
			case UNRESOLVED_TYPE_VARIABLE ->
					"Unresolved Java type variables are not supported.";
			case UNRESOLVED_GENERIC_ARRAY_COMPONENT ->
					"A generic array component must be fully resolved.";
			case OBJECT_TYPE -> "Object is not a supported typed schema type.";
			case CHAR_SEQUENCE_TYPE ->
					"Only String is a supported character-sequence type.";
			case FRAMEWORK_TYPE ->
					"Framework JSON, result, payload, and content types are not supported in typed schemas.";
			case OPTIONAL_OUTSIDE_PROPERTY ->
					"Optional is supported only at an object-property boundary.";
			case UNSUPPORTED_TYPE -> "The Java type is not supported.";
			default -> "The Java type descriptor is invalid for this use.";
		};
	}
}
