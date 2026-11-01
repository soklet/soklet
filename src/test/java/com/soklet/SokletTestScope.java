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

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Cleanup for low-level tests that need an existing Soklet handle to control
 * startup, partial lifecycle states, or shutdown. Ordinary integration tests
 * should use {@link Soklet#run(SokletConfig, Soklet.RunAction)} instead.
 * This helper is test-only; the production Soklet API is not AutoCloseable.
 */
public final class SokletTestScope implements AutoCloseable {
	private final Soklet soklet;

	private SokletTestScope(Soklet soklet) {
		this.soklet = Objects.requireNonNull(soklet);
	}

	public static SokletTestScope of(Soklet soklet) {
		return new SokletTestScope(soklet);
	}

	public Soklet soklet() {
		return this.soklet;
	}

	/** Request shutdown without blocking the current callback or handler. */
	public static CompletionStage<ShutdownResult> shutdown(Soklet soklet) {
		soklet.requestShutdown();
		return soklet.awaitShutdownAsync();
	}

	/** Join full finalization and preserve the calling test's interrupt status. */
	public static void close(Soklet soklet) {
		boolean interrupted = false;
		try {
			soklet.requestShutdown();
			for (;;) {
				try {
					soklet.awaitShutdown();
					return;
				} catch (InterruptedException failure) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() {
		close(this.soklet);
	}
}
