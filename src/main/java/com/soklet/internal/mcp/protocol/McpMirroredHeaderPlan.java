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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Precompiled, schema-independent instructions for validating tool argument
 * mirrors. Phase 4 registration projects validated Profile 1 schema metadata
 * into this plan exactly once.
 */
public record McpMirroredHeaderPlan(
		List<McpMirroredHeaderDeclaration> declarations) {
	public McpMirroredHeaderPlan {
		List<McpMirroredHeaderDeclaration> sortedDeclarations =
				new ArrayList<>(requireNonNull(declarations));
		for (McpMirroredHeaderDeclaration declaration : sortedDeclarations)
			requireNonNull(declaration);
		sortedDeclarations.sort(Comparator.comparing(
				declaration -> declaration.headerName().toLowerCase(Locale.ROOT)));
		declarations = List.copyOf(sortedDeclarations);
		Map<String, McpMirroredHeaderDeclaration> declarationsByName =
				new LinkedHashMap<>();
		for (McpMirroredHeaderDeclaration declaration : declarations) {
			String normalizedName = declaration.headerName().toLowerCase(Locale.ROOT);
			if (declarationsByName.putIfAbsent(normalizedName, declaration) != null)
				throw new IllegalArgumentException(
						"Mirrored header names must be unique case-insensitively.");
		}
	}

	public static McpMirroredHeaderPlan empty() {
		return new McpMirroredHeaderPlan(List.of());
	}

	@Override
	public String toString() {
		return "McpMirroredHeaderPlan[declarationCount=" + declarations.size() + "]";
	}
}
