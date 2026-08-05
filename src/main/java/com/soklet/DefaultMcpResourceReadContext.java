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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ResourceInvocation;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Package-private immutable resource-read context.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpResourceReadContext implements McpResourceReadContext {
	@NonNull
	private final URI uri;
	@NonNull
	private final Map<@NonNull String, @NonNull String> uriTemplateVariables;

	DefaultMcpResourceReadContext(@NonNull ResourceInvocation invocation) {
		requireNonNull(invocation);
		this.uri = URI.create(invocation.uri());
		this.uriTemplateVariables = Map.copyOf(invocation.templateVariables());
	}

	@Override
	@NonNull
	public URI getUri() {
		return this.uri;
	}

	@Override
	@NonNull
	public Map<@NonNull String, @NonNull String> getUriTemplateVariables() {
		return this.uriTemplateVariables;
	}
}
