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

package fixtures;

import com.soklet.HttpServer;
import com.soklet.SseServer;

import java.time.Duration;

/** Compile-only builder usage; explicit SSE values do not qualify SSE defaults. */
public final class ServerSettings {

	private ServerSettings() {}

	static HttpServer httpExplicit() {
		return HttpServer.withPort(0)
				.streamingLifecycleCapacity(256)
				.streamingCallbackConcurrency(4)
				.streamingCleanupTimeout(Duration.ofSeconds(5))
				.build();
	}

	static SseServer sseExplicit() {
		return SseServer.withPort(0)
				.streamingLifecycleCapacity(256)
				.streamingCallbackConcurrency(4)
				.streamingCleanupTimeout(Duration.ofSeconds(5))
				.build();
	}

	static HttpServer httpResetDefaults() {
		return HttpServer.withPort(0)
				.streamingLifecycleCapacity(256)
				.streamingCallbackConcurrency(4)
				.streamingCleanupTimeout(Duration.ofSeconds(5))
				.streamingLifecycleCapacity(null)
				.streamingCallbackConcurrency(null)
				.streamingCleanupTimeout(null)
				.build();
	}

	static SseServer sseResetDefaults() {
		return SseServer.withPort(0)
				.streamingLifecycleCapacity(256)
				.streamingCallbackConcurrency(4)
				.streamingCleanupTimeout(Duration.ofSeconds(5))
				.streamingLifecycleCapacity(null)
				.streamingCallbackConcurrency(null)
				.streamingCleanupTimeout(null)
				.build();
	}

	static HttpServer httpDefaultCapacityAndOneWorker() {
		return HttpServer.withPort(0)
				.streamingLifecycleCapacity(null)
				.streamingCallbackConcurrency(1)
				.streamingCleanupTimeout(null)
				.build();
	}

	static SseServer sseDefaultCapacityAndOneWorker() {
		return SseServer.withPort(0)
				.streamingLifecycleCapacity(null)
				.streamingCallbackConcurrency(1)
				.streamingCleanupTimeout(null)
				.build();
	}

	static HttpServer httpOneLifetime() {
		return HttpServer.withPort(0)
				.streamingLifecycleCapacity(1)
				.streamingCallbackConcurrency(1)
				.streamingCleanupTimeout(null)
				.build();
	}

	static SseServer sseOneLifetime() {
		return SseServer.withPort(0)
				.streamingLifecycleCapacity(1)
				.streamingCallbackConcurrency(1)
				.streamingCleanupTimeout(null)
				.build();
	}
}
