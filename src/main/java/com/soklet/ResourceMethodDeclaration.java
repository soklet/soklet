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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Internal type used by {@link SokletProcessor} to serialize (and {@link DefaultResourceMethodResolver} to deserialize) definitions of <em>Resource Methods</em>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record ResourceMethodDeclaration(
		@NonNull
		HttpMethod httpMethod,
		@NonNull
		String path,
		@NonNull
		String className,
		@NonNull
		String methodName,
		@NonNull
		String[] parameterTypes,
		@NonNull
		Boolean sseEventSource
) {
	ResourceMethodDeclaration {
		requireNonNull(httpMethod);
		requireNonNull(path);
		requireNonNull(className);
		requireNonNull(methodName);
		requireNonNull(parameterTypes);
		requireNonNull(sseEventSource);
	}

	/** Reads the existing semicolon-delimited index, including reference-array descriptor terminators. */
	@NonNull
	static String[] parameterTypesFromIndex(@NonNull String encoded) {
		requireNonNull(encoded);
		if (encoded.isEmpty())
			return new String[0];
		java.util.List<String> types = new java.util.ArrayList<>();
		int start = 0;
		while (start < encoded.length()) {
			int cursor = start;
			while (cursor < encoded.length() && encoded.charAt(cursor) == '[')
				cursor++;
			int end;
			if (cursor > start && cursor < encoded.length() && encoded.charAt(cursor) == 'L') {
				int terminator = encoded.indexOf(';', cursor);
				if (terminator < 0 || terminator == cursor + 1)
					throw new IllegalArgumentException("Malformed reference-array parameter in route index.");
				end = terminator + 1;
				if (end < encoded.length() && encoded.charAt(end) != ';')
					throw new IllegalArgumentException("Missing parameter separator in route index.");
			} else {
				end = encoded.indexOf(';', start);
				if (end < 0)
					end = encoded.length();
			}
			if (end == start)
				throw new IllegalArgumentException("Empty parameter type in route index.");
			types.add(encoded.substring(start, end));
			if (end == encoded.length())
				break;
			start = end + 1;
			if (start == encoded.length())
				throw new IllegalArgumentException("Trailing parameter separator in route index.");
		}
		return types.toArray(String[]::new);
	}
}
