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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class EventLoopUnexpectedTerminationTests {
	@Test
	void fatal_connection_loop_exit_notifies_the_parent_exactly_once() throws Exception {
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicInteger notifications = new AtomicInteger();
		ConnectionListener listener = new ConnectionListener() {
			@Override
			public void willAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didFailToAcceptConnection(
					@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didTerminateEventLoop(@NonNull EventLoop eventLoop,
					@NonNull Throwable throwable) {
				notifications.incrementAndGet();
				terminated.countDown();
			}
		};
		EventLoop eventLoop = new EventLoop(Options.builder()
				.withHost("127.0.0.1")
				.withPort(0)
				.withConcurrency(1)
				.build(), NoopLogger.instance(), (request, callback) -> {
		}, listener);

		try {
			eventLoop.start();
			Selector connectionSelector = connectionSelector(eventLoop);
			connectionSelector.close();

			Assertions.assertTrue(terminated.await(2, TimeUnit.SECONDS));
			Assertions.assertTrue(eventLoop.join(Duration.ofSeconds(2)));
			Assertions.assertTrue(eventLoop.isTerminated());
			Assertions.assertEquals(1, notifications.get());
		} finally {
			eventLoop.stop();
			eventLoop.join(Duration.ofSeconds(2));
		}
	}

	@Test
	void request_body_limit_is_validated_when_options_are_built() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> Options.builder()
				.withMaxRequestBodySize(0)
				.build());
		Assertions.assertThrows(IllegalArgumentException.class, () -> Options.builder()
				.withMaxRequestSize(100)
				.withMaxRequestBodySize(101)
				.build());
		Assertions.assertDoesNotThrow(() -> Options.builder()
				.withMaxRequestSize(100)
				.withMaxRequestBodySize(99)
				.build());
	}

	private static Selector connectionSelector(EventLoop eventLoop) throws Exception {
		Field loopsField = EventLoop.class.getDeclaredField("connectionEventLoops");
		loopsField.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<ConnectionEventLoop> loops = (List<ConnectionEventLoop>) loopsField.get(eventLoop);
		Field selectorField = ConnectionEventLoop.class.getDeclaredField("selector");
		selectorField.setAccessible(true);
		return (Selector) selectorField.get(loops.get(0));
	}
}
