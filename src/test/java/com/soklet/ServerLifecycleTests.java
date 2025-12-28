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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ServerLifecycleTests {
	private static HttpURLConnection open(String method, URL url, Map<String, String> headers) throws IOException {
		HttpURLConnection c = (HttpURLConnection) url.openConnection();
		c.setRequestMethod(method);
		c.setConnectTimeout(2000);
		c.setReadTimeout(2000);
		for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
		return c;
	}

	@Test
	public void start_stop_isStarted_toggles_and_serves_requests() throws Exception {
		int port = findFreePort();
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(HealthResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			Assertions.assertFalse(app.isStarted());
			app.start();
			Assertions.assertTrue(app.isStarted());

			URL url = new URL("http://127.0.0.1:" + port + "/health");
			HttpURLConnection c = open("GET", url, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c.getResponseCode());
			Assertions.assertEquals("ok", new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
		}
		// try-with-resources calls close(), which stops the server
		// Can't call isStarted() after close() directly; create again to check false
		Soklet app2 = Soklet.withConfig(cfg);
		try {
			Assertions.assertFalse(app2.isStarted());
		} finally {
			app2.close();
		}
	}

	@Test
	public void start_without_initialize_fails_fast() {
		Server server = Server.withPort(0).build();

		IllegalStateException exception =
				Assertions.assertThrows(IllegalStateException.class, server::start);
		Assertions.assertTrue(exception.getMessage().contains("RequestHandler"));
		Assertions.assertFalse(server.isStarted());
	}

	@Test
	public void start_port_in_use_cleans_up_state() throws Exception {
		int port = findFreePort();

		try (ServerSocket ss = new ServerSocket(port)) {
			ss.setReuseAddress(true);

			Server server = Server.withPort(port).build();

			SokletConfig cfg = SokletConfig.withServer(server)
					.lifecycleObserver(new QuietLifecycle())
					.build();

			server.initialize(cfg, (request, consumer) -> {
				MarshaledResponse response = MarshaledResponse.withStatusCode(200)
						.headers(Map.of("Content-Type", Set.of("text/plain")))
						.body("ok".getBytes(StandardCharsets.UTF_8))
						.build();
				consumer.accept(RequestResult.withMarshaledResponse(response).build());
			});

			Assertions.assertThrows(UncheckedIOException.class, server::start);
			Assertions.assertFalse(server.isStarted());

			DefaultServer internal = (DefaultServer) server;
			Assertions.assertTrue(internal.getEventLoop().isEmpty());
			Assertions.assertTrue(internal.getRequestHandlerExecutorService().isEmpty());
		}
	}

	@Test
	public void rejectedExecutor_returns_503() throws Exception {
		int port = findFreePort();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		executor.shutdown();

		Server server = Server.withPort(port)
				.requestTimeout(Duration.ofSeconds(5))
				.requestHandlerExecutorServiceSupplier(() -> executor)
				.build();

		SokletConfig cfg = SokletConfig.withServer(server)
				.lifecycleObserver(new QuietLifecycle())
				.build();

		server.initialize(cfg, (request, consumer) -> {
			MarshaledResponse response = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Type", Set.of("text/plain")))
					.body("ok".getBytes(StandardCharsets.UTF_8))
					.build();
			consumer.accept(RequestResult.withMarshaledResponse(response).build());
		});

		server.start();

		try {
			URL url = new URL("http://127.0.0.1:" + port + "/health");
			HttpURLConnection c = open("GET", url, Map.of("Accept", "text/plain"));
			int status = c.getResponseCode();
			Assertions.assertEquals(503, status);

			java.io.InputStream in = c.getErrorStream();
			if (in == null) in = c.getInputStream();
			String body = new String(readAll(in), StandardCharsets.UTF_8);
			Assertions.assertTrue(body.contains("HTTP 503"));
		} finally {
			server.stop();
		}
	}

	public static class HealthResource {
		@GET("/health")
		public String health() {return "ok";}
	}

	private static class QuietLifecycle implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* no-op */ }
	}
}
