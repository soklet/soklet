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

import com.soklet.annotation.ServerSentEventSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.jspecify.annotations.NonNull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SseLastEventIdTests {
	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	public void lastEventIdHeaderIsVisibleToResource() throws Exception {
		int port = findFreePort();
		int ssePort = findFreePort();

		ServerSentEventServer serverSentEventServer = ServerSentEventServer.withPort(ssePort)
				.verifyConnectionOnceEstablished(false)
				.build();

		SokletConfig config = SokletConfig.withServer(Server.withPort(port).build())
				.serverSentEventServer(serverSentEventServer)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseResource.class)))
				.build();

		try (Soklet soklet = Soklet.withConfig(config)) {
			soklet.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				writeHttpGet(socket, "/sse/abc", ssePort,
						"Accept: text/event-stream\r\n" +
								"Last-Event-ID: 123\r\n");

				BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				// First response line
				String status = br.readLine();
				Assertions.assertTrue(status.startsWith("HTTP/1.1 200"));

				// Headers
				String line;
				boolean sawEcho = false;
				while ((line = br.readLine()) != null) {
					if (line.isEmpty()) break; // end headers
				}

				// First SSE frame
				String data = br.readLine(); // e.g., "data: lastEventId=123"
				if (data != null && data.startsWith("data: ")) {
					sawEcho = data.contains("lastEventId=123");
				}

				Assertions.assertTrue(sawEcho, "Server did not receive Last-Event-ID");
			}
		}
	}

	public static class SseResource {
		@ServerSentEventSource("/sse/{id}")
		public HandshakeResult sse(@NonNull Request request,
															 @NonNull ServerSentEventServer serverSentEventServer) {
			String last = request.getHeaders().getOrDefault("Last-Event-ID", Set.of()).stream().findFirst().orElse("none");

			// Wait a bit and then broadcast
			new Thread(() -> {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}

				ServerSentEventBroadcaster broadcaster = serverSentEventServer.acquireBroadcaster(ResourcePath.withPath("/sse/abc")).get();
				broadcaster.broadcastEvent(ServerSentEvent.withData("lastEventId=" + last).build());
			}).start();

			return HandshakeResult.accept();
		}
	}

	private static void writeHttpGet(Socket socket, String path, int port, String extraHeaders) throws IOException {
		String req = "GET " + path + " HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + port + "\r\n" +
				extraHeaders +
				"\r\n";
		socket.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
		socket.getOutputStream().flush();
	}
}
