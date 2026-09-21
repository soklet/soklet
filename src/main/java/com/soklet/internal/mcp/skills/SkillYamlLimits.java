/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet.internal.mcp.skills;

/**
 * Explicit per-parse limits; not a catalog-memory policy or public configuration.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
record SkillYamlLimits(int maximumInputBytes, int maximumNestingDepth,
		int maximumNodes, int maximumScalarCharacters,
		long maximumTotalScalarCharacters, long maximumWork) {
	SkillYamlLimits {
		if (maximumInputBytes < 1 || maximumNestingDepth < 1
				|| maximumNestingDepth > 256 || maximumNodes < 1
				|| maximumScalarCharacters < 1 || maximumTotalScalarCharacters < 1
				|| maximumWork < 1)
			throw new IllegalArgumentException("Invalid private YAML limits.");
	}
}
