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

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;

import static java.util.Objects.requireNonNull;

/**
 * Internal application-publisher boundary for resource subscription events.
 * The identity is compared by reference so endpoint bindings backed by the
 * same application publisher share one listener registration per server.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpSubscriptionEventSource(@NonNull Object identity,
		@NonNull Subscriber subscriber) {
	McpSubscriptionEventSource {
		requireNonNull(identity);
		requireNonNull(subscriber);
	}

	@NonNull
	Registration subscribe(@NonNull Listener listener) {
		return requireNonNull(subscriber.subscribe(requireNonNull(listener)),
				"An MCP subscription event publisher returned a null registration.");
	}

	@ThreadSafe
	@FunctionalInterface
	interface Subscriber {
		@NonNull
		Registration subscribe(@NonNull Listener listener);
	}

	@ThreadSafe
	@FunctionalInterface
	interface Listener {
		void onEvent(@NonNull Event event);
	}

	@ThreadSafe
	@FunctionalInterface
	interface Registration extends AutoCloseable {
		@Override
		void close();
	}

	@ThreadSafe
	sealed interface Event permits Event.ResourcesListChanged,
			Event.ResourceUpdated {
		record ResourcesListChanged() implements Event {
		}

		record ResourceUpdated(@NonNull URI resourceUri,
				@NonNull String wireResourceUri) implements Event {
			public ResourceUpdated {
				requireNonNull(resourceUri);
				requireNonNull(wireResourceUri);
			}
		}
	}
}
