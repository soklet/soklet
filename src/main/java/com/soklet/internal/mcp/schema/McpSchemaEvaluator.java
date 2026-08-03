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

import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Stateless bounded work-stack evaluator for the currently supported
 * conjunctive validation program.
 */
final class McpSchemaEvaluator {
	private static final String FALSE_SCHEMA_MESSAGE =
			"The instance is rejected by a false schema.";
	private static final String TYPE_MESSAGE =
			"The instance does not match the required JSON type.";
	private static final String CONST_MESSAGE =
			"The instance does not equal the const value.";
	private static final String ENUM_MESSAGE =
			"The instance does not equal any enum value.";
	private static final String REQUIRED_MESSAGE =
			"A required object property is absent.";

	private final McpSchemaJsonEquality equality;

	McpSchemaEvaluator() {
		this.equality = new McpSchemaJsonEquality();
	}

	McpSchemaValidationOutcome evaluate(McpSchemaValidationProgram program,
			McpSchemaNodeId rootNodeId, McpJsonValue instance,
			McpSchemaEvaluationLimits limits) {
		requireNonNull(program);
		requireNonNull(rootNodeId);
		requireNonNull(instance);
		McpSchemaEvaluationContext context =
				new McpSchemaEvaluationContext(requireNonNull(limits));
		Deque<EvaluationTask> pending = new ArrayDeque<>();
		pending.push(new EvaluationTask(rootNodeId, instance, List.of()));
		boolean valid = true;

		while (!pending.isEmpty()) {
			EvaluationTask task = pending.pop();
			McpCompiledValidationNode node = program.node(task.nodeId());
			if (!context.chargeEvaluationOperation())
				return limitExceeded(context,
						McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);

			if (node.booleanSchema().isPresent()) {
				if (node.booleanSchema().get() == McpJsonBoolean.FALSE) {
					valid = false;
					context.addDiagnostic(diagnostic(node,
							McpSchemaDiagnostic.Code.FALSE_SCHEMA,
							Optional.empty(), task.instancePointerSegments(),
							FALSE_SCHEMA_MESSAGE));
				}
				continue;
			}

			if (!node.acceptedTypes().isEmpty()) {
				if (!context.chargeEvaluationOperation())
					return limitExceeded(context,
							McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
				boolean matches = node.acceptedTypes().stream()
						.anyMatch(type -> type.matches(task.instance()));
				if (!matches) {
					valid = false;
					context.addDiagnostic(diagnostic(node,
							McpSchemaDiagnostic.Code.TYPE_MISMATCH,
							Optional.of("type"), task.instancePointerSegments(),
							TYPE_MESSAGE));
				}
			}

			if (node.constant().isPresent()) {
				if (!context.chargeEvaluationOperation())
					return limitExceeded(context,
							McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
				McpSchemaJsonEquality.Result result = equality.compare(
						node.constant().get(), task.instance(), context);
				if (result == McpSchemaJsonEquality.Result.LIMIT_EXCEEDED)
					return limitExceeded(context,
							McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
				if (result == McpSchemaJsonEquality.Result.NOT_EQUAL) {
					valid = false;
					context.addDiagnostic(diagnostic(node,
							McpSchemaDiagnostic.Code.CONST_MISMATCH,
							Optional.of("const"), task.instancePointerSegments(),
							CONST_MESSAGE));
				}
			}

			if (node.enumeration().isPresent()) {
				if (!context.chargeEvaluationOperation())
					return limitExceeded(context,
							McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
				boolean matched = false;
				for (McpJsonValue candidate : node.enumeration().get()) {
					McpSchemaJsonEquality.Result result = equality.compare(candidate,
							task.instance(), context);
					if (result == McpSchemaJsonEquality.Result.LIMIT_EXCEEDED)
						return limitExceeded(context,
								McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
					if (result == McpSchemaJsonEquality.Result.EQUAL) {
						matched = true;
						break;
					}
				}
				if (!matched) {
					valid = false;
					context.addDiagnostic(diagnostic(node,
							McpSchemaDiagnostic.Code.ENUM_MISMATCH,
							Optional.of("enum"), task.instancePointerSegments(),
							ENUM_MESSAGE));
				}
			}

			if (!node.requiredProperties().isEmpty()) {
				if (!context.chargeEvaluationOperation())
					return limitExceeded(context,
							McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
				if (task.instance() instanceof McpJsonObject object) {
					if (!context.chargeEvaluationOperations(
							node.requiredProperties().size()))
						return limitExceeded(context,
								McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
					for (String property : node.requiredProperties()) {
						if (object.members().containsKey(property))
							continue;
						valid = false;
						context.addDiagnostic(diagnostic(node,
								McpSchemaDiagnostic.Code.REQUIRED_PROPERTY_MISSING,
								Optional.of("required"),
								Optional.of(property),
								task.instancePointerSegments(), REQUIRED_MESSAGE));
					}
				}
			}

			if (node.referenceTarget().isPresent()) {
				if (!context.chargeEvaluationOperation())
					return limitExceeded(context,
							McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
				if (!context.chargeReferenceTraversal())
					return limitExceeded(context,
							McpSchemaEvaluationLimit.REFERENCE_TRAVERSALS);
				if (!canSchedule(pending, 1, limits))
					return limitExceeded(context,
							McpSchemaEvaluationLimit.PENDING_TASKS);
				pending.push(new EvaluationTask(node.referenceTarget().get(),
						task.instance(), task.instancePointerSegments()));
			}

			if (!node.allOfSchemas().isEmpty()) {
				if (!context.chargeEvaluationOperation())
					return limitExceeded(context,
							McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
				if (!context.chargeEvaluationOperations(node.allOfSchemas().size()))
					return limitExceeded(context,
							McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
				if (!canSchedule(pending, node.allOfSchemas().size(), limits))
					return limitExceeded(context,
							McpSchemaEvaluationLimit.PENDING_TASKS);
				for (int index = node.allOfSchemas().size() - 1;
						index >= 0; --index)
					pending.push(new EvaluationTask(node.allOfSchemas().get(index),
							task.instance(), task.instancePointerSegments()));
			}

			if (!node.propertySchemas().isEmpty()) {
				if (!context.chargeEvaluationOperation())
					return limitExceeded(context,
							McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
				if (task.instance() instanceof McpJsonObject object) {
					if (!context.chargeEvaluationOperations(
							node.propertySchemas().size()))
						return limitExceeded(context,
								McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
					int presentPropertyCount = 0;
					for (String property : node.propertySchemas().keySet()) {
						if (object.members().containsKey(property))
							presentPropertyCount++;
					}
					if (!canSchedule(pending, presentPropertyCount, limits))
						return limitExceeded(context,
								McpSchemaEvaluationLimit.PENDING_TASKS);
					List<Map.Entry<String, McpSchemaNodeId>> properties =
							new ArrayList<>(presentPropertyCount);
					for (Map.Entry<String, McpSchemaNodeId> property
							: node.propertySchemas().entrySet()) {
						if (object.members().containsKey(property.getKey()))
							properties.add(property);
					}
					for (int index = properties.size() - 1; index >= 0; --index) {
						Map.Entry<String, McpSchemaNodeId> property =
								properties.get(index);
						McpJsonValue propertyValue = requireNonNull(
								object.members().get(property.getKey()));
						pending.push(new EvaluationTask(property.getValue(),
								propertyValue, append(
									task.instancePointerSegments(),
									property.getKey())));
					}
				}
			}
		}

		return outcome(context, valid);
	}

	private McpSchemaDiagnostic diagnostic(McpCompiledValidationNode node,
			McpSchemaDiagnostic.Code code, Optional<String> keyword,
			List<String> instancePointerSegments, String message) {
		return diagnostic(node, code, keyword, Optional.empty(),
				instancePointerSegments, message);
	}

	private McpSchemaDiagnostic diagnostic(McpCompiledValidationNode node,
			McpSchemaDiagnostic.Code code, Optional<String> keyword,
			Optional<String> missingPropertyName,
			List<String> instancePointerSegments, String message) {
		return new McpSchemaDiagnostic(code, node.location(), keyword,
				missingPropertyName, instancePointerSegments, message);
	}

	private List<String> append(List<String> source, String segment) {
		List<String> result = new ArrayList<>(source.size() + 1);
		result.addAll(source);
		result.add(segment);
		return List.copyOf(result);
	}

	private McpSchemaValidationOutcome outcome(
			McpSchemaEvaluationContext context, boolean valid) {
		if (valid)
			return new McpSchemaValidationOutcome.Valid(
					context.evaluationOperations());
		return new McpSchemaValidationOutcome.Invalid(context.diagnostics(),
				context.diagnosticsTruncated(), context.evaluationOperations());
	}

	private McpSchemaValidationOutcome limitExceeded(
			McpSchemaEvaluationContext context,
			McpSchemaEvaluationLimit limit) {
		return new McpSchemaValidationOutcome.LimitExceeded(
				requireNonNull(limit),
				context.evaluationOperations());
	}

	private boolean canSchedule(Deque<EvaluationTask> pending, int taskCount,
			McpSchemaEvaluationLimits limits) {
		if (taskCount < 0)
			throw new IllegalArgumentException("taskCount must not be negative.");
		return taskCount <= limits.maximumPendingTaskCount() - pending.size();
	}

	private record EvaluationTask(McpSchemaNodeId nodeId,
			McpJsonValue instance, List<String> instancePointerSegments) {
		private EvaluationTask {
			requireNonNull(nodeId);
			requireNonNull(instance);
			instancePointerSegments = List.copyOf(
					requireNonNull(instancePointerSegments));
		}
	}
}
