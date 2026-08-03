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

/**
 * Explicit per-invocation bounds for schema evaluation.
 *
 * <p>Static references consume the reference-traversal budget. Pending work
 * has a separate allocation-first bound so branching reference cycles cannot
 * grow the evaluator stack until memory exhaustion. The dynamic-scope limit
 * is reserved for the later dynamic-reference slice. Matcher steps remain a
 * separate future matcher-owned budget.</p>
 */
record McpSchemaEvaluationLimits(long maximumEvaluationOperations,
		long maximumReferenceTraversals, int maximumPendingTaskCount,
		int maximumDynamicScopeDepth,
		int maximumDiagnosticCount, int maximumDiagnosticUtf8Bytes) {
	McpSchemaEvaluationLimits {
		requirePositive(maximumEvaluationOperations,
				"maximumEvaluationOperations");
		requirePositive(maximumReferenceTraversals,
				"maximumReferenceTraversals");
		requirePositive(maximumPendingTaskCount, "maximumPendingTaskCount");
		requirePositive(maximumDynamicScopeDepth,
				"maximumDynamicScopeDepth");
		requirePositive(maximumDiagnosticCount, "maximumDiagnosticCount");
		requirePositive(maximumDiagnosticUtf8Bytes,
				"maximumDiagnosticUtf8Bytes");
	}

	private static void requirePositive(long value, String name) {
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive.");
	}
}
