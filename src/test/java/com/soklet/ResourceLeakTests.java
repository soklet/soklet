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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;

/**
 * CI-safe resource-leak tripwires. Long soak runs belong behind explicit/manual profiles; these tests
 * keep a small always-on signal for resource cleanup regressions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ResourceLeakTests {
	@Test
	public void httpConnectionChurnReturnsResourcesNearBaselineAfterShutdown() throws Exception {
		int port = findFreePort();
		HttpServer httpServer = HttpServer.withPort(port)
				.concurrency(2)
				.requestHeaderTimeout(Duration.ofSeconds(3))
				.shutdownTimeout(Duration.ofSeconds(2))
				.build();
		SokletConfig config = SokletConfig.withHttpServer(httpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ChurnResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();
		ResourceSnapshot runningBaseline;

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			assertOkResponse(port);
			runningBaseline = ResourceSnapshot.captureAfterGc();

			for (int i = 0; i < 75; i++)
				assertOkResponse(port);

			ResourceSnapshot.assertReturnsNear(
					"HTTP connection churn while running",
					runningBaseline,
					Duration.ofSeconds(5),
					new ResourceSnapshot.ResourceTolerance(2L, 16L * 1024L * 1024L, 8));
		}

		DefaultHttpServer defaultHttpServer = (DefaultHttpServer) httpServer;
		Assertions.assertTrue(defaultHttpServer.getEventLoop().isEmpty(), "Event loop should be cleared after stop");
		Assertions.assertTrue(defaultHttpServer.getRequestHandlerExecutorService().isEmpty(),
				"Request handler executor should be cleared after stop");
		Assertions.assertTrue(defaultHttpServer.getRequestHandlerTimeoutScheduler().isEmpty(),
				"Timeout scheduler should be cleared after stop");
	}

	private static void assertOkResponse(int port) throws Exception {
		try (Socket socket = connectWithRetry("127.0.0.1", port, 2_000)) {
			socket.setSoTimeout(2_000);
			socket.getOutputStream().write(("""
					GET /health HTTP/1.1\r
					Host: 127.0.0.1:%s\r
					Connection: close\r
					\r
					""").formatted(port).getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();

			String response = new String(readAll(socket.getInputStream()), StandardCharsets.ISO_8859_1);
			Assertions.assertTrue(response.startsWith("HTTP/1.1 200"), "Unexpected response: " + firstLine(response));
			Assertions.assertTrue(response.endsWith("ok"), "Unexpected response body: " + response);
		}
	}

	@NonNull
	private static String firstLine(@NonNull String response) {
		int endOfLine = response.indexOf("\r\n");
		return endOfLine < 0 ? response : response.substring(0, endOfLine);
	}

	@ThreadSafe
	public static class ChurnResource {
		@GET("/health")
		public String health() {
			return "ok";
		}
	}

	private static class QuietLifecycle implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Intentionally quiet for leak-tripwire tests.
		}
	}
}
