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
import com.soklet.McpStreamTerminationReason;
import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@NotThreadSafe
@Timeout(20)
class McpHttpServerInlineExecutorLockTests {
	private static final String APPLICATION_METHOD = "test/execute";

	@Test
	void inline_executor_never_runs_application_callbacks_under_request_control_lock()
			throws Exception {
		InlineExecutorService executor = new InlineExecutorService();
		TerminalObservation observation = new TerminalObservation();
		CrossThreadNotificationProbe interceptorProbe =
				new CrossThreadNotificationProbe("interceptor");
		CrossThreadNotificationProbe handlerProbe =
				new CrossThreadNotificationProbe("handler");
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpRequestAdmissionDecision.ACCEPT)
				.withRequestInterceptor((invocation, continuation) -> {
					interceptorProbe.run(invocation);
					return continuation.invoke();
				});
		McpApplicationRequestRouter router =
				McpApplicationRequestRouter.fromHandlers(Map.of(
						APPLICATION_METHOD, invocation -> {
							handlerProbe.run(invocation);
							return McpWireResult.complete(new McpJsonObject(Map.of(
									"value", new McpJsonString("complete"))));
						}));
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"inline-executor-lock-test", "4.0.0-SNAPSHOT"))
				.build();
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				List.of(new McpHttpEndpointBinding(policy, endpoint, router,
						observation)), McpJsonLimits.productionDefaults(),
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(10), Duration.ofMillis(10)),
				McpApplicationClock.SYSTEM, ignored -> executor,
				ignored -> {}, ignored -> {});

		try {
			int port = runtime.start().getPort();
			boolean terminalResultObserved = false;
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"inline-executor\"", APPLICATION_METHOD)) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				Assertions.assertEquals(200, head.status(), head.raw());
				if (head.hasHeader("Transfer-Encoding")) {
					byte[] chunk;
					while ((chunk = client.readChunk()) != null)
						terminalResultObserved |= new String(chunk,
								StandardCharsets.UTF_8)
								.contains("\"value\":\"complete\"");
				} else {
					terminalResultObserved = client.readFixedBody(head)
							.contains("\"value\":\"complete\"");
				}
			}

			Assertions.assertTrue(terminalResultObserved,
					"The inline handler did not write its terminal result.");
			observation.awaitFinished();
			awaitCondition(() -> {
				McpRequestExecutionSnapshot requests =
						runtime.requestExecutionSnapshot();
				return runtime.diagnosticsSnapshot().activeRequestStreams() == 0
						&& requests.retainedRequestControls() == 0
						&& requests.activeIdentifiedRequestExchanges() == 0;
			}, "The inline terminal response stranded its request stream.");
			observation.assertCompleteStreamLifecycle();
			interceptorProbe.assertCompletedWithoutRequestControlLock();
			handlerProbe.assertCompletedWithoutRequestControlLock();
		} finally {
			runtime.close();
		}
	}

	private static void awaitCondition(@NonNull BooleanSupplier condition,
			@NonNull String failure) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L)
			Thread.sleep(5L);
		Assertions.assertTrue(condition.getAsBoolean(), failure);
	}

	private static McpJsonRpcMessage.Notification progress(String token) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("progressToken", new McpJsonString(token));
		fields.put("progress", new McpJsonNumber(BigDecimal.ONE));
		return new McpJsonRpcMessage.Notification("notifications/progress",
				Optional.of(new McpJsonObject(fields)), McpJsonObject.empty());
	}

	private static final class CrossThreadNotificationProbe {
		private final String callbackName;
		private final CountDownLatch completed;
		private final AtomicBoolean completedBeforeCallbackReturned;
		private final AtomicBoolean notificationAccepted;
		private final AtomicReference<Throwable> failure;

		private CrossThreadNotificationProbe(String callbackName) {
			this.callbackName = callbackName;
			this.completed = new CountDownLatch(1);
			this.completedBeforeCallbackReturned = new AtomicBoolean();
			this.notificationAccepted = new AtomicBoolean();
			this.failure = new AtomicReference<>();
		}

		private void run(McpApplicationInvocation invocation)
				throws InterruptedException {
			Thread helper = new Thread(() -> {
				try {
					notificationAccepted.set(invocation.sendNotification(
							progress(callbackName)));
				} catch (Throwable throwable) {
					failure.set(throwable);
				} finally {
					completed.countDown();
				}
			}, "mcp-" + callbackName + "-request-control-lock-probe");
			helper.setDaemon(true);
			helper.start();
			completedBeforeCallbackReturned.set(
					completed.await(1, TimeUnit.SECONDS));
		}

		private void assertCompletedWithoutRequestControlLock()
				throws InterruptedException {
			Assertions.assertTrue(completed.await(5, TimeUnit.SECONDS),
					"The " + callbackName + " probe never completed.");
			Assertions.assertNull(failure.get(),
					"The " + callbackName + " probe failed unexpectedly.");
			Assertions.assertTrue(completedBeforeCallbackReturned.get(),
					"The " + callbackName + " ran while RequestControl.lock prevented "
							+ "a cross-thread notification from completing.");
			Assertions.assertTrue(notificationAccepted.get(),
					"The " + callbackName + " cross-thread notification was rejected.");
		}
	}

	private static final class TerminalObservation
			implements McpRuntimeObservationSink, McpRuntimeRequestObservation {
		private final AtomicInteger starts = new AtomicInteger();
		private final AtomicInteger streamOpens = new AtomicInteger();
		private final AtomicInteger streamCloses = new AtomicInteger();
		private final AtomicInteger finishes = new AtomicInteger();
		private final AtomicReference<StreamTerminationReason> closeReason =
				new AtomicReference<>();
		private final AtomicReference<McpRequestOutcome> outcome =
				new AtomicReference<>();
		private final AtomicReference<McpJsonRpcError> error =
				new AtomicReference<>();
		private final AtomicReference<List<Throwable>> throwables =
				new AtomicReference<>(List.of());
		private final CountDownLatch finished = new CountDownLatch(1);

		@Override
		@NonNull
		public McpRuntimeRequestObservation didStartRequest(
				@NonNull McpRuntimeRequestInput input) {
			starts.incrementAndGet();
			return this;
		}

		@Override
		@NonNull
		public Optional<@NonNull McpRequestContext> publicContext() {
			return Optional.empty();
		}

		@Override
		public void didOpenRequestStream() {
			streamOpens.incrementAndGet();
		}

		@Override
		public void didCloseRequestStream(
				@NonNull StreamTerminationReason reason,
				@Nullable McpStreamTerminationReason exactReason,
				@NonNull Duration duration) {
			closeReason.compareAndSet(null, reason);
			streamCloses.incrementAndGet();
		}

		@Override
		public void didFinish(@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error, @NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			this.outcome.compareAndSet(null, outcome);
			this.error.compareAndSet(null, error);
			this.throwables.set(List.copyOf(throwables));
			this.finishes.incrementAndGet();
			this.finished.countDown();
		}

		private void awaitFinished() throws InterruptedException {
			Assertions.assertTrue(this.finished.await(5, TimeUnit.SECONDS),
					"The inline terminal request observation was stranded.");
		}

		private void assertCompleteStreamLifecycle() {
			Assertions.assertEquals(1, this.starts.get());
			Assertions.assertEquals(1, this.streamOpens.get());
			Assertions.assertEquals(1, this.streamCloses.get());
			Assertions.assertEquals(StreamTerminationReason.COMPLETED,
					this.closeReason.get());
			Assertions.assertEquals(1, this.finishes.get());
			Assertions.assertEquals(McpRequestOutcome.COMPLETE,
					this.outcome.get());
			Assertions.assertNull(this.error.get());
			Assertions.assertEquals(List.of(), this.throwables.get());
		}
	}

	private static final class InlineExecutorService extends AbstractExecutorService {
		private final AtomicBoolean shutdown;

		private InlineExecutorService() {
			this.shutdown = new AtomicBoolean();
		}

		@Override
		public void shutdown() {
			shutdown.set(true);
		}

		@Override
		public List<Runnable> shutdownNow() {
			shutdown.set(true);
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return shutdown.get();
		}

		@Override
		public boolean isTerminated() {
			return shutdown.get();
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return shutdown.get();
		}

		@Override
		public void execute(Runnable command) {
			if (shutdown.get())
				throw new RejectedExecutionException("Executor is shut down.");
			command.run();
		}
	}
}
