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

import com.soklet.CancelationToken;
import com.soklet.CorsAuthorizer;
import com.soklet.McpLocalizationContext;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static java.util.Objects.requireNonNull;

@NotThreadSafe
@Timeout(30)
public class McpCatalogPolicyDeadlineRuntimeTests {
	private static final String TOOL_NAME = "policy-deadline-tool";
	private static final String SECOND_TOOL_NAME = "policy-stop-second-tool";
	private static final String JSON_MEDIA_TYPE = "application/json";

	@Test
	public void activePolicyDeadlineRetainsCorrelatedResponseAndCancelsPublicToken()
			throws Exception {
		Duration requestDeadline = Duration.ofSeconds(30);
		AtomicLong now = new AtomicLong();
		ManualExecutorService executor = new ManualExecutorService();
		AtomicReference<CancelationToken> token = new AtomicReference<>();
		AtomicInteger cancelationCallbacks = new AtomicInteger();
		McpServerRuntimeBridge.CatalogAccessAdapter adapter = input -> {
			CancelationToken observed = input.cancelationToken();
			token.set(observed);
			observed.onCancel(cancelationCallbacks::incrementAndGet);
			return new McpServerRuntimeBridge.CatalogAccessSession() {
				@Override
				public boolean isToolAccessible(@NonNull String toolName) {
					Assertions.assertEquals(TOOL_NAME, toolName);
					Assertions.assertFalse(observed.isCanceled());
					Assertions.assertTrue(observed.getCancelationReason().isEmpty());
					Assertions.assertTrue(observed.getCancelationCause().isEmpty());
					now.set(requestDeadline.toNanos());
					return true;
				}

				@Override
				public boolean isPromptAccessible(@NonNull String promptName) {
					throw new AssertionError("Prompt policy must not run.");
				}

				@Override
				@NonNull
				public Optional<@NonNull McpLocalizationContext>
						localizationContext() {
					return Optional.empty();
				}
			};
		};
		McpHttpServerRuntime runtime = runtime(adapter,
				requestDeadline, now::get, executor);

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"active-policy\"", "tools/list")) {
				executor.awaitCommand().run();
				assertPolicyDeadlineResponse(client, 504, "active-policy");
			}

			CancelationToken observed = token.get();
			Assertions.assertNotNull(observed,
					"The public catalog-policy token was not exposed.");
			Assertions.assertTrue(observed.isCanceled());
			Assertions.assertEquals(
					Optional.of(StreamTerminationReason.RESPONSE_TIMEOUT),
					observed.getCancelationReason());
			Assertions.assertTrue(observed.getCancelationCause().isEmpty(),
					"Internal policy deadline causes must remain hidden.");
			Assertions.assertEquals(1, cancelationCallbacks.get());
			McpApplicationExecutionSnapshot snapshot = runtime
					.applicationExecutionSnapshot().orElseThrow();
			Assertions.assertEquals(1, snapshot.deadlineExpirations());
			Assertions.assertEquals(1, snapshot.protocolDeadlineExpirations());
		} finally {
			runPendingCommand(executor);
			runtime.close();
		}
	}

	@Test
	public void stillQueuedPolicyDeadlineRetainsCorrelatedServiceUnavailableResponse()
			throws Exception {
		Duration requestDeadline = Duration.ofSeconds(30);
		SteppingPolicyWaitClock clock = new SteppingPolicyWaitClock(
				requestDeadline.toNanos());
		ManualExecutorService executor = new ManualExecutorService();
		McpServerRuntimeBridge.CatalogAccessAdapter adapter = ignored ->
				allowAllSession();
		McpHttpServerRuntime runtime = runtime(adapter,
				requestDeadline, clock, executor);

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient active = McpChunkedHttpClient.postMcp(
					port, "\"active-holder\"", "tools/list")) {
				assertPolicyDeadlineResponse(active, 504, "active-holder");
			}
			Assertions.assertNotNull(executor.command(),
					"The timed-out active ticket must retain its slot until exit.");
			Assertions.assertEquals(1, runtime.applicationExecutionSnapshot()
					.orElseThrow().activeHandlerSlots());
			Assertions.assertEquals(1, runtime.applicationExecutionSnapshot()
					.orElseThrow().protocolDeadlineExpirations());

			try (McpChunkedHttpClient queued = McpChunkedHttpClient.postMcp(
					port, "\"queued-policy\"", "tools/list")) {
				assertPolicyDeadlineResponse(queued, 503, "queued-policy");
			}

			McpApplicationExecutionSnapshot queuedSnapshot =
					runtime.applicationExecutionSnapshot().orElseThrow();
			Assertions.assertEquals(1,
					queuedSnapshot.maximumObservedQueuedRequests(),
					"The 503 must come from a ticket that was admitted to the queue.");
			Assertions.assertEquals(0, queuedSnapshot.queuedRequests(),
					"The expired queued ticket must remove itself without dispatch.");
			Assertions.assertEquals(2, queuedSnapshot.deadlineExpirations());
			Assertions.assertEquals(2,
					queuedSnapshot.protocolDeadlineExpirations());
		} finally {
			runPendingCommand(executor);
			runtime.close();
		}
	}

	@Test
	public void applicationStopCancelsActivePolicyBeforeAnotherEvaluatorCanEnter()
			throws Exception {
		Duration requestDeadline = Duration.ofSeconds(30);
		ManualExecutorService executor = new ManualExecutorService();
		AtomicReference<CancelationToken> token = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> cancelationReason =
				new AtomicReference<>();
		AtomicReference<Optional<Throwable>> cancelationCause =
				new AtomicReference<>();
		AtomicInteger cancelationCallbacks = new AtomicInteger();
		AtomicInteger secondInvocations = new AtomicInteger();
		AtomicBoolean firstInterruptWasCleared = new AtomicBoolean();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch firstInterrupted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		McpServerRuntimeBridge.CatalogAccessAdapter adapter = input -> {
			CancelationToken observed = input.cancelationToken();
			token.set(observed);
			observed.onCancel(() -> {
				cancelationCallbacks.incrementAndGet();
				cancelationReason.set(observed.getCancelationReason().orElse(null));
				cancelationCause.set(observed.getCancelationCause());
			});
			return new McpServerRuntimeBridge.CatalogAccessSession() {
				@Override
				public boolean isToolAccessible(@NonNull String toolName)
						throws Exception {
					if (TOOL_NAME.equals(toolName)) {
						firstEntered.countDown();
						try {
							new CountDownLatch(1).await();
						} catch (InterruptedException ignored) {
							firstInterruptWasCleared.set(
									!Thread.currentThread().isInterrupted());
							firstInterrupted.countDown();
						}
						releaseFirst.await();
						return true;
					}
					if (Thread.currentThread().isInterrupted()
							|| observed.isCanceled()
							|| input.pastDeadline().getAsBoolean())
						throw new InterruptedException(
								"Catalog policy evaluation was canceled.");
					Assertions.assertEquals(SECOND_TOOL_NAME, toolName);
					secondInvocations.incrementAndGet();
					return true;
				}

				@Override
				public boolean isPromptAccessible(@NonNull String promptName) {
					throw new AssertionError("Prompt policy must not run.");
				}

				@Override
				@NonNull
				public Optional<@NonNull McpLocalizationContext>
						localizationContext() {
					return Optional.empty();
				}
			};
		};
		McpHttpServerRuntime runtime = runtime(adapter, requestDeadline,
				McpApplicationClock.SYSTEM, executor,
				List.of(TOOL_NAME, SECOND_TOOL_NAME));
		Thread policyThread = null;

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient ignored = McpChunkedHttpClient.postMcp(
					port, "\"policy-stop\"", "tools/list")) {
				policyThread = new Thread(executor.awaitCommand(),
						"mcp-catalog-policy-stop-test");
				policyThread.start();
				Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS),
						"The first catalog evaluator did not enter.");

				application(runtime).stop(StreamTerminationReason.SERVER_STOPPING);
				Assertions.assertTrue(firstInterrupted.await(5, TimeUnit.SECONDS),
						"The active catalog evaluator was not interrupted.");
				Assertions.assertTrue(firstInterruptWasCleared.get(),
						"InterruptedException must clear the evaluator thread's flag.");
				awaitCondition(() -> runtime.requestExecutionSnapshot()
						.retainedRequestControls() == 0,
						"The stopped protocol request did not finish cleanup.");

				CancelationToken observed = token.get();
				Assertions.assertNotNull(observed);
				Assertions.assertTrue(observed.isCanceled(),
						"Application stop must cancel the request-scoped policy token.");
				Assertions.assertEquals(StreamTerminationReason.SERVER_STOPPING,
						cancelationReason.get());
				Assertions.assertEquals(Optional.empty(), cancelationCause.get());
				Assertions.assertTrue(observed.getCancelationCause().isEmpty());
				Assertions.assertEquals(1, cancelationCallbacks.get());

				releaseFirst.countDown();
				policyThread.join(TimeUnit.SECONDS.toMillis(5));
				Assertions.assertFalse(policyThread.isAlive());
				Assertions.assertEquals(0, secondInvocations.get(),
						"A later evaluator entered after application stop won.");
			}
		} finally {
			releaseFirst.countDown();
			if (policyThread != null)
				policyThread.join(TimeUnit.SECONDS.toMillis(5));
			runPendingCommand(executor);
			runtime.close();
		}
	}

	private static McpServerRuntimeBridge.@NonNull CatalogAccessSession
			allowAllSession() {
		return new McpServerRuntimeBridge.CatalogAccessSession() {
			@Override
			public boolean isToolAccessible(@NonNull String toolName) {
				return true;
			}

			@Override
			public boolean isPromptAccessible(@NonNull String promptName) {
				return true;
			}

			@Override
			@NonNull
			public Optional<@NonNull McpLocalizationContext> localizationContext() {
				return Optional.empty();
			}
		};
	}

	private static void assertPolicyDeadlineResponse(
			@NonNull McpChunkedHttpClient client, int expectedStatus,
			@NonNull String expectedId) throws Exception {
		McpChunkedHttpClient.HttpResponseHead head = client.readHead();
		String body = client.readFixedBody(head);
		Assertions.assertEquals(expectedStatus, head.status(), body);
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store",
				head.singleHeader("Cache-Control"));
		Assertions.assertFalse(head.hasHeader("Retry-After"));
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
				+ expectedId + "\",\"error\":{\"code\":-32603,"
				+ "\"message\":\"Internal error\"}}", body);
	}

	@NonNull
	private static McpHttpServerRuntime runtime(
			McpServerRuntimeBridge.@NonNull CatalogAccessAdapter adapter,
			@NonNull Duration requestDeadline,
			@NonNull McpApplicationClock clock,
			@NonNull ManualExecutorService executor) {
		return runtime(adapter, requestDeadline, clock, executor,
				List.of(TOOL_NAME));
	}

	@NonNull
	private static McpHttpServerRuntime runtime(
			McpServerRuntimeBridge.@NonNull CatalogAccessAdapter adapter,
			@NonNull Duration requestDeadline,
			@NonNull McpApplicationClock clock,
			@NonNull ManualExecutorService executor,
			@NonNull List<@NonNull String> toolNames) {
		McpNormalizedEndpoint.Builder endpointBuilder = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"catalog-policy-deadline-test", "4.0.0"));
		for (String toolName : toolNames)
			endpointBuilder.tool(McpNormalizedOperation.named(toolName));
		McpNormalizedEndpoint endpoint = endpointBuilder
				.catalogAccessAdapter(adapter).build();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous());
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(
				policy, endpoint, McpApplicationRequestRouter.empty(),
				observationWithPublicContext());
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				List.of(binding), McpJsonLimits.productionDefaults(),
				new McpApplicationExecutionConfiguration(1, 1, requestDeadline,
						Duration.ofDays(1)), clock, ignored -> executor,
				ignored -> {}, ignored -> {});
	}

	@NonNull
	private static McpApplicationExecution application(
			@NonNull McpHttpServerRuntime runtime) throws Exception {
		Field field = McpHttpServerRuntime.class.getDeclaredField(
				"applicationExecution");
		field.setAccessible(true);
		return (McpApplicationExecution) field.get(requireNonNull(runtime));
	}

	private static void awaitCondition(@NonNull BooleanSupplier condition,
			@NonNull String failureMessage) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		do {
			if (requireNonNull(condition).getAsBoolean())
				return;
			Thread.sleep(5L);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError(requireNonNull(failureMessage));
	}

	@NonNull
	private static McpRuntimeObservationSink observationWithPublicContext() {
		McpRequestContext context = (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class},
				(proxy, method, arguments) -> {
					if (method.getReturnType() == Optional.class)
						return Optional.empty();
					if (method.getReturnType() == Map.class)
						return Map.of();
					if (method.getReturnType() == String.class)
						return "catalog-policy-deadline-test";
					if (method.getReturnType() == boolean.class)
						return false;
					return null;
				});
		return ignored -> new McpRuntimeRequestObservation() {
			@Override
			@NonNull
			public Optional<@NonNull McpRequestContext> publicContext() {
				return Optional.of(context);
			}

			@Override
			public void didFinish(@NonNull McpRequestOutcome outcome,
					McpJsonRpcError error, @NonNull Duration duration,
					@NonNull List<@NonNull Throwable> throwables) {
			}
		};
	}

	private static void runPendingCommand(
			@NonNull ManualExecutorService executor) {
		Runnable pending = executor.takeCommand();
		if (pending != null)
			pending.run();
	}

	private static final class SteppingPolicyWaitClock
			implements McpApplicationClock {
		private final long requestDeadlineNanos;
		private final AtomicLong now;
		private final ThreadLocal<Integer> policyReads;

		private SteppingPolicyWaitClock(long requestDeadlineNanos) {
			this.requestDeadlineNanos = requestDeadlineNanos;
			this.now = new AtomicLong();
			this.policyReads = ThreadLocal.withInitial(() -> 0);
		}

		@Override
		public long nanoTime() {
			if (!insideBoundedPolicyWait())
				return now.get();
			int read = policyReads.get() + 1;
			policyReads.set(read);
			if ((read & 1) == 1)
				return now.get();
			long deadline = now.addAndGet(requestDeadlineNanos);
			return deadline - 1L;
		}

		private boolean insideBoundedPolicyWait() {
			for (StackTraceElement frame : Thread.currentThread().getStackTrace())
				if (McpApplicationExecution.class.getName().equals(
						frame.getClassName())
						&& "invokeBoundedPolicy".equals(frame.getMethodName()))
					return true;
			return false;
		}
	}

	private static final class ManualExecutorService
			extends AbstractExecutorService {
		private final CountDownLatch commandSubmitted;
		private boolean shutdown;
		private Runnable command;

		private ManualExecutorService() {
			this.commandSubmitted = new CountDownLatch(1);
		}

		@Override
		public synchronized void shutdown() {
			shutdown = true;
		}

		@Override
		public synchronized List<Runnable> shutdownNow() {
			shutdown = true;
			return List.of();
		}

		@Override
		public synchronized boolean isShutdown() {
			return shutdown;
		}

		@Override
		public synchronized boolean isTerminated() {
			return shutdown && command == null;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return isTerminated();
		}

		@Override
		public synchronized void execute(@NonNull Runnable command) {
			if (shutdown)
				throw new IllegalStateException("Executor is shut down.");
			if (this.command != null)
				throw new IllegalStateException("A command is already pending.");
			this.command = command;
			commandSubmitted.countDown();
		}

		@NonNull
		private Runnable awaitCommand() throws InterruptedException {
			Assertions.assertTrue(commandSubmitted.await(5, TimeUnit.SECONDS),
					"The catalog policy command was not submitted.");
			Runnable submitted = takeCommand();
			Assertions.assertNotNull(submitted);
			return submitted;
		}

		private synchronized Runnable command() {
			return command;
		}

		private synchronized Runnable takeCommand() {
			Runnable value = command;
			command = null;
			return value;
		}
	}
}
