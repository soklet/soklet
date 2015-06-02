/*
 * Copyright 2015 Transmogrify LLC.
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

package com.soklet.web;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class ResourcePath {
  /**
   * Pattern which matches a placeholder in a path component.
   * <p>
   * Placeholders are bracked-enclosed segments of text, for example {@code &#123;languageId&#125;}
   * <p>
   * A path component is either literal text or a placeholder. There is no concept of multiple placeholders in a
   * component.
   */
  private static final Pattern COMPONENT_PLACEHOLDER_PATTERN = Pattern.compile("^\\{.+\\}$");

  private final String path;
  private final List<Component> components;

  protected ResourcePath(String path, ComponentParsingStrategy strategy) {
    requireNonNull(strategy);
    this.path = normalizePath(requireNonNull(path));
    this.components = unmodifiableList(extractComponents(this.path, strategy));
  }

  public static ResourcePath fromPathDeclaration(String path) {
    requireNonNull(path);
    return new ResourcePath(path, ComponentParsingStrategy.FROM_DECLARATION);
  }

  public static ResourcePath fromPathInstance(String path) {
    requireNonNull(path);
    return new ResourcePath(path, ComponentParsingStrategy.FROM_INSTANCE);
  }

  public boolean matches(ResourcePath resourcePath) {
    requireNonNull(resourcePath);

    if (resourcePath.components().size() != components().size())
      return false;

    for (int i = 0; i < resourcePath.components().size(); ++i) {
      Component component1 = resourcePath.components().get(i);
      Component component2 = components().get(i);

      if (component1.type() == ComponentType.PLACEHOLDER || component2.type() == ComponentType.PLACEHOLDER)
        continue;

      if (!component1.value().equals(component2.value()))
        return false;
    }

    return true;
  }

  public Map<String, String> placeholders(ResourcePath resourcePath) {
    requireNonNull(resourcePath);

    if (!matches(resourcePath))
      throw new IllegalArgumentException(format("%s is not a match for %s so we cannot extract placeholders", this,
        resourcePath));

    Map<String, String> placeholders = new HashMap<>(resourcePath.components().size());

    for (int i = 0; i < resourcePath.components().size(); ++i) {
      Component component1 = resourcePath.components().get(i);
      Component component2 = components().get(i);

      if (component1.type() == ComponentType.PLACEHOLDER && component2.type() == ComponentType.LITERAL)
        placeholders.put(component1.value(), component2.value());
      else if (component1.type() == ComponentType.LITERAL && component2.type() == ComponentType.PLACEHOLDER)
        placeholders.put(component2.value(), component1.value());
    }

    return placeholders;
  }

  public String path() {
    return this.path;
  }

  public List<Component> components() {
    return this.components;
  }
  
  public boolean isLiteral() {
    for(Component component : components)
      if(component.type() != ComponentType.LITERAL)
        return false;
    
    return true;
  }

  protected String normalizePath(String path) {
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
   * @param path
   *          Path from which components are extracted
   * @param strategy
   *          How to perform the extraction (literal or look for placeholders)
   * @return Logical components of the supplied {@code path}
   */
  protected List<Component> extractComponents(String path, ComponentParsingStrategy strategy) {
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
    return format("%s{path=%s, components=%s}", getClass().getSimpleName(), path(), components());
  }

  @Override
  public boolean equals(Object object) {
    if (this == object)
      return true;

    if (!(object instanceof ResourcePath))
      return false;

    ResourcePath resourcePath = (ResourcePath) object;

    return Objects.equals(path(), resourcePath.path()) && Objects.equals(components(), resourcePath.components());
  }

  @Override
  public int hashCode() {
    return Objects.hash(path(), components());
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
   * @author <a href="http://revetkn.com">Mark Allen</a>
   * @see ResourcePath
   * @since 1.0.0
   */
  public static class Component {
    private final String value;
    private final ComponentType type;

    public Component(String value, ComponentType type) {
      this.value = requireNonNull(value);
      this.type = requireNonNull(type);
    }

    @Override
    public String toString() {
      return format("%s{value=%s, type=%s}", getClass().getSimpleName(), value(), type());
    }

    @Override
    public boolean equals(Object object) {
      if (this == object)
        return true;

      if (!(object instanceof Component))
        return false;

      Component component = (Component) object;

      return Objects.equals(value(), component.value()) && Objects.equals(type(), component.type());
    }

    @Override
    public int hashCode() {
      return Objects.hash(value(), type());
    }

    public String value() {
      return value;
    }

    public ComponentType type() {
      return type;
    }
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
   * @author <a href="http://revetkn.com">Mark Allen</a>
   * @see ResourcePath
   * @since 1.0.0
   */
  public static enum ComponentType {
    LITERAL, PLACEHOLDER
  }

  /**
   * Parsing modes for {@link ResourcePath}s.
   * <p>
   * We parse path declarations (which might include placeholders) differently from the path a user might type in a web
   * browser.
   * 
   * @author <a href="http://revetkn.com">Mark Allen</a>
   * @see ResourcePath
   * @since 1.0.0
   */
  private static enum ComponentParsingStrategy {
    /** For parsing annotation-specified declarations, e.g. <code>@GET("/languages/&#123;languageId&#125;")</code> */
    FROM_DECLARATION,
    /** For parsing end-user-specified URLs, e.g. {@code /languages/en} */
    FROM_INSTANCE
  }
}