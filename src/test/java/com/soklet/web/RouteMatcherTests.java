/*
 * Copyright (c) 2015 Transmogrify LLC.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
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