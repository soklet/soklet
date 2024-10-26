/*
 * Copyright 2022-2024 Revetware LLC.
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

import com.soklet.core.ResourcePath.Component;
import com.soklet.core.ResourcePath.ComponentType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Objects;

import static com.soklet.core.ResourcePath.normalizePath;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

/**
 * An HTTP URL path associated with an annotated <em>Resource Method</em>, such as {@code "/users/123"}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ResourcePathInstance {
	@Nonnull
	private final String path;
	@Nonnull
	private final List<Component> components;

	/**
	 * Creates an instance that represents a runtime "instance" of a resource path, e.g. {@code /users/123}.
	 * <p>
	 * This is in contrast to {@link ResourcePath}, which represents compile-time path declarations
	 * that may include placeholders, e.g. {@code /users/{userId}}.
	 *
	 * @param path a runtime path that may not include placeholders
	 */
	public ResourcePathInstance(@Nonnull String path) {
		requireNonNull(path);
		this.path = normalizePath(path);
		this.components = unmodifiableList(extractComponents(this.path));
	}

	/**
	 * Does this resource path instance match the given resource path (taking placeholders into account, if present)?
	 * <p>
	 * For example, this resource path instance {@code /users/123} would match the resource path {@code /users/{userId}}.
	 *
	 * @param resourcePath the resource path against which to match
	 * @return {@code true} if the paths match, {@code false} otherwise
	 */
	@Nonnull
	public Boolean matches(@Nonnull ResourcePath resourcePath) {
		requireNonNull(resourcePath);

		if (resourcePath.getComponents().size() != getComponents().size())
			return false;

		for (int i = 0; i < resourcePath.getComponents().size(); ++i) {
			Component resourcePathComponent = resourcePath.getComponents().get(i);
			Component resourcePathInstanceComponent = getComponents().get(i);

			if (resourcePathComponent.getType() == ComponentType.PLACEHOLDER)
				continue;

			if (!resourcePathComponent.getValue().equals(resourcePathInstanceComponent.getValue()))
				return false;
		}

		return true;
	}

	@Nonnull
	public String getPath() {
		return this.path;
	}

	@Nonnull
	public List<Component> getComponents() {
		return this.components;
	}

	/**
	 * Assumes {@code path} is already normalized via {@link ResourcePath#normalizePath(String)}.
	 *
	 * @param path (nonnull) Path from which components are extracted
	 * @return Logical components of the supplied {@code path}
	 */
	@Nonnull
	protected List<Component> extractComponents(@Nonnull String path) {
		requireNonNull(path);

		if ("/".equals(path))
			return emptyList();

		// Strip off leading /
		path = path.substring(1);

		List<String> values = asList(path.split("/"));

		return values.stream().map(value -> {
			return new Component(value, ComponentType.LITERAL);
		}).collect(toList());
	}

	@Override
	public String toString() {
		return format("%s{path=%s, components=%s}", getClass().getSimpleName(), getPath(), getComponents());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof ResourcePathInstance resourcePath))
			return false;

		return Objects.equals(getPath(), resourcePath.getPath()) && Objects.equals(getComponents(), resourcePath.getComponents());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getPath(), getComponents());
	}
}