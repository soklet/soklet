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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Stable typed binding failure which never retains or reflects an instance
 * value or an application exception.
 */
final class McpTypedJsonBindingException extends IllegalArgumentException {
	enum Operation {
		COMPILE,
		FROM_JSON,
		TO_JSON
	}

	enum Reason {
		SHAPE_MISMATCH,
		REFLECTION_ACCESS,
		JSON_TYPE_MISMATCH,
		NULL_VALUE,
		REQUIRED_PROPERTY_MISSING,
		UNKNOWN_PROPERTY,
		ENUM_CONSTANT_MISMATCH,
		NON_INTEGER_NUMBER,
		NUMBER_OUT_OF_RANGE,
		NON_FINITE_NUMBER,
		JAVA_TYPE_MISMATCH,
		CONTAINER_ACCESS_FAILED,
		CONTAINER_MUTATED,
		CYCLIC_VALUE,
		TYPE_METADATA_CYCLE,
		LIMIT_EXCEEDED,
		RECORD_CONSTRUCTION_FAILED,
		RECORD_ACCESSOR_FAILED
	}

	enum Limit {
		NODE_COUNT,
		NESTING_DEPTH,
		CONTAINER_ENTRY_COUNT
	}

	private final Operation operation;
	private final Reason reason;
	private final McpTypedSchemaPath path;
	private final Optional<Limit> limit;

	McpTypedJsonBindingException(Operation operation, Reason reason,
			McpTypedSchemaPath path) {
		this(operation, reason, path, Optional.empty());
	}

	McpTypedJsonBindingException(Operation operation, Limit limit,
			McpTypedSchemaPath path) {
		this(operation, Reason.LIMIT_EXCEEDED, path,
				Optional.of(requireNonNull(limit)));
	}

	private McpTypedJsonBindingException(Operation operation, Reason reason,
			McpTypedSchemaPath path, Optional<Limit> limit) {
		super(messageFor(requireNonNull(reason)));
		this.operation = requireNonNull(operation);
		this.reason = reason;
		this.path = requireNonNull(path);
		this.limit = requireNonNull(limit);
	}

	Operation operation() {
		return operation;
	}

	Reason reason() {
		return reason;
	}

	McpTypedSchemaPath path() {
		return path;
	}

	Optional<Limit> limit() {
		return limit;
	}

	private static String messageFor(Reason reason) {
		return switch (reason) {
			case SHAPE_MISMATCH ->
					"The Java binding does not match the resolved typed schema shape.";
			case REFLECTION_ACCESS ->
					"The Java binding cannot access required record mechanics.";
			case JSON_TYPE_MISMATCH ->
					"The JSON value does not have the required type.";
			case NULL_VALUE -> "Null is not supported by this typed binding.";
			case REQUIRED_PROPERTY_MISSING ->
					"A required record property is missing.";
			case UNKNOWN_PROPERTY ->
					"A closed record contains an unknown property.";
			case ENUM_CONSTANT_MISMATCH ->
					"The value is not a declared enum constant name.";
			case NON_INTEGER_NUMBER ->
					"The JSON number is not mathematically integral.";
			case NUMBER_OUT_OF_RANGE ->
					"The number is outside the declared Java type's range.";
			case NON_FINITE_NUMBER ->
					"NaN and infinite numbers cannot be represented as JSON.";
			case JAVA_TYPE_MISMATCH ->
					"The Java value does not have the declared type.";
			case CONTAINER_ACCESS_FAILED ->
					"The Java container could not be read.";
			case CONTAINER_MUTATED ->
					"The Java container changed during conversion.";
			case CYCLIC_VALUE ->
					"A cyclic Java container graph is not supported.";
			case TYPE_METADATA_CYCLE ->
					"The Java type metadata contains an identity cycle.";
			case LIMIT_EXCEEDED ->
					"The typed JSON binding exceeded a configured limit.";
			case RECORD_CONSTRUCTION_FAILED ->
					"The record could not be constructed.";
			case RECORD_ACCESSOR_FAILED ->
					"A record component could not be read.";
		};
	}
}
