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

import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import static java.util.Objects.requireNonNull;

/**
 * Private end-to-end qualification entry point. This reads supplied bytes only:
 * it neither opens a file nor publishes a Skills registration. Callers provide
 * all parser/output limits; public bundle APIs remain separate.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class SkillFrontmatter {
	private final SkillSource source;
	private final McpJsonObject metadata;
	private final int bodyByteOffset;

	private SkillFrontmatter(SkillSource source, McpJsonObject metadata, int bodyByteOffset) {
		this.source = source;
		this.metadata = metadata;
		this.bodyByteOffset = bodyByteOffset;
	}

	static SkillFrontmatter parse(byte[] bytes, SkillYamlLimits limits, McpJsonLimits jsonLimits) {
		requireNonNull(bytes); requireNonNull(limits); requireNonNull(jsonLimits);
		if (bytes.length > limits.maximumInputBytes())
			throw new SkillYamlException(SkillYamlException.Reason.INPUT_LIMIT, 1, 1);
		SkillYamlBudget budget = new SkillYamlBudget(limits);
		// Reserve linear snapshot, UTF-8 decoding and delimiter-scan work before
		// allocating input copies. This is a work bound, not a heap-size estimate.
		budget.work(3L * bytes.length, new SkillYamlNode.Position(1, 1));
		SkillSource source = SkillSource.fromBytes(bytes, limits.maximumInputBytes());
		SkillSource.Frontmatter frontmatter = source.frontmatter();
		SkillYamlNode syntax = SkillYamlParser.parse(frontmatter.text(), budget, frontmatter.firstLine());
		McpJsonValue value = new SkillYamlResolver(budget, jsonLimits).resolve(syntax);
		if (!(value instanceof McpJsonObject metadata))
			throw new SkillYamlException(SkillYamlException.Reason.TYPE, syntax.position().line(), syntax.position().column());
		return new SkillFrontmatter(source, metadata, frontmatter.bodyByteOffset());
	}

	SkillSource source() { return this.source; }
	McpJsonObject metadata() { return this.metadata; }
	int bodyByteOffset() { return this.bodyByteOffset; }
	@Override public String toString() { return "SkillFrontmatter[redacted]"; }
}
