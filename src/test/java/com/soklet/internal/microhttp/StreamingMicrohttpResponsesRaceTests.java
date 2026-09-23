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

package com.soklet.internal.microhttp;

import com.soklet.HttpMethod;
import com.soklet.CancelationToken;
import com.soklet.Request;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseCanceledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public class StreamingMicrohttpResponsesRaceTests {
	@AfterEach
	public void resetTestHooks() {
		StreamingMicrohttpResponses.setTestHooks(null);
	}

	@Test
	public void timeout_reserved_after_terminal_chunk_write_does_not_report_normal_completion() throws Exception {
		AtomicInteger timeoutInjections = new AtomicInteger();
		AtomicReference<StreamTerminationReason> callbackReasonRef = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> terminationReasonRef = new AtomicReference<>();
		AtomicReference<Throwable> terminationThrowableRef = new AtomicReference<>();
		AtomicReference<Throwable> callbackFailureRef = new AtomicReference<>();
		CountDownLatch terminatedLatch = new CountDownLatch(1);

		StreamingMicrohttpResponses.setTestHooks(new StreamingMicrohttpResponses.TestHooks() {
			@Override
			public void beforeTerminalCompletion(Runnable failWithResponseTimeout) {
				if (timeoutInjections.compareAndSet(0, 1))
					failWithResponseTimeout.run();
			}
		});

		ExerciseResult result = exerciseTerminalWriteRace(callbackReasonRef, terminationReasonRef, terminationThrowableRef,
				callbackFailureRef, terminatedLatch);

		Assertions.assertEquals(1, timeoutInjections.get(), "Timeout was not injected at the terminal write boundary");
		Assertions.assertInstanceOf(StreamingResponseCanceledException.class, result.getFailure());
		Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT,
				((StreamingResponseCanceledException) result.getFailure()).getCancelationReason());
		Assertions.assertTrue(terminatedLatch.await(2, TimeUnit.SECONDS), "Stream termination lifecycle hook was not invoked");
		Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, terminationReasonRef.get());
		Assertions.assertNull(terminationThrowableRef.get());
		Assertions.assertNull(callbackReasonRef.get(),
				"A delivery timeout must not cancel a producer that already completed normally");
		Assertions.assertNull(callbackFailureRef.get());
	}

	@Test
	public void close_after_failure_reservation_does_not_overwrite_cancelation_reason() throws Exception {
		AtomicInteger timeoutInjections = new AtomicInteger();
		AtomicInteger closeInjections = new AtomicInteger();
		AtomicReference<StreamTerminationReason> callbackReasonRef = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> terminationReasonRef = new AtomicReference<>();
		AtomicReference<Throwable> terminationThrowableRef = new AtomicReference<>();
		AtomicReference<Throwable> callbackFailureRef = new AtomicReference<>();
		CountDownLatch terminatedLatch = new CountDownLatch(1);

		StreamingMicrohttpResponses.setTestHooks(new StreamingMicrohttpResponses.TestHooks() {
			@Override
			public void beforeTerminalCompletion(Runnable failWithResponseTimeout) {
				if (timeoutInjections.compareAndSet(0, 1))
					failWithResponseTimeout.run();
			}

			@Override
			public void afterFailureReserved(Runnable closeAsClientDisconnected) {
				if (closeInjections.compareAndSet(0, 1))
					closeAsClientDisconnected.run();
			}
		});

		ExerciseResult result = exerciseTerminalWriteRace(callbackReasonRef, terminationReasonRef, terminationThrowableRef,
				callbackFailureRef, terminatedLatch);

		Assertions.assertEquals(1, timeoutInjections.get(), "Timeout was not injected at the terminal write boundary");
		Assertions.assertEquals(1, closeInjections.get(), "Close was not injected after failure reservation");
		Assertions.assertInstanceOf(StreamingResponseCanceledException.class, result.getFailure());
		Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT,
				((StreamingResponseCanceledException) result.getFailure()).getCancelationReason());
		Assertions.assertTrue(terminatedLatch.await(2, TimeUnit.SECONDS), "Stream termination lifecycle hook was not invoked");
		Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, terminationReasonRef.get());
		Assertions.assertNull(terminationThrowableRef.get());
		Assertions.assertNull(callbackReasonRef.get(),
				"A delivery timeout must not cancel a producer that already completed normally");
		Assertions.assertNull(callbackFailureRef.get());
	}

	@Test
	public void executor_shutdown_interrupt_wrapped_by_source_reports_server_stopping() throws Exception {
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutorService = Executors.newSingleThreadScheduledExecutor();
		AtomicReference<StreamTerminationReason> terminationReasonRef = new AtomicReference<>();
		AtomicReference<Throwable> terminationThrowableRef = new AtomicReference<>();
		AtomicBoolean forcedShutdownStarted = new AtomicBoolean();
		CountDownLatch readEnteredLatch = new CountDownLatch(1);
		CountDownLatch terminatedLatch = new CountDownLatch(1);
		WritableSource source = null;

		try {
			StreamingResponseBody body = StreamingResponseBody.fromInputStream(() -> new InputStream() {
				@Override
				public int read() throws IOException {
					return read(new byte[1], 0, 1);
				}

				@Override
				public int read(byte[] bytes, int offset, int length) throws IOException {
					readEnteredLatch.countDown();
					try {
						new CountDownLatch(1).await();
					} catch (InterruptedException e) {
						// Deliberately erase the interrupt shape. The explicit transport
						// force boundary, not application exception inspection, owns the reason.
						throw new IOException("Source read stopped");
					}
					return -1;
				}
			});
			source = newStreamingSource(body, executorService, timeoutExecutorService,
					terminationReasonRef, terminationThrowableRef, terminatedLatch,
					forcedShutdownStarted::get);
			source.start();
			Assertions.assertTrue(readEnteredLatch.await(2, TimeUnit.SECONDS),
					"Streaming source did not enter its blocking read");

			forcedShutdownStarted.set(true);
			executorService.shutdownNow();

			Assertions.assertTrue(terminatedLatch.await(2, TimeUnit.SECONDS),
					"Stream termination lifecycle hook was not invoked");
			Assertions.assertEquals(StreamTerminationReason.SERVER_STOPPING,
					terminationReasonRef.get());
			Assertions.assertNull(terminationThrowableRef.get());
		} finally {
			if (source != null)
				source.close(StreamTerminationReason.SERVER_STOPPING, null);
			executorService.shutdownNow();
			timeoutExecutorService.shutdownNow();
		}
	}

	@Test
	public void producer_failure_during_graceful_executor_shutdown_remains_producer_failed() throws Exception {
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutorService = Executors.newSingleThreadScheduledExecutor();
		AtomicReference<StreamTerminationReason> terminationReasonRef = new AtomicReference<>();
		AtomicReference<Throwable> terminationThrowableRef = new AtomicReference<>();
		CountDownLatch producerEnteredLatch = new CountDownLatch(1);
		CountDownLatch releaseProducerLatch = new CountDownLatch(1);
		CountDownLatch terminatedLatch = new CountDownLatch(1);
		WritableSource source = null;

		try {
			StreamingResponseBody body = StreamingResponseBody.fromWriter(responseStream -> {
				producerEnteredLatch.countDown();
				if (!releaseProducerLatch.await(2, TimeUnit.SECONDS))
					throw new IllegalStateException("Producer was not released");
				throw new IOException("Expected producer failure");
			});
			source = newStreamingSource(body, executorService, timeoutExecutorService,
					terminationReasonRef, terminationThrowableRef, terminatedLatch);
			source.start();
			Assertions.assertTrue(producerEnteredLatch.await(2, TimeUnit.SECONDS),
					"Streaming producer did not start");

			executorService.shutdown();
			releaseProducerLatch.countDown();

			Assertions.assertTrue(terminatedLatch.await(2, TimeUnit.SECONDS),
					"Stream termination lifecycle hook was not invoked");
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED,
					terminationReasonRef.get());
			Assertions.assertInstanceOf(IOException.class, terminationThrowableRef.get());
		} finally {
			releaseProducerLatch.countDown();
			if (source != null)
				source.close();
			executorService.shutdownNow();
			timeoutExecutorService.shutdownNow();
		}
	}

	@Test
	public void producer_failure_rethrows_reasoned_cancelation_with_original_cause() throws Exception {
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutorService = Executors.newSingleThreadScheduledExecutor();
		AtomicReference<StreamTerminationReason> terminationReasonRef = new AtomicReference<>();
		AtomicReference<Throwable> terminationThrowableRef = new AtomicReference<>();
		CountDownLatch terminatedLatch = new CountDownLatch(1);
		IOException producerFailure = new IOException("Expected producer failure");
		WritableSource source = null;

		try (RecordingSocketChannel socketChannel = new RecordingSocketChannel()) {
			StreamingResponseBody body = StreamingResponseBody.fromWriter(responseStream -> {
				throw producerFailure;
			});
			source = newStreamingSource(body, executorService, timeoutExecutorService,
					terminationReasonRef, terminationThrowableRef, terminatedLatch);
			source.start();

			IOException failure = writeUntilIOException(source, socketChannel);

			StreamingResponseCanceledException canceled = Assertions.assertInstanceOf(
					StreamingResponseCanceledException.class, failure);
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED,
					canceled.getCancelationReason());
			Assertions.assertSame(producerFailure,
					canceled.getCancelationCause().orElseThrow());
			Assertions.assertTrue(terminatedLatch.await(2, TimeUnit.SECONDS),
					"Stream termination lifecycle hook was not invoked");
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED,
					terminationReasonRef.get());
			Assertions.assertSame(producerFailure, terminationThrowableRef.get());
		} finally {
			if (source != null)
				source.close();
			executorService.shutdownNow();
			timeoutExecutorService.shutdownNow();
		}
	}

	@Test
	public void supervised_cancel_interrupts_producer_before_dispatch_and_retains_running_callback() throws Exception {
		ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
		ExecutorService signalExecutor = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(30), ignored -> {});
		CountDownLatch producerEntered = new CountDownLatch(1);
		CountDownLatch producerInterrupted = new CountDownLatch(1);
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch callbackRelease = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> callbackReason = new AtomicReference<>();
		AtomicReference<String> callbackThread = new AtomicReference<>();
		WritableSource source = null;
		try {
			StreamLifecycleCoordinator.Reservation reservation = coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			source = newStreamingSource(StreamingResponseBody.fromWriter(responseStream -> {
				responseStream.getCancelationToken().onCancel(() -> {
					callbackThread.set(Thread.currentThread().getName());
					callbackReason.set(responseStream.getCancelationToken().getCancelationReason().orElse(null));
					callbackEntered.countDown();
					awaitUninterruptibly(callbackRelease);
				});
				producerEntered.countDown();
				try {
					new CountDownLatch(1).await();
				} catch (InterruptedException interruptedException) {
					Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT,
							responseStream.getCancelationToken().getCancelationReason().orElse(null));
					producerInterrupted.countDown();
					throw interruptedException;
				}
			}), producerExecutor, timeoutExecutor, new AtomicReference<>(), new AtomicReference<>(), terminated,
					() -> false, ignored -> {}, reservation);
			source.start();
			Assertions.assertTrue(producerEntered.await(2, TimeUnit.SECONDS));
			WritableSource admittedSource = source;
			signalExecutor.submit(() -> {
				admittedSource.close(StreamTerminationReason.RESPONSE_TIMEOUT, null);
				return null;
			})
					.get(2, TimeUnit.SECONDS);
			Assertions.assertTrue(producerInterrupted.await(2, TimeUnit.SECONDS));
			Assertions.assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
			Assertions.assertTrue(callbackThread.get().contains("stream-callback"));
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, callbackReason.get());
			producerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
			Assertions.assertEquals(1, coordinator.snapshot().reservations(),
					"Callback physical exit, not producer Future completion, controls reservation retirement");
			Assertions.assertNull(coordinator.tryReserve());
			callbackRelease.countDown();
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
		} finally {
			callbackRelease.countDown();
			if (source != null) source.close();
			coordinator.force();
			producerExecutor.shutdownNow();
			signalExecutor.shutdownNow();
			timeoutExecutor.shutdownNow();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	@Test
	public void token_polling_reports_reserved_outcome_before_failure_is_applied() throws Exception {
		ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
		ExecutorService signalExecutor = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(30), ignored -> {});
		AtomicReference<CancelationToken> token = new AtomicReference<>();
		CountDownLatch producerEntered = new CountDownLatch(1);
		CountDownLatch releaseProducer = new CountDownLatch(1);
		CountDownLatch failureReserved = new CountDownLatch(1);
		CountDownLatch applyFailure = new CountDownLatch(1);
		CountDownLatch callbackInvoked = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		IOException failure = new IOException("Reserved transport failure");
		WritableSource source = null;
		try {
			StreamLifecycleCoordinator.Reservation reservation = coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			source = newStreamingSource(StreamingResponseBody.fromWriter(responseStream -> {
				token.set(responseStream.getCancelationToken());
				responseStream.getCancelationToken().onCancel(callbackInvoked::countDown);
				producerEntered.countDown();
				awaitUninterruptibly(releaseProducer);
			}), producerExecutor, timeoutExecutor, new AtomicReference<>(), new AtomicReference<>(), terminated,
					() -> false, ignored -> {}, reservation);
			source.start();
			Assertions.assertTrue(producerEntered.await(2, TimeUnit.SECONDS));
			StreamingMicrohttpResponses.setTestHooks(new StreamingMicrohttpResponses.TestHooks() {
				@Override public void beforeFailureApplied() {
					failureReserved.countDown();
					awaitUninterruptibly(applyFailure);
				}
			});
			WritableSource admittedSource = source;
			var signal = signalExecutor.submit(() -> {
				admittedSource.close(StreamTerminationReason.WRITE_FAILED, failure);
				return null;
			});
			Assertions.assertTrue(failureReserved.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(1L, callbackInvoked.getCount(), "Token callback publication is still paused");
			Assertions.assertFalse(reservation.completeProduction(), "The reserved cancelation already owns the outcome");
			Assertions.assertFalse(reservation.cancel(StreamTerminationReason.SERVER_STOPPING, new IOException("Later failure")));
			for (int repeat = 0; repeat < 3; ++repeat) {
				Assertions.assertTrue(token.get().isCanceled());
				Assertions.assertEquals(StreamTerminationReason.WRITE_FAILED, token.get().getCancelationReason().orElseThrow());
				Assertions.assertSame(failure, token.get().getCancelationCause().orElseThrow());
				StreamingResponseCanceledException canceled = Assertions.assertThrows(
						StreamingResponseCanceledException.class, token.get()::throwIfCanceled);
				Assertions.assertEquals(StreamTerminationReason.WRITE_FAILED, canceled.getCancelationReason());
				Assertions.assertSame(failure, canceled.getCancelationCause().orElseThrow());
			}
			applyFailure.countDown();
			signal.get(2, TimeUnit.SECONDS);
			Assertions.assertTrue(callbackInvoked.await(2, TimeUnit.SECONDS));
			releaseProducer.countDown();
			producerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
		} finally {
			applyFailure.countDown();
			releaseProducer.countDown();
			StreamingMicrohttpResponses.setTestHooks(null);
			if (source != null) source.close();
			coordinator.force();
			producerExecutor.shutdownNow();
			signalExecutor.shutdownNow();
			timeoutExecutor.shutdownNow();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	@Test
	public void normal_producer_completion_releases_callbacks_and_late_registrations_are_inert() throws Exception {
		ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(30), ignored -> {});
		AtomicReference<CancelationToken> token = new AtomicReference<>();
		AtomicInteger callbacks = new AtomicInteger();
		CountDownLatch terminated = new CountDownLatch(1);
		WritableSource source = null;
		try {
			source = newStreamingSource(StreamingResponseBody.fromWriter(responseStream -> {
				token.set(responseStream.getCancelationToken());
				responseStream.getCancelationToken().onCancel(callbacks::incrementAndGet);
				responseStream.write(new byte[]{1});
			}), producerExecutor, timeoutExecutor, new AtomicReference<>(), new AtomicReference<>(), terminated,
					() -> false, ignored -> {}, coordinator.tryReserve());
			source.start();
			producerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
			Assertions.assertNotNull(token.get());
			token.get().onCancel(callbacks::incrementAndGet).close();
			source.close(StreamTerminationReason.WRITE_FAILED, new IOException("Late delivery failure"));
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			Assertions.assertFalse(token.get().isCanceled());
			Assertions.assertTrue(token.get().getCancelationReason().isEmpty());
			Assertions.assertTrue(token.get().getCancelationCause().isEmpty());
			Assertions.assertDoesNotThrow(token.get()::throwIfCanceled);
			Assertions.assertEquals(0, callbacks.get());
		} finally {
			if (source != null) source.close();
			coordinator.force();
			producerExecutor.shutdownNow();
			timeoutExecutor.shutdownNow();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	@Test
	public void supervised_prestart_discard_never_enters_lazy_application_factory() throws Exception {
		ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(30), ignored -> {});
		AtomicInteger acquisitions = new AtomicInteger();
		CountDownLatch terminated = new CountDownLatch(1);
		try {
			WritableSource source = newStreamingSource(StreamingResponseBody.fromInputStream(() -> {
				acquisitions.incrementAndGet();
				return InputStream.nullInputStream();
			}), producerExecutor, timeoutExecutor, new AtomicReference<>(), new AtomicReference<>(), terminated,
					() -> false, ignored -> {}, coordinator.tryReserve());
			source.close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
			source.close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
			source.start();
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			producerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
			Assertions.assertEquals(0, acquisitions.get());
		} finally {
			coordinator.force();
			producerExecutor.shutdownNow();
			timeoutExecutor.shutdownNow();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	@Test
	public void duplicate_close_while_reserved_cancelation_hook_is_pending_still_retires() throws Exception {
		ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
		ExecutorService signalExecutor = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(30), ignored -> {});
		CountDownLatch failureReserved = new CountDownLatch(1);
		CountDownLatch applyFailure = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> terminationReason = new AtomicReference<>();
		AtomicInteger acquisitions = new AtomicInteger();
		WritableSource source = null;
		try {
			StreamLifecycleCoordinator.Reservation reservation = coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			source = newStreamingSource(StreamingResponseBody.fromInputStream(() -> {
				acquisitions.incrementAndGet();
				return InputStream.nullInputStream();
			}), producerExecutor, timeoutExecutor, terminationReason, new AtomicReference<>(), terminated,
					() -> false, ignored -> {}, reservation);
			StreamingMicrohttpResponses.setTestHooks(new StreamingMicrohttpResponses.TestHooks() {
				@Override
				public void beforeFailureApplied() {
					failureReserved.countDown();
					awaitUninterruptibly(applyFailure);
				}
			});
			var cancellation = signalExecutor.submit(() -> reservation.cancel(StreamTerminationReason.RESPONSE_TIMEOUT, null));
			Assertions.assertTrue(failureReserved.await(2, TimeUnit.SECONDS));
			source.close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
			applyFailure.countDown();
			Assertions.assertTrue(cancellation.get(2, TimeUnit.SECONDS));
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, terminationReason.get());
			Assertions.assertEquals(0, acquisitions.get());
			coordinator.stopAdmission();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
			Assertions.assertEquals(0, coordinator.snapshot().reservations());
		} finally {
			applyFailure.countDown();
			if (source != null) source.close();
			coordinator.force();
			producerExecutor.shutdownNow();
			signalExecutor.shutdownNow();
			timeoutExecutor.shutdownNow();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	@Test
	public void reserved_cancelation_wins_terminal_write_before_its_source_hook_is_applied() throws Exception {
		ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
		ExecutorService signalExecutor = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(30), ignored -> {});
		CountDownLatch failureReserved = new CountDownLatch(1);
		CountDownLatch applyFailure = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> terminationReason = new AtomicReference<>();
		IOException expectedCause = new IOException("Reserved transport failure");
		WritableSource source = null;
		try (RecordingSocketChannel socketChannel = new RecordingSocketChannel()) {
			StreamLifecycleCoordinator.Reservation reservation = coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			source = newStreamingSource(StreamingResponseBody.fromWriter(responseStream -> responseStream.write(new byte[]{1})),
					producerExecutor, timeoutExecutor, terminationReason, new AtomicReference<>(), terminated,
					() -> false, ignored -> {}, reservation);
			source.start();
			producerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
			StreamingMicrohttpResponses.setTestHooks(new StreamingMicrohttpResponses.TestHooks() {
				@Override
				public void beforeFailureApplied() {
					failureReserved.countDown();
					awaitUninterruptibly(applyFailure);
				}
			});
			var cancellation = signalExecutor.submit(() -> reservation.cancel(StreamTerminationReason.WRITE_FAILED, expectedCause));
			Assertions.assertTrue(failureReserved.await(2, TimeUnit.SECONDS));
			WritableSource admittedSource = source;
			StreamingResponseCanceledException failure = Assertions.assertThrows(StreamingResponseCanceledException.class,
					() -> admittedSource.writeTo(socketChannel, 1_024 * 1_024));
			Assertions.assertEquals(StreamTerminationReason.WRITE_FAILED, failure.getCancelationReason());
			Assertions.assertSame(expectedCause, failure.getCancelationCause().orElseThrow());
			Assertions.assertEquals(1L, terminated.getCount(), "Normal completion must not win while the cancelation hook is delayed");
			applyFailure.countDown();
			Assertions.assertTrue(cancellation.get(2, TimeUnit.SECONDS));
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.WRITE_FAILED, terminationReason.get());
		} finally {
			applyFailure.countDown();
			if (source != null) source.close();
			coordinator.force();
			producerExecutor.shutdownNow();
			signalExecutor.shutdownNow();
			timeoutExecutor.shutdownNow();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	@Test
	public void publisher_subscribe_failure_precedes_blocking_once_only_cancel_and_remains_supervised() throws Exception {
		ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
		CountDownLatch diagnostic = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofDays(1), throwable -> {
			if (throwable instanceof StreamLifecycleCoordinator.CleanupDeadlineExceededException)
				diagnostic.countDown();
		});
		CountDownLatch cancelEntered = new CountDownLatch(1);
		CountDownLatch cancelRelease = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> terminationReason = new AtomicReference<>();
		AtomicInteger cancelInvocations = new AtomicInteger();
		RuntimeException subscribeFailure = new IllegalStateException("Subscribe failed after publishing a subscription");
		WritableSource source = null;
		try (RecordingSocketChannel socketChannel = new RecordingSocketChannel()) {
			StreamLifecycleCoordinator.Reservation reservation = coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			source = newStreamingSource(StreamingResponseBody.fromPublisher(subscriber -> {
				subscriber.onSubscribe(new Flow.Subscription() {
					@Override public void request(long count) {}
					@Override public void cancel() {
						cancelInvocations.incrementAndGet();
						cancelEntered.countDown();
						awaitUninterruptibly(cancelRelease);
					}
				});
				throw subscribeFailure;
			}), producerExecutor, timeoutExecutor, terminationReason, new AtomicReference<>(), terminated,
					() -> false, ignored -> {}, reservation);
			source.start();
			Assertions.assertTrue(cancelEntered.await(2, TimeUnit.SECONDS));
			WritableSource admittedSource = source;
			StreamingResponseCanceledException failure = Assertions.assertThrows(StreamingResponseCanceledException.class,
					() -> admittedSource.writeTo(socketChannel, 1_024 * 1_024));
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, failure.getCancelationReason());
			Assertions.assertSame(subscribeFailure, failure.getCancelationCause().orElseThrow());
			reservation.checkCleanupDeadline(reservation.cleanupDeadlineNanos());
			Assertions.assertTrue(diagnostic.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(1, coordinator.snapshot().overdue());
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, reservation.reason().orElseThrow());
			Assertions.assertEquals(1, cancelInvocations.get());
			cancelRelease.countDown();
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			coordinator.stopAdmission();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, terminationReason.get());
			Assertions.assertEquals(1, cancelInvocations.get());
		} finally {
			cancelRelease.countDown();
			if (source != null) source.close();
			coordinator.force();
			producerExecutor.shutdownNow();
			timeoutExecutor.shutdownNow();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	@Test
	public void source_read_failure_precedes_blocking_close_and_keeps_its_original_cause() throws Exception {
		ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
		CountDownLatch diagnostic = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofDays(1), throwable -> {
			if (throwable instanceof StreamLifecycleCoordinator.CleanupDeadlineExceededException)
				diagnostic.countDown();
		});
		CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch closeRelease = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicInteger closeInvocations = new AtomicInteger();
		IOException readFailure = new IOException("Upstream read failed");
		WritableSource source = null;
		try (RecordingSocketChannel socketChannel = new RecordingSocketChannel()) {
			StreamLifecycleCoordinator.Reservation reservation = coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			source = newStreamingSource(StreamingResponseBody.fromInputStream(() -> new InputStream() {
				@Override public int read() throws IOException { throw readFailure; }
				@Override public void close() {
					closeInvocations.incrementAndGet();
					closeEntered.countDown();
					awaitUninterruptibly(closeRelease);
				}
			}), producerExecutor, timeoutExecutor, new AtomicReference<>(), new AtomicReference<>(), terminated,
					() -> false, ignored -> {}, reservation);
			source.start();
			Assertions.assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
			WritableSource admittedSource = source;
			StreamingResponseCanceledException failure = Assertions.assertThrows(StreamingResponseCanceledException.class,
					() -> admittedSource.writeTo(socketChannel, 1_024 * 1_024));
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, failure.getCancelationReason());
			Assertions.assertSame(readFailure, failure.getCancelationCause().orElseThrow());
			reservation.checkCleanupDeadline(reservation.cleanupDeadlineNanos());
			Assertions.assertTrue(diagnostic.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(1, coordinator.snapshot().overdue());
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, reservation.reason().orElseThrow());
			Assertions.assertSame(readFailure, reservation.cause().orElseThrow());
			closeRelease.countDown();
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			coordinator.stopAdmission();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
			Assertions.assertEquals(1, closeInvocations.get());
		} finally {
			closeRelease.countDown();
			if (source != null) source.close();
			coordinator.force();
			producerExecutor.shutdownNow();
			timeoutExecutor.shutdownNow();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	@Test
	public void canceled_source_close_failure_is_reported_without_replacing_cancelation() throws Exception {
		ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
		CountDownLatch diagnostic = new CountDownLatch(1);
		AtomicReference<Throwable> diagnosticFailure = new AtomicReference<>();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofDays(1), throwable -> {
			diagnosticFailure.set(throwable);
			diagnostic.countDown();
		});
		CountDownLatch readEntered = new CountDownLatch(1);
		CountDownLatch readRelease = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicInteger closeInvocations = new AtomicInteger();
		IOException closeFailure = new IOException("Upstream close failed");
		AtomicReference<StreamTerminationReason> terminationReason = new AtomicReference<>();
		WritableSource source = null;
		try {
			StreamLifecycleCoordinator.Reservation reservation = coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			source = newStreamingSource(StreamingResponseBody.fromInputStream(() -> new InputStream() {
				@Override public int read() {
					readEntered.countDown();
					awaitUninterruptibly(readRelease);
					return -1;
				}
				@Override public void close() throws IOException {
					closeInvocations.incrementAndGet();
					readRelease.countDown();
					throw closeFailure;
				}
			}), producerExecutor, timeoutExecutor, terminationReason, new AtomicReference<>(), terminated,
					() -> false, ignored -> {}, reservation);
			source.start();
			Assertions.assertTrue(readEntered.await(2, TimeUnit.SECONDS));
			source.close(StreamTerminationReason.RESPONSE_TIMEOUT, null);
			Assertions.assertTrue(diagnostic.await(2, TimeUnit.SECONDS));
			Assertions.assertSame(closeFailure, diagnosticFailure.get());
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, reservation.reason().orElseThrow());
			Assertions.assertTrue(reservation.cause().isEmpty());
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			coordinator.stopAdmission();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, terminationReason.get());
			Assertions.assertEquals(1, closeInvocations.get());
		} finally {
			readRelease.countDown();
			if (source != null) source.close();
			coordinator.force();
			producerExecutor.shutdownNow();
			timeoutExecutor.shutdownNow();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try { latch.await(); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private ExerciseResult exerciseTerminalWriteRace(AtomicReference<StreamTerminationReason> callbackReasonRef,
																									AtomicReference<StreamTerminationReason> terminationReasonRef,
																									AtomicReference<Throwable> terminationThrowableRef,
																									AtomicReference<Throwable> callbackFailureRef,
																									CountDownLatch terminatedLatch) throws Exception {
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timeoutExecutorService = Executors.newSingleThreadScheduledExecutor();
		WritableSource source = null;

		try (RecordingSocketChannel socketChannel = new RecordingSocketChannel()) {
			source = newStreamingSource(executorService, timeoutExecutorService, callbackReasonRef, terminationReasonRef,
					terminationThrowableRef, callbackFailureRef, terminatedLatch);
			source.start();
			return new ExerciseResult(writeUntilIOException(source, socketChannel));
		} finally {
			if (source != null)
				source.close();

			executorService.shutdownNow();
			timeoutExecutorService.shutdownNow();
		}
	}

	private WritableSource newStreamingSource(ExecutorService executorService,
																						ScheduledExecutorService timeoutExecutorService,
																						AtomicReference<StreamTerminationReason> callbackReasonRef,
																						AtomicReference<StreamTerminationReason> terminationReasonRef,
																						AtomicReference<Throwable> terminationThrowableRef,
																						AtomicReference<Throwable> callbackFailureRef,
																						CountDownLatch terminatedLatch) throws IOException {
		StreamingResponseBody body = StreamingResponseBody.fromWriter(responseStream -> {
			responseStream.getCancelationToken().onCancel(() -> callbackReasonRef.set(responseStream.getCancelationToken().getCancelationReason().orElse(null)));
			responseStream.write("ok".getBytes(StandardCharsets.UTF_8));
		});
		return newStreamingSource(body, executorService, timeoutExecutorService,
				terminationReasonRef, terminationThrowableRef, terminatedLatch,
				() -> false,
				callbackFailureRef::set);
	}

	private WritableSource newStreamingSource(StreamingResponseBody body,
													ExecutorService executorService,
													ScheduledExecutorService timeoutExecutorService,
													AtomicReference<StreamTerminationReason> terminationReasonRef,
													AtomicReference<Throwable> terminationThrowableRef,
													CountDownLatch terminatedLatch) throws IOException {
		return newStreamingSource(body, executorService, timeoutExecutorService,
				terminationReasonRef, terminationThrowableRef, terminatedLatch,
				() -> false,
				throwable -> {
					throw new AssertionError("Unexpected cancelation callback failure", throwable);
				});
	}

	private WritableSource newStreamingSource(StreamingResponseBody body,
													ExecutorService executorService,
													ScheduledExecutorService timeoutExecutorService,
													AtomicReference<StreamTerminationReason> terminationReasonRef,
													AtomicReference<Throwable> terminationThrowableRef,
													CountDownLatch terminatedLatch,
													BooleanSupplier forcedShutdownStarted) throws IOException {
		return newStreamingSource(body, executorService, timeoutExecutorService,
				terminationReasonRef, terminationThrowableRef, terminatedLatch,
				forcedShutdownStarted,
				throwable -> {
					throw new AssertionError("Unexpected cancelation callback failure", throwable);
				});
	}

	private WritableSource newStreamingSource(StreamingResponseBody body,
													ExecutorService executorService,
													ScheduledExecutorService timeoutExecutorService,
													AtomicReference<StreamTerminationReason> terminationReasonRef,
													AtomicReference<Throwable> terminationThrowableRef,
													CountDownLatch terminatedLatch,
													BooleanSupplier forcedShutdownStarted,
													Consumer<Throwable> callbackFailureConsumer) throws IOException {
		return newStreamingSource(body, executorService, timeoutExecutorService, terminationReasonRef,
				terminationThrowableRef, terminatedLatch, forcedShutdownStarted, callbackFailureConsumer, null);
	}

	private WritableSource newStreamingSource(StreamingResponseBody body,
			ExecutorService executorService, ScheduledExecutorService timeoutExecutorService,
			AtomicReference<StreamTerminationReason> terminationReasonRef,
			AtomicReference<Throwable> terminationThrowableRef, CountDownLatch terminatedLatch,
			BooleanSupplier forcedShutdownStarted, Consumer<Throwable> callbackFailureConsumer,
			StreamLifecycleCoordinator.Reservation reservation) throws IOException {
		MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(
				200,
				"OK",
				List.of(new Header("Transfer-Encoding", "chunked")),
				Request.withPath(HttpMethod.GET, "/stream").build(),
				body,
				executorService,
				timeoutExecutorService,
				1_024,
				1_024,
				null,
				null,
				forcedShutdownStarted,
				(establishedAt, streamDuration, cancelationReason, throwable) -> {
					terminationReasonRef.set(cancelationReason);
					terminationThrowableRef.set(throwable);
					terminatedLatch.countDown();
				},
				callbackFailureConsumer,
				reservation);

		return response.writableSource(response.serializeHead("HTTP/1.1", List.of()));
	}

	private IOException writeUntilIOException(WritableSource source, SocketChannel socketChannel) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

		while (System.nanoTime() < deadline) {
			try {
				source.writeTo(socketChannel, 1_024 * 1_024);
			} catch (IOException e) {
				return e;
			}

			Thread.sleep(10L);
		}

		Assertions.fail("Timed out waiting for streaming source to fail");
		return null;
	}

	private record ExerciseResult(IOException getFailure) {}

	private static final class RecordingSocketChannel extends SocketChannel {
		private final ByteArrayOutputStream outputStream;

		private RecordingSocketChannel() {
			super(SelectorProvider.provider());
			this.outputStream = new ByteArrayOutputStream();
		}

		@Override
		public int read(ByteBuffer dst) {
			return -1;
		}

		@Override
		public long read(ByteBuffer[] dsts, int offset, int length) {
			return -1L;
		}

		@Override
		public int write(ByteBuffer src) {
			int remaining = src.remaining();
			byte[] bytes = new byte[remaining];
			src.get(bytes);
			this.outputStream.writeBytes(bytes);
			return remaining;
		}

		@Override
		public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
			long written = 0L;

			for (int i = offset; i < offset + length; ++i)
				written += write(srcs[i]);

			return written;
		}

		@Override
		public SocketChannel bind(SocketAddress local) {
			return this;
		}

		@Override
		public <T> SocketChannel setOption(SocketOption<T> name, T value) {
			return this;
		}

		@Override
		public SocketChannel shutdownInput() {
			return this;
		}

		@Override
		public SocketChannel shutdownOutput() {
			return this;
		}

		@Override
		public Socket socket() {
			return new Socket();
		}

		@Override
		public boolean isConnected() {
			return true;
		}

		@Override
		public boolean isConnectionPending() {
			return false;
		}

		@Override
		public boolean connect(SocketAddress remote) {
			return true;
		}

		@Override
		public boolean finishConnect() {
			return true;
		}

		@Override
		public SocketAddress getRemoteAddress() {
			return null;
		}

		@Override
		public SocketAddress getLocalAddress() {
			return null;
		}

		@Override
		public <T> T getOption(SocketOption<T> name) {
			return null;
		}

		@Override
		public Set<SocketOption<?>> supportedOptions() {
			return Set.of();
		}

		@Override
		protected void implCloseSelectableChannel() {
			// No-op
		}

		@Override
		protected void implConfigureBlocking(boolean block) {
			// No-op
		}
	}
}
