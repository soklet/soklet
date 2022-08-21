/**
 * MIT License
 *
 * Copyright (c) 2022 Elliot Barlas
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.soklet.microhttp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Scheduler is a simple data structure for efficiently scheduling deferred tasks and draining
 * expired tasks. A {@link Cancellable} handle is returned to clients when a new task is scheduled.
 * That handle can be used to cancel a task.
 */
class Scheduler {

    private final Clock clock;
    private final SortedSet<Task> tasks;
    private long counter;

    Scheduler() {
        this(new SystemClock());
    }

    Scheduler(Clock clock) {
        this.clock = clock;
        this.tasks = new TreeSet<>(Comparator.comparing((Task t) -> t.time).thenComparing(t -> t.id));
    }

    int size() {
        return tasks.size();
    }

    Cancellable schedule(Runnable task, Duration duration) {
        Task t = new Task(task, clock.nanoTime() + duration.toNanos(), counter++);
        tasks.add(t);
        return t;
    }

    List<Runnable> expired() {
        long time = clock.nanoTime();
        List<Runnable> result = new ArrayList<>();
        Iterator<Task> it = tasks.iterator();
        Task item;
        while (it.hasNext() && (item = it.next()).time <= time) {
            result.add(item.task);
            it.remove();
        }
        return result;
    }

    class Task implements Cancellable {
        final Runnable task;
        final long time;
        final long id;

        Task(Runnable task, long time, long id) {
            this.task = task;
            this.time = time;
            this.id = id;
        }

        @Override
        public void cancel() {
            tasks.remove(this);
        }
    }

}
