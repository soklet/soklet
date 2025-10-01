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
import com.soklet.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.Set;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class OptionsTests {
	@Test
	public void options_includes_allow_header() {
		SokletConfiguration cfg = SokletConfiguration.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(EchoResource.class)))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			RequestResult result = simulator.performRequest(new Request.Builder(HttpMethod.OPTIONS, "/echo").build());
			Assertions.assertEquals(204, result.getMarshaledResponse().getStatusCode());
			Map<String, Set<String>> headers = result.getMarshaledResponse().getHeaders();
			Assertions.assertTrue(headers.containsKey("Allow"), "missing Allow header");
			String allow = String.join(",", headers.get("Allow"));
			Assertions.assertTrue(allow.contains("GET"));
			Assertions.assertTrue(allow.contains("HEAD"));
			Assertions.assertTrue(allow.contains("OPTIONS"));
		});
	}

	@Resource
	public static class EchoResource {
		@GET("/echo")
		public String echo() {return "ok";}
	}
}