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

import com.soklet.CallbackRegistration;
import com.soklet.CancelationToken;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamingResponseCanceledException;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Shared publisher adapter. A normally returned subscribe call leaves an acquisition
 * obligation until its first subscription arrives, even after the producer exits.
 * Provider calls and entered callback frames retain that obligation through physical
 * completion. No application code executes while holding the state lock.
 */
public final class PublisherResponseStream implements Flow.Subscriber<ByteBuffer> {

	private final Object lock = new Object();
	private final CancelationToken cancelationToken;
	private final ManagedResponseStream.Output output;
	private final Runnable beginFinalization;
	private final Consumer<Throwable> failureConsumer;
	private final Consumer<Throwable> cleanupFailureConsumer;
	@Nullable private final StreamLifecycleCoordinator.PublisherWork publisherWork;
	private final CountDownLatch completed = new CountDownLatch(1);
	private final CountDownLatch released = new CountDownLatch(1);
	private final CountDownLatch cancelCompleted = new CountDownLatch(1);
	private final AtomicReference<Throwable> failure = new AtomicReference<>();
	private Flow.@Nullable Subscription subscription;
	private boolean acquisitionFailed;
	private boolean terminal;
	private boolean stopping;
	private boolean cancelClaimed;
	private boolean cancelFinished;
	private boolean lifetimeReleased;
	private int enteredCalls;

	private PublisherResponseStream(CancelationToken cancelationToken, ManagedResponseStream.Output output,
			@Nullable StreamLifecycleCoordinator.Reservation reservation, Runnable beginFinalization,
			Consumer<Throwable> failureConsumer, Consumer<Throwable> cleanupFailureConsumer) {
		this.cancelationToken = requireNonNull(cancelationToken);
		this.output = requireNonNull(output);
		this.beginFinalization = requireNonNull(beginFinalization);
		this.failureConsumer = requireNonNull(failureConsumer);
		this.cleanupFailureConsumer = requireNonNull(cleanupFailureConsumer);
		this.publisherWork = reservation == null ? null : reservation.retainPublisher();
	}

	public static void copy(StreamingResponseBody.PublisherBody body, CancelationToken cancelationToken,
			ManagedResponseStream.Output output, @Nullable StreamLifecycleCoordinator.Reservation reservation,
			Runnable beginFinalization, Consumer<Throwable> failureConsumer,
			Consumer<Throwable> cleanupFailureConsumer) throws Exception {
		requireNonNull(body);
		cancelationToken.throwIfCanceled();
		PublisherResponseStream stream;
		try {
			stream = new PublisherResponseStream(cancelationToken, output, reservation, beginFinalization,
					failureConsumer, cleanupFailureConsumer);
		} catch (IllegalStateException failure) {
			// Cancelation can seal publication between the initial check and retention.
			// Suppressed acquisition still exposes its elected typed outcome.
			cancelationToken.throwIfCanceled();
			throw failure;
		}
		stream.consume(body.getPublisher());
	}

	private void consume(Flow.Publisher<ByteBuffer> publisher) throws Exception {
		// Keep the obligation alive while installing the registration and entering
		// subscribe, including synchronous reentrant terminal callbacks.
		synchronized (this.lock) { this.enteredCalls++; }
		try (CallbackRegistration registration = this.cancelationToken.onCancel(this::cancelSubscription)) {
			try {
				this.cancelationToken.throwIfCanceled();
				publisher.subscribe(this);
			} catch (Throwable throwable) {
				// A throwing acquisition must not subsequently deliver a subscription.
				// If it already supplied one, its existing cleanup remains owned.
				synchronized (this.lock) {
					if (this.subscription == null) this.acquisitionFailed = true;
				}
				fail(throwable);
			} finally {
				exitCall();
			}

			try {
				while (!this.completed.await(100L, TimeUnit.MILLISECONDS))
					this.cancelationToken.throwIfCanceled();
				this.cancelationToken.throwIfCanceled();
				throwFailure();
				// Completion can be reentrant inside request(). Do not elect successful
				// production while that provider frame can still block or fail.
				this.beginFinalization.run();
				while (!this.released.await(100L, TimeUnit.MILLISECONDS))
					this.cancelationToken.throwIfCanceled();
				this.cancelationToken.throwIfCanceled();
				throwFailure();
			} catch (Throwable throwable) {
				fail(throwable);
			}
		} finally {
			boolean shouldCancel;
			synchronized (this.lock) { shouldCancel = !this.terminal; }
			if (shouldCancel) {
				this.beginFinalization.run();
				cancelSubscription();
				// Preserve existing synchronous behavior for an already-started cancel.
				// A still-pending acquisition consumes no worker or waiting producer.
				boolean interrupted = false;
				boolean claimed;
				synchronized (this.lock) { claimed = this.cancelClaimed; }
				while (claimed) {
					try {
						this.cancelCompleted.await();
						break;
					} catch (InterruptedException ignored) {
						interrupted = true;
					}
				}
				if (interrupted) Thread.currentThread().interrupt();
			}
		}
		throwFailure();
	}

	@Override
	public void onSubscribe(Flow.Subscription subscription) {
		requireNonNull(subscription);
		boolean duplicate;
		boolean same;
		synchronized (this.lock) {
			if (this.lifetimeReleased || this.acquisitionFailed)
				throw new IllegalStateException("Publisher supplied a subscription after its acquisition or lifetime ended");
			this.enteredCalls++;
			duplicate = this.subscription != null;
			same = this.subscription == subscription;
			if (!duplicate) {
				this.subscription = subscription;
				if (this.publisherWork != null) this.publisherWork.subscriptionReceived();
			}
		}
		try {
			if (duplicate) {
				// Repeated delivery of the original must not cause a second cancel.
				if (same) {
					fail(new IllegalStateException("Publisher delivered the same subscription more than once"));
					cancelSubscription();
				}
				else cancelRejectedSubscription(subscription);
			} else if (!requestNext()) {
				cancelSubscription();
			}
		} catch (Throwable throwable) {
			fail(throwable);
			cancelSubscription();
		} finally {
			exitCall();
		}
	}

	@Override
	public void onNext(ByteBuffer item) {
		if (!enterSignal()) return;
		try {
			synchronized (this.lock) {
				if (this.subscription == null) {
					this.acquisitionFailed = true;
					throw new IllegalStateException("Publisher delivered data before a subscription");
				}
			}
			this.cancelationToken.throwIfCanceled();
			this.output.write(requireNonNull(item).duplicate());
			this.cancelationToken.throwIfCanceled();
			requestNext();
		} catch (Throwable throwable) {
			fail(throwable);
			this.beginFinalization.run();
			cancelSubscription();
		} finally {
			exitCall();
		}
	}

	@Override
	public void onError(Throwable throwable) {
		end(throwable == null ? new IllegalStateException("Publisher failed without an error") : throwable);
	}

	@Override
	public void onComplete() { end(null); }

	private void end(@Nullable Throwable throwable) {
		if (!enterSignal()) return;
		try {
			synchronized (this.lock) {
				if (this.subscription == null) {
					this.acquisitionFailed = true;
					throwable = new IllegalStateException("Publisher terminated before supplying a subscription", throwable);
				}
				this.terminal = true;
			}
			if (throwable != null) fail(throwable);
			// A synchronous publisher may still be inside subscribe/request after
			// signaling completion. Supervise that physical tail immediately.
			this.beginFinalization.run();
			this.completed.countDown();
		} finally {
			exitCall();
		}
	}

	private boolean enterSignal() {
		synchronized (this.lock) {
			if (this.lifetimeReleased || this.terminal || this.stopping) return false;
			this.enteredCalls++;
			return true;
		}
	}

	private boolean requestNext() {
		Flow.Subscription current;
		synchronized (this.lock) {
			if (this.stopping || this.terminal || this.cancelationToken.isCanceled()) return false;
			current = requireNonNull(this.subscription);
			// This check claims demand before a competing cancelation. The enclosing
			// callback remains counted while the already-claimed request runs.
		}
		current.request(1L);
		return true;
	}

	private void cancelSubscription() {
		Flow.Subscription current;
		synchronized (this.lock) {
			if (this.lifetimeReleased) return;
			this.stopping = true;
			this.completed.countDown();
			current = this.subscription;
			if (current == null || this.terminal || this.cancelClaimed) return;
			this.cancelClaimed = true;
			this.enteredCalls++;
		}
		try {
			current.cancel();
		} catch (Throwable throwable) {
			this.failure.compareAndSet(null, throwable);
			this.cleanupFailureConsumer.accept(throwable);
		} finally {
			synchronized (this.lock) {
				this.cancelFinished = true;
				this.cancelCompleted.countDown();
			}
			exitCall();
		}
	}

	private void cancelRejectedSubscription(Flow.Subscription rejected) {
		try {
			rejected.cancel();
		} catch (Throwable throwable) {
			this.cleanupFailureConsumer.accept(throwable);
			throw throwable;
		}
	}

	private void fail(Throwable throwable) {
		boolean first = this.failure.compareAndSet(null, throwable);
		boolean alreadyCanceled = this.cancelationToken.isCanceled();
		if (first) this.failureConsumer.accept(throwable);
		// A provider frame may fail after the canceled producer has already exited.
		// Keep its actual failure as bounded evidence without replacing the winner.
		if ((!first && throwable != this.failure.get() || first && alreadyCanceled)
				&& !(throwable instanceof InterruptedException)
				&& !(throwable instanceof StreamingResponseCanceledException))
			this.cleanupFailureConsumer.accept(throwable);
		this.completed.countDown();
	}

	private void exitCall() {
		synchronized (this.lock) {
			this.enteredCalls--;
			boolean resolved = this.acquisitionFailed || (this.subscription != null && (this.terminal || this.cancelFinished));
			if (!this.lifetimeReleased && resolved && this.enteredCalls == 0) {
				this.lifetimeReleased = true;
				this.subscription = null;
				if (this.publisherWork != null) this.publisherWork.close();
				this.released.countDown();
			}
		}
	}

	private void throwFailure() throws Exception {
		Throwable throwable = this.failure.get();
		if (throwable instanceof Exception exception) throw exception;
		if (throwable instanceof Error error) throw error;
		if (throwable != null) throw new RuntimeException(throwable);
	}
}
