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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Explicit per-invocation bounds for schema evaluation.
 *
 * <p>Static references consume the reference-traversal budget. Pending work
 * has a separate allocation-first bound so branching reference cycles cannot
 * grow evaluator work until memory exhaustion.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpSchemaEvaluationLimits(long maximumEvaluationOperations,
		long maximumReferenceTraversals, int maximumPendingTaskCount,
		int maximumDiagnosticCount, int maximumDiagnosticUtf8Bytes) {
	private static final long MAXIMUM_SUPPORTED_EVALUATION_OPERATIONS =
			10_000_000;
	private static final long MAXIMUM_SUPPORTED_REFERENCE_TRAVERSALS =
			1_000_000;
	private static final int MAXIMUM_SUPPORTED_PENDING_TASK_COUNT = 256;
	private static final int MAXIMUM_SUPPORTED_DIAGNOSTIC_COUNT = 1_000;
	private static final int MAXIMUM_SUPPORTED_DIAGNOSTIC_UTF8_BYTES =
			1_024 * 1_024;
	@NonNull
	private static final McpSchemaEvaluationLimits PRODUCTION_DEFAULTS =
			new McpSchemaEvaluationLimits(1_000_000, 100_000, 128, 100,
					64 * 1_024);
	@NonNull
	private static final McpSchemaEvaluationLimits MAXIMUM_SUPPORTED =
			new McpSchemaEvaluationLimits(
					MAXIMUM_SUPPORTED_EVALUATION_OPERATIONS,
					MAXIMUM_SUPPORTED_REFERENCE_TRAVERSALS,
					MAXIMUM_SUPPORTED_PENDING_TASK_COUNT,
					MAXIMUM_SUPPORTED_DIAGNOSTIC_COUNT,
					MAXIMUM_SUPPORTED_DIAGNOSTIC_UTF8_BYTES);

	McpSchemaEvaluationLimits {
		requirePositive(maximumEvaluationOperations,
				"maximumEvaluationOperations");
		requirePositive(maximumReferenceTraversals,
				"maximumReferenceTraversals");
		requirePositive(maximumPendingTaskCount, "maximumPendingTaskCount");
		requirePositive(maximumDiagnosticCount, "maximumDiagnosticCount");
		requirePositive(maximumDiagnosticUtf8Bytes,
				"maximumDiagnosticUtf8Bytes");
		requireAtMost(maximumEvaluationOperations,
				MAXIMUM_SUPPORTED_EVALUATION_OPERATIONS,
				"maximumEvaluationOperations");
		requireAtMost(maximumReferenceTraversals,
				MAXIMUM_SUPPORTED_REFERENCE_TRAVERSALS,
				"maximumReferenceTraversals");
		requireAtMost(maximumPendingTaskCount,
				MAXIMUM_SUPPORTED_PENDING_TASK_COUNT,
				"maximumPendingTaskCount");
		requireAtMost(maximumDiagnosticCount,
				MAXIMUM_SUPPORTED_DIAGNOSTIC_COUNT,
				"maximumDiagnosticCount");
		requireAtMost(maximumDiagnosticUtf8Bytes,
				MAXIMUM_SUPPORTED_DIAGNOSTIC_UTF8_BYTES,
				"maximumDiagnosticUtf8Bytes");
	}

	@NonNull
	static McpSchemaEvaluationLimits productionDefaults() {
		return PRODUCTION_DEFAULTS;
	}

	@NonNull
	static McpSchemaEvaluationLimits maximumSupported() {
		return MAXIMUM_SUPPORTED;
	}

	private static void requirePositive(long value, @NonNull String name) {
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive.");
	}

	private static void requireAtMost(long value, long maximum,
			@NonNull String name) {
		if (value > maximum)
			throw new IllegalArgumentException(name + " must not exceed "
					+ maximum + ".");
	}
}
