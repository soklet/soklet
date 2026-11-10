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

package com.soklet.internal;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/** Construction policy for internal control and cleanup threads, not application executors. */
@ThreadSafe
public final class HelperThreads {
	private HelperThreads() {
	}

	/**
	 * Creates an unstarted platform thread without invoking application
	 * InheritableThreadLocal hooks. Retains the creating thread's context loader,
	 * thread group, priority and daemon status; the owner may set its daemon policy.
	 */
	@NonNull
	public static Thread platform(@NonNull Runnable task, @NonNull String name) {
		ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
		Thread thread = new Thread(null, requireNonNull(task), requireNonNull(name), 0L, false);
		thread.setContextClassLoader(contextClassLoader);
		return thread;
	}

	/** Creates an unstarted virtual helper with the creator's context loader, without inherited values. */
	@NonNull
	public static Thread virtual(@NonNull Runnable task, @NonNull String name) {
		ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
		Thread thread = Thread.ofVirtual().inheritInheritableThreadLocals(false)
				.name(requireNonNull(name)).unstarted(requireNonNull(task));
		thread.setContextClassLoader(contextClassLoader);
		return thread;
	}
}
