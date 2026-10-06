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

package com.soklet.internal.mcp.schema;

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class McpTypedJsonBinderNumericLimitTests {
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			McpSchemaCompilationLimits.productionDefaults();
	private static final McpTypedJsonBinder BINDER = new McpTypedJsonBinder();

	@Test
	void oversizedIntegersFailBeforeExpansionIncludingExtremeScales() {
		McpTypedJsonBinding<BigInteger> binding = binding(BigInteger.class);
		for (BigDecimal decimal : List.of(new ExpansionTrapDecimal("1e9999"),
				new ExpansionTrapDecimal("-1e9999"),
				new ExpansionTrapDecimal(BigInteger.ONE, Integer.MIN_VALUE)))
			assertRangeFailure(() -> BINDER.fromJson(
					new McpJsonNumber(decimal), binding));
	}

	@Test
	void fixedWidthIntegersCheckTheirRangesBeforeExpansion() {
		for (Class<?> type : List.of(byte.class, short.class, int.class,
				long.class, Byte.class, Short.class, Integer.class, Long.class)) {
			McpTypedJsonBinding<?> binding = binding(type);
			for (String spelling : List.of("1e30", "-1e30"))
				assertRangeFailure(() -> BINDER.fromJson(new McpJsonNumber(
						new ExpansionTrapDecimal(spelling)), binding));
		}
	}

	@Test
	void expandedIntegerLengthCountsTheSignAndHasAnExactBoundary() {
		McpTypedJsonBinding<BigInteger> binding = binding(BigInteger.class);
		assertEquals(BigInteger.TEN.pow(1023),
				BINDER.fromJson(number("1e1023"), binding));
		assertEquals(BigInteger.TEN.pow(1022).negate(),
				BINDER.fromJson(number("-1e1022"), binding));
		assertRangeFailure(() -> BINDER.fromJson(number("1e1024"), binding));
		assertRangeFailure(() -> BINDER.fromJson(number("-1e1023"), binding));
		assertEquals(BigInteger.ONE,
				BINDER.fromJson(number("1.000"), binding));
		for (int scale : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE))
			assertEquals(BigInteger.ZERO, BINDER.fromJson(new McpJsonNumber(
					new BigDecimal(BigInteger.ZERO, scale)), binding));
	}

	@Test
	void aggregateBudgetIsChargedBeforeConversionAndResetsForEachBinding()
			throws ReflectiveOperationException {
		McpTypedJsonBinder limited = new McpTypedJsonBinder(
				new McpTypedJsonBindingLimits(10, 3, 3, 8, 5));
		McpTypedJsonBinding<List<BigInteger>> binding = binding(
				NumericTypes.class.getDeclaredField("integers").getGenericType());
		McpJsonArray exact = new McpJsonArray(List.of(number("10"), number("-10")));
		for (int index = 0; index < 2; ++index) {
			assertEquals(List.of(BigInteger.TEN, BigInteger.TEN.negate()),
					limited.fromJson(exact, binding));
			assertBudgetFailure("$/items", () -> limited.fromJson(
					new McpJsonArray(List.of(number("10"), number("-10"),
							new McpJsonNumber(new ExpansionTrapDecimal("1e2")))), binding));
		}
	}

	@Test
	void arrayMapAndNestedRecordUseTheSameAggregateBudget()
			throws ReflectiveOperationException {
		McpTypedJsonBinder limited = new McpTypedJsonBinder(
				new McpTypedJsonBindingLimits(20, 4, 4, 8, 4));
		McpJsonNumber trap = new McpJsonNumber(new ExpansionTrapDecimal("1e2"));
		McpTypedJsonBinding<BigInteger[]> arrayBinding = binding(BigInteger[].class);
		assertBudgetFailure("$/items", () -> limited.fromJson(
				new McpJsonArray(List.of(number("10"), trap)), arrayBinding));
		McpTypedJsonBinding<Map<String, BigInteger>> mapBinding = binding(
				NumericTypes.class.getDeclaredField("integerMap").getGenericType());
		Map<String, McpJsonValue> members = new java.util.LinkedHashMap<>();
		members.put("first", number("10"));
		members.put("second", trap);
		assertBudgetFailure("$/additionalProperties", () -> limited.fromJson(
				new McpJsonObject(members), mapBinding));
		McpTypedJsonBinding<NumericCollections> recordBinding = binding(NumericCollections.class);
		McpJsonObject nested = new McpJsonObject(Map.of(
				"integers", new McpJsonArray(List.of(number("10"))),
				"integerMap", new McpJsonObject(Map.of("value", number("10"))),
				"integerArray", new McpJsonArray(List.of(trap))));
		assertBudgetFailure("$/properties/integerArray/items",
				() -> limited.fromJson(nested, recordBinding));
	}

	@Test
	void fractionsAndZerosAreHandledWithoutIntegerExpansion() {
		McpTypedJsonBinding<BigInteger> binding = binding(BigInteger.class);
		for (BigDecimal decimal : List.of(new ExpansionTrapDecimal("1.5"),
				new ExpansionTrapDecimal(BigInteger.ONE, Integer.MAX_VALUE))) {
			McpTypedJsonBindingException exception = assertThrows(
					McpTypedJsonBindingException.class,
					() -> BINDER.fromJson(new McpJsonNumber(decimal), binding));
			assertEquals(McpTypedJsonBindingException.Reason.NON_INTEGER_NUMBER,
					exception.reason());
		}
		for (int scale : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE))
			assertEquals(BigInteger.ZERO, BINDER.fromJson(new McpJsonNumber(
					new ExpansionTrapDecimal(BigInteger.ZERO, scale)), binding));
	}

	@Test
	void integerLimitsArePositiveAndCannotExceedTheProductionCeilings() {
		for (int perValue : List.of(0, -1, 1025))
			assertThrows(IllegalArgumentException.class,
					() -> new McpTypedJsonBindingLimits(1, 1, 1, perValue, 1));
		for (int total : List.of(0, -1, 4 * 1024 * 1024 + 1))
			assertThrows(IllegalArgumentException.class,
					() -> new McpTypedJsonBindingLimits(1, 1, 1, 1, total));
	}

	private static void assertBudgetFailure(String path, Runnable invocation) {
		McpTypedJsonBindingException exception = assertThrows(
				McpTypedJsonBindingException.class, invocation::run);
		assertEquals(McpTypedJsonBindingException.Operation.FROM_JSON, exception.operation());
		assertEquals(McpTypedJsonBindingException.Reason.LIMIT_EXCEEDED, exception.reason());
		assertEquals(Optional.of(McpTypedJsonBindingException.Limit.TOTAL_EXPANDED_INTEGER_LENGTH),
				exception.limit());
		assertEquals(path, exception.path().toString());
		assertNull(exception.getCause());
	}

	private record NumericCollections(List<BigInteger> integers,
			Map<String, BigInteger> integerMap, BigInteger[] integerArray) {
	}

	private static final class NumericTypes {
		private List<BigInteger> integers;
		private Map<String, BigInteger> integerMap;
	}

	private static <T> McpTypedJsonBinding<T> binding(Type type) {
		McpTypedSchemaShape shape = new McpTypedSchemaResolver<>(
				new McpRuntimeTypedTypeModel(COMPILATION_LIMITS),
				COMPILATION_LIMITS).resolveSchema(type);
		return new McpRuntimeTypedJsonBindingCompiler(COMPILATION_LIMITS)
				.compile(type, shape);
	}

	private static McpJsonNumber number(String value) {
		return new McpJsonNumber(new BigDecimal(value));
	}

	private static void assertRangeFailure(Runnable invocation) {
		McpTypedJsonBindingException exception = assertThrows(
				McpTypedJsonBindingException.class, invocation::run);
		assertEquals(McpTypedJsonBindingException.Operation.FROM_JSON,
				exception.operation());
		assertEquals(McpTypedJsonBindingException.Reason.NUMBER_OUT_OF_RANGE,
				exception.reason());
		assertNull(exception.getCause());
	}

	private static final class ExpansionTrapDecimal extends BigDecimal {
		private ExpansionTrapDecimal(String value) {
			super(value);
		}

		private ExpansionTrapDecimal(BigInteger value, int scale) {
			super(value, scale);
		}

		@Override
		public BigInteger toBigIntegerExact() {
			throw new AssertionError("Integer conversion must not be reached.");
		}
	}
}
