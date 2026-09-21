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

import static com.soklet.internal.mcp.skills.SkillYamlException.Reason.*;
import static java.util.Objects.requireNonNull;

/**
 * Single-use counters shared by syntax parsing and semantic expansion.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class SkillYamlBudget {
	private final SkillYamlLimits limits;
	private long workRemaining;
	private long textRemaining;
	private int nodesRemaining;

	SkillYamlBudget(SkillYamlLimits limits) {
		this.limits = requireNonNull(limits);
		this.workRemaining = limits.maximumWork();
		this.textRemaining = limits.maximumTotalScalarCharacters();
		this.nodesRemaining = limits.maximumNodes();
	}

	SkillYamlLimits limits() { return this.limits; }
	void work(long amount, SkillYamlNode.Position position) {
		if (amount < 0) throw new IllegalArgumentException("Negative YAML work charge.");
		if (amount > this.workRemaining) fail(WORK_LIMIT, position);
		this.workRemaining -= amount;
	}
	void node(int depth, SkillYamlNode.Position position) {
		work(1, position);
		depth(depth, position);
		if (this.nodesRemaining == 0) fail(NODE_LIMIT, position);
		--this.nodesRemaining;
	}
	void depth(int depth, SkillYamlNode.Position position) {
		if (depth < 1 || depth > this.limits.maximumNestingDepth()) fail(DEPTH_LIMIT, position);
	}
	void text(int amount, SkillYamlNode.Position position) {
		if (amount < 0) throw new IllegalArgumentException("Negative YAML text charge.");
		work(amount, position);
		if (amount > this.textRemaining) fail(SCALAR_LIMIT, position);
		this.textRemaining -= amount;
	}
	void scalarLength(int length, SkillYamlNode.Position position) {
		if (length < 0 || length > this.limits.maximumScalarCharacters()) fail(SCALAR_LIMIT, position);
	}
	private static void fail(SkillYamlException.Reason reason, SkillYamlNode.Position position) {
		throw new SkillYamlException(reason, position.line(), position.column());
	}
}
