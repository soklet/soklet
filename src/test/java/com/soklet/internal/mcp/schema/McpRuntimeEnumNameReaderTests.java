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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class McpRuntimeEnumNameReaderTests {
	private static final AtomicBoolean ENUM_INITIALIZED = new AtomicBoolean();

	@Test
	void readsClassfileDeclarationOrderWithoutInitializingEnum() {
		Assertions.assertFalse(ENUM_INITIALIZED.get());

		List<String> names = new McpRuntimeEnumNameReader(3, 6)
				.read(InitializationProbe.class);

		Assertions.assertEquals(List.of("SECOND", "FIRST", "THIRD"), names);
		Assertions.assertFalse(ENUM_INITIALIZED.get());
	}

	@Test
	void enforcesConstantAndNameLimitsWithoutInitializingEnum() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpRuntimeEnumNameReader(2, 6)
						.read(InitializationProbe.class));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpRuntimeEnumNameReader(3, 5)
						.read(InitializationProbe.class));
		Assertions.assertFalse(ENUM_INITIALIZED.get());
	}

	@Test
	void rejectsResourceMetadataWhoseEnumNamesAreReordered() {
		McpRuntimeEnumNameReader reader = new McpRuntimeEnumNameReader(3, 6);

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> reader.verifyLoadedEnumMetadata(InitializationProbe.class,
						List.of("FIRST", "SECOND", "THIRD")));
		Assertions.assertFalse(ENUM_INITIALIZED.get());
	}

	@Test
	void rejectsNonEnumTypes() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpRuntimeEnumNameReader(1, 1).read(String.class));
	}

	private enum InitializationProbe {
		SECOND,
		FIRST,
		THIRD;

		static {
			ENUM_INITIALIZED.set(true);
		}
	}
}
