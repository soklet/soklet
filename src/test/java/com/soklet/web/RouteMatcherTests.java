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

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import com.soklet.web.annotation.GET;
import com.soklet.web.routing.DefaultRouteMatcher;
import com.soklet.web.routing.Route;
import com.soklet.web.routing.RouteMatcher;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class RouteMatcherTests {
  @Test
  public void testRoutes() throws NoSuchMethodException, SecurityException {
    Method simpleMethod = ExampleResource.class.getMethod("simple");
    Method singlePlaceholderMethod = ExampleResource.class.getMethod("singlePlaceholder");
    Method doublePlaceholderMethod = ExampleResource.class.getMethod("doublePlaceholder");
    Method doublePlaceholderLiteralMethod = ExampleResource.class.getMethod("doublePlaceholderLiteral");
    
    Set<Method> resourceMethods = new HashSet<>();
    resourceMethods.add(simpleMethod);
    resourceMethods.add(singlePlaceholderMethod);
    resourceMethods.add(doublePlaceholderMethod);
    resourceMethods.add(doublePlaceholderLiteralMethod);
    RouteMatcher routeMatcher = createRouteMatcher(resourceMethods);

    Optional<Route> route = routeMatcher.match(HttpMethod.GET, "/simple");
    assertTrue(route.isPresent());
    assertTrue(route.get().resourceMethod().equals(simpleMethod));

    route = routeMatcher.match(HttpMethod.GET, "/single-placeholder/123");
    assertTrue(route.isPresent());
    assertTrue(route.get().resourceMethod().equals(singlePlaceholderMethod));

    route = routeMatcher.match(HttpMethod.GET, "/double-placeholder/123/something/456");
    assertTrue(route.isPresent());
    assertTrue(route.get().resourceMethod().equals(doublePlaceholderMethod));
    
    route = routeMatcher.match(HttpMethod.GET, "/double-placeholder/123/something/literal");
    assertTrue(route.isPresent());
    assertTrue(route.get().resourceMethod().equals(doublePlaceholderLiteralMethod));    
  }

  protected RouteMatcher createRouteMatcher(Set<Method> resourceMethods) {
    return new DefaultRouteMatcher(resourceMethods);
  }

  protected static class ExampleResource {
    @GET("/simple")
    public void simple() {}

    @GET("/single-placeholder/{test}")
    public void singlePlaceholder() {}
    
    @GET("/double-placeholder/{test}/something/literal")
    public void doublePlaceholderLiteral() {}

    @GET("/double-placeholder/{test}/something/{another}")
    public void doublePlaceholder() {}
  }
}