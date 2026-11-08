/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet.internal.mcp.protocol;

import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.MicrohttpRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(60)
public class McpQueuedDeadlineBoundaryRegressionTests {
	@Test
	public void queuedDeadlinePhysicalCompletionDoesNotHoldElectionLocks() throws Exception {
		assertQueuedPhysicalCompletionOutsideLocks(null);
	}

	@Test
	public void queuedDisconnectPhysicalCompletionDoesNotHoldElectionLocks() throws Exception {
		assertQueuedPhysicalCompletionOutsideLocks(StreamTerminationReason.CLIENT_DISCONNECTED);
	}

	@Test
	public void queuedClientCancellationPhysicalCompletionDoesNotHoldElectionLocks() throws Exception {
		// Legacy session cancellation uses this reservation before completing it
		// after releasing the transport RequestControl's lock.
		assertQueuedPhysicalCompletionOutsideLocks(StreamTerminationReason.CLIENT_CANCELED);
	}

	@Test
	public void stoppingManyQueuedRequestsDoesNotOverflowRunningCancellationSidecars() throws Exception {
		assertStopBacklog(1, 5);
		assertStopBacklog(4, 14);
	}

	@Test
	public void rejectedTicketsDoNotReserveRunningCancellationSidecars() throws Exception {
		RejectingHandlerExecutor executor = new RejectingHandlerExecutor();
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(1, 1,
						Duration.ofSeconds(30), Duration.ofDays(1)), () -> 0L,
				ignored -> executor);
		List<McpApplicationCancellationReservation> cancellations = new ArrayList<>();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger physicalExits = new AtomicInteger();
		AtomicInteger responses = new AtomicInteger();
		AtomicInteger cleanups = new AtomicInteger();
		AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();
		try {
			execution.start();
			for (int index = 0; index < 5; index++) {
				MicrohttpRequest transport = transportRequest();
				execution.dispatch(transport, request("rejected-" + index),
						Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(), invocation -> {
							handlerInvocations.incrementAndGet();
							return completed();
						}, 1_000_000L, new McpApplicationResponseWriter() {
					@Override public boolean write(McpApplicationResponse response) {
						responses.incrementAndGet();
						return true;
					}
					@Override public void didFinishPhysicalWork() {
						physicalExits.incrementAndGet();
						try {
							// Failed submission has changed the ticket to REJECTED, but
							// its failure observer has not elected a response yet.
							McpApplicationCancellationReservation cancellation = execution.tryReserveCancellation(
									transport, StreamTerminationReason.CLIENT_DISCONNECTED);
							cancellations.add(cancellation);
							Assertions.assertTrue(cancellation.registered());
							Assertions.assertTrue(cancellation.won());
						} catch (Throwable failure) {
							cancellationFailure.compareAndSet(null, failure);
						}
					}
				}, cleanups::incrementAndGet);
				Assertions.assertNull(cancellationFailure.get(),
						"A rejected ticket must not consume a running-handler cancellation sidecar.");
			}
			// Hold every elected completion. If unentered tickets reserved sidecars,
			// five requests would overflow the one worker plus one queue allowance.
			Assertions.assertEquals(5, executor.submissions.get());
			Assertions.assertEquals(5, physicalExits.get());
			Assertions.assertEquals(5, cancellations.size());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(0, responses.get());
			Assertions.assertEquals(0, cleanups.get());
			Assertions.assertEquals(0, execution.snapshot().activeHandlerSlots());
			Assertions.assertEquals(5, execution.snapshot().retainedExchanges());
			Assertions.assertEquals(5, execution.snapshot().retainedTransportLeases());
			for (McpApplicationCancellationReservation cancellation : cancellations) {
				cancellation.complete().run();
				cancellation.complete().run();
			}
			Assertions.assertEquals(5, physicalExits.get());
			Assertions.assertEquals(5, cleanups.get());
			Assertions.assertEquals(5, execution.snapshot().abandonedResponses());
			Assertions.assertEquals(0, execution.snapshot().terminalResponses());
			Assertions.assertEquals(0, execution.snapshot().retainedExchanges());
			Assertions.assertEquals(0, execution.snapshot().retainedTransportLeases());
		} finally {
			for (McpApplicationCancellationReservation cancellation : cancellations)
				cancellation.complete().run();
			execution.stop();
			boolean terminated = execution.awaitTermination(Duration.ofSeconds(5));
			// A disposable negative run can lose the failing reservation's completion
			// when its impossible sidecar overflows. Preserve that original failure.
			if (!(cancellationFailure.get() instanceof RejectedExecutionException))
				Assertions.assertTrue(terminated);
		}
	}

	private static void assertQueuedPhysicalCompletionOutsideLocks(StreamTerminationReason reason)
			throws Exception {
		for (McpProtocolProfile profile : List.of(Mcp20260728ProtocolProfile.INSTANCE,
				Mcp2025ProtocolProfile.JUNE_18, Mcp2025ProtocolProfile.NOVEMBER_25)) {
			AtomicLong now = new AtomicLong();
			McpApplicationExecution execution = new McpApplicationExecution(
					new McpApplicationExecutionConfiguration(1, 2,
							Duration.ofSeconds(30), Duration.ofDays(1)), now::get);
			CountDownLatch activeEntered = new CountDownLatch(1);
			CountDownLatch releaseActive = new CountDownLatch(1);
			CountDownLatch physicalExitEntered = new CountDownLatch(1);
			CountDownLatch releasePhysicalExit = new CountDownLatch(1);
			CountDownLatch otherReserved = new CountDownLatch(1);
			AtomicInteger queuedInvocations = new AtomicInteger();
			AtomicInteger physicalExits = new AtomicInteger();
			AtomicInteger responseCleanups = new AtomicInteger();
			AtomicInteger queuedResponses = new AtomicInteger();
			AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
			AtomicReference<Throwable> triggerFailure = new AtomicReference<>();
			AtomicReference<Throwable> otherFailure = new AtomicReference<>();
			AtomicReference<Object> terminalLock = new AtomicReference<>();
			Object boundaryLock = field(execution, "executionBoundaryLock");
			MicrohttpRequest queued = transportRequest();
			MicrohttpRequest other = transportRequest();
			Thread trigger = new Thread(() -> {
				try {
					if (reason == null) {
						now.set(100L);
						execution.runTimerCycle();
					} else {
						McpApplicationCancellationReservation reservation =
								execution.tryReserveCancellation(queued, reason);
						if (!reservation.registered() || !reservation.won())
							throw new AssertionError("Queued cancellation did not win.");
						reservation.complete().run();
						reservation.complete().run(); // Winner completion is idempotent.
					}
				} catch (Throwable failure) { triggerFailure.set(failure); }
			}, "mcp-queued-terminal-trigger");
			Thread transport = new Thread(() -> {
				try {
					McpApplicationCancellationReservation reservation = execution.tryReserveCancellation(
							other, StreamTerminationReason.CLIENT_DISCONNECTED);
					if (!reservation.registered() || !reservation.won())
						throw new AssertionError("Unrelated queued cancellation did not win.");
					reservation.complete().run();
					otherReserved.countDown();
				} catch (Throwable failure) { otherFailure.set(failure); }
			}, "mcp-unrelated-transport-cancel");
			try {
				execution.start();
				execution.dispatch(transportRequest(), request("active"), profile, admissionIdentity(),
						invocation -> {
							activeEntered.countDown();
							awaitRelease(releaseActive);
							return completed();
						}, 1_000_000L, response -> true, () -> {});
				Assertions.assertTrue(activeEntered.await(2, TimeUnit.SECONDS));
				execution.dispatch(queued, request("queued"), profile, admissionIdentity(), invocation -> {
					queuedInvocations.incrementAndGet();
					return completed();
				}, reason == null ? 100L : 1_000_000L, new McpApplicationResponseWriter() {
					@Override public boolean write(McpApplicationResponse response) {
						queuedResponses.incrementAndGet();
						return true;
					}
					@Override public void didFinishPhysicalWork() {
						physicalExits.incrementAndGet();
						if (Thread.holdsLock(boundaryLock) || Thread.holdsLock(terminalLock.get()))
							callbackFailure.compareAndSet(null,
									new AssertionError("Physical completion retained an election monitor."));
						physicalExitEntered.countDown();
						awaitRelease(releasePhysicalExit);
					}
				}, responseCleanups::incrementAndGet);
				Map<?, ?> exchanges = (Map<?, ?>) field(execution, "requestsByIdentity");
				terminalLock.set(field(exchanges.get(queued), "terminalLock"));
				execution.dispatch(other, request("other"), profile, admissionIdentity(), invocation -> {
					queuedInvocations.incrementAndGet();
					return completed();
				}, 1_000_000L, response -> true, () -> {});
				trigger.start();
				Assertions.assertTrue(physicalExitEntered.await(2, TimeUnit.SECONDS));
				transport.start();
				Assertions.assertTrue(otherReserved.await(2, TimeUnit.SECONDS),
						"A physical-exit callback must not block unrelated transport cancellation.");
				Assertions.assertNull(callbackFailure.get());
				Assertions.assertEquals(0, queuedInvocations.get());
			} finally {
				releasePhysicalExit.countDown();
				releaseActive.countDown();
				trigger.join(2_000L);
				transport.join(2_000L);
				execution.stop();
				Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
				Assertions.assertFalse(trigger.isAlive());
				Assertions.assertFalse(transport.isAlive());
			}
			Assertions.assertNull(triggerFailure.get());
			Assertions.assertNull(otherFailure.get());
			Assertions.assertEquals(1, physicalExits.get());
			Assertions.assertEquals(1, responseCleanups.get());
			Assertions.assertEquals(reason == null ? 1 : 0, queuedResponses.get());
			Assertions.assertEquals(0, execution.snapshot().activeHandlerSlots());
			Assertions.assertEquals(0, execution.snapshot().retainedExchanges());
			Assertions.assertEquals(0, execution.snapshot().retainedTransportLeases());
		}
	}

	private static void assertStopBacklog(int concurrency, int queueCapacity) throws Exception {
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(concurrency, queueCapacity,
						Duration.ofSeconds(30), Duration.ofDays(1)), () -> 0L);
		CountDownLatch activeEntered = new CountDownLatch(concurrency);
		CountDownLatch releaseActive = new CountDownLatch(1);
		AtomicInteger activeCallbacks = new AtomicInteger();
		AtomicInteger queuedInvocations = new AtomicInteger();
		AtomicInteger physicalExits = new AtomicInteger();
		AtomicInteger cleanups = new AtomicInteger();
		AtomicReference<Throwable> stopFailure = new AtomicReference<>();
		Thread stop = new Thread(() -> {
			try { execution.stop(); } catch (Throwable failure) { stopFailure.set(failure); }
		}, "mcp-backlog-stop");
		try {
			execution.start();
			for (int index = 0; index < concurrency; index++)
				execution.dispatch(transportRequest(), request("active-" + index),
						Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(), invocation -> {
							invocation.cancelationToken().onCancel(activeCallbacks::incrementAndGet);
							activeEntered.countDown();
							awaitRelease(releaseActive);
							return completed();
						}, 1_000_000L, writer(physicalExits), cleanups::incrementAndGet);
			Assertions.assertTrue(activeEntered.await(2, TimeUnit.SECONDS));
			for (int index = 0; index < queueCapacity; index++)
				execution.dispatch(transportRequest(), request("queued-" + index),
						Mcp20260728ProtocolProfile.INSTANCE, admissionIdentity(), invocation -> {
							queuedInvocations.incrementAndGet();
							return completed();
						}, 1_000_000L, writer(physicalExits), cleanups::incrementAndGet);
			Assertions.assertEquals(queueCapacity, execution.snapshot().queuedRequests());
			stop.start();
			stop.join(2_000L);
			Assertions.assertFalse(stop.isAlive(), "Stop must not wait for physical handlers.");
			Assertions.assertNull(stopFailure.get(), "Never-entered tickets must not exhaust active sidecars.");
			Assertions.assertEquals(0, queuedInvocations.get());
			Assertions.assertEquals(queueCapacity, physicalExits.get());
			Assertions.assertEquals(concurrency + queueCapacity, cleanups.get());
			Assertions.assertEquals(concurrency, execution.snapshot().activeHandlerSlots());
			Assertions.assertEquals(concurrency, execution.snapshot().retainedExchanges());
			Assertions.assertFalse(execution.awaitTermination(Duration.ofMillis(20)),
					"Physically blocked active handlers must remain retained.");
		} finally {
			releaseActive.countDown();
			stop.join(2_000L);
			execution.stop();
			boolean terminated = execution.awaitTermination(Duration.ofSeconds(5));
			// A baseline-red run with the old overflow is confined to a disposable
			// JVM; do not mask its original RejectedExecutionException assertion.
			if (stopFailure.get() == null) Assertions.assertTrue(terminated);
		}
		Assertions.assertEquals(concurrency, activeCallbacks.get());
		Assertions.assertEquals(concurrency + queueCapacity, physicalExits.get());
		Assertions.assertEquals(0, execution.snapshot().activeHandlerSlots());
		Assertions.assertEquals(0, execution.snapshot().retainedExchanges());
		Assertions.assertEquals(0, execution.snapshot().retainedTransportLeases());
	}

	private static McpApplicationResponseWriter writer(AtomicInteger physicalExits) {
		return new McpApplicationResponseWriter() {
			@Override public boolean write(McpApplicationResponse response) { return true; }
			@Override public void didFinishPhysicalWork() { physicalExits.incrementAndGet(); }
		};
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static void awaitRelease(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			while (true) {
				try {
					if (!latch.await(10, TimeUnit.SECONDS))
						throw new AssertionError("Bounded fixture release expired.");
					return;
				} catch (InterruptedException expected) { interrupted = true; }
			}
		} finally { if (interrupted) Thread.currentThread().interrupt(); }
	}

	private static McpApplicationDispatchOutcome completed() {
		return McpApplicationDispatchOutcome.completed(McpWireResult.complete(McpJsonObject.empty()));
	}

	private static MicrohttpRequest transportRequest() {
		return new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", List.of(), new byte[0], false,
				new InetSocketAddress("127.0.0.1", 12345));
	}

	private static McpEffectiveAdmissionIdentity admissionIdentity() {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("queued-boundary-test", "1.0.0")).build();
		return McpEffectiveAdmissionIdentity.resolve(endpoint, "/mcp", McpAdmissionIdentity.anonymousInstance());
	}

	private static McpJsonRpcMessage.Request request(String id) {
		String json = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"test/execute\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		McpJsonLimits limits = McpJsonLimits.productionDefaults();
		McpJsonRpcEnvelope envelope = new McpJsonRpcEnvelopeCodec(new McpJsonCodec(limits))
				.decode(json.getBytes(StandardCharsets.UTF_8));
		return new McpRequestWireMapper(limits).map((McpJsonRpcEnvelope.Request) envelope);
	}

	private static final class RejectingHandlerExecutor extends AbstractExecutorService {
		private final AtomicInteger submissions = new AtomicInteger();
		private volatile boolean shutdown;

		@Override public void execute(Runnable command) {
			submissions.incrementAndGet();
			throw new RejectedExecutionException("Synthetic handler submission rejection.");
		}
		@Override public void shutdown() { shutdown = true; }
		@Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
		@Override public boolean isShutdown() { return shutdown; }
		@Override public boolean isTerminated() { return shutdown; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
	}
}
