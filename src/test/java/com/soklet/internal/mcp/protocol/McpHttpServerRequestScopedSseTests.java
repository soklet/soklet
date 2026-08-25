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
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.MetricsCollector;
import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

@NotThreadSafe
@Timeout(20)
public class McpHttpServerRequestScopedSseTests {
	private static final String APPLICATION_METHOD = "test/execute";

	@AfterEach
	public void resetTestHooks() {
		McpRequestSseStream.setTestHooks(null);
	}

	@Test
	public void completion_without_related_notifications_remains_json()
			throws Exception {
		McpHttpServerRuntime runtime = runtime(invocation -> completeResult("json"));
		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"json-only\"", APPLICATION_METHOD)) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				Assertions.assertEquals(200, head.status(), head.raw());
				Assertions.assertEquals("application/json",
						head.singleHeader("Content-Type"));
				Assertions.assertEquals("no-store",
						head.singleHeader("Cache-Control"));
				Assertions.assertTrue(head.hasHeader("Content-Length"));
				Assertions.assertFalse(head.hasHeader("Transfer-Encoding"));
				Assertions.assertFalse(head.hasHeader("X-Accel-Buffering"));
				Assertions.assertEquals(
						"{\"jsonrpc\":\"2.0\",\"id\":\"json-only\","
								+ "\"result\":{\"value\":\"json\","
								+ "\"resultType\":\"complete\"}}",
						client.readFixedBody(head));
			}
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void related_notification_commits_sse_then_terminal_result_ends_stream()
			throws Exception {
		McpHttpServerRuntime runtime = runtime(invocation -> {
			Assertions.assertTrue(invocation.sendNotification(progress("token-1", 1)));
			return completeResult("streamed");
		});
		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"stream-1\"", APPLICATION_METHOD)) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				Assertions.assertEquals(200, head.status(), head.raw());
				Assertions.assertEquals("text/event-stream",
						head.singleHeader("Content-Type"));
				Assertions.assertEquals("no-store",
						head.singleHeader("Cache-Control"));
				Assertions.assertEquals("no",
						head.singleHeader("X-Accel-Buffering"));
				Assertions.assertEquals("chunked",
						head.singleHeader("Transfer-Encoding"));
				Assertions.assertFalse(head.hasHeader("Content-Length"));

				Assertions.assertEquals(
						"data: {\"jsonrpc\":\"2.0\","
								+ "\"method\":\"notifications/progress\","
								+ "\"params\":{\"progressToken\":\"token-1\","
								+ "\"progress\":1}}\n\n",
						client.readChunkText());
				Assertions.assertEquals(
						"data: {\"jsonrpc\":\"2.0\",\"id\":\"stream-1\","
								+ "\"result\":{\"value\":\"streamed\","
								+ "\"resultType\":\"complete\"}}\n\n",
						client.readChunkText());
				Assertions.assertNull(client.readChunk(),
						"The final JSON-RPC response must terminate the SSE stream.");
			}
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void reset_after_sse_commit_cancels_and_interrupts_application_work()
			throws Exception {
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		AtomicReference<Optional<StreamTerminationReason>> cancellationReason =
				new AtomicReference<>(Optional.empty());
		McpHttpServerRuntime runtime = runtime(invocation -> {
			invocation.sendNotification(progress("disconnect", 1));
			try {
				emergencyRelease.await();
			} catch (InterruptedException exception) {
				cancellationReason.set(invocation.cancellationReason());
				handlerInterrupted.countDown();
				throw exception;
			}
			return completeResult("must-not-be-written");
		});

		try {
			int port = runtime.start().getPort();
			McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"disconnect\"", APPLICATION_METHOD);
			try {
				Assertions.assertEquals("text/event-stream",
						client.readHead().singleHeader("Content-Type"));
				Assertions.assertTrue(client.readChunkText().contains(
						"\"progressToken\":\"disconnect\""));
				client.closeWithReset();
				Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS),
						"A committed-stream reset did not interrupt the handler.");
				Assertions.assertEquals(
						Optional.of(StreamTerminationReason.CLIENT_DISCONNECTED),
						cancellationReason.get());
				awaitClean(runtime);
			} finally {
				client.close();
			}
		} finally {
			emergencyRelease.countDown();
			runtime.close();
		}
	}

	@Test
	public void keep_alive_write_does_not_extend_the_absolute_request_deadline()
			throws Exception {
		ControllableClock clock = new ControllableClock();
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		AtomicReference<Optional<StreamTerminationReason>> cancellationReason =
				new AtomicReference<>(Optional.empty());
		McpApplicationRequestHandler handler = invocation -> {
			invocation.sendNotification(progress("deadline", 1));
			try {
				emergencyRelease.await();
			} catch (InterruptedException exception) {
				cancellationReason.set(invocation.cancellationReason());
				handlerInterrupted.countDown();
				throw exception;
			}
			return completeResult("too-late");
		};
		McpHttpServerRuntime runtime = runtime(handler, clock,
				transportConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(10)),
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(5), Duration.ofDays(1)));

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"deadline\"", APPLICATION_METHOD)) {
				Assertions.assertEquals("text/event-stream",
						client.readHead().singleHeader("Content-Type"));
				Assertions.assertTrue(client.readChunkText().contains(
						"\"progressToken\":\"deadline\""));

				clock.advance(Duration.ofSeconds(1));
				runtime.runApplicationTimerCycle();
				Assertions.assertEquals(": keepalive\n\n", client.readChunkText());

				clock.advance(Duration.ofSeconds(4));
				runtime.runApplicationTimerCycle();
				Assertions.assertTrue(client.awaitTransportClosure(),
						"The absolute deadline did not close the committed stream.");
				Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS),
						"The absolute deadline did not interrupt the handler.");
				Assertions.assertEquals(
						Optional.of(StreamTerminationReason.RESPONSE_TIMEOUT),
						cancellationReason.get());
			}
			awaitClean(runtime);
		} finally {
			emergencyRelease.countDown();
			runtime.close();
		}
	}

	@Test
	public void response_write_idle_timeout_closes_stream_and_interrupts_handler()
			throws Exception {
		ControllableClock clock = new ControllableClock();
		WriteTimeoutObservation observation = new WriteTimeoutObservation();
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		AtomicReference<Optional<StreamTerminationReason>> cancellationReason =
				new AtomicReference<>(Optional.empty());
		McpHttpServerRuntime runtime = runtime(invocation -> {
			invocation.sendNotification(progress("write-idle", 1));
			try {
				emergencyRelease.await();
			} catch (InterruptedException exception) {
				cancellationReason.set(invocation.cancellationReason());
				handlerInterrupted.countDown();
				throw exception;
			}
			return completeResult("must-not-be-written");
		}, clock,
				transportConfiguration(Duration.ofSeconds(4), Duration.ofSeconds(5)),
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofDays(1), Duration.ofDays(1)),
				observation, observation);

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"write-idle\"", APPLICATION_METHOD)) {
				Assertions.assertEquals("text/event-stream",
						client.readHead().singleHeader("Content-Type"));
				Assertions.assertTrue(client.readChunkText().contains(
						"\"progressToken\":\"write-idle\""));

				clock.advance(Duration.ofSeconds(5));
				runtime.runApplicationTimerCycle();

				Assertions.assertTrue(client.awaitTransportClosure(),
						"The write-idle timeout did not close the committed stream.");
				Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS),
						"The write-idle timeout did not interrupt the handler.");
					Assertions.assertEquals(
							Optional.of(StreamTerminationReason.RESPONSE_IDLE_TIMEOUT),
							cancellationReason.get());
				}
			awaitClean(runtime);
			observation.awaitFinished();
			runtime.runApplicationTimerCycle();
			observation.awaitTransportFailureReasons(List.of(
					MetricsCollector.TransportFailureReason.WRITE_TIMEOUT));
			Assertions.assertEquals(List.of(
					MetricsCollector.TransportFailureReason.WRITE_TIMEOUT),
					observation.transportFailureReasons());
			Assertions.assertEquals(List.of("stream-opened",
					"transport-failure:WRITE_TIMEOUT",
					"stream-closed:RESPONSE_IDLE_TIMEOUT", "request-finished"),
					observation.order(),
					"The exact write-idle winner must be recorded once before its close and finish terminals.");
		} finally {
			emergencyRelease.countDown();
			runtime.close();
		}
	}

	@Test
	public void generic_stream_termination_discards_losing_write_timeout()
			throws Exception {
		ControllableClock clock = new ControllableClock();
		WriteTimeoutObservation observation = new WriteTimeoutObservation();
		AtomicLong hookInvocations = new AtomicLong();
		McpRequestSseStream.setTestHooks(new McpRequestSseStream.TestHooks() {
			@Override
			public void beforeTerminalReservation() {
			}

			@Override
			public void beforeWriteIdleFailureAttempt(
					Runnable competingTermination) {
				hookInvocations.incrementAndGet();
				competingTermination.run();
			}
		});
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		AtomicReference<Optional<StreamTerminationReason>> cancellationReason =
				new AtomicReference<>(Optional.empty());
		McpHttpServerRuntime runtime = runtime(invocation -> {
			invocation.sendNotification(progress("write-idle-loser", 1));
			try {
				emergencyRelease.await();
			} catch (InterruptedException exception) {
				cancellationReason.set(invocation.cancellationReason());
				handlerInterrupted.countDown();
				throw exception;
			}
			return completeResult("must-not-be-written");
		}, clock,
				transportConfiguration(Duration.ofSeconds(4), Duration.ofSeconds(5)),
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofDays(1), Duration.ofDays(1)),
				observation, observation);

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"write-idle-loser\"", APPLICATION_METHOD)) {
				Assertions.assertEquals("text/event-stream",
						client.readHead().singleHeader("Content-Type"));
				Assertions.assertTrue(client.readChunkText().contains(
						"\"progressToken\":\"write-idle-loser\""));

				clock.advance(Duration.ofSeconds(5));
				runtime.runApplicationTimerCycle();
				Assertions.assertTrue(client.awaitTransportClosure());
				Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS));
				Assertions.assertEquals(
						Optional.of(StreamTerminationReason.RESPONSE_IDLE_TIMEOUT),
						cancellationReason.get());
			}
			awaitClean(runtime);
			observation.awaitFinished();
			runtime.runApplicationTimerCycle();
			observation.awaitTransportFailureReasons(List.of());
			Assertions.assertEquals(1L, hookInvocations.get());
			Assertions.assertTrue(observation.transportFailureReasons().isEmpty(),
					"The generic termination winner must discard provisional WRITE_TIMEOUT.");
			Assertions.assertEquals(List.of("stream-opened",
					"stream-closed:RESPONSE_IDLE_TIMEOUT", "request-finished"),
					observation.order());
		} finally {
			emergencyRelease.countDown();
			runtime.close();
		}
	}

	@Test
	public void slow_reader_applies_bounded_backpressure_without_blocking_other_requests()
			throws Exception {
		CountDownLatch secondNotificationAttempted = new CountDownLatch(1);
		CountDownLatch secondNotificationFinished = new CountDownLatch(1);
		McpApplicationRequestHandler handler = invocation -> {
			String requestId = ((McpJsonRpcId.StringId) invocation.request().id()).value();
			if (!"slow".equals(requestId))
				return completeResult("fast");

			invocation.sendNotification(largeProgress("slow"));
			secondNotificationAttempted.countDown();
			try {
				invocation.sendNotification(progress("slow", 2));
			} finally {
				secondNotificationFinished.countDown();
			}
			return completeResult("slow");
		};
		McpHttpServerRuntime runtime = runtime(handler, McpApplicationClock.SYSTEM,
				transportConfiguration(Duration.ofSeconds(15),
						Duration.ofSeconds(60), 1),
				new McpApplicationExecutionConfiguration(
						2, 2, Duration.ofSeconds(30), Duration.ofMillis(10)));

		McpChunkedHttpClient slow = null;
		try {
			int port = runtime.start().getPort();
			slow = McpChunkedHttpClient.postMcp(
					port, "\"slow\"", APPLICATION_METHOD, 1_024);
			Assertions.assertEquals("text/event-stream",
					slow.readHead().singleHeader("Content-Type"));
			Assertions.assertTrue(secondNotificationAttempted.await(5, TimeUnit.SECONDS),
					"The slow handler did not attempt its second notification.");

			McpRequestExecutionSnapshot bounded = awaitRequestSnapshot(runtime,
					snapshot -> snapshot.activeResponseStreams() == 1
							&& snapshot.bufferedStreamFrames() == 1);
			Assertions.assertEquals(1,
					bounded.maximumObservedBufferedFramesPerStream());
			Assertions.assertTrue(bounded.bufferedStreamBytes() > 3_000_000,
					"The large frame did not remain bounded in the outbound lane.");
			Assertions.assertEquals(1L, secondNotificationFinished.getCount(),
					"The producer bypassed the configured one-frame bound.");

			try (McpChunkedHttpClient fast = McpChunkedHttpClient.postMcp(
					port, "\"fast\"", APPLICATION_METHOD)) {
				McpChunkedHttpClient.HttpResponseHead head = fast.readHead();
				Assertions.assertEquals("application/json",
						head.singleHeader("Content-Type"));
				Assertions.assertTrue(fast.readFixedBody(head).contains(
						"\"value\":\"fast\""));
			}

			slow.closeWithReset();
			Assertions.assertTrue(secondNotificationFinished.await(5, TimeUnit.SECONDS),
					"Disconnect did not release the backpressured producer.");
			awaitClean(runtime);
		} finally {
			if (slow != null)
				slow.close();
			runtime.close();
		}
	}

	@Test
	public void terminal_lane_remains_available_while_regular_stream_lane_is_full()
			throws Exception {
		McpHttpServerRuntime runtime = runtime(invocation -> {
			invocation.sendNotification(largeProgress("terminal-lane"));
			return completeResult("terminal-lane-complete");
		}, McpApplicationClock.SYSTEM,
				transportConfiguration(Duration.ofSeconds(15),
						Duration.ofSeconds(60), 1),
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofMillis(10)));

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"terminal-lane\"", APPLICATION_METHOD, 1_024)) {
				Assertions.assertEquals("text/event-stream",
						client.readHead().singleHeader("Content-Type"));
				McpRequestExecutionSnapshot reserved = awaitRequestSnapshot(runtime,
						snapshot -> snapshot.bufferedStreamFrames() == 1
								&& snapshot.terminalStreamBytes() > 0);
				Assertions.assertEquals(1,
						reserved.maximumObservedBufferedFramesPerStream());
				Assertions.assertEquals(1,
						reserved.activeIdentifiedRequestExchanges(),
						"The identified exchange must remain active until the stream drains.");
				Assertions.assertTrue(client.readChunk().length > 3_000_000);
				Assertions.assertEquals(
						"data: {\"jsonrpc\":\"2.0\",\"id\":\"terminal-lane\","
								+ "\"result\":{\"value\":\"terminal-lane-complete\","
								+ "\"resultType\":\"complete\"}}\n\n",
						client.readChunkText());
				Assertions.assertNull(client.readChunk());
			}
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void handler_failure_is_json_before_commit_and_terminal_sse_after_commit()
			throws Exception {
		McpHttpServerRuntime runtime = runtime(invocation -> {
			String requestId = ((McpJsonRpcId.StringId) invocation.request().id()).value();
			if ("postcommit-error".equals(requestId))
				invocation.sendNotification(progress("postcommit-error", 1));
			throw new IllegalStateException("simulated application failure");
		});

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient precommit = McpChunkedHttpClient.postMcp(
					port, "\"precommit-error\"", APPLICATION_METHOD)) {
				McpChunkedHttpClient.HttpResponseHead head = precommit.readHead();
				Assertions.assertEquals(500, head.status());
				Assertions.assertEquals("application/json",
						head.singleHeader("Content-Type"));
				Assertions.assertEquals(
						"{\"jsonrpc\":\"2.0\",\"id\":\"precommit-error\","
								+ "\"error\":{\"code\":-32603,"
								+ "\"message\":\"Internal error\"}}",
						precommit.readFixedBody(head));
			}

			try (McpChunkedHttpClient postcommit = McpChunkedHttpClient.postMcp(
					port, "\"postcommit-error\"", APPLICATION_METHOD)) {
				Assertions.assertEquals("text/event-stream",
						postcommit.readHead().singleHeader("Content-Type"));
				Assertions.assertTrue(postcommit.readChunkText().contains(
						"\"progressToken\":\"postcommit-error\""));
				Assertions.assertEquals(
						"data: {\"jsonrpc\":\"2.0\",\"id\":\"postcommit-error\","
								+ "\"error\":{\"code\":-32603,"
								+ "\"message\":\"Internal error\"}}\n\n",
						postcommit.readChunkText());
				Assertions.assertNull(postcommit.readChunk());
			}
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void shutdown_closes_committed_stream_and_runtime_restarts_cleanly()
			throws Exception {
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		AtomicReference<Optional<StreamTerminationReason>> cancellationReason =
				new AtomicReference<>(Optional.empty());
		McpHttpServerRuntime runtime = runtime(invocation -> {
			String requestId = ((McpJsonRpcId.StringId) invocation.request().id()).value();
			if (!"stream-before-stop".equals(requestId))
				return completeResult("after-restart");

			invocation.sendNotification(progress("stream-before-stop", 1));
			try {
				emergencyRelease.await();
			} catch (InterruptedException exception) {
				cancellationReason.set(invocation.cancellationReason());
				handlerInterrupted.countDown();
				throw exception;
			}
			return completeResult("must-not-be-written");
		});

		McpChunkedHttpClient stream = null;
		try {
			int firstPort = runtime.start().getPort();
			stream = McpChunkedHttpClient.postMcp(
					firstPort, "\"stream-before-stop\"", APPLICATION_METHOD);
			Assertions.assertEquals("text/event-stream",
					stream.readHead().singleHeader("Content-Type"));
			Assertions.assertTrue(stream.readChunkText().contains(
					"\"progressToken\":\"stream-before-stop\""));

			runtime.stop();
			Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(Optional.of(StreamTerminationReason.SERVER_STOPPING),
					cancellationReason.get());
			Assertions.assertTrue(stream.awaitTransportClosure());
			McpRequestExecutionSnapshot stopped = runtime.requestExecutionSnapshot();
			Assertions.assertEquals(0, stopped.retainedRequestControls());
			Assertions.assertEquals(0,
					stopped.activeIdentifiedRequestExchanges());
			Assertions.assertEquals(0, stopped.activeResponseStreams());

			int secondPort = runtime.start().getPort();
			try (McpChunkedHttpClient restarted = McpChunkedHttpClient.postMcp(
					secondPort, "\"after-restart\"", APPLICATION_METHOD)) {
				McpChunkedHttpClient.HttpResponseHead head = restarted.readHead();
				Assertions.assertEquals("application/json",
						head.singleHeader("Content-Type"));
				Assertions.assertTrue(restarted.readFixedBody(head).contains(
						"\"value\":\"after-restart\""));
			}
			awaitClean(runtime);
		} finally {
			emergencyRelease.countDown();
			if (stream != null)
				stream.close();
			runtime.close();
		}
	}

	@Test
	public void due_keep_alive_cannot_override_owned_terminal_response()
			throws Exception {
		ControllableClock clock = new ControllableClock();
		AtomicReference<McpHttpServerRuntime> runtimeReference = new AtomicReference<>();
		AtomicLong hookInvocations = new AtomicLong();
		McpRequestSseStream.setTestHooks(() -> {
			hookInvocations.incrementAndGet();
			clock.advance(Duration.ofSeconds(1));
			runtimeReference.get().runApplicationTimerCycle();
		});
		McpHttpServerRuntime runtime = runtime(invocation -> {
			invocation.sendNotification(largeProgress("keepalive-race"));
			return completeResult("terminal-wins");
		}, clock,
				transportConfiguration(Duration.ofSeconds(1),
						Duration.ofSeconds(10), 1),
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)));
		runtimeReference.set(runtime);

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"keepalive-race\"", APPLICATION_METHOD, 1_024)) {
				Assertions.assertEquals("text/event-stream",
						client.readHead().singleHeader("Content-Type"));
				Assertions.assertTrue(client.readChunk().length > 3_000_000);
				Assertions.assertEquals(
						"data: {\"jsonrpc\":\"2.0\",\"id\":\"keepalive-race\","
								+ "\"result\":{\"value\":\"terminal-wins\","
								+ "\"resultType\":\"complete\"}}\n\n",
						client.readChunkText());
				Assertions.assertNull(client.readChunk());
			}
			Assertions.assertEquals(1L, hookInvocations.get());
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void absolute_deadline_atomically_discards_undrained_terminal_response()
			throws Exception {
		McpRuntimeObservationRecorder observations =
				new McpRuntimeObservationRecorder();
		ControllableClock clock = new ControllableClock();
		AtomicReference<McpHttpServerRuntime> runtimeReference = new AtomicReference<>();
		AtomicLong hookInvocations = new AtomicLong();
		McpRequestSseStream.setTestHooks(() -> {
			hookInvocations.incrementAndGet();
			clock.advance(Duration.ofSeconds(1));
			runtimeReference.get().runApplicationTimerCycle();
		});
		McpHttpServerRuntime runtime = runtime(invocation -> {
			invocation.sendNotification(progress("deadline-race", 1));
			return completeResult("stale-terminal");
		}, clock,
				transportConfiguration(Duration.ofSeconds(1),
						Duration.ofSeconds(10)),
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(1), Duration.ofDays(1)),
				observations);
		runtimeReference.set(runtime);

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"deadline-race\"", APPLICATION_METHOD)) {
				Assertions.assertEquals("text/event-stream",
						client.readHead().singleHeader("Content-Type"));
				Assertions.assertTrue(client.awaitTransportClosure());
			}
			Assertions.assertEquals(1L, hookInvocations.get());
			McpRuntimeObservationRecorder.Observation observation =
					observations.observation("deadline-race");
			McpRuntimeObservationRecorder.Finish finish =
					observation.awaitFinish();
			Assertions.assertEquals(McpRequestOutcome.DEADLINE_EXCEEDED,
					finish.outcome());
			Assertions.assertNull(finish.error());
			Assertions.assertTrue(finish.throwables().isEmpty());
			McpApplicationExecutionSnapshot timedOut =
					awaitApplicationSnapshot(runtime,
							snapshot -> snapshot.deadlineExpirations() == 1
									&& snapshot.retainedExchanges() == 0);
			Assertions.assertEquals(1, timedOut.deadlineExpirations());
			awaitClean(runtime);
			Assertions.assertEquals(1, observations.startCount());
			Assertions.assertEquals(1, observation.finishCount(),
					"The stale complete result must not publish a second finish.");
		} finally {
			runtime.close();
		}
	}

	private static McpJsonRpcMessage.Notification progress(String token, int value) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("progressToken", new McpJsonString(token));
		fields.put("progress", new McpJsonNumber(BigDecimal.valueOf(value)));
		return new McpJsonRpcMessage.Notification("notifications/progress",
				Optional.of(new McpJsonObject(fields)), McpJsonObject.empty());
	}

	private static McpJsonRpcMessage.Notification largeProgress(String token) {
		String part = "x".repeat(900_000);
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("progressToken", new McpJsonString(token));
		fields.put("progress", new McpJsonNumber(BigDecimal.ONE));
		fields.put("message", new McpJsonString(part));
		fields.put("testPadding1", new McpJsonString(part));
		fields.put("testPadding2", new McpJsonString(part));
		fields.put("testPadding3", new McpJsonString(part));
		return new McpJsonRpcMessage.Notification("notifications/progress",
				Optional.of(new McpJsonObject(fields)), McpJsonObject.empty());
	}

	private static McpWireResult completeResult(String value) {
		return McpWireResult.complete(new McpJsonObject(
				Map.of("value", new McpJsonString(value))));
	}

	private static McpHttpServerRuntime runtime(McpApplicationRequestHandler handler) {
		return runtime(handler, McpApplicationClock.SYSTEM,
				McpHttpTransportConfiguration.productionDefaults(0),
				McpApplicationExecutionConfiguration.productionDefaults());
	}

	private static McpHttpServerRuntime runtime(McpApplicationRequestHandler handler,
			McpApplicationClock clock,
			McpHttpTransportConfiguration transportConfiguration,
			McpApplicationExecutionConfiguration executionConfiguration) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"request-scoped-sse-test", "4.0.0-SNAPSHOT"))
				.build();
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromHandlers(
				Map.of(APPLICATION_METHOD, handler));
		return new McpHttpServerRuntime(
				transportConfiguration,
				McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
						request -> McpRequestAdmissionDecision.ACCEPT),
				endpoint, router, executionConfiguration, clock);
	}

	private static McpHttpServerRuntime runtime(McpApplicationRequestHandler handler,
			McpApplicationClock clock,
			McpHttpTransportConfiguration transportConfiguration,
			McpApplicationExecutionConfiguration executionConfiguration,
			McpRuntimeObservationSink observationSink) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"request-scoped-sse-test", "4.0.0-SNAPSHOT"))
				.build();
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromHandlers(
				Map.of(APPLICATION_METHOD, handler));
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				request -> McpRequestAdmissionDecision.ACCEPT);
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(
				policy, endpoint, router, observationSink);
		return new McpHttpServerRuntime(
				transportConfiguration, List.of(binding),
				McpJsonLimits.productionDefaults(), executionConfiguration, clock,
				McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {});
	}

	private static McpHttpServerRuntime runtime(McpApplicationRequestHandler handler,
			McpApplicationClock clock,
			McpHttpTransportConfiguration transportConfiguration,
			McpApplicationExecutionConfiguration executionConfiguration,
			McpRuntimeObservationSink observationSink,
			McpApplicationExecutionObserver executionObserver) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"request-scoped-sse-test", "4.0.0-SNAPSHOT"))
				.build();
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromHandlers(
				Map.of(APPLICATION_METHOD, handler));
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				request -> McpRequestAdmissionDecision.ACCEPT);
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(
				policy, endpoint, router, observationSink);
		return new McpHttpServerRuntime(transportConfiguration, List.of(binding),
				McpJsonLimits.productionDefaults(), executionConfiguration, clock,
				McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {}, Optional.empty(),
				McpFrameworkRequestStateRuntime.disabledInstance(),
				McpSubscriptionRuntimeConfiguration.productionDefaults(),
				executionObserver);
	}

	private static McpHttpTransportConfiguration transportConfiguration(
			Duration keepAliveInterval, Duration responseWriteIdleTimeout) {
		return transportConfiguration(keepAliveInterval,
				responseWriteIdleTimeout,
				McpHttpTransportConfiguration.productionDefaults(0)
						.streamQueueCapacity());
	}

	private static McpHttpTransportConfiguration transportConfiguration(
			Duration keepAliveInterval, Duration responseWriteIdleTimeout,
			int streamQueueCapacity) {
		McpHttpTransportConfiguration defaults =
				McpHttpTransportConfiguration.productionDefaults(0);
		return new McpHttpTransportConfiguration(
				defaults.host(), defaults.port(), defaults.selectorResolution(),
				defaults.requestHeaderTimeout(), defaults.requestBodyTimeout(),
				responseWriteIdleTimeout, keepAliveInterval, defaults.shutdownTimeout(),
				defaults.readBufferSize(), defaults.acceptBacklog(),
				defaults.maximumAggregateRequestBytes(),
				defaults.maximumRequestBodyBytes(), defaults.maximumHeaderCount(),
				defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
				defaults.maximumConnections(), defaults.connectionWriterConcurrency(),
				defaults.requestProcessorConcurrency(),
				defaults.requestProcessorQueueCapacity(),
				streamQueueCapacity);
	}

	private static void awaitClean(McpHttpServerRuntime runtime) throws Exception {
		awaitRequestSnapshot(runtime, snapshot -> snapshot.retainedRequestControls() == 0
				&& snapshot.activeIdentifiedRequestExchanges() == 0);
		awaitApplicationSnapshot(runtime, snapshot -> snapshot.activeHandlerSlots() == 0
				&& snapshot.queuedRequests() == 0
				&& snapshot.activeIdentifiedRequestExchanges() == 0
				&& snapshot.retainedExchanges() == 0
				&& snapshot.retainedTransportLeases() == 0);
	}

	private static McpRequestExecutionSnapshot awaitRequestSnapshot(
			McpHttpServerRuntime runtime,
			Predicate<McpRequestExecutionSnapshot> condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpRequestExecutionSnapshot latest;
		do {
			latest = runtime.requestExecutionSnapshot();
			if (condition.test(latest))
				return latest;
			Thread.sleep(5);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for request cleanup: " + latest);
	}

	private static McpApplicationExecutionSnapshot awaitApplicationSnapshot(
			McpHttpServerRuntime runtime,
			Predicate<McpApplicationExecutionSnapshot> condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpApplicationExecutionSnapshot latest;
		do {
			latest = runtime.applicationExecutionSnapshot().orElseThrow();
			if (condition.test(latest))
				return latest;
			Thread.sleep(5);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for application cleanup: " + latest);
	}

	private static final class WriteTimeoutObservation
			implements McpRuntimeObservationSink, McpApplicationExecutionObserver {
		private final Object lock = new Object();
		private final List<Object> order = new ArrayList<>();
		private final CountDownLatch finished = new CountDownLatch(1);

		@Override
		public McpRuntimeRequestObservation didStartRequest(
				McpRuntimeRequestInput input) {
			return new McpRuntimeRequestObservation() {
				@Override
				public Optional<McpRequestContext> publicContext() {
					return Optional.empty();
				}

				@Override
				public void didOpenRequestStream() {
					add("stream-opened");
				}

				@Override
				public void didCloseRequestStream(
						StreamTerminationReason reason, Duration duration) {
					add("stream-closed:" + reason);
				}

				@Override
				public void didFinish(McpRequestOutcome outcome,
						McpJsonRpcError error, Duration duration,
						List<Throwable> throwables) {
					add("request-finished");
					finished.countDown();
				}
			};
		}

		@Override
		public PendingMetricRecord recordTransportFailure(
				MetricsCollector.TransportFailureReason reason) {
			PendingTransportFailure pending = new PendingTransportFailure(reason);
			add(pending);
			return pending;
		}

		@Override
		public void discardPendingMetric(PendingMetricRecord pendingMetricRecord) {
			if (!(pendingMetricRecord instanceof PendingTransportFailure))
				return;
			synchronized (this.lock) {
				if (!this.order.remove(pendingMetricRecord))
					throw new AssertionError(
							"The provisional transport failure was not pending.");
				this.lock.notifyAll();
			}
		}

		private void add(Object value) {
			synchronized (this.lock) {
				this.order.add(value);
				this.lock.notifyAll();
			}
		}

		private void awaitFinished() throws InterruptedException {
			Assertions.assertTrue(this.finished.await(5, TimeUnit.SECONDS),
					"The write-timeout request did not finish.");
		}

		private List<MetricsCollector.TransportFailureReason>
				transportFailureReasons() {
			synchronized (this.lock) {
				return this.order.stream()
						.filter(PendingTransportFailure.class::isInstance)
						.map(PendingTransportFailure.class::cast)
						.map(PendingTransportFailure::reason)
						.toList();
			}
		}

		private void awaitTransportFailureReasons(
				List<MetricsCollector.TransportFailureReason> expected)
				throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			synchronized (this.lock) {
				while (!transportFailureReasons().equals(expected)) {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0L)
						throw new AssertionError("Timed out awaiting transport failures "
								+ expected + "; found "
								+ transportFailureReasons());
					TimeUnit.NANOSECONDS.timedWait(this.lock, remaining);
				}
			}
		}

		private List<String> order() {
			synchronized (this.lock) {
				return this.order.stream().map(value ->
						value instanceof PendingTransportFailure pending
								? "transport-failure:" + pending.reason()
								: (String) value).toList();
			}
		}

		@Override
		public void beginDeferral() {
		}

		@Override
		public void recordHandlerExecutionStarted() {
		}

		@Override
		public void recordHandlerExecutionFinished() {
		}

		@Override
		public void recordHandlerQueued() {
		}

		@Override
		public void recordHandlerDequeued() {
		}

		@Override
		public void recordHandlerCapacityRejected() {
		}

		@Override
		public void drain() {
		}

		@Override
		public void endDeferral() {
		}
	}

	private static final class PendingTransportFailure
			implements McpApplicationExecutionObserver.PendingMetricRecord {
		private final MetricsCollector.TransportFailureReason reason;

		private PendingTransportFailure(
				MetricsCollector.TransportFailureReason reason) {
			this.reason = reason;
		}

		private MetricsCollector.TransportFailureReason reason() {
			return this.reason;
		}
	}

	private static final class ControllableClock implements McpApplicationClock {
		private final AtomicLong nanoseconds = new AtomicLong();

		@Override
		public long nanoTime() {
			return nanoseconds.get();
		}

		private void advance(Duration duration) {
			nanoseconds.addAndGet(duration.toNanos());
		}
	}
}
