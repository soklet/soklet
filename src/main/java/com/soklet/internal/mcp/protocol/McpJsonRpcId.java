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

import java.math.BigInteger;

import static java.util.Objects.requireNonNull;

sealed interface McpJsonRpcId permits McpJsonRpcId.StringId, McpJsonRpcId.IntegerId {
	default McpJsonValue toJsonValue() {
		if (this instanceof StringId stringId)
			return new McpJsonString(stringId.value());

		if (this instanceof IntegerId integerId)
			return new McpJsonNumber(new java.math.BigDecimal(integerId.value()));

		throw new IllegalArgumentException("Unsupported JSON-RPC request ID: " + this);
	}

	record StringId(String value) implements McpJsonRpcId {
		public StringId {
			requireNonNull(value);
		}
	}

	record IntegerId(BigInteger value) implements McpJsonRpcId {
		public IntegerId {
			requireNonNull(value);
		}
	}
}

sealed interface McpProgressToken permits McpProgressToken.StringToken,
		McpProgressToken.IntegerToken {
	record StringToken(String value) implements McpProgressToken {
		public StringToken {
			requireNonNull(value);
		}
	}

	record IntegerToken(BigInteger value) implements McpProgressToken {
		public IntegerToken {
			requireNonNull(value);
		}
	}
}
