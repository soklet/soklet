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
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerBoundaryTests {
    @Test
    void finiteDeadlinesKeepTheirOrderAcrossSignedClockWrap() {
        TestClock clock = new TestClock(Long.MAX_VALUE - 10);
        Scheduler scheduler = new Scheduler(clock);
        Runnable first = () -> {};
        Runnable second = () -> {};
        scheduler.schedule(first, Duration.ofNanos(5));
        scheduler.schedule(second, Duration.ofNanos(20));
        assertTrue(scheduler.expired().isEmpty());
        clock.time += 5;
        assertEquals(List.of(first), scheduler.expired());
        clock.time += 14;
        assertTrue(scheduler.expired().isEmpty());
        clock.time += 1;
        assertEquals(List.of(second), scheduler.expired());
        assertEquals(0, scheduler.size());
    }

    @Test
    void maximumAndOverflowingDurationsRemainPendingAndCancelable() {
        TestClock clock = new TestClock(100);
        Scheduler scheduler = new Scheduler(clock);
        Cancelable maximum = scheduler.schedule(() -> fail("Maximum timeout expired immediately"),
                Duration.ofNanos(Long.MAX_VALUE));
        Cancelable overflow = assertDoesNotThrow(() -> scheduler.schedule(
                () -> fail("Overflow timeout expired immediately"), Duration.ofSeconds(Long.MAX_VALUE)));
        assertTrue(scheduler.expired().isEmpty());
        clock.time += 100;
        assertTrue(scheduler.expired().isEmpty());
        maximum.cancel();
        overflow.cancel();
        assertEquals(0, scheduler.size());
    }

    @Test
    void equalDeadlinesRemainDistinctAndCancellationDoesNotRemoveItsNeighbor() {
        TestClock clock = new TestClock(-100);
        Scheduler scheduler = new Scheduler(clock);
        Runnable first = () -> {};
        Runnable second = () -> {};
        Cancelable canceled = scheduler.schedule(first, Duration.ofNanos(10));
        scheduler.schedule(second, Duration.ofNanos(10));
        canceled.cancel();
        clock.time += 10;
        assertEquals(List.of(second), scheduler.expired());
    }

    private static final class TestClock implements Clock {
        long time;
        TestClock(long time) { this.time = time; }
        @Override public long nanoTime() { return this.time; }
    }
}
