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

package com.soklet.internal.mcp.transport;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

public class McpExecutionDefaultsTests {
	@Test
	public void handler_admission_defaults_are_fixed_positive_and_finite() {
		Assertions.assertEquals(32,
				McpExecutionDefaults.REQUEST_HANDLER_CONCURRENCY);
		Assertions.assertEquals(128,
				McpExecutionDefaults.REQUEST_HANDLER_QUEUE_CAPACITY);
		Assertions.assertTrue(
				McpExecutionDefaults.REQUEST_HANDLER_CONCURRENCY > 0);
		Assertions.assertTrue(
				McpExecutionDefaults.REQUEST_HANDLER_QUEUE_CAPACITY > 0);
	}

	@Test
	public void transport_configuration_accepts_the_selected_defaults_for_both_strategies() {
		for (McpThreadStrategy threadStrategy : McpThreadStrategy.values()) {
			if (!threadStrategy.supported())
				continue;

			McpTransportConfiguration configuration = new McpTransportConfiguration(
					"127.0.0.1",
					0,
					1,
					64,
					McpExecutionDefaults.REQUEST_HANDLER_CONCURRENCY,
					McpExecutionDefaults.REQUEST_HANDLER_QUEUE_CAPACITY,
					8,
					8_192,
					McpTransportConfiguration.MINIMUM_FRAMEWORK_TERMINAL_BYTE_CAPACITY,
					Duration.ofSeconds(30),
					Duration.ofSeconds(30),
					Duration.ofSeconds(15),
					threadStrategy);

			Assertions.assertEquals(
					McpExecutionDefaults.REQUEST_HANDLER_CONCURRENCY,
					configuration.handlerConcurrency());
			Assertions.assertEquals(
					McpExecutionDefaults.REQUEST_HANDLER_QUEUE_CAPACITY,
					configuration.handlerQueueCapacity());
		}
	}
}

