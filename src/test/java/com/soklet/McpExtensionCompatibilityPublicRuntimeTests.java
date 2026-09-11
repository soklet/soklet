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

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public real-listener coverage for unsupported peer-extension fallback.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpExtensionCompatibilityPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String EXTENSION_ID = "com.example/client-extension";
	private static final String EXTENSION_SECRET = "extension-setting-secret";
	private static final String TASKS_EXTENSION_ID =
			"io.modelcontextprotocol/tasks";
	private static final String TOOL_NAME = "extension.negotiated";
	private static final String REQUEST_METADATA_KEY =
			"com.example/request-metadata";
	private static final String HANDLER_METADATA_KEY =
			"com.example/handler-result";
	private static final String INTERCEPTOR_METADATA_KEY =
			"com.example/interceptor-result";
	private static final AtomicInteger TASK_CONTROL_ARGUMENT_CONSTRUCTIONS =
			new AtomicInteger();

	@Test
	public void validUnknownExtensionFallsBackToCoreWithoutInventedOrReflectedSupport()
			throws Exception {
		List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		McpServer server = server(context -> {
			admissions.add(context);
			return McpAdmissionDecision.accepted();
		});
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = discover(port, "valid-extension", "{"
					+ "\"extensions\":{\"" + EXTENSION_ID + "\":{"
					+ "\"enabled\":true,\"secret\":\"" + EXTENSION_SECRET + "\"}},"
					+ "\"futureCapability\":{\"enabled\":true}}");

			Assertions.assertEquals(200, response.statusCode(), response.body());
			Assertions.assertEquals("no-store",
					response.headers().firstValue("Cache-Control").orElseThrow());
			Assertions.assertTrue(response.body().contains("\"id\":\"valid-extension\""),
					response.body());
			Assertions.assertTrue(response.body().contains("\"capabilities\":{}"),
					response.body());
			Assertions.assertFalse(response.body().contains(EXTENSION_ID), response.body());
			Assertions.assertFalse(response.body().contains(EXTENSION_SECRET), response.body());
			Assertions.assertFalse(response.body().contains("\"extensions\""),
					response.body());
			Assertions.assertFalse(response.body().contains("futureCapability"),
					response.body());

			Assertions.assertEquals(1, admissions.size());
			McpClientCapabilities capabilities = admissions.get(0)
					.getClientCapabilities().orElseThrow();
			McpJsonObject extension = capabilities.findExtension(EXTENSION_ID)
					.orElseThrow();
			Assertions.assertEquals(McpJsonBoolean.fromValue(true),
					extension.getMembers().get("enabled"));
			Assertions.assertEquals(EXTENSION_SECRET,
					((McpJsonString) extension.getMembers().get("secret")).getValue());
			Assertions.assertEquals(Set.of(EXTENSION_ID),
					capabilities.getExtensions().keySet());
			for (McpClientCapability capability : McpClientCapability.values())
				Assertions.assertFalse(capabilities.supports(capability),
						() -> "Unknown extensions must not invent core support: "
								+ capability);
			Assertions.assertTrue(capabilities.toJson().getMembers()
					.containsKey("futureCapability"));
		} finally {
			soklet.close();
		}
	}

	@Test
	public void malformedExtensionIdentifiersAndSettingsFailExplicitlyBeforeAdmission()
			throws Exception {
		List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		McpServer server = server(context -> {
			admissions.add(context);
			return McpAdmissionDecision.accepted();
		});
		Soklet soklet = managedSoklet(server);
		List<String> malformedCapabilities = List.of(
				"{\"extensions\":[]}",
				"{\"extensions\":{\"not-prefixed\":{}}}",
				"{\"extensions\":{\"/missing-prefix\":{}}}",
				"{\"extensions\":{\"com.example/client-extension\":true}}");

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			for (int index = 0; index < malformedCapabilities.size(); index++) {
				String id = "malformed-extension-" + index;
				HttpResponse<String> response = discover(port, id,
						malformedCapabilities.get(index));
				Assertions.assertEquals(400, response.statusCode(), response.body());
				Assertions.assertEquals("no-store",
						response.headers().firstValue("Cache-Control").orElseThrow());
				Assertions.assertTrue(response.body().contains("\"id\":\"" + id + "\""),
						response.body());
				Assertions.assertTrue(response.body().contains("\"code\":-32602"),
						response.body());
				Assertions.assertFalse(response.body().contains("not-prefixed"),
						response.body());
				Assertions.assertFalse(response.body().contains("missing-prefix"),
						response.body());
			}
			Assertions.assertTrue(admissions.isEmpty(),
					"Malformed extension metadata must fail before admission.");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void unsupportedExtensionCanDriveApplicationOwnedBehaviorOnAnExistingMethod()
			throws Exception {
		List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		AtomicReference<McpRequestContext> handlerContext = new AtomicReference<>();
		AtomicReference<McpRequestContext> interceptorContext =
				new AtomicReference<>();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					handlerContext.set(request);
					Assertions.assertSame(interceptorContext.get(), request);
					assertExtensionSettings(request.getClientCapabilities());
					McpJsonObject requestMetadata = Assertions.assertInstanceOf(
							McpJsonObject.class, request.getRequestMetadata()
									.getMembers().get(REQUEST_METADATA_KEY));
					Assertions.assertEquals("expanded",
							Assertions.assertInstanceOf(McpJsonString.class,
									requestMetadata.getMembers().get("mode"))
									.getValue());
					return McpCompleteResult.fromToolText("extension-negotiated")
							.withMetadata(McpJsonObject.builder()
									.put(HANDLER_METADATA_KEY, "handler")
									.build());
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"extension-compatibility-test", "4.0.0").build())
				.addTool(tool)
				.build();
		McpHandlerInterceptor interceptor = (context, features, continuation) -> {
			interceptorInvocations.incrementAndGet();
			interceptorContext.set(context);
			assertExtensionSettings(context.getClientCapabilities());
			McpCompleteResult result = Assertions.assertInstanceOf(
					McpCompleteResult.class, continuation.proceed());
			Assertions.assertEquals("handler",
					Assertions.assertInstanceOf(McpJsonString.class,
							result.getMetadata().getMembers()
									.get(HANDLER_METADATA_KEY)).getValue());
			return result.withMetadata(McpJsonObject.builder()
					.put(HANDLER_METADATA_KEY, "handler")
					.put(INTERCEPTOR_METADATA_KEY, "interceptor")
					.build());
		};
		McpServer server = server(endpoint, context -> {
			admissions.add(context);
			return McpAdmissionDecision.accepted();
		}, interceptor);
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = callNegotiatedTool(port);

			Assertions.assertEquals(200, response.statusCode(), response.body());
			Assertions.assertEquals("no-store",
					response.headers().firstValue("Cache-Control").orElseThrow());
			Assertions.assertTrue(response.body().contains("extension-negotiated"),
					response.body());
			Assertions.assertTrue(response.body().contains("\""
					+ HANDLER_METADATA_KEY + "\":\"handler\""), response.body());
			Assertions.assertTrue(response.body().contains("\""
					+ INTERCEPTOR_METADATA_KEY + "\":\"interceptor\""),
					response.body());
			Assertions.assertFalse(response.body().contains(REQUEST_METADATA_KEY),
					response.body());
			Assertions.assertFalse(response.body().contains(EXTENSION_ID),
					response.body());
			Assertions.assertFalse(response.body().contains(EXTENSION_SECRET),
					response.body());
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(1, interceptorInvocations.get());
			Assertions.assertSame(interceptorContext.get(), handlerContext.get());
			Assertions.assertEquals(1, admissions.size());
			assertExtensionSettings(admissions.get(0).getClientCapabilities()
					.orElseThrow());

			HttpResponse<String> discovery = discover(port, "post-negotiation", "{"
					+ "\"extensions\":{\"" + EXTENSION_ID + "\":{"
					+ "\"enabled\":true,\"secret\":\"" + EXTENSION_SECRET
					+ "\"}}}");
			Assertions.assertEquals(200, discovery.statusCode(), discovery.body());
			Assertions.assertTrue(discovery.body().contains(
					"\"capabilities\":{\"tools\":{}}"), discovery.body());
			Assertions.assertFalse(discovery.body().contains("\"extensions\""),
					discovery.body());
			Assertions.assertFalse(discovery.body().contains(EXTENSION_ID),
					discovery.body());
			Assertions.assertFalse(discovery.body().contains(HANDLER_METADATA_KEY),
					discovery.body());
			Assertions.assertFalse(discovery.body().contains(INTERCEPTOR_METADATA_KEY),
					discovery.body());
			Assertions.assertEquals(2, admissions.size());
			Assertions.assertEquals(1, interceptorInvocations.get(),
					"Framework-owned discovery must not traverse the application interceptor.");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void obsoleteTaskOptInIsIgnoredAndRemovedTasksResultIsUnknown()
			throws Exception {
		List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					McpJsonObject taskSettings = request.getClientCapabilities()
							.findExtension(TASKS_EXTENSION_ID).orElseThrow();
					Assertions.assertTrue(taskSettings.getMembers().isEmpty());
					return McpCompleteResult.fromToolText("ordinary-completion");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"tasks-compatibility-test", "4.0.0").build())
				.addTool(tool)
				.build();
		McpServer server = server(endpoint, context -> {
			admissions.add(context);
			return McpAdmissionDecision.accepted();
		}, McpHandlerInterceptor.passThroughInstance());
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> obsoleteTask = callWithObsoleteTask(port);

			Assertions.assertEquals(200, obsoleteTask.statusCode(),
					obsoleteTask.body());
			Assertions.assertTrue(obsoleteTask.body().contains(
					"\"id\":\"obsolete-task\""), obsoleteTask.body());
			Assertions.assertTrue(obsoleteTask.body().contains(
					"\"resultType\":\"complete\""), obsoleteTask.body());
			Assertions.assertTrue(obsoleteTask.body().contains(
					"ordinary-completion"), obsoleteTask.body());
			Assertions.assertFalse(obsoleteTask.body().contains(
					"\"resultType\":\"task\""), obsoleteTask.body());
			Assertions.assertFalse(obsoleteTask.body().contains("taskId"),
					obsoleteTask.body());
			Assertions.assertFalse(obsoleteTask.body().contains(TASKS_EXTENSION_ID),
					obsoleteTask.body());
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(1, admissions.size());
			McpJsonObject admittedTaskSettings = admissions.get(0)
					.getClientCapabilities().orElseThrow()
					.findExtension(TASKS_EXTENSION_ID).orElseThrow();
			Assertions.assertTrue(admittedTaskSettings.getMembers().isEmpty());

			HttpResponse<String> removedMethod = callRemovedTasksResult(port);
			Assertions.assertEquals(404, removedMethod.statusCode(),
					removedMethod.body());
			Assertions.assertTrue(removedMethod.body().contains(
					"\"id\":\"removed-tasks-result\""), removedMethod.body());
			Assertions.assertTrue(removedMethod.body().contains("\"code\":-32601"),
					removedMethod.body());
			Assertions.assertEquals(1, admissions.size(),
					"A removed Tasks method must fail before admission.");
			Assertions.assertEquals(1, handlerInvocations.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void configuredTaskManagerAdvertisesTasksExtensionWithoutClientReflection()
			throws Exception {
		McpTaskManager taskManager = McpTaskManager.fromInMemoryDefaults();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"tasks-capability-test", "4.0.0").build())
				.build();
		McpServer server = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.taskManager(taskManager)
				.host(LOOPBACK)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server);

		try {
			Assertions.assertSame(taskManager,
					server.getTaskManager().orElseThrow());
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = discover(port, "tasks-capability", "{}");

			Assertions.assertEquals(200, response.statusCode(), response.body());
			Assertions.assertTrue(response.body().contains(
					"\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}"),
					response.body());
		} finally {
			soklet.close();
		}
	}

	@Test
	@Timeout(120)
	public void taskControlRequiresConfiguredManagerAndNegotiatedToolCall()
			throws Exception {
		List<Optional<McpTaskControl>> observedTaskControls =
				new CopyOnWriteArrayList<>();
		List<Optional<McpTaskControl>> observedPromptTaskControls =
				new CopyOnWriteArrayList<>();
		List<McpRequestContext> observedRequestContexts =
				new CopyOnWriteArrayList<>();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					observedRequestContexts.add(request);
					observedTaskControls.add(features.getTaskControl());
					return McpCompleteResult.fromToolText("ordinary-completion");
				})
				.structuredContentMirroredAsText(false)
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("tasks.control.prompt")
				.handler((request, arguments, features) -> {
					observedPromptTaskControls.add(features.getTaskControl());
					return McpCompleteResult.fromPromptOutput(McpPromptOutput.builder()
							.addMessage(McpPromptMessage.fromUserContent(
									McpTextContent.fromText("ordinary-completion")))
							.build());
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"tasks-control-test", "4.0.0").build())
				.addTool(tool)
				.addPrompt(prompt)
				.build();
		McpTaskManager taskManager = McpTaskManager.fromInMemoryDefaults();
		McpServer configuredServer = taskControlServer(endpoint,
				Optional.of(taskManager));
		Soklet configuredSoklet = managedSoklet(configuredServer);

		try {
			configuredSoklet.start();
			int port = configuredServer.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			Assertions.assertEquals(200,
					callTaskControlTool(port, "capable", true).statusCode());
			HttpResponse<String> largeInline = callLargeTaskControlTool(port,
					"large-inline");
			Assertions.assertEquals(200, largeInline.statusCode(),
					largeInline.body());
			Assertions.assertEquals(200,
					callTaskControlTool(port, "incapable", false).statusCode());
			Assertions.assertEquals(200,
					callTaskControlPrompt(port, "non-tool").statusCode());
		} finally {
			configuredSoklet.close();
		}

		McpServer unconfiguredServer = taskControlServer(endpoint, Optional.empty());
		Soklet unconfiguredSoklet = managedSoklet(unconfiguredServer);
		try {
			unconfiguredSoklet.start();
			int port = unconfiguredServer.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			Assertions.assertEquals(200,
					callTaskControlTool(port, "no-manager", true).statusCode());
		} finally {
			unconfiguredSoklet.close();
		}

		Assertions.assertEquals(4, observedTaskControls.size());
		McpTaskControl taskControl = observedTaskControls.get(0).orElseThrow();
		Assertions.assertSame(observedRequestContexts.get(0),
				taskControl.getRequestContext());
		Assertions.assertEquals(McpJsonObject.builder()
				.put("formatVersion", 1)
				.put("protocolVersion", PROTOCOL_VERSION)
				.put("endpointPath", MCP_PATH)
				.put("operationType", "tools_call")
				.put("operationName", TOOL_NAME)
				.put("rawArguments", McpJsonObject.builder()
						.put("query", "catalog").build())
				.putNull("outputSchema")
				.put("structuredContentMirroredAsText", false)
				.put("inputRequestDeclarations", McpJsonArray.emptyInstance())
				.build(), taskControl.getTaskOrigin().getPersistedState());
		McpTaskControl largeInlineControl = observedTaskControls.get(1)
				.orElseThrow();
		Field taskOriginField = largeInlineControl.getClass()
				.getDeclaredField("taskOrigin");
		taskOriginField.setAccessible(true);
		Assertions.assertNull(taskOriginField.get(largeInlineControl),
				"A task-eligible inline completion must not eagerly encode its origin.");
		Assertions.assertTrue(observedTaskControls.get(2).isEmpty());
		Assertions.assertTrue(observedTaskControls.get(3).isEmpty());
		Assertions.assertEquals(List.of(Optional.empty()),
				observedPromptTaskControls);
	}

	@Test
	public void typedArgumentsMustBeValidBeforeTaskOriginCanBeUsed()
			throws Exception {
		String toolName = "tasks.control.typed";
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		List<Optional<McpTaskControl>> observedTaskControls =
				new CopyOnWriteArrayList<>();
		McpToolRegistration<TaskControlArguments> tool = McpToolRegistration
				.withName(toolName)
				.argumentType(TaskControlArguments.class)
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("ordinary-completion");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"tasks-typed-control-test", "4.0.0").build())
				.addTool(tool)
				.build();
		McpHandlerInterceptor interceptor = (context, features, continuation) -> {
			interceptorInvocations.incrementAndGet();
			observedTaskControls.add(features.getTaskControl());
			if (context.getRequestId().orElseThrow().asString()
					.equals(Optional.of("typed-short-circuit")))
				return McpCompleteResult.fromToolText("intercepted");
			Optional<McpTaskControl> taskControl = features.getTaskControl();
			try {
				taskControl.ifPresent(McpTaskControl::getTaskOrigin);
			} catch (McpInvalidToolArgumentsException exception) {
				Assertions.assertThrows(McpInvalidToolArgumentsException.class,
						() -> taskControl.orElseThrow().getTaskOrigin());
				throw exception;
			}
			return continuation.proceed();
		};
		McpServer server = taskControlServer(endpoint,
				Optional.of(McpTaskManager.fromInMemoryDefaults()), interceptor);
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			TASK_CONTROL_ARGUMENT_CONSTRUCTIONS.set(0);
			String capableMetadata = "{\"_meta\":{"
					+ "\"io.modelcontextprotocol/protocolVersion\":\""
					+ PROTOCOL_VERSION + "\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{"
					+ "\"extensions\":{\"" + TASKS_EXTENSION_ID
					+ "\":{}}}}";
			HttpResponse<String> valid = post(port, "tools/call", toolName,
					"{\"jsonrpc\":\"2.0\",\"id\":\"typed-valid\","
							+ "\"method\":\"tools/call\",\"params\":"
							+ capableMetadata + ",\"name\":\"" + toolName
							+ "\",\"arguments\":{\"query\":\"catalog\","
							+ "\"pageSizes\":[10]}}}");
			Assertions.assertEquals(200, valid.statusCode(), valid.body());
			Assertions.assertEquals(1, interceptorInvocations.get());
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(1,
					TASK_CONTROL_ARGUMENT_CONSTRUCTIONS.get(),
					"Valid typed arguments must be decoded exactly once.");
			Assertions.assertTrue(observedTaskControls.get(0).isPresent());

			HttpResponse<String> shortCircuited = post(port, "tools/call", toolName,
					"{\"jsonrpc\":\"2.0\",\"id\":\"typed-short-circuit\","
							+ "\"method\":\"tools/call\",\"params\":"
							+ capableMetadata + ",\"name\":\"" + toolName
							+ "\",\"arguments\":{\"query\":"
							+ "\"missing pageSizes\"}}}");
			Assertions.assertEquals(200, shortCircuited.statusCode(),
					shortCircuited.body());
			Assertions.assertTrue(shortCircuited.body().contains("intercepted"),
					shortCircuited.body());
			Assertions.assertEquals(2, interceptorInvocations.get());
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(1,
					TASK_CONTROL_ARGUMENT_CONSTRUCTIONS.get(),
					"An inline interceptor short circuit must retain pre-validation behavior.");
			Assertions.assertTrue(observedTaskControls.get(1).isPresent());

			HttpResponse<String> invalid = post(port, "tools/call", toolName,
					"{\"jsonrpc\":\"2.0\",\"id\":\"typed-invalid\","
							+ "\"method\":\"tools/call\",\"params\":"
							+ capableMetadata + ",\"name\":\"" + toolName
							+ "\",\"arguments\":{\"query\":"
							+ "\"missing pageSizes\"}}}");
			Assertions.assertEquals(400, invalid.statusCode(), invalid.body());
			Assertions.assertTrue(invalid.body().contains("\"code\":-32602"),
					invalid.body());
			Assertions.assertEquals(3, interceptorInvocations.get(),
					"Interception must retain its pre-validation ordering.");
			Assertions.assertEquals(1, handlerInvocations.get(),
					"Invalid typed arguments must not enter the tool handler.");
			Assertions.assertEquals(1,
					TASK_CONTROL_ARGUMENT_CONSTRUCTIONS.get(),
					"Invalid typed arguments must not construct a partial value.");
			Assertions.assertEquals(3, observedTaskControls.size());
			Assertions.assertTrue(observedTaskControls.get(2).isPresent(),
					"A control may be inspected, but invalid arguments must prevent its origin from being used.");

			HttpResponse<String> rejectedDuringConstruction = post(port,
					"tools/call", toolName,
					"{\"jsonrpc\":\"2.0\",\"id\":\"typed-rejected\","
							+ "\"method\":\"tools/call\",\"params\":"
							+ capableMetadata + ",\"name\":\"" + toolName
							+ "\",\"arguments\":{\"query\":\"reject\","
							+ "\"pageSizes\":[10]}}}");
			Assertions.assertEquals(400, rejectedDuringConstruction.statusCode(),
					rejectedDuringConstruction.body());
			Assertions.assertTrue(rejectedDuringConstruction.body().contains(
					"\"code\":-32602"), rejectedDuringConstruction.body());
			Assertions.assertEquals(4, interceptorInvocations.get());
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(2,
					TASK_CONTROL_ARGUMENT_CONSTRUCTIONS.get(),
					"A failed typed decode must not retry when task origin is accessed again.");
			Assertions.assertEquals(4, observedTaskControls.size());
			Assertions.assertTrue(observedTaskControls.get(3).isPresent());

			HttpResponse<String> incapableInvalid = post(port, "tools/call",
					toolName,
					"{\"jsonrpc\":\"2.0\",\"id\":\"typed-incapable\","
							+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
							+ "\"io.modelcontextprotocol/protocolVersion\":\""
							+ PROTOCOL_VERSION + "\","
							+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
							+ "\"name\":\"" + toolName + "\",\"arguments\":{"
							+ "\"query\":\"missing pageSizes\"}}}");
			Assertions.assertEquals(400, incapableInvalid.statusCode(),
					incapableInvalid.body());
			Assertions.assertEquals(5, interceptorInvocations.get(),
					"Non-Tasks calls retain interceptor-before-validation ordering.");
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(2,
					TASK_CONTROL_ARGUMENT_CONSTRUCTIONS.get());
			Assertions.assertEquals(Optional.empty(),
					observedTaskControls.get(4));
		} finally {
			soklet.close();
		}
	}

	private static McpServer taskControlServer(@NonNull McpEndpoint endpoint,
			@NonNull Optional<@NonNull McpTaskManager> taskManager) {
		return taskControlServer(endpoint, taskManager,
				McpHandlerInterceptor.passThroughInstance());
	}

	private static McpServer taskControlServer(@NonNull McpEndpoint endpoint,
			@NonNull Optional<@NonNull McpTaskManager> taskManager,
			@NonNull McpHandlerInterceptor handlerInterceptor) {
		McpServer.Builder builder = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(LOOPBACK)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.handlerInterceptor(handlerInterceptor)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
		taskManager.ifPresent(builder::taskManager);
		return builder.build();
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static McpServer server(McpAdmissionController admissionController) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"extension-compatibility-test", "4.0.0").build())
				.build();
		return server(endpoint, admissionController,
				McpHandlerInterceptor.passThroughInstance());
	}

	private static McpServer server(McpEndpoint endpoint,
			McpAdmissionController admissionController,
			McpHandlerInterceptor handlerInterceptor) {
		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).admissionController(admissionController)
				.host(LOOPBACK)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.handlerInterceptor(handlerInterceptor)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	private static void assertExtensionSettings(
			McpClientCapabilities capabilities) {
		McpJsonObject extension = capabilities.findExtension(EXTENSION_ID)
				.orElseThrow();
		Assertions.assertEquals(McpJsonBoolean.fromValue(true),
				extension.getMembers().get("enabled"));
		Assertions.assertEquals(EXTENSION_SECRET,
				Assertions.assertInstanceOf(McpJsonString.class,
						extension.getMembers().get("secret")).getValue());
		Assertions.assertEquals(Set.of(EXTENSION_ID),
				capabilities.getExtensions().keySet());
	}

	private static HttpResponse<String> callNegotiatedTool(int port)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"negotiated\","
				+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{"
				+ "\"extensions\":{\"" + EXTENSION_ID + "\":{"
				+ "\"enabled\":true,\"secret\":\"" + EXTENSION_SECRET + "\"}}},"
				+ "\"" + REQUEST_METADATA_KEY + "\":{\"mode\":\"expanded\"}},"
				+ "\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", TOOL_NAME)
				.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	private static HttpResponse<String> callTaskControlTool(int port, String id,
			boolean tasksCapable) throws Exception {
		String extensions = tasksCapable
				? "{\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}}"
				: "{}";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\","
				+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ extensions + "},\"name\":\"" + TOOL_NAME + "\","
				+ "\"arguments\":{\"query\":\"catalog\"}}}";
		return post(port, "tools/call", TOOL_NAME, body);
	}

	private static HttpResponse<String> callLargeTaskControlTool(int port,
			String id) throws Exception {
		String chunk = "x".repeat(900_000);
		StringBuilder arguments = new StringBuilder(4_500_128);
		arguments.append("{\"chunks\":[");
		for (int index = 0; index < 5; ++index) {
			if (index > 0)
				arguments.append(',');
			arguments.append('"').append(chunk).append('"');
		}
		arguments.append("]}");
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\","
				+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{"
				+ "\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}}},"
				+ "\"name\":\"" + TOOL_NAME + "\",\"arguments\":"
				+ arguments + "}}";
		return post(port, "tools/call", TOOL_NAME, body);
	}

	private static HttpResponse<String> callTaskControlPrompt(int port, String id)
			throws Exception {
		String promptName = "tasks.control.prompt";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\","
				+ "\"method\":\"prompts/get\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{"
				+ "\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}}},"
				+ "\"name\":\"" + promptName + "\"}}";
		return post(port, "prompts/get", promptName, body);
	}

	private static HttpResponse<String> callWithObsoleteTask(int port)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"obsolete-task\","
				+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{"
				+ "\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}}},"
				+ "\"name\":\"" + TOOL_NAME + "\",\"arguments\":{},"
				+ "\"task\":{\"ttl\":60000}}}";
		return post(port, "tools/call", TOOL_NAME, body);
	}

	private static HttpResponse<String> callRemovedTasksResult(int port)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"removed-tasks-result\","
				+ "\"method\":\"tasks/result\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{"
				+ "\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}}},"
				+ "\"taskId\":\"legacy-task\"}}";
		return post(port, "tasks/result", null, body);
	}

	private static HttpResponse<String> post(int port, String method,
			String operationName, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		if (operationName != null)
			request.header("Mcp-Name", operationName);
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static HttpResponse<String> discover(int port, String id,
			String clientCapabilities) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ clientCapabilities + "}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "server/discover")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private record TaskControlArguments(@NonNull String query,
			@NonNull List<@NonNull Integer> pageSizes) {
		private TaskControlArguments {
			TASK_CONTROL_ARGUMENT_CONSTRUCTIONS.incrementAndGet();
			if ("reject".equals(query))
				throw new IllegalArgumentException(
						"Rejected only to exercise failed-decode memoization.");
		}
	}
}
