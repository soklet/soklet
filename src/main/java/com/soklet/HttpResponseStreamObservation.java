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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Internal, constant-size ordering record for one HTTP dispatch. */
@javax.annotation.concurrent.ThreadSafe
final class HttpResponseStreamObservation {
	@NonNull
	private final Request request;
	private final long processingStartedNanos;
	private final CountDownLatch handlingFinished = new CountDownLatch(1);
	private final AtomicBoolean delivered = new AtomicBoolean();
	private final AtomicBoolean prepared = new AtomicBoolean();
	@Nullable
	private volatile StreamingResponseHandle preparedHandle;
	private volatile long establishedNanos;
	private volatile boolean streaming;

	HttpResponseStreamObservation(@NonNull Request request, long processingStartedNanos) {
		this.request = java.util.Objects.requireNonNull(request);
		this.processingStartedNanos = processingStartedNanos;
	}

	/** The original framework dispatch identity, before interceptor replacement. */
	@NonNull
	Request getRequest() { return this.request; }

	void prepare(@NonNull StreamingResponseHandle handle, long establishedNanos, @Nullable MetricsCollector metricsCollector,
			@NonNull LifecycleObserver lifecycleObserver, @NonNull Consumer<LogEvent> log) {
		if (!this.prepared.compareAndSet(false, true))
			return;
		this.establishedNanos = establishedNanos;
		this.preparedHandle = handle;
		if (metricsCollector != null) {
			try {
				metricsCollector.willWriteResponseStream(handle);
			} catch (Throwable throwable) {
				reportFailure(log, LogEvent.with(LogEventType.METRICS_COLLECTOR_FAILED,
						"An exception occurred while invoking MetricsCollector::willWriteResponseStream")
						.throwable(throwable).request(handle.getRequest()).build());
			}
		}
		try {
			lifecycleObserver.willWriteResponseStream(handle);
		} catch (Throwable throwable) {
			reportFailure(log, LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_WRITE_RESPONSE_FAILED,
					"An exception occurred while invoking LifecycleObserver::willWriteResponseStream")
					.throwable(throwable).request(handle.getRequest()).build());
		}
	}

	private static void reportFailure(@NonNull Consumer<LogEvent> log, @NonNull LogEvent event) {
		try { log.accept(event); }
		catch (Throwable throwable) { LifecycleObserverLogFallback.report(throwable); }
	}

	void completeHandling(boolean streaming) {
		this.streaming = streaming;
		this.handlingFinished.countDown();
	}

	@NonNull
	Delivery deliver(@NonNull StreamingResponseHandle handle, @NonNull StreamTermination termination,
			long terminatedNanos, long bodyBytes, @Nullable MetricsCollector metricsCollector,
			@NonNull Consumer<LogEvent> log) {
		boolean interrupted = false;
		for (;;) {
			try { this.handlingFinished.await(); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
		StreamingResponseHandle preparedHandle = this.preparedHandle;
		if (preparedHandle != null) {
			// Anchor the monotonic stream lifetime to the exact prepared handle.
			// Source wall-clock timestamps can step independently of this epoch.
			Duration preparedDuration = Duration.ofNanos(Math.max(0L, terminatedNanos - this.establishedNanos));
			termination = termination.copy().duration(preparedDuration).build();
			handle = preparedHandle;
		}
		Delivery delivery = new Delivery(handle, termination);
		if (!this.streaming || metricsCollector == null || !this.delivered.compareAndSet(false, true)) return delivery;
		try {
			metricsCollector.didTerminateResponseStream(handle, termination,
					Duration.ofNanos(Math.max(0L, terminatedNanos - this.processingStartedNanos)), bodyBytes);
		} catch (Throwable throwable) {
			log.accept(LogEvent.with(LogEventType.METRICS_COLLECTOR_FAILED,
					"An exception occurred while invoking MetricsCollector::didTerminateResponseStream")
					.throwable(throwable).request(handle.getRequest()).build());
		}
		return delivery;
	}

	/** Exact paired handle and its duration, sharing one establishment epoch. */
	record Delivery(@NonNull StreamingResponseHandle handle, @NonNull StreamTermination termination) {}
}
