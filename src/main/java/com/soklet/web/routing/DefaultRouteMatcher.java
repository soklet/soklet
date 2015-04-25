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

package com.soklet.web.routing;

import static java.lang.String.format;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.soklet.classindex.ClassIndex;
import com.soklet.web.HttpMethod;
import com.soklet.web.ResourcePath;
import com.soklet.web.ResourcePath.Component;
import com.soklet.web.annotation.DELETE;
import com.soklet.web.annotation.GET;
import com.soklet.web.annotation.HEAD;
import com.soklet.web.annotation.OPTIONS;
import com.soklet.web.annotation.PATCH;
import com.soklet.web.annotation.POST;
import com.soklet.web.annotation.PUT;
import com.soklet.web.annotation.Resource;
import com.soklet.web.annotation.repeatable.DELETEs;
import com.soklet.web.annotation.repeatable.GETs;
import com.soklet.web.annotation.repeatable.HEADs;
import com.soklet.web.annotation.repeatable.OPTIONSes;
import com.soklet.web.annotation.repeatable.PATCHes;
import com.soklet.web.annotation.repeatable.POSTs;
import com.soklet.web.annotation.repeatable.PUTs;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class DefaultRouteMatcher implements RouteMatcher {
  private final Set<Method> resourceMethods;
  private final Map<HttpMethod, Set<Method>> resourceMethodsByHttpMethod;
  private final Map<Method, Set<HttpMethodResourcePath>> httpMethodResourcePathsByResourceMethod;

  public DefaultRouteMatcher() {
    this.resourceMethods = unmodifiableSet(new HashSet<>(extractResourceMethods()));
    this.resourceMethodsByHttpMethod =
        unmodifiableMap(new HashMap<>(createResourceMethodsByHttpMethod(resourceMethods())));
    this.httpMethodResourcePathsByResourceMethod =
        unmodifiableMap(new HashMap<>(createHttpMethodResourcePathsByResourceMethod(resourceMethods())));
  }

  public DefaultRouteMatcher(Set<Method> resourceMethods) {
    this.resourceMethods = unmodifiableSet(requireNonNull(resourceMethods));
    this.resourceMethodsByHttpMethod =
        unmodifiableMap(new HashMap<>(createResourceMethodsByHttpMethod(resourceMethods())));
    this.httpMethodResourcePathsByResourceMethod =
        unmodifiableMap(new HashMap<>(createHttpMethodResourcePathsByResourceMethod(resourceMethods())));
  }

  protected Map<Method, Set<HttpMethodResourcePath>> createHttpMethodResourcePathsByResourceMethod(
      Set<Method> resourceMethods) {
    Map<Method, Set<HttpMethodResourcePath>> httpMethodResourcePathsByResourceMethod = new HashMap<>();

    for (Method resourceMethod : resourceMethods) {
      Set<HttpMethodResourcePath> matchedHttpMethodResourcePaths = new HashSet<>();

      for (Annotation annotation : resourceMethod.getAnnotations()) {
        if (annotation instanceof GET) {
          matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.GET, ResourcePath
            .fromPathDeclaration(((GET) annotation).value())));
        } else if (annotation instanceof POST) {
          matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.POST, ResourcePath
            .fromPathDeclaration(((POST) annotation).value())));
        } else if (annotation instanceof PUT) {
          matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.PUT, ResourcePath
            .fromPathDeclaration(((PUT) annotation).value())));
        } else if (annotation instanceof PATCH) {
          matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.PATCH, ResourcePath
            .fromPathDeclaration(((PATCH) annotation).value())));
        } else if (annotation instanceof DELETE) {
          matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.DELETE, ResourcePath
            .fromPathDeclaration(((DELETE) annotation).value())));
        } else if (annotation instanceof OPTIONS) {
          matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.OPTIONS, ResourcePath
            .fromPathDeclaration(((OPTIONS) annotation).value())));
        } else if (annotation instanceof HEAD) {
          matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.HEAD, ResourcePath
            .fromPathDeclaration(((HEAD) annotation).value())));
        } else if (annotation instanceof GETs) {
          for (GET get : ((GETs) annotation).value())
            matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.GET, ResourcePath
              .fromPathDeclaration(get.value())));
        } else if (annotation instanceof POSTs) {
          for (POST post : ((POSTs) annotation).value())
            matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.POST, ResourcePath
              .fromPathDeclaration(post.value())));
        } else if (annotation instanceof PUTs) {
          for (PUT put : ((PUTs) annotation).value())
            matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.PUT, ResourcePath
              .fromPathDeclaration(put.value())));
        } else if (annotation instanceof PATCHes) {
          for (PATCH patch : ((PATCHes) annotation).value())
            matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.PATCH, ResourcePath
              .fromPathDeclaration(patch.value())));
        } else if (annotation instanceof DELETEs) {
          for (DELETE delete : ((DELETEs) annotation).value())
            matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.DELETE, ResourcePath
              .fromPathDeclaration(delete.value())));
        } else if (annotation instanceof OPTIONSes) {
          for (OPTIONS options : ((OPTIONSes) annotation).value())
            matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.OPTIONS, ResourcePath
              .fromPathDeclaration(options.value())));
        } else if (annotation instanceof HEADs) {
          for (HEAD head : ((HEADs) annotation).value())
            matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.HEAD, ResourcePath
              .fromPathDeclaration(head.value())));
        }

        Set<HttpMethodResourcePath> httpMethodResourcePaths =
            httpMethodResourcePathsByResourceMethod.get(resourceMethod);

        if (httpMethodResourcePaths == null) {
          httpMethodResourcePaths = new HashSet<>();
          httpMethodResourcePathsByResourceMethod.put(resourceMethod, httpMethodResourcePaths);
        }

        httpMethodResourcePaths.addAll(matchedHttpMethodResourcePaths);
      }
    }

    return httpMethodResourcePathsByResourceMethod;
  }

  @Override
  public Optional<Route> match(HttpMethod httpMethod, String requestPath) {
    requireNonNull(httpMethod);
    requireNonNull(requestPath);

    Set<Method> resourceMethods = resourceMethodsByHttpMethod().get(httpMethod);

    if (resourceMethods == null)
      return Optional.empty();

    ResourcePath resourcePath = ResourcePath.fromPathInstance(requestPath);

    for (Entry<Method, Set<HttpMethodResourcePath>> entry : httpMethodResourcePathsByResourceMethod().entrySet()) {
      Method method = entry.getKey();
      Set<HttpMethodResourcePath> httpMethodResourcePaths = entry.getValue();

      for (HttpMethodResourcePath HttpMethodResourcePath : httpMethodResourcePaths)
        if (HttpMethodResourcePath.httpMethod().equals(httpMethod)
            && resourcePath.matches(HttpMethodResourcePath.resourcePath()))
          return Optional.of(new Route(HttpMethodResourcePath.httpMethod(), HttpMethodResourcePath.resourcePath(),
            method));
    }

    return Optional.empty();
  }

  protected Map<HttpMethod, Set<Method>> createResourceMethodsByHttpMethod(Set<Method> resourceMethods) {
    requireNonNull(resourceMethods);

    Map<HttpMethod, Set<Method>> resourceMethodsByHttpMethod = new HashMap<>();

    for (Method resourceMethod : resourceMethods) {
      for (Annotation annotation : resourceMethod.getAnnotations()) {
        HttpMethod httpMethod = null;

        if (annotation instanceof GET || annotation instanceof GETs)
          httpMethod = HttpMethod.GET;
        else if (annotation instanceof POST || annotation instanceof POSTs)
          httpMethod = HttpMethod.POST;
        else if (annotation instanceof PUT || annotation instanceof PUTs)
          httpMethod = HttpMethod.PUT;
        else if (annotation instanceof PATCH || annotation instanceof PATCHes)
          httpMethod = HttpMethod.PATCH;
        else if (annotation instanceof DELETE || annotation instanceof DELETEs)
          httpMethod = HttpMethod.DELETE;
        else if (annotation instanceof OPTIONS || annotation instanceof OPTIONSes)
          httpMethod = HttpMethod.OPTIONS;
        else if (annotation instanceof HEAD || annotation instanceof HEADs)
          httpMethod = HttpMethod.HEAD;

        if (httpMethod == null)
          continue;

        Set<Method> httpMethodResourceMethods = resourceMethodsByHttpMethod.get(httpMethod);

        if (httpMethodResourceMethods == null) {
          httpMethodResourceMethods = new HashSet<>();
          resourceMethodsByHttpMethod.put(httpMethod, httpMethodResourceMethods);
        }

        httpMethodResourceMethods.add(resourceMethod);
      }
    }

    return resourceMethodsByHttpMethod;
  }

  protected Set<Method> extractResourceMethods() {
    Set<Method> methods = new HashSet<>();

    // O(n³) complexity but in practice there are not many iterations plus the code is easy to follow :)
    for (Class<?> clazz : ClassIndex.getAnnotated(Resource.class))
      for (Method method : clazz.getMethods())
        for (Annotation annotation : method.getAnnotations())
          if (annotation instanceof GET || annotation instanceof POST || annotation instanceof PUT
              || annotation instanceof PATCH || annotation instanceof DELETE || annotation instanceof OPTIONS
              || annotation instanceof HEAD || annotation instanceof GETs || annotation instanceof POSTs
              || annotation instanceof PUTs || annotation instanceof PATCHes || annotation instanceof DELETEs
              || annotation instanceof OPTIONSes || annotation instanceof HEADs)
            methods.add(method);

    return methods;
  }

  protected Set<Method> resourceMethods() {
    return resourceMethods;
  }

  protected Map<HttpMethod, Set<Method>> resourceMethodsByHttpMethod() {
    return resourceMethodsByHttpMethod;
  }

  protected Map<Method, Set<HttpMethodResourcePath>> httpMethodResourcePathsByResourceMethod() {
    return httpMethodResourcePathsByResourceMethod;
  }

  protected static class HttpMethodResourcePath {
    private final HttpMethod httpMethod;
    private final ResourcePath resourcePath;

    public HttpMethodResourcePath(HttpMethod httpMethod, ResourcePath resourcePath) {
      this.httpMethod = requireNonNull(httpMethod);
      this.resourcePath = requireNonNull(resourcePath);
    }

    @Override
    public String toString() {
      return format("%s{httpMethod=%s, resourcePath=%s}", getClass().getSimpleName(), httpMethod(), resourcePath());
    }

    @Override
    public boolean equals(Object object) {
      if (this == object)
        return true;

      if (!(object instanceof Component))
        return false;

      HttpMethodResourcePath httpMethodResourcePath = (HttpMethodResourcePath) object;

      return Objects.equals(httpMethod(), httpMethodResourcePath.httpMethod())
          && Objects.equals(resourcePath(), httpMethodResourcePath.resourcePath());
    }

    @Override
    public int hashCode() {
      return Objects.hash(httpMethod(), resourcePath());
    }

    public HttpMethod httpMethod() {
      return httpMethod;
    }

    public ResourcePath resourcePath() {
      return resourcePath;
    }
  }
}