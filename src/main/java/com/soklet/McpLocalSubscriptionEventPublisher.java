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

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Built-in thread-safe in-process MCP subscription-event publisher.
 * <p>
 * Each publisher is independent and reaches only listeners registered with
 * that instance in the current process. It invokes listeners synchronously on
 * the publishing thread. A listener should therefore return promptly. If a
 * listener throws a runtime exception, all other current listeners are still
 * attempted before the first exception is rethrown with later exceptions
 * suppressed.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpLocalSubscriptionEventPublisher
		implements McpSubscriptionEventPublisher {
	@NonNull
	private final CopyOnWriteArrayList<@NonNull Registration> registrations;

	/**
	 * Creates an independent process-local publisher with default behavior.
	 *
	 * @return process-local event publisher
	 */
	@NonNull
	public static McpLocalSubscriptionEventPublisher fromDefaults() {
		return new McpLocalSubscriptionEventPublisher();
	}

	private McpLocalSubscriptionEventPublisher() {
		this.registrations = new CopyOnWriteArrayList<>();
	}

	/**
	 * Registers a listener for synchronous process-local broadcast.
	 *
	 * @param listener listener to register
	 * @return idempotently closable registration
	 */
	@Override
	@NonNull
	public McpSubscriptionEventSubscription subscribe(
			@NonNull McpSubscriptionEventListener listener) {
		Registration registration = new Registration(this,
				requireNonNull(listener));
		this.registrations.add(registration);
		return registration;
	}

	/**
	 * Broadcasts an event synchronously to every current listener.
	 *
	 * @param event event to broadcast
	 * @throws RuntimeException after all listeners have been attempted if one or
	 *                          more listeners fail
	 */
	@Override
	public void publish(@NonNull McpSubscriptionEvent event) {
		requireNonNull(event);
		RuntimeException firstFailure = null;

		for (Registration registration : this.registrations) {
			try {
				registration.deliver(event);
			} catch (RuntimeException exception) {
				if (firstFailure == null)
					firstFailure = exception;
				else if (firstFailure != exception)
					firstFailure.addSuppressed(exception);
			}
		}

		if (firstFailure != null)
			throw firstFailure;
	}

	private void unregister(@NonNull Registration registration) {
		this.registrations.remove(registration);
	}

	/**
	 * One idempotently closable process-local listener registration.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	private static final class Registration
			implements McpSubscriptionEventSubscription {
		@NonNull
		private final McpLocalSubscriptionEventPublisher publisher;
		@NonNull
		private final McpSubscriptionEventListener listener;
		@NonNull
		private final AtomicBoolean open;

		private Registration(
				@NonNull McpLocalSubscriptionEventPublisher publisher,
				@NonNull McpSubscriptionEventListener listener) {
			this.publisher = requireNonNull(publisher);
			this.listener = requireNonNull(listener);
			this.open = new AtomicBoolean(true);
		}

		private void deliver(@NonNull McpSubscriptionEvent event) {
			if (this.open.get())
				this.listener.onEvent(event);
		}

		@Override
		public void close() {
			if (this.open.compareAndSet(true, false))
				this.publisher.unregister(this);
		}
	}
}
