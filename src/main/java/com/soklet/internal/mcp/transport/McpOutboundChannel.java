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

package com.soklet.internal.mcp.transport;

import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseCanceledException;
import com.soklet.internal.microhttp.WritableSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.LongSupplier;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Bounded, thread-safe outbound response channel used by the MCP transport
 * containment runtime.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpOutboundChannel {
	public enum OfferResult {
		ACCEPTED,
		FULL,
		TOO_LARGE,
		CLOSED
	}

	public interface Listener {
		void didWrite(long byteCount, long timestampNanos);

		void didApplyBackpressure();

		void didTerminate(@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause);
	}

	public record Snapshot(int frameCapacity, int byteCapacity, int terminalByteCapacity,
			int bufferedFrames, int bufferedBytes, int terminalBytes,
			int maximumObservedBufferedFrames, int maximumObservedBufferedBytes,
			boolean terminalReserved, boolean started, boolean closed) {
	}

	private static final byte @NonNull [] CRLF =
			"\r\n".getBytes(StandardCharsets.US_ASCII);
	private static final byte @NonNull [] TERMINAL_CHUNK =
			"0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

	@NonNull
	private final Object lock;
	private final int frameCapacity;
	private final int byteCapacity;
	private final int terminalByteCapacity;
	@NonNull
	private final LongSupplier nanoTimeSupplier;
	@NonNull
	private final Listener listener;
	@NonNull
	private final Queue<@NonNull Chunk> chunks;
	@NonNull
	private final Set<@NonNull Object> pendingCoalescingKeys;
	@NonNull
	private Runnable writeReadyCallback;
	private @Nullable Chunk currentChunk;
	private @Nullable Chunk terminalChunk;
	private @Nullable IOException failure;
	private int bufferedFrames;
	private int bufferedBytes;
	private int terminalBytes;
	private int maximumObservedBufferedFrames;
	private int maximumObservedBufferedBytes;
	private long lastWriteAtNanos;
	private boolean callbackInstalled;
	private boolean wakePending;
	private boolean terminalReserved;
	private boolean terminalWritten;
	private boolean started;
	private boolean closed;
	private boolean terminationNotified;

	public McpOutboundChannel(int frameCapacity, int byteCapacity, int terminalByteCapacity,
			@NonNull LongSupplier nanoTimeSupplier, @NonNull Listener listener) {
		if (frameCapacity < 1)
			throw new IllegalArgumentException("Outbound frame capacity must be > 0.");

		if (byteCapacity < 1)
			throw new IllegalArgumentException("Outbound byte capacity must be > 0.");

		if (terminalByteCapacity < 1)
			throw new IllegalArgumentException("Terminal byte capacity must be > 0.");

		this.lock = new Object();
		this.frameCapacity = frameCapacity;
		this.byteCapacity = byteCapacity;
		this.terminalByteCapacity = terminalByteCapacity;
		this.nanoTimeSupplier = requireNonNull(nanoTimeSupplier);
		this.listener = requireNonNull(listener);
		this.chunks = new ArrayDeque<>(frameCapacity);
		this.pendingCoalescingKeys = new HashSet<>(frameCapacity);
		this.writeReadyCallback = McpOutboundChannel::noOp;
	}

	public void enqueue(byte @NonNull [] payload) throws InterruptedException {
		requireNonNull(payload);

		if (payload.length == 0)
			throw new IllegalArgumentException("Outbound frames must not be empty.");

		byte[] ownedPayload = Arrays.copyOf(payload, payload.length);
		boolean observedBackpressure = false;
		Runnable wake;

		synchronized (lock) {
			if (ownedPayload.length > byteCapacity)
				throw new IllegalArgumentException("Outbound frame exceeds the configured byte capacity.");

			while (!closed && failure == null && !terminalReserved && !hasRegularCapacity(ownedPayload.length)) {
				if (!observedBackpressure) {
					observedBackpressure = true;
					listener.didApplyBackpressure();
				}

				lock.wait();
			}

			if (closed || failure != null || terminalReserved)
				throw new InterruptedException("The MCP response stream is closed.");

			addRegularChunk(ownedPayload, null);
			wake = reserveWakeIfNeeded();
		}

		wake.run();
	}

	@NonNull
	public OfferResult offer(byte @NonNull [] payload) {
		return offer(payload, null);
	}

	/**
	 * Offers a regular frame while suppressing another frame with the same key
	 * until the pending or in-flight frame has been fully written.
	 *
	 * @param payload frame payload
	 * @param coalescingKey semantic duplicate key
	 * @return {@link OfferResult#ACCEPTED} when queued or already represented
	 */
	@NonNull
	public OfferResult offerCoalescing(byte @NonNull [] payload,
			@NonNull Object coalescingKey) {
		return offer(payload, requireNonNull(coalescingKey));
	}

	@NonNull
	private OfferResult offer(byte @NonNull [] payload,
			@Nullable Object coalescingKey) {
		requireNonNull(payload);

		if (payload.length == 0)
			return OfferResult.TOO_LARGE;

		byte[] ownedPayload = Arrays.copyOf(payload, payload.length);
		Runnable wake;

		synchronized (lock) {
			if (closed || failure != null || terminalReserved)
				return OfferResult.CLOSED;

			if (coalescingKey != null
					&& pendingCoalescingKeys.contains(coalescingKey))
				return OfferResult.ACCEPTED;

			if (ownedPayload.length > byteCapacity)
				return OfferResult.TOO_LARGE;

			if (!hasRegularCapacity(ownedPayload.length))
				return OfferResult.FULL;

			addRegularChunk(ownedPayload, coalescingKey);
			wake = reserveWakeIfNeeded();
		}

		wake.run();
		return OfferResult.ACCEPTED;
	}

	public boolean complete(byte @NonNull [] terminalPayload) {
		requireNonNull(terminalPayload);
		Runnable wake;

		synchronized (lock) {
			if (closed || failure != null || terminalReserved)
				return false;

			if (terminalPayload.length == 0)
				throw new IllegalArgumentException("Terminal frames must not be empty.");

			if (terminalPayload.length > terminalByteCapacity)
				throw new IllegalArgumentException("Terminal frame exceeds the configured terminal byte capacity.");

			byte[] ownedPayload = Arrays.copyOf(terminalPayload, terminalPayload.length);
			terminalReserved = true;
			terminalBytes = ownedPayload.length;
			terminalChunk = Chunk.terminal(ownedPayload);
			wake = reserveWakeIfNeeded();
		}

		wake.run();
		return true;
	}

	public boolean fail(@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		requireNonNull(reason);
		Runnable wake;
		boolean notify;

		synchronized (lock) {
			if (closed || terminalWritten || failure != null)
				return false;

			reserveFailure(reason, cause);
			wake = reserveWakeIfNeeded();
			notify = reserveTerminationNotification();
		}

		finishFailure(reason, cause, wake, notify);
		return true;
	}

	public boolean failIfDeadlineExpired(long nowNanos, long deadlineNanos,
			@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
		requireNonNull(reason);
		Runnable wake;
		boolean notify;

		synchronized (lock) {
			if (closed || terminalWritten || failure != null
					|| nowNanos - deadlineNanos < 0L)
				return false;

			reserveFailure(reason, cause);
			wake = reserveWakeIfNeeded();
			notify = reserveTerminationNotification();
		}

		finishFailure(reason, cause, wake, notify);
		return true;
	}

	public boolean failIfWriteIdleExpired(long nowNanos, long timeoutNanos,
			@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
		if (timeoutNanos <= 0L)
			throw new IllegalArgumentException("Write-idle timeout must be positive.");
		requireNonNull(reason);
		Runnable wake;
		boolean notify;

		synchronized (lock) {
			if (!started || closed || terminalWritten || failure != null
					|| nowNanos - saturatingAdd(lastWriteAtNanos, timeoutNanos) < 0L)
				return false;

			reserveFailure(reason, cause);
			wake = reserveWakeIfNeeded();
			notify = reserveTerminationNotification();
		}

		finishFailure(reason, cause, wake, notify);
		return true;
	}

	public long responseWriteIdleDeadlineNanos(long timeoutNanos) {
		synchronized (lock) {
			if (!started || closed || terminalWritten || failure != null)
				return Long.MAX_VALUE;

			return saturatingAdd(lastWriteAtNanos, timeoutNanos);
		}
	}

	@NonNull
	public Snapshot snapshot() {
		synchronized (lock) {
			return new Snapshot(
					frameCapacity,
					byteCapacity,
					terminalByteCapacity,
					bufferedFrames,
					bufferedBytes,
					terminalBytes,
					maximumObservedBufferedFrames,
					maximumObservedBufferedBytes,
					terminalReserved,
					started,
					closed);
		}
	}

	/**
	 * Reports whether the complete terminal chunk has reached the socket.
	 *
	 * @return {@code true} after the terminal chunk is fully written
	 */
	public boolean isTerminalWritten() {
		synchronized (lock) {
			return terminalWritten;
		}
	}

	/**
	 * Returns a fresh Microhttp body-source facade backed by this channel.
	 *
	 * <p>Each invocation returns a distinct facade so a Microhttp response-body
	 * supplier can satisfy its fresh-source contract. All facades delegate to
	 * this channel's single thread-safe lifecycle.</p>
	 *
	 * @return a fresh writable-source facade
	 */
	@NonNull
	public WritableSource newWritableSource() {
		return new WritableSourceFacade();
	}

	private void start() {
		Runnable wake;

		synchronized (lock) {
			if (started)
				return;

			started = true;
			lastWriteAtNanos = nanoTimeSupplier.getAsLong();
			wake = reserveWakeIfNeeded();
		}

		wake.run();
	}

	private void writeReadyCallback(@Nullable Runnable callback) {
		Runnable wake;

		synchronized (lock) {
			writeReadyCallback = callback == null ? McpOutboundChannel::noOp : callback;
			callbackInstalled = callback != null;
			wake = reserveWakeIfNeeded();
		}

		wake.run();
	}

	private long writeTo(@NonNull SocketChannel socketChannel,
			long maximumBytes) throws IOException {
		requireNonNull(socketChannel);

		if (maximumBytes <= 0L)
			return 0L;

		long written = 0L;
		boolean notifyCompleted = false;
		long writeTimestamp = 0L;

		synchronized (lock) {
			wakePending = false;

			if (failure != null)
				throw failure;

			while (written < maximumBytes) {
				Chunk chunk = currentChunk;

				if (chunk == null) {
					chunk = chunks.poll();

					if (chunk == null && terminalChunk != null) {
						chunk = terminalChunk;
						terminalChunk = null;
					}

					currentChunk = chunk;
				}

				if (chunk == null)
					break;

				long chunkBytes = chunk.writeTo(socketChannel, maximumBytes - written);
				written += chunkBytes;

				if (chunk.isComplete()) {
					currentChunk = null;

					if (chunk.regularPayloadBytes > 0) {
						bufferedFrames--;
						bufferedBytes -= chunk.regularPayloadBytes;
						if (chunk.coalescingKey != null)
							pendingCoalescingKeys.remove(chunk.coalescingKey);
						lock.notifyAll();
					}

					if (chunk.terminal) {
						terminalWritten = true;
						terminalBytes = 0;
						notifyCompleted = reserveTerminationNotification();
					}

					if (chunkBytes == 0L)
						continue;
				}

				if (chunkBytes == 0L)
					break;
			}

			if (written > 0L) {
				writeTimestamp = nanoTimeSupplier.getAsLong();
				lastWriteAtNanos = writeTimestamp;
			}
		}

		if (written > 0L)
			listener.didWrite(written, writeTimestamp);

		if (notifyCompleted)
			listener.didTerminate(StreamTerminationReason.COMPLETED, null);

		return written;
	}

	private boolean hasRemaining() {
		synchronized (lock) {
			return failure != null
					|| currentChunk != null
					|| !chunks.isEmpty()
					|| terminalChunk != null
					|| (!closed && !terminalWritten);
		}
	}

	private boolean isReadyToWrite() {
		synchronized (lock) {
			return failure != null || currentChunk != null || !chunks.isEmpty() || terminalChunk != null;
		}
	}

	private void close() {
		close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
	}

	public void close(@Nullable StreamTerminationReason reason,
			@Nullable Throwable cause) {
		@NonNull StreamTerminationReason effectiveReason = reason == null
				? StreamTerminationReason.CLIENT_DISCONNECTED
				: reason;
		boolean notify;
		Runnable wake;

		synchronized (lock) {
			if (closed)
				return;

			closed = true;
			clearBufferedData();
			// Closing an idle streaming source changes hasRemaining() from true to
			// false without making the source write-ready.  Wake the installed writer
			// explicitly so it can observe completion instead of waiting until a later
			// force-phase selector wakeup.
			wake = reserveTerminalWakeIfNeeded();
			notify = reserveTerminationNotification();
			lock.notifyAll();
		}

		try {
			if (notify)
				listener.didTerminate(terminalWritten
						? StreamTerminationReason.COMPLETED : effectiveReason, cause);
		} finally {
			wake.run();
		}
	}

	private final class WritableSourceFacade implements WritableSource {
		@Override
		public void start() {
			McpOutboundChannel.this.start();
		}

		@Override
		public void writeReadyCallback(@Nullable Runnable callback) {
			McpOutboundChannel.this.writeReadyCallback(callback);
		}

		@Override
		public long writeTo(@NonNull SocketChannel socketChannel,
				long maximumBytes) throws IOException {
			return McpOutboundChannel.this.writeTo(socketChannel, maximumBytes);
		}

		@Override
		public boolean hasRemaining() {
			return McpOutboundChannel.this.hasRemaining();
		}

		@Override
		public boolean isReadyToWrite() {
			return McpOutboundChannel.this.isReadyToWrite();
		}

		@Override
		public void close() {
			McpOutboundChannel.this.close();
		}

		@Override
		public void close(@Nullable StreamTerminationReason reason, @Nullable Throwable cause) {
			McpOutboundChannel.this.close(reason, cause);
		}
	}

	private boolean hasRegularCapacity(int payloadBytes) {
		return bufferedFrames < frameCapacity && bufferedBytes <= byteCapacity - payloadBytes;
	}

	private void addRegularChunk(byte @NonNull [] payload,
			@Nullable Object coalescingKey) {
		chunks.add(Chunk.payload(payload, coalescingKey));
		if (coalescingKey != null)
			pendingCoalescingKeys.add(coalescingKey);
		bufferedFrames++;
		bufferedBytes += payload.length;
		maximumObservedBufferedFrames = Math.max(maximumObservedBufferedFrames, bufferedFrames);
		maximumObservedBufferedBytes = Math.max(maximumObservedBufferedBytes, bufferedBytes);
	}

	@NonNull
	private Runnable reserveWakeIfNeeded() {
		if (!callbackInstalled || wakePending || !isReadyToWriteUnderLock())
			return McpOutboundChannel::noOp;

		wakePending = true;
		return writeReadyCallback;
	}

	private Runnable reserveTerminalWakeIfNeeded() {
		if (!callbackInstalled || wakePending)
			return McpOutboundChannel::noOp;

		wakePending = true;
		return writeReadyCallback;
	}

	private static void noOp() {
		// No-op
	}

	private boolean isReadyToWriteUnderLock() {
		return failure != null || currentChunk != null || !chunks.isEmpty() || terminalChunk != null;
	}

	private boolean reserveTerminationNotification() {
		if (terminationNotified)
			return false;

		terminationNotified = true;
		return true;
	}

	private void reserveFailure(@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		failure = new StreamingResponseCanceledException(reason, cause);
		terminalReserved = true;
		clearBufferedData();
		lock.notifyAll();
	}

	private void finishFailure(@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause, @NonNull Runnable wake, boolean notify) {
		if (notify)
			listener.didTerminate(reason, cause);
		wake.run();
	}

	private void clearBufferedData() {
		chunks.clear();
		pendingCoalescingKeys.clear();
		currentChunk = null;
		terminalChunk = null;
		bufferedFrames = 0;
		bufferedBytes = 0;
		terminalBytes = 0;
	}

	private static long saturatingAdd(long left, long right) {
		long result = left + right;

		if (((left ^ result) & (right ^ result)) < 0)
			return Long.MAX_VALUE;

		return result;
	}

	private static final class Chunk {
		@NonNull
		private final List<@NonNull ByteBuffer> buffers;
		private final int regularPayloadBytes;
		private final boolean terminal;
		private final @Nullable Object coalescingKey;
		private int bufferIndex;

		private Chunk(@NonNull List<@NonNull ByteBuffer> buffers,
				int regularPayloadBytes, boolean terminal,
				@Nullable Object coalescingKey) {
			this.buffers = requireNonNull(buffers);
			this.regularPayloadBytes = regularPayloadBytes;
			this.terminal = terminal;
			this.coalescingKey = coalescingKey;
		}

		@NonNull
		private static Chunk payload(byte @NonNull [] payload,
				@Nullable Object coalescingKey) {
			return new Chunk(framedPayload(payload), payload.length, false,
					coalescingKey);
		}

		@NonNull
		private static Chunk terminal(byte @NonNull [] payload) {
			List<@NonNull ByteBuffer> buffers = framedPayload(payload);
			buffers.add(ByteBuffer.wrap(TERMINAL_CHUNK));
			return new Chunk(buffers, 0, true, null);
		}

		@NonNull
		private static List<@NonNull ByteBuffer> framedPayload(
				byte @NonNull [] payload) {
			byte[] header = format("%x\r\n", payload.length).getBytes(StandardCharsets.US_ASCII);
			List<@NonNull ByteBuffer> buffers = new ArrayList<>(3);
			buffers.add(ByteBuffer.wrap(header));
			buffers.add(ByteBuffer.wrap(payload));
			buffers.add(ByteBuffer.wrap(CRLF));
			return buffers;
		}

		private long writeTo(@NonNull SocketChannel socketChannel,
				long maximumBytes) throws IOException {
			long written = 0L;

			while (written < maximumBytes && bufferIndex < buffers.size()) {
				ByteBuffer buffer = buffers.get(bufferIndex);

				if (!buffer.hasRemaining()) {
					bufferIndex++;
					continue;
				}

				int originalLimit = buffer.limit();
				int writeSize = (int) Math.min(maximumBytes - written, (long) buffer.remaining());
				buffer.limit(buffer.position() + writeSize);

				long currentWrite;
				try {
					currentWrite = socketChannel.write(buffer);
				} finally {
					buffer.limit(originalLimit);
				}

				written += currentWrite;

				if (currentWrite == 0L)
					break;
			}

			return written;
		}

		private boolean isComplete() {
			while (bufferIndex < buffers.size() && !buffers.get(bufferIndex).hasRemaining())
				bufferIndex++;

			return bufferIndex >= buffers.size();
		}
	}
}
