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

package com.soklet.core;

import com.soklet.HttpMethod;
import com.soklet.LifecycleInterceptor;
import com.soklet.LogEvent;
import com.soklet.Request;
import com.soklet.RequestResult;
import com.soklet.ResourceMethodResolver;
import com.soklet.Response;
import com.soklet.Soklet;
import com.soklet.SokletConfiguration;
import com.soklet.annotation.GET;
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.RequestCookie;
import com.soklet.annotation.RequestHeader;
import com.soklet.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ParameterBindingTests {
	@Test
	public void required_and_optional_query_parameters() {
		SokletConfiguration cfg = SokletConfiguration.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(ParamResource.class)))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			// Missing required param -> 400
			RequestResult r1 = simulator.performRequest(Request.with(HttpMethod.GET, "/param/required").build());
			Assertions.assertEquals(400, r1.getMarshaledResponse().getStatusCode());

			// Present required param -> 200
			RequestResult r2 = simulator.performRequest(Request.with(HttpMethod.GET, "/param/required?id=42").build());
			Assertions.assertEquals(200, r2.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("ok", new String(r2.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			// Optional param present -> 200 with body
			RequestResult r3 = simulator.performRequest(Request.with(HttpMethod.GET, "/param/optional?q=5").build());
			Assertions.assertEquals(200, r3.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("5", new String(r3.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			// Optional param absent -> 204
			RequestResult r4 = simulator.performRequest(Request.with(HttpMethod.GET, "/param/optional").build());
			Assertions.assertEquals(204, r4.getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void headers_cookies_path_and_body_conversions() {
		SokletConfiguration cfg = SokletConfiguration.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(ParamResource.class)))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			// Header case-insensitivity
			RequestResult h = simulator.performRequest(
					Request.with(HttpMethod.GET, "/param/header")
							.headers(Map.of("X-NUM", Set.of("21")))
							.build());
			Assertions.assertEquals(200, h.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("42", new String(h.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			// Cookie binding
			RequestResult c = simulator.performRequest(
					Request.with(HttpMethod.GET, "/param/cookie")
							.headers(Map.of("Cookie", Set.of("session=abc123")))
							.build());
			Assertions.assertEquals(200, c.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("abc123", new String(c.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			// Path parameter conversion
			RequestResult p = simulator.performRequest(Request.with(HttpMethod.GET, "/param/id/123").build());
			Assertions.assertEquals(200, p.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("123", new String(p.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			// Request body conversion to LocalDate
			RequestResult b = simulator.performRequest(
					Request.with(HttpMethod.POST, "/param/body-date")
							.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
							.body("2025-09-21".getBytes(StandardCharsets.UTF_8))
							.build());
			Assertions.assertEquals(200, b.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("2025-09-21", new String(b.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			// Bad body conversion -> 400
			RequestResult b2 = simulator.performRequest(
					Request.with(HttpMethod.POST, "/param/body-int")
							.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
							.body("not-an-int".getBytes(StandardCharsets.UTF_8))
							.build());
			Assertions.assertEquals(400, b2.getMarshaledResponse().getStatusCode());
		});
	}

	@Resource
	public static class ParamResource {
		@GET("/param/required")
		public String required(@QueryParameter Long id) { // required by default
			Objects.requireNonNull(id);
			return "ok";
		}

		@GET("/param/optional")
		public Response optional(@QueryParameter(optional = true) Integer q) {
			if (q != null)
				return Response.withStatusCode(200).body(String.valueOf(q)).build();

			return Response.withStatusCode(204).build();
		}

		@GET("/param/header")
		public String header(@RequestHeader(name = "x-num") Integer num) {
			return String.valueOf(num * 2);
		}

		@GET("/param/cookie")
		public String cookie(@RequestCookie(name = "session") String session) {
			return session;
		}

		@GET("/param/id/{id}")
		public String id(@PathParameter Long id) {
			return String.valueOf(id);
		}

		@POST("/param/body-date")
		public String bodyDate(@RequestBody LocalDate value) {return value.toString();}

		@POST("/param/body-int")
		public void bodyInt(@RequestBody Integer value) { /* should fail before reaching here if invalid */ }
	}
}