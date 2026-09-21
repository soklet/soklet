/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
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
 * Concurrently callable application-owned {@code skills/list} page handler.
 *
 * <p>On the first page, return an ordered subsequence of the initial selection
 * supplied by the context. On continuation, restore the original ordered
 * selection and page position from an authenticated application-owned cursor.
 * Every returned registration must be an exact currently configured instance;
 * duplicate URIs and foreign or reconstructed instances are rejected. Current
 * access and discovery are rechecked on continuation without selecting a new
 * variant. A complete skill manifest is never split between pages.
 *
 * <p>The application owns cursor integrity, endpoint/caller binding, expiry,
 * content identities, retained snapshot availability, cross-page duplicate
 * prevention, and cross-node portability. Current membership alone does not
 * prove an old snapshot's content is unchanged. Do not retain request objects,
 * invocation features, or localization contexts as snapshot state. Invalid or
 * unavailable continuations should fail neutrally, without substituting content.
 * The handler controls list pages, not canonical file reads.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpSkillListHandler {
	/**
	 * Produces one complete page containing at most 32 registered skills.
	 *
	 * @param requestContext admitted request context
	 * @param skillListContext opaque cursor and authoritative first-page phase marker
	 * @param invocationFeatures invocation-scoped optional features
	 * @return non-null Skills page
	 * @throws Exception if page production or snapshot restoration fails
	 */
	@NonNull
	McpSkillPage handle(@NonNull McpRequestContext requestContext,
			@NonNull McpSkillListContext skillListContext,
			@NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
