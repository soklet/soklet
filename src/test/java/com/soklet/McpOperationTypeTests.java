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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Contracts for the public semantic classification of inbound MCP methods.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpOperationTypeTests {
	private static final Map<String, McpOperationType> RECOGNIZED_METHODS =
			recognizedMethods();
	private static final Map<String, McpOperationType> OTHER_METHODS = Map.of(
			"notifications/vendor-event", McpOperationType.OTHER,
			"com.example/future-operation", McpOperationType.OTHER);

	@Test
	void everyRecognizedInboundMethodHasAnExactOperationType() {
		Assertions.assertEquals(
				EnumSet.complementOf(EnumSet.of(McpOperationType.OTHER)),
				Set.copyOf(RECOGNIZED_METHODS.values()));
		RECOGNIZED_METHODS.forEach((jsonRpcMethod, expectedOperationType) ->
				Assertions.assertEquals(expectedOperationType,
						McpOperationType.fromJsonRpcMethod(jsonRpcMethod),
						jsonRpcMethod));
	}

	@Test
	void arbitraryNotificationAndVendorMethodsClassifyAsOther() {
		OTHER_METHODS.forEach((jsonRpcMethod, expectedOperationType) ->
				Assertions.assertEquals(expectedOperationType,
						McpOperationType.fromJsonRpcMethod(jsonRpcMethod),
						jsonRpcMethod));
	}

	@Test
	void publicContextsShareClassificationAndPreserveTheExactWireMethod() {
		Map<String, McpOperationType> expectedMethods = new LinkedHashMap<>(
				RECOGNIZED_METHODS);
		expectedMethods.putAll(OTHER_METHODS);

		expectedMethods.forEach((jsonRpcMethod, expectedOperationType) -> {
			McpRequestContext requestContext = methodOnlyContext(
					McpRequestContext.class, jsonRpcMethod);
			McpAdmissionContext admissionContext = methodOnlyContext(
					McpAdmissionContext.class, jsonRpcMethod);
			McpRateLimitContext rateLimitContext = methodOnlyContext(
					McpRateLimitContext.class, jsonRpcMethod);

			assertContext(jsonRpcMethod, expectedOperationType,
					requestContext.getJsonRpcMethod(),
					requestContext.getOperationType(), "request");
			assertContext(jsonRpcMethod, expectedOperationType,
					admissionContext.getJsonRpcMethod(),
					admissionContext.getOperationType(), "admission");
			assertContext(jsonRpcMethod, expectedOperationType,
					rateLimitContext.getJsonRpcMethod(),
					rateLimitContext.getOperationType(), "rate-limit");
		});
	}

	private static Map<String, McpOperationType> recognizedMethods() {
		Map<String, McpOperationType> methods = new LinkedHashMap<>();
		methods.put("server/discover", McpOperationType.SERVER_DISCOVER);
		methods.put("tools/list", McpOperationType.TOOLS_LIST);
		methods.put("tools/call", McpOperationType.TOOLS_CALL);
		methods.put("prompts/list", McpOperationType.PROMPTS_LIST);
		methods.put("prompts/get", McpOperationType.PROMPTS_GET);
		methods.put("resources/list", McpOperationType.RESOURCES_LIST);
		methods.put("resources/templates/list",
				McpOperationType.RESOURCES_TEMPLATES_LIST);
		methods.put("resources/read", McpOperationType.RESOURCES_READ);
		methods.put("subscriptions/listen",
				McpOperationType.SUBSCRIPTIONS_LISTEN);
		methods.put("tasks/get", McpOperationType.TASKS_GET);
		methods.put("tasks/update", McpOperationType.TASKS_UPDATE);
		methods.put("tasks/cancel", McpOperationType.TASKS_CANCEL);
		methods.put("notifications/cancelled",
				McpOperationType.NOTIFICATIONS_CANCELED);
		return Map.copyOf(methods);
	}

	private static void assertContext(String expectedJsonRpcMethod,
			McpOperationType expectedOperationType, String actualJsonRpcMethod,
			McpOperationType actualOperationType, String contextName) {
		Assertions.assertEquals(expectedJsonRpcMethod, actualJsonRpcMethod,
				contextName + " context changed the exact JSON-RPC method");
		Assertions.assertEquals(expectedOperationType, actualOperationType,
				contextName + " context exposed a different operation type");
	}

	private static <T> T methodOnlyContext(Class<T> contextType,
			String jsonRpcMethod) {
		Object proxy = Proxy.newProxyInstance(contextType.getClassLoader(),
				new Class<?>[] { contextType }, (instance, method, arguments) -> {
					if (method.getName().equals("getJsonRpcMethod")
							&& method.getParameterCount() == 0)
						return jsonRpcMethod;
					if (method.isDefault())
						return InvocationHandler.invokeDefault(instance, method,
								arguments == null ? new Object[0] : arguments);
					throw new AssertionError("Unexpected context method: " + method);
				});
		return contextType.cast(proxy);
	}
}
