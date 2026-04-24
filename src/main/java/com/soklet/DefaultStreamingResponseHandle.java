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

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

final class DefaultStreamingResponseHandle implements StreamingResponseHandle {
	@NonNull
	private final ServerType serverType;
	@NonNull
	private final Request request;
	@Nullable
	private final ResourceMethod resourceMethod;
	@NonNull
	private final MarshaledResponse marshaledResponse;
	@NonNull
	private final Instant establishedAt;

	DefaultStreamingResponseHandle(@NonNull ServerType serverType,
																 @NonNull Request request,
																 @Nullable ResourceMethod resourceMethod,
																 @NonNull MarshaledResponse marshaledResponse,
																 @NonNull Instant establishedAt) {
		this.serverType = requireNonNull(serverType);
		this.request = requireNonNull(request);
		this.resourceMethod = resourceMethod;
		this.marshaledResponse = requireNonNull(marshaledResponse);
		this.establishedAt = requireNonNull(establishedAt);
	}

	@Override
	@NonNull
	public ServerType getServerType() {
		return this.serverType;
	}

	@Override
	@NonNull
	public Request getRequest() {
		return this.request;
	}

	@Override
	@NonNull
	public Optional<ResourceMethod> getResourceMethod() {
		return Optional.ofNullable(this.resourceMethod);
	}

	@Override
	@NonNull
	public MarshaledResponse getMarshaledResponse() {
		return this.marshaledResponse;
	}

	@Override
	@NonNull
	public Instant getEstablishedAt() {
		return this.establishedAt;
	}
}
