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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestError;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestObservation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestObservationAdapter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Test-only access to Soklet's production MCP request-context projection.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpRequestObservationTestSupport {
	@NonNull
	private static final RequestObservationAdapter NO_OP_ADAPTER = input -> {
		McpRequestContext context = new DefaultMcpRequestContext(input);
		return new RequestObservation() {
			@Override
			@NonNull
			public McpRequestContext context() {
				return context;
			}

			@Override
			public void didFinish(@NonNull McpRequestOutcome outcome,
					@Nullable RequestError error, @NonNull Duration duration,
					@NonNull List<@NonNull Throwable> throwables) {
				requireNonNull(outcome);
				requireNonNull(duration);
				requireNonNull(throwables);
			}
		};
	};

	private McpRequestObservationTestSupport() {}

	/** @return context-producing observation adapter with no terminal side effect */
	@NonNull
	public static RequestObservationAdapter noOpAdapter() {
		return NO_OP_ADAPTER;
	}
}
