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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.soklet.web.ResourcePath;

/**
 * @author Transmogrify LLC.
 */
public class ResourcePathTests {
  @Test
  public void paths() {
    assertEquals(ResourcePath.fromPathDeclaration("").path(), "/");
    assertEquals(ResourcePath.fromPathDeclaration("   ").path(), "/");
    assertEquals(ResourcePath.fromPathDeclaration("/").path(), "/");
    assertEquals(ResourcePath.fromPathDeclaration("////").path(), "/");
    assertEquals(ResourcePath.fromPathDeclaration("/test//one/").path(), "/test/one");
  }

  @Test
  public void placeholderPaths() {
    assertEquals(ResourcePath.fromPathDeclaration("/one/{two}////{four}").path(), "/one/{two}/{four}");
  }
}