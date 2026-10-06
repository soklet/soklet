/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.mcp.transport;

import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(10)
public class McpOutboundChannelWaitTests {
	@Test
	public void both_clean_completions_wake_producers_without_accepting_more_frames() throws Exception {
		for (boolean terminalPayload : new boolean[]{false, true}) {
			CountDownLatch pressured = new CountDownLatch(1);
			McpOutboundChannel channel = channel(pressured);
			ExecutorService executor = Executors.newSingleThreadExecutor();
			try {
				Assertions.assertTrue(channel.enqueue(new byte[]{1}));
				Future<Boolean> waiting = executor.submit(() -> channel.enqueue(new byte[]{2}));
				Assertions.assertTrue(pressured.await(2, TimeUnit.SECONDS));
				Assertions.assertTrue(terminalPayload ? channel.complete(new byte[]{3}) : channel.completeWithoutPayload());
				Assertions.assertFalse(waiting.get(2, TimeUnit.SECONDS));
				Assertions.assertEquals(1, channel.snapshot().bufferedFrames());
			} finally {
				channel.close(StreamTerminationReason.APPLICATION_CANCELED, null);
				executor.shutdownNow();
				Assertions.assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	public void interrupted_wait_does_not_accept_a_frame_and_close_wakes_other_waiters() throws Exception {
		CountDownLatch pressured = new CountDownLatch(2);
		McpOutboundChannel channel = channel(pressured);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		AtomicReference<Thread> firstThread = new AtomicReference<>();
		AtomicBoolean interrupted = new AtomicBoolean();
		try {
			Assertions.assertTrue(channel.enqueue(new byte[]{1}));
			Future<?> first = executor.submit(() -> {
				firstThread.set(Thread.currentThread());
				try { channel.enqueue(new byte[]{2}); }
				catch (InterruptedException expected) { interrupted.set(true); }
			});
			Future<Boolean> second = executor.submit(() -> channel.enqueue(new byte[]{3}));
			Assertions.assertTrue(pressured.await(2, TimeUnit.SECONDS));
			firstThread.get().interrupt();
			first.get(2, TimeUnit.SECONDS);
			Assertions.assertTrue(interrupted.get());
			Assertions.assertEquals(1, channel.snapshot().bufferedFrames());
			channel.close(StreamTerminationReason.APPLICATION_CANCELED, null);
			Assertions.assertFalse(second.get(2, TimeUnit.SECONDS));
			Assertions.assertEquals(0, channel.snapshot().bufferedFrames());
		} finally {
			channel.close(StreamTerminationReason.APPLICATION_CANCELED, null);
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
		}
	}

	private static McpOutboundChannel channel(CountDownLatch pressured) {
		return new McpOutboundChannel(1, 1, 1, System::nanoTime, new McpOutboundChannel.Listener() {
			@Override public void didWrite(long bytes, long timestamp) {}
			@Override public void didApplyBackpressure() { pressured.countDown(); }
			@Override public void didTerminate(StreamTerminationReason reason, Throwable cause) {}
		});
	}
}
