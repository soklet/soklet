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

import java.time.Duration;

class LifecyclePolicyTests {
	@Test
	void defaultPolicyHasFourFiniteTimeouts() {
		LifecyclePolicy policy = LifecyclePolicy.fromDefaults();

		Assertions.assertEquals(Duration.ofSeconds(30),
				policy.getStartupTimeout());
		Assertions.assertEquals(Duration.ofSeconds(2),
				policy.getStartupCancelationTimeout());
		Assertions.assertEquals(Duration.ofSeconds(15),
				policy.getGracefulShutdownTimeout());
		Assertions.assertEquals(Duration.ofSeconds(3),
				policy.getForcedShutdownTimeout());
	}

	@Test
	void nullRestoresEachBuiltInDefault() {
		LifecyclePolicy policy = LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(1))
				.startupTimeout(null)
				.startupCancelationTimeout(Duration.ofSeconds(1))
				.startupCancelationTimeout(null)
				.gracefulShutdownTimeout(Duration.ofSeconds(1))
				.gracefulShutdownTimeout(null)
				.forcedShutdownTimeout(Duration.ofSeconds(1))
				.forcedShutdownTimeout(null)
				.build();

		Assertions.assertEquals(Duration.ofSeconds(30),
				policy.getStartupTimeout());
		Assertions.assertEquals(Duration.ofSeconds(2),
				policy.getStartupCancelationTimeout());
		Assertions.assertEquals(Duration.ofSeconds(15),
				policy.getGracefulShutdownTimeout());
		Assertions.assertEquals(Duration.ofSeconds(3),
				policy.getForcedShutdownTimeout());
	}

	@Test
	void zeroRemainsAnImmediateBoundary() {
		LifecyclePolicy policy = LifecyclePolicy.builder()
				.startupTimeout(Duration.ZERO)
				.startupCancelationTimeout(Duration.ZERO)
				.gracefulShutdownTimeout(Duration.ZERO)
				.forcedShutdownTimeout(Duration.ZERO)
				.build();

		Assertions.assertEquals(Duration.ZERO, policy.getStartupTimeout());
		Assertions.assertEquals(Duration.ZERO,
				policy.getStartupCancelationTimeout());
		Assertions.assertEquals(Duration.ZERO,
				policy.getGracefulShutdownTimeout());
		Assertions.assertEquals(Duration.ZERO,
				policy.getForcedShutdownTimeout());
	}

	@Test
	void negativeAndNanosecondOverflowingTimeoutsAreRejected() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				LifecyclePolicy.builder().startupTimeout(Duration.ofNanos(-1)));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				LifecyclePolicy.builder().forcedShutdownTimeout(
						Duration.ofSeconds(Long.MAX_VALUE)));
	}
}
