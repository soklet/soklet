/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.microhttp;

import com.soklet.MetricsCollector;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Typed observation boundary for one low-level transport failure and all of
 * its synchronous terminal consequences.
 */
@ThreadSafe
public interface TransportFailureObserver {
	/**
	 * Begins observation of one transport failure.
	 *
	 * @param reason bounded failure reason
	 * @return scope that ends observation after terminal consequences complete
	 */
	@NonNull
	Observation beginFailure(
			MetricsCollector.@NonNull TransportFailureReason reason);

	/** A single transport-failure observation scope. */
	interface Observation extends AutoCloseable {
		/** Discards a provisionally recorded failure that did not win. */
		default void discard() {
		}

		/** Ends observation without invoking application callbacks inline. */
		@Override
		void close();
	}

	/**
	 * Returns the shared disabled observer.
	 *
	 * @return disabled observer
	 */
	@NonNull
	static TransportFailureObserver disabledInstance() {
		return DisabledTransportFailureObserver.INSTANCE;
	}
}

/** Shared no-op transport-failure observer and scope. */
enum DisabledTransportFailureObserver
		implements TransportFailureObserver, TransportFailureObserver.Observation {
	INSTANCE;

	@Override
	@NonNull
	public Observation beginFailure(
			MetricsCollector.@NonNull TransportFailureReason reason) {
		if (reason == null)
			throw new NullPointerException("reason");
		return this;
	}

	@Override
	public void close() {
	}
}

/** Failure-safe adapter for transport-owned observer scopes. */
final class TransportFailureObservations {
	private TransportFailureObservations() {
	}

	static TransportFailureObserver.@NonNull Observation beginSafely(
			@NonNull TransportFailureObserver observer,
			MetricsCollector.@NonNull TransportFailureReason reason) {
		try {
			TransportFailureObserver.Observation observation =
					observer.beginFailure(reason);
			if (observation == null)
				return DisabledTransportFailureObserver.INSTANCE;
			return new SafeObservation(observation);
		} catch (Throwable ignored) {
			return DisabledTransportFailureObserver.INSTANCE;
		}
	}

	private static final class SafeObservation
			implements TransportFailureObserver.Observation {
		private final TransportFailureObserver.@NonNull Observation delegate;

		private SafeObservation(
				TransportFailureObserver.@NonNull Observation delegate) {
			this.delegate = delegate;
		}

		@Override
		public void discard() {
			try {
				this.delegate.discard();
			} catch (Throwable ignored) {
				// Observation must not alter the transport transition.
			}
		}

		@Override
		public void close() {
			try {
				this.delegate.close();
			} catch (Throwable ignored) {
				// Observation must not alter the transport transition.
			}
		}
	}
}
