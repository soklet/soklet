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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Configuration checks for SSE connection admission; these do not qualify connection workloads. */
public class SseStreamingServerSettingsTests {
	@Test
	public void default_and_null_reset() {
		assertCapacity(server(builder()), 256);
		SseServer.Builder builder = builder().streamingLifecycleCapacity(8);
		assertCapacity(server(builder), 8);
		assertCapacity(server(builder.streamingLifecycleCapacity(null)), 256);
	}

	@Test
	public void invalid_effective_values_fail_at_build() {
		for (int capacity : new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE / 2 + 1, Integer.MAX_VALUE}) {
			SseServer.Builder builder = builder().streamingLifecycleCapacity(capacity);
			Assertions.assertThrows(IllegalArgumentException.class, builder::build, "capacity=" + capacity);
		}
	}

	@Test
	public void exact_bounds_are_valid_without_starting_transport() {
		assertCapacity(server(builder().streamingLifecycleCapacity(1)), 1);
		int maximumCapacity = Integer.MAX_VALUE / 2;
		assertCapacity(server(builder().streamingLifecycleCapacity(maximumCapacity)), maximumCapacity);
	}

	@Test
	public void intermediate_invalid_values_do_not_restrict_final_configuration() {
		SseServer.Builder builder = builder().streamingLifecycleCapacity(0);
		assertCapacity(server(builder.streamingLifecycleCapacity(2)), 2);
	}

	@Test
	public void reusable_builders_do_not_mutate_previous_servers() {
		SseServer.Builder builder = builder().streamingLifecycleCapacity(8);
		DefaultSseServer first = server(builder);
		DefaultSseServer second = server(builder.streamingLifecycleCapacity(12));
		DefaultSseServer reset = server(builder.streamingLifecycleCapacity(null));
		assertCapacity(first, 8);
		assertCapacity(second, 12);
		assertCapacity(reset, 256);
	}

	private static SseServer.Builder builder() { return SseServer.withPort(0); }
	private static DefaultSseServer server(SseServer.Builder builder) { return (DefaultSseServer) builder.build(); }
	private static void assertCapacity(DefaultSseServer server, int capacity) {
		Assertions.assertEquals(capacity, server.getStreamingLifecycleCapacity());
		Assertions.assertFalse(server.isStarted());
	}
}
