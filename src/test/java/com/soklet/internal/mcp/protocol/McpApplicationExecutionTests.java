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
import com.soklet.McpRequestOutcome;
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
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
	public void framework_protocol_errors_remain_protocol_observation_outcomes() {
		McpJsonRpcId id = new McpJsonRpcId.StringId("conditional-capability");
		McpJsonRpcError error = McpJsonRpcError.missingRequiredClientCapabilities(
				Set.of(McpCoreClientCapability.ELICITATION_URL));

		McpApplicationResponse response =
				McpApplicationResponse.protocolJsonRpcError(id, error);

		Assertions.assertEquals(400, response.status());
		Assertions.assertEquals("Bad Request", response.reason());
		Assertions.assertEquals(McpRequestOutcome.PROTOCOL_ERROR,
				response.outcome());
		McpJsonRpcMessage.ErrorResponse message =
				(McpJsonRpcMessage.ErrorResponse) response.message().orElseThrow();
		Assertions.assertEquals(Optional.of(id), message.id());
		Assertions.assertSame(error, message.error());
		Assertions.assertTrue(response.throwables().isEmpty());
	}

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
			execution.dispatch(transportRequest, request,
					Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(), invocation -> {
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
			Assertions.assertEquals(0,
					canceled.activeIdentifiedRequestExchanges());
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
		execution.dispatch(transportRequest(), request("start-after-stop"),
				Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(), invocation -> {
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
			execution.dispatch(transportRequest(), request("already-expired"),
					Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(), invocation -> {
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
	public void bounded_policy_expired_before_admission_never_reaches_dispatcher()
			throws Exception {
		AtomicLong now = new AtomicLong(100L);
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				now::get, ignored -> executor);
		AtomicInteger callbackInvocations = new AtomicInteger();

		try {
			execution.start();
			McpApplicationPolicyDeadlineException exception =
					Assertions.assertThrows(
							McpApplicationPolicyDeadlineException.class,
							() -> execution.invokeBoundedPolicy(() -> {
								callbackInvocations.incrementAndGet();
								return "must-not-run";
							}, 100L));

			Assertions.assertTrue(exception.queued(),
					"Pre-admission expiration retains queued 503 semantics.");
			Assertions.assertEquals(0, callbackInvocations.get());
			Assertions.assertNull(executor.command());
			Assertions.assertEquals(0, execution.snapshot().activeHandlerSlots());
			Assertions.assertEquals(0, execution.snapshot().queuedRequests());
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void bounded_policy_expired_after_dispatch_but_before_entry_is_active()
			throws Exception {
		AtomicLong now = new AtomicLong();
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				now::get, ignored -> executor);
		AtomicInteger callbackInvocations = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		long deadline = TimeUnit.SECONDS.toNanos(30);
		Thread caller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> {
					callbackInvocations.incrementAndGet();
					return "must-not-run";
				}, deadline);
			} catch (Throwable throwable) {
				failure.set(throwable);
			}
		}, "mcp-bounded-policy-active-deadline-test");

		try {
			execution.start();
			caller.start();
			awaitCondition(() -> executor.command() != null);
			now.set(deadline);
			executor.takeCommand().run();
			caller.join(TimeUnit.SECONDS.toMillis(5));

			Assertions.assertFalse(caller.isAlive());
			McpApplicationPolicyDeadlineException exception =
					Assertions.assertInstanceOf(
							McpApplicationPolicyDeadlineException.class,
							failure.get());
			Assertions.assertFalse(exception.queued(),
					"A ticket that owned a slot retains active 504 semantics.");
			Assertions.assertEquals(0, callbackInvocations.get());
		} finally {
			execution.stop();
			caller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void bounded_policy_deadline_reserves_reason_before_interrupt()
			throws Exception {
		long deadline = TimeUnit.SECONDS.toNanos(30);
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch callbackInterrupted = new CountDownLatch(1);
		ActivePolicyDeadlineClock clock = new ActivePolicyDeadlineClock(
				deadline, callbackEntered);
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				clock, ignored -> executor);
		AtomicReference<StreamTerminationReason> reservedReason =
				new AtomicReference<>();
		AtomicReference<StreamTerminationReason> reasonObservedAtInterrupt =
				new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread caller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> {
					callbackEntered.countDown();
					try {
						new CountDownLatch(1).await();
					} catch (InterruptedException ignored) {
						reasonObservedAtInterrupt.set(reservedReason.get());
						callbackInterrupted.countDown();
					}
					return "interrupted";
				}, deadline, reason -> reservedReason.compareAndSet(null, reason));
			} catch (Throwable throwable) {
				failure.set(throwable);
			}
		}, "mcp-bounded-policy-token-order-test");
		Thread worker = null;

		try {
			execution.start();
			caller.start();
			awaitCondition(() -> executor.command() != null);
			worker = new Thread(executor.takeCommand(),
					"mcp-bounded-policy-token-order-worker");
			worker.start();

			Assertions.assertTrue(callbackInterrupted.await(5, TimeUnit.SECONDS),
					"The active policy callback was not interrupted.");
			caller.join(TimeUnit.SECONDS.toMillis(5));
			worker.join(TimeUnit.SECONDS.toMillis(5));

			Assertions.assertFalse(caller.isAlive());
			Assertions.assertFalse(worker.isAlive());
			McpApplicationPolicyDeadlineException exception =
					Assertions.assertInstanceOf(
							McpApplicationPolicyDeadlineException.class,
							failure.get());
			Assertions.assertFalse(exception.queued());
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT,
					reasonObservedAtInterrupt.get(),
					"The public reason must be fixed before interruption is visible.");
			awaitCondition(() -> execution.snapshot().activeHandlerSlots() == 0);
		} finally {
			execution.stop();
			caller.interrupt();
			if (worker != null)
				worker.interrupt();
			caller.join(TimeUnit.SECONDS.toMillis(5));
			if (worker != null)
				worker.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void bounded_policy_completion_after_deadline_is_not_accepted()
			throws Exception {
		AtomicLong now = new AtomicLong();
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				now::get, ignored -> executor);
		AtomicInteger callbackInvocations = new AtomicInteger();
		AtomicReference<String> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		long deadline = TimeUnit.SECONDS.toNanos(30);
		Thread caller = new Thread(() -> {
			try {
				result.set(execution.invokeBoundedPolicy(() -> {
					callbackInvocations.incrementAndGet();
					now.set(deadline);
					return "late-result";
				}, deadline));
			} catch (Throwable throwable) {
				failure.set(throwable);
			}
		}, "mcp-bounded-policy-late-completion-test");

		try {
			execution.start();
			caller.start();
			awaitCondition(() -> executor.command() != null);
			executor.takeCommand().run();
			caller.join(TimeUnit.SECONDS.toMillis(5));

			Assertions.assertFalse(caller.isAlive());
			McpApplicationPolicyDeadlineException exception =
					Assertions.assertInstanceOf(
							McpApplicationPolicyDeadlineException.class,
							failure.get());
			Assertions.assertFalse(exception.queued(),
					"A callback that entered retains active 504 semantics.");
			Assertions.assertNull(result.get());
			Assertions.assertEquals(1, callbackInvocations.get());
			Assertions.assertEquals(0, execution.snapshot().activeHandlerSlots());
		} finally {
			execution.stop();
			caller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void graceful_drain_wakes_day_long_timer_after_bounded_policy_slot_exits()
			throws Exception {
		AtomicInteger timerCycles = new AtomicInteger();
		AtomicReference<Thread> timerThread = new AtomicReference<>();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(), nowNanos -> {
					timerThread.compareAndSet(null, Thread.currentThread());
					timerCycles.incrementAndGet();
				});
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch releaseCallback = new CountDownLatch(1);
		AtomicReference<String> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		Thread caller = new Thread(() -> {
			try {
				result.set(execution.invokeBoundedPolicy(() -> {
					callbackEntered.countDown();
					releaseCallback.await();
					return "drained";
				}, deadline));
			} catch (Throwable throwable) {
				failure.set(throwable);
			}
		}, "mcp-bounded-policy-graceful-drain-test");

		try {
			execution.start();
			awaitCondition(() -> timerCycles.get() > 0);
			caller.start();
			Assertions.assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
			int cyclesBeforeDrain = timerCycles.get();

			execution.beginGracefulDrain();
			awaitCondition(() -> timerCycles.get() > cyclesBeforeDrain);
			awaitCondition(() -> timerThread.get() != null
					&& timerThread.get().getState() == Thread.State.TIMED_WAITING);
			Assertions.assertFalse(execution.isTerminated(),
					"Graceful drain must retain the active policy slot.");

			releaseCallback.countDown();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)),
					"The released slot must wake a timer parked at a day-long resolution.");
			caller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(caller.isAlive());
			Assertions.assertNull(failure.get());
			Assertions.assertEquals("drained", result.get());
			Assertions.assertEquals(0, execution.snapshot().activeHandlerSlots());
		} finally {
			releaseCallback.countDown();
			execution.stop();
			caller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void bounded_policy_queued_ticket_times_out_while_still_queued()
			throws Exception {
		AtomicLong now = new AtomicLong();
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				now::get, ignored -> executor);
		AtomicInteger activeInvocations = new AtomicInteger();
		AtomicInteger queuedInvocations = new AtomicInteger();
		AtomicReference<String> activeResult = new AtomicReference<>();
		AtomicReference<Throwable> activeFailure = new AtomicReference<>();
		AtomicReference<Throwable> queuedFailure = new AtomicReference<>();
		long queuedDeadline = TimeUnit.SECONDS.toNanos(1);
		long activeDeadline = TimeUnit.SECONDS.toNanos(60);
		Thread activeCaller = new Thread(() -> {
			try {
				activeResult.set(execution.invokeBoundedPolicy(() -> {
					activeInvocations.incrementAndGet();
					return "active";
				}, activeDeadline));
			} catch (Throwable throwable) {
				activeFailure.set(throwable);
			}
		}, "mcp-bounded-policy-active-caller-test");
		Thread queuedCaller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> {
					queuedInvocations.incrementAndGet();
					return "must-not-run";
				}, queuedDeadline);
			} catch (Throwable throwable) {
				queuedFailure.set(throwable);
			}
		}, "mcp-bounded-policy-queued-caller-test");

		try {
			execution.start();
			activeCaller.start();
			awaitCondition(() -> executor.command() != null);
			queuedCaller.start();
			awaitCondition(() -> execution.snapshot().queuedRequests() == 1);
			queuedCaller.join(TimeUnit.SECONDS.toMillis(5));

			Assertions.assertFalse(queuedCaller.isAlive());
			McpApplicationPolicyDeadlineException exception =
					Assertions.assertInstanceOf(
							McpApplicationPolicyDeadlineException.class,
							queuedFailure.get());
			Assertions.assertTrue(exception.queued(),
					"Only successful removal from the queue has 503 semantics.");
			Assertions.assertEquals(0, queuedInvocations.get());
			Assertions.assertEquals(0, execution.snapshot().queuedRequests());

			executor.takeCommand().run();
			activeCaller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(activeCaller.isAlive());
			Assertions.assertNull(activeFailure.get());
			Assertions.assertEquals("active", activeResult.get());
			Assertions.assertEquals(1, activeInvocations.get());
		} finally {
			execution.stop();
			activeCaller.join(TimeUnit.SECONDS.toMillis(5));
			queuedCaller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void bounded_policy_promoted_before_entry_uses_active_deadline_semantics()
			throws Exception {
		AtomicLong now = new AtomicLong();
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				now::get, ignored -> executor);
		AtomicInteger queuedInvocations = new AtomicInteger();
		AtomicReference<Throwable> activeFailure = new AtomicReference<>();
		AtomicReference<Throwable> promotedFailure = new AtomicReference<>();
		long promotedDeadline = TimeUnit.SECONDS.toNanos(30);
		long activeDeadline = TimeUnit.SECONDS.toNanos(60);
		Thread activeCaller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> "active", activeDeadline);
			} catch (Throwable throwable) {
				activeFailure.set(throwable);
			}
		}, "mcp-bounded-policy-promotion-owner-test");
		Thread promotedCaller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> {
					queuedInvocations.incrementAndGet();
					return "must-not-run";
				}, promotedDeadline);
			} catch (Throwable throwable) {
				promotedFailure.set(throwable);
			}
		}, "mcp-bounded-policy-promoted-caller-test");

		try {
			execution.start();
			activeCaller.start();
			awaitCondition(() -> executor.command() != null);
			promotedCaller.start();
			awaitCondition(() -> execution.snapshot().queuedRequests() == 1);

			now.set(promotedDeadline);
			executor.takeCommand().run();
			awaitCondition(() -> executor.command() != null);
			executor.takeCommand().run();
			activeCaller.join(TimeUnit.SECONDS.toMillis(5));
			promotedCaller.join(TimeUnit.SECONDS.toMillis(5));

			Assertions.assertFalse(activeCaller.isAlive());
			Assertions.assertFalse(promotedCaller.isAlive());
			Assertions.assertNull(activeFailure.get());
			McpApplicationPolicyDeadlineException exception =
					Assertions.assertInstanceOf(
							McpApplicationPolicyDeadlineException.class,
							promotedFailure.get());
			Assertions.assertFalse(exception.queued(),
					"Promotion ends queued state and therefore maps to active 504 semantics.");
			Assertions.assertEquals(0, queuedInvocations.get());
		} finally {
			execution.stop();
			activeCaller.join(TimeUnit.SECONDS.toMillis(5));
			promotedCaller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void stop_wakes_queued_bounded_policy_callers_without_invocation()
			throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM, ignored -> executor);
		AtomicInteger activeInvocations = new AtomicInteger();
		AtomicInteger queuedInvocations = new AtomicInteger();
		AtomicReference<Throwable> activeFailure = new AtomicReference<>();
		AtomicReference<Throwable> queuedFailure = new AtomicReference<>();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		Thread activeCaller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> {
					activeInvocations.incrementAndGet();
					return "must-not-run";
				}, deadline);
			} catch (Throwable throwable) {
				activeFailure.set(throwable);
			}
		}, "mcp-bounded-policy-stop-owner-test");
		Thread queuedCaller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> {
					queuedInvocations.incrementAndGet();
					return "must-not-run";
				}, deadline);
			} catch (Throwable throwable) {
				queuedFailure.set(throwable);
			}
		}, "mcp-bounded-policy-queued-stop-test");

		try {
			execution.start();
			activeCaller.start();
			awaitCondition(() -> executor.command() != null);
			queuedCaller.start();
			awaitCondition(() -> execution.snapshot().queuedRequests() == 1);

			execution.stop();
			activeCaller.join(TimeUnit.SECONDS.toMillis(5));
			queuedCaller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(activeCaller.isAlive());
			Assertions.assertFalse(queuedCaller.isAlive());
			Assertions.assertInstanceOf(
					McpApplicationExecutionStoppedException.class,
					activeFailure.get());
			Assertions.assertInstanceOf(
					McpApplicationExecutionStoppedException.class,
					queuedFailure.get());
			Assertions.assertEquals(0, activeInvocations.get());
			Assertions.assertEquals(0, queuedInvocations.get());
			Assertions.assertEquals(0, execution.snapshot().queuedRequests());

			Runnable dispatched = executor.takeCommand();
			Assertions.assertNotNull(dispatched);
			dispatched.run();
			Assertions.assertEquals(0, execution.snapshot().activeHandlerSlots());
		} finally {
			Runnable pending = executor.takeCommand();
			if (pending != null)
				pending.run();
			execution.stop();
			activeCaller.join(TimeUnit.SECONDS.toMillis(5));
			queuedCaller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void stop_interrupts_active_bounded_policy_work_and_wakes_its_caller()
			throws Exception {
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM);
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch callbackExited = new CountDownLatch(1);
		AtomicBoolean callbackInterrupted = new AtomicBoolean();
		AtomicReference<Throwable> callerFailure = new AtomicReference<>();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		Thread caller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> {
					callbackEntered.countDown();
					try {
						new CountDownLatch(1).await();
						return "must-not-complete";
					} catch (InterruptedException exception) {
						callbackInterrupted.set(true);
						throw exception;
					} finally {
						callbackExited.countDown();
					}
				}, deadline);
			} catch (Throwable throwable) {
				callerFailure.set(throwable);
			}
		}, "mcp-bounded-policy-active-stop-test");

		try {
			execution.start();
			caller.start();
			Assertions.assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));

			execution.stop();
			Assertions.assertTrue(callbackExited.await(5, TimeUnit.SECONDS));
			caller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(caller.isAlive());
			Assertions.assertTrue(callbackInterrupted.get());
			Assertions.assertInstanceOf(
					McpApplicationExecutionStoppedException.class,
					callerFailure.get());
		} finally {
			execution.stop();
			caller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void blocking_exchange_cancel_callback_does_not_delay_policy_stop_signals()
			throws Exception {
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						2, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM);
		CountDownLatch exchangeHandlerEntered = new CountDownLatch(1);
		CountDownLatch exchangeCancelEntered = new CountDownLatch(1);
		CountDownLatch releaseExchangeCancel = new CountDownLatch(1);
		CountDownLatch releaseExchangeHandler = new CountDownLatch(1);
		CountDownLatch activePolicyEntered = new CountDownLatch(1);
		CountDownLatch activePolicyInterrupted = new CountDownLatch(1);
		CountDownLatch releaseActivePolicy = new CountDownLatch(1);
		AtomicReference<Throwable> activePolicyFailure = new AtomicReference<>();
		AtomicReference<Throwable> queuedPolicyFailure = new AtomicReference<>();
		AtomicReference<Throwable> stopFailure = new AtomicReference<>();
		AtomicInteger queuedPolicyInvocations = new AtomicInteger();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		Thread activePolicyCaller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> {
					activePolicyEntered.countDown();
					try {
						releaseActivePolicy.await();
						return "must-not-complete";
					} catch (InterruptedException exception) {
						activePolicyInterrupted.countDown();
						throw exception;
					}
				}, deadline);
			} catch (Throwable throwable) {
				activePolicyFailure.set(throwable);
			}
		}, "mcp-bounded-policy-blocking-cancel-active-test");
		Thread queuedPolicyCaller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> {
					queuedPolicyInvocations.incrementAndGet();
					return "must-not-run";
				}, deadline);
			} catch (Throwable throwable) {
				queuedPolicyFailure.set(throwable);
			}
		}, "mcp-bounded-policy-blocking-cancel-queued-test");
		Thread stopThread = new Thread(() -> {
			try {
				execution.stop();
			} catch (Throwable throwable) {
				stopFailure.set(throwable);
			}
		}, "mcp-bounded-policy-blocking-cancel-stop-test");

		try {
			execution.start();
			execution.dispatch(transportRequest(), request("blocking-cancel-exchange"),
					Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(), invocation -> {
						invocation.cancelationToken().onCancel(() -> {
							exchangeCancelEntered.countDown();
							try {
								releaseExchangeCancel.await();
							} catch (InterruptedException exception) {
								Thread.currentThread().interrupt();
								throw new AssertionError(
										"The blocking cancellation callback was interrupted.",
										exception);
							}
						});
						exchangeHandlerEntered.countDown();
						releaseExchangeHandler.await();
						return McpWireResult.complete(McpJsonObject.empty());
					}, deadline, ignored -> true, () -> {});
			Assertions.assertTrue(exchangeHandlerEntered.await(5, TimeUnit.SECONDS));

			activePolicyCaller.start();
			Assertions.assertTrue(activePolicyEntered.await(5, TimeUnit.SECONDS));
			queuedPolicyCaller.start();
			awaitCondition(() -> execution.snapshot().queuedRequests() == 1);

			stopThread.start();
			Assertions.assertTrue(exchangeCancelEntered.await(5, TimeUnit.SECONDS),
					"Stop did not reach the blocking Exchange cancellation callback.");
			Assertions.assertEquals(1L, releaseExchangeCancel.getCount(),
					"The Exchange cancellation callback must remain blocked for the probe.");
			Assertions.assertTrue(stopThread.isAlive(),
					"Stop must still be inside the blocking application callback.");

			Assertions.assertTrue(activePolicyInterrupted.await(5, TimeUnit.SECONDS),
					"Active policy interruption was delayed behind an application callback.");
			activePolicyCaller.join(TimeUnit.SECONDS.toMillis(5));
			queuedPolicyCaller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(activePolicyCaller.isAlive());
			Assertions.assertFalse(queuedPolicyCaller.isAlive());
			Assertions.assertInstanceOf(McpApplicationExecutionStoppedException.class,
					activePolicyFailure.get());
			Assertions.assertInstanceOf(McpApplicationExecutionStoppedException.class,
					queuedPolicyFailure.get());
			Assertions.assertEquals(0, queuedPolicyInvocations.get());
			Assertions.assertEquals(1L, releaseExchangeCancel.getCount(),
					"Policy stop signals must arrive before the callback is released.");

			releaseExchangeCancel.countDown();
			stopThread.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(stopThread.isAlive());
			Assertions.assertNull(stopFailure.get());
		} finally {
			releaseExchangeCancel.countDown();
			releaseExchangeHandler.countDown();
			releaseActivePolicy.countDown();
			execution.stop();
			stopThread.join(TimeUnit.SECONDS.toMillis(5));
			activePolicyCaller.join(TimeUnit.SECONDS.toMillis(5));
			queuedPolicyCaller.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void progress_notifications_do_not_extend_the_absolute_request_deadline()
			throws Exception {
		AtomicLong now = new AtomicLong();
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				now::get, ignored -> executor);
		CountDownLatch firstProgress = new CountDownLatch(1);
		CountDownLatch allowSecondProgress = new CountDownLatch(1);
		CountDownLatch secondProgress = new CountDownLatch(1);
		CountDownLatch holdHandler = new CountDownLatch(1);
		CountDownLatch cancelationCallback = new CountDownLatch(1);
		AtomicInteger notificationWrites = new AtomicInteger();
		AtomicInteger cleanups = new AtomicInteger();
		AtomicReference<McpApplicationResponse> terminalResponse =
				new AtomicReference<>();
		AtomicReference<CancelationToken> token = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> cancelationReason =
				new AtomicReference<>();
		AtomicBoolean handlerInterrupted = new AtomicBoolean();
		McpApplicationResponseWriter writer =
				new McpApplicationResponseWriter() {
					@Override
					public boolean write(McpApplicationResponse response) {
						terminalResponse.set(response);
						return true;
					}

					@Override
					public boolean writeNotification(
							McpJsonRpcMessage.Notification notification) {
						Assertions.assertEquals("notifications/progress",
								notification.method());
						notificationWrites.incrementAndGet();
						return true;
					}
				};
		Thread handlerThread = null;

		try {
			execution.start();
			execution.dispatch(transportRequest(), progressRequest("deadline-progress"),
					Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(), invocation -> {
						token.set(invocation.cancelationToken());
						invocation.cancelationToken().onCancel(() -> {
							cancelationReason.set(invocation.cancellationReason()
									.orElse(null));
							cancelationCallback.countDown();
						});
						McpServerRuntimeBridge.ProgressEmitter emitter =
								McpServerRuntimeBridge.progressEmitterFor(invocation,
										McpInputRequestPlan.empty()).orElseThrow();
						Assertions.assertTrue(emitter.emit(0.0d,
								Optional.of(100.0d), Optional.empty()));
						firstProgress.countDown();
						if (!allowSecondProgress.await(5, TimeUnit.SECONDS))
							throw new AssertionError(
									"Second progress update was not released.");
						Assertions.assertTrue(emitter.emit(50.0d,
								Optional.of(100.0d), Optional.empty()));
						secondProgress.countDown();
						try {
							holdHandler.await();
						} catch (InterruptedException exception) {
							handlerInterrupted.set(true);
							Thread.currentThread().interrupt();
						}
						return McpWireResult.complete(McpJsonObject.empty());
					}, 100L, writer, cleanups::incrementAndGet);

			handlerThread = new Thread(executor.takeCommand(),
					"mcp-progress-deadline-test");
			handlerThread.start();
			Assertions.assertTrue(firstProgress.await(5, TimeUnit.SECONDS));

			now.set(99L);
			execution.runTimerCycle();
			Assertions.assertNull(terminalResponse.get());
			allowSecondProgress.countDown();
			Assertions.assertTrue(secondProgress.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(2, notificationWrites.get());
			Assertions.assertFalse(token.get().isCanceled());

			now.set(100L);
			execution.runTimerCycle();
			Assertions.assertTrue(cancelationCallback.await(5, TimeUnit.SECONDS));
			handlerThread.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(handlerThread.isAlive());
			Assertions.assertTrue(handlerInterrupted.get());
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT,
					cancelationReason.get());
			Assertions.assertTrue(token.get().isCanceled());
			Assertions.assertEquals(McpApplicationResponse.activeDeadline(
					new McpJsonRpcId.StringId("deadline-progress")),
					terminalResponse.get());
			Assertions.assertEquals(1, cleanups.get());
			Assertions.assertEquals(1,
					execution.snapshot().deadlineExpirations());
		} finally {
			allowSecondProgress.countDown();
			holdHandler.countDown();
			execution.stop();
			if (handlerThread != null)
				handlerThread.join(TimeUnit.SECONDS.toMillis(5));
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
					Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(),
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
						Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(),
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

	@Test
	public void queued_cancel_and_deadline_drains_release_exchange_monitors()
			throws Exception {
		assertQueuedDequeueDrainReleasesExchangeMonitors(false);
		assertQueuedDequeueDrainReleasesExchangeMonitors(true);
	}

	private static void assertQueuedDequeueDrainReleasesExchangeMonitors(
			boolean deadline) throws Exception {
		ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
		AtomicLong now = new AtomicLong();
		DeferredDequeuedProbeObserver observer =
				new DeferredDequeuedProbeObserver(probeExecutor);
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				now::get, McpApplicationHandlerExecutorFactory.production(),
				null, observer);
		MicrohttpRequest activeRequest = transportRequest();
		MicrohttpRequest queuedRequest = transportRequest();
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch releaseActive = new CountDownLatch(1);
		AtomicInteger queuedInvocations = new AtomicInteger();
		AtomicInteger queuedResponses = new AtomicInteger();
		AtomicInteger queuedCleanups = new AtomicInteger();

		try {
			execution.start();
			execution.dispatch(activeRequest,
					request(deadline ? "deadline-active" : "cancel-active"),
					Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(), invocation -> {
						activeEntered.countDown();
						releaseActive.await();
						return McpWireResult.complete(McpJsonObject.empty());
					}, 1_000L, response -> true, () -> {});
			Assertions.assertTrue(activeEntered.await(5, TimeUnit.SECONDS),
					"The monitor-probe handler did not enter.");

			execution.dispatch(queuedRequest,
					request(deadline ? "deadline-queued" : "cancel-queued"),
					Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(), invocation -> {
						queuedInvocations.incrementAndGet();
						return McpWireResult.complete(McpJsonObject.empty());
					}, deadline ? 10L : 1_000L, response -> {
						queuedResponses.incrementAndGet();
						return true;
					}, queuedCleanups::incrementAndGet);
			awaitCondition(() -> execution.snapshot().queuedRequests() == 1);

			if (deadline) {
				observer.probeWith(execution::runTimerCycle);
				now.set(10L);
				execution.runTimerCycle();
			} else {
				observer.probeWith(() -> execution.cancel(queuedRequest,
						StreamTerminationReason.CLIENT_DISCONNECTED, null));
				execution.cancel(queuedRequest,
						StreamTerminationReason.CLIENT_DISCONNECTED, null);
			}

			observer.awaitProbe();
			Assertions.assertNull(observer.probeFailure(),
					deadline
							? "Dequeued delivery retained the execution-boundary monitor."
							: "Dequeued delivery retained the exchange terminal monitor.");
			awaitCondition(() -> execution.snapshot().retainedExchanges() == 1
					&& queuedCleanups.get() == 1);
			McpApplicationExecutionSnapshot dequeued = execution.snapshot();
			Assertions.assertEquals(1, dequeued.activeHandlerSlots());
			Assertions.assertEquals(0, dequeued.queuedRequests());
			Assertions.assertEquals(1, dequeued.retainedExchanges());
			Assertions.assertEquals(0, queuedInvocations.get());
			Assertions.assertEquals(deadline ? 1 : 0, queuedResponses.get());
			Assertions.assertEquals(1, queuedCleanups.get());

			releaseActive.countDown();
			awaitCondition(() -> execution.snapshot().activeHandlerSlots() == 0
					&& execution.snapshot().retainedExchanges() == 0);
		} finally {
			releaseActive.countDown();
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
			probeExecutor.shutdownNow();
			Assertions.assertTrue(probeExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	private static MicrohttpRequest transportRequest() {
		return new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", List.of(),
				new byte[0], false, new InetSocketAddress("127.0.0.1", 12345));
	}

	private static McpEffectiveAdmissionIdentity admissionIdentity() {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"application-execution-test", "4.0.0"))
				.build();
		return McpEffectiveAdmissionIdentity.resolve(endpoint, "/mcp",
				McpAdmissionIdentity.anonymousInstance());
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

	private static McpJsonRpcMessage.Request progressRequest(String id) {
		String json = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{},"
				+ "\"progressToken\":\"deadline-token\"},"
				+ "\"name\":\"deadline-progress\",\"arguments\":{}}}";
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

	private static final class ActivePolicyDeadlineClock
			implements McpApplicationClock {
		private final long deadlineNanos;
		private final CountDownLatch callbackEntered;
		private final ThreadLocal<Integer> policyReads;

		private ActivePolicyDeadlineClock(long deadlineNanos,
				CountDownLatch callbackEntered) {
			this.deadlineNanos = deadlineNanos;
			this.callbackEntered = callbackEntered;
			this.policyReads = ThreadLocal.withInitial(() -> 0);
		}

		@Override
		public long nanoTime() {
			if (!insideBoundedPolicyWait())
				return 0L;
			int read = this.policyReads.get() + 1;
			this.policyReads.set(read);
			if (read == 1)
				return 0L;
			awaitLatch(this.callbackEntered);
			return this.deadlineNanos;
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

	private static final class DeferredDequeuedProbeObserver
			implements McpApplicationExecutionObserver {
		private final Object lock;
		private final ExecutorService probeExecutor;
		private final AtomicReference<Runnable> probeAction;
		private final AtomicReference<Throwable> probeFailure;
		private final CountDownLatch probeCompleted;
		private int deferralDepth;
		private boolean dequeuedPending;
		private boolean delivering;

		private DeferredDequeuedProbeObserver(ExecutorService probeExecutor) {
			this.lock = new Object();
			this.probeExecutor = probeExecutor;
			this.probeAction = new AtomicReference<>();
			this.probeFailure = new AtomicReference<>();
			this.probeCompleted = new CountDownLatch(1);
		}

		@Override
		public void beginDeferral() {
			synchronized (this.lock) {
				this.deferralDepth++;
			}
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
			synchronized (this.lock) {
				this.dequeuedPending = true;
			}
		}

		@Override
		public void recordHandlerCapacityRejected() {
		}

		@Override
		public void drain() {
			synchronized (this.lock) {
				if (this.deferralDepth != 0 || this.delivering
						|| !this.dequeuedPending)
					return;
				this.dequeuedPending = false;
				this.delivering = true;
			}

			Future<?> probe = null;
			try {
				probe = this.probeExecutor.submit(this.probeAction.get());
				probe.get(1, TimeUnit.SECONDS);
			} catch (Throwable throwable) {
				if (probe != null)
					probe.cancel(true);
				this.probeFailure.compareAndSet(null, throwable);
			} finally {
				synchronized (this.lock) {
					this.delivering = false;
				}
				this.probeCompleted.countDown();
			}
		}

		@Override
		public void endDeferral() {
			boolean drain;
			synchronized (this.lock) {
				if (this.deferralDepth == 0)
					throw new IllegalStateException("Observer deferral is not active.");
				this.deferralDepth--;
				drain = this.deferralDepth == 0;
			}
			if (drain)
				drain();
		}

		private void probeWith(Runnable probeAction) {
			this.probeAction.set(probeAction);
		}

		private void awaitProbe() throws InterruptedException {
			Assertions.assertTrue(this.probeCompleted.await(5, TimeUnit.SECONDS),
					"Dequeued observer probe did not run.");
		}

		private Throwable probeFailure() {
			return this.probeFailure.get();
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
