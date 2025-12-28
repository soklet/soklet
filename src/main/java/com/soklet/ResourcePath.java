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

import com.soklet.ResourcePathDeclaration.Component;
import com.soklet.ResourcePathDeclaration.ComponentType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * An HTTP URL path used to resolve a <em>Resource Method</em> at runtime, such as {@code /users/123}.
 * <p>
 * You may obtain instances via the {@link #withPath(String)} factory method.
 * <p>
 * <strong>Note: this type is not normally used by Soklet applications unless they support <a href="https://www.soklet.com/docs/server-sent-events">Server-Sent Events</a> or choose to implement a custom {@link ResourceMethodResolver}.</strong>
 * <p>
 * The corresponding compile-time type for {@link ResourcePath} is {@link ResourcePathDeclaration} and functionality is provided to check if the two "match" via {@link #matches(ResourcePathDeclaration)}.
 * <p>
 * For example, a {@link ResourcePath} {@code /users/123} would match {@link ResourcePathDeclaration} {@code /users/{userId}}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ResourcePath {
	@NonNull
	final static ResourcePath OPTIONS_SPLAT_RESOURCE_PATH;

	static {
		OPTIONS_SPLAT_RESOURCE_PATH = new ResourcePath();
	}

	@NonNull
	private final String path;
	@NonNull
	private final List<String> components;

	/**
	 * Vends an instance that represents a runtime representation of a resource path, for example {@code /users/123}.
	 * <p>
	 * This is in contrast to {@link ResourcePathDeclaration}, which represents compile-time path declarations
	 * that may include placeholders, e.g. {@code /users/{userId}}.
	 *
	 * @param path a runtime path (no placeholders) e.g. {@code /users/123}
	 */
	@NonNull
	public static ResourcePath withPath(@NonNull String path) {
		requireNonNull(path);
		return new ResourcePath(path);
	}

	// Special "options splat" path
	private ResourcePath() {
		this.path = "*";
		this.components = List.of();
	}

	private ResourcePath(@NonNull String path) {
		requireNonNull(path);
		this.path = ResourcePathDeclaration.normalizePath(path);
		this.components = unmodifiableList(extractComponents(this.path));
	}

	/**
	 * Does this resource path match the given resource path (taking placeholders/varargs into account, if present)?
	 * <p>
	 * For example, resource path {@code /users/123} would match the resource path declaration {@code /users/{userId}}.
	 *
	 * @param resourcePathDeclaration the compile-time declaration to match against
	 * @return {@code true} if this resource path matches, {@code false} otherwise
	 */
	@NonNull
	public Boolean matches(@NonNull ResourcePathDeclaration resourcePathDeclaration) {
		requireNonNull(resourcePathDeclaration);

		if (this == OPTIONS_SPLAT_RESOURCE_PATH)
			return false;

		List<Component> declarationComponents = resourcePathDeclaration.getComponents();

		if (!declarationComponents.isEmpty() && declarationComponents.get(declarationComponents.size() - 1).getType() == ComponentType.VARARGS) {
			if (getComponents().size() < declarationComponents.size() - 1)
				return false;

			// Check prefix
			for (int i = 0; i < declarationComponents.size() - 1; i++) {
				Component comp = declarationComponents.get(i);
				String pathComp = getComponents().get(i);

				if (comp.getType() == ComponentType.LITERAL && !comp.getValue().equals(pathComp))
					return false;
			}

			return true;
		} else {
			if (getComponents().size() != declarationComponents.size())
				return false;

			for (int i = 0; i < declarationComponents.size(); i++) {
				Component comp = declarationComponents.get(i);
				String pathComp = getComponents().get(i);

				if (comp.getType() == ComponentType.LITERAL && !comp.getValue().equals(pathComp))
					return false;
			}

			return true;
		}
	}

	/**
	 * What is the mapping between this resource path's placeholder values to the given resource path declaration's placeholder names?
	 * <p>
	 * For example, placeholder extraction for resource path {@code /users/123} and resource path declaration {@code /users/{userId}}
	 * would result in a value equivalent to {@code Map.of("userId", "123")}.
	 * <p>
	 * Resource path placeholder values are automatically URL-decoded.  For example, placeholder extraction for resource path declaration {@code /users/{userId}}
	 * and resource path {@code /users/ab%20c} would result in a value equivalent to {@code Map.of("userId", "ab c")}.
	 * <p>
	 * For varargs placeholders, the extra path components are joined with '/'.
	 *
	 * @param resourcePathDeclaration compile-time resource path, used to provide placeholder names
	 * @return a mapping of placeholder names to values, or the empty map if there were no placeholders
	 * @throws IllegalArgumentException if the provided resource path declaration does not match this resource path, i.e. {@link #matches(ResourcePathDeclaration)} is {@code false}
	 */
	@NonNull
	public Map<String, String> extractPlaceholders(@NonNull ResourcePathDeclaration resourcePathDeclaration) {
		requireNonNull(resourcePathDeclaration);

		if (!matches(resourcePathDeclaration))
			throw new IllegalArgumentException(format("%s is not a match for %s so we cannot extract placeholders", this, resourcePathDeclaration));

		Map<String, String> placeholders = new LinkedHashMap<>();
		List<Component> declarationComponents = resourcePathDeclaration.getComponents();

		if (!declarationComponents.isEmpty() && declarationComponents.get(declarationComponents.size() - 1).getType() == ComponentType.VARARGS) {
			for (int i = 0; i < declarationComponents.size() - 1; i++) {
				Component comp = declarationComponents.get(i);

				if (comp.getType() == ComponentType.PLACEHOLDER)
					placeholders.put(comp.getValue(), getComponents().get(i));
			}

			// Join remaining components for varargs placeholder.
			String varargsValue = String.join("/", getComponents().subList(declarationComponents.size() - 1, getComponents().size()));
			placeholders.put(declarationComponents.get(declarationComponents.size() - 1).getValue(), varargsValue);
		} else {
			for (int i = 0; i < declarationComponents.size(); i++) {
				Component comp = declarationComponents.get(i);

				if (comp.getType() == ComponentType.PLACEHOLDER)
					placeholders.put(comp.getValue(), getComponents().get(i));
			}
		}

		return Collections.unmodifiableMap(placeholders);
	}

	/**
	 * What is the string representation of this resource path?
	 *
	 * @return the string representation of this resource path, which must start with {@code /}
	 */
	@NonNull
	public String getPath() {
		return this.path;
	}

	/**
	 * What are the {@code /}-delimited components of this resource path?
	 *
	 * @return the components, or the empty list if this path is equal to {@code /}
	 */
	@NonNull
	public List<String> getComponents() {
		return this.components;
	}

	/**
	 * Assumes {@code path} is already normalized via {@link ResourcePathDeclaration#normalizePath(String)}.
	 *
	 * @param path (nonnull) Path from which components are extracted
	 * @return Logical components of the supplied {@code path}
	 */
	@NonNull
	protected List<String> extractComponents(@NonNull String path) {
		requireNonNull(path);

		if ("/".equals(path))
			return emptyList();

		// Strip off leading /
		path = path.substring(1);
		return Arrays.asList(path.split("/"));
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

		return Objects.equals(getPath(), resourcePath.getPath());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getPath());
	}
}
