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
import java.time.Duration;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Verified framework-state data retained between request dispatch and a
 * possible subsequent state emission.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpFrameworkRequestStateContinuation(
		@NonNull McpRequestStateTimestamp issuedAt,
		@NonNull McpRequestStateTimestamp expiresAt,
		int round,
		@NonNull McpJsonRpcId originatingRequestId,
		@NonNull McpJsonValue state) {
	McpFrameworkRequestStateContinuation {
		requireNonNull(issuedAt);
		requireNonNull(expiresAt);
		requireNonNull(originatingRequestId);
		requireNonNull(state);
		if (issuedAt.compareTo(expiresAt) >= 0)
			throw new IllegalArgumentException(
					"Request-state expiry must follow issuance.");
		if (round < 1)
			throw new IllegalArgumentException(
					"Request-state round must be positive.");
	}

	@NonNull
	static McpFrameworkRequestStateContinuation initial(
			@NonNull McpJsonValue state,
			@NonNull Instant now,
			@NonNull Duration maximumLifetime,
			@NonNull McpJsonRpcId currentRequestId) {
		requireNonNull(state);
		requireNonNull(now);
		requireNonNull(currentRequestId);
		McpRequestStateTimestamp issuedAt =
				McpRequestStateTimestamp.fromInstant(now);
		return new McpFrameworkRequestStateContinuation(
				issuedAt, issuedAt.plus(maximumLifetime), 1,
				currentRequestId, state);
	}

	@NonNull
	McpFrameworkRequestStateContinuation next(
			@NonNull McpJsonValue nextState,
			@NonNull McpJsonRpcId currentRequestId,
			int maximumRounds) {
		requireNonNull(nextState);
		requireNonNull(currentRequestId);
		if (maximumRounds < 1)
			throw new IllegalArgumentException(
					"Maximum request-state rounds must be positive.");
		if (round >= maximumRounds)
			throw new IllegalArgumentException(
					"Request-state has reached its maximum round.");
		return new McpFrameworkRequestStateContinuation(
				issuedAt, expiresAt, Math.addExact(round, 1),
				currentRequestId, nextState);
	}
}
