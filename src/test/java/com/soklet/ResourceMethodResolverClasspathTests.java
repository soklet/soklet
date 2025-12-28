/*
 * Copyright 2022-2026 Revetware LLC.
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

import com.soklet.annotation.GET;
import com.soklet.annotation.PathParameter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ResourceMethodResolverClasspathTests {
	@Test
	public void resolvesPrimitiveParameterTypesFromLookupTable() {
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasspathIntrospection();

		Request request = Request.withPath(HttpMethod.GET, "/primitive/123").build();
		ResourceMethod resourceMethod = resolver.resourceMethodForRequest(request, ServerType.STANDARD_HTTP).orElse(null);

		Assertions.assertNotNull(resourceMethod, "Expected classpath resource method lookup to resolve /primitive/{id}");
		Assertions.assertEquals("getPrimitive", resourceMethod.getMethod().getName());
		Assertions.assertEquals(int.class, resourceMethod.getMethod().getParameterTypes()[0]);
	}
}

class ClasspathPrimitiveResource {
	@GET("/primitive/{id}")
	public String getPrimitive(@PathParameter(name = "id") int id) {
		return Integer.toString(id);
	}
}
