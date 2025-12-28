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

import com.soklet.annotation.GET;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.soklet.TestSupport.findFreePort;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class CorsWildcardWithCredentialsTests {
	@Test
	public void wildcardOriginIsRewrittenWhenCredentialsTrue() throws Exception {
		int port = findFreePort();

		CorsAuthorizer authorizer = new CorsAuthorizer() {
			@NonNull
			@Override
			public Optional<CorsResponse> authorize(@NonNull Request request,
																							@NonNull Cors cors) {
				return Optional.empty();
			}

			@NonNull
			@Override
			public Optional<CorsPreflightResponse> authorizePreflight(@NonNull Request request,
																																@NonNull CorsPreflight preflight,
																																@NonNull Map<HttpMethod, ResourceMethod> methods) {
				return Optional.of(
						CorsPreflightResponse.withAccessControlAllowOrigin("*")
								.accessControlAllowCredentials(Boolean.TRUE)
								.accessControlAllowMethods(Set.of(HttpMethod.GET))
								.build()
				);
			}
		};

		SokletConfig cfg = SokletConfig.withServer(
						Server.withPort(port).build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(authorizer)
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			HttpClient client = HttpClient.newHttpClient();
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/hello"))
					.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
					.header("Origin", "https://example.com")
					.header("Access-Control-Request-Method", "GET")
					.build();

			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

			int code = response.statusCode();
			Assertions.assertEquals(204, code);

			String acao = response.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
			String acc = response.headers().firstValue("Access-Control-Allow-Credentials").orElse(null);

			// When credentials are allowed, ACAO must echo the request Origin and not be "*"
			Assertions.assertEquals("true", acc);
			Assertions.assertNotEquals("*", acao);
			Assertions.assertEquals("https://example.com", acao);
		}
	}

	public static class CorsResource {
		@GET("/api/hello")
		public String hello() {return "ok";}
	}

}
