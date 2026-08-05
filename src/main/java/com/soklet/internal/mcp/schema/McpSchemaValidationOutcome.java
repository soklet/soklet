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
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Typed result of a bounded schema evaluation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
sealed interface McpSchemaValidationOutcome permits
		McpSchemaValidationOutcome.Valid,
		McpSchemaValidationOutcome.Invalid,
		McpSchemaValidationOutcome.LimitExceeded {
	long evaluationOperations();

	record Valid(long evaluationOperations) implements McpSchemaValidationOutcome {
		public Valid {
			requireNonNegative(evaluationOperations);
		}
	}

	record Invalid(@NonNull List<@NonNull McpSchemaDiagnostic> diagnostics,
			boolean diagnosticsTruncated,
			long evaluationOperations) implements McpSchemaValidationOutcome {
		public Invalid {
			diagnostics = List.copyOf(requireNonNull(diagnostics));
			requireNonNegative(evaluationOperations);
			if (diagnostics.isEmpty() && !diagnosticsTruncated)
				throw new IllegalArgumentException(
						"An invalid outcome must retain or truncate a diagnostic.");
		}
	}

	record LimitExceeded(@NonNull McpSchemaEvaluationLimit limit,
			long evaluationOperations) implements McpSchemaValidationOutcome {
		public LimitExceeded {
			requireNonNull(limit);
			requireNonNegative(evaluationOperations);
		}
	}

	private static void requireNonNegative(long value) {
		if (value < 0)
			throw new IllegalArgumentException(
					"evaluationOperations must not be negative.");
	}
}
