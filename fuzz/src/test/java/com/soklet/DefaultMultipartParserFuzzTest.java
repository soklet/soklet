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
import com.soklet.exception.IllegalRequestBodyException;

import javax.annotation.concurrent.ThreadSafe;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;

/**
 * Fuzz targets for the multipart/form-data parser.
 */
@ThreadSafe
public class DefaultMultipartParserFuzzTest {
	private static final String BOUNDARY = "----SokletFuzzBoundary";
	private static final Map<String, Set<String>> HEADERS = Map.of(
			"Content-Type", Set.of("multipart/form-data; boundary=" + BOUNDARY));
	private static volatile int sink;

	@FuzzTest(maxDuration = "2m")
	public void extractMultipartFieldsOnlyRejectsWithExpectedExceptions(byte[] requestBody) {
		Request request = Request.withPath(HttpMethod.POST, "/upload")
				.headers(HEADERS)
				.body(requestBody)
				.build();

		try {
			Map<String, Set<MultipartField>> fields = DefaultMultipartParser.defaultInstance()
					.extractMultipartFields(request);
			consume(fields);
		} catch (IllegalRequestBodyException | UncheckedIOException expected) {
			// Invalid multipart bodies are expected. Other RuntimeExceptions and Errors are fuzz findings.
		}
	}

	private static void consume(Map<String, Set<MultipartField>> fields) {
		int observed = 0;

		for (Map.Entry<String, Set<MultipartField>> entry : fields.entrySet()) {
			observed += entry.getKey().length();

			for (MultipartField field : entry.getValue()) {
				observed += field.getName().length();
				observed += field.getFilename().map(String::length).orElse(0);
				observed += field.getContentType().map(String::length).orElse(0);
				observed += field.getCharset().map(charset -> charset.name().length()).orElse(0);
				observed += field.getData().map(data -> data.length).orElse(0);
			}
		}

		sink = observed;
	}
}
