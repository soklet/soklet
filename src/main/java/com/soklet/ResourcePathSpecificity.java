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

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Shared ranking and ambiguity key for the processor and default HTTP/SSE resolver. */
record ResourcePathSpecificity(boolean hasVarargs, long placeholderCount, long literalCount,
		@NonNull List<ResourcePathDeclaration.@NonNull ComponentType> varargsComponents)
		implements Comparable<@NonNull ResourcePathSpecificity> {

	ResourcePathSpecificity {
		varargsComponents = List.copyOf(requireNonNull(varargsComponents));
	}

	@NonNull
	static ResourcePathSpecificity from(@NonNull ResourcePathDeclaration declaration) {
		requireNonNull(declaration);
		List<ResourcePathDeclaration.Component> components = declaration.getComponents();
		if (declaration.getVarargsComponent().isPresent()) {
			return new ResourcePathSpecificity(true, 0, 0,
					components.stream().map(ResourcePathDeclaration.Component::getType).toList());
		}
		return new ResourcePathSpecificity(false,
				components.stream().filter(component -> component.getType() == ResourcePathDeclaration.ComponentType.PLACEHOLDER).count(),
				components.stream().filter(component -> component.getType() == ResourcePathDeclaration.ComponentType.LITERAL).count(),
				List.of());
	}

	/** Negative means this declaration is more specific. Literal spellings do not break ambiguity ties. */
	@Override
	public int compareTo(@NonNull ResourcePathSpecificity other) {
		requireNonNull(other);
		int comparison = Boolean.compare(this.hasVarargs, other.hasVarargs);
		if (comparison != 0)
			return comparison;
		if (this.hasVarargs) {
			int commonLength = Math.min(this.varargsComponents.size(), other.varargsComponents.size());
			for (int i = 0; i < commonLength; i++) {
				comparison = Integer.compare(componentRank(this.varargsComponents.get(i)), componentRank(other.varargsComponents.get(i)));
				if (comparison != 0)
					return comparison;
			}
			return Integer.compare(other.varargsComponents.size(), this.varargsComponents.size());
		}
		// Retain fixed-route precedence and ambiguity behavior.
		comparison = Long.compare(this.placeholderCount, other.placeholderCount);
		return comparison != 0 ? comparison : Long.compare(other.literalCount, this.literalCount);
	}

	private static int componentRank(ResourcePathDeclaration.@NonNull ComponentType type) {
		return switch (type) {
			case LITERAL -> 0;
			case PLACEHOLDER -> 1;
			case VARARGS -> 2;
		};
	}
}
