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

/**
 * Thread-safe application provider of request-local MCP localization contexts.
 * <p>
 * Soklet may call one provider concurrently for independent admitted requests.
 * The provider is application-owned and is never closed by Soklet. Context
 * creation runs under the operation's existing bounded execution owner:
 * application operations consume their acquired handler slot, while framework
 * catalogs and subscriptions consume bounded protocol ownership. Creation
 * therefore must use already-loaded, in-memory state rather than remote
 * translation lookup.
 * <p>
 * Soklet exposes the exact returned context to application handlers and
 * interceptors. Soklet's no-leak guarantee applies when Soklet invokes this
 * callback. Direct application invocation remains application-owned and has
 * normal application failure semantics.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpLocalizationContextProvider {
	/**
	 * Creates the immutable localization context for one admitted
	 * localization-capable operation.
	 *
	 * @param request bounded localization inputs and admitted request context
	 * @return non-null request-local context
	 * @throws Exception if context creation fails; when Soklet invokes this
	 * callback it treats the exception as untrusted localization data and never
	 * forwards it through framework-owned lifecycle, simulation, logging,
	 * response-throwable, or cause surfaces
	 */
	@NonNull
	McpLocalizationContext createContext(
			@NonNull McpLocalizationRequest request) throws Exception;
}
