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

package com.soklet;

import com.soklet.HttpMethod;
import com.soklet.LifecycleInterceptor;
import com.soklet.LogEvent;
import com.soklet.Request;
import com.soklet.RequestResult;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.annotation.Multipart;
import com.soklet.annotation.POST;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class MultipartEdgeCaseTests {
	private static byte[] simpleMultipartBody() {
		// Very small multipart body with exactly one field: a=1
		String boundary = "----AaB03x";
		String body = ""
				+ "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"a\"" + "\r\n"
				+ "\r\n"
				+ "1\r\n"
				+ "--" + boundary + "--\r\n";
		return body.getBytes(StandardCharsets.US_ASCII);
	}

	@Test
	public void missing_required_field_yields_400() {
		SokletConfig cfg = SokletConfig.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(UploadResource.class)))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			byte[] body = simpleMultipartBody();
			RequestResult r = simulator.performRequest(
					Request.with(HttpMethod.POST, "/upload")
							.headers(Map.of(
									"Content-Type", Set.of("multipart/form-data; boundary=----AaB03x"),
									"Content-Length", Set.of(String.valueOf(body.length))
							))
							.body(body)
							.build());
			Assertions.assertEquals(400, r.getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void missing_optional_field_is_ok() {
		SokletConfig cfg = SokletConfig.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(UploadResource.class)))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(cfg, simulator -> {
			byte[] body = simpleMultipartBody();
			RequestResult r = simulator.performRequest(
					Request.with(HttpMethod.POST, "/upload-optional")
							.headers(Map.of(
									"Content-Type", Set.of("multipart/form-data; boundary=----AaB03x"),
									"Content-Length", Set.of(String.valueOf(body.length))
							))
							.body(body)
							.build());
			Assertions.assertEquals(204, r.getMarshaledResponse().getStatusCode());
		});
	}

	public static class UploadResource {
		@POST("/upload")
		public void upload(@Multipart(name = "b") String requiredB) { /* missing -> 400 */ }

		@POST("/upload-optional")
		public void uploadOptional(@Multipart(name = "b", optional = true) Optional<String> b) { /* OK */ }
	}
}