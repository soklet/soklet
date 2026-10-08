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

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Test worker that finishes application execution before submission returns. */
final class McpEarlyCompletionExecutor extends AbstractExecutorService {
	private final ExecutorService delegate = Executors.newSingleThreadExecutor();

	@Override
	public void execute(Runnable command) {
		Future<?> execution = delegate.submit(command);
		try {
			execution.get(5, TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			execution.cancel(true);
			Thread.currentThread().interrupt();
			throw new RejectedExecutionException("Early-completion worker interrupted", exception);
		} catch (TimeoutException exception) {
			execution.cancel(true);
			throw new RejectedExecutionException("Early-completion worker timed out", exception);
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtimeException)
				throw runtimeException;
			if (cause instanceof Error error)
				throw error;
			throw new IllegalStateException("Early-completion worker failed", cause);
		}
	}

	@Override
	public void shutdown() {
		delegate.shutdown();
	}

	@Override
	public List<Runnable> shutdownNow() {
		return delegate.shutdownNow();
	}

	@Override
	public boolean isShutdown() {
		return delegate.isShutdown();
	}

	@Override
	public boolean isTerminated() {
		return delegate.isTerminated();
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		return delegate.awaitTermination(timeout, unit);
	}
}
