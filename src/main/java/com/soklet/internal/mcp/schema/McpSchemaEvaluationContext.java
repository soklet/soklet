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
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Per-call mutable state for the otherwise stateless schema evaluator.
 */
final class McpSchemaEvaluationContext {
	private final McpSchemaEvaluationLimits limits;
	private final List<McpSchemaDiagnostic> diagnostics;
	private long evaluationOperations;
	private long referenceTraversals;
	private int diagnosticUtf8Bytes;
	private boolean diagnosticsTruncated;

	McpSchemaEvaluationContext(McpSchemaEvaluationLimits limits) {
		this.limits = requireNonNull(limits);
		this.diagnostics = new ArrayList<>(Math.min(
				limits.maximumDiagnosticCount(), 16));
	}

	boolean chargeEvaluationOperation() {
		return chargeEvaluationOperations(1);
	}

	boolean chargeEvaluationOperations(long count) {
		if (count < 0)
			throw new IllegalArgumentException("count must not be negative.");
		long remaining = limits.maximumEvaluationOperations()
				- evaluationOperations;
		if (count > remaining)
			return false;
		evaluationOperations += count;
		return true;
	}

	boolean chargeReferenceTraversal() {
		if (referenceTraversals >= limits.maximumReferenceTraversals())
			return false;
		referenceTraversals++;
		return true;
	}

	void addDiagnostic(McpSchemaDiagnostic.Code code,
			McpSchemaLocation schemaLocation, Optional<String> keyword,
			Optional<String> missingPropertyName,
			List<String> instancePointerSegments, String message) {
		if (diagnosticsTruncated)
			return;
		if (diagnostics.size() >= limits.maximumDiagnosticCount()) {
			diagnosticsTruncated = true;
			return;
		}
		int remainingBytes = limits.maximumDiagnosticUtf8Bytes()
				- diagnosticUtf8Bytes;
		long bytes = McpSchemaDiagnostic.utf8ByteCountUpTo(code, schemaLocation,
				keyword, missingPropertyName, instancePointerSegments, message,
				remainingBytes);
		if (bytes > remainingBytes) {
			diagnosticsTruncated = true;
			return;
		}
		diagnostics.add(new McpSchemaDiagnostic(code, schemaLocation, keyword,
				missingPropertyName, instancePointerSegments, message));
		diagnosticUtf8Bytes += (int) bytes;
	}

	long evaluationOperations() {
		return evaluationOperations;
	}

	List<McpSchemaDiagnostic> diagnostics() {
		return List.copyOf(diagnostics);
	}

	boolean diagnosticsTruncated() {
		return diagnosticsTruncated;
	}
}
