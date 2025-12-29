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

/**
 * Contract for collecting operational metrics from Soklet.
 * <p>
 * Implementations might bridge to external systems (OpenTelemetry, Micrometer, Prometheus, etc.)
 * <p>
 * <p>All methods must be:
 * <ul>
 *   <li><strong>Thread-safe</strong> — called concurrently from multiple request threads</li>
 *   <li><strong>Non-blocking</strong> — should not perform I/O or acquire locks that might contend</li>
 *   <li><strong>Failure-tolerant</strong> — exceptions are caught and logged, never break request handling</li>
 * </ul>
 * See <a href="https://www.soklet.com/docs/metrics-collection">https://www.soklet.com/docs/metrics-collection</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface MetricsCollector {
	// TODO: introduce methods for metrics collection

	/**
	 * Acquires a threadsafe {@link MetricsCollector} instance with sensible defaults.
	 * <p>
	 * This method is guaranteed to return a new instance.
	 *
	 * @return a {@code MetricsCollector} with default settings
	 */
	@NonNull
	static MetricsCollector withDefaults() {
		return DefaultMetricsCollector.withDefaults();
	}
}
