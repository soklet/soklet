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
 * Explicit bounds for compiling one Profile 1 schema document.
 *
 * <p>The production and maximum-supported profiles are fixed from the pinned
 * corpus, adversarial cases, and cross-JDK evidence. A stricter internal
 * profile remains useful for exact-boundary tests.</p>
 */
record McpSchemaCompilationLimits(int maximumSchemaNodeCount,
		int maximumSchemaDepth, int maximumKeywordCount,
		int maximumAnchorCount, int maximumReferenceCount,
		int maximumAnchorNameLengthInCharacters,
		int maximumReferenceLengthInCharacters,
		int maximumPointerSegmentCount, int maximumCollectionEntryCount,
		int maximumNameLengthInCharacters,
		int maximumPointerSegmentLengthInCharacters) {
	private static final int MAXIMUM_SUPPORTED_SCHEMA_NODE_COUNT = 65_536;
	private static final int MAXIMUM_SUPPORTED_SCHEMA_DEPTH = 256;
	private static final int MAXIMUM_SUPPORTED_KEYWORD_COUNT = 524_288;
	private static final int MAXIMUM_SUPPORTED_ANCHOR_COUNT = 65_536;
	private static final int MAXIMUM_SUPPORTED_REFERENCE_COUNT = 65_536;
	private static final int MAXIMUM_SUPPORTED_ANCHOR_NAME_LENGTH = 4_096;
	private static final int MAXIMUM_SUPPORTED_REFERENCE_LENGTH = 65_536;
	private static final int MAXIMUM_SUPPORTED_POINTER_SEGMENT_COUNT = 512;
	private static final int MAXIMUM_SUPPORTED_COLLECTION_ENTRY_COUNT = 65_536;
	private static final int MAXIMUM_SUPPORTED_NAME_LENGTH = 16_384;
	private static final int MAXIMUM_SUPPORTED_POINTER_SEGMENT_LENGTH = 16_384;
	private static final McpSchemaCompilationLimits PRODUCTION_DEFAULTS =
			new McpSchemaCompilationLimits(4_096, 64, 32_768, 1_024,
					4_096, 256, 4_096, 128, 4_096, 1_024, 1_024);
	private static final McpSchemaCompilationLimits MAXIMUM_SUPPORTED =
			new McpSchemaCompilationLimits(MAXIMUM_SUPPORTED_SCHEMA_NODE_COUNT,
					MAXIMUM_SUPPORTED_SCHEMA_DEPTH,
					MAXIMUM_SUPPORTED_KEYWORD_COUNT,
					MAXIMUM_SUPPORTED_ANCHOR_COUNT,
					MAXIMUM_SUPPORTED_REFERENCE_COUNT,
					MAXIMUM_SUPPORTED_ANCHOR_NAME_LENGTH,
					MAXIMUM_SUPPORTED_REFERENCE_LENGTH,
					MAXIMUM_SUPPORTED_POINTER_SEGMENT_COUNT,
					MAXIMUM_SUPPORTED_COLLECTION_ENTRY_COUNT,
					MAXIMUM_SUPPORTED_NAME_LENGTH,
					MAXIMUM_SUPPORTED_POINTER_SEGMENT_LENGTH);

	McpSchemaCompilationLimits {
		requirePositive(maximumSchemaNodeCount, "maximumSchemaNodeCount");
		requirePositive(maximumSchemaDepth, "maximumSchemaDepth");
		requirePositive(maximumKeywordCount, "maximumKeywordCount");
		requirePositive(maximumAnchorCount, "maximumAnchorCount");
		requirePositive(maximumReferenceCount, "maximumReferenceCount");
		requirePositive(maximumAnchorNameLengthInCharacters,
				"maximumAnchorNameLengthInCharacters");
		requirePositive(maximumReferenceLengthInCharacters,
				"maximumReferenceLengthInCharacters");
		requirePositive(maximumPointerSegmentCount,
				"maximumPointerSegmentCount");
		requirePositive(maximumCollectionEntryCount,
				"maximumCollectionEntryCount");
		requirePositive(maximumNameLengthInCharacters,
				"maximumNameLengthInCharacters");
		requirePositive(maximumPointerSegmentLengthInCharacters,
				"maximumPointerSegmentLengthInCharacters");

		requireAtMost(maximumSchemaNodeCount,
				MAXIMUM_SUPPORTED_SCHEMA_NODE_COUNT,
				"maximumSchemaNodeCount");
		requireAtMost(maximumSchemaDepth,
				MAXIMUM_SUPPORTED_SCHEMA_DEPTH,
				"maximumSchemaDepth");
		requireAtMost(maximumKeywordCount,
				MAXIMUM_SUPPORTED_KEYWORD_COUNT,
				"maximumKeywordCount");
		requireAtMost(maximumAnchorCount,
				MAXIMUM_SUPPORTED_ANCHOR_COUNT,
				"maximumAnchorCount");
		requireAtMost(maximumReferenceCount,
				MAXIMUM_SUPPORTED_REFERENCE_COUNT,
				"maximumReferenceCount");
		requireAtMost(maximumAnchorNameLengthInCharacters,
				MAXIMUM_SUPPORTED_ANCHOR_NAME_LENGTH,
				"maximumAnchorNameLengthInCharacters");
		requireAtMost(maximumReferenceLengthInCharacters,
				MAXIMUM_SUPPORTED_REFERENCE_LENGTH,
				"maximumReferenceLengthInCharacters");
		requireAtMost(maximumPointerSegmentCount,
				MAXIMUM_SUPPORTED_POINTER_SEGMENT_COUNT,
				"maximumPointerSegmentCount");
		requireAtMost(maximumCollectionEntryCount,
				MAXIMUM_SUPPORTED_COLLECTION_ENTRY_COUNT,
				"maximumCollectionEntryCount");
		requireAtMost(maximumNameLengthInCharacters,
				MAXIMUM_SUPPORTED_NAME_LENGTH,
				"maximumNameLengthInCharacters");
		requireAtMost(maximumPointerSegmentLengthInCharacters,
				MAXIMUM_SUPPORTED_POINTER_SEGMENT_LENGTH,
				"maximumPointerSegmentLengthInCharacters");
	}

	static McpSchemaCompilationLimits productionDefaults() {
		return PRODUCTION_DEFAULTS;
	}

	static McpSchemaCompilationLimits maximumSupported() {
		return MAXIMUM_SUPPORTED;
	}

	private static void requirePositive(int value, String name) {
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive.");
	}

	private static void requireAtMost(int value, int maximum, String name) {
		if (value > maximum)
			throw new IllegalArgumentException(name + " must not exceed "
					+ maximum + ".");
	}
}
