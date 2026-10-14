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
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;

/**
 * Expected outcomes after the public handler/interceptor exception boundary.
 * Application errors are already provenance-checked; framework errors retain
 * separate protocol-profile projection at the response boundary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
sealed interface McpApplicationDispatchOutcome {
	@NonNull
	static Completed completed(@NonNull McpWireResult value) {
		return new Completed(value);
	}

	@NonNull
	static InvalidInput invalidInput() {
		return InvalidInput.DEFAULT_INSTANCE;
	}

	@NonNull
	static ApplicationError applicationError(@NonNull McpJsonRpcError error) {
		return new ApplicationError(error);
	}

	@NonNull
	static ProtocolError protocolError(@NonNull McpJsonRpcError error) {
		return new ProtocolError(error);
	}

	/** Applies successful-result decoration without changing an error outcome. */
	@NonNull
	default McpApplicationDispatchOutcome mapCompleted(
			@NonNull UnaryOperator<@NonNull McpWireResult> mapper) {
		requireNonNull(mapper);
		return switch (this) {
			case Completed completed -> completed(mapper.apply(completed.getValue()));
			case InvalidInput invalid -> invalid;
			case ApplicationError error -> error;
			case ProtocolError error -> error;
		};
	}

	@ThreadSafe
	final class Completed implements McpApplicationDispatchOutcome {
		@NonNull
		private final McpWireResult value;

		private Completed(@NonNull McpWireResult value) {
			this.value = requireNonNull(value);
		}

		@NonNull
		public McpWireResult getValue() {
			return this.value;
		}
	}

	@ThreadSafe
	final class InvalidInput implements McpApplicationDispatchOutcome {
		@NonNull
		private static final InvalidInput DEFAULT_INSTANCE = new InvalidInput();

		private InvalidInput() {}
	}

	@ThreadSafe
	final class ApplicationError implements McpApplicationDispatchOutcome {
		@NonNull
		private final McpJsonRpcError error;

		private ApplicationError(@NonNull McpJsonRpcError error) {
			this.error = requireNonNull(error);
		}

		@NonNull
		public McpJsonRpcError getError() {
			return this.error;
		}
	}

	@ThreadSafe
	final class ProtocolError implements McpApplicationDispatchOutcome {
		@NonNull
		private final McpJsonRpcError error;

		private ProtocolError(@NonNull McpJsonRpcError error) {
			this.error = requireNonNull(error);
		}

		@NonNull
		public McpJsonRpcError getError() {
			return this.error;
		}
	}
}
