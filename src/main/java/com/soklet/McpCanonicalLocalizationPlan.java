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
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable construction-time plan for canonical framework-owned MCP text.
 * This package-private model does not enable request localization; it records
 * the exact copy-on-write targets that a later rendering phase may use.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpCanonicalLocalizationPlan {
	@NonNull
	private final List<@NonNull McpLocalizableText> texts;
	@NonNull
	private final List<@NonNull EndpointPlan> endpoints;

	McpCanonicalLocalizationPlan(
			@NonNull List<@NonNull McpLocalizableText> texts,
			@NonNull List<@NonNull EndpointPlan> endpoints) {
		this.texts = List.copyOf(requireNonNull(texts));
		this.endpoints = List.copyOf(requireNonNull(endpoints));
	}

	@NonNull
	List<@NonNull McpLocalizableText> texts() {
		return this.texts;
	}

	@NonNull
	List<@NonNull EndpointPlan> endpoints() {
		return this.endpoints;
	}

	/**
	 * Framework response whose canonical publication object owns slots.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	enum ResponseKind {
		DISCOVERY,
		TOOLS_LIST,
		PROMPTS_LIST,
		RESOURCES_LIST,
		RESOURCE_TEMPLATES_LIST,
		SUBSCRIPTION_TERMINAL
	}

	/**
	 * One endpoint and its nonempty response-local plans.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record EndpointPlan(@NonNull String endpointPath,
			@NonNull List<@NonNull ResponsePlan> responses) {
		EndpointPlan {
			requireNonNull(endpointPath);
			responses = List.copyOf(requireNonNull(responses));
		}

		@NonNull
		Optional<@NonNull ResponsePlan> response(@NonNull ResponseKind kind) {
			requireNonNull(kind);
			return responses.stream()
					.filter(response -> response.kind() == kind)
					.findFirst();
		}
	}

	/**
	 * One bounded framework response and its deterministic callback order.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record ResponsePlan(@NonNull ResponseKind kind,
			@NonNull List<@NonNull Slot> slots) {
		ResponsePlan {
			requireNonNull(kind);
			slots = List.copyOf(requireNonNull(slots));
			if (slots.isEmpty())
				throw new IllegalArgumentException(
						"A canonical MCP localization response plan must not be empty.");
		}
	}

	/**
	 * One canonical string and its RFC 6901 pointer in the response result
	 * object. The pointer is a rendering target, not translation identity.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record Slot(@NonNull McpLocalizableText text,
			@NonNull String targetPointer) {
		Slot {
			requireNonNull(text);
			requireNonNull(targetPointer);
			if (!targetPointer.startsWith("/"))
				throw new IllegalArgumentException(
						"A canonical MCP localization target must be an RFC 6901 pointer.");
		}
	}
}
