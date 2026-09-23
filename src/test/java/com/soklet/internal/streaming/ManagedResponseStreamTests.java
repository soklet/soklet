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

package com.soklet.internal.streaming;

import com.soklet.CallbackRegistration;
import com.soklet.CancelationToken;
import com.soklet.HttpMethod;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseCanceledException;
import com.soklet.StreamingResponseWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Ownership races use explicit latches; no sockets, sleeps, or production worker pools are required. */
public class ManagedResponseStreamTests {
	@Test
	public void nested_lexical_resources_close_in_reverse_order_without_starting_root_supervision() throws Exception {
		Fixture fixture = new Fixture();
		List<String> closes = new ArrayList<>();
		fixture.stream.run(responseStream -> {
			responseStream.open(() -> (AutoCloseable) () -> closes.add("root"));
			responseStream.using(() -> (AutoCloseable) () -> closes.add("page"), page -> {
				responseStream.own((AutoCloseable) () -> {
					Assertions.assertEquals(0, fixture.finalizations.get());
					responseStream.write(new byte[]{'p'});
					closes.add("parser");
				});
				responseStream.open(() -> (AutoCloseable) () -> closes.add("child"));
				responseStream.using(() -> (AutoCloseable) () -> closes.add("nested-page"), nestedPage ->
						responseStream.own((AutoCloseable) () -> closes.add("nested-parser")));
			});
			Assertions.assertEquals(List.of("nested-parser", "nested-page", "child", "parser", "page"), closes);
			Assertions.assertEquals(1, fixture.token.registrationCount());
			Assertions.assertEquals(0, fixture.finalizations.get());
			responseStream.own((AutoCloseable) () -> {
				Assertions.assertEquals(1, fixture.finalizations.get());
				responseStream.write(new byte[]{'r'});
				closes.add("encoder");
			});
		});
		Assertions.assertEquals(List.of("nested-parser", "nested-page", "child", "parser", "page", "encoder", "root"), closes);
		Assertions.assertEquals("pr", fixture.output.bytes.toString(StandardCharsets.UTF_8));
		Assertions.assertEquals(0, fixture.token.registrationCount());
		Assertions.assertFalse(fixture.stream.isOpen());
		Assertions.assertFalse(fixture.token.isCanceled());
	}

	@Test
	public void repeated_lexical_scopes_release_registrations_and_active_identity_entries() throws Exception {
		Fixture fixture = new Fixture();
		AtomicInteger closes = new AtomicInteger();
		fixture.stream.run(responseStream -> {
			for (int iteration = 0; iteration < 100; iteration++) {
				responseStream.using(() -> (AutoCloseable) closes::incrementAndGet, resource -> {
					Assertions.assertEquals(1, fixture.token.registrationCount());
					Assertions.assertThrows(IllegalArgumentException.class, () -> responseStream.own(resource));
				});
				Assertions.assertEquals(0, fixture.token.registrationCount());
			}
		});
		Assertions.assertEquals(100, closes.get());
	}

	@Test
	public void cancelation_closes_a_blocked_upstream_once() throws Exception {
		Fixture fixture = new Fixture();
		AtomicInteger closes = new AtomicInteger();
		CountDownLatch consuming = new CountDownLatch(1);
		CountDownLatch released = new CountDownLatch(1);
		RunningProducer producer = start(fixture, responseStream -> {
			responseStream.open(() -> (AutoCloseable) () -> {
				closes.incrementAndGet();
				released.countDown();
			});
			consuming.countDown();
			await(released);
		});
		try {
			await(consuming);
			fixture.token.cancel(StreamTerminationReason.RESPONSE_TIMEOUT, null).run();
			producer.awaitExit();
			assertCanceled(producer.failure.get(), StreamTerminationReason.RESPONSE_TIMEOUT);
			Assertions.assertEquals(1, closes.get());
			Assertions.assertEquals(0, fixture.token.registrationCount());
		} finally {
			released.countDown();
			producer.awaitExit();
		}
	}

	@Test
	public void separate_abort_finishes_before_a_not_yet_started_final_close() throws Exception {
		Fixture fixture = new Fixture();
		List<String> actions = new CopyOnWriteArrayList<>();
		CountDownLatch ready = new CountDownLatch(1);
		CountDownLatch returnFromBody = new CountDownLatch(1);
		CountDownLatch abortEntered = new CountDownLatch(1);
		CountDownLatch releaseAbort = new CountDownLatch(1);
		RunningProducer producer = start(fixture, responseStream -> {
			responseStream.open(() -> (AutoCloseable) () -> actions.add("close"), resource -> {
				actions.add("abort-enter");
				abortEntered.countDown();
				await(releaseAbort);
				actions.add("abort-exit");
			});
			ready.countDown();
			await(returnFromBody);
		});
		Thread callback = null;
		try {
			await(ready);
			callback = daemonThread(fixture.token.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null));
			callback.start();
			await(abortEntered);
			returnFromBody.countDown();
			await(fixture.finalizationEntered);
			Assertions.assertEquals(List.of("abort-enter"), actions);
			releaseAbort.countDown();
			producer.awaitExit();
			assertCanceled(producer.failure.get(), StreamTerminationReason.CLIENT_DISCONNECTED);
			Assertions.assertEquals(List.of("abort-enter", "abort-exit", "close"), actions);
		} finally {
			releaseAbort.countDown();
			returnFromBody.countDown();
			producer.awaitExit();
			join(callback);
		}
	}

	@Test
	public void separate_abort_can_release_a_final_close_that_already_started() throws Exception {
		Fixture fixture = new Fixture();
		AtomicInteger closes = new AtomicInteger();
		AtomicInteger aborts = new AtomicInteger();
		AtomicReference<Thread> closeThread = new AtomicReference<>();
		CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch releaseClose = new CountDownLatch(1);
		RunningProducer producer = start(fixture, responseStream -> responseStream.open(() -> (AutoCloseable) () -> {
			closeThread.set(Thread.currentThread());
			closes.incrementAndGet();
			closeEntered.countDown();
			await(releaseClose);
		}, resource -> {
			aborts.incrementAndGet();
			releaseClose.countDown();
		}));
		try {
			await(closeEntered);
			fixture.token.cancel(StreamTerminationReason.RESPONSE_TIMEOUT, null).run();
			producer.awaitExit();
			assertCanceled(producer.failure.get(), StreamTerminationReason.RESPONSE_TIMEOUT);
			Assertions.assertEquals(1, closes.get());
			Assertions.assertEquals(1, aborts.get());
			Assertions.assertSame(producer.thread, closeThread.get());
		} finally {
			releaseClose.countDown();
			producer.awaitExit();
		}
	}

	@Test
	public void own_never_registers_a_concurrent_close() throws Exception {
		Fixture fixture = new Fixture();
		AtomicInteger closes = new AtomicInteger();
		AtomicReference<Thread> closeThread = new AtomicReference<>();
		CountDownLatch ready = new CountDownLatch(1);
		CountDownLatch returnFromBody = new CountDownLatch(1);
		RunningProducer producer = start(fixture, responseStream -> {
			responseStream.own((AutoCloseable) () -> {
				closes.incrementAndGet();
				closeThread.set(Thread.currentThread());
			});
			ready.countDown();
			await(returnFromBody);
		});
		try {
			await(ready);
			Assertions.assertEquals(0, fixture.token.registrationCount());
			fixture.token.cancel(StreamTerminationReason.SERVER_STOPPING, null).run();
			Assertions.assertEquals(0, closes.get());
			returnFromBody.countDown();
			producer.awaitExit();
			assertCanceled(producer.failure.get(), StreamTerminationReason.SERVER_STOPPING);
			Assertions.assertEquals(1, closes.get());
			Assertions.assertSame(producer.thread, closeThread.get());
		} finally {
			returnFromBody.countDown();
			producer.awaitExit();
		}
	}

	@Test
	public void producer_error_is_published_before_cleanup_and_cleanup_failures_are_suppressed() {
		Fixture fixture = new Fixture();
		AssertionError producerFailure = new AssertionError("producer failed");
		IOException cleanupFailure = new IOException("close failed");
		AtomicInteger closes = new AtomicInteger();
		AssertionError thrown = Assertions.assertThrows(AssertionError.class, () -> fixture.stream.run(responseStream -> {
			responseStream.own((AutoCloseable) () -> {
				Assertions.assertEquals(List.of(producerFailure), fixture.failures);
				Assertions.assertEquals(1, fixture.finalizations.get());
				Assertions.assertFalse(responseStream.isOpen());
				closes.incrementAndGet();
				throw cleanupFailure;
			});
			throw producerFailure;
		}));
		Assertions.assertSame(producerFailure, thrown);
		Assertions.assertEquals(List.of(cleanupFailure), List.of(thrown.getSuppressed()));
		Assertions.assertEquals(List.of(cleanupFailure), fixture.cleanupFailures);
		Assertions.assertEquals(1, closes.get());
	}

	@Test
	public void lexical_body_and_factory_failures_can_be_recovered_without_starting_root_supervision() throws Exception {
		Fixture fixture = new Fixture();
		IOException lexicalFailure = new IOException("page failed");
		AtomicInteger closes = new AtomicInteger();
		fixture.stream.run(responseStream -> {
			try {
				responseStream.using(() -> (AutoCloseable) () -> {
					Assertions.assertTrue(fixture.failures.isEmpty());
					Assertions.assertEquals(0, fixture.finalizations.get());
					closes.incrementAndGet();
				}, resource -> { throw lexicalFailure; });
			} catch (IOException expected) {
				Assertions.assertSame(lexicalFailure, expected);
			}
			Assertions.assertSame(lexicalFailure, Assertions.assertThrows(IOException.class,
					() -> responseStream.using(() -> { throw lexicalFailure; }, resource -> Assertions.fail("No resource was acquired"))));
			responseStream.using(() -> (AutoCloseable) closes::incrementAndGet, resource -> responseStream.write(new byte[]{'x'}));
			Assertions.assertEquals(0, fixture.finalizations.get());
			Assertions.assertTrue(fixture.failures.isEmpty());
		});
		Assertions.assertEquals("x", fixture.output.bytes.toString(StandardCharsets.UTF_8));
		Assertions.assertEquals(2, closes.get());
		Assertions.assertEquals(0, fixture.token.registrationCount());
	}

	@Test
	public void lexical_failure_remains_primary_when_cleanup_also_fails_and_makes_the_stream_terminal() {
		Fixture fixture = new Fixture();
		IOException lexicalFailure = new IOException("page failed");
		IOException cleanupFailure = new IOException("page close failed");
		IOException thrown = Assertions.assertThrows(IOException.class, () -> fixture.stream.run(responseStream -> {
			Assertions.assertSame(lexicalFailure, Assertions.assertThrows(IOException.class,
					() -> responseStream.using(() -> (AutoCloseable) () -> { throw cleanupFailure; },
							resource -> { throw lexicalFailure; })));
			Assertions.assertFalse(responseStream.isOpen());
		}));
		Assertions.assertSame(lexicalFailure, thrown);
		Assertions.assertEquals(List.of(cleanupFailure), List.of(thrown.getSuppressed()));
		Assertions.assertEquals(List.of(lexicalFailure), fixture.failures);
		Assertions.assertEquals(List.of(cleanupFailure), fixture.cleanupFailures);
		Assertions.assertEquals(0, fixture.token.registrationCount());
	}

	@Test
	public void cleanup_interruption_preserves_lexical_primary_and_restores_the_interrupt_flag() {
		Fixture fixture = new Fixture();
		IOException lexicalFailure = new IOException("page failed");
		InterruptedException cleanupInterruption = new InterruptedException("close interrupted");
		try {
			IOException thrown = Assertions.assertThrows(IOException.class, () -> fixture.stream.run(responseStream ->
					responseStream.using(() -> (AutoCloseable) () -> {
						Thread.currentThread().interrupt();
						Assertions.assertTrue(Thread.interrupted());
						throw cleanupInterruption;
					}, resource -> { throw lexicalFailure; })));
			Assertions.assertSame(lexicalFailure, thrown);
			Assertions.assertEquals(List.of(cleanupInterruption), List.of(thrown.getSuppressed()));
			Assertions.assertEquals(List.of(lexicalFailure), fixture.failures);
			Assertions.assertEquals(List.of(cleanupInterruption), fixture.cleanupFailures);
			Assertions.assertEquals(Optional.of(StreamTerminationReason.PRODUCER_FAILED), fixture.token.getCancelationReason());
			Assertions.assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void canceled_lexical_failure_remains_available_without_suppression_cycles() {
		Fixture fixture = new Fixture();
		IOException lexicalFailure = new IOException("late page failure");
		StreamingResponseCanceledException canceled = Assertions.assertThrows(StreamingResponseCanceledException.class,
				() -> fixture.stream.run(responseStream -> responseStream.using(() -> (AutoCloseable) () -> {}, resource -> {
					fixture.token.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null).run();
					throw lexicalFailure;
				})));
		Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, canceled.getCancelationReason());
		Assertions.assertTrue(contains(canceled, lexicalFailure));
		assertAcyclic(canceled, new IdentityHashMap<>());
	}

	@Test
	public void caught_output_failure_cannot_turn_into_success() {
		Fixture fixture = new Fixture();
		IOException outputFailure = new IOException("transport failed");
		fixture.output.failure = outputFailure;
		AtomicInteger closes = new AtomicInteger();
		IOException thrown = Assertions.assertThrows(IOException.class, () -> fixture.stream.run(responseStream -> {
			responseStream.own((AutoCloseable) closes::incrementAndGet);
			Assertions.assertSame(outputFailure,
					Assertions.assertThrows(IOException.class, () -> responseStream.write(new byte[]{1})));
			Assertions.assertThrows(StreamingResponseCanceledException.class, responseStream::flush);
		}));
		Assertions.assertSame(outputFailure, thrown);
		Assertions.assertEquals(1, fixture.output.writes.get());
		Assertions.assertEquals(0, fixture.output.flushes.get());
		Assertions.assertEquals(1, closes.get());
		Assertions.assertFalse(fixture.stream.isOpen());
	}

	@Test
	public void late_factory_result_is_closed_before_failed_open_returns() throws Exception {
		Fixture fixture = new Fixture();
		AtomicInteger closes = new AtomicInteger();
		CountDownLatch acquisitionEntered = new CountDownLatch(1);
		CountDownLatch releaseAcquisition = new CountDownLatch(1);
		RunningProducer producer = start(fixture, responseStream -> {
			Assertions.assertThrows(StreamingResponseCanceledException.class, () -> responseStream.open(() -> {
				acquisitionEntered.countDown();
				await(releaseAcquisition);
				return (AutoCloseable) closes::incrementAndGet;
			}));
			Assertions.assertEquals(1, closes.get());
		});
		try {
			await(acquisitionEntered);
			fixture.token.cancel(StreamTerminationReason.RESPONSE_TIMEOUT, null).run();
			releaseAcquisition.countDown();
			producer.awaitExit();
			assertCanceled(producer.failure.get(), StreamTerminationReason.RESPONSE_TIMEOUT);
			Assertions.assertEquals(1, closes.get());
		} finally {
			releaseAcquisition.countDown();
			producer.awaitExit();
		}
	}

	@Test
	public void canceled_acquisition_is_suppressed_and_late_adoption_is_disposed() {
		Fixture fixture = new Fixture();
		AtomicInteger factories = new AtomicInteger();
		AtomicInteger closes = new AtomicInteger();
		StreamingResponseCanceledException canceled = Assertions.assertThrows(StreamingResponseCanceledException.class,
				() -> fixture.stream.run(responseStream -> {
					fixture.token.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null).run();
					Assertions.assertThrows(StreamingResponseCanceledException.class, () -> responseStream.open(() -> {
						factories.incrementAndGet();
						return (AutoCloseable) closes::incrementAndGet;
					}));
					Assertions.assertThrows(StreamingResponseCanceledException.class,
							() -> responseStream.own((AutoCloseable) closes::incrementAndGet));
					Assertions.assertEquals(1, closes.get());
				}));
		Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, canceled.getCancelationReason());
		Assertions.assertEquals(0, factories.get());
		Assertions.assertEquals(1, closes.get());
	}

	@Test
	public void explicit_close_abort_is_distinct_from_coordinated_close() {
		Fixture fixture = new Fixture();
		AtomicInteger coordinatedCloses = new AtomicInteger();
		AtomicInteger separateCloses = new AtomicInteger();
		Assertions.assertThrows(StreamingResponseCanceledException.class, () -> fixture.stream.run(responseStream -> {
			responseStream.open(() -> (AutoCloseable) coordinatedCloses::incrementAndGet);
			responseStream.open(() -> (AutoCloseable) separateCloses::incrementAndGet, AutoCloseable::close);
			fixture.token.cancel(StreamTerminationReason.APPLICATION_CANCELED, null).run();
		}));
		Assertions.assertEquals(1, coordinatedCloses.get());
		Assertions.assertEquals(2, separateCloses.get());
	}

	@Test
	public void programming_errors_do_not_poison_output_and_metadata_remains_readable() throws Exception {
		Fixture fixture = new Fixture();
		AtomicInteger factories = new AtomicInteger();
		fixture.stream.run(responseStream -> {
			Assertions.assertThrows(NullPointerException.class, () -> responseStream.write((byte[]) null));
			Assertions.assertThrows(NullPointerException.class, () -> responseStream.write((ByteBuffer) null));
			AtomicReference<Throwable> unexpected = new AtomicReference<>();
			Thread observer = daemonThread(() -> {
				try {
					Assertions.assertSame(fixture.request, responseStream.getRequest());
					Assertions.assertSame(fixture.token, responseStream.getCancelationToken());
					Assertions.assertEquals(Optional.of(fixture.deadline), responseStream.getDeadline());
					Assertions.assertEquals(Optional.of(fixture.idleTimeout), responseStream.getIdleTimeout());
					Assertions.assertThrows(IllegalStateException.class, () -> responseStream.write(new byte[]{1}));
					Assertions.assertThrows(IllegalStateException.class, () -> responseStream.open(() -> {
						factories.incrementAndGet();
						return (AutoCloseable) () -> {};
					}));
				} catch (Throwable throwable) {
					unexpected.set(throwable);
				}
			});
			observer.start();
			join(observer);
			Assertions.assertNull(unexpected.get());
			ByteBuffer bytes = ByteBuffer.wrap(new byte[]{1, 2, 3});
			bytes.position(1);
			responseStream.write(bytes);
			Assertions.assertEquals(1, bytes.position());
			Assertions.assertEquals(3, bytes.limit());
		});
		Assertions.assertArrayEquals(new byte[]{2, 3}, fixture.output.bytes.toByteArray());
		Assertions.assertEquals(0, factories.get());
		Assertions.assertTrue(fixture.failures.isEmpty());
		Assertions.assertThrows(IllegalStateException.class, () -> fixture.stream.write(new byte[0]));
		Assertions.assertThrows(IllegalStateException.class, fixture.stream::flush);
		Assertions.assertThrows(IllegalStateException.class, () -> fixture.stream.run(responseStream -> {}));
		AtomicInteger lateCloses = new AtomicInteger();
		AutoCloseable lateResource = lateCloses::incrementAndGet;
		Assertions.assertThrows(IllegalStateException.class, () -> fixture.stream.own(lateResource));
		Assertions.assertEquals(0, lateCloses.get());
		lateResource.close();
		Assertions.assertEquals(1, lateCloses.get());
		Assertions.assertTrue(fixture.failures.isEmpty());
		Assertions.assertFalse(fixture.token.isCanceled());
		Assertions.assertSame(fixture.request, fixture.stream.getRequest());
	}

	@Test
	public void adoption_during_finalization_is_rejected_before_ownership_transfers() throws Exception {
		Fixture fixture = new Fixture();
		AtomicInteger rejectedCloses = new AtomicInteger();
		AutoCloseable rejectedResource = rejectedCloses::incrementAndGet;
		fixture.stream.run(responseStream -> responseStream.own((AutoCloseable) () -> {
			Assertions.assertThrows(IllegalStateException.class, () -> responseStream.own(rejectedResource));
			Assertions.assertEquals(0, rejectedCloses.get());
		}));
		Assertions.assertEquals(0, rejectedCloses.get());
		rejectedResource.close();
		Assertions.assertEquals(1, rejectedCloses.get());
	}

	@Test
	public void native_interruption_is_sticky_and_cleanup_temporarily_clears_interrupt() {
		Fixture fixture = new Fixture();
		AtomicInteger closes = new AtomicInteger();
		try {
			StreamingResponseCanceledException canceled = Assertions.assertThrows(StreamingResponseCanceledException.class,
					() -> fixture.stream.run(responseStream -> {
						responseStream.own((AutoCloseable) () -> {
							Assertions.assertFalse(Thread.currentThread().isInterrupted());
							closes.incrementAndGet();
						});
						Thread.currentThread().interrupt();
						Assertions.assertThrows(InterruptedException.class, () -> responseStream.write(new byte[0]));
					}));
			Assertions.assertEquals(StreamTerminationReason.APPLICATION_CANCELED, canceled.getCancelationReason());
			Assertions.assertTrue(Thread.currentThread().isInterrupted());
			Assertions.assertEquals(1, closes.get());
			Assertions.assertEquals(0, fixture.output.writes.get());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void claimed_but_delayed_cancel_callback_cannot_close_a_resource_twice() {
		Fixture fixture = new Fixture();
		AtomicInteger closes = new AtomicInteger();
		AtomicReference<Runnable> delayed = new AtomicReference<>();
		Assertions.assertThrows(StreamingResponseCanceledException.class, () -> fixture.stream.run(responseStream -> {
			responseStream.open(() -> (AutoCloseable) closes::incrementAndGet);
			delayed.set(fixture.token.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null));
		}));
		Assertions.assertEquals(1, closes.get());
		delayed.get().run();
		Assertions.assertEquals(1, closes.get());
	}

	private static void assertCanceled(Throwable throwable, StreamTerminationReason reason) {
		Assertions.assertEquals(reason, Assertions.assertInstanceOf(StreamingResponseCanceledException.class, throwable).getCancelationReason());
	}

	private static boolean contains(Throwable root, Throwable target) {
		if (root == target)
			return true;
		if (root.getCause() != null && contains(root.getCause(), target))
			return true;
		for (Throwable suppressed : root.getSuppressed())
			if (contains(suppressed, target))
				return true;
		return false;
	}

	private static void assertAcyclic(Throwable throwable, IdentityHashMap<Throwable, Boolean> path) {
		Assertions.assertNull(path.put(throwable, Boolean.TRUE), "Throwable graph contains a cycle");
		if (throwable.getCause() != null)
			assertAcyclic(throwable.getCause(), path);
		for (Throwable suppressed : throwable.getSuppressed())
			assertAcyclic(suppressed, path);
		path.remove(throwable);
	}

	private static RunningProducer start(Fixture fixture, StreamingResponseWriter writer) {
		RunningProducer producer = new RunningProducer(fixture, writer);
		producer.thread.start();
		return producer;
	}

	private static Thread daemonThread(Runnable runnable) {
		Thread thread = new Thread(runnable, "managed-response-stream-test");
		thread.setDaemon(true);
		return thread;
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Controlled operation did not reach its checkpoint");
	}

	private static void join(Thread thread) throws InterruptedException {
		if (thread != null) {
			thread.join(5_000);
			Assertions.assertFalse(thread.isAlive(), "Controlled thread did not exit");
		}
	}

	private static final class RunningProducer {
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private final CountDownLatch exited = new CountDownLatch(1);
		private final Thread thread;

		private RunningProducer(Fixture fixture, StreamingResponseWriter writer) {
			this.thread = daemonThread(() -> {
				try {
					fixture.stream.run(writer);
				} catch (Throwable throwable) {
					this.failure.set(throwable);
				} finally {
					this.exited.countDown();
				}
			});
		}

		private void awaitExit() throws InterruptedException { await(this.exited); }
	}

	private static final class Fixture {
		private final Request request = Request.fromPath(HttpMethod.GET, "/managed");
		private final Instant deadline = Instant.parse("2026-09-21T00:00:00Z");
		private final Duration idleTimeout = Duration.ofSeconds(30);
		private final TestToken token = new TestToken();
		private final TestOutput output = new TestOutput();
		private final AtomicInteger finalizations = new AtomicInteger();
		private final CountDownLatch finalizationEntered = new CountDownLatch(1);
		private final List<Throwable> failures = new CopyOnWriteArrayList<>();
		private final List<Throwable> cleanupFailures = new CopyOnWriteArrayList<>();
		private final ManagedResponseStream stream = new ManagedResponseStream(this.request, this.token,
				this.deadline, this.idleTimeout, this.output, () -> {
			this.finalizations.incrementAndGet();
			this.finalizationEntered.countDown();
		}, throwable -> {
			this.failures.add(throwable);
			StreamTerminationReason reason = throwable instanceof StreamingResponseCanceledException canceled
					? canceled.getCancelationReason() : throwable instanceof InterruptedException
					? StreamTerminationReason.APPLICATION_CANCELED : StreamTerminationReason.PRODUCER_FAILED;
			this.token.cancel(reason, throwable).run();
		}, this.cleanupFailures::add);
	}

	private static final class TestOutput implements ManagedResponseStream.Output {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final AtomicInteger writes = new AtomicInteger();
		private final AtomicInteger flushes = new AtomicInteger();
		private IOException failure;

		@Override
		public void write(ByteBuffer byteBuffer) throws IOException {
			this.writes.incrementAndGet();
			if (this.failure != null)
				throw this.failure;
			byte[] copy = new byte[byteBuffer.remaining()];
			byteBuffer.get(copy);
			this.bytes.write(copy);
		}

		@Override
		public void flush() { this.flushes.incrementAndGet(); }

		@Override
		public boolean isOpen() { return true; }
	}

	private static final class TestToken implements CancelationToken {
		private final Set<Registration> registrations = new LinkedHashSet<>();
		private StreamTerminationReason reason;
		private Throwable cause;

		@Override
		public synchronized Boolean isCanceled() { return this.reason != null; }

		@Override
		public synchronized Optional<StreamTerminationReason> getCancelationReason() { return Optional.ofNullable(this.reason); }

		@Override
		public synchronized Optional<Throwable> getCancelationCause() { return Optional.ofNullable(this.cause); }

		@Override
		public CallbackRegistration onCancel(Runnable callback) {
			Registration registration = new Registration(callback);
			synchronized (this) {
				if (this.reason == null) {
					this.registrations.add(registration);
					return registration;
				}
			}
			callback.run();
			registration.close();
			return registration;
		}

		private synchronized int registrationCount() { return this.registrations.size(); }

		private Runnable cancel(StreamTerminationReason reason, Throwable cause) {
			List<Runnable> callbacks = new ArrayList<>();
			synchronized (this) {
				if (this.reason != null)
					return () -> {};
				this.reason = reason;
				this.cause = cause;
				for (Registration registration : this.registrations) {
					callbacks.add(registration.callback);
					registration.callback = null;
				}
				this.registrations.clear();
			}
			return () -> callbacks.forEach(Runnable::run);
		}

		private final class Registration implements CallbackRegistration {
			private Runnable callback;

			private Registration(Runnable callback) { this.callback = callback; }

			@Override
			public void close() {
				synchronized (TestToken.this) {
					this.callback = null;
					registrations.remove(this);
				}
			}
		}
	}
}
