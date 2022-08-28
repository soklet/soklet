/*
 * Copyright 2022 Revetware LLC.
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

package com.soklet.exception;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.net.HttpCookie;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@NotThreadSafe
public class IllegalRequestCookieException extends BadRequestException {
	@Nonnull
	private final HttpCookie cookie;

	public IllegalRequestCookieException(@Nullable String message,
																			 @Nonnull HttpCookie cookie) {
		super(message);
		this.cookie = requireNonNull(cookie);
	}

	public IllegalRequestCookieException(@Nullable String message,
																			 @Nullable Throwable cause,
																			 @Nonnull HttpCookie cookie) {
		super(message, cause);
		this.cookie = requireNonNull(cookie);
	}

	@Nonnull
	public HttpCookie getCookie() {
		return this.cookie;
	}
}