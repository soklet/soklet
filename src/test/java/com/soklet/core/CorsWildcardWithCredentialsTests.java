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

import com.soklet.Cors;
import com.soklet.CorsAuthorizer;
import com.soklet.CorsPreflight;
import com.soklet.CorsPreflightResponse;
import com.soklet.CorsResponse;
import com.soklet.HttpMethod;
import com.soklet.Request;
import com.soklet.ResourceMethod;
import com.soklet.ResourceMethodResolver;
import com.soklet.Server;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.annotation.GET;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class CorsWildcardWithCredentialsTests {
	@Test
	public void wildcardOriginIsRewrittenWhenCredentialsTrue() throws Exception {
		int port = findFreePort();

		CorsAuthorizer authorizer = new CorsAuthorizer() {
			@Nonnull
			@Override
			public Optional<CorsResponse> authorize(@Nonnull Request request,
																							@Nonnull Cors cors) {
				return Optional.empty();
			}

			@Nonnull
			@Override
			public Optional<CorsPreflightResponse> authorizePreflight(@Nonnull Request request,
																																@Nonnull CorsPreflight preflight,
																																@Nonnull Map<HttpMethod, ResourceMethod> methods) {
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(CorsResource.class)))
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

	private static int findFreePort() throws IOException {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}