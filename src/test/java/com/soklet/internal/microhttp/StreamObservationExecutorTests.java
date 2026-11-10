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
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Component coverage for bounded observation workers and their idle policy. */
@Timeout(60)
class StreamObservationExecutorTests {
    private static final int CAPACITY = 4;

    @Test
    void retiringIdleWorkerCannotStrandAnObservationBehindABlockedPeer() throws Exception {
        ExpiryQueue queue = new ExpiryQueue();
        Class<?> type = Class.forName(StreamLifecycleCoordinator.class.getName() + "$ObservationExecutor");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, String.class, BlockingQueue.class);
        constructor.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) constructor.newInstance(2, "expiry-race-test", queue);
        queue.executor = executor;
        executor.setKeepAliveTime(50, TimeUnit.MILLISECONDS);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch warm = new CountDownLatch(1), delivered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            executor.execute(() -> hold(entered, release, failure));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            queue.pauseExpiry.set(true);
            executor.execute(warm::countDown);
            assertTrue(warm.await(3, TimeUnit.SECONDS));
            assertTrue(queue.expired.await(3, TimeUnit.SECONDS));
            assertEquals(2, executor.getPoolSize());
            // execute has observed both workers before offer permits the idle one
            // to retire. The remaining worker cannot consume this new observation.
            queue.pauseOffer.set(true);
            executor.execute(delivered::countDown);
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            await(() -> executor.getPoolSize() == 1 && executor.getQueue().isEmpty());
            assertEquals(1, executor.getActiveCount());
            release.countDown();
            await(() -> executor.getPoolSize() == 0);
            assertEquals(2, executor.getCorePoolSize());
            assertNull(failure.get());
        } finally {
            queue.releaseExpiry.countDown(); release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void lazilyCreatedStreamHelpersUseTheCoordinatorCreationLoader() throws Exception {
        Thread caller = Thread.currentThread();
        ClassLoader startupLoader = caller.getContextClassLoader();
        StreamLifecycleCoordinator owner = new StreamLifecycleCoordinator(2, 1, Duration.ofSeconds(1), ignored -> {});
        try (URLClassLoader requestLoader = new URLClassLoader("request-helper-test", new URL[0], startupLoader)) {
            try {
                caller.setContextClassLoader(requestLoader);
                for (String name : List.of("callbackExecutor", "terminationExecutor", "diagnosticExecutor", "interruptExecutor", "supervisor")) {
                    Field field = StreamLifecycleCoordinator.class.getDeclaredField(name);
                    field.setAccessible(true);
                    ExecutorService executor = (ExecutorService) field.get(owner);
                    Thread worker = executor.submit(Thread::currentThread).get(3, TimeUnit.SECONDS);
                    assertSame(startupLoader, worker.getContextClassLoader(), name);
                }
            } finally { caller.setContextClassLoader(startupLoader); }
        } finally {
            owner.stopAdmission();
            assertTrue(owner.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
        }
    }

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

    private static final class ExpiryQueue extends LinkedBlockingQueue<Runnable> {
        private final AtomicBoolean pauseExpiry = new AtomicBoolean(), pauseOffer = new AtomicBoolean();
        private final CountDownLatch expired = new CountDownLatch(1), releaseExpiry = new CountDownLatch(1);
        private ThreadPoolExecutor executor;
        private ExpiryQueue() { super(2); }
        @Override public Runnable poll(long time, TimeUnit unit) throws InterruptedException {
            Runnable task = super.poll(time, unit);
            if (task == null && pauseExpiry.compareAndSet(true, false)) {
                expired.countDown();
                if (!releaseExpiry.await(3, TimeUnit.SECONDS)) throw new AssertionError("Missing expiry release.");
            }
            return task;
        }
        @Override public boolean offer(Runnable task) {
            if (pauseOffer.compareAndSet(true, false)) {
                releaseExpiry.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (executor.getPoolSize() != 1 && System.nanoTime() - deadline < 0L) Thread.onSpinWait();
                if (executor.getPoolSize() != 1) throw new AssertionError("Idle worker did not retire.");
            }
            return super.offer(task);
        }
    }
}
