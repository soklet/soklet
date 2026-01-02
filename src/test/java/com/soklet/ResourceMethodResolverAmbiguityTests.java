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
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ResourceMethodResolverAmbiguityTests {
	@Test
	public void ambiguousRoutesFailFast() {
		IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
			ResourceMethodResolver.withClasses(Set.of(AmbiguousRouteResource.class));
		});

		assertTrue(exception.getMessage().toLowerCase().contains("ambiguous"),
				"Expected ambiguous route error message");
	}
}

class AmbiguousRouteResource {
	@GET("/items/{id}")
	public String getById(@PathParameter String id) {
		return id;
	}

	@GET("/items/{name}")
	public String getByName(@PathParameter String name) {
		return name;
	}
}
