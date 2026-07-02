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

package com.soklet.internal.util;

import javax.annotation.concurrent.ThreadSafe;
import java.util.function.BooleanSupplier;

/**
 * Shared accept-loop retry policy for Soklet's HTTP, SSE, and MCP servers.
 * <p>
 * A persistent accept-loop failure (e.g. file-descriptor exhaustion) would otherwise spin the
 * accept loop with little or no delay. This policy escalates the retry delay exponentially per
 * consecutive failure up to a ceiling, coalesces log volume to exponentially-spaced milestones,
 * and sleeps in small increments so a stopping server never waits out a full backoff delay.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class AcceptLoopBackoff {
	/**
	 * Delay before the first retry; doubles per consecutive failure.
	 */
	private static final long BASE_BACKOFF_MILLIS = 50L;
	/**
	 * Ceiling for the escalating retry delay.
	 */
	private static final long MAX_BACKOFF_MILLIS = 1_000L;
	/**
	 * Upper bound on each individual sleep so a stop request is observed promptly mid-backoff.
	 */
	private static final long STOP_CHECK_INTERVAL_MILLIS = 50L;

	private AcceptLoopBackoff() {
		// Non-instantiable
	}

	/**
	 * Escalating backoff schedule: doubles per consecutive failure from a 50ms base up to a 1s ceiling.
	 *
	 * @param consecutiveFailures the current run of back-to-back accept failures (1-based)
	 * @return how long to sleep before retrying, in milliseconds
	 */
	public static long backoffMillis(long consecutiveFailures) {
		int shift = (int) Math.min(Math.max(0L, consecutiveFailures - 1L), 20L);
		return Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS << shift);
	}

	/**
	 * Coalesces log volume during a sustained failure: log the first failure and then only at
	 * exponentially-spaced (power-of-two) milestones.
	 *
	 * @param consecutiveFailures the current run of back-to-back accept failures (1-based)
	 * @return {@code true} if this failure should be logged
	 */
	public static boolean shouldLogFailure(long consecutiveFailures) {
		return consecutiveFailures > 0 && (consecutiveFailures & (consecutiveFailures - 1)) == 0;
	}

	/**
	 * Sleeps for up to {@code millis}, in increments of at most 50ms, returning early as soon as
	 * {@code shouldStop} reports {@code true}. This keeps a stopping server from waiting out a full
	 * backoff delay (up to 1s) before observing its stop flag.
	 *
	 * @param millis     how long to sleep in total, in milliseconds
	 * @param shouldStop consulted before each increment; a {@code true} result ends the sleep early
	 * @return {@code true} if the sleep was interrupted (the thread's interrupt status is restored),
	 * {@code false} if it completed or ended early because {@code shouldStop} reported {@code true}
	 */
	public static boolean sleepBeforeRetry(long millis,
																				 BooleanSupplier shouldStop) {
		long remainingMillis = millis;

		while (remainingMillis > 0L) {
			if (shouldStop.getAsBoolean())
				return false;

			long chunkMillis = Math.min(remainingMillis, STOP_CHECK_INTERVAL_MILLIS);

			try {
				Thread.sleep(chunkMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return true;
			}

			remainingMillis -= chunkMillis;
		}

		return false;
	}
}
