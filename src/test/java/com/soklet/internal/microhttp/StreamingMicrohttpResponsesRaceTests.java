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
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
		Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, callbackReasonRef.get());
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
		Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, callbackReasonRef.get());
		Assertions.assertNull(callbackFailureRef.get());
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
		StreamingResponseBody body = StreamingResponseBody.fromWriter((output, context) -> {
			context.onCancel(() -> callbackReasonRef.set(context.getCancelationReason().orElse(null)));
			output.write("ok".getBytes(StandardCharsets.UTF_8));
		});
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
				(establishedAt, streamDuration, cancelationReason, throwable) -> {
					terminationReasonRef.set(cancelationReason);
					terminationThrowableRef.set(throwable);
					terminatedLatch.countDown();
				},
				callbackFailureRef::set);

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
