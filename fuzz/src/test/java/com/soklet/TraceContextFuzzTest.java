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
import java.util.List;

/**
 * Fuzz target for the W3C trace-context parser. Soklet parses {@code traceparent}/{@code tracestate}
 * from untrusted inbound headers while constructing every {@link Request}, and the contract is
 * lenient: {@link TraceContext#fromHeaderValues} must be total - it returns {@link java.util.Optional#empty()}
 * for invalid input and must never throw. Any thrown exception or error is a fuzz finding, because a
 * throw here would break request handling for a crafted header.
 */
@ThreadSafe
public class TraceContextFuzzTest {
	private static volatile int sink;

	@FuzzTest(maxDuration = "2m")
	public void fromHeaderValuesNeverThrows(byte[] input) {
		String text = new String(input, StandardCharsets.UTF_8);

		// A NUL (0x00) byte splits the input into a single traceparent value plus one tracestate
		// value, so the fuzzer can reach both parsers; without a NUL the whole input is the traceparent.
		int split = text.indexOf(0);
		String traceparent = split < 0 ? text : text.substring(0, split);
		List<String> tracestate = split < 0 ? List.of() : List.of(text.substring(split + 1));

		// Returning normally (Optional, possibly empty) is required for ALL inputs; a throw is a finding.
		TraceContext.fromHeaderValues(List.of(traceparent), tracestate)
				.ifPresent(TraceContextFuzzTest::exercise);
	}

	private static void exercise(TraceContext context) {
		// Accessors and header re-emission must also be total on a successfully-parsed context.
		// The arithmetic into a volatile sink prevents the JIT from eliding the calls.
		sink = context.getTraceId().length()
				+ context.getParentId().length()
				+ context.getTraceFlags()
				+ (context.isSampled() ? 1 : 0)
				+ context.getTraceStateEntries().size()
				+ context.toTraceparentHeaderValue().length()
				+ context.toTracestateHeaderValue().map(String::length).orElse(0);
	}
}
