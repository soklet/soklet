/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.Optional;

/**
 * Concurrently callable selector for one explicit Skills variant group.
 *
 * <p>Only accessible, discoverable registrations are supplied. Return one exact
 * supplied registration instance, not an equal copy, or omit the group with
 * {@link Optional#empty()}. A foreign registration, null result, or callback
 * failure fails listing without publishing a partial result. Selection grants
 * no access; direct URI lookup bypasses it and independently rechecks access.
 *
 * <p>A configured selector runs for every nonempty filtered group, including a
 * singleton. Empty filtered groups do not invoke it. Declared multivariant
 * groups require an explicit selector, even if filtering leaves one candidate.
 * The application owns fallback selection and must respect the supplied bounded
 * language preferences, including zero-weight exclusions. The existing
 * localization context is available through invocation features rather than a
 * separate negotiation; Soklet does not replace a custom selector's policy
 * with automatic regional matching.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpSkillVariantSelector {
	/**
	 * Selects at most one of the supplied canonical registrations for listing.
	 *
	 * @param requestContext admitted request context
	 * @param skillVariantSelectionContext group key, filtered candidates, and bounded client preferences
	 * @param invocationFeatures invocation-scoped optional features
	 * @return one supplied registration instance, or empty to omit the group; never null
	 * @throws Exception if variant selection fails
	 */
	@NonNull
	Optional<@NonNull McpSkillRegistration> select(@NonNull McpRequestContext requestContext,
			@NonNull McpSkillVariantSelectionContext skillVariantSelectionContext,
			@NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
