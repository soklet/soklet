/*
 * Copyright 2022-2025 Revetware LLC.
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

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Internal type used by {@link SokletProcessor} to serialize (and {@link DefaultResourceMethodResolver} to deserialize) definitions of <em>Resource Methods</em>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record ResourceMethodDeclaration(
		@Nonnull
		HttpMethod httpMethod,
		@Nonnull
		String path,
		@Nonnull
		String className,
		@Nonnull
		String methodName,
		@Nonnull
		String[] parameterTypes,
		@Nonnull
		Boolean serverSentEventSource
) {
	ResourceMethodDeclaration {
		requireNonNull(httpMethod);
		requireNonNull(path);
		requireNonNull(className);
		requireNonNull(methodName);
		requireNonNull(parameterTypes);
		requireNonNull(serverSentEventSource);
	}
}