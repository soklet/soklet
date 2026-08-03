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
import com.soklet.internal.microhttp.WritableSource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

final class McpOutboundChannel implements WritableSource {
	enum OfferResult {
		ACCEPTED,
		FULL,
		TOO_LARGE,
		CLOSED
	}

	interface Listener {
		void didWrite(long byteCount, long timestampNanos);

		void didApplyBackpressure();

		void didTerminate(StreamTerminationReason reason, @Nullable Throwable cause);
	}

	record Snapshot(int frameCapacity, int byteCapacity, int terminalByteCapacity,
			int bufferedFrames, int bufferedBytes, int terminalBytes,
			int maximumObservedBufferedFrames, int maximumObservedBufferedBytes,
			boolean terminalReserved, boolean started, boolean closed) {
	}

	private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] TERMINAL_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

	private final Object lock;
	private final int frameCapacity;
	private final int byteCapacity;
	private final int terminalByteCapacity;
	private final McpMonotonicClock clock;
	private final Listener listener;
	private final Queue<Chunk> chunks;
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

	McpOutboundChannel(int frameCapacity, int byteCapacity, int terminalByteCapacity,
			McpMonotonicClock clock, Listener listener) {
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
		this.clock = requireNonNull(clock);
		this.listener = requireNonNull(listener);
		this.chunks = new ArrayDeque<>(frameCapacity);
		this.writeReadyCallback = McpOutboundChannel::noOp;
	}

	void enqueue(byte[] payload) throws InterruptedException {
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

			addRegularChunk(ownedPayload);
			wake = reserveWakeIfNeeded();
		}

		wake.run();
	}

	OfferResult offer(byte[] payload) {
		requireNonNull(payload);

		if (payload.length == 0)
			return OfferResult.TOO_LARGE;

		byte[] ownedPayload = Arrays.copyOf(payload, payload.length);
		Runnable wake;

		synchronized (lock) {
			if (closed || failure != null || terminalReserved)
				return OfferResult.CLOSED;

			if (ownedPayload.length > byteCapacity)
				return OfferResult.TOO_LARGE;

			if (!hasRegularCapacity(ownedPayload.length))
				return OfferResult.FULL;

			addRegularChunk(ownedPayload);
			wake = reserveWakeIfNeeded();
		}

		wake.run();
		return OfferResult.ACCEPTED;
	}

	boolean complete(byte[] terminalPayload) {
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

	boolean fail(StreamTerminationReason reason, @Nullable Throwable cause) {
		requireNonNull(reason);
		Runnable wake;
		boolean notify;

		synchronized (lock) {
			if (closed || terminalWritten || failure != null)
				return false;

			failure = cause instanceof IOException ioException
					? ioException
					: new IOException("MCP response stream terminated: " + reason, cause);
			terminalReserved = true;
			clearBufferedData();
			wake = reserveWakeIfNeeded();
			notify = reserveTerminationNotification();
			lock.notifyAll();
		}

		if (notify)
			listener.didTerminate(reason, cause);

		wake.run();
		return true;
	}

	long responseWriteIdleDeadlineNanos(long timeoutNanos) {
		synchronized (lock) {
			if (!started || closed || terminalWritten || failure != null)
				return Long.MAX_VALUE;

			return saturatingAdd(lastWriteAtNanos, timeoutNanos);
		}
	}

	Snapshot snapshot() {
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

	@Override
	public void start() {
		Runnable wake;

		synchronized (lock) {
			if (started)
				return;

			started = true;
			lastWriteAtNanos = clock.nanoTime();
			wake = reserveWakeIfNeeded();
		}

		wake.run();
	}

	@Override
	public void writeReadyCallback(@Nullable Runnable callback) {
		Runnable wake;

		synchronized (lock) {
			writeReadyCallback = callback == null ? McpOutboundChannel::noOp : callback;
			callbackInstalled = callback != null;
			wake = reserveWakeIfNeeded();
		}

		wake.run();
	}

	@Override
	public long writeTo(SocketChannel socketChannel, long maximumBytes) throws IOException {
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
				writeTimestamp = clock.nanoTime();
				lastWriteAtNanos = writeTimestamp;
			}
		}

		if (written > 0L)
			listener.didWrite(written, writeTimestamp);

		if (notifyCompleted)
			listener.didTerminate(StreamTerminationReason.COMPLETED, null);

		return written;
	}

	@Override
	public boolean hasRemaining() {
		synchronized (lock) {
			return failure != null
					|| currentChunk != null
					|| !chunks.isEmpty()
					|| terminalChunk != null
					|| (!closed && !terminalWritten);
		}
	}

	@Override
	public boolean isReadyToWrite() {
		synchronized (lock) {
			return failure != null || currentChunk != null || !chunks.isEmpty() || terminalChunk != null;
		}
	}

	@Override
	public void close() {
		close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
	}

	@Override
	public void close(@Nullable StreamTerminationReason reason, @Nullable Throwable cause) {
		StreamTerminationReason effectiveReason = reason == null
				? StreamTerminationReason.CLIENT_DISCONNECTED
				: reason;
		boolean notify;

		synchronized (lock) {
			if (closed)
				return;

			closed = true;
			clearBufferedData();
			notify = reserveTerminationNotification();
			lock.notifyAll();
		}

		if (notify)
			listener.didTerminate(terminalWritten ? StreamTerminationReason.COMPLETED : effectiveReason, cause);
	}

	private boolean hasRegularCapacity(int payloadBytes) {
		return bufferedFrames < frameCapacity && bufferedBytes <= byteCapacity - payloadBytes;
	}

	private void addRegularChunk(byte[] payload) {
		chunks.add(Chunk.payload(payload));
		bufferedFrames++;
		bufferedBytes += payload.length;
		maximumObservedBufferedFrames = Math.max(maximumObservedBufferedFrames, bufferedFrames);
		maximumObservedBufferedBytes = Math.max(maximumObservedBufferedBytes, bufferedBytes);
	}

	private Runnable reserveWakeIfNeeded() {
		if (!callbackInstalled || wakePending || !isReadyToWriteUnderLock())
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

	private void clearBufferedData() {
		chunks.clear();
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
		private final List<ByteBuffer> buffers;
		private final int regularPayloadBytes;
		private final boolean terminal;
		private int bufferIndex;

		private Chunk(List<ByteBuffer> buffers, int regularPayloadBytes, boolean terminal) {
			this.buffers = requireNonNull(buffers);
			this.regularPayloadBytes = regularPayloadBytes;
			this.terminal = terminal;
		}

		private static Chunk payload(byte[] payload) {
			return new Chunk(framedPayload(payload), payload.length, false);
		}

		private static Chunk terminal(byte[] payload) {
			List<ByteBuffer> buffers = framedPayload(payload);
			buffers.add(ByteBuffer.wrap(TERMINAL_CHUNK));
			return new Chunk(buffers, 0, true);
		}

		private static List<ByteBuffer> framedPayload(byte[] payload) {
			byte[] header = format("%x\r\n", payload.length).getBytes(StandardCharsets.US_ASCII);
			List<ByteBuffer> buffers = new ArrayList<>(3);
			buffers.add(ByteBuffer.wrap(header));
			buffers.add(ByteBuffer.wrap(payload));
			buffers.add(ByteBuffer.wrap(CRLF));
			return buffers;
		}

		private long writeTo(SocketChannel socketChannel, long maximumBytes) throws IOException {
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
