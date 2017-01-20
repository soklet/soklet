/*
 * Copyright 2015 Transmogrify LLC.
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
package com.soklet.util;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;

import static com.soklet.util.StringUtils.trimToNull;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.1.13
 */
public final class RequestUtils {
	private RequestUtils() {
	}

	public static enum QueryStringParseStrategy {
		INCLUDE_NULL_VALUES,
		EXCLUDE_NULL_VALUES
	}

	public static Map<String, List<String>> parseQueryString(String queryString, QueryStringParseStrategy queryStringParseStrategy) {
		Objects.requireNonNull(queryStringParseStrategy);

		queryString = trimToNull(queryString);

		if (queryString == null)
			return Collections.emptyMap();

		Map<String, List<String>> parsedQueryString = new LinkedHashMap<>();

		String[] groups = queryString.split("&");

		for (String group : groups) {
			String[] pair = group.split("=");

			if (pair.length == 0)
				continue;

			String name = trimToNull(pair[0]);
			String value = pair.length > 1 ? trimToNull(pair[1]) : null;

			if (name == null)
				continue;

			if (value == null && queryStringParseStrategy == QueryStringParseStrategy.EXCLUDE_NULL_VALUES)
				continue;

			List<String> values = parsedQueryString.get(name);

			if (values == null) {
				values = new ArrayList<>();
				parsedQueryString.put(name, values);
			}

			values.add(urlDecode(value));
		}

		return parsedQueryString;
	}

	public static String urlDecode(String string) {
		try {
			return string == null ? null : URLDecoder.decode(string, "UTF-8");
		} catch (final UnsupportedEncodingException e) {
			// Impossible
			throw new IllegalStateException("UTF-8 not supported", e);
		}
	}
}