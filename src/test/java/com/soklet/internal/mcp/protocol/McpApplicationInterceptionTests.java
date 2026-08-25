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

import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.MicrohttpRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@NotThreadSafe
@Timeout(20)
public class McpApplicationInterceptionTests {
	@Test
	public void interceptor_runs_inside_the_acquired_slot_and_can_transform_results()
			throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		List<String> stages = new ArrayList<>();
		McpEffectiveAdmissionIdentity identity = admissionIdentity("transform");
		McpApplicationExecution execution = execution(executor,
				(invocation, continuation) -> {
					Assertions.assertSame(identity, invocation.admissionIdentity());
					stages.add("interceptor-before");
					McpWireResult downstream = continuation.invoke();
					Assertions.assertEquals("handler", resultValue(downstream));
					stages.add("interceptor-after");
					return result("transformed");
				});
		AtomicReference<McpApplicationResponse> response = new AtomicReference<>();
		AtomicInteger cleanups = new AtomicInteger();

		try {
			execution.start();
			execution.dispatch(transportRequest(1), request("transform"),
					Mcp20260728ProtocolProfile.INSTANCE, identity,
					invocation -> {
						stages.add("handler");
						return result("handler");
					}, deadline(), value -> {
						response.set(value);
						return true;
					}, cleanups::incrementAndGet);

			Assertions.assertEquals(1, execution.snapshot().activeHandlerSlots());
			Assertions.assertTrue(stages.isEmpty(),
					"The interceptor must not run before a handler slot executes.");
			runCommand(executor.takeCommand(), "mcp-interceptor-transform-test");

			Assertions.assertEquals(List.of(
					"interceptor-before", "handler", "interceptor-after"), stages);
			Assertions.assertEquals(200, response.get().status());
			Assertions.assertEquals("transformed", responseResultValue(response.get()));
			Assertions.assertEquals(1, cleanups.get());
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void interceptor_can_short_circuit_without_invoking_the_handler()
			throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpApplicationExecution execution = execution(executor,
				(invocation, continuation) -> result("short-circuit"));
		AtomicReference<McpApplicationResponse> response = new AtomicReference<>();

		try {
			execution.start();
			execution.dispatch(transportRequest(2), request("short-circuit"),
					Mcp20260728ProtocolProfile.INSTANCE,
					admissionIdentity("short-circuit"), invocation -> {
						handlerInvocations.incrementAndGet();
						return result("handler");
					}, deadline(), value -> {
						response.set(value);
						return true;
					}, () -> {});
			runCommand(executor.takeCommand(), "mcp-interceptor-short-circuit-test");

			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals("short-circuit",
					responseResultValue(response.get()));
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void interceptor_continuation_is_one_shot_and_never_reenters_the_handler()
			throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpApplicationExecution execution = execution(executor,
				(invocation, continuation) -> {
					McpWireResult first = continuation.invoke();
					IllegalStateException failure = Assertions.assertThrows(
							IllegalStateException.class, continuation::invoke);
					Assertions.assertEquals(
							"An MCP interceptor continuation may be invoked only once.",
							failure.getMessage());
					return first;
				});
		AtomicReference<McpApplicationResponse> response = new AtomicReference<>();

		try {
			execution.start();
			execution.dispatch(transportRequest(3), request("one-shot"),
					Mcp20260728ProtocolProfile.INSTANCE,
					admissionIdentity("one-shot"), invocation -> {
						handlerInvocations.incrementAndGet();
						return result("once");
					}, deadline(), value -> {
						response.set(value);
						return true;
					}, () -> {});
			runCommand(executor.takeCommand(), "mcp-interceptor-one-shot-test");

			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals("once", responseResultValue(response.get()));
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void interceptor_continuation_cannot_escape_its_thread_or_call_lifetime()
			throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicReference<McpApplicationHandlerInvocation> retainedContinuation =
				new AtomicReference<>();
		AtomicReference<Throwable> crossThreadFailure = new AtomicReference<>();
		McpApplicationExecution execution = execution(executor,
				(invocation, continuation) -> {
					retainedContinuation.set(continuation);
					Thread crossThread = new Thread(() -> {
						try {
							continuation.invoke();
						} catch (Throwable throwable) {
							crossThreadFailure.set(throwable);
						}
					}, "mcp-interceptor-cross-thread-continuation-test");
					crossThread.start();
					crossThread.join(TimeUnit.SECONDS.toMillis(5));
					Assertions.assertFalse(crossThread.isAlive());
					return result("bounded");
				});
		AtomicReference<McpApplicationResponse> response = new AtomicReference<>();

		try {
			execution.start();
			execution.dispatch(transportRequest(31), request("bounded-continuation"),
					Mcp20260728ProtocolProfile.INSTANCE,
					admissionIdentity("bounded-continuation"), invocation -> {
						handlerInvocations.incrementAndGet();
						return result("escaped");
					}, deadline(), value -> {
						response.set(value);
						return true;
					}, () -> {});
			runCommand(executor.takeCommand(),
					"mcp-interceptor-bounded-continuation-test");

			IllegalStateException threadFailure = Assertions.assertInstanceOf(
					IllegalStateException.class, crossThreadFailure.get());
			Assertions.assertEquals(
					"An MCP interceptor continuation must be invoked on the interceptor thread.",
					threadFailure.getMessage());
			IllegalStateException lateFailure = Assertions.assertThrows(
					IllegalStateException.class,
					() -> retainedContinuation.get().invoke());
			Assertions.assertEquals(
					"An MCP interceptor continuation cannot be invoked after interception returns.",
					lateFailure.getMessage());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals("bounded", responseResultValue(response.get()));
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void null_and_throwing_interceptors_fail_closed_without_handler_entry()
			throws Exception {
		assertInterceptorFailure((invocation, continuation) -> null);
		assertInterceptorFailure((invocation, continuation) -> {
			throw new IllegalStateException("secret failure text");
		});
	}

	@Test
	public void queue_rejection_happens_before_interception_and_slot_release_promotes_once()
			throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpApplicationExecution execution = execution(executor,
				(invocation, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.invoke();
				});
		AtomicReference<McpApplicationResponse> firstResponse = new AtomicReference<>();
		AtomicReference<McpApplicationResponse> secondResponse = new AtomicReference<>();
		AtomicReference<McpApplicationResponse> rejectedResponse = new AtomicReference<>();
		AtomicInteger cleanups = new AtomicInteger();
		McpApplicationRequestHandler handler = invocation -> {
			handlerInvocations.incrementAndGet();
			return result("handled");
		};

		try {
			execution.start();
			dispatch(execution, 4, "first", handler, firstResponse, cleanups);
			dispatch(execution, 5, "second", handler, secondResponse, cleanups);
			dispatch(execution, 6, "rejected", handler, rejectedResponse, cleanups);

			Assertions.assertEquals(1, execution.snapshot().activeHandlerSlots());
			Assertions.assertEquals(1, execution.snapshot().queuedRequests());
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(503, rejectedResponse.get().status());
			Assertions.assertEquals(0, handlerInvocations.get());

			runCommand(executor.takeCommand(), "mcp-interceptor-first-admitted-test");
			runCommand(executor.takeCommand(), "mcp-interceptor-second-admitted-test");

			Assertions.assertEquals(2, interceptorInvocations.get());
			Assertions.assertEquals(2, handlerInvocations.get());
			Assertions.assertEquals(200, firstResponse.get().status());
			Assertions.assertEquals(200, secondResponse.get().status());
			Assertions.assertEquals(3, cleanups.get());
			Assertions.assertEquals(1, execution.snapshot().capacityRejections());
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void cancellation_before_continuation_prevents_late_handler_entry()
			throws Exception {
		CountDownLatch interceptorEntered = new CountDownLatch(1);
		CountDownLatch continueInterceptor = new CountDownLatch(1);
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger responses = new AtomicInteger();
		AtomicInteger cleanups = new AtomicInteger();
		McpApplicationExecution execution = new McpApplicationExecution(
				configuration(), McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(), null,
				(invocation, continuation) -> {
					interceptorEntered.countDown();
					boolean released = false;
					while (!released) {
						try {
							released = continueInterceptor.await(25, TimeUnit.MILLISECONDS);
						} catch (InterruptedException ignored) {
							// Deliberately survive cooperative cancellation to exercise the
							// continuation's own late-entry guard.
						}
					}
					return continuation.invoke();
				});
		MicrohttpRequest transportRequest = transportRequest(7);

		try {
			execution.start();
			execution.dispatch(transportRequest, request("canceled"),
					Mcp20260728ProtocolProfile.INSTANCE,
					admissionIdentity("canceled"), invocation -> {
						handlerInvocations.incrementAndGet();
						return result("too-late");
					}, deadline(), response -> {
						responses.incrementAndGet();
						return true;
					}, cleanups::incrementAndGet);
			Assertions.assertTrue(interceptorEntered.await(5, TimeUnit.SECONDS));

			execution.cancel(transportRequest,
					StreamTerminationReason.CLIENT_DISCONNECTED, null);
			continueInterceptor.countDown();
			awaitCondition(() -> execution.snapshot().activeHandlerSlots() == 0
					&& execution.snapshot().retainedExchanges() == 0);

			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(0, responses.get());
			Assertions.assertEquals(1, cleanups.get());
		} finally {
			continueInterceptor.countDown();
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	private static void assertInterceptorFailure(
			McpApplicationRequestInterceptor interceptor) throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicReference<McpApplicationResponse> response = new AtomicReference<>();
		McpApplicationExecution execution = execution(executor, interceptor);
		try {
			execution.start();
			execution.dispatch(transportRequest(8), request("failure"),
					Mcp20260728ProtocolProfile.INSTANCE,
					admissionIdentity("failure"), invocation -> {
						handlerInvocations.incrementAndGet();
						return result("handler");
					}, deadline(), value -> {
						response.set(value);
						return true;
					}, () -> {});
			runCommand(executor.takeCommand(), "mcp-interceptor-failure-test");
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(500, response.get().status());
			McpJsonRpcMessage.ErrorResponse error = (McpJsonRpcMessage.ErrorResponse)
					response.get().message().orElseThrow();
			Assertions.assertEquals(McpJsonRpcError.INTERNAL_ERROR, error.error().code());
			Assertions.assertEquals("Internal error", error.error().message());
			Assertions.assertTrue(error.error().data().isEmpty());
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	private static McpApplicationExecution execution(ManualExecutorService executor,
			McpApplicationRequestInterceptor interceptor) {
		return new McpApplicationExecution(configuration(), McpApplicationClock.SYSTEM,
				ignored -> executor, null, interceptor);
	}

	private static McpApplicationExecutionConfiguration configuration() {
		return new McpApplicationExecutionConfiguration(
				1, 1, Duration.ofSeconds(30), Duration.ofDays(1));
	}

	private static void dispatch(McpApplicationExecution execution, int port,
			String id, McpApplicationRequestHandler handler,
			AtomicReference<McpApplicationResponse> response, AtomicInteger cleanups) {
		execution.dispatch(transportRequest(port), request(id),
				Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(id),
				handler, deadline(), value -> {
					response.set(value);
					return true;
				}, cleanups::incrementAndGet);
	}

	private static long deadline() {
		return System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
	}

	private static void runCommand(Runnable command, String threadName)
			throws InterruptedException {
		Assertions.assertNotNull(command);
		Thread thread = new Thread(command, threadName);
		thread.start();
		thread.join(TimeUnit.SECONDS.toMillis(5));
		Assertions.assertFalse(thread.isAlive());
	}

	private static void awaitCondition(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		do {
			if (condition.getAsBoolean())
				return;
			Thread.sleep(5L);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for application execution state.");
	}

	private static String responseResultValue(McpApplicationResponse response) {
		McpJsonRpcMessage.ResultResponse resultResponse =
				(McpJsonRpcMessage.ResultResponse) response.message().orElseThrow();
		return resultValue(resultResponse.result());
	}

	private static String resultValue(McpWireResult result) {
		return ((McpJsonString) result.fields().members().get("value")).value();
	}

	private static McpWireResult result(String value) {
		return McpWireResult.complete(new McpJsonObject(
				Map.of("value", new McpJsonString(value))));
	}

	private static McpEffectiveAdmissionIdentity admissionIdentity(String suffix) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"interception-" + suffix, "4.0.0-SNAPSHOT"))
				.build();
		return McpEffectiveAdmissionIdentity.resolve(endpoint, "/mcp",
				McpAdmissionIdentity.anonymousInstance());
	}

	private static MicrohttpRequest transportRequest(int sourcePort) {
		return new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", List.of(),
				new byte[0], false,
				new InetSocketAddress("127.0.0.1", 12_000 + sourcePort));
	}

	private static McpJsonRpcMessage.Request request(String id) {
		String json = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"test/execute\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		McpJsonLimits limits = McpJsonLimits.productionDefaults();
		McpJsonRpcEnvelope envelope = new McpJsonRpcEnvelopeCodec(
				new McpJsonCodec(limits)).decode(json.getBytes(StandardCharsets.UTF_8));
		return new McpRequestWireMapper(limits).map(
				(McpJsonRpcEnvelope.Request) envelope);
	}

	private static final class ManualExecutorService extends AbstractExecutorService {
		private boolean shutdown;
		private Runnable command;

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
		public synchronized void execute(Runnable command) {
			if (shutdown)
				throw new IllegalStateException("Executor is shut down.");
			if (this.command != null)
				throw new IllegalStateException("A command is already pending.");
			this.command = command;
		}

		private synchronized Runnable takeCommand() {
			Runnable value = command;
			command = null;
			return value;
		}
	}
}
