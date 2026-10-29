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

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Compiler access to the runtime's resource-template grammar and overlap rules.
 * Public only for use across Soklet's internal package boundary; not a supported API.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class McpResourceTemplateValidation {
	private McpResourceTemplateValidation() {}

	@NonNull
	public static Template parse(@NonNull String template) {
		return new Template(McpLevelOneUriTemplate.parse(template));
	}

	@NonNull
	public static OverlapBudget endpointOverlapBudget() {
		return new OverlapBudget();
	}

	public static final class Template {
		@NonNull
		private final McpLevelOneUriTemplate delegate;

		private Template(@NonNull McpLevelOneUriTemplate delegate) {
			this.delegate = requireNonNull(delegate);
		}

		@NonNull
		public Set<@NonNull String> variableNames() {
			return delegate.variableNames();
		}

		public boolean potentiallyOverlaps(@NonNull Template other, @NonNull OverlapBudget budget) {
			return delegate.potentiallyOverlaps(requireNonNull(other).delegate, requireNonNull(budget).delegate);
		}
	}

	public static final class OverlapBudget {
		private final McpLevelOneUriTemplate.@NonNull OverlapComparisonBudget delegate =
				McpLevelOneUriTemplate.endpointOverlapComparisonBudget();

		private OverlapBudget() {}

		public boolean exhausted() {
			return delegate.exhausted();
		}
	}
}
