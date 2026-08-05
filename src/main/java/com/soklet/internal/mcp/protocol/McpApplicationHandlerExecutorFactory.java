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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
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
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
interface McpApplicationHandlerExecutorFactory {
	@NonNull
	ExecutorService create(int concurrency);

	@NonNull
	static McpApplicationHandlerExecutorFactory production() {
		return McpApplicationHandlerExecutors::newProductionExecutor;
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationHandlerExecutors {
	@NonNull
	private static final String THREAD_NAME_PREFIX = "soklet-mcp-handler-";

	private McpApplicationHandlerExecutors() {
	}

	@NonNull
	static ExecutorService newProductionExecutor(int concurrency) {
		if (concurrency < 1)
			throw new IllegalArgumentException("Handler concurrency must be positive.");

		return virtualThreadsSupported()
				? newVirtualThreadExecutor()
				: newPlatformThreadExecutor(concurrency);
	}

	@NonNull
	private static ExecutorService newPlatformThreadExecutor(int concurrency) {
		return new ThreadPoolExecutor(
				concurrency,
				concurrency,
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(concurrency),
				platformThreadFactory(),
				new ThreadPoolExecutor.AbortPolicy());
	}

	@NonNull
	private static ExecutorService newVirtualThreadExecutor() {
		try {
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			Class<?> virtualBuilderClass = Class.forName("java.lang.Thread$Builder$OfVirtual");
			MethodHandle ofVirtual = lookup.findStatic(Thread.class, "ofVirtual",
					MethodType.methodType(virtualBuilderClass));
			MethodHandle name = lookup.findVirtual(virtualBuilderClass, "name",
					MethodType.methodType(virtualBuilderClass, String.class, long.class));
			MethodHandle factory = lookup.findVirtual(virtualBuilderClass, "factory",
					MethodType.methodType(ThreadFactory.class));
			MethodHandle newThreadPerTaskExecutor = lookup.findStatic(Executors.class,
					"newThreadPerTaskExecutor",
					MethodType.methodType(ExecutorService.class, ThreadFactory.class));
			Object builder = ofVirtual.invoke();
			builder = name.invoke(builder, THREAD_NAME_PREFIX, 1L);
			ThreadFactory threadFactory = (ThreadFactory) factory.invoke(builder);
			return (ExecutorService) newThreadPerTaskExecutor.invoke(threadFactory);
		} catch (Throwable throwable) {
			throw new IllegalStateException("Unable to create the MCP virtual-thread executor.",
					throwable);
		}
	}

	private static boolean virtualThreadsSupported() {
		try {
			Class.forName("java.lang.Thread$Builder$OfVirtual");
			Executors.class.getMethod("newThreadPerTaskExecutor", ThreadFactory.class);
			return true;
		} catch (ClassNotFoundException | NoSuchMethodException exception) {
			return false;
		}
	}

	@NonNull
	private static ThreadFactory platformThreadFactory() {
		AtomicLong sequence = new AtomicLong();
		return runnable -> {
			Thread thread = new Thread(requireNonNull(runnable),
					THREAD_NAME_PREFIX + sequence.incrementAndGet());
			thread.setDaemon(false);
			return thread;
		};
	}
}
