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

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public record McpJsonObject(@NonNull Map<@NonNull String, @NonNull McpJsonValue> members)
		implements McpJsonValue {
	@NonNull
	private static final McpJsonObject EMPTY = new McpJsonObject(Map.of());

	public McpJsonObject {
		requireNonNull(members);
		Map<@NonNull String, @NonNull McpJsonValue> copiedMembers =
				new LinkedHashMap<>(members.size());

		for (Map.Entry<@NonNull String, @NonNull McpJsonValue> entry : members.entrySet())
			copiedMembers.put(requireNonNull(entry.getKey()), requireNonNull(entry.getValue()));

		members = Collections.unmodifiableMap(copiedMembers);
	}

	@NonNull
	public static McpJsonObject empty() {
		return EMPTY;
	}
}
