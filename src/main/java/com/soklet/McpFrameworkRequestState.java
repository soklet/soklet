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
 * Immutable application-defined JSON state whose wire representation is
 * protected by Soklet.
 *
 * @param value decrypted application-defined JSON value
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public record McpFrameworkRequestState(
		@NonNull McpJsonValue value) implements McpRequestState {
	/**
	 * Creates framework-protected request state.
	 *
	 * @param value decrypted application-defined JSON value
	 */
	public McpFrameworkRequestState {
		requireNonNull(value);
	}
}
