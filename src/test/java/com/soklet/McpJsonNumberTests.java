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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Numeric equality and hash collection behavior without losing the supplied decimal. */
class McpJsonNumberTests {
	@Test
	void equivalentSpellingsHaveNumericEqualityAndTheSameHash() {
		for (List<String> spellings : List.of(
				List.of("1", "1.0", "1.00", "1E+0"),
				List.of("100", "1E+2", "100.000", "10E+1"),
				List.of("1.5", "1.50", "150E-2"),
				List.of("-1.5", "-1.50", "-150E-2"),
				List.of("0", "-0.000", "0E+9999", "0E-9999"),
				List.of("1E+9999", "10E+9998", "100E+9997"),
				List.of("1E-9999", "10E-10000", "0.10E-9998"))) {
			List<McpJsonNumber> values = spellings.stream().map(McpJsonNumberTests::number).toList();
			for (McpJsonNumber left : values) {
				assertEquals(left, left);
				for (McpJsonNumber right : values) {
					assertEquals(left, right);
					assertEquals(right, left);
					assertEquals(left.hashCode(), right.hashCode());
				}
			}
			assertEquals(1, new HashSet<>(values).size());
		}
		assertNotEquals(number("1"), number("-1"));
		assertNotEquals(number("1"), number("1.0001"));
		assertNotEquals(number("1"), McpJsonString.fromValue("1"));
		assertNotEquals(number("1"), McpJsonBoolean.fromValue(true));
		assertNotEquals(number("1"), null);
	}

	@Test
	void nestedTreesUseNumericHashesAndKeepObjectAndArraySemantics() {
		McpJsonObject original = McpJsonObject.builder()
				.put("amount", new BigDecimal("1.50"))
				.put("items", McpJsonArray.fromElements(List.of(number("100.00"), number("-0.000"))))
				.build();
		McpJsonObject normalized = McpJsonObject.builder()
				.put("items", McpJsonArray.fromElements(List.of(number("1E+2"), number("0"))))
				.put("amount", new BigDecimal("1.5")).build();
		assertEquals(original, normalized);
		assertEquals(original.hashCode(), normalized.hashCode());
		Map<McpJsonValue, String> lookup = new HashMap<>();
		lookup.put(original, "found");
		assertEquals("found", lookup.get(normalized));
		assertEquals(1, new HashSet<>(List.of(original, normalized)).size());
		assertNotEquals(original, McpJsonObject.builder()
				.put("amount", new BigDecimal("1.5"))
				.put("items", McpJsonArray.fromElements(List.of(number("0"), number("100")))).build());
	}

	@Test
	void getValueRetainsTheSuppliedDecimalAndScale() {
		for (String spelling : List.of("100.00", "1.50", "-0.000", "1E+9999", "1E-9999")) {
			BigDecimal supplied = new BigDecimal(spelling);
			McpJsonNumber value = McpJsonNumber.fromValue(supplied);
			value.hashCode();
			assertEquals(value, number(spelling));
			assertSame(supplied, value.getValue());
			assertEquals(supplied.scale(), value.getValue().scale());
			assertEquals("McpJsonNumber{value=<redacted>}", value.toString());
		}
	}

	@Test
	void equalityAndHashingWorkAtBothBigDecimalScaleExtremes() {
		assertEquivalent(new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE),
				new BigDecimal(BigInteger.valueOf(100), Integer.MIN_VALUE + 1));
		assertEquivalent(new BigDecimal(BigInteger.TEN.negate(), Integer.MIN_VALUE),
				new BigDecimal(BigInteger.valueOf(-100), Integer.MIN_VALUE + 1));
		assertEquivalent(new BigDecimal(BigInteger.TEN, Integer.MAX_VALUE),
				new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE - 1));
		assertEquivalent(new BigDecimal(BigInteger.ZERO, Integer.MIN_VALUE),
				new BigDecimal(BigInteger.ZERO, Integer.MAX_VALUE));
		// The public value factory accepts these decimals even though the wire
		// codec rejects their exponents. Hashing must not underflow the scale.
		assertThrows(ArithmeticException.class,
				() -> new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE).stripTrailingZeros());
	}

	@Test
	void numericEqualityWorksAcrossBoundedCoefficientsAndScaleOffsets() {
		for (long coefficient : List.of(-123456789L, -10L, 1L, 7L, 1000L)) {
			for (int scale : List.of(Integer.MIN_VALUE + 16, -10000, 0, 10000, Integer.MAX_VALUE - 16)) {
				BigInteger unscaled = BigInteger.valueOf(coefficient);
				BigDecimal base = new BigDecimal(unscaled, scale);
				for (int zeros : List.of(1, 3, 8))
					assertEquivalent(base, new BigDecimal(unscaled.multiply(BigInteger.TEN.pow(zeros)), scale + zeros));
			}
		}
	}

	@Test
	void comparisonAndHashingDoNotRenderOrExpandExponentValues() {
		McpJsonNumber guarded = McpJsonNumber.fromValue(new ExpansionTrap(BigInteger.ONE, -9999));
		assertEquals(number("10E+9998"), guarded);
		assertEquals(guarded, number("10E+9998"));
		assertEquals(number("10E+9998").hashCode(), guarded.hashCode());
	}

	private static void assertEquivalent(BigDecimal left, BigDecimal right) {
		assertEquals(0, left.compareTo(right));
		McpJsonNumber first = McpJsonNumber.fromValue(left);
		McpJsonNumber second = McpJsonNumber.fromValue(right);
		assertEquals(first, second);
		assertEquals(second, first);
		assertEquals(first.hashCode(), second.hashCode());
		Map<McpJsonNumber, String> lookup = new HashMap<>();
		lookup.put(first, "found");
		assertEquals("found", lookup.get(second));
	}

	private static McpJsonNumber number(String value) {
		return McpJsonNumber.fromValue(new BigDecimal(value));
	}

	private static final class ExpansionTrap extends BigDecimal {
		private ExpansionTrap(BigInteger unscaledValue, int scale) { super(unscaledValue, scale); }
		@Override public String toString() { throw new AssertionError("Numeric equality rendered a value."); }
		@Override public String toPlainString() { throw new AssertionError("Numeric equality expanded a value."); }
		@Override public BigInteger toBigIntegerExact() { throw new AssertionError("Numeric equality converted a value."); }
	}
}
