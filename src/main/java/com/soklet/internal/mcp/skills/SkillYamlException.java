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

import static java.util.Objects.requireNonNull;

/**
 * Fixed diagnostics deliberately exclude authored text, keys, tags and causes.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class SkillYamlException extends IllegalArgumentException {
	private static final long serialVersionUID = 1L;
	enum Reason {
		SYNTAX, UNSUPPORTED_SYNTAX, INVALID_UTF8, INPUT_LIMIT, WORK_LIMIT,
		NODE_LIMIT, DEPTH_LIMIT, SCALAR_LIMIT, TYPE, DUPLICATE_KEY,
		UNSUPPORTED_TAG, UNDEFINED_ALIAS, CYCLIC_ALIAS, NUMBER_LIMIT, OUTPUT_LIMIT
	}
	private final Reason reason;
	private final int line;
	private final int column;

	SkillYamlException(Reason reason, int line, int column) {
		super("Invalid skill frontmatter (" + requireNonNull(reason).name()
				+ " at " + line + ":" + column + ").");
		this.reason = reason;
		this.line = line;
		this.column = column;
	}

	Reason reason() { return this.reason; }
	int line() { return this.line; }
	int column() { return this.column; }
}
