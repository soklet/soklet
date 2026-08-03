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
 * Explicit resource bounds for compiling an offline schema catalog.
 *
 * <p>Phase 2 intentionally has no default instance yet. Production values
 * will be selected from pinned-corpus, adversarial, and cross-JDK evidence.</p>
 */
record McpSchemaCompilationLimits(int maximumDocumentCount,
		int maximumSchemaNodeCount, int maximumSchemaDepth,
		int maximumKeywordCount, int maximumResourceCount,
		int maximumResourceIdentifierCount, int maximumAnchorCount,
		int maximumReferenceCount, int maximumUriLengthInCharacters,
		int maximumPointerSegmentCount,
		int maximumVocabularyDeclarationCount) {
	private static final int MAXIMUM_SAFE_RECURSIVE_SCHEMA_DEPTH = 256;

	McpSchemaCompilationLimits {
		requirePositive(maximumDocumentCount, "maximumDocumentCount");
		requirePositive(maximumSchemaNodeCount, "maximumSchemaNodeCount");
		requirePositive(maximumSchemaDepth, "maximumSchemaDepth");
		requirePositive(maximumKeywordCount, "maximumKeywordCount");
		requirePositive(maximumResourceCount, "maximumResourceCount");
		requirePositive(maximumResourceIdentifierCount,
				"maximumResourceIdentifierCount");
		requirePositive(maximumAnchorCount, "maximumAnchorCount");
		requirePositive(maximumReferenceCount, "maximumReferenceCount");
		requirePositive(maximumUriLengthInCharacters,
				"maximumUriLengthInCharacters");
		requirePositive(maximumPointerSegmentCount,
				"maximumPointerSegmentCount");
		requirePositive(maximumVocabularyDeclarationCount,
				"maximumVocabularyDeclarationCount");

		if (maximumSchemaDepth > MAXIMUM_SAFE_RECURSIVE_SCHEMA_DEPTH)
			throw new IllegalArgumentException("maximumSchemaDepth must not exceed "
					+ MAXIMUM_SAFE_RECURSIVE_SCHEMA_DEPTH + ".");
	}

	private static void requirePositive(int value, String name) {
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive.");
	}
}
