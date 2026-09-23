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

import com.soklet.CallbackRegistration;
import com.soklet.CancelationToken;
import com.soklet.Request;
import com.soklet.ResponseStream;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamingResponseCanceledException;
import com.soklet.internal.streaming.ManagedResponseStream;
import com.soklet.internal.streaming.PublisherResponseStream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Factory for streaming microhttp responses.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class StreamingMicrohttpResponses {
	@NonNull
	private static final TestHooks NO_OP_TEST_HOOKS = new TestHooks() {
		// No-op
	};
	@NonNull
	private static volatile TestHooks testHooks = NO_OP_TEST_HOOKS;

	private StreamingMicrohttpResponses() {
		// Utility class
	}

	/**
	 * Creates a streaming response whose body is written directly by the supplied event-driven source.
	 * The supplier is normally invoked when the transport takes ownership of the response for writing. It is
	 * also invoked when a canceled, late, or duplicate response must be disposed without being written; in
	 * that case the returned source is closed without {@link WritableSource#start()} being called. Every
	 * invocation must return a fresh source whose close operation is safe and non-blocking.
	 *
	 * @param status the HTTP status code
	 * @param reason the HTTP reason phrase
	 * @param headers the response headers
	 * @param sourceSupplier supplies a new response-body source
	 * @return the streaming response
	 */
	@NonNull
	public static MicrohttpResponse withWritableSourceBody(@NonNull Integer status,
																			 @NonNull String reason,
																			 @NonNull List<@NonNull Header> headers,
																			 @NonNull Supplier<? extends @NonNull WritableSource> sourceSupplier) {
		requireNonNull(status);
		requireNonNull(reason);
		requireNonNull(headers);
		requireNonNull(sourceSupplier);

		return MicrohttpResponse.withStreamingBody(status, reason, headers,
				() -> requireNonNull(sourceSupplier.get()));
	}

	static void setTestHooks(@Nullable TestHooks testHooks) {
		StreamingMicrohttpResponses.testHooks = testHooks == null ? NO_OP_TEST_HOOKS : testHooks;
	}

	interface TestHooks {
		default long nanoTime() { return System.nanoTime(); }
		default void beforeResponseTimeoutScheduled() {
			// No-op by default
		}
		default void beforeTerminalCompletion(@NonNull Runnable failWithResponseTimeout) {
			// No-op by default
		}

		default void afterFailureReserved(@NonNull Runnable closeAsClientDisconnected) {
			// No-op by default
		}

		default void beforeFailureApplied() {
			// No-op by default
		}
	}

	/** Starts supervision before a managed writer begins producer-thread resource finalization. */
	public static void beginFinalization(@NonNull ResponseStream responseStream) {
		requireNonNull(responseStream);
		if (responseStream.getCancelationToken() instanceof DefaultCancelationToken cancelationToken
				&& cancelationToken.reservation != null)
			cancelationToken.reservation.beginCleanup();
	}

	/** Disposes an uncommitted/duplicate streaming response without invoking its producer. */
	public static void discard(@NonNull MicrohttpResponse microhttpResponse) {
		requireNonNull(microhttpResponse);
		if (!microhttpResponse.streaming())
			return;
		try {
			microhttpResponse.closeBody(StreamTerminationReason.CLIENT_DISCONNECTED, null);
		} catch (IOException ignored) {
			// The source owns termination accounting; discarding must not replace the caller's failure.
		}
	}

	@NonNull
	public static MicrohttpResponse withStreamingBody(@NonNull Integer status,
																									 @NonNull String reason,
																									 @NonNull List<@NonNull Header> headers,
																									 @NonNull Request request,
																									 @NonNull StreamingResponseBody body,
																									 @NonNull ExecutorService executorService,
																									 @NonNull ScheduledExecutorService timeoutExecutorService,
																									 @NonNull Integer queueCapacityInBytes,
																									 @NonNull Integer chunkSizeInBytes,
																									 @Nullable Instant deadline,
																	 @Nullable Duration idleTimeout,
																	 @NonNull TerminationListener terminationListener,
																	 @NonNull Consumer<Throwable> cancelationCallbackFailureConsumer) {
		return withStreamingBody(status, reason, headers, request, body,
				executorService, timeoutExecutorService, queueCapacityInBytes,
				chunkSizeInBytes, deadline, idleTimeout, () -> false,
				terminationListener, cancelationCallbackFailureConsumer);
	}

	@NonNull
	public static MicrohttpResponse withStreamingBody(@NonNull Integer status,
																	 @NonNull String reason,
																	 @NonNull List<@NonNull Header> headers,
																	 @NonNull Request request,
																	 @NonNull StreamingResponseBody body,
																	 @NonNull ExecutorService executorService,
																	 @NonNull ScheduledExecutorService timeoutExecutorService,
																	 @NonNull Integer queueCapacityInBytes,
																	 @NonNull Integer chunkSizeInBytes,
																	 @Nullable Instant deadline,
																	 @Nullable Duration idleTimeout,
									 @NonNull BooleanSupplier forcedShutdownStarted,
									 @NonNull TerminationListener terminationListener,
									 @NonNull Consumer<Throwable> cancelationCallbackFailureConsumer) {
		return withStreamingBody(status, reason, headers, request, body, executorService,
				timeoutExecutorService, queueCapacityInBytes, chunkSizeInBytes, deadline, idleTimeout,
				forcedShutdownStarted, terminationListener, cancelationCallbackFailureConsumer, null);
	}

	/** Uses a lifecycle reservation acquired before response commitment by the owning HTTP server. */
	@NonNull
	public static MicrohttpResponse withStreamingBody(@NonNull Integer status,
			@NonNull String reason, @NonNull List<@NonNull Header> headers,
			@NonNull Request request, @NonNull StreamingResponseBody body,
			@NonNull ExecutorService executorService,
			@NonNull ScheduledExecutorService timeoutExecutorService,
			@NonNull Integer queueCapacityInBytes, @NonNull Integer chunkSizeInBytes,
			@Nullable Instant deadline, @Nullable Duration idleTimeout,
			@NonNull BooleanSupplier forcedShutdownStarted,
			@NonNull TerminationListener terminationListener,
			@NonNull Consumer<Throwable> cancelationCallbackFailureConsumer,
			StreamLifecycleCoordinator.@Nullable Reservation reservation) {
		requireNonNull(status);
		requireNonNull(reason);
		requireNonNull(headers);
		requireNonNull(request);
		requireNonNull(body);
		requireNonNull(executorService);
		requireNonNull(timeoutExecutorService);
		requireNonNull(queueCapacityInBytes);
		requireNonNull(chunkSizeInBytes);
		requireNonNull(forcedShutdownStarted);
		requireNonNull(terminationListener);
		requireNonNull(cancelationCallbackFailureConsumer);

		if (queueCapacityInBytes < 1)
			throw new IllegalArgumentException("Streaming queue capacity must be > 0");

		if (chunkSizeInBytes < 1)
			throw new IllegalArgumentException("Streaming chunk size must be > 0");

		Supplier<StreamingWritableSource> sourceSupplier = () -> new StreamingWritableSource(
				request,
				body,
				executorService,
				timeoutExecutorService,
				queueCapacityInBytes,
				chunkSizeInBytes,
				deadline,
				idleTimeout,
				forcedShutdownStarted,
				terminationListener,
				cancelationCallbackFailureConsumer,
				reservation);
		if (reservation != null) {
			// Binding before commitment makes forced shutdown/discard observable even if the
			// transport never requests a source. No application acquisition occurs until start.
			StreamingWritableSource source = sourceSupplier.get();
			try {
				MicrohttpResponse response = MicrohttpResponse.withStreamingBody(status, reason, headers, () -> source);
				source.prepareProducer();
				return response;
			} catch (RuntimeException | Error throwable) {
				source.close(StreamTerminationReason.PRODUCER_FAILED, throwable);
				throw throwable;
			}
		}
		return MicrohttpResponse.withStreamingBody(status, reason, headers, sourceSupplier::get);
	}

	/**
	 * Callback invoked once when a streaming response terminates.
	 */
	@FunctionalInterface
	public interface TerminationListener {
		void didTerminate(@NonNull Instant establishedAt,
											@NonNull Duration streamDuration,
											@Nullable StreamTerminationReason cancelationReason,
											@Nullable Throwable throwable);
	}

	@NotThreadSafe
	private static final class StreamingWritableSource implements WritableSource {
		private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
		private static final byte[] TERMINAL_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

		@NonNull
		private final StreamingResponseBody body;
		@NonNull
		private final ExecutorService executorService;
		@NonNull
		private final ScheduledExecutorService timeoutExecutorService;
		@NonNull
		private final Integer queueCapacityInBytes;
		@NonNull
		private final Integer chunkSizeInBytes;
		@Nullable
		private final Instant deadline;
		@Nullable
		private final Duration idleTimeout;
		@NonNull
		private final BooleanSupplier forcedShutdownStarted;
		@NonNull
		private final TerminationListener terminationListener;
		@NonNull
		private final DefaultCancelationToken cancelationToken;
		@NonNull
		private final Request request;
		@Nullable
		private final StreamLifecycleCoordinator.Reservation reservation;
		@NonNull
		private final Object lock;
		@NonNull
		private final Queue<QueuedChunk> chunks;
		@NonNull
		private final AtomicBoolean started;
		@NonNull
		private final AtomicBoolean terminationNotified;
		@NonNull
		private final AtomicReference<ScheduledFuture<?>> responseTimeoutFuture;
		@NonNull
		private final AtomicReference<ScheduledFuture<?>> idleTimeoutFuture;
		@NonNull
		private volatile Runnable writeReadyCallback;
		@Nullable
		private QueuedChunk currentChunk;
		@Nullable
		private Throwable failure;
		@Nullable
		private Thread producerThread;
		private boolean producerEntered;
		private boolean producerStartReleased;
		private boolean producerDone;
		private boolean closed;
		private boolean completed;
		private int queuedPayloadBytes;
		private volatile long lastIdleActivityNanos;
		private boolean timeoutsStopped;
		@NonNull
		private final Instant streamStarted;

		private StreamingWritableSource(@NonNull Request request,
																		@NonNull StreamingResponseBody body,
																		@NonNull ExecutorService executorService,
																		@NonNull ScheduledExecutorService timeoutExecutorService,
																		@NonNull Integer queueCapacityInBytes,
																		@NonNull Integer chunkSizeInBytes,
																						@Nullable Instant deadline,
																						@Nullable Duration idleTimeout,
																						@NonNull BooleanSupplier forcedShutdownStarted,
																						@NonNull TerminationListener terminationListener,
																	 @NonNull Consumer<Throwable> cancelationCallbackFailureConsumer,
																	 StreamLifecycleCoordinator.@Nullable Reservation reservation) {
			requireNonNull(request);
			this.body = requireNonNull(body);
			this.executorService = requireNonNull(executorService);
			this.timeoutExecutorService = requireNonNull(timeoutExecutorService);
			this.queueCapacityInBytes = requireNonNull(queueCapacityInBytes);
			this.chunkSizeInBytes = requireNonNull(chunkSizeInBytes);
			this.deadline = deadline;
			this.idleTimeout = idleTimeout;
			this.forcedShutdownStarted = requireNonNull(forcedShutdownStarted);
			this.terminationListener = requireNonNull(terminationListener);
			this.reservation = reservation;
			this.cancelationToken = new DefaultCancelationToken(cancelationCallbackFailureConsumer, reservation);
			this.request = request;
			this.lock = new Object();
			this.chunks = new ArrayDeque<>();
			this.started = new AtomicBoolean(false);
			this.terminationNotified = new AtomicBoolean(false);
			this.responseTimeoutFuture = new AtomicReference<>();
			this.idleTimeoutFuture = new AtomicReference<>();
			this.writeReadyCallback = () -> {
				// No-op until the event loop provides a wakeup callback.
			};
			this.streamStarted = Instant.now();
			if (reservation != null)
				reservation.bindTermination(this::applyFailure);
		}

		@Override
		public void start() throws IOException {
			if (!this.started.compareAndSet(false, true))
				return;
			synchronized (this.lock) {
				if (this.closed || this.failure != null)
					return;
			}

			scheduleResponseTimeoutIfNeeded();
			resetIdleTimeoutIfNeeded();
			if (this.reservation != null) {
				synchronized (this.lock) {
					this.producerStartReleased = true;
					this.lock.notifyAll();
				}
				return;
			}

			try {
				this.executorService.execute(this::runProducer);
			} catch (RejectedExecutionException e) {
				boolean serverStopping = this.forcedShutdownStarted.getAsBoolean();
				fail(serverStopping
						? StreamTerminationReason.SERVER_STOPPING
						: StreamTerminationReason.PRODUCER_FAILED,
						serverStopping ? null : e);
			}
		}

		private void prepareProducer() {
			StreamLifecycleCoordinator.Reservation reservation = requireNonNull(this.reservation);
			if (!reservation.execute(this.executorService, this::awaitStartAndRun))
				throw new RejectedExecutionException("Streaming production was canceled before admission");
		}

		private void awaitStartAndRun() {
			try {
				synchronized (this.lock) {
					while (!this.producerStartReleased && !this.closed && this.failure == null)
						this.lock.wait();
					if (this.closed || this.failure != null)
						return;
				}
				runProducer();
			} catch (InterruptedException interruptedException) {
				Thread.currentThread().interrupt();
				fail(this.forcedShutdownStarted.getAsBoolean()
						? StreamTerminationReason.SERVER_STOPPING
						: StreamTerminationReason.APPLICATION_CANCELED, interruptedException);
			}
		}

		@Override
		public void writeReadyCallback(Runnable callback) {
			this.writeReadyCallback = callback == null ? () -> {
				// No-op
			} : callback;
		}

		@Override
		public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
			requireNonNull(socketChannel);

			if (maxBytes <= 0)
				return 0L;

			Throwable failure = this.failure;

			if (failure != null)
				throw toIOException(failure);

			long totalWritten = 0L;

			while (totalWritten < maxBytes) {
				QueuedChunk chunk = this.currentChunk;

				if (chunk == null) {
					synchronized (this.lock) {
						if (this.failure != null)
							throw toIOException(this.failure);

						chunk = this.chunks.poll();
						this.currentChunk = chunk;
					}

					if (chunk == null)
						break;
				}

				long written = chunk.writeTo(socketChannel, maxBytes - totalWritten);
				totalWritten += written;

				if (chunk.isComplete()) {
					boolean terminal = chunk.terminal;
					boolean notifyCompleted = false;

					if (terminal)
						StreamingMicrohttpResponses.testHooks.beforeTerminalCompletion(() ->
								fail(StreamTerminationReason.RESPONSE_TIMEOUT, null));

					synchronized (this.lock) {
						if (chunk.payloadBytes > 0)
							this.queuedPayloadBytes -= chunk.payloadBytes;

						this.currentChunk = null;

						if (terminal && !this.closed && this.failure == null && !this.completed) {
							if (this.reservation != null && !this.reservation.completeTransport())
								throw new StreamingResponseCanceledException(
										this.reservation.reason().orElseThrow(), this.reservation.cause().orElse(null));
							this.completed = true;
							notifyCompleted = true;
						}

						this.lock.notifyAll();
					}

					if (notifyCompleted)
						notifyTerminated(null, null);

					if (written == 0)
						continue;
				}

				if (written == 0)
					break;
			}

			return totalWritten;
		}

		@Override
		public boolean hasRemaining() {
			synchronized (this.lock) {
				return !this.completed
						&& (this.failure != null || this.currentChunk != null || !this.chunks.isEmpty() || !this.producerDone);
			}
		}

		@Override
		public boolean isReadyToWrite() {
			synchronized (this.lock) {
				return this.failure != null || this.currentChunk != null || !this.chunks.isEmpty();
			}
		}

		@Override
		public void close() {
			close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
		}

		@Override
		public void close(@Nullable StreamTerminationReason cancelationReason, @Nullable Throwable cause) {
			StreamTerminationReason defaultedCancelationReason =
					cancelationReason == null ? StreamTerminationReason.CLIENT_DISCONNECTED : cancelationReason;
			cancelTimeouts();
			boolean cancel;

			synchronized (this.lock) {
				if (this.closed)
					return;
				cancel = !this.completed && this.failure == null;
			}
			if (cancel)
				fail(defaultedCancelationReason, cause);
			synchronized (this.lock) {
				this.closed = true;
				this.chunks.clear();
				this.currentChunk = null;
				this.lock.notifyAll();
			}
		}

		private void beginFinalization() {
			if (this.reservation != null)
				this.reservation.beginCleanup();
		}

		private void runProducer() {
			synchronized (this.lock) {
				if (this.closed || this.failure != null || this.producerEntered)
					return;
				this.producerEntered = true;
				this.producerThread = Thread.currentThread();
			}
			try {
				StreamingResponseBody body = this.body;

				if (body instanceof StreamingResponseBody.WriterBody writerBody) {
					newManagedResponseStream().run(writerBody.getWriter());
				} else if (body instanceof StreamingResponseBody.InputStreamBody inputStreamBody) {
					newManagedResponseStream().run(responseStream -> copyInputStream(inputStreamBody, responseStream));
				} else if (body instanceof StreamingResponseBody.ReaderBody readerBody) {
					newManagedResponseStream().run(responseStream -> copyReader(readerBody, responseStream));
				} else if (body instanceof StreamingResponseBody.PublisherBody publisherBody) {
					copyPublisher(publisherBody);
				} else {
					throw new IllegalStateException(format("Unsupported streaming response body type: %s", body.getClass().getName()));
				}

				completeProducer();
			} catch (Throwable t) {
				failProducer(t);
			} finally {
				synchronized (this.lock) {
					this.producerThread = null;
					this.lock.notifyAll();
				}
			}
		}

		private void failProducer(@NonNull Throwable throwable) {
			// A transport outcome can precede producer exit. Preserve later application
			// failure evidence even though a second terminal signal cannot replace it.
			if (this.cancelationToken.isCanceled()
					&& throwable != this.cancelationToken.getCancelationCause().orElse(null)
					&& !(throwable instanceof InterruptedException)
					&& (!(throwable instanceof StreamingResponseCanceledException)
							|| throwable.getSuppressed().length != 0))
				reportCleanupFailure(throwable);
			if (throwable instanceof StreamingResponseCanceledException canceledException) {
				fail(canceledException.getCancelationReason(), canceledException.getCancelationCause().orElse(null));
			} else if (throwable instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				boolean serverStopping = this.forcedShutdownStarted.getAsBoolean();
				fail(this.cancelationToken.getCancelationReason().orElse(serverStopping
						? StreamTerminationReason.SERVER_STOPPING
						: StreamTerminationReason.APPLICATION_CANCELED), serverStopping ? null : throwable);
			} else if (this.forcedShutdownStarted.getAsBoolean()) {
				fail(StreamTerminationReason.SERVER_STOPPING, null);
			} else {
				fail(StreamTerminationReason.PRODUCER_FAILED, throwable);
			}
		}

		private void reportCleanupFailure(@NonNull Throwable throwable) {
			if (this.reservation != null)
				this.reservation.reportCleanupFailure(throwable);
			else
				this.cancelationToken.reportCallbackFailure(throwable);
		}

		private ManagedResponseStream newManagedResponseStream() {
			return new ManagedResponseStream(this.request, this.cancelationToken, this.deadline,
					this.idleTimeout, new ResponseStreamAdapter(), this::beginFinalization,
					this::failProducer, this::reportCleanupFailure);
		}

		private void copyInputStream(com.soklet.StreamingResponseBody.@NonNull InputStreamBody body,
				@NonNull ResponseStream responseStream) throws Exception {
			InputStream inputStream = responseStream.open(body.getInputStreamFactory());
			byte[] buffer = new byte[body.getBufferSizeInBytes()];
			int read;
			while ((read = inputStream.read(buffer)) >= 0) {
				this.cancelationToken.throwIfCanceled();
				if (read > 0)
					responseStream.write(ByteBuffer.wrap(buffer, 0, read));
			}
		}

		private void copyReader(com.soklet.StreamingResponseBody.@NonNull ReaderBody body,
				@NonNull ResponseStream responseStream) throws Exception {
			Reader reader = responseStream.open(body.getReaderFactory());
			CharsetEncoder encoder = body.newEncoder();
			int readSize = body.getBufferSizeInCharacters();
			// An encoder can leave a high surrogate unconsumed. Keep one extra slot when
			// callers request single-character reads so its low surrogate can arrive.
			CharBuffer charBuffer = CharBuffer.allocate(Math.max(2, readSize));
			ByteBuffer byteBuffer = ByteBuffer.allocate(Math.max(128,
					(int) Math.ceil(readSize * encoder.maxBytesPerChar())));
			while (true) {
				charBuffer.limit(charBuffer.position() + Math.min(readSize, charBuffer.remaining()));
				int read = reader.read(charBuffer);
				charBuffer.limit(charBuffer.capacity());
				if (read < 0)
					break;
				this.cancelationToken.throwIfCanceled();
				charBuffer.flip();
				encodeChars(encoder, charBuffer, byteBuffer, false, responseStream);
				charBuffer.compact();
			}
			charBuffer.flip();
			encodeChars(encoder, charBuffer, byteBuffer, true, responseStream);
			CoderResult result;
			do {
				result = encoder.flush(byteBuffer);
				writeEncodedBytes(byteBuffer, responseStream);
				if (result.isError())
					result.throwException();
			} while (result.isOverflow());
		}

		private void encodeChars(@NonNull CharsetEncoder encoder,
														 @NonNull CharBuffer charBuffer,
														 @NonNull ByteBuffer byteBuffer,
														 boolean endOfInput,
														 @NonNull ResponseStream responseStream) throws IOException, InterruptedException, StreamingResponseCanceledException, CharacterCodingException {
			CoderResult result;

			do {
				result = encoder.encode(charBuffer, byteBuffer, endOfInput);
				writeEncodedBytes(byteBuffer, responseStream);

				if (result.isError())
					result.throwException();
			} while (result.isOverflow());
		}

		private void writeEncodedBytes(@NonNull ByteBuffer byteBuffer,
																	 @NonNull ResponseStream responseStream) throws IOException, InterruptedException, StreamingResponseCanceledException {
			byteBuffer.flip();
			if (byteBuffer.hasRemaining())
				responseStream.write(byteBuffer);
			byteBuffer.clear();
		}

		private void copyPublisher(com.soklet.StreamingResponseBody.@NonNull PublisherBody body) throws Exception {
			PublisherResponseStream.copy(body, this.cancelationToken, new ResponseStreamAdapter(),
					this.reservation, this::beginFinalization, this::failProducer, this::reportCleanupFailure);
		}

		private void completeProducer() {
			// Owned finalizers start supervision before calling application cleanup.
			// Only framework bookkeeping remains here; observer dispatch has its own grace.
			Runnable releaseCallbacks;
			synchronized (this.lock) {
				if (this.closed || this.failure != null || this.completed)
					return;
				if (this.reservation != null && !this.reservation.completeProduction())
					return;

				releaseCallbacks = this.cancelationToken.complete();
				if (releaseCallbacks == null)
					return;
				this.producerDone = true;
				this.chunks.add(QueuedChunk.terminal());
				this.lock.notifyAll();
			}
			releaseCallbacks.run();
			wakeWriter();
		}

		private void fail(@NonNull StreamTerminationReason reason,
											@Nullable Throwable cause) {
			requireNonNull(reason);
			synchronized (this.lock) {
				if (this.closed || this.completed || this.failure != null)
					return;
			}
			if (this.reservation != null) {
				this.reservation.cancel(reason, cause);
				return;
			}
			applyFailure(reason, cause);
		}

		private void applyFailure(@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
			requireNonNull(reason);
			StreamingMicrohttpResponses.testHooks.beforeFailureApplied();

			StreamTerminationReason effectiveReason;
			Throwable effectiveCause;
			Runnable cancelationCallbacks;

			synchronized (this.lock) {
				if (this.completed || this.failure != null)
					return;

				effectiveReason = this.cancelationToken.getCancelationReason().orElse(reason);
				effectiveCause = this.cancelationToken.getCancelationCause().orElse(cause);
				// Preserve the reserved stream reason even when an underlying cause is
				// available.  The connection event loop distinguishes reasoned stream
				// termination from a socket write failure by this exception type.
				this.failure = new StreamingResponseCanceledException(effectiveReason,
						effectiveCause);
				cancelationCallbacks = this.cancelationToken.reserveCancelation(effectiveReason, effectiveCause);
				this.producerDone = true;
				this.lock.notifyAll();
				// Pair the interrupt with physical producer ownership, never Future completion.
				// Clearing producerThread in the same lock prevents interrupting a reused worker.
				if (this.reservation == null && !this.cancelationToken.isCompleted()
						&& this.producerThread != null && this.producerThread != Thread.currentThread())
					this.producerThread.interrupt();
			}

			StreamingMicrohttpResponses.testHooks.afterFailureReserved(() ->
					close(StreamTerminationReason.CLIENT_DISCONNECTED, null));
			cancelTimeouts();
			if (cancelationCallbacks != null) {
				if (this.reservation != null)
					this.reservation.dispatchCallbacks(cancelationCallbacks);
				else
					cancelationCallbacks.run();
			}
			notifyTerminated(effectiveReason, effectiveCause);
			wakeWriter();
		}

		private void scheduleResponseTimeoutIfNeeded() {
			Instant deadline = this.deadline;

			if (deadline == null)
				return;

			long delayMillis = Math.max(0L, Duration.between(Instant.now(), deadline).toMillis());
			testHooks.beforeResponseTimeoutScheduled();

			synchronized (this.lock) {
				if (this.timeoutsStopped)
					return;
				ScheduledFuture<?> newResponseTimeoutFuture = this.timeoutExecutorService.schedule(() ->
								fail(StreamTerminationReason.RESPONSE_TIMEOUT, null),
						delayMillis,
						TimeUnit.MILLISECONDS);
				// A scheduler can invoke a due task before returning its future. A reentrant
				// termination must cancel that future instead of publishing it after stop.
				if (this.timeoutsStopped) {
					newResponseTimeoutFuture.cancel(false);
					return;
				}
				ScheduledFuture<?> previousResponseTimeoutFuture = this.responseTimeoutFuture.getAndSet(newResponseTimeoutFuture);

				if (previousResponseTimeoutFuture != null)
					previousResponseTimeoutFuture.cancel(false);
			}
		}

		private void resetIdleTimeoutIfNeeded() {
			Duration idleTimeout = this.idleTimeout;

			if (idleTimeout == null)
				return;

			synchronized (this.lock) {
				if (this.timeoutsStopped) return;
				this.lastIdleActivityNanos = testHooks.nanoTime();
				if (this.idleTimeoutFuture.get() == null)
					scheduleIdleCheck(idleTimeoutNanos());
			}
		}

		private long idleTimeoutNanos() {
			try { return Math.max(TimeUnit.MILLISECONDS.toNanos(1), requireNonNull(this.idleTimeout).toNanos()); }
			catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
		}

		private void scheduleIdleCheck(long delayNanos) {
			ScheduledFuture<?> idleTimeoutFuture = this.timeoutExecutorService.schedule(this::checkIdleTimeout,
					delayNanos, TimeUnit.NANOSECONDS);
			if (this.timeoutsStopped)
				idleTimeoutFuture.cancel(false);
			else
				this.idleTimeoutFuture.set(idleTimeoutFuture);
		}

		private void checkIdleTimeout() {
			synchronized (this.lock) {
				if (this.timeoutsStopped) return;
				long elapsed = testHooks.nanoTime() - this.lastIdleActivityNanos;
				long timeout = idleTimeoutNanos();
				if (elapsed < timeout) {
					scheduleIdleCheck(timeout - Math.max(0L, elapsed));
					return;
				}
			}
			fail(StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null);
		}

		private void cancelTimeouts() {
			synchronized (this.lock) {
				this.timeoutsStopped = true;
				ScheduledFuture<?> responseTimeoutFuture = this.responseTimeoutFuture.getAndSet(null);
				if (responseTimeoutFuture != null)
					responseTimeoutFuture.cancel(false);
				ScheduledFuture<?> idleTimeoutFuture = this.idleTimeoutFuture.getAndSet(null);
				if (idleTimeoutFuture != null)
					idleTimeoutFuture.cancel(false);
			}
		}

		private void wakeWriter() {
			this.writeReadyCallback.run();
		}

		private void notifyTerminated(@Nullable StreamTerminationReason reason,
																	@Nullable Throwable throwable) {
			if (!this.terminationNotified.compareAndSet(false, true))
				return;

			cancelTimeouts();
			Duration streamDuration = Duration.between(this.streamStarted, Instant.now());
			Runnable notification = () -> this.terminationListener.didTerminate(
					this.streamStarted, streamDuration, reason, throwable);
			if (this.reservation != null) {
				this.reservation.dispatchTermination(notification);
				this.reservation.complete();
			} else
				notification.run();
		}

		private IOException toIOException(@NonNull Throwable throwable) {
			requireNonNull(throwable);

			if (throwable instanceof IOException ioException)
				return ioException;

			return new IOException("Streaming response failed.", throwable);
		}

		@NotThreadSafe
		private final class ResponseStreamAdapter implements ManagedResponseStream.Output {
			@Override
			public void write(@NonNull ByteBuffer byteBuffer) throws IOException, InterruptedException, StreamingResponseCanceledException {
				requireNonNull(byteBuffer);

				StreamingWritableSource.this.cancelationToken.throwIfCanceled();
				while (byteBuffer.hasRemaining()) {
					StreamingWritableSource.this.cancelationToken.throwIfCanceled();

					int payloadSize = Math.min(byteBuffer.remaining(), Math.min(StreamingWritableSource.this.chunkSizeInBytes, StreamingWritableSource.this.queueCapacityInBytes));
					byte[] payload = new byte[payloadSize];
					byteBuffer.duplicate().get(payload);
					enqueue(payload, byteBuffer);
				}
			}

			@Override
			public int stagingCapacityInBytes() {
				return Math.min(8_192, Math.min(StreamingWritableSource.this.chunkSizeInBytes,
						StreamingWritableSource.this.queueCapacityInBytes));
			}

			@Override
			public void didStageBytes() {
				// Scalar writes update activity without allocating or replacing a timer.
				if (StreamingWritableSource.this.idleTimeout != null)
					StreamingWritableSource.this.lastIdleActivityNanos = testHooks.nanoTime();
			}

			@Override
			public void flush() throws IOException, InterruptedException, StreamingResponseCanceledException {
				StreamingWritableSource.this.cancelationToken.throwIfCanceled();
				wakeWriter();
			}

			@Override
			public boolean isOpen() {
				synchronized (StreamingWritableSource.this.lock) {
					return !StreamingWritableSource.this.closed
							&& !StreamingWritableSource.this.completed
							&& StreamingWritableSource.this.failure == null
							&& !StreamingWritableSource.this.cancelationToken.isCanceled();
				}
			}

			private void enqueue(@NonNull byte[] payload, @NonNull ByteBuffer acceptedSource) throws IOException, InterruptedException, StreamingResponseCanceledException {
				requireNonNull(payload);

				if (payload.length == 0)
					return;

				synchronized (StreamingWritableSource.this.lock) {
					if (Thread.currentThread().isInterrupted())
						throw new InterruptedException("Response producer is interrupted");
					while (!StreamingWritableSource.this.closed
							&& StreamingWritableSource.this.failure == null
							&& !StreamingWritableSource.this.cancelationToken.isCanceled()
							&& (long) StreamingWritableSource.this.queuedPayloadBytes + payload.length
								> StreamingWritableSource.this.queueCapacityInBytes)
						StreamingWritableSource.this.lock.wait();

					StreamingWritableSource.this.cancelationToken.throwIfCanceled();

					if (StreamingWritableSource.this.closed)
						throw new StreamingResponseCanceledException(StreamTerminationReason.CLIENT_DISCONNECTED);

					if (StreamingWritableSource.this.failure != null)
						throw toIOException(StreamingWritableSource.this.failure);

					StreamingWritableSource.this.chunks.add(QueuedChunk.payload(payload));
					StreamingWritableSource.this.queuedPayloadBytes += payload.length;
					// Acceptance means queue ownership, not copying into a temporary
					// payload. Preserve this prefix even if a following wakeup fails.
					acceptedSource.position(acceptedSource.position() + payload.length);
					StreamingWritableSource.this.lock.notifyAll();
				}

				resetIdleTimeoutIfNeeded();
				wakeWriter();
			}
		}
	}

	@NotThreadSafe
	private static final class QueuedChunk {
		private final List<ByteBuffer> buffers;
		private final int payloadBytes;
		private final boolean terminal;
		private int bufferIndex;

		private static QueuedChunk payload(@NonNull byte[] payload) {
			requireNonNull(payload);

			byte[] header = format("%x\r\n", payload.length).getBytes(StandardCharsets.US_ASCII);
			List<ByteBuffer> buffers = new ArrayList<>(3);
			buffers.add(ByteBuffer.wrap(header));
			buffers.add(ByteBuffer.wrap(payload));
			buffers.add(ByteBuffer.wrap(StreamingWritableSource.CRLF));
			return new QueuedChunk(buffers, payload.length, false);
		}

		private static QueuedChunk terminal() {
			return new QueuedChunk(List.of(ByteBuffer.wrap(StreamingWritableSource.TERMINAL_CHUNK)), 0, true);
		}

		private QueuedChunk(@NonNull List<@NonNull ByteBuffer> buffers,
												int payloadBytes,
												boolean terminal) {
			this.buffers = requireNonNull(buffers);
			this.payloadBytes = payloadBytes;
			this.terminal = terminal;
		}

		private long writeTo(@NonNull SocketChannel socketChannel,
												 long maxBytes) throws IOException {
			requireNonNull(socketChannel);

			long totalWritten = 0L;

			while (totalWritten < maxBytes && this.bufferIndex < this.buffers.size()) {
				ByteBuffer buffer = this.buffers.get(this.bufferIndex);

				if (!buffer.hasRemaining()) {
					this.bufferIndex++;
					continue;
				}

				int originalLimit = buffer.limit();
				int maxBytesThisWrite = (int) Math.min(maxBytes - totalWritten, (long) buffer.remaining());
				buffer.limit(buffer.position() + maxBytesThisWrite);

				long written;
				try {
					written = socketChannel.write(buffer);
				} finally {
					buffer.limit(originalLimit);
				}

				totalWritten += written;

				if (written == 0)
					break;
			}

			return totalWritten;
		}

		private boolean isComplete() {
			while (this.bufferIndex < this.buffers.size() && !this.buffers.get(this.bufferIndex).hasRemaining())
				this.bufferIndex++;

			return this.bufferIndex >= this.buffers.size();
		}
	}

	@ThreadSafe
	private static final class DefaultCancelationToken implements CancelationToken {
		private static final Runnable NO_CALLBACKS = () -> {};
		private boolean canceled;
		private boolean completed;
		@Nullable
		private Set<CancelationCallbackRegistration> callbacks;
		@NonNull
		private final Consumer<Throwable> callbackFailureConsumer;
		@Nullable
		private final StreamLifecycleCoordinator.Reservation reservation;
		@Nullable
		private volatile StreamTerminationReason reason;
		@Nullable
		private volatile Throwable cause;

		private DefaultCancelationToken(@NonNull Consumer<Throwable> callbackFailureConsumer,
				StreamLifecycleCoordinator.@Nullable Reservation reservation) {
			this.callbackFailureConsumer = requireNonNull(callbackFailureConsumer);
			this.reservation = reservation;
		}

		@Override
		@NonNull
		public synchronized Boolean isCanceled() {
			boolean canceled = this.canceled
					|| this.reservation != null && this.reservation.isCanceled();
			// Production may complete while the coordinator is publishing a later
			// transport failure. Check completion after reading its cancelation state.
			return !isCompleted() && canceled;
		}

		@Override
		public void throwIfCanceled() throws StreamingResponseCanceledException {
			// Healthy output only polls monotonic status. The exceptional path uses
			// locked getters to read the complete reserved reason/cause pair.
			if (isCanceled())
				CancelationToken.super.throwIfCanceled();
		}

		@Override
		@NonNull
		public synchronized Optional<StreamTerminationReason> getCancelationReason() {
			Optional<StreamTerminationReason> reason = this.reason == null && this.reservation != null
					? this.reservation.reason() : Optional.ofNullable(this.reason);
			return isCompleted() ? Optional.empty() : reason;
		}

		@Override
		@NonNull
		public synchronized Optional<Throwable> getCancelationCause() {
			Optional<Throwable> cause = this.reason == null && this.reservation != null
					? this.reservation.cause() : Optional.ofNullable(this.cause);
			return isCompleted() ? Optional.empty() : cause;
		}

		@Override
		@NonNull
		public CallbackRegistration onCancel(@NonNull Runnable callback) {
			requireNonNull(callback);
			CancelationCallbackRegistration registration = new CancelationCallbackRegistration(callback);
			boolean runImmediately;

			synchronized (this) {
				if (isCompleted()) {
					registration.callback = null;
					return registration;
				}
				runImmediately = this.canceled;
				if (!runImmediately) {
					if (this.callbacks == null)
						this.callbacks = new LinkedHashSet<>();
					this.callbacks.add(registration);
				}
			}
			if (runImmediately)
				registration.invoke();
			return registration;
		}

		@Nullable
		private Runnable reserveCancelation(@NonNull StreamTerminationReason reason,
													 @Nullable Throwable cause) {
			requireNonNull(reason);

			if (reason == StreamTerminationReason.COMPLETED)
				throw new IllegalArgumentException("Cancelation reason cannot be COMPLETED");

			Set<CancelationCallbackRegistration> callbacksToRun;

			synchronized (this) {
				if (this.canceled || isCompleted())
					return null;

				this.reason = reason;
				this.cause = cause;
				this.canceled = true;
				// Detach in constant time; traverse only on managed callback execution.
				callbacksToRun = this.callbacks;
				this.callbacks = null;
			}
			if (callbacksToRun == null || callbacksToRun.isEmpty())
				return null;
			return () -> {
				for (CancelationCallbackRegistration registration : callbacksToRun)
					registration.invoke();
				callbacksToRun.clear();
			};
		}

		@Nullable
		private synchronized Runnable complete() {
			if (this.canceled || this.reservation != null
					&& !this.reservation.isProductionComplete() && this.reservation.isCanceled())
				return null;
			if (this.completed)
				return NO_CALLBACKS;
			this.completed = true;
			Set<CancelationCallbackRegistration> completedCallbacks = this.callbacks;
			this.callbacks = null;
			if (completedCallbacks == null || completedCallbacks.isEmpty())
				return NO_CALLBACKS;
			return () -> {
				for (CancelationCallbackRegistration registration : completedCallbacks)
					registration.callback = null;
				completedCallbacks.clear();
			};
		}

		private synchronized boolean isCompleted() {
			return this.completed || this.reservation != null && this.reservation.isProductionComplete();
		}

		private void runCallback(@NonNull Runnable callback) {
			requireNonNull(callback);

			try {
				callback.run();
			} catch (Throwable t) {
				reportCallbackFailure(t);
			}
		}

		private void reportCallbackFailure(@NonNull Throwable throwable) {
			try {
				this.callbackFailureConsumer.accept(throwable);
			} catch (Throwable ignored) {
				// Diagnostic observers cannot suppress remaining cleanup callbacks.
			}
		}

		private final class CancelationCallbackRegistration implements CallbackRegistration {
			@Nullable
			private volatile Runnable callback;

			private CancelationCallbackRegistration(@NonNull Runnable callback) {
				this.callback = callback;
			}

			@Override
			public void close() {
				synchronized (DefaultCancelationToken.this) {
					this.callback = null;
					if (DefaultCancelationToken.this.callbacks != null)
						DefaultCancelationToken.this.callbacks.remove(this);
				}
			}

			private void invoke() {
				Runnable callback;
				synchronized (DefaultCancelationToken.this) {
					callback = this.callback;
					this.callback = null;
				}
				if (callback != null)
					runCallback(callback);
			}
		}
	}

}
