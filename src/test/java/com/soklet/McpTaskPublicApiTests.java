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
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Public value and contract coverage for MCP Tasks.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpTaskPublicApiTests {
	private static final Instant CREATED_AT =
			Instant.parse("2026-09-08T12:00:00Z");
	private static final Instant LAST_UPDATED_AT =
			Instant.parse("2026-09-08T12:01:00Z");

	@Test
	public void taskOriginIsOpaqueStructuralAndRedacted() {
		McpTaskOrigin first = McpTaskOrigin.fromPersistedState(
				McpJsonObject.builder()
						.put("version", 1)
						.put("arguments", secretArguments())
						.build());
		McpTaskOrigin equal = McpTaskOrigin.fromPersistedState(
				McpJsonObject.builder()
						.put("arguments", secretArguments())
						.put("version", 1)
						.build());
		McpTaskOrigin different = McpTaskOrigin.fromPersistedState(
				McpJsonObject.builder().put("version", 2).build());

		Assertions.assertEquals(first, equal);
		Assertions.assertEquals(first.hashCode(), equal.hashCode());
		Assertions.assertNotEquals(first, different);
		Assertions.assertSame(first.getPersistedState(),
				first.getPersistedState());
		Assertions.assertFalse(first.toString().contains("secret-argument"));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpTaskOrigin.fromPersistedState(null));
	}

	@Test
	public void workingTaskRoundTripsOptionalStateAndComparesStructurally() {
		McpTask first = workingTask(origin(), metadata("one", "two"));
		McpTask equal = workingTask(origin(), metadata("two", "one"));
		McpTask different = McpTask.withTaskId("task-2", origin(),
				McpTaskStatus.WORKING, CREATED_AT, LAST_UPDATED_AT)
				.timeToLive(Duration.ofMinutes(5))
				.pollInterval(Duration.ofSeconds(2))
				.build();

		Assertions.assertEquals(first, equal);
		Assertions.assertEquals(first.hashCode(), equal.hashCode());
		Assertions.assertNotEquals(first, different);
		Assertions.assertEquals("task-1", first.getTaskId());
		Assertions.assertEquals(origin(), first.getTaskOrigin());
		Assertions.assertEquals(McpTaskStatus.WORKING,
				first.getTaskStatus());
		Assertions.assertEquals("working",
				first.getTaskStatusMessage().orElseThrow());
		Assertions.assertEquals(CREATED_AT, first.getCreatedAt());
		Assertions.assertEquals(LAST_UPDATED_AT, first.getLastUpdatedAt());
		Assertions.assertEquals(Duration.ofMinutes(5),
				first.getTimeToLive().orElseThrow());
		Assertions.assertEquals(Duration.ofSeconds(2),
				first.getPollInterval().orElseThrow());
		Assertions.assertTrue(first.getInputRequests().isEmpty());
		Assertions.assertTrue(first.getCompletedResult().isEmpty());
		Assertions.assertTrue(first.getFailure().isEmpty());
		Assertions.assertEquals(metadata("one", "two"), first.getMetadata());
		Assertions.assertFalse(first.toString().contains("task-1"));
		Assertions.assertFalse(first.toString().contains("working"));
	}

	@Test
	public void eachStatusAcceptsOnlyItsRequiredPayload() {
		McpInputRequest inputRequest = inputRequest();
		McpCompleteResult completedResult =
				McpCompleteResult.fromToolText("finished");
		McpJsonRpcError failure = McpJsonRpcError.fromApplication(
				42, "failed safely");

		McpTask inputRequired = task(McpTaskStatus.INPUT_REQUIRED)
				.addInputRequest("name", inputRequest)
				.build();
		McpTask completed = task(McpTaskStatus.COMPLETED)
				.completedResult(completedResult)
				.build();
		McpTask failed = task(McpTaskStatus.FAILED)
				.failure(failure)
				.build();
		McpTask canceled = task(McpTaskStatus.CANCELED).build();

		Assertions.assertEquals(Map.of("name", inputRequest),
				inputRequired.getInputRequests());
		Assertions.assertEquals(completedResult,
				completed.getCompletedResult().orElseThrow());
		Assertions.assertEquals(failure, failed.getFailure().orElseThrow());
		Assertions.assertEquals(McpTaskStatus.CANCELED,
				canceled.getTaskStatus());

		Assertions.assertThrows(IllegalStateException.class,
				() -> task(McpTaskStatus.INPUT_REQUIRED).build());
		Assertions.assertThrows(IllegalStateException.class,
				() -> task(McpTaskStatus.COMPLETED).build());
		Assertions.assertThrows(IllegalStateException.class,
				() -> task(McpTaskStatus.FAILED).build());
		Assertions.assertThrows(IllegalStateException.class,
				() -> task(McpTaskStatus.WORKING)
						.completedResult(completedResult).build());
		Assertions.assertThrows(IllegalStateException.class,
				() -> task(McpTaskStatus.CANCELED)
						.addInputRequest("name", inputRequest).build());
		Assertions.assertThrows(IllegalStateException.class,
				() -> task(McpTaskStatus.COMPLETED)
						.completedResult(completedResult).failure(failure).build());
	}

	@Test
	public void taskBuilderValidatesIdentifiersTimesDurationsAndCollections() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpTaskCreatedResult.fromTaskId(" \t "));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpTaskCreatedResult.fromTaskId("task\r\ninjected"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpTask.withTaskId("task", origin(),
						McpTaskStatus.WORKING, LAST_UPDATED_AT, CREATED_AT));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> task(McpTaskStatus.WORKING)
						.timeToLive(Duration.ofMillis(-1)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> task(McpTaskStatus.WORKING)
						.timeToLive(Duration.ZERO));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> task(McpTaskStatus.WORKING)
						.timeToLive(Duration.ofNanos(1)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> task(McpTaskStatus.WORKING)
						.pollInterval(Duration.ZERO));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> task(McpTaskStatus.WORKING)
						.pollInterval(Duration.ofNanos(1)));

		McpTask unlimited = task(McpTaskStatus.WORKING)
				.timeToLive(null)
				.pollInterval(null)
				.taskStatusMessage(null)
				.metadata(null)
				.build();
		Assertions.assertTrue(unlimited.getTimeToLive().isEmpty());
		Assertions.assertTrue(unlimited.getPollInterval().isEmpty());
		Assertions.assertTrue(unlimited.getTaskStatusMessage().isEmpty());
		Assertions.assertSame(McpJsonObject.emptyInstance(),
				unlimited.getMetadata());

		McpTask.Builder builder = task(McpTaskStatus.INPUT_REQUIRED)
				.addInputRequest("one", inputRequest());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> builder.addInputRequests(Map.of("one", inputRequest())));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> builder.build().getInputRequests().clear());

		McpJsonObject reservedMetadata = McpJsonObject.builder()
				.put("io.modelcontextprotocol/related-task", "obsolete")
				.build();
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> task(McpTaskStatus.WORKING)
						.metadata(reservedMetadata).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpCompleteResult.fromToolText("complete")
						.withMetadata(reservedMetadata));
	}

	@Test
	public void taskCreatedResultsPreservePhantomOutputTypeAndValueSemantics() {
		McpTaskCreatedResult<String> first =
				McpTaskCreatedResult.fromTaskId("task-1");
		McpTaskCreatedResult<Integer> equal =
				McpTaskCreatedResult.fromTaskId("task-1");
		McpTaskCreatedResult<String> different =
				McpTaskCreatedResult.fromTaskId("task-2");

		Assertions.assertEquals(first, equal);
		Assertions.assertEquals(first.hashCode(), equal.hashCode());
		Assertions.assertNotEquals(first, different);
		Assertions.assertEquals("task-1", first.getTaskId());
		Assertions.assertFalse(first.toString().contains("task-1"));
	}

	@Test
	public void taskManagerContextsHavePublicTestFixtureFactories() {
		McpRequestContext requestContext = requestContext();
		McpInputResponses inputResponses = McpInputResponses.fromResponses(
				Map.of("answer", McpJsonString.fromValue("yes")));

		McpTaskRequestContext taskRequestContext =
				McpTaskRequestContext.fromComponents(requestContext, "task-1");
		McpTaskUpdateContext taskUpdateContext =
				McpTaskUpdateContext.fromComponents(requestContext, "task-1",
						inputResponses);

		Assertions.assertSame(requestContext,
				taskRequestContext.getRequestContext());
		Assertions.assertEquals("task-1", taskRequestContext.getTaskId());
		Assertions.assertSame(requestContext,
				taskUpdateContext.getRequestContext());
		Assertions.assertEquals("task-1", taskUpdateContext.getTaskId());
		Assertions.assertSame(inputResponses,
				taskUpdateContext.getInputResponses());

		Assertions.assertThrows(NullPointerException.class, () ->
				McpTaskRequestContext.fromComponents(null, "task-1"));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpTaskRequestContext.fromComponents(requestContext, " \t "));
		Assertions.assertThrows(NullPointerException.class, () ->
				McpTaskUpdateContext.fromComponents(requestContext, "task-1",
						null));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpTaskUpdateContext.fromComponents(requestContext,
						"task\r\ninjected", inputResponses));
	}

	@Test
	public void completeResultsCompareNestedResourceContentsStructurally() {
		URI textUri = URI.create("test://tasks/text");
		URI blobUri = URI.create("test://tasks/blob");
		McpResourceOutput first = McpResourceOutput.withContent(
				McpTextResourceContents.withUriAndText(textUri, "text").build())
				.addContent(McpBlobResourceContents
						.withUriAndData(blobUri, new byte[] { 1, 2, 3 }).build())
				.cacheTimeToLiveOverride(Duration.ofSeconds(5))
				.build();
		McpResourceOutput equal = McpResourceOutput.withContent(
				McpTextResourceContents.withUriAndText(textUri, "text").build())
				.addContent(McpBlobResourceContents
						.withUriAndData(blobUri, new byte[] { 1, 2, 3 }).build())
				.cacheTimeToLiveOverride(Duration.ofSeconds(5))
				.build();
		McpResourceOutput different = McpResourceOutput.withContent(
				McpTextResourceContents.withUriAndText(textUri, "different").build())
				.build();

		Assertions.assertEquals(first, equal);
		Assertions.assertEquals(first.hashCode(), equal.hashCode());
		Assertions.assertNotEquals(first, different);
		Assertions.assertEquals(
				McpCompleteResult.fromResourceOutput(first),
				McpCompleteResult.fromResourceOutput(equal));
	}

	@Test
	public void taskControlIsDiscoverableOnlyAsAnOptionalInvocationFeature() {
		McpTaskOrigin origin = origin();
		McpTaskControl taskControl = new McpTaskControl() {
			@Override
			public McpRequestContext getRequestContext() {
				throw new UnsupportedOperationException();
			}

			@Override
			public McpTaskOrigin getTaskOrigin() {
				return origin;
			}
		};
		McpInvocationFeatures present = McpInvocationFeatures.fromFeatures(
				Map.of(McpTaskControl.class, taskControl));
		McpInvocationFeatures absent =
				McpInvocationFeatures.fromFeatures(Map.of());

		Assertions.assertSame(taskControl,
				present.getTaskControl().orElseThrow());
		Assertions.assertTrue(absent.getTaskControl().isEmpty());
	}

	@Test
	public void taskOperationsHaveStableHighLevelTypes() {
		Assertions.assertEquals(McpOperationType.TASKS_GET,
				McpOperationType.fromJsonRpcMethod("tasks/get"));
		Assertions.assertEquals(McpOperationType.TASKS_UPDATE,
				McpOperationType.fromJsonRpcMethod("tasks/update"));
		Assertions.assertEquals(McpOperationType.TASKS_CANCEL,
				McpOperationType.fromJsonRpcMethod("tasks/cancel"));
	}

	@Test
	public void newTaskValueApiUsesClassesRatherThanRecords() {
		for (Class<?> type : new Class<?>[]{McpTask.class,
				McpTaskOrigin.class, McpTaskCreatedResult.class,
				McpTaskRequestContext.class, McpTaskUpdateContext.class}) {
			Assertions.assertFalse(type.isRecord(), type.getName());
		}
	}

	private static McpTask workingTask(McpTaskOrigin origin,
			McpJsonObject metadata) {
		return McpTask.withTaskId("task-1", origin, McpTaskStatus.WORKING,
				CREATED_AT, LAST_UPDATED_AT)
				.taskStatusMessage("working")
				.timeToLive(Duration.ofMinutes(5))
				.pollInterval(Duration.ofSeconds(2))
				.metadata(metadata)
				.build();
	}

	private static McpTask.Builder task(McpTaskStatus taskStatus) {
		return McpTask.withTaskId("task-1", origin(), taskStatus,
				CREATED_AT, LAST_UPDATED_AT);
	}

	private static McpTaskOrigin origin() {
		return McpTaskOrigin.fromPersistedState(McpJsonObject.builder()
				.put("version", 1)
				.put("operation", "tools/call")
				.put("arguments", secretArguments())
				.build());
	}

	private static McpJsonObject secretArguments() {
		return McpJsonObject.builder()
				.put("value", "secret-argument")
				.build();
	}

	private static McpJsonObject metadata(String first, String second) {
		return McpJsonObject.builder()
				.put(first, first)
				.put(second, second)
				.build();
	}

	private static McpInputRequest inputRequest() {
		return McpInputRequest.fromDeclaration(
				McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.CONDITIONAL),
				McpJsonObject.emptyInstance());
	}

	private static McpRequestContext requestContext() {
		return (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class},
				(proxy, method, arguments) -> {
					throw new UnsupportedOperationException(method.getName());
				});
	}
}
