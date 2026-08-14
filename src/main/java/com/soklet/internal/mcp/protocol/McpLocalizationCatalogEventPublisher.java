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

import com.soklet.internal.mcp.protocol.McpSubscriptionEventSource.Event;
import com.soklet.internal.mcp.protocol.McpSubscriptionEventSource.Listener;
import com.soklet.internal.mcp.protocol.McpSubscriptionEventSource.Registration;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Objects.requireNonNull;

/**
 * Framework-owned catalog-change event source for one localization-enabled
 * endpoint.
 * <p>
 * It rides the exact subscription event machinery an application publisher
 * uses - the same generation fencing, filtering, coalescing, backpressure, and
 * shutdown behavior - so composed sources never diverge. Publication holds no
 * lock while listeners run.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLocalizationCatalogEventPublisher {
	@NonNull
	private final List<@NonNull Listener> listeners = new CopyOnWriteArrayList<>();

	@NonNull
	Registration subscribe(@NonNull Listener listener) {
		requireNonNull(listener);
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	void publish(@NonNull Event event) {
		requireNonNull(event);

		for (Listener listener : listeners) {
			try {
				listener.onEvent(event);
			} catch (Throwable ignored) {
				// One generation's failure never alters peer delivery.
			}
		}
	}
}
