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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
		boolean supported() {
			try {
				Class.forName("java.lang.Thread$Builder$OfVirtual");
				Executors.class.getMethod("newThreadPerTaskExecutor", ThreadFactory.class);
				return true;
			} catch (ClassNotFoundException | NoSuchMethodException e) {
				return false;
			}
		}

		@Override
		@NonNull
		ExecutorService createExecutor(int concurrency, @NonNull String threadNamePrefix,
				@NonNull UncaughtExceptionHandler uncaughtExceptionHandler) {
			validate(concurrency, threadNamePrefix, uncaughtExceptionHandler);

			if (!supported())
				throw new IllegalStateException("Virtual threads are not available on this runtime.");

			ThreadFactory threadFactory = virtualThreadFactory(threadNamePrefix, uncaughtExceptionHandler);
			MethodHandle newThreadPerTaskExecutor;

			try {
				newThreadPerTaskExecutor = MethodHandles.publicLookup().findStatic(
						Executors.class,
						"newThreadPerTaskExecutor",
						MethodType.methodType(ExecutorService.class, ThreadFactory.class));
			} catch (NoSuchMethodException | IllegalAccessException e) {
				throw new IllegalStateException("Unable to access the virtual-thread executor factory.", e);
			}

			try {
				return (ExecutorService) newThreadPerTaskExecutor.invoke(threadFactory);
			} catch (Throwable throwable) {
				throw new IllegalStateException("Unable to create a virtual-thread executor.", throwable);
			}
		}
	};

	boolean supported() {
		return true;
	}

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
			thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
			return thread;
		};
	}

	@NonNull
	private static ThreadFactory virtualThreadFactory(@NonNull String prefix,
			@NonNull UncaughtExceptionHandler uncaughtExceptionHandler) {
		Class<?> virtualBuilderClass;

		try {
			virtualBuilderClass = Class.forName("java.lang.Thread$Builder$OfVirtual");
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("Unable to load the virtual-thread builder.", e);
		}

		MethodHandles.Lookup lookup = MethodHandles.publicLookup();

		try {
			MethodHandle ofVirtual = lookup.findStatic(
					Thread.class,
					"ofVirtual",
					MethodType.methodType(virtualBuilderClass));
			MethodHandle name = lookup.findVirtual(
					virtualBuilderClass,
					"name",
					MethodType.methodType(virtualBuilderClass, String.class, long.class));
			MethodHandle exceptionHandler = lookup.findVirtual(
					virtualBuilderClass,
					"uncaughtExceptionHandler",
					MethodType.methodType(virtualBuilderClass, UncaughtExceptionHandler.class));
			MethodHandle factory = lookup.findVirtual(
					virtualBuilderClass,
					"factory",
					MethodType.methodType(ThreadFactory.class));
			Object builder = ofVirtual.invoke();
			builder = name.invoke(builder, prefix, 1L);
			builder = exceptionHandler.invoke(builder, uncaughtExceptionHandler);
			return (ThreadFactory) factory.invoke(builder);
		} catch (Throwable throwable) {
			throw new IllegalStateException("Unable to create a virtual-thread factory.", throwable);
		}
	}
}
