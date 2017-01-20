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

package com.soklet.web.exception;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.1.13
 */
public class IllegalFormParameterException extends BadRequestException {
	private final String formParameterName;
	private final Optional<String> formParameterValue;

	public IllegalFormParameterException(String message, String formParameterName, Optional<String> formParameterValue) {
		super(message);
		this.formParameterName = requireNonNull(formParameterName);
		this.formParameterValue = requireNonNull(formParameterValue);
	}

	public IllegalFormParameterException(String message, Throwable cause, String formParameterName,
																			 Optional<String> formParameterValue) {
		super(message, cause);
		this.formParameterName = requireNonNull(formParameterName);
		this.formParameterValue = requireNonNull(formParameterValue);
	}

	public String formParameterName() {
		return this.formParameterName;
	}

	public Optional<String> formParameterValue() {
		return this.formParameterValue;
	}
}