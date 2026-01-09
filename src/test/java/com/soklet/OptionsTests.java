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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
		SokletConfig cfg = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(EchoResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			RequestResult result = simulator.performRequest(Request.withPath(HttpMethod.OPTIONS, "/echo").build());
			Assertions.assertEquals(204, result.getMarshaledResponse().getStatusCode());
			Map<String, Set<String>> headers = result.getMarshaledResponse().getHeaders();
			Assertions.assertTrue(headers.containsKey("Allow"), "missing Allow header");
			String allow = String.join(",", headers.get("Allow"));
			Assertions.assertTrue(allow.contains("GET"));
			Assertions.assertTrue(allow.contains("HEAD"));
			Assertions.assertTrue(allow.contains("OPTIONS"));
		});
	}

	@Test
	public void options_excludes_head_when_no_get_or_head() {
		SokletConfig cfg = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(PostOnlyResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			RequestResult result = simulator.performRequest(Request.withPath(HttpMethod.OPTIONS, "/submit").build());
			Assertions.assertEquals(204, result.getMarshaledResponse().getStatusCode());
			Map<String, Set<String>> headers = result.getMarshaledResponse().getHeaders();
			Assertions.assertTrue(headers.containsKey("Allow"), "missing Allow header");
			String allow = String.join(",", headers.get("Allow"));
			Assertions.assertTrue(allow.contains("POST"));
			Assertions.assertTrue(allow.contains("OPTIONS"));
			Assertions.assertFalse(allow.contains("HEAD"));
		});
	}

	public static class EchoResource {
		@GET("/echo")
		public String echo() {return "ok";}
	}

	public static class PostOnlyResource {
		@POST("/submit")
		public String submit() {return "ok";}
	}
}
