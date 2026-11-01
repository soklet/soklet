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

package com.soklet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.soklet.internal.ObjectIdentity.sameInstance;
import static java.util.Objects.requireNonNull;

/**
 * One reserved daemon for potentially blocking interrupt delivery. Requesting
 * interruption and retiring unused delivery never invoke a target's code.
 */
@ThreadSafe
final class LifecycleInterruptDelivery {
	private final Object monitor = new Object();
	@NonNull
	private final Runnable afterDelivery;
	private final AtomicBoolean completionClaimed = new AtomicBoolean();
	@Nullable
	private Thread target;
	private boolean requested;
	private boolean retired;
	private boolean deliveryClaimed;

	LifecycleInterruptDelivery(@NonNull LifecycleWorkers workers,
			@NonNull String name, @NonNull Runnable afterDelivery) {
		this.afterDelivery = requireNonNull(afterDelivery);
		try {
			requireNonNull(workers).start(LifecycleWorkers.Role.INTERRUPT_DELIVERY,
					requireNonNull(name), this::run);
		} catch (RuntimeException | Error launchFailure) {
			try {
				retire();
			} catch (Throwable retirementFailure) {
				if (!sameInstance(launchFailure, retirementFailure))
					launchFailure.addSuppressed(retirementFailure);
			}
			throw launchFailure;
		}
	}

	void bind(@NonNull Thread target) {
		Thread exactTarget = requireNonNull(target);
		boolean deliverOnTarget = false;
		synchronized (this.monitor) {
			if (this.retired)
				return;
			if (this.target != null && !sameInstance(this.target, exactTarget))
				throw new IllegalStateException("Interrupt delivery already has a target");
			if (this.requested && !this.deliveryClaimed
					&& sameInstance(Thread.currentThread(), exactTarget)) {
				// Before app code, self-interruption cannot invoke an NIO close
				// callback. Publish its interrupt flag before the action begins.
				this.deliveryClaimed = true;
				this.retired = true;
				this.target = null;
				deliverOnTarget = true;
			} else {
				this.target = exactTarget;
			}
			this.monitor.notifyAll();
		}
		if (deliverOnTarget) {
			try {
				exactTarget.interrupt();
			} catch (Throwable ignored) {
				// A denied self-interrupt cannot strand its delivery ownership.
			} finally {
				completeDelivery();
			}
		}
	}

	void request() {
		synchronized (this.monitor) {
			if (this.retired)
				return;
			this.requested = true;
			this.monitor.notifyAll();
		}
	}

	void retire() {
		boolean release;
		synchronized (this.monitor) {
			this.retired = true;
			this.target = null;
			release = !this.deliveryClaimed
					&& this.completionClaimed.compareAndSet(false, true);
			this.monitor.notifyAll();
		}
		if (release)
			this.afterDelivery.run();
	}

	private void run() {
		boolean interrupted = false;
		boolean ownsDelivery = false;
		try {
			Thread target;
			synchronized (this.monitor) {
				while (!this.retired && (!this.requested || this.target == null)) {
					try {
						this.monitor.wait();
					} catch (InterruptedException interruption) {
						interrupted = true;
					}
				}
				if (this.retired)
					return;
				target = requireNonNull(this.target);
				this.deliveryClaimed = true;
				ownsDelivery = true;
			}
			try {
				// Interruptible-channel close callbacks run here, never on the
				// lifecycle owner, coordinator, caller, or JVM shutdown hook.
				target.interrupt();
			} catch (Throwable ignored) {
				// Intent and deadlines remain authoritative if delivery fails.
			}
		} finally {
			try {
				if (ownsDelivery)
					completeDelivery();
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		}
	}

	private void completeDelivery() {
		if (this.completionClaimed.compareAndSet(false, true))
			this.afterDelivery.run();
	}
}
