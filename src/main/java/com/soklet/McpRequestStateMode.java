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

/**
 * Declares the request-state contract for one MCP operation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpRequestStateMode {
	/** The operation does not use request state. */
	NONE,
	/** Soklet protects application-defined JSON state on the wire. */
	FRAMEWORK_PROTECTED,
	/** The application owns protection of an opaque state string. */
	APPLICATION_PROTECTED
}
