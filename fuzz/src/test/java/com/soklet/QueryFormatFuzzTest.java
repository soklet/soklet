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
import com.soklet.exception.IllegalRequestException;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Fuzz targets for query-string extraction in both supported query formats.
 */
@ThreadSafe
public class QueryFormatFuzzTest {
	private static volatile int sink;

	@FuzzTest(maxDuration = "2m")
	public void extractQueryParametersOnlyRejectsWithIllegalRequestException(byte[] input) {
		String text = new String(input, StandardCharsets.UTF_8);

		for (QueryFormat queryFormat : QueryFormat.values()) {
			expectOnlyIllegalRequest(() -> consume(Utilities.extractQueryParametersFromQuery(text, queryFormat)));
			expectOnlyIllegalRequest(() -> consume(Utilities.extractQueryParametersFromUrl("/fuzz?" + text, queryFormat)));
		}
	}

	private static void expectOnlyIllegalRequest(Runnable runnable) {
		try {
			runnable.run();
		} catch (IllegalRequestException expected) {
			// Malformed URLs or percent-encoding are expected. Other RuntimeExceptions and Errors are fuzz findings.
		}
	}

	private static void consume(Map<String, Set<String>> values) {
		int observed = values.size();

		for (Map.Entry<String, Set<String>> entry : values.entrySet()) {
			observed += entry.getKey().length();

			for (String value : entry.getValue())
				observed += value.length();
		}

		sink += observed;
	}
}
