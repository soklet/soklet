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

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Indicates that an MCP task operation cannot resolve an authorized task.
 *
 * <p>A manager throws this same fixed-semantic exception for both an unknown
 * task ID and a task the current admitted request is not authorized to access.
 * The exception deliberately carries no task ID, authorization detail, or
 * application cause that might disclose task existence.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public final class McpTaskNotFoundException extends Exception {
	private static final long serialVersionUID = 1L;

	/** Creates the fixed non-disclosing task-not-found signal. */
	public McpTaskNotFoundException() {
		super("An authorized MCP task was not found.");
	}
}
