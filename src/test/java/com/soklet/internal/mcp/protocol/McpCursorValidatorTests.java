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

public class McpCursorValidatorTests {
	@Test
	public void countsUtf8BytesAndRejectsMalformedUtf16() {
		Assertions.assertTrue(
				McpCursorValidator.fitsWithinUtf8ByteLimit("", 1));
		Assertions.assertTrue(
				McpCursorValidator.fitsWithinUtf8ByteLimit("a¢界\uD83D\uDE00", 10));
		Assertions.assertFalse(
				McpCursorValidator.fitsWithinUtf8ByteLimit("a¢界\uD83D\uDE00", 9));
		Assertions.assertFalse(
				McpCursorValidator.fitsWithinUtf8ByteLimit("\uD83D", 4));
		Assertions.assertFalse(
				McpCursorValidator.fitsWithinUtf8ByteLimit("\uDE00", 4));
		Assertions.assertFalse(
				McpCursorValidator.fitsWithinUtf8ByteLimit("\uD83Da", 4));
	}

	@Test
	public void requiresPositiveLimit() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpCursorValidator.fitsWithinUtf8ByteLimit("", 0));
	}
}
