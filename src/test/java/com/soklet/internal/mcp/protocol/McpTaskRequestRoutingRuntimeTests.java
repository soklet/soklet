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

import com.soklet.CorsAuthorizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@NotThreadSafe
@Timeout(60)
public class McpTaskRequestRoutingRuntimeTests {
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TASKS_EXTENSION_IDENTIFIER =
			"io.modelcontextprotocol/tasks";
	private static final List<String> TASK_METHODS =
			List.of("tasks/get", "tasks/update", "tasks/cancel");

	@Test
	public void taskMethodsRequireMatchingTaskIdRoutingHeader() throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger invocations = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(true, admissions, invocations,
				McpRuntimeObservationSink.disabledInstance());

		try {
			int port = runtime.start().getPort();
			int requestId = 1;
			for (String method : TASK_METHODS) {
				String fields = taskFields(method, "task-" + requestId);
				FixedResponse success = send(port,
						request(requestId, method, fields, true),
						headers(method, "task-" + requestId));
				assertSuccess(success);

				FixedResponse missing = send(port,
						request(++requestId, method,
								taskFields(method, "task-" + requestId), true),
						headers(method, null));
				assertError(missing, McpJsonRpcError.HEADER_MISMATCH);

				FixedResponse mismatched = send(port,
						request(++requestId, method,
								taskFields(method, "task-" + requestId), true),
						headers(method, "different-task"));
				assertError(mismatched, McpJsonRpcError.HEADER_MISMATCH);
				++requestId;
			}

			Assertions.assertEquals(3, admissions.get());
			Assertions.assertEquals(3, invocations.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void taskMethodsRequireNegotiatedExtensionOnlyWhenServerSupportsTasks()
			throws Exception {
		AtomicInteger supportedAdmissions = new AtomicInteger();
		AtomicInteger supportedInvocations = new AtomicInteger();
		McpHttpServerRuntime supported = runtime(true, supportedAdmissions,
				supportedInvocations, McpRuntimeObservationSink.disabledInstance());
		AtomicInteger unsupportedAdmissions = new AtomicInteger();
		AtomicInteger unsupportedInvocations = new AtomicInteger();
		McpHttpServerRuntime unsupported = runtime(false, unsupportedAdmissions,
				unsupportedInvocations, McpRuntimeObservationSink.disabledInstance());

		try {
			int supportedPort = supported.start().getPort();
			FixedResponse missingCapability = send(supportedPort,
					request(20, "tasks/get", "\"taskId\":\"task-20\"", false),
					headers("tasks/get", "task-20"));
			assertError(missingCapability,
					McpJsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY);
			Assertions.assertTrue(missingCapability.body().contains(
					"\"requiredCapabilities\":{\"extensions\":{\""
							+ TASKS_EXTENSION_IDENTIFIER + "\":{}}}"),
					missingCapability.body());
			Assertions.assertEquals(0, supportedAdmissions.get());
			Assertions.assertEquals(0, supportedInvocations.get());

			int unsupportedPort = unsupported.start().getPort();
			FixedResponse methodNotFound = send(unsupportedPort,
					request(21, "tasks/get", "\"taskId\":\"task-21\"", false),
					headers("tasks/get", "task-21"));
			assertError(methodNotFound, McpJsonRpcError.METHOD_NOT_FOUND);
			Assertions.assertFalse(methodNotFound.body().contains("-32021"),
					methodNotFound.body());
			Assertions.assertEquals(0, unsupportedAdmissions.get());
			Assertions.assertEquals(0, unsupportedInvocations.get());
		} finally {
			supported.close();
			unsupported.close();
		}
	}

	@Test
	public void taskParametersAreOpenAndUpdateResponsesReachObservation()
			throws Exception {
		List<McpRuntimeRequestInput> observed = new CopyOnWriteArrayList<>();
		McpRuntimeObservationSink observationSink = input -> {
			observed.add(input);
			return McpRuntimeRequestObservation.disabledInstance();
		};
		AtomicInteger invocations = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(true, new AtomicInteger(), invocations,
				observationSink);

		try {
			int port = runtime.start().getPort();
			String taskId = "durable-task-1";
			String updateFields = "\"taskId\":\"" + taskId + "\"," +
					"\"inputResponses\":{\"answer\":{\"action\":\"decline\"}}," +
					"\"future\":{\"preserved\":true}";
			assertSuccess(send(port, request(30, "tasks/update", updateFields, true),
					headers("tasks/update", taskId)));

			for (String method : List.of("tasks/get", "tasks/cancel"))
				assertSuccess(send(port, request(31 + observed.size(), method,
						"\"taskId\":\"" + taskId + "\"," +
								"\"inputResponses\":\"ignored-open-member\"," +
								"\"future\":[true,null]", true),
						headers(method, taskId)));

			Assertions.assertEquals(3, invocations.get());
			Assertions.assertEquals(3, observed.size());
			for (McpRuntimeRequestInput input : observed)
				Assertions.assertEquals(taskId,
						input.operationName().orElseThrow());
			McpJsonObject updateResponses = observed.get(0).inputResponses();
			McpJsonObject answer = Assertions.assertInstanceOf(McpJsonObject.class,
					updateResponses.members().get("answer"));
			Assertions.assertEquals(new McpJsonString("decline"),
					answer.members().get("action"));
			Assertions.assertTrue(observed.get(1).inputResponses().members().isEmpty());
			Assertions.assertTrue(observed.get(2).inputResponses().members().isEmpty());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void updateRequiresValidInputResponsesAndTaskIdsRejectNewlines()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger invocations = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(true, admissions, invocations,
				McpRuntimeObservationSink.disabledInstance());

		try {
			int port = runtime.start().getPort();
			List<String> invalidUpdateFields = List.of(
					"\"taskId\":\"task-40\"",
					"\"taskId\":\"task-40\",\"inputResponses\":[]",
					"\"taskId\":\"task-40\",\"inputResponses\":{\"answer\":{}}"
			);
			for (int index = 0; index < invalidUpdateFields.size(); ++index) {
				FixedResponse response = send(port,
						request(40 + index, "tasks/update",
								invalidUpdateFields.get(index), true),
						headers("tasks/update", "task-40"));
				assertError(response, McpJsonRpcError.INVALID_PARAMS);
			}

			String newlineTaskId = "line1\nline2";
			FixedResponse newline = send(port,
					request(50, "tasks/get",
							"\"taskId\":\"line1\\nline2\"", true),
					headers("tasks/get", "=?base64?bGluZTEKbGluZTI=?="));
			assertError(newline, McpJsonRpcError.INVALID_PARAMS);
			Assertions.assertEquals(0, admissions.get());
			Assertions.assertEquals(0, invocations.get());
		} finally {
			runtime.close();
		}
	}

	private static String taskFields(String method, String taskId) {
		String fields = "\"taskId\":\"" + taskId + "\"";
		return "tasks/update".equals(method)
				? fields + ",\"inputResponses\":{}" : fields;
	}

	private static McpHttpServerRuntime runtime(boolean tasksSupported,
			AtomicInteger admissions, AtomicInteger invocations,
			McpRuntimeObservationSink observationSink) {
		McpNormalizedEndpoint.Builder endpointBuilder =
				McpNormalizedEndpoint.withServerInformation(
						McpImplementationMetadata.withNameAndVersion(
								"task-routing-test", "4.0.0"));
		if (tasksSupported)
			endpointBuilder.serverExtension(TASKS_EXTENSION_IDENTIFIER,
					McpJsonObject.empty());
		McpNormalizedEndpoint endpoint = endpointBuilder.build();
		McpApplicationRequestHandler handler = invocation -> {
			invocations.incrementAndGet();
			return McpWireResult.complete(new McpJsonObject(
					Map.of("accepted", McpJsonBoolean.TRUE)));
		};
		Map<String, McpApplicationRequestHandler> handlers = Map.of(
				"tasks/get", handler,
				"tasks/update", handler,
				"tasks/cancel", handler);
		McpApplicationRequestRouter router =
				McpApplicationRequestRouter
						.fromFrameworkHandlersAndValidatedOperationRoutes(
								handlers, Map.of(), Map.of(), Map.of(), List.of(),
								Optional.empty());
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				});
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy, endpoint,
				router, observationSink);
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				List.of(binding), McpJsonLimits.productionDefaults(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {});
	}

	private static FixedResponse send(int port, String body,
			List<McpChunkedHttpClient.RequestHeader> headers) throws Exception {
		try (McpChunkedHttpClient client =
					McpChunkedHttpClient.postMcpMessage(port, body, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			return new FixedResponse(head, client.readFixedBody(head));
		}
	}

	private static List<McpChunkedHttpClient.RequestHeader> headers(
			String method, String taskId) {
		List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>();
		headers.add(new McpChunkedHttpClient.RequestHeader(
				"MCP-Protocol-Version", PROTOCOL_VERSION));
		headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Method", method));
		if (taskId != null)
			headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Name", taskId));
		return List.copyOf(headers);
	}

	private static String request(int id, String method, String fields,
			boolean tasksCapability) {
		String capabilities = tasksCapability
				? "{\"extensions\":{\"" + TASKS_EXTENSION_IDENTIFIER + "\":{}}}"
				: "{}";
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id
				+ ",\"method\":\"" + method + "\",\"params\":{" + fields
				+ (fields.isEmpty() ? "" : ",") + "\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ capabilities + "}}}";
	}

	private static void assertSuccess(FixedResponse response) {
		Assertions.assertEquals(200, response.head().status(), response.body());
		Assertions.assertTrue(response.body().contains("\"resultType\":\"complete\""),
				response.body());
	}

	private static void assertError(FixedResponse response, int errorCode) {
		Assertions.assertTrue(response.head().status() >= 400,
				response.head().raw());
		Assertions.assertTrue(response.body().contains("\"code\":" + errorCode),
				response.body());
		Assertions.assertEquals("no-store",
				response.head().singleHeader("Cache-Control"));
	}

	private record FixedResponse(McpChunkedHttpClient.HttpResponseHead head,
			String body) {
	}
}
