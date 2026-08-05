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
 * Programmatic dynamic {@code resources/list} handler.
 *
 * <p>When an endpoint has this handler, its returned page is the sole
 * authority for that response. Soklet does not implicitly merge exact-URI
 * registrations into the page. Implementations must be safe for concurrent
 * invocation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpResourceListHandler {
	/**
	 * Produces one complete resource-list page.
	 *
	 * @param request request metadata
	 * @param list pagination input and registered-descriptor convenience data
	 * @param features invocation-scoped optional features
	 * @return non-null resource page
	 * @throws Exception if application handling fails
	 */
	@NonNull
	McpResourcePage handle(@NonNull McpRequestContext request,
			@NonNull McpResourceListContext list,
			@NonNull McpInvocationFeatures features) throws Exception;
}
