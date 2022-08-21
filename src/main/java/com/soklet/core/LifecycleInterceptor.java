/*
 * Copyright 2022 Revetware LLC.
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

package com.soklet.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
public interface LifecycleInterceptor {
	default void willStartServer(@Nonnull Server server) {
		// No-op by default
	}

	default void didStartServer(@Nonnull Server server) {
		// No-op by default
	}

	default void willStopServer(@Nonnull Server server) {
		// No-op by default
	}

	default void didStopServer(@Nonnull Server server) {
		// No-op by default
	}

	default void didStartRequestHandling(@Nonnull Request request,
																			 @Nullable ResourceMethod resourceMethod) {
		// No-op by default
	}

	default void didFinishRequestHandling(@Nonnull Request request,
																				@Nullable ResourceMethod resourceMethod,
																				@Nonnull MarshaledResponse marshaledResponse,
																				@Nonnull Duration processingDuration,
																				@Nonnull List<Throwable> throwables) {
		// No-op by default
	}

	default void willStartResponseWriting(@Nonnull Request request,
																				@Nullable ResourceMethod resourceMethod,
																				@Nonnull MarshaledResponse marshaledResponse) {
		// No-op by default
	}

	default void didFinishResponseWriting(@Nonnull Request request,
																				@Nullable ResourceMethod resourceMethod,
																				@Nonnull MarshaledResponse marshaledResponse,
																				@Nonnull Duration responseWriteDuration,
																				@Nullable Throwable throwable) {
		// No-op by default
	}

	default void interceptRequest(@Nonnull Request request,
																@Nullable ResourceMethod resourceMethod,
																@Nonnull Function<Request, MarshaledResponse> requestHandler,
																@Nonnull Consumer<MarshaledResponse> responseHandler) {
		requireNonNull(request);
		requireNonNull(requestHandler);
		requireNonNull(responseHandler);

		MarshaledResponse marshaledResponse = requestHandler.apply(request);
		responseHandler.accept(marshaledResponse);
	}
}