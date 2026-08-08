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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Tests for immutable public MCP progress values.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpProgressUpdateTests {
	@Test
	public void builds_minimal_and_complete_updates() {
		McpProgressUpdate minimal = McpProgressUpdate.withProgress(2.5d).build();
		Assertions.assertEquals(2.5d, minimal.getProgress());
		Assertions.assertEquals(Optional.empty(), minimal.getTotal());
		Assertions.assertEquals(Optional.empty(), minimal.getMessage());

		McpProgressUpdate complete = McpProgressUpdate.withProgress(7.25d)
				.total(10.5d)
				.message("Working")
				.build();
		Assertions.assertEquals(7.25d, complete.getProgress());
		Assertions.assertEquals(Optional.of(10.5d), complete.getTotal());
		Assertions.assertEquals(Optional.of("Working"), complete.getMessage());
	}

	@Test
	public void rejects_nonfinite_numbers_and_null_messages() {
		for (double value : new double[]{
				Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpProgressUpdate.withProgress(value));
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpProgressUpdate.withProgress(1.0d).total(value));
		}

		Assertions.assertThrows(NullPointerException.class,
				() -> McpProgressUpdate.withProgress(1.0d).message(null));
	}

	@Test
	public void normalizes_negative_zero_to_the_json_zero_value() {
		McpProgressUpdate update = McpProgressUpdate.withProgress(-0.0d)
				.total(-0.0d)
				.build();
		Assertions.assertEquals(
				Double.doubleToLongBits(0.0d),
				Double.doubleToLongBits(update.getProgress()));
		Assertions.assertEquals(
				Double.doubleToLongBits(0.0d),
				Double.doubleToLongBits(update.getTotal().orElseThrow()));
	}
}
