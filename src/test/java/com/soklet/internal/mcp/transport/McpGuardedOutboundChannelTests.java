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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authorization fences at the actual HTTP chunk write boundary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpGuardedOutboundChannelTests {
	@Test
	public void rejected_offers_release_once_without_consuming_the_existing_key() throws Exception {
		RecordingListener listener = new RecordingListener();
		McpOutboundChannel channel = channel(1, 8, listener);
		Object lock = channelLock(channel);
		AtomicInteger retained = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		Runnable releaseRejected = () -> {
			Assertions.assertFalse(Thread.holdsLock(lock));
			rejected.incrementAndGet();
		};
		Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.ACCEPTED),
				channel.offerGuardedCoalescing(ascii("one"), "one", () -> true,
						retained::incrementAndGet));
		Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.COALESCED),
				channel.offerGuardedCoalescing(ascii("copy"), "one", () -> true, releaseRejected));
		Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.FULL),
				channel.offerGuardedCoalescing(ascii("two"), "two", () -> true, releaseRejected));
		Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.TOO_LARGE),
				channel.offerGuardedCoalescing(ascii("123456789"), "large", () -> true, releaseRejected));
		Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.TOO_LARGE),
				channel.offerGuardedCoalescing(new byte[0], "empty", () -> true, releaseRejected));
		Assertions.assertEquals(Optional.empty(), channel.offerGuardedCoalescing(
				ascii("deny"), "deny", () -> false, releaseRejected));
		Assertions.assertEquals(5, rejected.get());
		Assertions.assertEquals(0, retained.get());
		Assertions.assertEquals(1, channel.snapshot().bufferedFrames());
		channel.close(StreamTerminationReason.SERVER_STOPPING, null);
		Assertions.assertEquals(1, retained.get());
		Assertions.assertEquals(Optional.of(McpOutboundChannel.OfferResult.CLOSED),
				channel.offerGuardedCoalescing(ascii("late"), "late", () -> true, releaseRejected));
		Assertions.assertEquals(6, rejected.get());
		channel.close(StreamTerminationReason.SERVER_STOPPING, null);
		Assertions.assertEquals(1, retained.get());
	}

	@Test
	public void recheck_purges_only_revoked_unwritten_frames_and_releases_the_key() throws Exception {
		RecordingListener listener = new RecordingListener();
		McpOutboundChannel channel = channel(4, 64, listener);
		AtomicBoolean allowed = new AtomicBoolean(true);
		AtomicInteger releases = new AtomicInteger();
		Object lock = channelLock(channel);
		channel.offerGuardedCoalescing(ascii("secret"), "uri", allowed::get, () -> {
			Assertions.assertFalse(Thread.holdsLock(lock));
			releases.incrementAndGet();
		});
		channel.offer(ascii("modern"));
		allowed.set(false);
		Assertions.assertTrue(channel.recheckGuardedFrames());
		Assertions.assertEquals(1, releases.get());
		Assertions.assertEquals(1, channel.snapshot().bufferedFrames());
		Assertions.assertEquals(6, channel.snapshot().bufferedBytes());
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				channel.offerCoalescing(ascii("fresh"), "uri"));
		Assertions.assertTrue(channel.complete(ascii("final")));
		TestSocketChannel socket = new TestSocketChannel(2);
		WritableSource source = channel.newWritableSource();
		source.start();
		source.writeTo(socket, 1024);
		Assertions.assertEquals("6\r\nmodern\r\n5\r\nfresh\r\n5\r\nfinal\r\n0\r\n\r\n",
				new String(socket.writtenBytes(), StandardCharsets.US_ASCII));
		Assertions.assertEquals(1, releases.get());
		Assertions.assertEquals(0, channel.snapshot().bufferedFrames());
		Assertions.assertEquals(StreamTerminationReason.COMPLETED, listener.reason.get());
	}

	@Test
	public void revocation_between_offer_and_first_write_is_suppressed_without_an_explicit_recheck() throws Exception {
		McpOutboundChannel channel = channel(2, 32, new RecordingListener());
		AtomicBoolean allowed = new AtomicBoolean(true);
		AtomicInteger releases = new AtomicInteger();
		channel.offerGuardedCoalescing(ascii("secret"), "uri", allowed::get, releases::incrementAndGet);
		channel.offer(ascii("safe"));
		allowed.set(false);
		WritableSource source = channel.newWritableSource();
		source.start();
		TestSocketChannel socket = new TestSocketChannel(1);
		source.writeTo(socket, 1024);
		Assertions.assertEquals("4\r\nsafe\r\n", new String(socket.writtenBytes(), StandardCharsets.US_ASCII));
		Assertions.assertEquals(1, releases.get());
		Assertions.assertEquals(0, channel.snapshot().bufferedFrames());
		Assertions.assertTrue(source.hasRemaining());
	}

	@Test
	public void revoked_current_chunk_with_zero_socket_bytes_is_dropped_without_closing() throws Exception {
		McpOutboundChannel channel = channel(2, 32, new RecordingListener());
		AtomicBoolean allowed = new AtomicBoolean(true);
		AtomicInteger releases = new AtomicInteger();
		channel.offerGuardedCoalescing(ascii("secret"), "uri", allowed::get, releases::incrementAndGet);
		WritableSource source = channel.newWritableSource();
		source.start();
		TestSocketChannel blocked = new TestSocketChannel(0);
		Assertions.assertEquals(0, source.writeTo(blocked, 1024));
		allowed.set(false);
		Assertions.assertTrue(channel.recheckGuardedFrames());
		Assertions.assertEquals(1, releases.get());
		Assertions.assertEquals(0, channel.snapshot().bufferedFrames());
		channel.offer(ascii("safe"));
		TestSocketChannel socket = new TestSocketChannel(4);
		source.writeTo(socket, 1024);
		Assertions.assertEquals("4\r\nsafe\r\n", new String(socket.writtenBytes(), StandardCharsets.US_ASCII));
		Assertions.assertTrue(source.hasRemaining());
	}

	@Test
	public void write_rechecks_before_each_socket_call_and_never_finishes_a_revoked_partial_frame() throws Exception {
		RecordingListener listener = new RecordingListener();
		McpOutboundChannel channel = channel(3, 64, listener);
		AtomicBoolean allowed = new AtomicBoolean(true);
		AtomicInteger releases = new AtomicInteger();
		channel.offerGuardedCoalescing(ascii("secret"), "uri", allowed::get, releases::incrementAndGet);
		channel.offerGuardedCoalescing(ascii("other"), "other", () -> true, releases::incrementAndGet);
		TestSocketChannel socket = new TestSocketChannel(1, () -> allowed.set(false));
		WritableSource source = channel.newWritableSource();
		source.start();
		Assertions.assertThrows(IOException.class, () -> source.writeTo(socket, 1024));
		Assertions.assertEquals("6", new String(socket.writtenBytes(), StandardCharsets.US_ASCII));
		Assertions.assertEquals(StreamTerminationReason.APPLICATION_CANCELED, listener.reason.get());
		Assertions.assertEquals(1, listener.terminations.get());
		Assertions.assertEquals(2, releases.get());
		Assertions.assertEquals(0, channel.snapshot().bufferedBytes());
		Assertions.assertFalse(channel.completeWithoutPayload());
		Assertions.assertFalse(channel.recheckGuardedFrames());
		Assertions.assertThrows(IOException.class, () -> source.writeTo(socket, 1024));
		Assertions.assertEquals(1, socket.writtenBytes().length);
		source.close();
		Assertions.assertEquals(2, releases.get());
		Assertions.assertEquals(1, listener.terminations.get());
	}

	@Test
	public void synchronous_recheck_fails_a_partial_frame_before_any_later_write() throws Exception {
		RecordingListener listener = new RecordingListener();
		McpOutboundChannel channel = channel(2, 32, listener);
		AtomicBoolean allowed = new AtomicBoolean(true);
		AtomicInteger releases = new AtomicInteger();
		Object lock = channelLock(channel);
		listener.outsideLock = () -> Assertions.assertFalse(Thread.holdsLock(lock));
		channel.offerGuardedCoalescing(ascii("secret"), "uri", allowed::get, () -> {
			Assertions.assertFalse(Thread.holdsLock(lock));
			releases.incrementAndGet();
		});
		WritableSource source = channel.newWritableSource();
		source.start();
		TestSocketChannel socket = new TestSocketChannel(1);
		Assertions.assertEquals(1, source.writeTo(socket, 1));
		allowed.set(false);
		Assertions.assertFalse(channel.recheckGuardedFrames());
		Assertions.assertEquals(1, releases.get());
		Assertions.assertEquals(1, listener.terminations.get());
		Assertions.assertThrows(IOException.class, () -> source.writeTo(socket, 1024));
		Assertions.assertEquals(1, socket.writtenBytes().length);
	}

	@Test
	public void full_write_releases_once_and_clean_completion_keeps_exact_old_wire_bytes() throws Exception {
		RecordingListener listener = new RecordingListener();
		McpOutboundChannel channel = channel(2, 32, listener);
		AtomicInteger releases = new AtomicInteger();
		channel.offerGuardedCoalescing(ascii("update"), "uri", () -> true, releases::incrementAndGet);
		Assertions.assertTrue(channel.completeWithoutPayload());
		WritableSource source = channel.newWritableSource();
		source.start();
		TestSocketChannel socket = new TestSocketChannel(1);
		for (int i = 0; i < 64 && source.hasRemaining(); ++i)
			source.writeTo(socket, 1);
		Assertions.assertEquals("6\r\nupdate\r\n0\r\n\r\n", new String(socket.writtenBytes(), StandardCharsets.US_ASCII));
		Assertions.assertEquals(1, releases.get());
		Assertions.assertEquals(0, channel.snapshot().bufferedBytes());
		source.close();
		Assertions.assertEquals(1, releases.get());
		Assertions.assertEquals(1, listener.terminations.get());
	}

	@Test
	public void discard_failure_and_shutdown_release_all_callbacks_even_when_one_internal_observer_throws() {
		McpOutboundChannel discarded = channel(2, 32, new RecordingListener());
		AtomicInteger discardedRelease = new AtomicInteger();
		discarded.offerGuardedCoalescing(ascii("secret"), "uri", () -> true, discardedRelease::incrementAndGet);
		Assertions.assertTrue(discarded.completeWithoutPayload(true));
		Assertions.assertEquals(1, discardedRelease.get());
		discarded.close(StreamTerminationReason.SERVER_STOPPING, null);
		Assertions.assertEquals(1, discardedRelease.get());
		McpOutboundChannel failed = channel(3, 32, new RecordingListener());
		AtomicInteger releases = new AtomicInteger();
		failed.offerGuardedCoalescing(ascii("one"), "one", () -> true, () -> {
			releases.incrementAndGet();
			throw new IllegalStateException("internal release failure");
		});
		failed.offerGuardedCoalescing(ascii("two"), "two", () -> true, releases::incrementAndGet);
		Assertions.assertThrows(IllegalStateException.class,
				() -> failed.fail(StreamTerminationReason.SERVER_STOPPING, null));
		Assertions.assertEquals(2, releases.get());
		failed.close(StreamTerminationReason.SERVER_STOPPING, null);
		Assertions.assertEquals(2, releases.get());
	}

	private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
	private static McpOutboundChannel channel(int frames, int bytes, RecordingListener listener) {
		return new McpOutboundChannel(frames, bytes, bytes, System::nanoTime, listener);
	}
	private static Object channelLock(McpOutboundChannel channel) throws Exception {
		var field = McpOutboundChannel.class.getDeclaredField("lock");
		field.setAccessible(true);
		return field.get(channel);
	}
	private static final class RecordingListener implements McpOutboundChannel.Listener {
		private final AtomicInteger terminations = new AtomicInteger();
		private final AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		private Runnable outsideLock = () -> {};
		@Override public void didWrite(long count, long time) {}
		@Override public void didApplyBackpressure() {}
		@Override public void didTerminate(StreamTerminationReason reason, @Nullable Throwable cause) {
			outsideLock.run();
			this.reason.set(reason);
			terminations.incrementAndGet();
		}
	}
	private static final class TestSocketChannel extends SocketChannel {
		private final ByteArrayOutputStream output;
		private final int maximumBytesPerWrite;
		private final Runnable afterWrite;

		private TestSocketChannel(int maximumBytesPerWrite) {
			this(maximumBytesPerWrite, () -> {});
		}

		private TestSocketChannel(int maximumBytesPerWrite, Runnable afterWrite) {
			super(SelectorProvider.provider());
			this.output = new ByteArrayOutputStream();
			this.maximumBytesPerWrite = maximumBytesPerWrite;
			this.afterWrite = afterWrite;
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
			afterWrite.run();
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
