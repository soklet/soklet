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

package com.soklet.core.impl;

import com.soklet.core.IdGenerator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultIdGenerator implements IdGenerator {
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
	private final Long minimumId;
	@Nonnull
	private final Long maximumId;
	@Nonnull
	private final AtomicLong idGenerator;
	@Nonnull
	private final String idPrefix;

	public DefaultIdGenerator() {
		this(1L, 9_999_999L);
	}

	public DefaultIdGenerator(@Nonnull Long minimumId,
														@Nonnull Long maximumId) {
		this(minimumId, maximumId, null);
	}

	public DefaultIdGenerator(@Nonnull Long minimumId,
														@Nonnull Long maximumId,
														@Nullable String idPrefix) {
		requireNonNull(minimumId);
		requireNonNull(maximumId);

		if (minimumId < 0)
			throw new IllegalArgumentException(format("Minimum ID (%s) cannot be negative", minimumId));

		if (maximumId < 1)
			throw new IllegalArgumentException(format("Maximum ID (%s) must be greater than zero", maximumId));

		if (minimumId >= maximumId)
			throw new IllegalArgumentException(format("Minimum ID (%s) must be less than maximum ID (%s)", minimumId, maximumId));

		if (idPrefix == null)
			idPrefix = getDefaultIdPrefix();

		this.minimumId = minimumId;
		this.maximumId = maximumId;
		this.idPrefix = idPrefix;
		this.idGenerator = new AtomicLong(getMinimumId());
	}

	@Nonnull
	@Override
	public Object generateId() {
		return format("%s%s", getIdPrefix(), getIdGenerator().getAndAccumulate(getMaximumId(),
				(currentId, ignored) -> currentId < getMaximumId() ? ++currentId : getMinimumId()));
	}

	@Nonnull
	protected String getDefaultIdPrefix() {
		return DEFAULT_ID_PREFIX;
	}

	@Nonnull
	protected AtomicLong getIdGenerator() {
		return this.idGenerator;
	}

	@Nonnull
	protected Long getMinimumId() {
		return this.minimumId;
	}

	@Nonnull
	protected Long getMaximumId() {
		return this.maximumId;
	}

	@Nonnull
	protected String getIdPrefix() {
		return this.idPrefix;
	}
}