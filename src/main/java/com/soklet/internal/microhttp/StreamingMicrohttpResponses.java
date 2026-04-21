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

import com.soklet.CancelationToken;
import com.soklet.ResponseStream;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamingResponseCancelationReason;
import com.soklet.StreamingResponseCanceledException;
import com.soklet.StreamingResponseContext;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Factory for streaming microhttp responses.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class StreamingMicrohttpResponses {
	private StreamingMicrohttpResponses() {
		// Utility class
	}

	@NonNull
	public static MicrohttpResponse withStreamingBody(@NonNull Integer status,
																									 @NonNull String reason,
																									 @NonNull List<@NonNull Header> headers,
																									 @NonNull StreamingResponseBody body,
																									 @NonNull ExecutorService executorService,
																									 @NonNull ScheduledExecutorService timeoutExecutorService,
																									 @NonNull Integer queueCapacityInBytes,
																									 @NonNull Integer chunkSizeInBytes,
																									 @Nullable Instant deadline,
																									 @Nullable Duration idleTimeout,
																									 @NonNull TerminationListener terminationListener,
																									 @NonNull Consumer<Throwable> cancelationCallbackFailureConsumer) {
		requireNonNull(status);
		requireNonNull(reason);
		requireNonNull(headers);
		requireNonNull(body);
		requireNonNull(executorService);
		requireNonNull(timeoutExecutorService);
		requireNonNull(queueCapacityInBytes);
		requireNonNull(chunkSizeInBytes);
		requireNonNull(terminationListener);
		requireNonNull(cancelationCallbackFailureConsumer);

		if (queueCapacityInBytes < 1)
			throw new IllegalArgumentException("Streaming queue capacity must be > 0");

		if (chunkSizeInBytes < 1)
			throw new IllegalArgumentException("Streaming chunk size must be > 0");

		return MicrohttpResponse.withStreamingBody(status, reason, headers, () -> new StreamingWritableSource(
				body,
				executorService,
				timeoutExecutorService,
				queueCapacityInBytes,
				chunkSizeInBytes,
				deadline,
				idleTimeout,
				terminationListener,
				cancelationCallbackFailureConsumer));
	}

	/**
	 * Callback invoked once when a streaming response terminates.
	 */
	@FunctionalInterface
	public interface TerminationListener {
		void didTerminate(@NonNull Duration streamDuration,
											@Nullable StreamingResponseCancelationReason cancelationReason,
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
		private final TerminationListener terminationListener;
		@NonNull
		private final DefaultCancelationToken cancelationToken;
		@NonNull
		private final StreamingResponseContext context;
		@NonNull
		private final Object lock;
		@NonNull
		private final Queue<QueuedChunk> chunks;
		@NonNull
		private final AtomicBoolean started;
		@NonNull
		private final AtomicBoolean terminationNotified;
		@NonNull
		private Runnable writeReadyCallback;
		@Nullable
		private QueuedChunk currentChunk;
		@Nullable
		private Throwable failure;
		@Nullable
		private Future<?> producerFuture;
		@Nullable
		private ScheduledFuture<?> responseTimeoutFuture;
		@Nullable
		private ScheduledFuture<?> idleTimeoutFuture;
		private boolean producerDone;
		private boolean closed;
		private boolean completed;
		private int queuedPayloadBytes;
		@NonNull
		private final Instant streamStarted;

		private StreamingWritableSource(@NonNull StreamingResponseBody body,
																		@NonNull ExecutorService executorService,
																		@NonNull ScheduledExecutorService timeoutExecutorService,
																		@NonNull Integer queueCapacityInBytes,
																		@NonNull Integer chunkSizeInBytes,
																		@Nullable Instant deadline,
																		@Nullable Duration idleTimeout,
																		@NonNull TerminationListener terminationListener,
																		@NonNull Consumer<Throwable> cancelationCallbackFailureConsumer) {
			this.body = requireNonNull(body);
			this.executorService = requireNonNull(executorService);
			this.timeoutExecutorService = requireNonNull(timeoutExecutorService);
			this.queueCapacityInBytes = requireNonNull(queueCapacityInBytes);
			this.chunkSizeInBytes = requireNonNull(chunkSizeInBytes);
			this.deadline = deadline;
			this.idleTimeout = idleTimeout;
			this.terminationListener = requireNonNull(terminationListener);
			this.cancelationToken = new DefaultCancelationToken(cancelationCallbackFailureConsumer);
			this.context = new DefaultStreamingResponseContext(this.cancelationToken, deadline, idleTimeout);
			this.lock = new Object();
			this.chunks = new ArrayDeque<>();
			this.started = new AtomicBoolean(false);
			this.terminationNotified = new AtomicBoolean(false);
			this.writeReadyCallback = () -> {
				// No-op until the event loop provides a wakeup callback.
			};
			this.streamStarted = Instant.now();
		}

		@Override
		public void start() throws IOException {
			if (!this.started.compareAndSet(false, true))
				return;

			scheduleResponseTimeoutIfNeeded();
			resetIdleTimeoutIfNeeded();

			try {
				this.producerFuture = this.executorService.submit(this::runProducer);
			} catch (RejectedExecutionException e) {
				fail(StreamingResponseCancelationReason.PRODUCER_FAILED, e);
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

					synchronized (this.lock) {
						if (chunk.payloadBytes > 0)
							this.queuedPayloadBytes -= chunk.payloadBytes;

						this.currentChunk = null;

						if (terminal)
							this.completed = true;

						this.lock.notifyAll();
					}

					if (terminal)
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
			cancelTimeouts();

			StreamingResponseCancelationReason reason = null;
			Throwable cause = null;

			synchronized (this.lock) {
				if (this.closed)
					return;

				this.closed = true;

				if (!this.completed) {
					reason = this.cancelationToken.getCancelationReason()
							.orElse(StreamingResponseCancelationReason.CLIENT_DISCONNECTED);
					cause = this.cancelationToken.getCancelationCause().orElse(null);
					this.cancelationToken.cancel(reason, cause);
				}

				this.chunks.clear();
				this.currentChunk = null;
				this.lock.notifyAll();
			}

			Future<?> producerFuture = this.producerFuture;

			if (producerFuture != null && !producerFuture.isDone())
				producerFuture.cancel(true);

			if (reason != null)
				notifyTerminated(reason, cause);
		}

		private void runProducer() {
			try {
				StreamingResponseBody body = this.body;

				if (body instanceof StreamingResponseBody.WriterBody writerBody) {
					writerBody.getWriter().writeTo(new ResponseStreamAdapter(), this.context);
				} else if (body instanceof StreamingResponseBody.InputStreamBody inputStreamBody) {
					copyInputStream(inputStreamBody);
				} else if (body instanceof StreamingResponseBody.ReaderBody readerBody) {
					copyReader(readerBody);
				} else if (body instanceof StreamingResponseBody.PublisherBody publisherBody) {
					copyPublisher(publisherBody);
				} else {
					throw new IllegalStateException(format("Unsupported streaming response body type: %s", body.getClass().getName()));
				}

				completeProducer();
			} catch (StreamingResponseCanceledException e) {
				fail(e.getCancelationReason(), e.getCancelationCause().orElse(null));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail(this.cancelationToken.getCancelationReason()
						.orElse(StreamingResponseCancelationReason.CLIENT_DISCONNECTED), e);
			} catch (Throwable t) {
				fail(StreamingResponseCancelationReason.PRODUCER_FAILED, t);
			}
		}

		private void copyInputStream(com.soklet.StreamingResponseBody.@NonNull InputStreamBody body) throws Exception {
			requireNonNull(body);

			try (InputStream inputStream = requireNonNull(body.getInputStreamSupplier().get())) {
				byte[] buffer = new byte[body.getBufferSizeInBytes()];
				int read;
				ResponseStreamAdapter responseStream = new ResponseStreamAdapter();

				while ((read = inputStream.read(buffer)) >= 0) {
					this.context.throwIfCanceled();

					if (read > 0)
						responseStream.write(Arrays.copyOf(buffer, read));
				}
			}
		}

		private void copyReader(com.soklet.StreamingResponseBody.@NonNull ReaderBody body) throws Exception {
			requireNonNull(body);

			try (Reader reader = requireNonNull(body.getReaderSupplier().get())) {
				CharsetEncoder encoder = body.newEncoder();
				CharBuffer charBuffer = CharBuffer.allocate(body.getBufferSizeInCharacters());
				ByteBuffer byteBuffer = ByteBuffer.allocate(Math.max(128, (int) Math.ceil(body.getBufferSizeInCharacters() * encoder.maxBytesPerChar())));
				ResponseStreamAdapter responseStream = new ResponseStreamAdapter();

				while (reader.read(charBuffer) >= 0) {
					this.context.throwIfCanceled();
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
		}

		private void encodeChars(@NonNull CharsetEncoder encoder,
														 @NonNull CharBuffer charBuffer,
														 @NonNull ByteBuffer byteBuffer,
														 boolean endOfInput,
														 @NonNull ResponseStreamAdapter responseStream) throws IOException, InterruptedException, StreamingResponseCanceledException, CharacterCodingException {
			CoderResult result;

			do {
				result = encoder.encode(charBuffer, byteBuffer, endOfInput);
				writeEncodedBytes(byteBuffer, responseStream);

				if (result.isError())
					result.throwException();
			} while (result.isOverflow());
		}

		private void writeEncodedBytes(@NonNull ByteBuffer byteBuffer,
																	 @NonNull ResponseStreamAdapter responseStream) throws IOException, InterruptedException, StreamingResponseCanceledException {
			byteBuffer.flip();
			if (byteBuffer.hasRemaining())
				responseStream.write(byteBuffer);
			byteBuffer.clear();
		}

		private void copyPublisher(com.soklet.StreamingResponseBody.@NonNull PublisherBody body) throws Exception {
			requireNonNull(body);

			CountDownLatch completed = new CountDownLatch(1);
			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();
			ResponseStreamAdapter responseStream = new ResponseStreamAdapter();

			body.getPublisher().subscribe(new Flow.Subscriber<>() {
				@Override
				public void onSubscribe(Flow.Subscription subscription) {
					requireNonNull(subscription);

					if (!subscriptionRef.compareAndSet(null, subscription)) {
						subscription.cancel();
						return;
					}

					subscription.request(1L);
				}

				@Override
				public void onNext(ByteBuffer item) {
					Flow.Subscription subscription = subscriptionRef.get();

					try {
						context.throwIfCanceled();
						responseStream.write(requireNonNull(item));
						context.throwIfCanceled();
					} catch (Throwable t) {
						failure.compareAndSet(null, t);

						if (subscription != null)
							subscription.cancel();

						completed.countDown();
						return;
					}

					if (subscription != null)
						subscription.request(1L);
				}

				@Override
				public void onError(Throwable throwable) {
					failure.compareAndSet(null, throwable == null
							? new IllegalStateException("Publisher failed without an error")
							: throwable);
					completed.countDown();
				}

				@Override
				public void onComplete() {
					completed.countDown();
				}
			});

			while (!completed.await(100L, TimeUnit.MILLISECONDS))
				this.context.throwIfCanceled();

			Throwable throwable = failure.get();

			if (throwable != null) {
				if (throwable instanceof Exception exception)
					throw exception;

				if (throwable instanceof Error error)
					throw error;

				throw new RuntimeException(throwable);
			}
		}

		private void completeProducer() {
			synchronized (this.lock) {
				if (this.closed || this.failure != null || this.completed)
					return;

				this.producerDone = true;
				this.chunks.add(QueuedChunk.terminal());
				this.lock.notifyAll();
			}

			wakeWriter();
		}

		private void fail(@NonNull StreamingResponseCancelationReason reason,
											@Nullable Throwable cause) {
			requireNonNull(reason);

			this.cancelationToken.cancel(reason, cause);
			cancelTimeouts();

			synchronized (this.lock) {
				if (this.completed || this.failure != null)
					return;

				this.failure = cause == null ? new StreamingResponseCanceledException(reason) : cause;
				this.producerDone = true;
				this.lock.notifyAll();
			}

			notifyTerminated(reason, cause);
			wakeWriter();
		}

		private void scheduleResponseTimeoutIfNeeded() {
			Instant deadline = this.deadline;

			if (deadline == null)
				return;

			long delayMillis = Math.max(0L, Duration.between(Instant.now(), deadline).toMillis());

			this.responseTimeoutFuture = this.timeoutExecutorService.schedule(() ->
							fail(StreamingResponseCancelationReason.RESPONSE_TIMEOUT, null),
					delayMillis,
					TimeUnit.MILLISECONDS);
		}

		private void resetIdleTimeoutIfNeeded() {
			Duration idleTimeout = this.idleTimeout;

			if (idleTimeout == null)
				return;

			ScheduledFuture<?> idleTimeoutFuture = this.idleTimeoutFuture;

			if (idleTimeoutFuture != null)
				idleTimeoutFuture.cancel(false);

			this.idleTimeoutFuture = this.timeoutExecutorService.schedule(() ->
							fail(StreamingResponseCancelationReason.RESPONSE_IDLE_TIMEOUT, null),
					Math.max(1L, idleTimeout.toMillis()),
					TimeUnit.MILLISECONDS);
		}

		private void cancelTimeouts() {
			ScheduledFuture<?> responseTimeoutFuture = this.responseTimeoutFuture;

			if (responseTimeoutFuture != null)
				responseTimeoutFuture.cancel(false);

			ScheduledFuture<?> idleTimeoutFuture = this.idleTimeoutFuture;

			if (idleTimeoutFuture != null)
				idleTimeoutFuture.cancel(false);
		}

		private void wakeWriter() {
			this.writeReadyCallback.run();
		}

		private void notifyTerminated(@Nullable StreamingResponseCancelationReason reason,
																	@Nullable Throwable throwable) {
			if (!this.terminationNotified.compareAndSet(false, true))
				return;

			cancelTimeouts();
			this.terminationListener.didTerminate(Duration.between(this.streamStarted, Instant.now()), reason, throwable);
		}

		private IOException toIOException(@NonNull Throwable throwable) {
			requireNonNull(throwable);

			if (throwable instanceof IOException ioException)
				return ioException;

			return new IOException("Streaming response failed.", throwable);
		}

		@NotThreadSafe
		private final class ResponseStreamAdapter implements ResponseStream {
			@Override
			public void write(@NonNull byte[] bytes) throws IOException, InterruptedException, StreamingResponseCanceledException {
				requireNonNull(bytes);
				write(ByteBuffer.wrap(bytes));
			}

			@Override
			public void write(@NonNull ByteBuffer byteBuffer) throws IOException, InterruptedException, StreamingResponseCanceledException {
				requireNonNull(byteBuffer);

				ByteBuffer source = byteBuffer.asReadOnlyBuffer();

				while (source.hasRemaining()) {
					StreamingWritableSource.this.context.throwIfCanceled();

					int payloadSize = Math.min(source.remaining(), Math.min(StreamingWritableSource.this.chunkSizeInBytes, StreamingWritableSource.this.queueCapacityInBytes));
					byte[] payload = new byte[payloadSize];
					source.get(payload);
					enqueue(payload);
				}
			}

			@Override
			public void flush() throws IOException, InterruptedException, StreamingResponseCanceledException {
				StreamingWritableSource.this.context.throwIfCanceled();
				wakeWriter();
			}

			@Override
			@NonNull
			public Boolean isOpen() {
				synchronized (StreamingWritableSource.this.lock) {
					return !StreamingWritableSource.this.closed
							&& !StreamingWritableSource.this.completed
							&& StreamingWritableSource.this.failure == null
							&& !StreamingWritableSource.this.cancelationToken.isCanceled();
				}
			}

			private void enqueue(@NonNull byte[] payload) throws IOException, InterruptedException, StreamingResponseCanceledException {
				requireNonNull(payload);

				if (payload.length == 0)
					return;

				synchronized (StreamingWritableSource.this.lock) {
					while (!StreamingWritableSource.this.closed
							&& StreamingWritableSource.this.failure == null
							&& !StreamingWritableSource.this.cancelationToken.isCanceled()
							&& StreamingWritableSource.this.queuedPayloadBytes + payload.length > StreamingWritableSource.this.queueCapacityInBytes)
						StreamingWritableSource.this.lock.wait();

					StreamingWritableSource.this.context.throwIfCanceled();

					if (StreamingWritableSource.this.closed)
						throw new StreamingResponseCanceledException(StreamingResponseCancelationReason.CLIENT_DISCONNECTED);

					if (StreamingWritableSource.this.failure != null)
						throw toIOException(StreamingWritableSource.this.failure);

					StreamingWritableSource.this.chunks.add(QueuedChunk.payload(payload));
					StreamingWritableSource.this.queuedPayloadBytes += payload.length;
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
		@NonNull
		private final AtomicBoolean canceled;
		@NonNull
		private final CopyOnWriteArrayList<Runnable> callbacks;
		@NonNull
		private final Consumer<Throwable> callbackFailureConsumer;
		@Nullable
		private volatile StreamingResponseCancelationReason reason;
		@Nullable
		private volatile Throwable cause;

		private DefaultCancelationToken(@NonNull Consumer<Throwable> callbackFailureConsumer) {
			this.canceled = new AtomicBoolean(false);
			this.callbacks = new CopyOnWriteArrayList<>();
			this.callbackFailureConsumer = requireNonNull(callbackFailureConsumer);
		}

		@Override
		@NonNull
		public Boolean isCanceled() {
			return this.canceled.get();
		}

		@Override
		@NonNull
		public Optional<StreamingResponseCancelationReason> getCancelationReason() {
			return Optional.ofNullable(this.reason);
		}

		@Override
		@NonNull
		public Optional<Throwable> getCancelationCause() {
			return Optional.ofNullable(this.cause);
		}

		@Override
		@NonNull
		public AutoCloseable onCancel(@NonNull Runnable callback) {
			requireNonNull(callback);

			if (isCanceled()) {
				runCallback(callback);
				return () -> {
					// No-op
				};
			}

			this.callbacks.add(callback);

			if (isCanceled() && this.callbacks.remove(callback))
				runCallback(callback);

			return () -> this.callbacks.remove(callback);
		}

		private boolean cancel(@NonNull StreamingResponseCancelationReason reason,
													 @Nullable Throwable cause) {
			requireNonNull(reason);

			if (!this.canceled.compareAndSet(false, true))
				return false;

			this.reason = reason;
			this.cause = cause;

			for (Runnable callback : this.callbacks)
				runCallback(callback);

			this.callbacks.clear();
			return true;
		}

		private void runCallback(@NonNull Runnable callback) {
			requireNonNull(callback);

			try {
				callback.run();
			} catch (Throwable t) {
				this.callbackFailureConsumer.accept(t);
			}
		}
	}

	@ThreadSafe
	private static final class DefaultStreamingResponseContext implements StreamingResponseContext {
		@NonNull
		private final CancelationToken cancelationToken;
		@Nullable
		private final Instant deadline;
		@Nullable
		private final Duration idleTimeout;

		private DefaultStreamingResponseContext(@NonNull CancelationToken cancelationToken,
																						@Nullable Instant deadline,
																						@Nullable Duration idleTimeout) {
			this.cancelationToken = requireNonNull(cancelationToken);
			this.deadline = deadline;
			this.idleTimeout = idleTimeout;
		}

		@Override
		@NonNull
		public CancelationToken getCancelationToken() {
			return this.cancelationToken;
		}

		@Override
		@NonNull
		public Optional<Instant> getDeadline() {
			return Optional.ofNullable(this.deadline);
		}

		@Override
		@NonNull
		public Optional<Duration> getIdleTimeout() {
			return Optional.ofNullable(this.idleTimeout);
		}
	}
}
