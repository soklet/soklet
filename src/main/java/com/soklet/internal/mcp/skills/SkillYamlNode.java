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


import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import java.util.List;
import static java.util.Objects.requireNonNull;

/**
 * Lossless semantic inputs: mapping keys and duplicates survive syntax parsing.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
sealed interface SkillYamlNode {
	@NonNull Position position();
	@NonNull Properties properties();
	enum Style { PLAIN, SINGLE_QUOTED, DOUBLE_QUOTED, LITERAL, FOLDED }
	record Position(int line, int column) {
		public Position {
			if (line < 1 || column < 1)
				throw new IllegalArgumentException("Invalid YAML source position.");
		}
	}
	record Properties(@Nullable String tag, @Nullable String anchor) {
		static final @NonNull Properties EMPTY = new Properties(null, null);
		@Override public @NonNull String toString() { return "Properties[redacted]"; }
	}
	record Scalar(@NonNull String value, @NonNull Style style, @NonNull Properties properties, @NonNull Position position)
			implements SkillYamlNode {
		public Scalar { requireNonNull(value); requireNonNull(style); requireNonNull(properties); requireNonNull(position); }
		@Override public @NonNull String toString() { return "Scalar[style=" + style + ", characters=" + value.length() + "]"; }
	}
	record Sequence(@NonNull List<@NonNull SkillYamlNode> items, @NonNull Properties properties, @NonNull Position position)
			implements SkillYamlNode {
		public Sequence { items = List.copyOf(items); requireNonNull(properties); requireNonNull(position); }
		@Override public @NonNull String toString() { return "Sequence[items=" + items.size() + "]"; }
	}
	record Entry(@NonNull SkillYamlNode key, @NonNull SkillYamlNode value) {
		public Entry { requireNonNull(key); requireNonNull(value); }
		@Override public @NonNull String toString() { return "Entry[redacted]"; }
	}
	record Mapping(@NonNull List<@NonNull Entry> entries, @NonNull Properties properties, @NonNull Position position)
			implements SkillYamlNode {
		public Mapping { entries = List.copyOf(entries); requireNonNull(properties); requireNonNull(position); }
		@Override public @NonNull String toString() { return "Mapping[entries=" + entries.size() + "]"; }
	}
	record Alias(@NonNull String name, @NonNull Position position) implements SkillYamlNode {
		public Alias { requireNonNull(name); requireNonNull(position); }
		@Override public @NonNull Properties properties() { return Properties.EMPTY; }
		@Override public @NonNull String toString() { return "Alias[redacted]"; }
	}
}
