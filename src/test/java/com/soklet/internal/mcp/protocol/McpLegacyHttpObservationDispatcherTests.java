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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class McpLegacyHttpObservationDispatcherTests {
	@Test
	void reservationsBoundBothPairsAndRetainLateFinishesThroughForce() throws Exception {
		McpLegacyHttpObservationDispatcher delivery = new McpLegacyHttpObservationDispatcher(1, 1, () -> {});
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger finished = new AtomicInteger(), physicallyExited = new AtomicInteger();
		McpLegacyHttpObservationDispatcher.Reservation first = delivery.reserve(), pendingStart = delivery.reserve();
		assertNotNull(first); assertNotNull(pendingStart); assertNull(delivery.reserve());
		try {
			first.finish(() -> {
				entered.countDown();
				while (release.getCount() != 0) {
					try { release.await(20, TimeUnit.MILLISECONDS); }
					catch (InterruptedException ignored) {}
				}
				finished.incrementAndGet();
			}, physicallyExited::incrementAndGet);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			delivery.stop(true);
			assertFalse(delivery.isTerminated()); assertEquals(2, delivery.outstanding()); assertNull(delivery.reserve());
			pendingStart.finish(finished::incrementAndGet, physicallyExited::incrementAndGet);
			assertEquals(0, finished.get(), "The late finish must remain queued behind the retained physical callback.");
		} finally {
			release.countDown();
			delivery.stop(false);
			delivery.awaitTermination(Duration.ofSeconds(5));
		}
		assertTrue(delivery.isTerminated()); assertEquals(2, finished.get()); assertEquals(2, physicallyExited.get());
		pendingStart.finish(() -> fail("Finish was delivered twice."), physicallyExited::incrementAndGet);
		assertEquals(2, physicallyExited.get());
	}
}
