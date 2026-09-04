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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Structural equality support for values nested by public MCP content blocks.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpContentValueSupport {
	private McpContentValueSupport() {
	}

	static boolean annotationsEqual(@Nullable McpContentAnnotations first,
			@Nullable McpContentAnnotations second) {
		if (first == second)
			return true;
		if (first == null || second == null)
			return false;
		return first.getAudience().equals(second.getAudience())
				&& first.getPriority().equals(second.getPriority())
				&& first.getLastModified().equals(second.getLastModified());
	}

	static int annotationsHashCode(@Nullable McpContentAnnotations annotations) {
		if (annotations == null)
			return 0;
		return Objects.hash(annotations.getAudience(), annotations.getPriority(),
				annotations.getLastModified());
	}

	static boolean iconListsEqual(
			@NonNull List<@NonNull McpIcon> first,
			@NonNull List<@NonNull McpIcon> second) {
		requireNonNull(first);
		requireNonNull(second);
		if (first.size() != second.size())
			return false;
		for (int index = 0; index < first.size(); ++index) {
			if (!iconsEqual(first.get(index), second.get(index)))
				return false;
		}
		return true;
	}

	static int iconListHashCode(@NonNull List<@NonNull McpIcon> icons) {
		requireNonNull(icons);
		int result = 1;
		for (McpIcon icon : icons)
			result = 31 * result + iconHashCode(icon);
		return result;
	}

	static boolean resourceContentsEqual(@NonNull McpResourceContents first,
			@NonNull McpResourceContents second) {
		requireNonNull(first);
		requireNonNull(second);
		if (first == second)
			return true;
		if (first instanceof McpTextResourceContents firstText
				&& second instanceof McpTextResourceContents secondText) {
			return firstText.getUri().equals(secondText.getUri())
					&& firstText.getText().equals(secondText.getText())
					&& firstText.getMimeType().equals(secondText.getMimeType())
					&& firstText.getMetadata().equals(secondText.getMetadata());
		}
		if (first instanceof McpBlobResourceContents firstBlob
				&& second instanceof McpBlobResourceContents secondBlob) {
			return firstBlob.getUri().equals(secondBlob.getUri())
					&& firstBlob.dataEquals(secondBlob)
					&& firstBlob.getMimeType().equals(secondBlob.getMimeType())
					&& firstBlob.getMetadata().equals(secondBlob.getMetadata());
		}
		return false;
	}

	static int resourceContentsHashCode(@NonNull McpResourceContents contents) {
		requireNonNull(contents);
		if (contents instanceof McpTextResourceContents text) {
			return Objects.hash(McpTextResourceContents.class, text.getUri(),
					text.getText(), text.getMimeType(), text.getMetadata());
		}
		if (contents instanceof McpBlobResourceContents blob) {
			return Objects.hash(McpBlobResourceContents.class, blob.getUri(),
					blob.dataHashCode(), blob.getMimeType(),
					blob.getMetadata());
		}
		throw new IllegalArgumentException(
				"Unsupported MCP resource-content implementation: "
						+ contents.getClass().getName());
	}

	private static boolean iconsEqual(@NonNull McpIcon first,
			@NonNull McpIcon second) {
		if (first == second)
			return true;
		return first.getSource().equals(second.getSource())
				&& first.getMimeType().equals(second.getMimeType())
				&& first.getSizes().equals(second.getSizes())
				&& first.getTheme().equals(second.getTheme());
	}

	private static int iconHashCode(@NonNull McpIcon icon) {
		return Objects.hash(icon.getSource(), icon.getMimeType(), icon.getSizes(),
				icon.getTheme());
	}
}
