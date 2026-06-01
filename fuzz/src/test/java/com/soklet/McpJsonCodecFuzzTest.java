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

import com.code_intelligence.jazzer.junit.FuzzTest;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Map;

/**
 * Fuzz targets for the MCP JSON codec. Regression mode replays the checked-in
 * corpus deterministically; fuzzing mode mutates it coverage-guided.
 */
@ThreadSafe
public class McpJsonCodecFuzzTest {
	@FuzzTest(maxDuration = "2m")
	public void parseArbitraryBytesOnlyRejectsWithIllegalArgumentException(byte[] jsonBytes) {
		try {
			McpJsonCodec.parse(jsonBytes);
		} catch (IllegalArgumentException expected) {
			// Invalid JSON is expected. Runtime exceptions and Errors are fuzz findings.
		}
	}

	@FuzzTest(maxDuration = "2m")
	public void parseSerializeParseRoundTripsStructurally(byte[] jsonBytes) {
		McpValue parsed;

		try {
			parsed = McpJsonCodec.parse(jsonBytes);
		} catch (IllegalArgumentException invalidJson) {
			// Only well-formed JSON carries a round-trip obligation.
			return;
		}

		McpValue reparsed = McpJsonCodec.parse(McpJsonCodec.toUtf8Bytes(parsed));

		if (!jsonEqual(parsed, reparsed))
			throw new AssertionError("parse -> serialize -> parse mismatch:"
					+ "\n  first:  " + McpJsonCodec.toJson(parsed)
					+ "\n  second: " + McpJsonCodec.toJson(reparsed));
	}

	/**
	 * Structural JSON equality for round-trip checking. Strings are compared exactly, so an unpaired
	 * surrogate corrupted to '?' is caught; numbers are compared by value rather than {@link java.math.BigDecimal}
	 * representation, so {@code 1e1} and {@code 10} are treated as equal (the codec normalizes representation,
	 * not value).
	 */
	private static boolean jsonEqual(McpValue a, McpValue b) {
		if (a instanceof McpObject objectA && b instanceof McpObject objectB) {
			Map<String, McpValue> valuesA = objectA.values();
			Map<String, McpValue> valuesB = objectB.values();

			if (!valuesA.keySet().equals(valuesB.keySet()))
				return false;

			for (Map.Entry<String, McpValue> entry : valuesA.entrySet())
				if (!jsonEqual(entry.getValue(), valuesB.get(entry.getKey())))
					return false;

			return true;
		}

		if (a instanceof McpArray arrayA && b instanceof McpArray arrayB) {
			List<McpValue> valuesA = arrayA.values();
			List<McpValue> valuesB = arrayB.values();

			if (valuesA.size() != valuesB.size())
				return false;

			for (int i = 0; i < valuesA.size(); i++)
				if (!jsonEqual(valuesA.get(i), valuesB.get(i)))
					return false;

			return true;
		}

		if (a instanceof McpString stringA && b instanceof McpString stringB)
			return stringA.value().equals(stringB.value());

		if (a instanceof McpNumber numberA && b instanceof McpNumber numberB)
			return numberA.value().compareTo(numberB.value()) == 0;

		if (a instanceof McpBoolean booleanA && b instanceof McpBoolean booleanB)
			return booleanA.value().equals(booleanB.value());

		return a instanceof McpNull && b instanceof McpNull;
	}
}
