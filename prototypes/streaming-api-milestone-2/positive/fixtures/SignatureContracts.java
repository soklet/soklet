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

package fixtures;

import com.soklet.CallbackRegistration;
import com.soklet.CancelationToken;
import com.soklet.SseClientInitializer;
import com.soklet.SseHandshakeResult;
import com.soklet.SseUnicaster;
import com.soklet.StreamResourceFactory;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamingResponseCanceledException;
import org.jspecify.annotations.NonNull;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Compile-only checks for preserved defaults, checked callbacks, and shared factory types. */
public final class SignatureContracts {

	private SignatureContracts() {}

	/** A completed token implements only the four abstract members and inherits throwIfCanceled. */
	@NonNull
	public static CancelationToken completedToken() {
		return new CancelationToken() {
			@NonNull
			@Override
			public Boolean isCanceled() {
				return false;
			}

			@NonNull
			@Override
			public Optional<@NonNull StreamTerminationReason> getCancelationReason() {
				return Optional.empty();
			}

			@NonNull
			@Override
			public Optional<@NonNull Throwable> getCancelationCause() {
				return Optional.empty();
			}

			@NonNull
			@Override
			public CallbackRegistration onCancel(@NonNull Runnable callback) {
				requireNonNull(callback);
				return () -> {};
			}
		};
	}

	public static void inheritedCancelationCheck() throws StreamingResponseCanceledException {
		completedToken().throwIfCanceled();
	}

	/** No throws clause: neither registration's close introduces checked cleanup. */
	public static void registrationTryWithResources(@NonNull CancelationToken cancelationToken,
			@NonNull SseUnicaster sseUnicaster) {
		try (CallbackRegistration cancelationRegistration = cancelationToken.onCancel(() -> {});
				CallbackRegistration terminationRegistration = sseUnicaster.onTermination(streamTermination -> {})) {
			// Registration removal is permitted without exception wrapping.
		}
	}

	/** The referenced initializer declares checked exceptions rather than wrapping them. */
	public static SseHandshakeResult.@NonNull Accepted checkedSseMethodReference(
			@NonNull Providers providers, @NonNull String topic) {
		SubscriptionInitializer subscriptionInitializer = new SubscriptionInitializer(providers, topic);
		return SseHandshakeResult.Accepted.builder()
				.clientInitializer(subscriptionInitializer::initialize)
				.build();
	}

	/** Null clears the sole initializer overload, and its getter preserves the checked type. */
	public static SseHandshakeResult.@NonNull Accepted initializerRoundTrip(
			SseHandshakeResult.@NonNull Accepted acceptedHandshake) {
		Optional<SseClientInitializer> sseClientInitializer = acceptedHandshake.getClientInitializer();
		return SseHandshakeResult.Accepted.builder()
				.clientInitializer(null)
				.clientInitializer(sseClientInitializer.orElse(null))
				.build();
	}

	/** Declaring checked factories does not make descriptor construction throw. */
	@NonNull
	public static List<@NonNull StreamingResponseBody> checkedIoFactoryVariables(
			@NonNull StreamResourceFactory<? extends @NonNull InputStream> inputStreamFactory,
			@NonNull StreamResourceFactory<? extends @NonNull Reader> readerFactory) {
		return List.of(
				StreamingResponseBody.fromInputStream(inputStreamFactory),
				StreamingResponseBody.withInputStream(inputStreamFactory).build(),
				StreamingResponseBody.fromReader(readerFactory, StandardCharsets.UTF_8),
				StreamingResponseBody.withReader(readerFactory, StandardCharsets.UTF_8).build());
	}

	private static final class SubscriptionInitializer {
		@NonNull
		private final Providers providers;
		@NonNull
		private final String topic;

		private SubscriptionInitializer(@NonNull Providers providers, @NonNull String topic) {
			this.providers = requireNonNull(providers);
			this.topic = requireNonNull(topic);
		}

		private void initialize(@NonNull SseUnicaster sseUnicaster) throws Exception {
			StreamResourceFactory<Providers.Subscription> subscriptionFactory =
					() -> this.providers.subscribe(this.topic, sseUnicaster::unicastEvent);
			sseUnicaster.open(subscriptionFactory);
		}
	}
}
