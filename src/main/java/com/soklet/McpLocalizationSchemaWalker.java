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

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Schema-aware walk of the closed Soklet MCP Tool Schema Profile. It visits
 * only schema-bearing keywords, so title-like data in default, const, enum,
 * examples, or application extensions is never treated as presentation text.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLocalizationSchemaWalker {
	@NonNull
	private static final List<@NonNull String> MAP_SCHEMA_KEYWORDS =
			List.of("$defs", "properties");
	@NonNull
	private static final List<@NonNull String> SINGLE_SCHEMA_KEYWORDS =
			List.of("additionalProperties", "items", "if", "then", "else");
	@NonNull
	private static final List<@NonNull String> ARRAY_SCHEMA_KEYWORDS =
			List.of("allOf", "anyOf");

	private McpLocalizationSchemaWalker() {
	}

	@NonNull
	static List<@NonNull SchemaText> walk(@NonNull McpJsonObject document) {
		requireNonNull(document);
		List<SchemaText> texts = new ArrayList<>();
		walkSchema(document, "", texts);
		return List.copyOf(texts);
	}

	private static void walkSchema(@NonNull McpJsonValue schema,
			@NonNull String pointer,
			@NonNull List<@NonNull SchemaText> texts) {
		if (!(schema instanceof McpJsonObject object))
			return;

		addAnnotation(object, pointer, "title", texts);
		addAnnotation(object, pointer, "description", texts);

		for (String keyword : MAP_SCHEMA_KEYWORDS) {
			McpJsonValue value = object.getMembers().get(keyword);
			if (!(value instanceof McpJsonObject children))
				continue;
			List<String> names = new ArrayList<>(children.getMembers().keySet());
			Collections.sort(names);
			for (String name : names)
				walkSchema(children.getMembers().get(name),
						childPointer(pointer, keyword, name), texts);
		}

		for (String keyword : SINGLE_SCHEMA_KEYWORDS) {
			McpJsonValue child = object.getMembers().get(keyword);
			if (child != null)
				walkSchema(child, childPointer(pointer, keyword), texts);
		}

		for (String keyword : ARRAY_SCHEMA_KEYWORDS) {
			McpJsonValue value = object.getMembers().get(keyword);
			if (!(value instanceof McpJsonArray children))
				continue;
			for (int index = 0; index < children.getElements().size(); ++index)
				walkSchema(children.getElements().get(index),
						childPointer(pointer, keyword, Integer.toString(index)), texts);
		}
	}

	private static void addAnnotation(@NonNull McpJsonObject schema,
			@NonNull String pointer, @NonNull String keyword,
			@NonNull List<@NonNull SchemaText> texts) {
		McpJsonValue value = schema.getMembers().get(keyword);
		if (value instanceof McpJsonString string && !string.value().isBlank())
			texts.add(new SchemaText(childPointer(pointer, keyword),
					string.value()));
	}

	@NonNull
	static String childPointer(@NonNull String parent,
			@NonNull String... segments) {
		StringBuilder pointer = new StringBuilder(requireNonNull(parent));
		for (String segment : requireNonNull(segments))
			pointer.append('/').append(escape(requireNonNull(segment)));
		return pointer.toString();
	}

	@NonNull
	private static String escape(@NonNull String segment) {
		return segment.replace("~", "~0").replace("/", "~1");
	}

	/**
	 * One schema-relative annotation pointer and its canonical text.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record SchemaText(@NonNull String pointer, @NonNull String text) {
		SchemaText {
			requireNonNull(pointer);
			requireNonNull(text);
		}
	}
}
