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

package com.soklet.internal.microhttp;

import com.soklet.HttpMethod;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.nio.ByteBuffer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Small isolated probes of active waits, with no spare carrier for compensation. */
@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(20)
public class StreamingVirtualThreadWaitTests {
	@Test
	public void http_start_barriers_leave_carriers_available() throws Exception {
		runProbe("start");
	}

	@Test
	public void http_backpressure_leaves_carriers_available() throws Exception {
		runProbe("http");
	}

	@Test
	public void mcp_backpressure_leaves_carriers_available() throws Exception {
		runProbe("mcp");
	}

	private static void runProbe(String mode) throws Exception {
		Path outputFile = Files.createTempFile("soklet-carriers-", ".log");
		try {
			Process process = new ProcessBuilder(
					Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-Xmx128m", "-XX:ActiveProcessorCount=2",
					"-Djdk.virtualThreadScheduler.parallelism=2",
					"-Djdk.virtualThreadScheduler.maxPoolSize=2", "-cp",
					System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
					StreamingVirtualThreadWaitTests.class.getName(), mode)
					.redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
			try {
				Assertions.assertTrue(process.waitFor(12, TimeUnit.SECONDS), "Carrier probe timed out: " + mode);
				Assertions.assertTrue(Files.size(outputFile) <= 65_536, "Carrier probe output exceeded its bound");
				Assertions.assertEquals(0, process.exitValue(), Files.readString(outputFile, StandardCharsets.UTF_8));
			} finally {
				process.destroyForcibly();
				Assertions.assertTrue(process.waitFor(2, TimeUnit.SECONDS));
			}
		} finally {
			Files.deleteIfExists(outputFile);
		}
	}

	public static void main(String[] args) throws Exception {
		ExecutorService producers = (ExecutorService) Executors.class
				.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
		ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(3, 1, Duration.ofSeconds(1),
				failure -> { throw new AssertionError(failure); });
		List<WritableSource> sources = new ArrayList<>();
		List<McpOutboundChannel> channels = new ArrayList<>();
		CountDownLatch blocked = new CountDownLatch(2);
		try {
			for (int i = 0; i < 3; i++) {
				if (args[0].equals("mcp")) {
					McpOutboundChannel channel = new McpOutboundChannel(1, 1, 1, System::nanoTime,
							new McpOutboundChannel.Listener() {
								@Override public void didWrite(long bytes, long timestamp) {}
								@Override public void didApplyBackpressure() { blocked.countDown(); }
								@Override public void didTerminate(StreamTerminationReason reason, Throwable cause) {}
							});
					channels.add(channel);
					Assertions.assertTrue(channel.enqueue(new byte[]{1}));
					producers.submit(() -> channel.enqueue(new byte[]{2}));
				} else {
					boolean startBarrier = args[0].equals("start");
					StreamingResponseBody body = StreamingResponseBody.fromWriter(stream -> {
						stream.write(ByteBuffer.wrap(new byte[]{1}));
						blocked.countDown();
						stream.write(ByteBuffer.wrap(new byte[]{2}));
					});
					MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(
							200, "OK", List.of(), Request.fromPath(HttpMethod.GET, "/stream"), body,
							producers, timer, 1, 1, null, null, () -> false,
							(established, duration, reason, cause) -> {}, failure -> {},
							startBarrier ? coordinator.tryReserve() : null);
					Method newBodySource = MicrohttpResponse.class.getDeclaredMethod("newBodySource");
					newBodySource.setAccessible(true);
					sources.add((WritableSource) newBodySource.invoke(response));
					if (!startBarrier)
						sources.get(i).start();
				}
			}
			if (args[0].equals("start")) {
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
				while (coordinator.snapshot().runningProducers() < 2 && System.nanoTime() < deadline)
					Thread.sleep(5);
				Assertions.assertTrue(coordinator.snapshot().runningProducers() >= 2);
			} else
				Assertions.assertTrue(blocked.await(2, TimeUnit.SECONDS), "Two producers did not reach backpressure");
			// Allow both waiters to enter their wait before submitting unrelated work.
			Thread.sleep(100);
			Assertions.assertEquals("available", producers.submit(() -> "available").get(2, TimeUnit.SECONDS));
		} finally {
			for (WritableSource source : sources)
				source.close(StreamTerminationReason.APPLICATION_CANCELED, null);
			for (McpOutboundChannel channel : channels)
				channel.close(StreamTerminationReason.APPLICATION_CANCELED, null);
			coordinator.force();
			producers.shutdownNow();
			timer.shutdownNow();
			Assertions.assertTrue(producers.awaitTermination(2, TimeUnit.SECONDS));
			Assertions.assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
		}
	}
}
