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
}
