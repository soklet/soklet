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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Temporary copy-on-write overlay that replaces canonical framework-owned
 * strings at precompiled RFC 6901 locations.
 * <p>
 * Untouched subtrees are shared with the canonical document rather than copied,
 * so a catalog with one localized leaf allocates only along that single path.
 * Member order is preserved exactly, because response bytes are golden-tested.
 * <p>
 * Every replacement target must already exist and already be a JSON string. A
 * plan that points anywhere else is a construction-time defect rather than a
 * request-time condition, so it fails loudly and the caller discards the whole
 * candidate.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLocalizationOverlay {
	private McpLocalizationOverlay() {
	}

	/** One precompiled replacement: an RFC 6901 pointer and its new text. */
	record Replacement(@NonNull String targetPointer, @NonNull String text) {
		Replacement {
			requireNonNull(targetPointer, "targetPointer");
			requireNonNull(text, "text");
		}
	}

	/**
	 * Returns a copy of {@code document} with every replacement applied.
	 *
	 * @param document canonical publication document
	 * @param replacements precompiled replacements, applied in order
	 * @return new document, or the identical instance when nothing was replaced
	 * @throws IllegalStateException if any target is absent or is not a string
	 */
	@NonNull
	static McpJsonObject withReplacements(@NonNull McpJsonObject document,
			@NonNull List<@NonNull Replacement> replacements) {
		requireNonNull(document, "document");
		requireNonNull(replacements, "replacements");

		ReplacementTrieNode replacementTrie = new ReplacementTrieNode();

		for (Replacement replacement : replacements) {
			List<String> tokens = parsePointer(replacement.targetPointer());

			// A replacement can only change a string's value, never the shape of
			// the document. Validating against the canonical tree therefore
			// preserves the errors and input order of sequential application while
			// allowing all valid paths to be compiled before rebuilding anything.
			validateTarget(document, tokens, replacement);
			replacementTrie.add(tokens, replacement);
		}

		return replacementTrie.children().isEmpty() ? document
				: (McpJsonObject) overlay(document, replacementTrie);
	}

	private static void validateTarget(@NonNull McpJsonValue document,
			@NonNull List<@NonNull String> tokens,
			@NonNull Replacement replacement) {
		McpJsonValue node = document;

		for (String token : tokens) {
			if (node instanceof McpJsonObject object) {
				node = object.members().get(token);

				if (node == null)
					throw missingTarget(replacement);
			} else if (node instanceof McpJsonArray array) {
				int elementIndex = parseElementIndex(token, replacement);

				if (elementIndex >= array.values().size())
					throw missingTarget(replacement);

				node = array.values().get(elementIndex);
			} else {
				throw missingTarget(replacement);
			}
		}

		if (!(node instanceof McpJsonString))
			throw new IllegalStateException(String.format(
					"Localization target %s is not a JSON string.",
					replacement.targetPointer()));
	}

	@NonNull
	private static McpJsonValue overlay(@NonNull McpJsonValue node,
			@NonNull ReplacementTrieNode replacementTrie) {
		Replacement replacement = replacementTrie.replacement();

		if (replacement != null)
			return new McpJsonString(replacement.text());

		if (node instanceof McpJsonObject object) {
			Map<String, McpJsonValue> members =
					new LinkedHashMap<>(object.members().size());

			for (Map.Entry<String, McpJsonValue> entry
					: object.members().entrySet()) {
				ReplacementTrieNode childTrie =
						replacementTrie.children().get(entry.getKey());
				members.put(entry.getKey(), childTrie == null ? entry.getValue()
						: overlay(entry.getValue(), childTrie));
			}

			return new McpJsonObject(members);
		}

		if (node instanceof McpJsonArray array) {
			List<McpJsonValue> values = new ArrayList<>(array.values());

			for (Map.Entry<String, ReplacementTrieNode> entry
					: replacementTrie.children().entrySet()) {
				int elementIndex = Integer.parseInt(entry.getKey());
				values.set(elementIndex, overlay(values.get(elementIndex),
						entry.getValue()));
			}

			return new McpJsonArray(values);
		}

		throw new IllegalStateException(
				"Localization replacement trie does not match the document.");
	}

	private static int parseElementIndex(@NonNull String token,
			@NonNull Replacement replacement) {
		// RFC 6901 array indices are canonical decimal without leading zeros.
		if (token.isEmpty() || (token.length() > 1 && token.charAt(0) == '0'))
			throw missingTarget(replacement);

		for (int index = 0; index < token.length(); ++index)
			if (token.charAt(index) < '0' || token.charAt(index) > '9')
				throw missingTarget(replacement);

		try {
			return Integer.parseInt(token);
		} catch (NumberFormatException exception) {
			throw missingTarget(replacement);
		}
	}

	@NonNull
	private static IllegalStateException missingTarget(
			@NonNull Replacement replacement) {
		return new IllegalStateException(String.format(
				"Localization target %s does not exist.",
				replacement.targetPointer()));
	}

	@NonNull
	private static List<@NonNull String> parsePointer(@NonNull String pointer) {
		if (pointer.isEmpty() || pointer.charAt(0) != '/')
			throw new IllegalStateException(String.format(
					"Localization target %s is not a member pointer.", pointer));

		List<String> tokens = new ArrayList<>();
		int start = 1;

		for (int index = 1; index <= pointer.length(); ++index) {
			if (index == pointer.length() || pointer.charAt(index) == '/') {
				tokens.add(unescapeToken(pointer.substring(start, index)));
				start = index + 1;
			}
		}

		return List.copyOf(tokens);
	}

	@NonNull
	private static String unescapeToken(@NonNull String token) {
		// RFC 6901 requires ~1 before ~0 so an encoded "~1" survives intact.
		return token.indexOf('~') < 0 ? token
				: token.replace("~1", "/").replace("~0", "~");
	}

	/** Mutable only while a request-local replacement plan is compiled. */
	private static final class ReplacementTrieNode {
		@NonNull
		private final Map<@NonNull String, @NonNull ReplacementTrieNode> children;
		@Nullable
		private Replacement replacement;

		private ReplacementTrieNode() {
			this.children = new LinkedHashMap<>();
		}

		private void add(@NonNull List<@NonNull String> tokens,
				@NonNull Replacement replacement) {
			ReplacementTrieNode node = this;

			for (String token : tokens)
				node = node.children.computeIfAbsent(token,
						ignored -> new ReplacementTrieNode());

			// Repeated pointers retain sequential semantics: the last text wins.
			node.replacement = replacement;
		}

		@NonNull
		private Map<@NonNull String, @NonNull ReplacementTrieNode> children() {
			return children;
		}

		@Nullable
		private Replacement replacement() {
			return replacement;
		}
	}
}
