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

import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionAuthorizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

public class McpSubscriptionRuntimeConfigurationTests {
	@Test
	public void compatibilityConstructionRetainsAuthorizationDefaults() {
		McpSubscriptionRuntimeConfiguration configuration =
				new McpSubscriptionRuntimeConfiguration(7,
						Duration.ofSeconds(9), Duration.ofSeconds(3),
						Duration.ofSeconds(11), 13, Duration.ofMinutes(17));

		Assertions.assertEquals(Duration.ofSeconds(5),
				configuration.catalogProjectionTimeout());
		Assertions.assertEquals(Duration.ofSeconds(5),
				configuration.authorizationTimeout());
		Assertions.assertEquals(Duration.ofMinutes(1),
				configuration.maximumAuthorizationDuration());
		Assertions.assertEquals(Optional.empty(), configuration.authorizer());
	}

	@Test
	public void exactAuthorizationConfigurationIsPreserved() {
		McpSubscriptionAuthorizer authorizer = (context, features) ->
				McpSubscriptionAuthorization.deniedInstance();
		McpSubscriptionRuntimeConfiguration configuration =
				new McpSubscriptionRuntimeConfiguration(7,
						Duration.ofSeconds(9), Duration.ofSeconds(3),
						Duration.ofSeconds(11), 13, Duration.ofMinutes(17),
						Duration.ofSeconds(19), Duration.ofSeconds(23),
						Duration.ofSeconds(29), Optional.of(authorizer));

		Assertions.assertEquals(Duration.ofSeconds(19),
				configuration.catalogProjectionTimeout());
		Assertions.assertEquals(Duration.ofSeconds(23),
				configuration.authorizationTimeout());
		Assertions.assertEquals(Duration.ofSeconds(29),
				configuration.maximumAuthorizationDuration());
		Assertions.assertSame(authorizer,
				configuration.authorizer().orElseThrow());
	}

	@Test
	public void authorizationDurationsMustBePositiveAndFinite() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> exactConfiguration(Duration.ZERO, Duration.ofSeconds(2),
						Duration.ofSeconds(3)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> exactConfiguration(Duration.ofSeconds(1),
						Duration.ofNanos(-1), Duration.ofSeconds(3)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> exactConfiguration(Duration.ofSeconds(1),
						Duration.ofSeconds(2),
						Duration.ofSeconds(Long.MAX_VALUE)));
	}

	private static McpSubscriptionRuntimeConfiguration exactConfiguration(
			Duration catalogProjectionTimeout, Duration authorizationTimeout,
			Duration maximumAuthorizationDuration) {
		return new McpSubscriptionRuntimeConfiguration(7,
				Duration.ofSeconds(9), Duration.ofSeconds(3),
				Duration.ofSeconds(11), 13, Duration.ofMinutes(17),
				catalogProjectionTimeout, authorizationTimeout,
				maximumAuthorizationDuration, Optional.empty());
	}
}
