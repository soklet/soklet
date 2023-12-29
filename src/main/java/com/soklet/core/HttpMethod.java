/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.core;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum HttpMethod {
	GET,
	POST,
	PUT,
	PATCH,
	OPTIONS,
	HEAD,
	DELETE;

	@Nonnull
	private static final Set<HttpMethod> VALUES_AS_SET;

	static {
		VALUES_AS_SET = Arrays.stream(HttpMethod.values()).collect(Collectors.toUnmodifiableSet());
	}

	@Nonnull
	public static Set<HttpMethod> valuesAsSet() {
		return VALUES_AS_SET;
	}
}