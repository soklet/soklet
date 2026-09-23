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

import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Transport admission and initializer behavior independent of the public SSE API. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class ManagedSseLifecycleTests {
	@Test
	public void initializer_is_one_time_and_connection_lifetime_ends_on_transport_termination() throws Exception {
		StreamLifecycleCoordinator coordinator = coordinator();
		AtomicInteger transportStops = new AtomicInteger();
		ManagedSseLifecycle lifecycle = new ManagedSseLifecycle(coordinator.tryReserve(), transportStops::incrementAndGet);
		try {
			AtomicInteger initializations = new AtomicInteger();
			Assertions.assertTrue(lifecycle.executeInitializer(initializations::incrementAndGet));
			Assertions.assertEquals(1, initializations.get());
			Assertions.assertTrue(lifecycle.isOpen());
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			Assertions.assertEquals("ready", lifecycle.whileOpen(() -> "ready"));
			Assertions.assertTrue(lifecycle.terminate(StreamTerminationReason.CLIENT_DISCONNECTED, null));
			Assertions.assertFalse(lifecycle.terminate(StreamTerminationReason.SERVER_STOPPING, null));
			Assertions.assertEquals(1, transportStops.get());
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, lifecycle.termination().orElseThrow().getReason());
			Assertions.assertThrows(IllegalStateException.class, () -> lifecycle.whileOpen(() -> "late"));
			Assertions.assertEquals(1, initializations.get());
		} finally {
			finish(coordinator);
		}
	}

	@Test
	public void checked_initializer_failure_is_preserved_and_prevents_activation() throws Exception {
		StreamLifecycleCoordinator coordinator = coordinator();
		ManagedSseLifecycle lifecycle = new ManagedSseLifecycle(coordinator.tryReserve(), () -> {});
		try {
			IOException failure = new IOException("catch-up failed");
			Assertions.assertSame(failure, Assertions.assertThrows(IOException.class,
					() -> lifecycle.executeInitializer(() -> { throw failure; })));
			Assertions.assertFalse(lifecycle.isOpen());
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, lifecycle.termination().orElseThrow().getReason());
			Assertions.assertSame(failure, lifecycle.termination().orElseThrow().getCause().orElseThrow());
		} finally {
			finish(coordinator);
		}
	}

	@Test
	public void forced_termination_releases_only_after_blocked_initializer_physically_exits() throws Exception {
		StreamLifecycleCoordinator coordinator = coordinator();
		ManagedSseLifecycle lifecycle = new ManagedSseLifecycle(coordinator.tryReserve(), () -> {});
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread initializer = new Thread(() -> {
			try {
				lifecycle.executeInitializer(() -> {
					entered.countDown();
					for (;;) {
						try { release.await(); return; }
						catch (InterruptedException ignored) { interrupted.set(true); }
					}
				});
			} catch (Throwable throwable) {
				failure.set(throwable);
			}
		}, "sse-blocked-initializer");
		initializer.start();
		try {
			Assertions.assertTrue(entered.await(3, TimeUnit.SECONDS));
			coordinator.force();
			Assertions.assertFalse(lifecycle.isOpen());
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			Assertions.assertEquals(1, coordinator.snapshot().runningProducers());
		} finally {
			release.countDown();
			initializer.join(3000);
			Assertions.assertFalse(initializer.isAlive());
			Assertions.assertNull(failure.get());
			finish(coordinator);
		}
	}

	private static StreamLifecycleCoordinator coordinator() {
		return new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(1), ignored -> {});
	}

	private static void finish(StreamLifecycleCoordinator coordinator) throws InterruptedException {
		coordinator.force();
		Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
	}
}
