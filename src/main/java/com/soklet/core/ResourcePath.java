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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static com.soklet.core.Utilities.trimAggressively;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

/**
 * An HTTP URL path associated with an annotated <em>Resource Method</em>, such as {@code @POST("/users")}.
 * <p>
 * {@link ResourcePath} instances must start with the {@code /} character and may contain placeholders denoted by single-mustache syntax.
 * For example, the {@link ResourcePath} {@code /users/{userId}} has a placeholder named {@code userId}.
 * <p>
 * <strong>Please note the following restrictions on {@link ResourcePath} structure:</strong>
 * <p>
 * 1. It is not legal to use the same placeholder name more than once in a {@link ResourcePath}.
 * <p>
 * For example:
 * <ul>
 *  <li>{@code /users/{userId}} is valid resource path</li>
 *  <li>{@code /users/{userId}/roles/{roleId}} is valid resource path</li>
 *  <li>{@code /users/{userId}/other/{userId}} is an <em>invalid</em> resource path</li>
 * </ul>
 * 2. Placeholders must span the entire {@code /}-delimited path component in which they reside.
 * <p>
 * For example:
 * <ul>
 *   <li>{@code /users/{userId}} is a valid resource path</li>
 *   <li>{@code /users/{userId}/details} is a valid resource path</li>
 *   <li>{@code /users/prefix{userId}} is an <em>invalid</em> resource path</li>
 * </ul>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ResourcePath {
	/**
	 * Pattern which matches a placeholder in a path component.
	 * <p>
	 * Placeholders are bracked-enclosed segments of text, for example {@code &#123;languageId&#125;}
	 * <p>
	 * A path component is either literal text or a placeholder. There is no concept of multiple placeholders in a
	 * component.
	 */
	@Nonnull
	private static final Pattern COMPONENT_PLACEHOLDER_PATTERN;

	static {
		COMPONENT_PLACEHOLDER_PATTERN = Pattern.compile("^\\{.+\\}$");
	}

	@Nonnull
	private final String path;
	@Nonnull
	private final List<Component> components;

	/**
	 * Creates an instance that represents a compile-time path declaration, e.g. {@code /users/{userId}}.
	 *
	 * @param path a compile-time path declaration that may include placeholders
	 */
	public ResourcePath(@Nonnull String path) {
		requireNonNull(path);
		this.path = normalizePath(path);
		this.components = unmodifiableList(extractComponents(this.path));
	}

	/**
	 * Does this resource path match the given resource path instance (taking placeholders into account, if present)?
	 * <p>
	 * For example, {@code /users/{userId}} would match {@code /users/123}.
	 *
	 * @param resourcePathInstance the resource path instance against which to match
	 * @return {@code true} if the paths match, {@code false} otherwise
	 */
	@Nonnull
	public Boolean matches(@Nonnull ResourcePathInstance resourcePathInstance) {
		requireNonNull(resourcePathInstance);

		if (resourcePathInstance.getComponents().size() != getComponents().size())
			return false;

		for (int i = 0; i < resourcePathInstance.getComponents().size(); ++i) {
			Component component1 = resourcePathInstance.getComponents().get(i);
			Component component2 = getComponents().get(i);

			if (component1.getType() == ComponentType.PLACEHOLDER || component2.getType() == ComponentType.PLACEHOLDER)
				continue;

			if (!component1.getValue().equals(component2.getValue()))
				return false;
		}

		return true;
	}

	@Nonnull
	public Map<String, String> extractPlaceholders(@Nonnull ResourcePathInstance resourcePathInstance) {
		requireNonNull(resourcePathInstance);

		if (!matches(resourcePathInstance))
			throw new IllegalArgumentException(format("%s is not a match for %s so we cannot extract placeholders", this,
					resourcePathInstance));

		Map<String, String> placeholders = new HashMap<>(resourcePathInstance.getComponents().size());

		for (int i = 0; i < resourcePathInstance.getComponents().size(); ++i) {
			Component component1 = resourcePathInstance.getComponents().get(i);
			Component component2 = getComponents().get(i);

			if (component1.getType() == ComponentType.PLACEHOLDER && component2.getType() == ComponentType.LITERAL) {
				placeholders.put(component1.getValue(), component2.getValue());
			} else if (component1.getType() == ComponentType.LITERAL && component2.getType() == ComponentType.PLACEHOLDER) {
				String component1Value = component1.getValue();

				if (component1Value != null)
					component1Value = URLDecoder.decode(component1Value, StandardCharsets.UTF_8);

				placeholders.put(component2.getValue(), component1Value);
			}
		}

		return Collections.unmodifiableMap(placeholders);
	}

	@Nonnull
	public String getPath() {
		return this.path;
	}

	@Nonnull
	public List<Component> getComponents() {
		return this.components;
	}

	@Nonnull
	public Boolean isLiteral() {
		for (Component component : components)
			if (component.getType() != ComponentType.LITERAL)
				return false;

		return true;
	}

	@Nonnull
	protected static String normalizePath(@Nonnull String path) {
		requireNonNull(path);

		path = trimAggressively(path);

		if (path.length() == 0)
			return "/";

		// Remove any duplicate slashes, e.g. //test///something -> /test/something
		path = path.replaceAll("(/)\\1+", "$1");

		if (!path.startsWith("/"))
			path = format("/%s", path);

		if ("/".equals(path))
			return path;

		if (path.endsWith("/"))
			path = path.substring(0, path.length() - 1);

		return path;
	}

	/**
	 * Assumes {@code path} is already normalized via {@link #normalizePath(String)}.
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
			ComponentType type = ComponentType.LITERAL;

			if (COMPONENT_PLACEHOLDER_PATTERN.matcher(value).matches()) {
				type = ComponentType.PLACEHOLDER;
				value = value.substring(1, value.length() - 1);
			}

			return new Component(value, type);
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

		if (!(object instanceof ResourcePath resourcePath))
			return false;

		return Objects.equals(getPath(), resourcePath.getPath()) && Objects.equals(getComponents(), resourcePath.getComponents());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getPath(), getComponents());
	}

	/**
	 * How to interpret a {@link Component} of a {@link ResourcePath} - is it literal text or a placeholder?
	 * <p>
	 * For example, given the path declaration <code>/languages/&#123;languageId&#125;</code>
	 *
	 * <ul>
	 * <li>{@code ComponentType} 0 would be {@code LITERAL}
	 * <li>{@code ComponentType} 1 would be {@code PLACEHOLDER}
	 * </ul>
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 * @see ResourcePath
	 */
	public enum ComponentType {
		LITERAL,
		PLACEHOLDER
	}

	/**
	 * Represents a {@code /}-delimited part of a {@link ResourcePath}.
	 * <p>
	 * For example, given the path <code>/languages/&#123;languageId&#125;</code>
	 *
	 * <ul>
	 * <li>{@code Component} 0 would have type {@code LITERAL} and value {@code languages}
	 * <li>{@code Component} 1 would have type {@code PLACEHOLDER} and value {@code languageId}
	 * </ul>
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 * @see ResourcePath
	 */
	@Immutable
	public static class Component {
		@Nonnull
		private final String value;
		@Nonnull
		private final ComponentType type;

		public Component(@Nonnull String value,
										 @Nonnull ComponentType type) {
			requireNonNull(value);
			requireNonNull(type);

			this.value = value;
			this.type = type;
		}

		@Override
		public String toString() {
			return format("%s{value=%s, type=%s}", getClass().getSimpleName(), getValue(), getType());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof Component component))
				return false;

			return Objects.equals(getValue(), component.getValue()) && Objects.equals(getType(), component.getType());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getValue(), getType());
		}

		@Nonnull
		public String getValue() {
			return value;
		}

		@Nonnull
		public ComponentType getType() {
			return type;
		}
	}
}