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

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coverage for the public MCP interception and sanitization configuration.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpInterceptionConfigurationTests {
	@Test
	public void defaultHooksAreSharedAndPassThrough() throws Exception {
		McpHandlerInterceptor interceptor =
				McpHandlerInterceptor.passThroughInstance();
		McpToolOutputSanitizer sanitizer =
				McpToolOutputSanitizer.passThroughInstance();
		McpRequestContext request = requestContext();
		McpOperationResult expectedResult =
				McpCompleteResult.fromToolText("expected");
		McpToolOutput expectedOutput = McpToolOutput.fromText("expected");
		AtomicBoolean invoked = new AtomicBoolean();

		McpHandlerContinuation continuation = () -> {
			invoked.set(true);
			return expectedResult;
		};
		McpInvocationFeatures features = McpInvocationFeatures.fromFeatures(
				java.util.Map.of());
		McpOperationResult actualResult = interceptor.interceptHandler(request,
				features, continuation);
		McpToolOutput actualOutput = sanitizer.sanitize(request, "tool",
				McpJsonObject.builder().build(), expectedOutput);

		Assertions.assertTrue(invoked.get());
		Assertions.assertSame(expectedResult, actualResult);
		Assertions.assertSame(expectedOutput, actualOutput);
		Assertions.assertSame(interceptor,
				McpHandlerInterceptor.passThroughInstance());
		Assertions.assertSame(sanitizer,
				McpToolOutputSanitizer.passThroughInstance());
	}

	@Test
	public void builderPublishesDefaultsAndConfiguredHookIdentities() {
		McpServer defaultServer = serverBuilder().build();
		McpHandlerInterceptor interceptor = (request, features, continuation) ->
				McpCompleteResult.fromToolText("intercepted");
		McpToolOutputSanitizer sanitizer =
				(request, toolName, rawArguments, output) ->
						McpToolOutput.fromText("sanitized");
		McpServer configuredServer = serverBuilder()
				.handlerInterceptor(interceptor)
				.toolOutputSanitizer(sanitizer)
				.build();

		Assertions.assertSame(McpHandlerInterceptor.passThroughInstance(),
				defaultServer.getHandlerInterceptor());
		Assertions.assertSame(McpToolOutputSanitizer.passThroughInstance(),
				defaultServer.getToolOutputSanitizer());
		Assertions.assertSame(interceptor,
				configuredServer.getHandlerInterceptor());
		Assertions.assertSame(sanitizer,
				configuredServer.getToolOutputSanitizer());
		Assertions.assertInstanceOf(McpOperationResult.class,
				McpResourcePage.builder().build());
	}

	@Test
	public void builderRejectsNullHooksImmediately() {
		McpServer.Builder builder = McpServer.withPort(0);

		Assertions.assertThrows(NullPointerException.class,
				() -> builder.handlerInterceptor(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> builder.toolOutputSanitizer(null));
	}

	private static McpServer.Builder serverBuilder() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"interception-tests", "4.0.0").build())
				.build();
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance());
	}

	private static McpRequestContext requestContext() {
		return (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class},
				(proxy, method, arguments) -> {
					throw new UnsupportedOperationException(
							"The pass-through hooks must not inspect request context.");
				});
	}
}
