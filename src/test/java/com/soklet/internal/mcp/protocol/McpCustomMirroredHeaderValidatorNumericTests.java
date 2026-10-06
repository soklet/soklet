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

import com.soklet.internal.microhttp.Header;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpCustomMirroredHeaderValidatorNumericTests {
	@Test
	void safeIntegerRangeIsCheckedBeforeExactConversion() {
		for (BigDecimal value : List.of(new ConversionTrapDecimal("1e9999"),
				new ConversionTrapDecimal("-1e9999"),
				new ConversionTrapDecimal(BigInteger.ONE, Integer.MIN_VALUE)))
			assertEquals(McpCustomMirroredHeaderOutcome.HEADER_MISMATCH, validate("1", value));
	}

	@Test
	void exactValuesFractionsAndOversizedHeaderIntegersRemainBounded() {
		for (String value : List.of("-9007199254740991", "9007199254740991", "0", "42"))
			assertEquals(McpCustomMirroredHeaderOutcome.VALID,
					validate(value, new BigDecimal(value + ".0")));
		for (String value : List.of("1.5", "1e-9999", "9007199254740992"))
			assertEquals(McpCustomMirroredHeaderOutcome.HEADER_MISMATCH, validate("1", new BigDecimal(value)));
		assertEquals(McpCustomMirroredHeaderOutcome.HEADER_MISMATCH,
				validate("9".repeat(1024), BigDecimal.ONE));
	}

	private static McpCustomMirroredHeaderOutcome validate(String headerValue, BigDecimal value) {
		McpMirroredHeaderPlan plan = new McpMirroredHeaderPlan(List.of(
				new McpMirroredHeaderDeclaration("Integer", List.of("value"), McpMirroredHeaderValueType.INTEGER)));
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("numeric-test", "1"))
				.tool(new McpNormalizedOperation("integers", McpInputRequestPlan.empty(), plan)).build();
		McpJsonObject params = new McpJsonObject(Map.of("name", new McpJsonString("integers"),
				"arguments", new McpJsonObject(Map.of("value", new McpJsonNumber(value)))));
		return new McpCustomMirroredHeaderValidator(new McpMirroredHeaderCodec(16384))
				.validate(List.of(new Header("Mcp-Param-Integer", headerValue)),
						new McpJsonRpcEnvelope.Request(new McpJsonRpcId.StringId("test"), "tools/call",
								Optional.of(params), McpJsonObject.empty()),
						McpServerCapabilityRegistry.fromEndpoint(endpoint), McpUnknownMirroredHeaderPolicy.IGNORE, false)
				.outcome();
	}

	private static final class ConversionTrapDecimal extends BigDecimal {
		private ConversionTrapDecimal(String value) { super(value); }
		private ConversionTrapDecimal(BigInteger value, int scale) { super(value, scale); }
		@Override public BigInteger toBigIntegerExact() {
			throw new AssertionError("Integer expansion must not be reached.");
		}
		@Override public long longValueExact() {
			throw new AssertionError("Exact conversion must not be reached.");
		}
	}
}
