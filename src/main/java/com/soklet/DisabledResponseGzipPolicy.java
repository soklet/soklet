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
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DisabledResponseGzipPolicy implements ResponseGzipPolicy {
	@NonNull
	private static final DisabledResponseGzipPolicy INSTANCE;

	static {
		INSTANCE = new DisabledResponseGzipPolicy();
	}

	private DisabledResponseGzipPolicy() {}

	@NonNull
	static DisabledResponseGzipPolicy defaultInstance() {
		return INSTANCE;
	}

	@Override
	@NonNull
	public Boolean shouldGzip(@NonNull Request request,
														@NonNull MarshaledResponse response) {
		requireNonNull(request);
		requireNonNull(response);
		return false;
	}
}
