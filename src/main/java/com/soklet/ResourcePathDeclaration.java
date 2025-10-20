/*
 * Copyright 2022-2025 Revetware LLC.
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.soklet.Utilities.trimAggressively;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

/**
 * A compile-time HTTP URL path declaration associated with an annotated <em>Resource Method</em>, such as {@code /users/{userId}}.
 * <p>
 * You may obtain instances via the {@link #withPath(String)} factory method.
 * <p>
 * <strong>Note: this type is not normally used by Soklet applications unless they support <a href="https://www.soklet.com/docs/server-sent-events">Server-Sent Events</a> or choose to implement a custom {@link ResourceMethodResolver}.</strong>
 * <p>
 * {@link ResourcePathDeclaration} instances must start with the {@code /} character and may contain placeholders denoted by single-mustache syntax.
 * For example, the {@link ResourcePathDeclaration} {@code /users/{userId}} has a placeholder named {@code userId}.
 * <p>
 * A {@link ResourcePathDeclaration} is intended for compile-time <em>Resource Method</em> HTTP URL path declarations.
 * The corresponding runtime type is {@link ResourcePath} and functionality is provided to check if the two "match" via {@link #matches(ResourcePath)}.
 * <p>
 * For example, a {@link ResourcePathDeclaration} {@code /users/{userId}} would match {@link ResourcePath} {@code /users/123}.
 * <p>
 * <strong>Please note the following restrictions on {@link ResourcePathDeclaration} structure:</strong>
 * <p>
 * 1. It is not legal to use the same placeholder name more than once in a {@link ResourcePathDeclaration}.
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
 * <p>
 * In addition to simple placeholders, this version supports a special "varargs" placeholder indicated by a trailing {@code *}
 * in the placeholder name. For example, {@code /static/{filePath*}}. When present, the varargs placeholder must appear only once
 * and as the last component in the path.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ResourcePathDeclaration {
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
	 * Vends an instance that represents a compile-time path declaration, for example {@code /users/{userId}}.
	 *
	 * @param path a compile-time path declaration that may include placeholders
	 */
	@Nonnull
	public static ResourcePathDeclaration withPath(@Nonnull String path) {
		requireNonNull(path);
		return new ResourcePathDeclaration(path);
	}

	private ResourcePathDeclaration(@Nonnull String path) {
		requireNonNull(path);
		this.path = normalizePath(path);

		List<Component> components = extractComponents(this.path);

		// Validate varargs: if any component is VARARGS then it must be the last one and only occur once.
		int varargsCount = 0;

		for (int i = 0; i < components.size(); i++) {
			if (components.get(i).getType() == ComponentType.VARARGS) {
				varargsCount++;

				if (i != components.size() - 1)
					throw new IllegalArgumentException(format("Varargs placeholder must be the last component in the path declaration: %s", path));
			}
		}

		Set<String> pathParameterNames = new LinkedHashSet<String>();

		for (var component : components)
			if (component.getType() == ComponentType.PLACEHOLDER && !pathParameterNames.add(component.getValue()))
				throw new IllegalArgumentException(
						String.format("Duplicate placeholder name '%s' in resource path declaration: %s", component.getValue(), path));

		if (varargsCount > 1)
			throw new IllegalArgumentException(format("Only one varargs placeholder is allowed in the path declaration: %s", path));

		this.components = unmodifiableList(components);
	}

	/**
	 * Gets the {@link ComponentType#VARARGS} component in this declaration, if any.
	 *
	 * @return the {@link ComponentType#VARARGS} component in this declaration, or {@link Optional#empty()} if none exists.
	 */
	@Nonnull
	public Optional<Component> getVarargsComponent() {
		if (getComponents().size() == 0)
			return Optional.empty();

		Component lastComponent = getComponents().get(getComponents().size() - 1);

		if (lastComponent.getType() == ComponentType.VARARGS)
			return Optional.of(lastComponent);

		return Optional.empty();
	}

	/**
	 * Does this resource path declaration match the given resource path (taking placeholders/varargs into account, if present)?
	 * <p>
	 * For example, resource path declaration {@code /users/{userId}} would match {@code /users/123}.
	 *
	 * @param resourcePath the resource path against which to match
	 * @return {@code true} if the paths match, {@code false} otherwise
	 */
	@Nonnull
	public Boolean matches(@Nonnull ResourcePath resourcePath) {
		requireNonNull(resourcePath);

		List<Component> declarationComponents = getComponents();
		List<String> pathComponents = resourcePath.getComponents();

		// If the last declaration component is a varargs placeholder, allow extra path components.
		if (!declarationComponents.isEmpty() && declarationComponents.get(declarationComponents.size() - 1).getType() == ComponentType.VARARGS) {
			if (pathComponents.size() < declarationComponents.size() - 1)
				return false;

			// Check the prefix components
			for (int i = 0; i < declarationComponents.size() - 1; i++) {
				Component comp = declarationComponents.get(i);
				String pathComp = pathComponents.get(i);
				if (comp.getType() == ComponentType.LITERAL && !comp.getValue().equals(pathComp))
					return false;
			}

			return true;
		} else {
			if (pathComponents.size() != declarationComponents.size())
				return false;

			for (int i = 0; i < declarationComponents.size(); i++) {
				Component comp = declarationComponents.get(i);
				String pathComp = pathComponents.get(i);

				if (comp.getType() == ComponentType.LITERAL && !comp.getValue().equals(pathComp))
					return false;
			}

			return true;
		}
	}

	/**
	 * What is the mapping between this resource path declaration's placeholder names to the given resource path's placeholder values?
	 * <p>
	 * For example, placeholder extraction for resource path declaration {@code /users/{userId}} and resource path {@code /users/123}
	 * would result in a value equivalent to {@code Map.of("userId", "123")}.
	 * <p>
	 * Resource path declaration placeholder values are automatically URL-decoded.  For example, placeholder extraction for resource path declaration {@code /users/{userId}}
	 * and resource path {@code /users/ab%20c} would result in a value equivalent to {@code Map.of("userId", "ab c")}.
	 * <p>
	 * Varargs placeholders will combine all remaining path components (joined with @{code /}).
	 *
	 * @param resourcePath runtime version of this resource path declaration, used to provide placeholder values
	 * @return a mapping of placeholder names to values, or the empty map if there were no placeholders
	 * @throws IllegalArgumentException if the provided resource path does not match this resource path declaration, i.e. {@link #matches(ResourcePath)} is {@code false}
	 */
	@Nonnull
	public Map<String, String> extractPlaceholders(@Nonnull ResourcePath resourcePath) {
		requireNonNull(resourcePath);

		if (!matches(resourcePath))
			throw new IllegalArgumentException(format("%s is not a match for %s so we cannot extract placeholders", this, resourcePath));

		Map<String, String> placeholders = new LinkedHashMap<>();
		List<Component> declarationComponents = getComponents();
		List<String> pathComponents = resourcePath.getComponents();

		// If varargs is present as the last component, process accordingly.
		if (!declarationComponents.isEmpty() && declarationComponents.get(declarationComponents.size() - 1).getType() == ComponentType.VARARGS) {
			// Process all but the last component normally.
			for (int i = 0; i < declarationComponents.size() - 1; i++) {
				Component comp = declarationComponents.get(i);

				if (comp.getType() == ComponentType.PLACEHOLDER)
					placeholders.put(comp.getValue(), pathComponents.get(i));
			}

			// For varargs, join all remaining path components.
			String varargsValue = pathComponents.subList(declarationComponents.size() - 1, pathComponents.size())
					.stream().collect(Collectors.joining("/"));
			placeholders.put(declarationComponents.get(declarationComponents.size() - 1).getValue(), varargsValue);
		} else {
			// Normal processing: one-to-one mapping.
			for (int i = 0; i < declarationComponents.size(); i++) {
				Component comp = declarationComponents.get(i);

				if (comp.getType() == ComponentType.PLACEHOLDER)
					placeholders.put(comp.getValue(), pathComponents.get(i));
			}
		}

		return Collections.unmodifiableMap(placeholders);
	}

	/**
	 * What is the string representation of this resource path declaration?
	 *
	 * @return the string representation of this resource path declaration, which must start with {@code /}
	 */
	@Nonnull
	public String getPath() {
		return this.path;
	}

	/**
	 * What are the {@code /}-delimited components of this resource path declaration?
	 *
	 * @return the components, or the empty list if this path is equal to {@code /}
	 */
	@Nonnull
	public List<Component> getComponents() {
		return this.components;
	}

	/**
	 * Is this resource path declaration comprised of all "literal" components (that is, no placeholders)?
	 *
	 * @return {@code true} if this resource path declaration is entirely literal, {@code false} otherwise
	 */
	@Nonnull
	public Boolean isLiteral() {
		for (Component component : components)
			if (component.getType() != ComponentType.LITERAL)
				return false;

		return true;
	}

	@Nonnull
	static String normalizePath(@Nonnull String path) {
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
	 * <p>
	 * If a component is a placeholder, determines whether it is a varargs placeholder (trailing {@code *}).
	 *
	 * @param path path from which components are extracted
	 * @return logical components of the supplied {@code path}
	 */
	@Nonnull
	protected List<Component> extractComponents(@Nonnull String path) {
		requireNonNull(path);

		if ("/".equals(path))
			return emptyList();

		// Strip off leading /
		path = path.substring(1);

		List<String> parts = asList(path.split("/"));

		return parts.stream().map(part -> {
			if (COMPONENT_PLACEHOLDER_PATTERN.matcher(part).matches()) {
				// Remove the enclosing '{' and '}'
				String inner = part.substring(1, part.length() - 1);
				ComponentType type;

				if (inner.endsWith("*")) {
					type = ComponentType.VARARGS;
					inner = inner.substring(0, inner.length() - 1);
				} else {
					type = ComponentType.PLACEHOLDER;
				}

				return Component.with(inner, type);
			} else {
				return Component.with(part, ComponentType.LITERAL);
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
		if (!(object instanceof ResourcePathDeclaration resourcePathDeclaration))
			return false;
		return Objects.equals(getPath(), resourcePathDeclaration.getPath())
				&& Objects.equals(getComponents(), resourcePathDeclaration.getComponents());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getPath(), getComponents());
	}

	/**
	 * How to interpret a {@link Component} of a {@link ResourcePathDeclaration} - is it literal text or a placeholder?
	 * <p>
	 * For example, given the path declaration <code>/languages/&#123;languageId&#125;</code>:
	 * <ul>
	 * <li>{@code ComponentType} at index 0 would be {@code LITERAL}
	 * <li>{@code ComponentType} at index 1 would be {@code PLACEHOLDER}
	 * </ul>
	 * <p>
	 * <strong>Note: this type is not normally used by Soklet applications unless they choose to implement a custom {@link ResourceMethodResolver}.</strong>
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 * @see ResourcePathDeclaration
	 */
	public enum ComponentType {
		/**
		 * A literal component of a resource path declaration.
		 * <p>
		 * For example, given resource path declaration {@code /users/{userId}}, the {@code users} component would be of type {@code LITERAL}.
		 */
		LITERAL,
		/**
		 * A placeholder component (that is, one whose value is provided at runtime) of a resource path declaration.
		 * <p>
		 * For example, given resource path declaration {@code /users/{userId}}, the {@code userId} component would be of type {@code PLACEHOLDER}.
		 */
		PLACEHOLDER,
		/**
		 * A "varargs" placeholder component that may match multiple path segments.
		 * <p>
		 * For example, given resource path declaration {@code /static/{filepath*}}, the {@code filepath*} component would be of type {@code VARARGS}.
		 */
		VARARGS
	}

	/**
	 * Represents a {@code /}-delimited part of a {@link ResourcePathDeclaration}.
	 * <p>
	 * For example, given the path declaration <code>/languages/&#123;languageId&#125;</code>:
	 * <ul>
	 *   <li>{@code Component} 0 would have type {@code LITERAL} and value {@code languages}
	 *   <li>{@code Component} 1 would have type {@code PLACEHOLDER} and value {@code languageId}
	 * </ul>
	 * <p>
	 * You may obtain instances via the {@link #with(String, ComponentType)} factory method.
	 * <p>
	 * <strong>Note: this type is not normally used by Soklet applications unless they choose to implement a custom {@link ResourceMethodResolver}.</strong>
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 * @see ResourcePathDeclaration
	 */
	@Immutable
	public static final class Component {
		@Nonnull
		private final String value;
		@Nonnull
		private final ComponentType type;

		/**
		 * Acquires a {@link Component} instance given a {@code value} and {@code type}.
		 *
		 * @param value the value of this component
		 * @param type  the type of this component (literal or placeholder)
		 * @return a {@link Component} instance
		 */
		@Nonnull
		public static Component with(@Nonnull String value,
																 @Nonnull ComponentType type) {
			requireNonNull(value);
			requireNonNull(type);

			return new Component(value, type);
		}

		private Component(@Nonnull String value,
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

			return Objects.equals(getValue(), component.getValue())
					&& Objects.equals(getType(), component.getType());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getValue(), getType());
		}

		/**
		 * What is the value of this resource path declaration component?
		 * <p>
		 * Note that the value of a {@link ComponentType#PLACEHOLDER} component does not include enclosing braces.
		 * For example, given the path declaration <code>/languages/&#123;languageId&#125;</code>,
		 * the component at index 1 would have value {@code languageId}, not {@code {languageId}}.
		 *
		 * @return the value of this component
		 */
		@Nonnull
		public String getValue() {
			return value;
		}

		/**
		 * What type of resource path declaration component is this?
		 *
		 * @return the type of component, e.g. {@link ComponentType#LITERAL} or {@link ComponentType#PLACEHOLDER}
		 */
		@Nonnull
		public ComponentType getType() {
			return type;
		}
	}
}
