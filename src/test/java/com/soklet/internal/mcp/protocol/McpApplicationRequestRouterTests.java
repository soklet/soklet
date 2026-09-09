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

package com.soklet.internal.mcp.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Focused framework-method ownership tests for the application request router.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public class McpApplicationRequestRouterTests {
	@Test
	public void taskNamespaceCannotBeReplacedByApplicationHandlers() {
		McpApplicationRequestHandler handler = invocation -> {
			throw new AssertionError("A reserved task handler must not run.");
		};

		for (String method : List.of("tasks/get", "tasks/update", "tasks/cancel",
				"tasks/list", "tasks/result", "tasks/custom")) {
			IllegalArgumentException exception = Assertions.assertThrows(
					IllegalArgumentException.class,
					() -> McpApplicationRequestRouter.fromHandlers(
							Map.of(method, handler)));
			Assertions.assertEquals(
					"Framework-owned MCP methods cannot be replaced by an application handler.",
					exception.getMessage());
		}
	}

	@Test
	public void frameworkFactoryInstallsOnlyExactTaskMethods() {
		McpApplicationRequestHandler handler = invocation -> {
			throw new AssertionError("A routing-construction test must not dispatch.");
		};
		Map<String, McpApplicationRequestHandler> taskHandlers =
				new LinkedHashMap<>();
		for (String method : List.of("tasks/get", "tasks/update",
				"tasks/cancel"))
			taskHandlers.put(method, handler);

		McpApplicationRequestRouter router = frameworkRouter(taskHandlers);
		for (String method : taskHandlers.keySet())
			Assertions.assertSame(handler, router.resolve(method).orElseThrow());

		for (String method : List.of("server/discover", "example/custom",
				"tasks/list", "tasks/result", "tasks/custom")) {
			IllegalArgumentException exception = Assertions.assertThrows(
					IllegalArgumentException.class,
					() -> frameworkRouter(Map.of(method, handler)));
			Assertions.assertEquals(
					"Only framework-owned MCP task methods may be installed by the framework handler factory.",
					exception.getMessage());
		}
	}

	private static McpApplicationRequestRouter frameworkRouter(
			Map<String, McpApplicationRequestHandler> handlersByMethod) {
		return McpApplicationRequestRouter
				.fromFrameworkHandlersAndValidatedOperationRoutes(
						handlersByMethod, Map.of(), Map.of(), Map.of(), List.of(),
						Optional.empty());
	}
}
