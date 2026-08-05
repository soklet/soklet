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
 * Deprecated protocol log-level metadata retained for the MCP 2026-07-28
 * request contract.
 *
 * <p>Soklet parses and exposes this metadata but does not advertise or
 * implement the MCP Logging capability.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 * @deprecated the 2026-07-28 protocol retains this deprecated request
 * metadata only for wire compatibility
 */
@Deprecated(since = "3.6.0")
public enum McpLogLevel {
	/** Debug-level messages. */
	DEBUG,
	/** Informational messages. */
	INFO,
	/** Normal but significant messages. */
	NOTICE,
	/** Warning conditions. */
	WARNING,
	/** Error conditions. */
	ERROR,
	/** Critical conditions. */
	CRITICAL,
	/** Immediate action is required. */
	ALERT,
	/** The system is unusable. */
	EMERGENCY
}
