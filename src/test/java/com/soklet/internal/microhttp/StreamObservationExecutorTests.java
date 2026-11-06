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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Component coverage for bounded observation workers and their idle policy. */
@Timeout(60)
class StreamObservationExecutorTests {
    private static final int CAPACITY = 4;

    @Test
    void completionDoesNotInterruptIdleWorkersAndBlockedObserversKeepIndependentCapacity() throws Exception {
        CountingWorkers workers = new CountingWorkers();
        ThreadPoolExecutor executor = executor(workers);
        CountDownLatch entered = new CountDownLatch(CAPACITY);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicInteger queuedCalls = new AtomicInteger();
        try {
            for (int index = 0; index < CAPACITY; index++)
                executor.execute(() -> hold(entered, release, callbackFailure));
            assertTrue(entered.await(3, TimeUnit.SECONDS), "Every blocked observer needs its own worker.");
            for (int index = 0; index < CAPACITY; index++)
                executor.execute(queuedCalls::incrementAndGet);
            assertEquals(CAPACITY, executor.getPoolSize());
            assertEquals(CAPACITY, executor.getQueue().size());
            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(() -> fail("An overflow observation entered.")));
            release.countDown();
            await(() -> executor.getCompletedTaskCount() == CAPACITY * 2L && executor.getActiveCount() == 0);
            assertNull(callbackFailure.get());
            assertEquals(CAPACITY, queuedCalls.get());
            assertEquals(0, workers.interruptions.get(), "Finishing observations must not wake all idle workers.");
            assertEquals(CAPACITY, executor.getCorePoolSize());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void coreHighWaterSurvivesIdleExpiryAndSparseRefillRemainsBounded() throws Exception {
        CountingWorkers workers = new CountingWorkers();
        ThreadPoolExecutor executor = executor(workers);
        CountDownLatch entered = new CountDownLatch(CAPACITY);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        try {
            assertTrue(executor.allowsCoreThreadTimeOut());
            assertEquals(Duration.ofSeconds(30).toNanos(), executor.getKeepAliveTime(TimeUnit.NANOSECONDS));
            for (int index = 0; index < CAPACITY; index++)
                executor.execute(() -> hold(entered, release, callbackFailure));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            release.countDown();
            await(() -> executor.getCompletedTaskCount() == CAPACITY && executor.getActiveCount() == 0);
            assertNull(callbackFailure.get());
            assertEquals(CAPACITY, executor.getCorePoolSize());
            assertEquals(0, workers.interruptions.get());
            // Shorten only this fixture's idle period; production keeps thirty seconds.
            executor.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
            await(() -> executor.getPoolSize() == 0);
            assertEquals(CAPACITY, executor.getCorePoolSize(), "Worker expiry does not reset the core target.");
            executor.setKeepAliveTime(1, TimeUnit.DAYS);
            // Changing the fixture's keepalive itself wakes idle workers once.
            int configurationInterruptions = workers.interruptions.get();
            int beforeRefill = workers.created.get();
            for (int index = 0; index < CAPACITY; index++) {
                long completed = CAPACITY + index + 1L;
                executor.execute(() -> {});
                await(() -> executor.getCompletedTaskCount() == completed && executor.getActiveCount() == 0);
                assertEquals(index + 1, executor.getPoolSize(), "Sparse submissions refill the retained core target.");
            }
            assertEquals(CAPACITY, workers.created.get() - beforeRefill);
            int afterRefill = workers.created.get();
            for (int index = 0; index < CAPACITY; index++) {
                long completed = CAPACITY * 2L + index + 1L;
                executor.execute(() -> {});
                await(() -> executor.getCompletedTaskCount() == completed && executor.getActiveCount() == 0);
            }
            assertEquals(afterRefill, workers.created.get(), "A refilled pool reuses its idle workers.");
            assertEquals(CAPACITY, executor.getPoolSize());
            assertEquals(configurationInterruptions, workers.interruptions.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void hold(CountDownLatch entered, CountDownLatch release,
            AtomicReference<Throwable> failure) {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) failure.compareAndSet(null, new AssertionError("Missing observer release."));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, interrupted);
        }
    }

    private static ThreadPoolExecutor executor(CountingWorkers workers) throws Exception {
        Class<?> type = Class.forName(StreamLifecycleCoordinator.class.getName() + "$ObservationExecutor");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, String.class);
        constructor.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) constructor.newInstance(CAPACITY, "observation-pool-test");
        executor.setThreadFactory(action -> {
            workers.created.incrementAndGet();
            Thread worker = new Thread(action, "counted-observation-pool-test") {
                @Override public void interrupt() {
                    workers.interruptions.incrementAndGet();
                    super.interrupt();
                }
            };
            worker.setDaemon(true);
            return worker;
        });
        return executor;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L)
            Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "Observation worker state did not settle.");
    }

    private static final class CountingWorkers {
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger interruptions = new AtomicInteger();
    }
}
