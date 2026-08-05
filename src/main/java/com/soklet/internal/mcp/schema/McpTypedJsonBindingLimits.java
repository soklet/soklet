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
 * Positive finite per-conversion bounds for intrinsic typed JSON binding.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpTypedJsonBindingLimits(int maximumNodeCount,
		int maximumNestingDepth, int maximumContainerEntryCount) {
	private static final int MAXIMUM_SUPPORTED_NODE_COUNT = 1_000_000;
	private static final int MAXIMUM_SUPPORTED_NESTING_DEPTH = 256;
	private static final int MAXIMUM_SUPPORTED_CONTAINER_ENTRY_COUNT =
			1_000_000;
	@NonNull
	private static final McpTypedJsonBindingLimits PRODUCTION_DEFAULTS =
			new McpTypedJsonBindingLimits(100_000, 128, 100_000);

	McpTypedJsonBindingLimits {
		requirePositive(maximumNodeCount, "maximumNodeCount");
		requirePositive(maximumNestingDepth, "maximumNestingDepth");
		requirePositive(maximumContainerEntryCount,
				"maximumContainerEntryCount");
		requireAtMost(maximumNodeCount, MAXIMUM_SUPPORTED_NODE_COUNT,
				"maximumNodeCount");
		requireAtMost(maximumNestingDepth,
				MAXIMUM_SUPPORTED_NESTING_DEPTH, "maximumNestingDepth");
		requireAtMost(maximumContainerEntryCount,
				MAXIMUM_SUPPORTED_CONTAINER_ENTRY_COUNT,
				"maximumContainerEntryCount");
	}

	@NonNull
	static McpTypedJsonBindingLimits productionDefaults() {
		return PRODUCTION_DEFAULTS;
	}

	private static void requirePositive(int value, @NonNull String name) {
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive.");
	}

	private static void requireAtMost(int value, int maximum,
			@NonNull String name) {
		if (value > maximum)
			throw new IllegalArgumentException(name + " must not exceed "
					+ maximum + ".");
	}
}
