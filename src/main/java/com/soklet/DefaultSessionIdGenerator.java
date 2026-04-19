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
import java.security.SecureRandom;
import java.util.Base64;

import static java.util.Objects.requireNonNull;

/**
 * {@link IdGenerator} implementation for cryptographically strong session IDs.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultSessionIdGenerator implements IdGenerator<String> {
	private static final int RANDOM_BYTES = 24;
	@NonNull
	private final SecureRandom secureRandom;

	private DefaultSessionIdGenerator() {
		this.secureRandom = new SecureRandom();
	}

	/**
	 * Returns a {@link DefaultSessionIdGenerator}.
	 * <p>
	 * Callers should not rely on reference identity; this method may
	 * return a new or cached instance.
	 *
	 * @return a session ID generator
	 */
	@NonNull
	public static DefaultSessionIdGenerator defaultInstance() {
		return new DefaultSessionIdGenerator();
	}

	@NonNull
	@Override
	public String generateId(@NonNull Request request) {
		requireNonNull(request);
		byte[] randomBytes = new byte[RANDOM_BYTES];
		this.secureRandom.nextBytes(randomBytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
	}
}
