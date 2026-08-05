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
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Stateless bounded evaluator for Soklet MCP Tool Schema Profile 1.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpToolSchemaProfileEvaluator {
	@NonNull
	private static final String FALSE_SCHEMA_MESSAGE =
			"The instance is rejected by a false schema.";
	@NonNull
	private static final String TYPE_MESSAGE =
			"The instance does not match the required JSON type.";
	@NonNull
	private static final String CONST_MESSAGE =
			"The instance does not equal the const value.";
	@NonNull
	private static final String ENUM_MESSAGE =
			"The instance does not equal any enum value.";
	@NonNull
	private static final String REQUIRED_MESSAGE =
			"A required object property is absent.";
	@NonNull
	private static final String MINIMUM_MESSAGE =
			"The number is less than the inclusive minimum.";
	@NonNull
	private static final String MAXIMUM_MESSAGE =
			"The number is greater than the inclusive maximum.";
	@NonNull
	private static final String ANY_OF_MESSAGE =
			"The instance does not satisfy any anyOf branch.";

	@NonNull
	McpSchemaValidationOutcome evaluate(
			@NonNull McpToolSchemaProfileProgram program,
			@NonNull McpJsonValue instance,
			@NonNull McpSchemaEvaluationLimits limits) {
		requireNonNull(program);
		requireNonNull(instance);
		Evaluation evaluation = new Evaluation(program, requireNonNull(limits));
		try {
			boolean valid = evaluation.evaluateNode(program.rootNodeId(), instance,
					List.of(), true);
			if (valid)
				return new McpSchemaValidationOutcome.Valid(
						evaluation.context.evaluationOperations());
			return new McpSchemaValidationOutcome.Invalid(
					evaluation.context.diagnostics(),
					evaluation.context.diagnosticsTruncated(),
					evaluation.context.evaluationOperations());
		} catch (LimitReached exception) {
			return new McpSchemaValidationOutcome.LimitExceeded(exception.limit,
					evaluation.context.evaluationOperations());
		}
	}

	@NotThreadSafe
	private static final class Evaluation {
		@NonNull
		private final McpToolSchemaProfileProgram program;
		@NonNull
		private final McpSchemaEvaluationLimits limits;
		@NonNull
		private final McpSchemaEvaluationContext context;
		@NonNull
		private final McpSchemaJsonEquality equality;
		@NonNull
		private final IdentityHashMap<@NonNull McpJsonValue, @NonNull Set<@NonNull Integer>> activePairs;
		private int activeCallCount;

		private Evaluation(@NonNull McpToolSchemaProfileProgram program,
				@NonNull McpSchemaEvaluationLimits limits) {
			this.program = program;
			this.limits = limits;
			this.context = new McpSchemaEvaluationContext(limits);
			this.equality = new McpSchemaJsonEquality();
			this.activePairs = new IdentityHashMap<>();
		}

		private boolean evaluateNode(@NonNull McpSchemaNodeId nodeId,
				@NonNull McpJsonValue instance,
				@NonNull List<@NonNull String> instancePointer,
				boolean reportDiagnostics) {
			if (activeCallCount >= limits.maximumPendingTaskCount())
				throw new LimitReached(McpSchemaEvaluationLimit.PENDING_TASKS);
			Set<Integer> activeNodes = activePairs.computeIfAbsent(instance,
					ignored -> new LinkedHashSet<>());
			if (!activeNodes.add(nodeId.value()))
				throw new LimitReached(
						McpSchemaEvaluationLimit.REFERENCE_TRAVERSALS);
			activeCallCount++;
			try {
				return evaluateActiveNode(program.node(nodeId), instance,
						instancePointer, reportDiagnostics);
			} finally {
				activeCallCount--;
				activeNodes.remove(nodeId.value());
				if (activeNodes.isEmpty())
					activePairs.remove(instance);
			}
		}

		private boolean evaluateActiveNode(
				@NonNull McpToolSchemaProfileNode node,
				@NonNull McpJsonValue instance,
				@NonNull List<@NonNull String> instancePointer,
				boolean reportDiagnostics) {
			chargeOperation();
			if (node.booleanSchema().isPresent()) {
				boolean valid = node.booleanSchema().get() == McpJsonBoolean.TRUE;
				if (!valid && reportDiagnostics)
					addDiagnostic(node, McpSchemaDiagnostic.Code.FALSE_SCHEMA,
							Optional.empty(), Optional.empty(), instancePointer,
							FALSE_SCHEMA_MESSAGE);
				return valid;
			}

			boolean valid = true;
			if (!node.acceptedTypes().isEmpty()) {
				chargeOperation();
				boolean matches = node.acceptedTypes().stream()
						.anyMatch(type -> type.matches(instance));
				if (!matches) {
					valid = false;
					if (reportDiagnostics)
						addDiagnostic(node, McpSchemaDiagnostic.Code.TYPE_MISMATCH,
								Optional.of("type"), Optional.empty(),
								instancePointer, TYPE_MESSAGE);
				}
			}

			if (node.constant().isPresent()
					&& !equalsJson(node.constant().get(), instance)) {
				valid = false;
				if (reportDiagnostics)
					addDiagnostic(node, McpSchemaDiagnostic.Code.CONST_MISMATCH,
							Optional.of("const"), Optional.empty(), instancePointer,
							CONST_MESSAGE);
			}

			if (node.enumeration().isPresent()) {
				chargeOperation();
				boolean matched = false;
				for (McpJsonValue candidate : node.enumeration().get()) {
					if (equalsJson(candidate, instance)) {
						matched = true;
						break;
					}
				}
				if (!matched) {
					valid = false;
					if (reportDiagnostics)
						addDiagnostic(node, McpSchemaDiagnostic.Code.ENUM_MISMATCH,
								Optional.of("enum"), Optional.empty(), instancePointer,
								ENUM_MESSAGE);
				}
			}

			if (instance instanceof McpJsonNumber number) {
				if (node.minimum().isPresent()) {
					chargeOperation();
					if (number.value().compareTo(node.minimum().get()) < 0) {
						valid = false;
						if (reportDiagnostics)
							addDiagnostic(node,
									McpSchemaDiagnostic.Code.MINIMUM_MISMATCH,
									Optional.of("minimum"), Optional.empty(),
									instancePointer, MINIMUM_MESSAGE);
					}
				}
				if (node.maximum().isPresent()) {
					chargeOperation();
					if (number.value().compareTo(node.maximum().get()) > 0) {
						valid = false;
						if (reportDiagnostics)
							addDiagnostic(node,
									McpSchemaDiagnostic.Code.MAXIMUM_MISMATCH,
									Optional.of("maximum"), Optional.empty(),
									instancePointer, MAXIMUM_MESSAGE);
					}
				}
			}

			if (instance instanceof McpJsonObject object) {
				valid &= evaluateRequired(node, object, instancePointer,
						reportDiagnostics);
			}

			if (node.referenceTarget().isPresent()) {
				if (!context.chargeReferenceTraversal())
					throw new LimitReached(
							McpSchemaEvaluationLimit.REFERENCE_TRAVERSALS);
				valid &= evaluateNode(node.referenceTarget().get(), instance,
						instancePointer, reportDiagnostics);
			}

			for (McpSchemaNodeId child : node.allOfSchemas())
				valid &= evaluateNode(child, instance, instancePointer,
						reportDiagnostics);

			if (!node.anyOfSchemas().isEmpty()) {
				boolean anyValid = false;
				for (McpSchemaNodeId child : node.anyOfSchemas()) {
					if (evaluateNode(child, instance, instancePointer, false)) {
						anyValid = true;
						break;
					}
				}
				if (!anyValid) {
					valid = false;
					if (reportDiagnostics)
						addDiagnostic(node, McpSchemaDiagnostic.Code.ANY_OF_MISMATCH,
								Optional.of("anyOf"), Optional.empty(), instancePointer,
								ANY_OF_MESSAGE);
				}
			}

			if (node.ifSchema().isPresent()) {
				boolean condition = evaluateNode(node.ifSchema().get(), instance,
						instancePointer, false);
				Optional<McpSchemaNodeId> branch = condition ? node.thenSchema()
						: node.elseSchema();
				if (branch.isPresent())
					valid &= evaluateNode(branch.get(), instance, instancePointer,
							reportDiagnostics);
			}

			if (instance instanceof McpJsonObject object)
				valid &= evaluateObjectChildren(node, object, instancePointer,
						reportDiagnostics);
			if (instance instanceof McpJsonArray array
					&& node.itemSchema().isPresent()) {
				for (int index = 0; index < array.values().size(); ++index) {
					valid &= evaluateNode(node.itemSchema().get(),
							array.values().get(index), append(instancePointer,
									Integer.toString(index)), reportDiagnostics);
				}
			}

			return valid;
		}

		private boolean evaluateRequired(@NonNull McpToolSchemaProfileNode node,
				@NonNull McpJsonObject object,
				@NonNull List<@NonNull String> instancePointer,
				boolean reportDiagnostics) {
			boolean valid = true;
			for (String property : node.requiredProperties()) {
				chargeOperation();
				if (object.members().containsKey(property))
					continue;
				valid = false;
				if (reportDiagnostics)
					addDiagnostic(node,
							McpSchemaDiagnostic.Code.REQUIRED_PROPERTY_MISSING,
							Optional.of("required"), Optional.of(property),
							instancePointer, REQUIRED_MESSAGE);
			}
			return valid;
		}

		private boolean evaluateObjectChildren(
				@NonNull McpToolSchemaProfileNode node,
				@NonNull McpJsonObject object,
				@NonNull List<@NonNull String> instancePointer,
				boolean reportDiagnostics) {
			boolean valid = true;
			chargeOperations(node.propertySchemas().size());
			for (Map.Entry<String, McpSchemaNodeId> property
					: node.propertySchemas().entrySet()) {
				McpJsonValue value = object.members().get(property.getKey());
				if (value != null)
					valid &= evaluateNode(property.getValue(), value,
							append(instancePointer, property.getKey()),
							reportDiagnostics);
			}
			if (node.additionalPropertiesSchema().isEmpty())
				return valid;
			int objectSize = object.members().size();
			chargeOperations((long) objectSize
					+ estimatedSortingOperations(objectSize));
			List<Map.Entry<String, McpJsonValue>> entries =
					new ArrayList<>(objectSize);
			for (Map.Entry<String, McpJsonValue> entry
					: object.members().entrySet()) {
				if (!node.propertySchemas().containsKey(entry.getKey()))
					entries.add(entry);
			}
			entries.sort(Map.Entry.comparingByKey());
			for (Map.Entry<String, McpJsonValue> entry : entries)
				valid &= evaluateNode(node.additionalPropertiesSchema().get(),
						entry.getValue(), append(instancePointer, entry.getKey()),
						reportDiagnostics);
			return valid;
		}

		private boolean equalsJson(@NonNull McpJsonValue expected,
				@NonNull McpJsonValue actual) {
			chargeOperation();
			McpSchemaJsonEquality.Result result = equality.compare(expected, actual,
					context);
			if (result == McpSchemaJsonEquality.Result.LIMIT_EXCEEDED)
				throw new LimitReached(
						McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
			return result == McpSchemaJsonEquality.Result.EQUAL;
		}

		private void chargeOperation() {
			chargeOperations(1);
		}

		private void chargeOperations(long count) {
			if (!context.chargeEvaluationOperations(count))
				throw new LimitReached(
						McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
		}

		private long estimatedSortingOperations(int size) {
			if (size <= 1)
				return 0;
			int passes = Integer.SIZE
					- Integer.numberOfLeadingZeros(size - 1);
			return (long) size * passes;
		}

		private void addDiagnostic(@NonNull McpToolSchemaProfileNode node,
				McpSchemaDiagnostic.@NonNull Code code,
				@NonNull Optional<@NonNull String> keyword,
				@NonNull Optional<@NonNull String> missingPropertyName,
				@NonNull List<@NonNull String> instancePointer,
				@NonNull String message) {
			context.addDiagnostic(code, node.location(), keyword,
					missingPropertyName, instancePointer, message);
		}

		@NonNull
		private List<@NonNull String> append(
				@NonNull List<@NonNull String> source,
				@NonNull String segment) {
			chargeOperations((long) source.size() + 1);
			List<String> result = new ArrayList<>(source.size() + 1);
			result.addAll(source);
			result.add(segment);
			return List.copyOf(result);
		}
	}

	@NotThreadSafe
	private static final class LimitReached extends RuntimeException {
		@NonNull
		private final McpSchemaEvaluationLimit limit;

		private LimitReached(@NonNull McpSchemaEvaluationLimit limit) {
			super(null, null, false, false);
			this.limit = requireNonNull(limit);
		}
	}
}
