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
	private final long processingStartedNanos;
	private final CountDownLatch handlingFinished = new CountDownLatch(1);
	private final AtomicBoolean delivered = new AtomicBoolean();
	private volatile boolean streaming;

	HttpResponseStreamObservation(long processingStartedNanos) { this.processingStartedNanos = processingStartedNanos; }

	void completeHandling(boolean streaming) {
		this.streaming = streaming;
		this.handlingFinished.countDown();
	}

	void deliver(@NonNull StreamingResponseHandle handle, @NonNull StreamTermination termination,
			long terminatedNanos, long bodyBytes, @Nullable MetricsCollector metricsCollector,
			@NonNull Consumer<LogEvent> log) {
		boolean interrupted = false;
		for (;;) {
			try { this.handlingFinished.await(); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
		if (!this.streaming || metricsCollector == null || !this.delivered.compareAndSet(false, true)) return;
		try {
			metricsCollector.didTerminateResponseStream(handle, termination,
					Duration.ofNanos(Math.max(0L, terminatedNanos - this.processingStartedNanos)), bodyBytes);
		} catch (Throwable throwable) {
			log.accept(LogEvent.with(LogEventType.METRICS_COLLECTOR_FAILED,
					"An exception occurred while invoking MetricsCollector::didTerminateResponseStream")
					.throwable(throwable).request(handle.getRequest()).build());
		}
	}
}
