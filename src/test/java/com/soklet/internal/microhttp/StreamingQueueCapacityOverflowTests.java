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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Checks queue arithmetic near the largest supported capacity without allocating a huge queue. */
public class StreamingQueueCapacityOverflowTests {
	@Test
	public void payload_waits_when_int_addition_would_overflow_then_fits_at_exact_capacity()
			throws Exception {
		CountDownLatch writeEntered = new CountDownLatch(1);
		CountDownLatch writeReturned = new CountDownLatch(1);
		ExecutorService producer = Executors.newSingleThreadExecutor();
		ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
		WritableSource source = null;
		try {
			StreamingResponseBody body = StreamingResponseBody.fromWriter(responseStream -> {
				writeEntered.countDown();
				responseStream.write(ByteBuffer.wrap(new byte[16]));
				writeReturned.countDown();
			});
			MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(
					200, "OK", List.of(), Request.withPath(HttpMethod.GET, "/stream").build(),
					body, producer, timer, Integer.MAX_VALUE, 16, null, null,
					(establishedAt, duration, reason, cause) -> {}, throwable -> {});
			Method newBodySource = MicrohttpResponse.class.getDeclaredMethod("newBodySource");
			newBodySource.setAccessible(true);
			source = (WritableSource) newBodySource.invoke(response);
			Field queueBytes = source.getClass().getDeclaredField("queuedPayloadBytes");
			queueBytes.setAccessible(true);
			Field sourceLock = source.getClass().getDeclaredField("lock");
			sourceLock.setAccessible(true);
			Object lock = sourceLock.get(source);
			synchronized (lock) {
				queueBytes.setInt(source, Integer.MAX_VALUE - 8);
			}

			source.start();
			Assertions.assertTrue(writeEntered.await(2, TimeUnit.SECONDS));
			Assertions.assertFalse(writeReturned.await(200, TimeUnit.MILLISECONDS),
					"The producer accepted bytes beyond queue capacity after integer overflow");

			synchronized (lock) {
				queueBytes.setInt(source, Integer.MAX_VALUE - 16);
				lock.notifyAll();
			}
			Assertions.assertTrue(writeReturned.await(2, TimeUnit.SECONDS),
					"The payload should fit exactly when enough queue space becomes available");
			synchronized (lock) {
				Assertions.assertEquals(Integer.MAX_VALUE, queueBytes.getInt(source));
			}
		} finally {
			if (source != null)
				source.close(StreamTerminationReason.APPLICATION_CANCELED, null);
			producer.shutdownNow();
			timer.shutdownNow();
			Assertions.assertTrue(producer.awaitTermination(2, TimeUnit.SECONDS));
			Assertions.assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
		}
	}
}
