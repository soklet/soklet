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
 * Core client-input request types an MCP operation may emit.
 *
 * <p>The protocol-defined set may grow as Soklet adopts later MCP profiles;
 * callers switching over these values should retain a forward-compatible
 * default.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpInputRequestType {
	/** Form-based elicitation through {@code elicitation/create}. */
	ELICITATION_FORM,
	/** URL-based elicitation through {@code elicitation/create}. */
	ELICITATION_URL,
	/**
	 * Model sampling through {@code sampling/createMessage}.
	 *
	 * <p>SEP-2577 marks Sampling deprecated in MCP 2026-07-28, with
	 * specification removal eligible no earlier than 2027-07-28. Prefer direct
	 * model-provider integration. Soklet keeps this constant functional for
	 * every supported profile containing it and has made no Java API-removal
	 * decision.
	 */
	SAMPLING,
	/**
	 * Client-root discovery through {@code roots/list}.
	 *
	 * <p>SEP-2577 marks Roots deprecated in MCP 2026-07-28, with specification
	 * removal eligible no earlier than 2027-07-28. Prefer explicit tool
	 * parameters, resource URIs, or server configuration. Soklet keeps this
	 * constant functional for every supported profile containing it and has
	 * made no Java API-removal decision.
	 */
	ROOTS
}
