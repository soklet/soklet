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
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
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

	@Nonnull
	private final String path;
	@Nonnull
	private final List<Component> components;

	static {
		COMPONENT_PLACEHOLDER_PATTERN = Pattern.compile("^\\{.+\\}$");
	}

	protected ResourcePath(@Nonnull String path,
												 @Nonnull ComponentParsingStrategy strategy) {
		requireNonNull(path);
		requireNonNull(strategy);

		this.path = normalizePath(path);
		this.components = unmodifiableList(extractComponents(this.path, strategy));
	}

	@Nonnull
	public static ResourcePath fromPathDeclaration(@Nonnull String path) {
		requireNonNull(path);
		return new ResourcePath(path, ComponentParsingStrategy.FROM_DECLARATION);
	}

	@Nonnull
	public static ResourcePath fromPathInstance(@Nonnull String path) {
		requireNonNull(path);
		return new ResourcePath(path, ComponentParsingStrategy.FROM_INSTANCE);
	}

	@Nonnull
	public Boolean matches(@Nonnull ResourcePath resourcePath) {
		requireNonNull(resourcePath);

		if (resourcePath.getComponents().size() != getComponents().size())
			return false;

		for (int i = 0; i < resourcePath.getComponents().size(); ++i) {
			Component component1 = resourcePath.getComponents().get(i);
			Component component2 = getComponents().get(i);

			if (component1.getType() == ComponentType.PLACEHOLDER || component2.getType() == ComponentType.PLACEHOLDER)
				continue;

			if (!component1.getValue().equals(component2.getValue()))
				return false;
		}

		return true;
	}

	@Nonnull
	public Map<String, String> placeholders(@Nonnull ResourcePath resourcePath) {
		requireNonNull(resourcePath);

		if (!matches(resourcePath))
			throw new IllegalArgumentException(format("%s is not a match for %s so we cannot extract placeholders", this,
					resourcePath));

		Map<String, String> placeholders = new HashMap<>(resourcePath.getComponents().size());

		for (int i = 0; i < resourcePath.getComponents().size(); ++i) {
			Component component1 = resourcePath.getComponents().get(i);
			Component component2 = getComponents().get(i);

			if (component1.getType() == ComponentType.PLACEHOLDER && component2.getType() == ComponentType.LITERAL)
				placeholders.put(component1.getValue(), component2.getValue());
			else if (component1.getType() == ComponentType.LITERAL && component2.getType() == ComponentType.PLACEHOLDER)
				placeholders.put(component2.getValue(), component1.getValue());
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
	protected String normalizePath(@Nonnull String path) {
		requireNonNull(path);

		path = path.trim();

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
	 * @param path     (nonnull) Path from which components are extracted
	 * @param strategy (nonnull) How to perform the extraction (literal or look for placeholders)
	 * @return Logical components of the supplied {@code path}
	 */
	@Nonnull
	protected List<Component> extractComponents(@Nonnull String path,
																							@Nonnull ComponentParsingStrategy strategy) {
		requireNonNull(path);
		requireNonNull(strategy);

		if ("/".equals(path))
			return emptyList();

		// Strip off leading /
		path = path.substring(1);

		List<String> values = asList(path.split("/"));
		boolean checkForPlaceholder = strategy == ComponentParsingStrategy.FROM_DECLARATION;

		return values.stream().map(value -> {
			if (checkForPlaceholder) {
				ComponentType type = ComponentType.LITERAL;

				if (checkForPlaceholder && COMPONENT_PLACEHOLDER_PATTERN.matcher(value).matches()) {
					type = ComponentType.PLACEHOLDER;
					value = value.substring(1, value.length() - 1);
				}

				return new Component(value, type);
			} else {
				return new Component(value, ComponentType.LITERAL);
			}
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

		if (!(object instanceof ResourcePath))
			return false;

		ResourcePath resourcePath = (ResourcePath) object;

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
	 * @author <a href="https://www.revetware.com">Mark Allen</a>
	 * @see ResourcePath
	 */
	public enum ComponentType {
		LITERAL,
		PLACEHOLDER
	}

	/**
	 * Parsing modes for {@link ResourcePath}s.
	 * <p>
	 * We parse path declarations (which might include placeholders) differently from the path a user might type in a web
	 * browser.
	 *
	 * @author <a href="https://www.revetware.com">Mark Allen</a>
	 * @see ResourcePath
	 */
	private enum ComponentParsingStrategy {
		/**
		 * For parsing annotation-specified declarations, e.g. <code>@GET("/languages/&#123;languageId&#125;")</code>
		 */
		FROM_DECLARATION,
		/**
		 * For parsing end-user-specified URLs, e.g. {@code /languages/en}
		 */
		FROM_INSTANCE
	}

	/**
	 * Represents a {@code /}-delimited part of a {@code ResourcePath}.
	 * <p>
	 * For example, given the path <code>/languages/&#123;languageId&#125;</code>
	 *
	 * <ul>
	 * <li>{@code Component} 0 would have type {@code LITERAL} and value {@code languages}
	 * <li>{@code Component} 1 would have type {@code PLACEHOLDER} and value {@code languageId}
	 * </ul>
	 *
	 * @author <a href="https://www.revetware.com">Mark Allen</a>
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

			if (!(object instanceof Component))
				return false;

			Component component = (Component) object;

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