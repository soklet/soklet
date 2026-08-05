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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable lookup for optional features associated with one MCP invocation.
 *
 * <p>Features are registered and found by their exact public feature-interface
 * class. Assignable supertypes and subtypes are not searched. Feature types
 * are added without changing handler or context signatures.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpInvocationFeatures {
	/**
	 * Creates an immutable feature lookup, primarily for application tests.
	 *
	 * <p>The map and its entries are defensively copied. Each value must be an
	 * instance of its key.
	 *
	 * @param features exact feature-interface keys and matching values
	 * @return immutable feature lookup
	 * @throws NullPointerException if the map, a key, or a value is null
	 * @throws IllegalArgumentException if a value is not an instance of its key
	 */
	@NonNull
	static McpInvocationFeatures fromFeatures(
			@NonNull Map<@NonNull Class<?>, @NonNull Object> features) {
		requireNonNull(features);
		Map<@NonNull Class<?>, @NonNull Object> copiedFeatures =
				new LinkedHashMap<>();
		for (Map.Entry<@NonNull Class<?>, @NonNull Object> entry
				: features.entrySet()) {
			Class<?> featureType = requireNonNull(entry.getKey());
			Object feature = requireNonNull(entry.getValue());
			if (!featureType.isInstance(feature))
				throw new IllegalArgumentException(
						"Feature value is not an instance of "
								+ featureType.getName() + ".");
			copiedFeatures.put(featureType, feature);
		}
		Map<@NonNull Class<?>, @NonNull Object> immutableFeatures =
				Map.copyOf(copiedFeatures);

		return new McpInvocationFeatures() {
			@Override
			@NonNull
			public <T> Optional<@NonNull T> find(
					@NonNull Class<T> featureType) {
				Class<T> requiredType = requireNonNull(featureType);
				return Optional.ofNullable(requiredType.cast(
						immutableFeatures.get(requiredType)));
			}
		};
	}

	/**
	 * Finds a feature by its exact registered feature-interface class.
	 *
	 * <p>An empty result means that the feature is unknown, does not apply to
	 * this operation, or is unavailable for this request. Repeated lookup of a
	 * present feature returns the same instance for the invocation's lifetime.
	 *
	 * @param featureType exact feature-interface class
	 * @param <T> feature type
	 * @return feature instance, if available
	 * @throws NullPointerException if {@code featureType} is null
	 */
	@NonNull
	<T> Optional<@NonNull T> find(@NonNull Class<T> featureType);

	/**
	 * Requires a feature using the same exact-class lookup as
	 * {@link #find(Class)}.
	 *
	 * @param featureType exact feature-interface class
	 * @param <T> feature type
	 * @return feature instance
	 * @throws NullPointerException if {@code featureType} is null
	 * @throws IllegalStateException if the feature is unavailable
	 */
	@NonNull
	default <T> T require(@NonNull Class<T> featureType) {
		Class<T> requiredFeatureType = requireNonNull(featureType);
		return find(requiredFeatureType).orElseThrow(() ->
				new IllegalStateException(
						"Required MCP invocation feature is unavailable: "
								+ requiredFeatureType.getName()));
	}
}
