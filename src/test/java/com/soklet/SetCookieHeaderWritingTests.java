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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SetCookieHeaderWritingTests {
	@Test
	public void multipleCookiesAreSeparateHeaders() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(CookieResource.class))) {
			URL url = new URL("http://127.0.0.1:" + port + "/set-cookies");
			HttpURLConnection c = (HttpURLConnection) url.openConnection();
			c.setRequestMethod("GET");
			c.setRequestProperty("Accept", "text/plain");
			int code = c.getResponseCode();
			Assertions.assertEquals(200, code);

			Map<String, List<String>> headers = c.getHeaderFields();
			List<String> setCookies = headers.get("Set-Cookie");
			Assertions.assertNotNull(setCookies, "No Set-Cookie headers present");
			Assertions.assertEquals(2, setCookies.size(), "Expected two Set-Cookie headers, got: " + setCookies);
			Assertions.assertTrue(setCookies.get(0).startsWith("a="));
			Assertions.assertTrue(setCookies.get(1).startsWith("b="));
		}
	}

	public static class CookieResource {
		@GET("/set-cookies")
		public Response cookies() {
			ResponseCookie a = ResponseCookie.with("a", "1").path("/").httpOnly(true).build();
			ResponseCookie b = ResponseCookie.with("b", "2").path("/").secure(true).build();
			return Response.withStatusCode(200)
					.build()
					.copy().cookies(set -> {
						set.add(a);
						set.add(b);
					}).finish();
		}
	}

	private static Soklet startApp(int port, Set<Class<?>> resourceClasses) {
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port).build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(resourceClasses))
				.build();
		Soklet app = Soklet.withConfig(cfg);
		app.start();
		return app;
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}