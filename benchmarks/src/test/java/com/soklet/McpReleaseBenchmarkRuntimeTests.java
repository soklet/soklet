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

package com.soklet;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpReleaseBenchmarkRuntimeTests {
	@Test
	void exactReleasedAndCandidateCodecsRunInIsolatedLoaders() {
		Path baseline = Path.of("target", "release-inputs", "soklet-3.5.1.jar");
		Path candidate = Path.of("target", "classes");

		try (McpReleaseBenchmarkRuntime released =
					McpReleaseBenchmarkRuntime.open("3.5.1", baseline);
				McpReleaseBenchmarkRuntime current =
						McpReleaseBenchmarkRuntime.open("4.0.0", candidate)) {
			Object releasedValue = released.parse(
					McpReleaseBenchmarkRuntime.JSON_PAYLOAD);
			Object currentValue = current.parse(
					McpReleaseBenchmarkRuntime.JSON_PAYLOAD);
			assertArrayEquals(McpReleaseBenchmarkRuntime.JSON_PAYLOAD,
					released.write(releasedValue));
			assertArrayEquals(McpReleaseBenchmarkRuntime.JSON_PAYLOAD,
					current.write(currentValue));
			assertEquals("com.soklet.McpObject",
					releasedValue.getClass().getName());
			assertEquals("com.soklet.internal.mcp.protocol.McpJsonObject",
					currentValue.getClass().getName());
		}
	}

	@Test
	void candidateProfileFixtureCompilesAndEvaluatesWithoutErrors() {
		try (McpReleaseBenchmarkRuntime current = McpReleaseBenchmarkRuntime.open(
				"4.0.0", Path.of("target", "classes"))) {
			Object schema = current.parse(McpReleaseBenchmarkRuntime.PROFILE_SCHEMA);
			Object instance = current.parse(
					McpReleaseBenchmarkRuntime.PROFILE_INSTANCE);
			Object program = current.compile(schema);
			Object result = current.evaluate(program, instance);
			assertNotNull(current.compilationLimits());
			assertEquals("Valid", result.getClass().getSimpleName());
		}
	}

	@Test
	void artifactSelectionFailsClosed() {
		IllegalArgumentException exception = assertThrows(
				IllegalArgumentException.class,
				() -> McpReleaseBenchmarkRuntime.open("4.0.0-SNAPSHOT",
						Path.of("target", "classes")));
		assertEquals("Unsupported Soklet artifact: 4.0.0-SNAPSHOT",
				exception.getMessage());
	}
}
