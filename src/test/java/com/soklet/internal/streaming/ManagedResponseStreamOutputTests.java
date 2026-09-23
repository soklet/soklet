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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ManagedResponseStreamOutputTests {
	@Test
	public void mixed_views_native_buffers_and_slices_preserve_order_and_caller_storage() throws Exception {
		Fixture fixture = new Fixture(4);
		ByteBuffer caller = ByteBuffer.wrap(new byte[]{'x', 'c', 'd', 'y'});
		caller.position(1).limit(3);
		byte[] array = {'x', 'e', 'f', 'y'};
		fixture.stream.run(responseStream -> {
			OutputStream first = responseStream.asOutputStream();
			OutputStream second = responseStream.asOutputStream();
			Assertions.assertNotSame(first, second);
			Assertions.assertEquals(0, fixture.output.capacityRequests);
			first.write('a');
			second.write('b');
			Assertions.assertEquals(0, fixture.output.writes);
			responseStream.write(caller);
			Assertions.assertEquals(1, caller.position());
			Assertions.assertEquals(3, caller.limit());
			responseStream.write(array, 1, 2);
			array[1] = '?';
			array[2] = '?';
			first.write('g');
			second.write(new byte[]{'h'});
			first.close();
			Assertions.assertThrows(IOException.class, () -> first.write('!'));
			first.close();
			second.write('i');
			responseStream.write("j".getBytes(StandardCharsets.UTF_8));
		});
		Assertions.assertEquals("abcdefghij", fixture.output.text());
		Assertions.assertEquals(1, fixture.output.capacityRequests);
		Assertions.assertEquals(4, fixture.output.stagedActivity);
	}

	@Test
	public void scalar_staging_is_shared_lazy_and_bounded_across_many_views() throws Exception {
		Fixture fixture = new Fixture(4);
		fixture.stream.run(responseStream -> {
			for (int index = 0; index < 100; index++)
				responseStream.asOutputStream().write(index);
			Assertions.assertEquals(1, fixture.output.capacityRequests);
			Assertions.assertEquals(24, fixture.output.writes);
		});
		Assertions.assertEquals(25, fixture.output.writes);
		Assertions.assertEquals(4, fixture.output.largestWrite);
		Assertions.assertEquals(100, fixture.output.stagedActivity);
		byte[] actual = fixture.output.bytes.toByteArray();
		Assertions.assertEquals(100, actual.length);
		for (int index = 0; index < actual.length; index++)
			Assertions.assertEquals((byte) index, actual[index]);
		Assertions.assertNull(staging(fixture.stream));
	}

	@Test
	public void invalid_arguments_do_not_drain_staging_or_poison_the_stream() throws Exception {
		Fixture fixture = new Fixture(4);
		fixture.stream.run(responseStream -> {
			OutputStream view = responseStream.asOutputStream();
			view.write('a');
			Assertions.assertThrows(IndexOutOfBoundsException.class, () -> view.write(new byte[2], 1, 2));
			Assertions.assertThrows(IndexOutOfBoundsException.class, () -> responseStream.write(new byte[2], -1, 1));
			Assertions.assertThrows(IndexOutOfBoundsException.class, () -> responseStream.write(new byte[2], 1, Integer.MAX_VALUE));
			Assertions.assertThrows(NullPointerException.class, () -> responseStream.write(new byte[0], null, 0));
			Assertions.assertThrows(NullPointerException.class, () -> responseStream.write(new byte[0], 0, null));
			Assertions.assertThrows(NullPointerException.class, () -> view.write(null, 0, 0));
			Assertions.assertThrows(NullPointerException.class, () -> responseStream.write((ByteBuffer) null));
			Assertions.assertThrows(NullPointerException.class, () -> responseStream.write((byte[]) null));
			Assertions.assertEquals(0, fixture.output.writes);
			Assertions.assertTrue(responseStream.isOpen());
			responseStream.write("b".getBytes(StandardCharsets.UTF_8));
		});
		Assertions.assertEquals("ab", fixture.output.text());
		Assertions.assertNull(fixture.failure.get());
	}

	@Test
	public void empty_writes_and_flushes_check_cancelation() {
		Fixture fixture = new Fixture(4);
		StreamingResponseCanceledException outcome = Assertions.assertThrows(StreamingResponseCanceledException.class,
				() -> fixture.stream.run(responseStream -> {
					OutputStream view = responseStream.asOutputStream();
					responseStream.write(new byte[0]);
					responseStream.write(new byte[0], 0, 0);
					responseStream.write(ByteBuffer.allocate(0));
					responseStream.write(new byte[0]);
					view.write(new byte[0]);
					fixture.token.cancel(StreamTerminationReason.RESPONSE_TIMEOUT, null);
					Assertions.assertThrows(StreamingResponseCanceledException.class, () -> responseStream.write(new byte[0]));
					Assertions.assertThrows(StreamingResponseCanceledException.class, () -> responseStream.write(new byte[0], 0, 0));
					Assertions.assertThrows(StreamingResponseCanceledException.class, () -> responseStream.write(ByteBuffer.allocate(0)));
					Assertions.assertThrows(StreamingResponseCanceledException.class, () -> responseStream.write(new byte[0]));
					Assertions.assertThrows(StreamingResponseCanceledException.class, () -> view.write(new byte[0]));
					Assertions.assertThrows(StreamingResponseCanceledException.class, view::flush);
					Assertions.assertThrows(StreamingResponseCanceledException.class, responseStream::flush);
				}));
		Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, outcome.getCancelationReason());
		Assertions.assertEquals(0, fixture.output.writes);
	}

	@Test
	public void native_and_view_lifetime_errors_remain_distinct_and_foreign_thread_use_is_rejected() throws Exception {
		Fixture fixture = new Fixture(4);
		AtomicReference<OutputStream> retained = new AtomicReference<>();
		Assertions.assertThrows(IllegalStateException.class, fixture.stream::asOutputStream);
		fixture.stream.run(responseStream -> {
			OutputStream view = responseStream.asOutputStream();
			retained.set(view);
			AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
			Thread foreignThread = new Thread(() -> {
				try {
					Assertions.assertThrows(IllegalStateException.class, () -> view.write(new byte[0]));
					Assertions.assertThrows(IllegalStateException.class, view::flush);
					Assertions.assertThrows(IllegalStateException.class, view::close);
					Assertions.assertThrows(IllegalStateException.class, () -> responseStream.write(new byte[0]));
				} catch (Throwable throwable) {
					foreignFailure.set(throwable);
				}
			});
			foreignThread.start();
			foreignThread.join(3000);
			Assertions.assertFalse(foreignThread.isAlive());
			Assertions.assertNull(foreignFailure.get());
			view.write('a');
		});
		Assertions.assertFalse(fixture.stream.isOpen());
		Assertions.assertThrows(IllegalStateException.class, () -> fixture.stream.write(new byte[0], 0, 0));
		Assertions.assertThrows(IllegalStateException.class, () -> fixture.stream.write(new byte[0]));
		Assertions.assertThrows(IllegalStateException.class, fixture.stream::flush);
		Assertions.assertThrows(IOException.class, () -> retained.get().write(new byte[0]));
		Assertions.assertThrows(IOException.class, retained.get()::flush);
		Assertions.assertThrows(IOException.class, retained.get()::close);
		Assertions.assertDoesNotThrow(retained.get()::close);
		Assertions.assertEquals("a", fixture.output.text());
	}

	@Test
	public void managed_zip_close_writes_its_trailer_before_final_output_is_sealed() throws Exception {
		Fixture fixture = new Fixture(7);
		fixture.stream.run(responseStream -> {
			ZipOutputStream zipOutputStream = responseStream.own(new ZipOutputStream(responseStream.asOutputStream()));
			zipOutputStream.putNextEntry(new ZipEntry("message.txt"));
			zipOutputStream.write("contents".getBytes(StandardCharsets.UTF_8));
		});
		try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(fixture.output.bytes.toByteArray()))) {
			Assertions.assertEquals("message.txt", zipInputStream.getNextEntry().getName());
			Assertions.assertEquals("contents", new String(zipInputStream.readAllBytes(), StandardCharsets.UTF_8));
			Assertions.assertNull(zipInputStream.getNextEntry());
		}
		Assertions.assertFalse(fixture.stream.isOpen());
		Assertions.assertNull(staging(fixture.stream));
	}

	@Test
	public void caught_output_failure_discards_staging_and_cannot_be_retried() {
		Fixture fixture = new Fixture(4);
		IOException failure = new IOException("transport failed");
		fixture.output.writeFailure = failure;
		fixture.output.acceptedBeforeFailure = 1;
		IOException outcome = Assertions.assertThrows(IOException.class, () -> fixture.stream.run(responseStream -> {
			OutputStream view = responseStream.asOutputStream();
			view.write('a');
			view.write('b');
			Assertions.assertSame(failure, Assertions.assertThrows(IOException.class, () -> view.write(new byte[]{'c'})));
			Assertions.assertNull(staging(fixture.stream));
			Assertions.assertFalse(responseStream.isOpen());
			Assertions.assertThrows(IOException.class, () -> responseStream.write(new byte[]{'d'}));
		}));
		Assertions.assertSame(failure, outcome);
		Assertions.assertEquals("a", fixture.output.text());
		Assertions.assertEquals(1, fixture.output.writes);
	}

	@Test
	public void interrupted_bulk_view_reports_only_the_accepted_prefix_of_its_current_slice() {
		Fixture fixture = new Fixture(4);
		InterruptedException interruption = new InterruptedException("capacity wait interrupted");
		fixture.output.writeFailure = interruption;
		fixture.output.failOnWrite = 2;
		fixture.output.acceptedBeforeFailure = 2;
		try {
			StreamingResponseCanceledException outcome = Assertions.assertThrows(StreamingResponseCanceledException.class,
					() -> fixture.stream.run(responseStream -> {
						OutputStream view = responseStream.asOutputStream();
						view.write('a');
						view.write('b');
						InterruptedIOException bridge = Assertions.assertThrows(InterruptedIOException.class,
								() -> view.write(new byte[]{'x', 'c', 'd', 'e', 'f', 'y'}, 1, 4));
						Assertions.assertEquals(2, bridge.bytesTransferred);
						Assertions.assertSame(interruption, bridge.getCause());
						Assertions.assertTrue(Thread.currentThread().isInterrupted());
						Assertions.assertThrows(IOException.class, () -> view.write('!'));
					}));
			Assertions.assertEquals(StreamTerminationReason.APPLICATION_CANCELED, outcome.getCancelationReason());
			Assertions.assertSame(interruption, outcome.getCancelationCause().orElseThrow());
			Assertions.assertEquals("abcd", fixture.output.text());
			Assertions.assertEquals(2, fixture.output.writes);
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void interruption_while_draining_older_staging_accepts_none_of_the_new_bulk_or_scalar_call() throws Exception {
		for (boolean scalar : List.of(false, true)) {
			Fixture fixture = new Fixture(1);
			fixture.output.writeFailure = new InterruptedException("older staging interrupted");
			fixture.output.acceptedBeforeFailure = 1;
			try {
				Assertions.assertThrows(StreamingResponseCanceledException.class, () -> fixture.stream.run(responseStream -> {
					OutputStream view = responseStream.asOutputStream();
					view.write('a');
					InterruptedIOException bridge = Assertions.assertThrows(InterruptedIOException.class, () -> {
						if (scalar) view.write('b');
						else view.write(new byte[]{'b', 'c'});
					});
					Assertions.assertEquals(0, bridge.bytesTransferred);
					Assertions.assertTrue(Thread.currentThread().isInterrupted());
				}));
				Assertions.assertEquals("a", fixture.output.text());
				Assertions.assertEquals(1, fixture.output.writes);
				Assertions.assertNull(staging(fixture.stream));
			} finally {
				Thread.interrupted();
			}
		}
	}

	@Test
	public void preexisting_interrupt_on_an_empty_view_write_is_terminal_without_accepting_bytes() {
		Fixture fixture = new Fixture(4);
		try {
			Assertions.assertThrows(StreamingResponseCanceledException.class, () -> fixture.stream.run(responseStream -> {
				OutputStream view = responseStream.asOutputStream();
				Thread.currentThread().interrupt();
				InterruptedIOException bridge = Assertions.assertThrows(InterruptedIOException.class, () -> view.write(new byte[0]));
				Assertions.assertEquals(0, bridge.bytesTransferred);
				Assertions.assertInstanceOf(InterruptedException.class, bridge.getCause());
				Assertions.assertTrue(Thread.currentThread().isInterrupted());
			}));
			Assertions.assertEquals(0, fixture.output.writes);
			Assertions.assertEquals(StreamTerminationReason.APPLICATION_CANCELED, fixture.token.reason);
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void native_partial_interruption_leaves_the_callers_buffer_unchanged_and_elects_the_same_reason() {
		Fixture fixture = new Fixture(4);
		InterruptedException interruption = new InterruptedException("native capacity wait interrupted");
		fixture.output.writeFailure = interruption;
		fixture.output.acceptedBeforeFailure = 2;
		ByteBuffer caller = ByteBuffer.wrap(new byte[]{'x', 'a', 'b', 'c', 'y'});
		caller.position(1).limit(4);
		try {
			StreamingResponseCanceledException outcome = Assertions.assertThrows(StreamingResponseCanceledException.class,
					() -> fixture.stream.run(responseStream -> {
						Assertions.assertSame(interruption, Assertions.assertThrows(InterruptedException.class, () -> responseStream.write(caller)));
						Assertions.assertEquals(1, caller.position());
						Assertions.assertEquals(4, caller.limit());
					}));
			Assertions.assertEquals(StreamTerminationReason.APPLICATION_CANCELED, outcome.getCancelationReason());
			Assertions.assertEquals("ab", fixture.output.text());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void an_already_elected_reason_wins_over_output_interruption_for_native_and_view_calls() {
		for (boolean view : List.of(false, true)) {
			Fixture fixture = new Fixture(4);
			IOException timeoutCause = new IOException("deadline already elapsed");
			fixture.output.writeFailure = new InterruptedException("interrupted by timeout");
			fixture.output.beforeWriteFailure = () -> fixture.token.cancel(StreamTerminationReason.RESPONSE_TIMEOUT, timeoutCause);
			try {
				StreamingResponseCanceledException outcome = Assertions.assertThrows(StreamingResponseCanceledException.class,
						() -> fixture.stream.run(responseStream -> {
							StreamingResponseCanceledException caught = Assertions.assertThrows(StreamingResponseCanceledException.class, () -> {
								if (view) responseStream.asOutputStream().write(new byte[]{'a'});
								else responseStream.write(new byte[]{'a'});
							});
							Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, caught.getCancelationReason());
							Assertions.assertSame(timeoutCause, caught.getCancelationCause().orElseThrow());
							Assertions.assertTrue(Thread.currentThread().isInterrupted());
						}));
				Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, outcome.getCancelationReason());
				Assertions.assertSame(timeoutCause, outcome.getCancelationCause().orElseThrow());
				Assertions.assertTrue(Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}
		}
	}

	@Test
	public void socket_timeout_is_not_reclassified_as_a_bridge_interruption() {
		Fixture fixture = new Fixture(4);
		SocketTimeoutException timeout = new SocketTimeoutException("upstream timeout");
		fixture.output.writeFailure = timeout;
		SocketTimeoutException outcome = Assertions.assertThrows(SocketTimeoutException.class, () -> fixture.stream.run(responseStream -> {
			Assertions.assertSame(timeout, Assertions.assertThrows(SocketTimeoutException.class,
					() -> responseStream.asOutputStream().write(new byte[]{'a'})));
			Assertions.assertFalse(Thread.currentThread().isInterrupted());
		}));
		Assertions.assertSame(timeout, outcome);
		Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, fixture.token.reason);
	}

	@Test
	public void view_close_is_final_even_when_flush_is_interrupted_and_repeated_close_does_not_retry() {
		Fixture fixture = new Fixture(4);
		InterruptedException interruption = new InterruptedException("flush interrupted");
		fixture.output.flushFailure = interruption;
		try {
			Assertions.assertThrows(StreamingResponseCanceledException.class, () -> fixture.stream.run(responseStream -> {
				OutputStream view = responseStream.asOutputStream();
				view.write('a');
				InterruptedIOException bridge = Assertions.assertThrows(InterruptedIOException.class, view::close);
				Assertions.assertEquals(0, bridge.bytesTransferred);
				Assertions.assertSame(interruption, bridge.getCause());
				Assertions.assertTrue(Thread.currentThread().isInterrupted());
				Assertions.assertDoesNotThrow(view::close);
				IOException closed = Assertions.assertThrows(IOException.class, () -> view.write('b'));
				Assertions.assertEquals("Response output view is closed", closed.getMessage());
			}));
			Assertions.assertEquals("a", fixture.output.text());
			Assertions.assertEquals(1, fixture.output.flushes);
		} finally {
			Thread.interrupted();
		}
	}

	private static Object staging(ManagedResponseStream stream) throws Exception {
		var field = ManagedResponseStream.class.getDeclaredField("staging");
		field.setAccessible(true);
		return field.get(stream);
	}

	private static final class Fixture {
		private final TestToken token = new TestToken();
		private final TestOutput output;
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private final ManagedResponseStream stream;

		private Fixture(int capacity) {
			this.output = new TestOutput(capacity);
			this.stream = new ManagedResponseStream(Request.withPath(HttpMethod.GET, "/output").build(), this.token,
					null, null, this.output, () -> {}, throwable -> {
				this.failure.compareAndSet(null, throwable);
				if (throwable instanceof StreamingResponseCanceledException canceled) {
					this.token.cancel(canceled.getCancelationReason(), canceled.getCancelationCause().orElse(null));
				} else if (throwable instanceof InterruptedException) {
					this.token.cancel(StreamTerminationReason.APPLICATION_CANCELED, throwable);
				} else {
					this.token.cancel(StreamTerminationReason.PRODUCER_FAILED, throwable);
				}
			}, ignored -> {});
		}
	}

	private static final class TestOutput implements ManagedResponseStream.Output {
		private final int capacity;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private int capacityRequests;
		private int stagedActivity;
		private int writes;
		private int flushes;
		private int largestWrite;
		private Throwable writeFailure;
		private Throwable flushFailure;
		private int failOnWrite = 1;
		private int acceptedBeforeFailure;
		private Runnable beforeWriteFailure = () -> {};

		private TestOutput(int capacity) { this.capacity = capacity; }

		@Override
		public void write(ByteBuffer byteBuffer) throws IOException, InterruptedException {
			this.writes++;
			this.largestWrite = Math.max(this.largestWrite, byteBuffer.remaining());
			boolean fail = this.writeFailure != null && this.writes == this.failOnWrite;
			int count = fail ? Math.min(this.acceptedBeforeFailure, byteBuffer.remaining()) : byteBuffer.remaining();
			byte[] accepted = new byte[count];
			byteBuffer.get(accepted);
			this.bytes.writeBytes(accepted);
			if (fail) {
				this.beforeWriteFailure.run();
				throwFailure(this.writeFailure);
			}
		}

		@Override
		public void flush() throws IOException, InterruptedException {
			this.flushes++;
			throwFailure(this.flushFailure);
		}

		@Override public boolean isOpen() { return true; }
		@Override public int stagingCapacityInBytes() { this.capacityRequests++; return this.capacity; }
		@Override public void didStageBytes() { this.stagedActivity++; }
		private String text() { return this.bytes.toString(StandardCharsets.UTF_8); }

		private static void throwFailure(Throwable throwable) throws IOException, InterruptedException {
			if (throwable instanceof IOException exception) throw exception;
			if (throwable instanceof InterruptedException exception) throw exception;
			if (throwable instanceof RuntimeException exception) throw exception;
			if (throwable instanceof Error error) throw error;
		}
	}

	private static final class TestToken implements CancelationToken {
		private final List<Runnable> callbacks = new ArrayList<>();
		private StreamTerminationReason reason;
		private Throwable cause;

		@Override public Boolean isCanceled() { return this.reason != null; }
		@Override public Optional<StreamTerminationReason> getCancelationReason() { return Optional.ofNullable(this.reason); }
		@Override public Optional<Throwable> getCancelationCause() { return Optional.ofNullable(this.cause); }
		@Override public CallbackRegistration onCancel(Runnable callback) {
			if (this.reason != null) callback.run();
			else this.callbacks.add(callback);
			return () -> this.callbacks.remove(callback);
		}
		private void cancel(StreamTerminationReason reason, Throwable cause) {
			if (this.reason != null) return;
			this.reason = reason;
			this.cause = cause;
			List<Runnable> pending = List.copyOf(this.callbacks);
			this.callbacks.clear();
			pending.forEach(Runnable::run);
		}
	}
}
