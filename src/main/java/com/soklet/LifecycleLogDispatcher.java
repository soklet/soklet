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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-scoped, best-effort console delivery; never runs a rejected task inline. */
@ThreadSafe
final class LifecycleLogDispatcher {

	static final int QUEUE_CAPACITY = 128;
	static final int MAXIMUM_MESSAGE_CHARACTERS = 4_096;
	private static final @NonNull LifecycleLogDispatcher DEFAULT_INSTANCE = new LifecycleLogDispatcher();
	private final @NonNull ThreadPoolExecutor executor;

	LifecycleLogDispatcher() {
		this(task -> {
			Thread thread = Thread.ofPlatform().daemon(true)
					.inheritInheritableThreadLocals(false)
					.name("soklet-lifecycle-log").unstarted(task);
			// Use the framework loader rather than the emitting application's context loader.
			thread.setContextClassLoader(LifecycleLogDispatcher.class.getClassLoader());
			return thread;
		});
	}

	LifecycleLogDispatcher(@NonNull ThreadFactory threadFactory) {
		executor = new ThreadPoolExecutor(1, 1, 1L, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(QUEUE_CAPACITY), threadFactory,
				new ThreadPoolExecutor.AbortPolicy());
		// An idle worker must not keep a discarded framework class loader alive.
		executor.allowCoreThreadTimeOut(true);
	}

	@NonNull
	static LifecycleLogDispatcher defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	void dispatch(@NonNull Runnable record) {
		try {
			executor.execute(() -> {
				try {
					record.run();
				} catch (Throwable ignored) {
					// A broken formatter or sink must not recurse into logging.
				}
			});
		} catch (Throwable ignored) {
			// Full queue, shutdown, or worker-start failure: discard, never write inline.
		}
	}

	@NonNull
	static String snapshotMessage(@NonNull String message) {
		if (message.length() <= MAXIMUM_MESSAGE_CHARACTERS)
			return message;
		int end = MAXIMUM_MESSAGE_CHARACTERS;
		if (Character.isHighSurrogate(message.charAt(end - 1))
				&& Character.isLowSurrogate(message.charAt(end)))
			--end;
		return message.substring(0, end);
	}

	// Bounded package-private test controls; production shutdown never waits on stderr.
	boolean awaitIdle(@NonNull Duration timeout) throws InterruptedException {
		CountDownLatch drained = new CountDownLatch(1);
		try {
			executor.execute(drained::countDown);
		} catch (Throwable ignored) {
			return false;
		}
		return drained.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
	}

	void shutdown() {
		executor.shutdownNow();
	}
}
