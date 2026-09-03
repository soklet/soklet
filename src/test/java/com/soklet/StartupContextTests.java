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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

class StartupContextTests {
	@Test
	void remainingTimeIsFiniteAndClampedAtZero() {
		AtomicLong now = new AtomicLong(5L);
		StartupContext context = new StartupContext(now::get, 10L, 30L,
				() -> false);

		Assertions.assertEquals(Duration.ofNanos(5L),
				context.getRemainingTime());
		now.set(10L);
		Assertions.assertEquals(Duration.ZERO, context.getRemainingTime());
		now.set(20L);
		Assertions.assertEquals(Duration.ZERO, context.getRemainingTime());
	}

	@Test
	void cancelationSwitchesToTheCancelationDeadline() {
		AtomicLong now = new AtomicLong(5L);
		AtomicLong cancelationDeadline = new AtomicLong(30L);
		AtomicBoolean cancelationRequested = new AtomicBoolean();
		StartupContext context = new StartupContext(now::get, 10L,
				cancelationDeadline::get, cancelationRequested::get);

		Assertions.assertEquals(Duration.ofNanos(5L),
				context.getRemainingTime());
		cancelationRequested.set(true);
		Assertions.assertEquals(Duration.ofNanos(25L),
				context.getRemainingTime());
		cancelationDeadline.set(4L);
		Assertions.assertEquals(Duration.ZERO, context.getRemainingTime());
	}
}
