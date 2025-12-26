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
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * {@link IdGenerator} implementation which includes a sequence number and a best-effort attempt to extract local IP address.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultIdGenerator implements IdGenerator<String> {
	@Nonnull
	private static final String DEFAULT_ID_PREFIX;

	static {
		String idPrefix = "";

		// IDs ultimately look like "192.168.4.75-1234" (or just "1234" if we can't detect host address)
		try {
			String hostAddress = InetAddress.getLocalHost().getHostAddress();

			if (hostAddress != null) {
				hostAddress = hostAddress.trim();

				if (hostAddress.length() > 0)
					idPrefix = format("%s-", hostAddress);
			}
		} catch (Exception e) {
			// Ignored
		}

		DEFAULT_ID_PREFIX = idPrefix;
	}

	@Nonnull
	private final AtomicLong idGenerator;
	@Nonnull
	private final String idPrefix;

	/**
	 * Returns a {@link DefaultIdGenerator} with a best-effort local IP prefix.
	 * <p>
	 * Callers should not rely on reference identity; this method may
	 * return a new or cached instance.
	 *
	 * @return a {@code DefaultIdGenerator} with default settings
	 */
	@Nonnull
	public static DefaultIdGenerator withDefaults() {
		return new DefaultIdGenerator(null);
	}

	/**
	 * Returns a {@link DefaultIdGenerator} with the given prefix.
	 * <p>
	 * Callers should not rely on reference identity; this method may
	 * return a new or cached instance.
	 *
	 * @param prefix an optional string to prepend to generated IDs,
	 *               or {@code null} to use the default host-based prefix
	 * @return a {@code DefaultIdGenerator} configured with the given prefix
	 */
	@Nonnull
	public static DefaultIdGenerator withPrefix(@Nullable String prefix) {
		return new DefaultIdGenerator(prefix);
	}

	private DefaultIdGenerator(@Nullable String idPrefix) {
		if (idPrefix == null)
			idPrefix = getDefaultIdPrefix();

		this.idPrefix = idPrefix;
		this.idGenerator = new AtomicLong(0L);
	}

	@Nonnull
	@Override
	public String generateId(@Nonnull Request request) {
		requireNonNull(request);
		return format("%s%s", getIdPrefix(), getIdGenerator().getAndIncrement());
	}

	@Nonnull
	private String getDefaultIdPrefix() {
		return DEFAULT_ID_PREFIX;
	}

	@Nonnull
	private AtomicLong getIdGenerator() {
		return this.idGenerator;
	}

	@Nonnull
	private String getIdPrefix() {
		return this.idPrefix;
	}
}
