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

import com.soklet.internal.microhttp.MicrohttpResponse;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HttpCookieOrderingTests {
    @Test
    void rawCookiesPrecedeStructuredCookiesIndependentlyOfHeaderCasingAndResponsePlanning() {
        DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).build();
        for (String name : List.of("set-cookie", "Set-Cookie", "SET-COOKIE")) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
                for (String encoding : List.of("identity", "gzip")) {
                    MarshaledResponse response = MarshaledResponse.withStatusCode(200)
                            .headers(Map.of(name, List.of("session=old; Path=/", "other=raw; Path=/"),
                                    "Content-Type", List.of("text/plain")))
                            .cookies(List.of(ResponseCookie.with("session", "new").path("/").build(),
                                    ResponseCookie.with("session", "newest").path("/").build()))
                            .body("compressible ".repeat(1024).getBytes(StandardCharsets.US_ASCII)).build();
                    Request request = Request.withPath(method, "/cookies")
                            .headers(Map.of("Accept-Encoding", List.of(encoding))).build();
                    MicrohttpResponse transport = server.toMicrohttpResponse(request, null, response);
                    String wire = new String(transport.serialize("HTTP/1.1", List.of()), StandardCharsets.ISO_8859_1);
                    List<String> cookieLines = wire.lines().filter(line -> line.regionMatches(true, 0,
                                    "set-cookie:", 0, "set-cookie:".length()))
                            .map(line -> line.substring(line.indexOf(':') + 1).trim()).toList();
                    assertEquals(List.of("session=old; Path=/", "other=raw; Path=/",
                            "session=new; Path=/", "session=newest; Path=/"), cookieLines,
                            name + " " + method + " " + encoding);
                }
            }
        }
    }
}
