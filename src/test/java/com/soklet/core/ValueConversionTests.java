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

import com.soklet.Soklet;
import com.soklet.SokletConfiguration;
import com.soklet.annotation.GET;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.Resource;
import com.soklet.core.impl.DefaultResourceMethodResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ValueConversionTests {
	enum Flavor {
		VANILLA,
		CHOCOLATE
	}

	@Test
	public void converts_common_types_from_query_params() {
		SokletConfiguration cfg = SokletConfiguration.forTesting()
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(ConversionResource.class)))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			// LocalDate
			RequestResult r1 = simulator.performRequest(new Request.Builder(HttpMethod.GET, "/conv/date?d=2024-09-30").build());
			Assertions.assertEquals(200, r1.getMarshaledResponse().getStatusCode());
			// Enum (case-insensitive support is implementation-defined; assume upper-case)
			RequestResult r2 = simulator.performRequest(new Request.Builder(HttpMethod.GET, "/conv/flavor?f=VANILLA").build());
			Assertions.assertEquals(200, r2.getMarshaledResponse().getStatusCode());
			// UUID
			String id = UUID.randomUUID().toString();
			RequestResult r3 = simulator.performRequest(new Request.Builder(HttpMethod.GET, "/conv/uuid?id=" + id).build());
			Assertions.assertEquals(200, r3.getMarshaledResponse().getStatusCode());
			// List of ints
			RequestResult r4 = simulator.performRequest(new Request.Builder(HttpMethod.GET, "/conv/list?x=1&x=2&x=3").build());
			Assertions.assertEquals(200, r4.getMarshaledResponse().getStatusCode());
			// Locale (BCP 47)
			RequestResult r5 = simulator.performRequest(new Request.Builder(HttpMethod.GET, "/conv/locale?l=pt-BR").build());
			Assertions.assertEquals(200, r5.getMarshaledResponse().getStatusCode());
		});
	}

	@Resource
	public static class ConversionResource {
		@GET("/conv/date")
		public String date(@QueryParameter(name = "d") LocalDate d) {return d.toString();}

		@GET("/conv/flavor")
		public String flavor(@QueryParameter(name = "f") Flavor f) {return f.name();}

		@GET("/conv/uuid")
		public String uuid(@QueryParameter(name = "id") UUID id) {return id.toString();}

		@GET("/conv/list")
		public String list(@QueryParameter(name = "x") List<Integer> xs) {return String.valueOf(xs.size());}

		@GET("/conv/locale")
		public String locale(@QueryParameter(name = "l") Locale locale) {return locale.toLanguageTag();}
	}
}