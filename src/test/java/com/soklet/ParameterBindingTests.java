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
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.RequestCookie;
import com.soklet.annotation.RequestHeader;
import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ParameterBindingTests {
	@Test
	public void required_and_optional_query_parameters() {
		SokletConfig cfg = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ParamResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			// Missing required param -> 400
			HttpRequestResult r1 = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/param/required").build());
			Assertions.assertEquals(400, r1.getMarshaledResponse().getStatusCode());

			// Present required param -> 200
			HttpRequestResult r2 = simulator.performHttpRequest(Request.withRawUrl(HttpMethod.GET, "/param/required?id=42").build());
			Assertions.assertEquals(200, r2.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("ok", new String(r2.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			// Optional param present -> 200 with body
			HttpRequestResult r3 = simulator.performHttpRequest(Request.withRawUrl(HttpMethod.GET, "/param/optional?q=5").build());
			Assertions.assertEquals(200, r3.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("5", new String(r3.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			// Optional param absent -> 204
			HttpRequestResult r4 = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/param/optional").build());
			Assertions.assertEquals(204, r4.getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void headers_cookies_path_and_body_conversions() {
		SokletConfig cfg = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ParamResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			// Header case-insensitivity
			HttpRequestResult h = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/param/header")
							.headers(Map.of("X-NUM", Set.of("21")))
							.build());
			Assertions.assertEquals(200, h.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("42", new String(h.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			// Cookie binding
			HttpRequestResult c = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/param/cookie")
							.headers(Map.of("Cookie", Set.of("session=abc123")))
							.build());
			Assertions.assertEquals(200, c.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("abc123", new String(c.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			// Path parameter conversion
			HttpRequestResult p = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/param/id/123").build());
			Assertions.assertEquals(200, p.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("123", new String(p.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

				// Request body conversion to LocalDate
				HttpRequestResult b = simulator.performHttpRequest(
					Request.withPath(HttpMethod.POST, "/param/body-date")
							.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
							.body("2025-09-21".getBytes(StandardCharsets.UTF_8))
							.build());
				Assertions.assertEquals(200, b.getMarshaledResponse().getStatusCode());
				Assertions.assertEquals("2025-09-21", new String(b.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

				// Bad body conversion -> 400
				HttpRequestResult b2 = simulator.performHttpRequest(
					Request.withPath(HttpMethod.POST, "/param/body-int")
							.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
							.body("not-an-int".getBytes(StandardCharsets.UTF_8))
							.build());
				Assertions.assertEquals(400, b2.getMarshaledResponse().getStatusCode());
			});
	}

	@Test
	public void object_parameters_are_not_hijacked_by_request_injection() {
		SokletConfig cfg = specialInjectionConfiguration(new SpecialLifecycleObserver("configured-observer"));

		Soklet.runSimulator(cfg, simulator -> {
			HttpRequestResult objectResult = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/param/object-instance").build());
			Assertions.assertEquals(200, objectResult.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(Object.class.getName(), responseBody(objectResult));

			HttpRequestResult queryObjectResult = simulator.performHttpRequest(Request.withRawUrl(HttpMethod.GET, "/param/query-object?q=abc").build());
			Assertions.assertEquals(200, queryObjectResult.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("converted:abc", responseBody(queryObjectResult));

			HttpRequestResult bodyObjectResult = simulator.performHttpRequest(
					Request.withPath(HttpMethod.POST, "/param/body-object")
							.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
							.body("payload".getBytes(StandardCharsets.UTF_8))
							.build());
			Assertions.assertEquals(200, bodyObjectResult.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("converted:payload", responseBody(bodyObjectResult));
		});
	}

	@Test
	public void configured_component_concrete_subtypes_are_injected_when_assignable() {
		SpecialLifecycleObserver lifecycleObserver = new SpecialLifecycleObserver("configured-observer");
		SokletConfig cfg = specialInjectionConfiguration(lifecycleObserver);

		Soklet.runSimulator(cfg, simulator -> {
			HttpRequestResult lifecycleResult = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/param/lifecycle-special").build());
			Assertions.assertEquals(200, lifecycleResult.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("configured-observer", responseBody(lifecycleResult));
		});
	}

	@Test
	public void configured_component_subtype_mismatches_fail_clearly() {
		SpecialLifecycleObserver lifecycleObserver = new SpecialLifecycleObserver("configured-observer");
		SokletConfig cfg = specialInjectionConfiguration(lifecycleObserver);
		Request request = Request.withPath(HttpMethod.GET, "/param/lifecycle-mismatch").build();
		ResourceMethod resourceMethod = cfg.getResourceMethodResolver().resourceMethodForRequest(request, ServerType.STANDARD_HTTP).orElseThrow();

		IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
				() -> cfg.getResourceMethodParameterProvider().parameterValuesForResourceMethod(request, resourceMethod));

		Assertions.assertInstanceOf(IllegalStateException.class, exception.getCause());
		Assertions.assertTrue(exception.getCause().getMessage().contains(IncompatibleLifecycleObserver.class.getName()));
		Assertions.assertTrue(exception.getCause().getMessage().contains(SpecialLifecycleObserver.class.getName()));
		Assertions.assertTrue(exception.getCause().getMessage().contains("not assignable"));
	}

	private static SokletConfig specialInjectionConfiguration(@NonNull LifecycleObserver lifecycleObserver) {
		return SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ParamResource.class, SpecialInjectionResource.class)))
				.valueConverterRegistry(ValueConverterRegistry.fromDefaultsSupplementedBy(Set.of(new StringToObjectValueConverter())))
				.lifecycleObserver(lifecycleObserver)
				.build();
	}

	@NonNull
	private static String responseBody(@NonNull HttpRequestResult httpRequestResult) {
		return new String(httpRequestResult.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8);
	}

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

	public static final class SpecialInjectionResource {
		@GET("/param/object-instance")
		public String objectInstance(Object value) {
			return value.getClass().getName();
		}

		@GET("/param/query-object")
		public String queryObject(@QueryParameter(name = "q") Object value) {
			return value.toString();
		}

		@POST("/param/body-object")
		public String bodyObject(@RequestBody Object value) {
			return value.toString();
		}

		@GET("/param/lifecycle-special")
		public String lifecycleSpecial(SpecialLifecycleObserver lifecycleObserver) {
			return lifecycleObserver.getName();
		}

		@GET("/param/lifecycle-mismatch")
		public String lifecycleMismatch(IncompatibleLifecycleObserver lifecycleObserver) {
			return lifecycleObserver.getName();
		}
	}

	private static final class StringToObjectValueConverter implements ValueConverter<String, Object> {
		@NonNull
		@Override
		public Optional<Object> convert(@Nullable String from) throws ValueConversionException {
			return from == null ? Optional.empty() : Optional.of(new ConvertedObject(from));
		}

		@NonNull
		@Override
		public Type getFromType() {
			return String.class;
		}

		@NonNull
		@Override
		public Type getToType() {
			return Object.class;
		}
	}

	private static final class ConvertedObject {
		@NonNull
		private final String value;

		private ConvertedObject(@NonNull String value) {
			this.value = Objects.requireNonNull(value);
		}

		@NonNull
		@Override
		public String toString() {
			return "converted:" + getValue();
		}

		@NonNull
		private String getValue() {
			return this.value;
		}
	}

	private static final class SpecialLifecycleObserver implements LifecycleObserver {
		@NonNull
		private final String name;

		private SpecialLifecycleObserver(@NonNull String name) {
			this.name = Objects.requireNonNull(name);
		}

		@NonNull
		private String getName() {
			return this.name;
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Quiet test double
		}
	}

	private static final class IncompatibleLifecycleObserver implements LifecycleObserver {
		@NonNull
		private final String name;

		private IncompatibleLifecycleObserver(@NonNull String name) {
			this.name = Objects.requireNonNull(name);
		}

		@NonNull
		private String getName() {
			return this.name;
		}
	}
}
