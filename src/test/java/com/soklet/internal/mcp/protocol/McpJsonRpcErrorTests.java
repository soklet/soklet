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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

public class McpJsonRpcErrorTests {
	private static final Set<Integer> DEFINED_RESERVED_CODES = Set.of(
			McpJsonRpcError.PARSE_ERROR,
			McpJsonRpcError.INVALID_REQUEST,
			McpJsonRpcError.METHOD_NOT_FOUND,
			McpJsonRpcError.INVALID_PARAMS,
			McpJsonRpcError.INTERNAL_ERROR,
			McpJsonRpcError.HEADER_MISMATCH,
			McpJsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY,
			McpJsonRpcError.UNSUPPORTED_PROTOCOL_VERSION);

	@Test
	public void rejectsLegacyAndWithdrawnCodes() {
		for (int code = -32768; code <= -32000; ++code) {
			if (DEFINED_RESERVED_CODES.contains(code))
				continue;
			int rejectedCode = code;
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> error(rejectedCode), Integer.toString(rejectedCode));
		}
		Assertions.assertThrows(IllegalArgumentException.class, () -> error(-32002));
		Assertions.assertThrows(IllegalArgumentException.class, () -> error(-32042));
	}

	@Test
	public void enforcesMcpReservedCodesAndMeanings() {
		Assertions.assertEquals(Set.of(-32020, -32021, -32022), Set.of(
				McpJsonRpcError.HEADER_MISMATCH,
				McpJsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY,
				McpJsonRpcError.UNSUPPORTED_PROTOCOL_VERSION));
		DEFINED_RESERVED_CODES.forEach(code ->
				Assertions.assertDoesNotThrow(() -> error(code), Integer.toString(code)));

		McpJsonRpcError missing = McpJsonRpcError.missingRequiredClientCapabilities(
				Set.of(McpCoreClientCapability.SAMPLING));
		Assertions.assertEquals(McpJsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY,
				missing.code());
		Assertions.assertEquals("Missing required client capability", missing.message());
		Assertions.assertEquals(Set.of("requiredCapabilities"),
				((McpJsonObject) missing.data().orElseThrow()).members().keySet());

		McpJsonRpcError unsupported = McpJsonRpcError.unsupportedProtocolVersion("draft");
		Assertions.assertEquals(McpJsonRpcError.UNSUPPORTED_PROTOCOL_VERSION,
				unsupported.code());
		Assertions.assertEquals("Unsupported protocol version", unsupported.message());
		McpJsonObject data = (McpJsonObject) unsupported.data().orElseThrow();
		Assertions.assertEquals(Set.of("supported", "requested"), data.members().keySet());
		Assertions.assertEquals(new McpJsonString("draft"), data.members().get("requested"));
	}

	@Test
	public void permitsApplicationAndSokletPolicyCodesOutsideTheReservedRange() {
		for (int code : new int[] { Integer.MIN_VALUE, -32769, -31999, -31998, 1_000,
				Integer.MAX_VALUE })
			Assertions.assertDoesNotThrow(() -> error(code), Integer.toString(code));
	}

	private static McpJsonRpcError error(int code) {
		return new McpJsonRpcError(code, "message", Optional.empty());
	}
}
