/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.microhttp;

import com.soklet.HttpMethod;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingOutputActivityTests {
	@AfterEach public void resetHooks() { StreamingMicrohttpResponses.setTestHooks(null); }

	@Test
	public void healthy_owned_tail_waits_for_transport_without_consuming_cleanup_grace() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		ExecutorService producers = Executors.newSingleThreadExecutor();
		ControlledTimeouts timeouts = new ControlledTimeouts();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(1),
				ignored -> {}, clock::get);
		CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicReference<Thread> owner = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		WritableSource source = null;
		try (ProgressSocketChannel socket = new ProgressSocketChannel()) {
			var reservation = coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(200, "OK", List.of(),
					Request.fromPath(HttpMethod.GET, "/tail"), StreamingResponseBody.fromWriter(stream -> {
						owner.set(Thread.currentThread());
						stream.own((AutoCloseable) () -> {
							closeEntered.countDown();
							stream.write(new byte[]{'z'});
						});
						stream.write(new byte[]{'a'});
					}), producers, timeouts, 1, 1, null, null, () -> false,
					(established, elapsed, terminationReason, cause) -> { reason.set(terminationReason); terminated.countDown(); },
					ignored -> {}, reservation);
			var factory = MicrohttpResponse.class.getDeclaredMethod("newBodySource");
			factory.setAccessible(true);
			source = (WritableSource) factory.invoke(response);
			source.start();
			Assertions.assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
			long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (owner.get().getState() != Thread.State.WAITING && System.nanoTime() < waitDeadline)
				Thread.sleep(1);
			Assertions.assertEquals(Thread.State.WAITING, owner.get().getState());
			clock.set(reservation.cleanupDeadlineNanos() + TimeUnit.SECONDS.toNanos(2));
			reservation.checkCleanupDeadline(clock.get());
			Assertions.assertTrue(reservation.reason().isEmpty(), "Healthy encoder-tail backpressure used up cleanup grace");
			while (source.hasRemaining() && System.nanoTime() < waitDeadline) {
				source.writeTo(socket, Long.MAX_VALUE);
				Thread.yield();
			}
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			Assertions.assertNull(reason.get());
			Assertions.assertFalse(source.hasRemaining());
		} finally {
			if (source != null) source.close();
			coordinator.force(); producers.shutdownNow(); timeouts.shutdownNow();
			Assertions.assertTrue(producers.awaitTermination(2, TimeUnit.SECONDS));
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
		}
	}

	@Test
	public void socket_progress_renews_idle_timeout_after_production_then_a_stalled_drain_expires() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		StreamingMicrohttpResponses.setTestHooks(new StreamingMicrohttpResponses.TestHooks() {
			@Override public long nanoTime() { return clock.get(); }
		});
		ExecutorService producers = Executors.newSingleThreadExecutor();
		ControlledTimeouts timeouts = new ControlledTimeouts();
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		WritableSource source = null;
		try (ProgressSocketChannel socket = new ProgressSocketChannel()) {
			MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(200, "OK", List.of(),
					Request.fromPath(HttpMethod.GET, "/drain"), StreamingResponseBody.fromWriter(stream ->
							stream.write(new byte[128])), producers, timeouts, 256, 128, null, Duration.ofSeconds(1),
					(established, elapsed, terminationReason, cause) -> { reason.set(terminationReason); terminated.countDown(); },
					ignored -> {});
			var factory = MicrohttpResponse.class.getDeclaredMethod("newBodySource");
			factory.setAccessible(true);
			source = (WritableSource) factory.invoke(response);
			source.start();
			producers.submit(() -> {}).get(2, TimeUnit.SECONDS);
			clock.set(100L + TimeUnit.MILLISECONDS.toNanos(750));
			Assertions.assertEquals(1L, source.writeTo(socket, 1));
			clock.set(100L + TimeUnit.SECONDS.toNanos(1));
			timeouts.runNext();
			Assertions.assertNull(reason.get(), "An active socket drain must not expire on the producer's old timestamp");
			clock.set(100L + TimeUnit.MILLISECONDS.toNanos(1_750));
			timeouts.runNext();
			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, reason.get());
		} finally {
			if (source != null) source.close();
			producers.shutdownNow(); timeouts.shutdownNow();
			Assertions.assertTrue(producers.awaitTermination(2, TimeUnit.SECONDS));
		}
	}

	@Test
	public void stagedScalarActivityExtendsIdleDeadlineWithoutSchedulingPerByte() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		StreamingMicrohttpResponses.setTestHooks(new StreamingMicrohttpResponses.TestHooks() {
			@Override public long nanoTime() { return clock.get(); }
		});
		ExecutorService producers = Executors.newSingleThreadExecutor();
		ControlledTimeouts timeouts = new ControlledTimeouts();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(30), ignored -> {});
		CountDownLatch stagedBurst = new CountDownLatch(1);
		CountDownLatch writeAnother = new CountDownLatch(1);
		CountDownLatch stagedAnother = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		WritableSource source = null;
		try {
			var reservation = coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(200, "OK", List.of(),
					Request.withPath(HttpMethod.GET, "/scalar").build(), StreamingResponseBody.fromWriter(responseStream -> {
						OutputStream outputStream = responseStream.asOutputStream();
						for (int i = 0; i < 1_000; i++) outputStream.write(i);
						stagedBurst.countDown();
						writeAnother.await();
						outputStream.write(7);
						stagedAnother.countDown();
						release.await();
					}), producers, timeouts, 16_384, 8_192, null, Duration.ofSeconds(1), () -> false,
					(established, elapsed, terminationReason, cause) -> { reason.set(terminationReason); terminated.countDown(); },
					ignored -> {}, reservation);
			source = response.writableSource(response.serializeHead("HTTP/1.1", List.of()));
			source.start();
			Assertions.assertTrue(stagedBurst.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, timeouts.scheduled.get(), "Scalar bytes must not allocate a timer per byte");
			clock.set(100L + TimeUnit.MILLISECONDS.toNanos(750));
			writeAnother.countDown();
			Assertions.assertTrue(stagedAnother.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, timeouts.scheduled.get());
			clock.set(100L + TimeUnit.SECONDS.toNanos(1));
			timeouts.runNext();
			Assertions.assertNull(reason.get(), "Recent staged output must keep the stream active");
			Assertions.assertEquals(2, timeouts.scheduled.get());
			clock.set(100L + TimeUnit.MILLISECONDS.toNanos(1_749));
			timeouts.runNext();
			Assertions.assertNull(reason.get());
			clock.set(100L + TimeUnit.MILLISECONDS.toNanos(1_750));
			timeouts.runNext();
			Assertions.assertTrue(terminated.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, reason.get());
			Assertions.assertEquals(3, timeouts.scheduled.get());
		} finally {
			writeAnother.countDown(); release.countDown();
			if (source != null) source.close();
			coordinator.force(); producers.shutdownNow(); timeouts.shutdownNow();
			Assertions.assertTrue(producers.awaitTermination(3, TimeUnit.SECONDS));
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	static final class ControlledTimeouts extends ScheduledThreadPoolExecutor {
		private final AtomicInteger scheduled = new AtomicInteger();
		private final BlockingQueue<ManualFuture> tasks = new LinkedBlockingQueue<>();
		ControlledTimeouts() { super(1); }
		@Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			ManualFuture future = new ManualFuture(command);
			this.scheduled.incrementAndGet();
			this.tasks.add(future);
			return future;
		}
		void runNext() throws InterruptedException {
			ManualFuture future = this.tasks.poll(3, TimeUnit.SECONDS);
			Assertions.assertNotNull(future);
			future.run();
		}
	}

	static final class ProgressSocketChannel extends SocketChannel {
		ProgressSocketChannel() { super(SelectorProvider.provider()); }
		@Override public int write(ByteBuffer buffer) {
			int count = buffer.remaining(); buffer.position(buffer.limit()); return count;
		}
		@Override public long write(ByteBuffer[] buffers, int offset, int length) {
			long count = 0; for (int i = offset; i < offset + length; i++) count += write(buffers[i]); return count;
		}
		@Override public int read(ByteBuffer buffer) { return -1; }
		@Override public long read(ByteBuffer[] buffers, int offset, int length) { return -1; }
		@Override public SocketChannel bind(SocketAddress address) { return this; }
		@Override public <T> SocketChannel setOption(SocketOption<T> option, T value) { return this; }
		@Override public <T> T getOption(SocketOption<T> option) { return null; }
		@Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
		@Override public SocketChannel shutdownInput() { return this; }
		@Override public SocketChannel shutdownOutput() { return this; }
		@Override public Socket socket() { return new Socket(); }
		@Override public boolean isConnected() { return true; }
		@Override public boolean isConnectionPending() { return false; }
		@Override public boolean connect(SocketAddress address) { return true; }
		@Override public boolean finishConnect() { return true; }
		@Override public SocketAddress getRemoteAddress() { return null; }
		@Override public SocketAddress getLocalAddress() { return null; }
		@Override protected void implCloseSelectableChannel() {}
		@Override protected void implConfigureBlocking(boolean block) {}
	}

	private static final class ManualFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
		private ManualFuture(Runnable command) { super(command, null); }
		@Override public long getDelay(TimeUnit unit) { return 0; }
		@Override public int compareTo(Delayed other) { return 0; }
	}
}
