package com.soklet;

import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultHttpServerTests {
	@Test
	public void headersFromMicrohttpRequestPreservesNormalizedHeaderBehavior() {
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).build();
		MicrohttpRequest microhttpRequest = new MicrohttpRequest(
				"GET",
				"/",
				"HTTP/1.1",
				List.of(
						new Header("Cache-Control", "no-cache, no-store"),
						new Header("Set-Cookie", "session=xyz; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/"),
						new Header("X-Empty", "   "),
						new Header("X-Trace-Id", " abc123 ")),
				new byte[0],
				false,
				new InetSocketAddress("127.0.0.1", 12345));

		Map<String, Set<String>> headers = server.headersFromMicrohttpRequest(microhttpRequest);

		Assertions.assertEquals(new LinkedHashSet<>(List.of("no-cache", "no-store")), headers.get("Cache-Control"));
		Assertions.assertEquals(
				List.of("session=xyz; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/"),
				new ArrayList<>(headers.get("Set-Cookie")));
		Assertions.assertEquals(Set.of("abc123"), headers.get("x-trace-id"));
		Assertions.assertFalse(headers.containsKey("X-Empty"));
	}
}
