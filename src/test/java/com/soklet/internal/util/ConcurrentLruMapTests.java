package com.soklet.internal.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ConcurrentLruMapTests {
    @Test
    void entriesRetainObservedValuesAfterRemovalAndStillWriteThrough() {
        ConcurrentLruMap<String, String> map = new ConcurrentLruMap<>(1);
        map.put("first", "observed");
        Map.Entry<String, String> entry = map.entrySet().iterator().next();
        map.clear();
        assertEquals("observed", entry.getValue());
        assertEquals("observed", entry.setValue("replacement"));
        assertEquals("replacement", map.get("first"));
        assertEquals("replacement", entry.getValue());
    }

    @Test
    void pausedMaintenanceBackpressuresConcurrentWriters() throws Exception {
        int capacity = 16;
        int workers = 8;
        ConcurrentLruMap<Integer, Integer> map = new ConcurrentLruMap<>(capacity);
        ReentrantLock lock = (ReentrantLock) field("maintenanceLock").get(map);
        List<Thread> threads = new ArrayList<>();
        List<AtomicBoolean> entered = new ArrayList<>();
        List<FutureTask<Void>> tasks = new ArrayList<>();
        lock.lock();
        try {
            for (int worker = 0; worker < workers; worker++) {
                int first = worker * 1000;
                AtomicBoolean running = new AtomicBoolean();
                entered.add(running);
                FutureTask<Void> task = new FutureTask<>(() -> {
                    running.set(true);
                    for (int index = first; index < first + 1000; index++) map.put(index, index);
                    return null;
                });
                tasks.add(task);
                Thread thread = Thread.ofPlatform().unstarted(task);
                threads.add(thread);
                thread.start();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                boolean started = entered.stream().allMatch(AtomicBoolean::get);
                boolean stopped = threads.stream().allMatch(thread -> thread.getState() == Thread.State.WAITING
                        || thread.getState() == Thread.State.TERMINATED);
                if (started && stopped) break;
                Thread.sleep(1);
            }
            assertTrue(entered.stream().allMatch(AtomicBoolean::get));
            assertTrue(threads.stream().allMatch(thread -> thread.getState() == Thread.State.WAITING
                    || thread.getState() == Thread.State.TERMINATED));
            assertTrue(map.size() <= capacity + workers, "Paused maintenance allowed unbounded growth: " + map.size());
        } finally {
            lock.unlock();
        }
        for (FutureTask<Void> task : tasks) task.get(10, TimeUnit.SECONDS);
        map.drain();
        assertEquals(capacity, map.size());
        assertEquals(new HashSet<>(map.keySet()), new HashSet<>(map.keysInAccessOrder()));
    }

    @Test
    void clearKeepsInsertionsWhoseTasksArriveAfterTheMapWasCleared() throws Exception {
        ConcurrentLruMap<String, String> map = new ConcurrentLruMap<>(2);
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>() {
            @Override public Object poll() {
                if (pause.compareAndSet(true, false)) {
                    polling.countDown();
                    await(resume);
                }
                return super.poll();
            }
        };
        field("writeBuffer").set(map, queue);
        FutureTask<Void> clear = new FutureTask<>(() -> { map.clear(); return null; });
        Thread clearer = Thread.ofPlatform().start(clear);
        try {
            assertTrue(polling.await(5, TimeUnit.SECONDS));
            // clear() has already removed the old mappings, but has not consumed write tasks.
            map.put("survivor", "value");
        } finally {
            resume.countDown();
        }
        clear.get(5, TimeUnit.SECONDS);
        clearer.join();
        map.drain();
        assertEquals("value", map.get("survivor"));
        assertEquals(List.of("survivor"), map.keysInAccessOrder());
        map.put("next", "2");
        map.put("newest", "3");
        map.drain();
        assertEquals(2, map.size());
        assertFalse(map.containsKey("survivor"));
    }

    @Test
    void concurrentClearAndMixedWritesKeepMapAndEvictionListConsistent() throws Exception {
        ConcurrentLruMap<Integer, Integer> map = new ConcurrentLruMap<>(16);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int first = worker * 20000;
                tasks.add(executor.submit(() -> {
                    for (int index = first; index < first + 20000; index++) {
                        switch (index % 5) {
                            case 0 -> map.put(index, index);
                            case 1 -> map.computeIfAbsent(index, key -> key);
                            case 2 -> map.putIfAbsent(index, index);
                            case 3 -> map.remove(index - 3);
                            default -> map.get(index - 4);
                        }
                        if (index % 127 == 0) map.clear();
                    }
                }));
            }
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        map.drain();
        assertTrue(map.size() <= map.capacity());
        assertEquals(new HashSet<>(map.keySet()), new HashSet<>(map.keysInAccessOrder()));
    }

    @Test
    void evictionCallbacksRemainExactAndCanReenterTheMap() throws Exception {
        Set<Integer> evictions = ConcurrentHashMap.newKeySet();
        AtomicInteger callbacks = new AtomicInteger();
        ConcurrentLruMap<Integer, Integer> map = new ConcurrentLruMap<>(16, (key, value) -> {
            assertEquals(key, value);
            assertTrue(evictions.add(key), "Duplicate eviction");
            callbacks.incrementAndGet();
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                int first = worker * 5000;
                tasks.add(executor.submit(() -> {
                    for (int key = first; key < first + 5000; key++) map.put(key, key);
                }));
            }
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        map.drain();
        assertEquals(20000, callbacks.get() + map.size());
        assertTrue(Collections.disjoint(map.keySet(), evictions));
        map.clear();
        assertEquals(20000 - 16, callbacks.get(), "Explicit clear does not deliver eviction callbacks");

        List<ConcurrentLruMap<String, String>> reference = new ArrayList<>();
        ConcurrentLruMap<String, String> reentrant = new ConcurrentLruMap<>(1,
                (key, value) -> reference.getFirst().clear());
        reference.add(reentrant);
        reentrant.put("first", "one");
        reentrant.put("second", "two");
        assertTrue(reentrant.isEmpty());
    }

    private static Field field(String name) throws Exception {
        Field field = ConcurrentLruMap.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test coordination");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
