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

/**
 * Fuzz targets for parameterized HTTP header value rendering.
 */
@ThreadSafe
public class ParameterizedHeaderValueFuzzTest {
	private static volatile int sink;

	@FuzzTest(maxDuration = "2m")
	public void builderOnlyRejectsWithIllegalArgumentException(byte[] input) {
		String[] fields = new String(input, StandardCharsets.UTF_8).split("\n", -1);
		String primaryValue = field(fields, 0);
		String parameterName = field(fields, 1);
		String parameterValue = field(fields, 2);

		expectOnlyIllegalArgument(() -> consume(ParameterizedHeaderValue.withName(primaryValue)
				.build()));
		expectOnlyIllegalArgument(() -> consume(ParameterizedHeaderValue.withName(primaryValue)
				.tokenParameter(parameterName, parameterValue)
				.build()));
		expectOnlyIllegalArgument(() -> consume(ParameterizedHeaderValue.withName(primaryValue)
				.quotedParameter(parameterName, parameterValue)
				.build()));
		expectOnlyIllegalArgument(() -> consume(ParameterizedHeaderValue.withName(primaryValue)
				.rfc8187Parameter(parameterName, parameterValue)
				.build()));
	}

	private static String field(String[] fields, int index) {
		return index < fields.length ? fields[index] : "";
	}

	private static void expectOnlyIllegalArgument(Runnable runnable) {
		try {
			runnable.run();
		} catch (IllegalArgumentException expected) {
			// Invalid header fragments are expected. Other RuntimeExceptions and Errors are fuzz findings.
		}
	}

	private static void consume(ParameterizedHeaderValue value) {
		sink += value.getName().length()
				+ value.getParameters().size()
				+ value.getStringValue().length()
				+ value.toString().length()
				+ value.hashCode();
	}
}
