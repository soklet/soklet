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

import org.jspecify.annotations.NonNull;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

/**
 * Supported bounded application-handler execution strategies.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
enum McpThreadStrategy {
	PLATFORM {
		@Override
		@NonNull
		ExecutorService createExecutor(int concurrency, @NonNull String threadNamePrefix,
				@NonNull UncaughtExceptionHandler uncaughtExceptionHandler) {
			validate(concurrency, threadNamePrefix, uncaughtExceptionHandler);
			ThreadFactory threadFactory = platformThreadFactory(threadNamePrefix, uncaughtExceptionHandler);

			return new ThreadPoolExecutor(
					concurrency,
					concurrency,
					0L,
					TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(concurrency),
					threadFactory,
					new ThreadPoolExecutor.AbortPolicy());
		}
	},
	VIRTUAL {
		@Override
		@NonNull
		ExecutorService createExecutor(int concurrency, @NonNull String threadNamePrefix,
				@NonNull UncaughtExceptionHandler uncaughtExceptionHandler) {
			validate(concurrency, threadNamePrefix, uncaughtExceptionHandler);
			// The dispatcher owns concurrency and queue admission limits.
			return Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
					.name(threadNamePrefix, 1L)
					.uncaughtExceptionHandler(uncaughtExceptionHandler)
					.factory());
		}
	};

	@NonNull
	abstract ExecutorService createExecutor(int concurrency, @NonNull String threadNamePrefix,
			@NonNull UncaughtExceptionHandler uncaughtExceptionHandler);

	private static void validate(int concurrency, @NonNull String threadNamePrefix,
			@NonNull UncaughtExceptionHandler uncaughtExceptionHandler) {
		if (concurrency < 1)
			throw new IllegalArgumentException("Handler concurrency must be > 0.");

		requireNonNull(threadNamePrefix);
		requireNonNull(uncaughtExceptionHandler);
	}

	@NonNull
	private static ThreadFactory platformThreadFactory(@NonNull String prefix,
			@NonNull UncaughtExceptionHandler uncaughtExceptionHandler) {
		AtomicLong sequence = new AtomicLong();

		return runnable -> {
			Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
			thread.setDaemon(true);
			thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
			return thread;
		};
	}
}
