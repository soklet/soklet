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

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

record McpTransportConfiguration(String host, int port, int connectionWriterConcurrency,
		int maximumConnections, int handlerConcurrency, int handlerQueueCapacity,
		int outboundFrameCapacity, int outboundByteCapacity, int terminalByteCapacity,
		Duration requestDeadline, Duration responseWriteIdleTimeout, Duration keepAliveInterval,
		McpThreadStrategy threadStrategy) {
	static final int MINIMUM_FRAMEWORK_TERMINAL_BYTE_CAPACITY =
			"event: error\ndata: Request deadline exceeded\n\n".getBytes(StandardCharsets.UTF_8).length;

	McpTransportConfiguration {
		requireNonNull(host);
		requireNonNull(requestDeadline);
		requireNonNull(responseWriteIdleTimeout);
		requireNonNull(keepAliveInterval);
		requireNonNull(threadStrategy);

		if (port < 0 || port > 65_535)
			throw new IllegalArgumentException("Port must be between 0 and 65535.");

		positive(connectionWriterConcurrency, "Connection-writer concurrency");
		positive(maximumConnections, "Maximum connections");
		positive(handlerConcurrency, "Handler concurrency");
		positive(handlerQueueCapacity, "Handler queue capacity");
		positive(outboundFrameCapacity, "Outbound frame capacity");
		positive(outboundByteCapacity, "Outbound byte capacity");
		positive(terminalByteCapacity, "Terminal byte capacity");

		if (terminalByteCapacity < MINIMUM_FRAMEWORK_TERMINAL_BYTE_CAPACITY)
			throw new IllegalArgumentException("Terminal byte capacity must be >= "
					+ MINIMUM_FRAMEWORK_TERMINAL_BYTE_CAPACITY
					+ " bytes so framework terminal events always fit.");

		positive(requestDeadline, "Request deadline");
		positive(responseWriteIdleTimeout, "Response write-idle timeout");
		positive(keepAliveInterval, "Keepalive interval");
	}

	private static void positive(int value, String name) {
		if (value < 1)
			throw new IllegalArgumentException(name + " must be > 0.");
	}

	private static void positive(Duration value, String name) {
		if (value.isZero() || value.isNegative())
			throw new IllegalArgumentException(name + " must be > 0.");
	}
}
