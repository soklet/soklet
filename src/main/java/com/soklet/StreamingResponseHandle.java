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

import java.time.Instant;
import java.util.Optional;

/**
 * Read-only identity and metadata for a streaming HTTP response.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface StreamingResponseHandle {
	/**
	 * The server type that wrote the stream.
	 *
	 * @return the server type
	 */
	@NonNull
	ServerType getServerType();

	/**
	 * The request associated with the stream.
	 *
	 * @return the request
	 */
	@NonNull
	Request getRequest();

	/**
	 * The resource method associated with the stream, if available.
	 *
	 * @return the resource method, or {@link Optional#empty()} if unavailable
	 */
	@NonNull
	Optional<ResourceMethod> getResourceMethod();

	/**
	 * The streaming response.
	 *
	 * @return the streaming response
	 */
	@NonNull
	MarshaledResponse getMarshaledResponse();

	/**
	 * Returns the moment at which this stream was established.
	 *
	 * @return the stream establishment moment
	 */
	@NonNull
	Instant getEstablishedAt();
}
