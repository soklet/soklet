package com.soklet.internal.microhttp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Scheduler is a simple data structure for efficiently scheduling deferred tasks and draining
 * expired tasks. A {@link Cancelable} handle is returned to clients when a new task is scheduled.
 * That handle can be used to cancel a task.
 * <p>
 * Instances are owned by a single {@link ConnectionEventLoop} thread and are not thread-safe.
 */
class Scheduler {

    private final Clock clock;
    private final long clockOrigin;
    private final SortedSet<Task> tasks;
    private long counter;

    Scheduler() {
        this(new SystemClock());
    }

    Scheduler(Clock clock) {
        this.clock = clock;
        this.clockOrigin = clock.nanoTime();
        this.tasks = new TreeSet<>(Comparator.comparing((Task t) -> t.time).thenComparing(t -> t.id));
    }

    int size() {
        return tasks.size();
    }

    Cancelable schedule(Runnable task, Duration duration) {
        if (duration.isNegative())
            throw new IllegalArgumentException("Scheduled duration must not be negative");
        long delay;
        try {
            delay = duration.toNanos();
        } catch (ArithmeticException overflow) {
            delay = Long.MAX_VALUE;
        }
        long now = elapsedNanos();
        long deadline = delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
        Task t = new Task(task, deadline, counter++);
        tasks.add(t);
        return t;
    }

	    List<Runnable> expired() {
	        long time = elapsedNanos();
	        List<Runnable> result = new ArrayList<>();
	        Iterator<Task> it = tasks.iterator();
	        while (it.hasNext()) {
	            Task item = it.next();
	            if (item.time > time) {
	                break;
	            }
	            result.add(item.task);
	            it.remove();
	        }
        return result;
    }

    private long elapsedNanos() {
        // Order deadlines relative to this scheduler's creation, rather than the
        // arbitrary signed nanoTime origin. Subtraction preserves short elapsed
        // intervals across the clock's signed wrap. As with nanoTime deltas, the
        // scheduler lifetime must remain below the signed-nanosecond horizon.
        return clock.nanoTime() - clockOrigin;
    }

    class Task implements Cancelable {
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
