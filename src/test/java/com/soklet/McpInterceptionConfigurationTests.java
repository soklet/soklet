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
		McpToolResultSanitizer sanitizer =
				McpToolResultSanitizer.nonSanitizingInstance();
		McpRequestContext request = requestContext();
		McpOperationResult expectedResult =
				McpCompleteResult.fromToolText("expected");
		McpCompleteResult expectedCompleteResult =
				McpCompleteResult.fromToolText("expected");
		AtomicBoolean invoked = new AtomicBoolean();

		McpHandlerContinuation continuation = () -> {
			invoked.set(true);
			return expectedResult;
		};
		McpInvocationFeatures features = McpInvocationFeatures.fromFeatures(
				java.util.Map.of());
		McpOperationResult actualResult = interceptor.interceptHandler(request,
				features, continuation);
		McpCompleteResult actualCompleteResult = sanitizer.sanitize(request,
				"tool", McpJsonObject.builder().build(), expectedCompleteResult);

		Assertions.assertTrue(invoked.get());
		Assertions.assertSame(expectedResult, actualResult);
		Assertions.assertSame(expectedCompleteResult, actualCompleteResult);
		Assertions.assertSame(interceptor,
				McpHandlerInterceptor.passThroughInstance());
		Assertions.assertSame(sanitizer,
				McpToolResultSanitizer.nonSanitizingInstance());
	}

	@Test
	public void builderPublishesDefaultsAndConfiguredHookIdentities() {
		McpServer defaultServer = serverBuilder().build();
		McpHandlerInterceptor interceptor = (request, features, continuation) ->
				McpCompleteResult.fromToolText("intercepted");
		McpToolResultSanitizer sanitizer =
				(request, toolName, rawArguments, completeResult) ->
						McpCompleteResult.fromToolText("sanitized");
		McpServer configuredServer = serverBuilder()
				.handlerInterceptor(interceptor)
				.toolResultSanitizer(sanitizer)
				.build();

		Assertions.assertSame(McpHandlerInterceptor.passThroughInstance(),
				defaultServer.getHandlerInterceptor());
		Assertions.assertSame(McpToolResultSanitizer.nonSanitizingInstance(),
				defaultServer.getToolResultSanitizer());
		Assertions.assertSame(interceptor,
				configuredServer.getHandlerInterceptor());
		Assertions.assertSame(sanitizer,
				configuredServer.getToolResultSanitizer());
		Assertions.assertInstanceOf(McpOperationResult.class,
				McpResourcePage.builder().build());
	}

	@Test
	public void builderNullHooksRestorePassThroughDefaults() {
		McpHandlerInterceptor interceptor = (request, features, continuation) ->
				McpCompleteResult.fromToolText("intercepted");
		McpToolResultSanitizer sanitizer =
				(request, toolName, rawArguments, completeResult) ->
						McpCompleteResult.fromToolText("sanitized");
		McpServer server = serverBuilder()
				.handlerInterceptor(interceptor)
				.handlerInterceptor(null)
				.toolResultSanitizer(sanitizer)
				.toolResultSanitizer(null)
				.build();

		Assertions.assertSame(McpHandlerInterceptor.passThroughInstance(),
				server.getHandlerInterceptor());
		Assertions.assertSame(McpToolResultSanitizer.nonSanitizingInstance(),
				server.getToolResultSanitizer());
	}

	private static McpServer.Builder serverBuilder() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"interception-tests", "4.0.0").build())
				.build();
		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)));
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
