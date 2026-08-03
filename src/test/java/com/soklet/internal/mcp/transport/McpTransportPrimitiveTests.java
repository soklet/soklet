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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class McpTransportPrimitiveTests {
	@Test
	public void dispatcher_enforces_slot_and_queue_bounds_and_recovers() throws Exception {
		ExecutorService executor = McpThreadStrategy.PLATFORM.createExecutor(1, "mcp-dispatch-test-", (thread, failure) -> {
			// Failures are reported through the dispatcher ticket.
		});
		McpHandlerDispatcher dispatcher = new McpHandlerDispatcher(1, 1, executor);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondCompleted = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpHandlerDispatcher.Ticket first = dispatcher.newTicket(() -> {
			firstEntered.countDown();
			releaseFirst.await();
		}, failure::set);
		McpHandlerDispatcher.Ticket second = dispatcher.newTicket(secondCompleted::countDown, failure::set);
		McpHandlerDispatcher.Ticket third = dispatcher.newTicket(() -> {
			throw new AssertionError("Rejected ticket ran");
		}, failure::set);

		try {
			Assertions.assertEquals(McpHandlerDispatcher.Admission.DISPATCHED, dispatcher.admit(first));
			Assertions.assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(McpHandlerDispatcher.Admission.QUEUED, dispatcher.admit(second));
			Assertions.assertEquals(McpHandlerDispatcher.Admission.REJECTED, dispatcher.admit(third));

			McpHandlerDispatcher.Snapshot saturated = dispatcher.snapshot();
			Assertions.assertEquals(1, saturated.activeSlots());
			Assertions.assertEquals(1, saturated.queueDepth());
			Assertions.assertEquals(1, saturated.maximumObservedActiveSlots());
			Assertions.assertEquals(1, saturated.maximumObservedQueueDepth());
			Assertions.assertEquals(McpHandlerDispatcher.TicketState.REJECTED, third.state());

			releaseFirst.countDown();
			Assertions.assertTrue(secondCompleted.await(3, TimeUnit.SECONDS));
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			Assertions.assertNull(failure.get());
		} finally {
			releaseFirst.countDown();
			dispatcher.stopAccepting();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	@Test
	public void dispatcher_cancelation_removes_only_queued_ticket() throws Exception {
		ExecutorService executor = McpThreadStrategy.PLATFORM.createExecutor(1, "mcp-cancel-test-", (thread, failure) -> {
			// Failures are reported through the dispatcher ticket.
		});
		McpHandlerDispatcher dispatcher = new McpHandlerDispatcher(1, 1, executor);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger secondRuns = new AtomicInteger();
		McpHandlerDispatcher.Ticket first = dispatcher.newTicket(() -> {
			firstEntered.countDown();
			releaseFirst.await();
		}, failure -> {
			throw new AssertionError(failure);
		});
		McpHandlerDispatcher.Ticket second = dispatcher.newTicket(secondRuns::incrementAndGet, failure -> {
			throw new AssertionError(failure);
		});

		try {
			dispatcher.admit(first);
			Assertions.assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
			dispatcher.admit(second);
			Assertions.assertTrue(dispatcher.cancelQueued(second));
			Assertions.assertFalse(dispatcher.cancelQueued(second));
			Assertions.assertEquals(McpHandlerDispatcher.TicketState.CANCELED, second.state());
			Assertions.assertEquals(0, dispatcher.snapshot().queueDepth());
			releaseFirst.countDown();
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			Assertions.assertEquals(0, secondRuns.get());
		} finally {
			releaseFirst.countDown();
			dispatcher.stopAccepting();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	@Test
	public void outbound_channel_enforces_dual_bounds_and_preserves_terminal_lane() throws Exception {
		RecordingChannelListener listener = new RecordingChannelListener();
		McpOutboundChannel channel = new McpOutboundChannel(2, 6, 4, () -> 17L, listener);
		AtomicInteger wakeCount = new AtomicInteger();

		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED, channel.offer(ascii("one")));
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED, channel.offer(ascii("two")));
		Assertions.assertEquals(McpOutboundChannel.OfferResult.FULL, channel.offer(ascii("x")));
		Assertions.assertTrue(channel.complete(ascii("end")), "reserved terminal lane should remain available");
		Assertions.assertFalse(channel.complete(ascii("again")), "only one terminal may win");

		McpOutboundChannel.Snapshot full = channel.snapshot();
		Assertions.assertEquals(2, full.bufferedFrames());
		Assertions.assertEquals(6, full.bufferedBytes());
		Assertions.assertEquals(3, full.terminalBytes());
		Assertions.assertTrue(full.terminalReserved());

		channel.writeReadyCallback(wakeCount::incrementAndGet);
		channel.start();
		Assertions.assertEquals(1, wakeCount.get(), "writer wakeups must be coalesced");
		PartialWriteSocketChannel socketChannel = new PartialWriteSocketChannel(1);

		while (channel.hasRemaining()) {
			long written = channel.writeTo(socketChannel, 2L);
			Assertions.assertTrue(written > 0L || channel.isReadyToWrite(), "ready channel made no progress");
		}

		Assertions.assertEquals("3\r\none\r\n3\r\ntwo\r\n3\r\nend\r\n0\r\n\r\n",
				ascii(socketChannel.writtenBytes()));
		Assertions.assertEquals(1, listener.terminationCount.get());
		Assertions.assertEquals(StreamTerminationReason.COMPLETED, listener.terminationReason.get());
		Assertions.assertEquals(0, channel.snapshot().bufferedFrames());
		Assertions.assertEquals(0, channel.snapshot().bufferedBytes());
		channel.close();
		Assertions.assertEquals(1, listener.terminationCount.get());
	}

	@Test
	public void outbound_channel_backpressures_one_producer_with_bounded_retention() throws Exception {
		RecordingChannelListener listener = new RecordingChannelListener();
		McpOutboundChannel channel = new McpOutboundChannel(1, 3, 4, System::nanoTime, listener);
		channel.enqueue(ascii("one"));
		CountDownLatch secondEnqueued = new CountDownLatch(1);
		AtomicReference<Throwable> producerFailure = new AtomicReference<>();
		Thread producer = new Thread(() -> {
			try {
				channel.enqueue(ascii("two"));
				secondEnqueued.countDown();
			} catch (Throwable throwable) {
				producerFailure.set(throwable);
			}
		}, "mcp-backpressure-test");

		try {
			producer.start();
			Assertions.assertTrue(listener.backpressure.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, channel.snapshot().bufferedFrames());
			Assertions.assertEquals(3, channel.snapshot().bufferedBytes());
			Assertions.assertEquals(1L, secondEnqueued.getCount());

			PartialWriteSocketChannel socketChannel = new PartialWriteSocketChannel(64);
			channel.writeTo(socketChannel, 64L);
			Assertions.assertTrue(secondEnqueued.await(3, TimeUnit.SECONDS));
			Assertions.assertNull(producerFailure.get());
			Assertions.assertEquals(1, channel.snapshot().bufferedFrames());
			Assertions.assertEquals(3, channel.snapshot().bufferedBytes());
		} finally {
			channel.close(StreamTerminationReason.SERVER_STOPPING, null);
			producer.interrupt();
			producer.join(Duration.ofSeconds(3).toMillis());
			Assertions.assertFalse(producer.isAlive());
		}
	}

	private static void awaitCondition(Condition condition) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();

		while (!condition.evaluate()) {
			if (System.nanoTime() - deadline >= 0L)
				throw new AssertionError("Timed out waiting for condition");

			Thread.onSpinWait();
		}
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static String ascii(byte[] value) {
		return new String(value, StandardCharsets.US_ASCII);
	}

	@FunctionalInterface
	private interface Condition {
		boolean evaluate() throws Exception;
	}

	private static final class RecordingChannelListener implements McpOutboundChannel.Listener {
		private final CountDownLatch backpressure = new CountDownLatch(1);
		private final AtomicInteger terminationCount = new AtomicInteger();
		private final AtomicReference<StreamTerminationReason> terminationReason = new AtomicReference<>();

		@Override
		public void didWrite(long byteCount, long timestampNanos) {
			// No-op
		}

		@Override
		public void didApplyBackpressure() {
			backpressure.countDown();
		}

		@Override
		public void didTerminate(StreamTerminationReason reason, @Nullable Throwable cause) {
			terminationReason.compareAndSet(null, reason);
			terminationCount.incrementAndGet();
		}
	}

	private static final class PartialWriteSocketChannel extends SocketChannel {
		private final ByteArrayOutputStream output;
		private final int maximumBytesPerWrite;

		private PartialWriteSocketChannel(int maximumBytesPerWrite) {
			super(SelectorProvider.provider());
			this.output = new ByteArrayOutputStream();
			this.maximumBytesPerWrite = maximumBytesPerWrite;
		}

		private byte[] writtenBytes() {
			return output.toByteArray();
		}

		@Override
		public int write(ByteBuffer source) throws IOException {
			int byteCount = Math.min(source.remaining(), maximumBytesPerWrite);
			byte[] bytes = new byte[byteCount];
			source.get(bytes);
			output.write(bytes);
			return byteCount;
		}

		@Override
		public long write(ByteBuffer[] sources, int offset, int length) throws IOException {
			long written = 0L;

			for (int index = offset; index < offset + length; index++)
				written += write(sources[index]);

			return written;
		}

		@Override
		public int read(ByteBuffer destination) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long read(ByteBuffer[] destinations, int offset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SocketChannel bind(SocketAddress localAddress) {
			return this;
		}

		@Override
		public <T> SocketChannel setOption(SocketOption<T> option, T value) {
			return this;
		}

		@Override
		public <T> T getOption(SocketOption<T> option) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Set<SocketOption<?>> supportedOptions() {
			return Set.of();
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
			throw new UnsupportedOperationException();
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
		public boolean connect(SocketAddress remoteAddress) {
			return true;
		}

		@Override
		public boolean finishConnect() {
			return true;
		}

		@Override
		public @Nullable SocketAddress getRemoteAddress() {
			return null;
		}

		@Override
		public @Nullable SocketAddress getLocalAddress() {
			return null;
		}

		@Override
		protected void implCloseSelectableChannel() {
			// No-op
		}

		@Override
		protected void implConfigureBlocking(boolean blocking) {
			// No-op
		}
	}
}
