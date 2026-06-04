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
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Fuzz target for HTTP-date parsing. The parser contract is lenient: arbitrary header
 * values return an {@link java.util.Optional}, never an exception.
 */
@ThreadSafe
public class HttpDateFuzzTest {
	private static volatile int sink;

	@FuzzTest(maxDuration = "2m")
	public void fromHeaderValueNeverThrows(byte[] input) {
		String headerValue = new String(input, StandardCharsets.UTF_8);

		HttpDate.fromHeaderValue(headerValue).ifPresent(HttpDateFuzzTest::exercise);
		sink += HttpDate.currentSecondHeaderValue().length();
	}

	private static void exercise(Instant instant) {
		String canonical = HttpDate.toHeaderValue(instant);
		sink += canonical.length()
				+ HttpDate.fromHeaderValue(canonical)
				.map(parsed -> Long.hashCode(parsed.getEpochSecond()))
				.orElse(0);
	}
}
