/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class HttpStreamingPassiveDisconnectTests {
    private static final String STREAM_REQUEST = "GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n";
    private static final String NEXT_REQUEST =
            "GET /next HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

    @Test
    void ordinaryHttpStreamRetainsPipelinedRequestForDispatchAfterCompletion() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StreamingResponseBody body = StreamingResponseBody.fromWriter(responseStream -> {
            responseStream.write("first".getBytes(StandardCharsets.UTF_8));
            responseStream.flush();
            release.await();
            responseStream.write("last".getBytes(StandardCharsets.UTF_8));
        });
        try (Fixture fixture = new Fixture(body); Socket socket = fixture.connect()) {
            send(socket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            Assertions.assertEquals("first", readChunk(socket.getInputStream()));
            send(socket, NEXT_REQUEST);
            Assertions.assertEquals(0, fixture.routes.nextCalls.get(),
                    "The second request must not start before the stream completes");

            release.countDown();
            Assertions.assertEquals("last", readChunk(socket.getInputStream()));
            Assertions.assertEquals("", readChunk(socket.getInputStream()));
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            Assertions.assertArrayEquals("next".getBytes(StandardCharsets.UTF_8),
                    socket.getInputStream().readNBytes(4));
            Assertions.assertEquals(1, fixture.routes.nextCalls.get());
            Assertions.assertTrue(fixture.terminated.await(3, TimeUnit.SECONDS));
            Assertions.assertEquals(StreamTerminationReason.COMPLETED, fixture.reason.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void socketResetWhileOrdinaryStreamIsIdleCancelsPublisherWithoutAnotherWrite() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        Flow.Publisher<ByteBuffer> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
                subscribed.countDown();
            }

            @Override
            public void cancel() {
                canceled.countDown();
            }
        });
        try (Fixture fixture = new Fixture(StreamingResponseBody.fromPublisher(publisher));
             Socket socket = fixture.connect()) {
            send(socket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            Assertions.assertTrue(subscribed.await(3, TimeUnit.SECONDS));
            socket.setSoLinger(true, 0);
            socket.close();

            Assertions.assertTrue(canceled.await(3, TimeUnit.SECONDS),
                    "A reset during idle output must cancel the blocked upstream publisher");
            Assertions.assertTrue(fixture.terminated.await(3, TimeUnit.SECONDS));
            Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.reason.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final int port;
        final Routes routes;
        final Soklet soklet;
        final CountDownLatch terminated = new CountDownLatch(1);
        final AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();

        Fixture(StreamingResponseBody body) throws IOException {
            port = findFreePort();
            routes = new Routes(body);
            HttpServer server = HttpServer.withPort(port).host("127.0.0.1")
                    .streamingResponseTimeout(Duration.ZERO)
                    .streamingResponseIdleTimeout(Duration.ZERO).build();
            soklet = Soklet.fromConfig(SokletConfig.withHttpServer(server)
                    .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Routes.class)))
                    .instanceProvider(new InstanceProvider() {
                        @Override
                        public <T> T provide(@NonNull Class<@NonNull T> instanceClass) {
                            return instanceClass == Routes.class
                                    ? instanceClass.cast(routes)
                                    : InstanceProvider.defaultInstance().provide(instanceClass);
                        }
                    })
                    .lifecycleObserver(new LifecycleObserver() {
                        @Override
                        public void didTerminateResponseStream(@NonNull StreamingResponseHandle handle,
                                                               @NonNull StreamTermination termination) {
                            reason.set(termination.getReason());
                            terminated.countDown();
                        }

                    }).build());
            soklet.start();
        }

        Socket connect() throws IOException, InterruptedException {
            Socket socket = connectWithRetry("127.0.0.1", port, 3_000);
            socket.setSoTimeout(3_000);
            return socket;
        }

        @Override
        public void close() {
            soklet.close();
        }
    }

    public static final class Routes {
        private final StreamingResponseBody body;
        final AtomicInteger nextCalls = new AtomicInteger();

        private Routes(StreamingResponseBody body) {
            this.body = body;
        }

        @GET("/stream")
        public MarshaledResponse stream() {
            return MarshaledResponse.withStatusCode(200).streamingResponseBody(body).build();
        }

        @GET("/next")
        public String next() {
            nextCalls.incrementAndGet();
            return "next";
        }
    }

    private static void send(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        while (output.size() < 8_192) {
            int value = input.read();
            if (value < 0) throw new IOException("EOF before headers");
            output.write(value);
            matched = value == "\r\n\r\n".charAt(matched) ? matched + 1 : (value == '\r' ? 1 : 0);
            if (matched == 4) return output.toString(StandardCharsets.US_ASCII);
        }
        throw new IOException("Headers exceeded test bound");
    }

    private static String readChunk(InputStream input) throws IOException {
        int size = Integer.parseInt(readLine(input), 16);
        byte[] data = input.readNBytes(size);
        if (data.length != size) throw new IOException("Truncated chunk");
        Assertions.assertEquals("", readLine(input));
        if (size == 0) return "";
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() < 8_192) {
            int value = input.read();
            if (value < 0) throw new IOException("EOF before CRLF");
            if (value == '\r') {
                if (input.read() != '\n') throw new IOException("Malformed CRLF");
                return output.toString(StandardCharsets.US_ASCII);
            }
            output.write(value);
        }
        throw new IOException("Line exceeded test bound");
    }
}
