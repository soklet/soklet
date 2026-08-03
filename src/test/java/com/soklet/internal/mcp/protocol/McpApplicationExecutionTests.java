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

import javax.annotation.concurrent.NotThreadSafe;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@NotThreadSafe
public class McpApplicationExecutionTests {
	@Test
	public void protocol_operation_reservation_and_stop_share_the_execution_boundary()
			throws Exception {
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM);
		CountDownLatch reservationEntered = new CountDownLatch(1);
		CountDownLatch releaseReservation = new CountDownLatch(1);
		CountDownLatch stopAttempted = new CountDownLatch(1);
		AtomicInteger supplierInvocations = new AtomicInteger();
		AtomicReference<Optional<String>> reservation = new AtomicReference<>();
		AtomicReference<Throwable> reservationFailure = new AtomicReference<>();
		AtomicReference<Throwable> stopFailure = new AtomicReference<>();
		Thread reservationThread = new Thread(() -> {
			try {
				reservation.set(execution.reserveProtocolOperationIfRunning(() -> {
					supplierInvocations.incrementAndGet();
					reservationEntered.countDown();
					awaitLatch(releaseReservation);
					return "reserved";
				}));
			} catch (Throwable throwable) {
				reservationFailure.set(throwable);
			}
		}, "mcp-protocol-operation-reservation-test");
		Thread stopThread = new Thread(() -> {
			stopAttempted.countDown();
			try {
				execution.stop();
			} catch (Throwable throwable) {
				stopFailure.set(throwable);
			}
		}, "mcp-protocol-operation-stop-test");

		try {
			execution.start();
			reservationThread.start();
			Assertions.assertTrue(reservationEntered.await(5, TimeUnit.SECONDS));

			stopThread.start();
			Assertions.assertTrue(stopAttempted.await(5, TimeUnit.SECONDS));
			awaitCondition(() -> stopThread.getState() == Thread.State.BLOCKED);
			Assertions.assertTrue(stopThread.isAlive(),
					"Stop must wait for an in-progress deadline reservation.");

			releaseReservation.countDown();
			reservationThread.join(TimeUnit.SECONDS.toMillis(5));
			stopThread.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(reservationThread.isAlive());
			Assertions.assertFalse(stopThread.isAlive());
			Assertions.assertNull(reservationFailure.get());
			Assertions.assertNull(stopFailure.get());
			Assertions.assertEquals(Optional.of("reserved"), reservation.get(),
					"A reservation that owns the boundary before stop is allowed to finish.");
			Assertions.assertEquals(1, supplierInvocations.get());

			Optional<String> afterStop = execution.reserveProtocolOperationIfRunning(() -> {
				supplierInvocations.incrementAndGet();
				return "must-not-run";
			});
			Assertions.assertTrue(afterStop.isEmpty());
			Assertions.assertEquals(1, supplierInvocations.get(),
					"A reservation rejected after stop must not invoke its supplier.");
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		} finally {
			releaseReservation.countDown();
			execution.stop();
			reservationThread.join(TimeUnit.SECONDS.toMillis(5));
			stopThread.join(TimeUnit.SECONDS.toMillis(5));
		}
	}

	@Test
	public void production_bounds_are_fixed_and_every_configured_bound_is_positive() {
		McpApplicationExecutionConfiguration defaults =
				McpApplicationExecutionConfiguration.productionDefaults();
		Assertions.assertEquals(32, defaults.handlerConcurrency());
		Assertions.assertEquals(128, defaults.handlerQueueCapacity());
		Assertions.assertFalse(defaults.requestDeadline().isZero());
		Assertions.assertFalse(defaults.requestDeadline().isNegative());
		Assertions.assertFalse(defaults.timerResolution().isZero());
		Assertions.assertFalse(defaults.timerResolution().isNegative());

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpApplicationExecutionConfiguration(
						0, 1, Duration.ofSeconds(1), Duration.ofMillis(1)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpApplicationExecutionConfiguration(
						1, 0, Duration.ofSeconds(1), Duration.ofMillis(1)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpApplicationExecutionConfiguration(
						1, 1, Duration.ZERO, Duration.ofMillis(1)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(1), Duration.ZERO));
	}

	@Test
	public void cancellation_before_handler_thread_publication_retains_the_slot_until_exit()
			throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM, ignored -> executor);
		MicrohttpRequest transportRequest = transportRequest();
		McpJsonRpcMessage.Request request = request("pre-publication");
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger responses = new AtomicInteger();
		AtomicInteger cleanups = new AtomicInteger();

		try {
			execution.start();
			execution.dispatch(transportRequest, request, invocation -> {
				handlerInvocations.incrementAndGet();
				return McpWireResult.complete(McpJsonObject.empty());
			}, System.nanoTime() + TimeUnit.SECONDS.toNanos(30), response -> {
				responses.incrementAndGet();
				return true;
			}, cleanups::incrementAndGet);

			Assertions.assertNotNull(executor.command());
			Assertions.assertEquals(1, execution.snapshot().activeHandlerSlots());
			Assertions.assertEquals(1, execution.snapshot().retainedExchanges());
			execution.cancel(transportRequest,
					StreamTerminationReason.CLIENT_DISCONNECTED, null);

			McpApplicationExecutionSnapshot canceled = execution.snapshot();
			Assertions.assertEquals(1, canceled.activeHandlerSlots());
			Assertions.assertEquals(1, canceled.retainedExchanges(),
					"A dispatched-but-not-started ticket still owns its handler slot.");
			Assertions.assertEquals(0, canceled.retainedTransportLeases(),
					"Cancellation must detach raw transport and callback ownership.");
			Assertions.assertEquals(0, canceled.activeRequestIds());
			Assertions.assertEquals(1, canceled.abandonedResponses());
			Assertions.assertEquals(0, responses.get());
			Assertions.assertEquals(1, cleanups.get());

			Thread handlerThread = new Thread(executor.takeCommand(),
					"mcp-pre-publication-cancellation-test");
			handlerThread.start();
			handlerThread.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(handlerThread.isAlive());
			awaitCondition(() -> execution.snapshot().activeHandlerSlots() == 0
					&& execution.snapshot().retainedExchanges() == 0);
			Assertions.assertEquals(0, handlerInvocations.get(),
					"Canceled application code must not begin after thread publication.");
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void dispatched_work_that_starts_after_stop_never_invokes_application_code()
			throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM, ignored -> executor);
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger responses = new AtomicInteger();
		AtomicInteger cleanups = new AtomicInteger();

		execution.start();
		execution.dispatch(transportRequest(), request("start-after-stop"), invocation -> {
			handlerInvocations.incrementAndGet();
			return McpWireResult.complete(McpJsonObject.empty());
		}, System.nanoTime() + TimeUnit.SECONDS.toNanos(30), response -> {
			responses.incrementAndGet();
			return true;
		}, cleanups::incrementAndGet);
		Runnable command = executor.takeCommand();
		Assertions.assertNotNull(command);

		execution.stop();
		Thread handlerThread = new Thread(command, "mcp-start-after-stop-test");
		handlerThread.start();
		handlerThread.join(TimeUnit.SECONDS.toMillis(5));

		Assertions.assertFalse(handlerThread.isAlive());
		Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		Assertions.assertEquals(0, handlerInvocations.get());
		Assertions.assertEquals(0, responses.get());
		Assertions.assertEquals(1, cleanups.get());
		McpApplicationExecutionSnapshot snapshot = execution.snapshot();
		Assertions.assertEquals(0, snapshot.activeHandlerSlots());
		Assertions.assertEquals(0, snapshot.retainedExchanges());
		Assertions.assertEquals(0, snapshot.retainedTransportLeases());
		Assertions.assertEquals(1, snapshot.abandonedResponses());
	}

	@Test
	public void deadline_captured_before_dispatch_expires_without_handler_admission()
			throws Exception {
		AtomicLong now = new AtomicLong(100L);
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				now::get);
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger cleanups = new AtomicInteger();
		AtomicReference<McpApplicationResponse> response = new AtomicReference<>();

		try {
			execution.start();
			execution.dispatch(transportRequest(), request("already-expired"), invocation -> {
				handlerInvocations.incrementAndGet();
				return McpWireResult.complete(McpJsonObject.empty());
			}, 99L, value -> {
				response.set(value);
				return true;
			}, cleanups::incrementAndGet);

			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(503, response.get().status());
			Assertions.assertEquals(1, cleanups.get());
			McpApplicationExecutionSnapshot snapshot = execution.snapshot();
			Assertions.assertEquals(0, snapshot.activeHandlerSlots());
			Assertions.assertEquals(0, snapshot.queuedRequests());
			Assertions.assertEquals(1, snapshot.deadlineExpirations());
			Assertions.assertEquals(0, snapshot.capacityRejections());
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void cancellation_during_failed_executor_submission_releases_retained_exchange()
			throws Exception {
		CancelThenRejectExecutorService executor = new CancelThenRejectExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM, ignored -> executor);
		MicrohttpRequest transportRequest = transportRequest();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger responses = new AtomicInteger();
		AtomicInteger cleanups = new AtomicInteger();
		executor.beforeReject(() -> execution.cancel(transportRequest,
				StreamTerminationReason.CLIENT_DISCONNECTED, null));

		try {
			execution.start();
			execution.dispatch(transportRequest, request("cancel-during-rejection"),
					invocation -> {
						handlerInvocations.incrementAndGet();
						return McpWireResult.complete(McpJsonObject.empty());
					}, System.nanoTime() + TimeUnit.SECONDS.toNanos(30), response -> {
						responses.incrementAndGet();
						return true;
					}, cleanups::incrementAndGet);

			McpApplicationExecutionSnapshot snapshot = execution.snapshot();
			Assertions.assertEquals(0, snapshot.activeHandlerSlots());
			Assertions.assertEquals(0, snapshot.retainedExchanges());
			Assertions.assertEquals(0, snapshot.retainedTransportLeases());
			Assertions.assertEquals(1, snapshot.abandonedResponses());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(0, responses.get());
			Assertions.assertEquals(1, cleanups.get());
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void pre_admission_deadline_keeps_response_ownership_until_writer_finishes()
			throws Exception {
		CountDownLatch preAdmissionCheckEntered = new CountDownLatch(1);
		CountDownLatch allowPreAdmissionCheck = new CountDownLatch(1);
		CountDownLatch writerEntered = new CountDownLatch(1);
		CountDownLatch allowWriter = new CountDownLatch(1);
		AtomicReference<Thread> dispatchThread = new AtomicReference<>();
		AtomicBoolean transportTerminated = new AtomicBoolean();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger cleanups = new AtomicInteger();
		McpApplicationClock clock = () -> {
			if (Thread.currentThread() == dispatchThread.get()) {
				preAdmissionCheckEntered.countDown();
				awaitLatch(allowPreAdmissionCheck);
				return 0L;
			}
			return 100L;
		};
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)), clock);

		try {
			execution.start();
			Thread dispatch = new Thread(() -> {
				dispatchThread.set(Thread.currentThread());
				execution.dispatch(transportRequest(), request("pre-admission-deadline"),
						invocation -> {
							handlerInvocations.incrementAndGet();
							return McpWireResult.complete(McpJsonObject.empty());
						}, 50L, response -> {
							writerEntered.countDown();
							awaitLatch(allowWriter);
							return !transportTerminated.get();
						}, () -> {
							transportTerminated.set(true);
							cleanups.incrementAndGet();
						});
			}, "mcp-pre-admission-dispatch-test");
			dispatch.start();
			Assertions.assertTrue(preAdmissionCheckEntered.await(5, TimeUnit.SECONDS));

			Thread deadline = new Thread(execution::runTimerCycle,
					"mcp-pre-admission-deadline-test");
			deadline.start();
			Assertions.assertTrue(writerEntered.await(5, TimeUnit.SECONDS));
			allowPreAdmissionCheck.countDown();
			dispatch.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(dispatch.isAlive());
			Assertions.assertEquals(0, cleanups.get(),
					"A CANCELED admission must not steal a reserved deadline response.");
			Assertions.assertEquals(1, execution.snapshot().retainedTransportLeases());

			allowWriter.countDown();
			deadline.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(deadline.isAlive());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(1, cleanups.get());
			McpApplicationExecutionSnapshot snapshot = execution.snapshot();
			Assertions.assertEquals(0, snapshot.retainedExchanges());
			Assertions.assertEquals(0, snapshot.retainedTransportLeases());
			Assertions.assertEquals(1, snapshot.terminalResponses());
			Assertions.assertEquals(0, snapshot.abandonedResponses());
		} finally {
			allowPreAdmissionCheck.countDown();
			allowWriter.countDown();
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	private static MicrohttpRequest transportRequest() {
		return new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", List.of(),
				new byte[0], false, new InetSocketAddress("127.0.0.1", 12345));
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

	private static void awaitCondition(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		do {
			if (condition.getAsBoolean())
				return;
			Thread.sleep(5L);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for application execution state.");
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS))
				throw new AssertionError("Timed out waiting for test coordination.");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Test coordination was interrupted.", exception);
		}
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

		private synchronized Runnable command() {
			return command;
		}

		private synchronized Runnable takeCommand() {
			Runnable value = command;
			command = null;
			return value;
		}
	}

	private static final class CancelThenRejectExecutorService
			extends AbstractExecutorService {
		private boolean shutdown;
		private Runnable beforeReject;

		private void beforeReject(Runnable beforeReject) {
			this.beforeReject = beforeReject;
		}

		@Override
		public void shutdown() {
			shutdown = true;
		}

		@Override
		public List<Runnable> shutdownNow() {
			shutdown = true;
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return shutdown;
		}

		@Override
		public boolean isTerminated() {
			return shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return shutdown;
		}

		@Override
		public void execute(Runnable command) {
			if (beforeReject != null)
				beforeReject.run();
			throw new RejectedExecutionException("simulated submission failure");
		}
	}
}
