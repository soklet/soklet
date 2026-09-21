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
package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Construction-time bridge to the ordinary resource router's template matcher.
 * Public only for internal integration with the com.soklet facade; this is not
 * a supported application API or a resource route.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSkillResourceCollisionValidator {
	private McpSkillResourceCollisionValidator() {}

	/**
	 * Rejects a Skills file that could be served by an ordinary resource template.
	 * Uses exactly the router's URI normalization, candidate pruning, and shared
	 * per-URI matching-work limit. Matching errors fail closed. Exact-resource
	 * collisions remain the caller's {@link URI#equals(Object)} identity check.
	 *
	 * <p>The caller supplies its immutable file-index keys. This
	 * method does not add a separate lifetime or aggregate catalog budget. When
	 * no templates exist, it imposes no template-routing URI-length restriction.
	 *
	 * @param templates ordinary resource templates, compiled once per invocation
	 * @param skillFileUris the Skills file-index keys
	 * @throws IllegalArgumentException if a template matches, is invalid, or
	 *                                  matching exceeds existing router limits
	 * @throws NullPointerException if an argument or an examined element is null
	 */
	public static void requireNoTemplateCollisions(
			@NonNull List<@NonNull String> templates,
			@NonNull Collection<@NonNull URI> skillFileUris) {
		requireNonNull(templates, "MCP resource templates are required.");
		requireNonNull(skillFileUris, "Skills file URIs are required.");
		try {
			List<McpLevelOneUriTemplate> compiled = new ArrayList<>();
			for (String template : templates) {
				// Bound actual iteration, without trusting an application collection's
				// reported size or preallocating from it.
				McpLevelOneUriTemplate.requireResourceTemplateCount(compiled.size() + 1);
				compiled.add(McpLevelOneUriTemplate.parse(requireNonNull(template,
						"An MCP resource template is required.")));
			}
			if (compiled.isEmpty())
				return;

			for (URI uri : skillFileUris) {
				McpLevelOneUriTemplate.NormalizedResourceUri normalized =
						McpLevelOneUriTemplate.normalizeResourceUriForTemplateMatching(
								requireNonNull(uri, "A Skills file URI is required.").toString());
				List<McpLevelOneUriTemplate> candidates = new ArrayList<>();
				long dynamicProgrammingCells = 0L;
				for (McpLevelOneUriTemplate template : compiled) {
					if (!template.couldMatch(normalized))
						continue;
					dynamicProgrammingCells += template.dynamicProgrammingCellCount(normalized);
					McpLevelOneUriTemplate.requireTemplateMatchDynamicProgrammingCellBudget(
							dynamicProgrammingCells);
					candidates.add(template);
				}
				if (candidates.isEmpty())
					continue;
				McpLevelOneUriTemplate.PreparedResourceUri prepared =
						McpLevelOneUriTemplate.prepareResourceUriForTemplateMatching(normalized);
				for (McpLevelOneUriTemplate template : candidates)
					if (template.match(prepared).isPresent())
						throw invalid();
			}
		} catch (IllegalArgumentException ignored) {
			// Parser/matcher diagnostics can contain configured identifiers. Do not
			// retain the original cause or expose URI/template content here.
			throw invalid();
		}
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException(
				"Skills file URIs conflict with resource templates or cannot be safely validated.");
	}
}
