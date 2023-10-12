/*
 * Copyright 2022-2023 Revetware LLC.
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

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum RedirectType {
	HTTP_301_MOVED_PERMANENTLY(StatusCode.HTTP_301),
	HTTP_302_FOUND(StatusCode.HTTP_302),
	HTTP_303_SEE_OTHER(StatusCode.HTTP_303),
	HTTP_307_TEMPORARY_REDIRECT(StatusCode.HTTP_307),
	HTTP_308_PERMANENT_REDIRECT(StatusCode.HTTP_308);

	@Nonnull
	private final StatusCode statusCode;

	RedirectType(@Nonnull StatusCode statusCode) {
		requireNonNull(statusCode);
		this.statusCode = statusCode;
	}

	@Nonnull
	public StatusCode getStatusCode() {
		return this.statusCode;
	}
}
