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
package com.soklet.internal.streaming;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static java.util.Objects.requireNonNull;

/**
 * Waits for monitor-guarded state without retaining a virtual-thread carrier.
 * Predicates and registration share the owner's monitor; parking always occurs
 * outside it. An unpark permit covers a signal between registration and parking.
 * Predicates contain only bounded internal state checks, never application code.
 */
@ThreadSafe
public final class StateChangeWaiters {
	private final Object monitor;
	private final Set<Thread> waiters = new HashSet<>();

	public StateChangeWaiters(@NonNull Object monitor) {
		this.monitor = requireNonNull(monitor);
	}

	/** Call without holding the owner monitor; always recheck state before mutation. */
	public void awaitWhile(@NonNull BooleanSupplier waiting) throws InterruptedException {
		requireNonNull(waiting);
		if (Thread.holdsLock(this.monitor))
			throw new IllegalStateException("A state wait cannot retain its owner monitor");
		Thread current = Thread.currentThread();
		try {
			while (true) {
				synchronized (this.monitor) {
					if (!waiting.getAsBoolean())
						return;
					if (Thread.interrupted())
						throw new InterruptedException();
					this.waiters.add(current);
				}
				if (current instanceof ForkJoinWorkerThread)
					ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
						@Override public boolean isReleasable() {
							if (current.isInterrupted())
								return true;
							synchronized (monitor) {
								return !waiting.getAsBoolean();
							}
						}
						@Override public boolean block() {
							if (!isReleasable())
								LockSupport.park(StateChangeWaiters.this);
							return isReleasable();
						}
					});
				else
					LockSupport.park(this);
				if (Thread.interrupted())
					throw new InterruptedException();
			}
		} finally {
			synchronized (this.monitor) {
				this.waiters.remove(current);
			}
		}
	}

	/** Signal while holding the same monitor used to change the owner's state. */
	public void signalAll() {
		if (!Thread.holdsLock(this.monitor))
			throw new IllegalStateException("A state signal requires its owner monitor");
		for (Thread waiter : this.waiters)
			LockSupport.unpark(waiter);
	}
}
