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
import java.time.Duration;
import java.util.List;
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

	private static final class ControlledTimeouts extends ScheduledThreadPoolExecutor {
		private final AtomicInteger scheduled = new AtomicInteger();
		private final BlockingQueue<ManualFuture> tasks = new LinkedBlockingQueue<>();
		private ControlledTimeouts() { super(1); }
		@Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			ManualFuture future = new ManualFuture(command);
			this.scheduled.incrementAndGet();
			this.tasks.add(future);
			return future;
		}
		private void runNext() throws InterruptedException {
			ManualFuture future = this.tasks.poll(3, TimeUnit.SECONDS);
			Assertions.assertNotNull(future);
			future.run();
		}
	}

	private static final class ManualFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
		private ManualFuture(Runnable command) { super(command, null); }
		@Override public long getDelay(TimeUnit unit) { return 0; }
		@Override public int compareTo(Delayed other) { return 0; }
	}
}
