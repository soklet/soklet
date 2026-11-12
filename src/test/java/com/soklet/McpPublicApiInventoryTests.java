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

import com.soklet.annotation.McpTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Checks that convention tests discover the current exported API without release inventories. */
public class McpPublicApiInventoryTests {
	@Test
	public void compiledDiscoveryIncludesNestedTypesAndAnnotations() throws Exception {
		List<Class<?>> types = McpPublicApiTestSupport.publicMcpTypes();
		Assertions.assertTrue(types.containsAll(List.of(McpServer.class,
				McpServer.Builder.class, McpTool.class)));
		Assertions.assertTrue(types.stream().allMatch(McpPublicApiTestSupport::isExported));
	}

	@Test
	public void packagePrivateEnclosingTypesDoNotExportTheirNestedTypes() {
		Assertions.assertFalse(McpPublicApiTestSupport.isExported(HiddenHost.PublicNested.class));
		Assertions.assertFalse(McpPublicApiTestSupport.isExported(McpPublicApiTestSupport.class));
	}

	private static class HiddenHost {
		public static class PublicNested {}
	}
}
