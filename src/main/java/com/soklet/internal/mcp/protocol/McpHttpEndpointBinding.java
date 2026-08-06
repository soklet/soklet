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

import static java.util.Objects.requireNonNull;

/**
 * Immutable transport binding for one exact MCP endpoint path.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpHttpEndpointBinding(@NonNull McpHttpEndpointPolicy endpointPolicy,
		@NonNull McpNormalizedEndpoint endpoint,
		@NonNull McpApplicationRequestRouter applicationRouter,
		@NonNull McpRuntimeObservationSink observationSink) {
	McpHttpEndpointBinding(@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpApplicationRequestRouter applicationRouter) {
		this(endpointPolicy, endpoint, applicationRouter,
				McpRuntimeObservationSink.disabledInstance());
	}

	McpHttpEndpointBinding {
		requireNonNull(endpointPolicy);
		requireNonNull(endpoint);
		requireNonNull(applicationRouter);
		requireNonNull(observationSink);
	}
}
