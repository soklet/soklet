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
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class ResourceMethodResolverIndexTests {
	@Test
	public void resolvesIndexedLiteralPlaceholderAndVarargsRoutes() {
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasses(Set.of(IndexedRouteResource.class));

		assertRoute(resolver, Request.withPath(HttpMethod.GET, "/indexed/literal").build(),
				ServerType.STANDARD_HTTP, "literal");
		assertRoute(resolver, Request.withPath(HttpMethod.GET, "/indexed/123").build(),
				ServerType.STANDARD_HTTP, "placeholder");
		assertRoute(resolver, Request.withPath(HttpMethod.GET, "/indexed/files/js/app.js").build(),
				ServerType.STANDARD_HTTP, "varargs");
		assertRoute(resolver, Request.withPath(HttpMethod.GET, "/assets").build(),
				ServerType.STANDARD_HTTP, "assetsVarargs");
	}

	@Test
	public void routeIndexSeparatesHttpAndSseRoutesWithSameMethodAndPath() {
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasses(Set.of(IndexedRouteResource.class));

		assertRoute(resolver, Request.withPath(HttpMethod.GET, "/events/123").build(),
				ServerType.STANDARD_HTTP, "httpEvent");
		assertRoute(resolver, Request.withPath(HttpMethod.GET, "/events/123").build(),
				ServerType.SSE, "sseEvent");
	}

	private static void assertRoute(ResourceMethodResolver resolver,
																	Request request,
																	ServerType serverType,
																	String methodName) {
		ResourceMethod resourceMethod = resolver.resourceMethodForRequest(request, serverType).orElseThrow();
		assertEquals(methodName, resourceMethod.getMethod().getName());
		assertSame(resourceMethod, resolver.getResourceMethods().stream()
				.filter(candidate -> candidate.equals(resourceMethod))
				.findFirst()
				.orElseThrow());
	}

	@ThreadSafe
	public static class IndexedRouteResource {
		@GET("/indexed/literal")
		public String literal() {
			return "literal";
		}

		@GET("/indexed/{id}")
		public String placeholder() {
			return "placeholder";
		}

		@GET("/indexed/files/{path*}")
		public String varargs() {
			return "varargs";
		}

		@GET("/assets/{path*}")
		public String assetsVarargs() {
			return "assets";
		}

		@GET("/events/{id}")
		public String httpEvent() {
			return "http";
		}

		@SseEventSource("/events/{id}")
		public SseHandshakeResult sseEvent() {
			return SseHandshakeResult.accept();
		}
	}
}
