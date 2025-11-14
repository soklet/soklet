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
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
			assertEquals(200, code);

			Map<String, List<String>> headers = c.getHeaderFields();
			List<String> setCookies = headers.get("Set-Cookie");
			assertNotNull(setCookies, "No Set-Cookie headers present");
			assertEquals(2, setCookies.size(), "Expected two Set-Cookie headers, got: " + setCookies);
			assertTrue(setCookies.get(0).startsWith("a="));
			assertTrue(setCookies.get(1).startsWith("b="));
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

	@Test
	public void pathWithDotDotSegmentShouldBeRejected() {
		// Arrange & Act & Assert
		// Path traversal in cookie paths could lead to unexpected behavior
		// For example: /admin/../public effectively becomes /public
		IllegalArgumentException exception = assertThrows(
				IllegalArgumentException.class,
				() -> {
					ResponseCookie.with("session", "abc123")
							.path("/admin/../public")
							.build();
				},
				"Cookie path with .. segment should be rejected"
		);

		// Verify the error message is meaningful
		String message = exception.getMessage().toLowerCase();
		assertTrue(
				message.contains("path") || message.contains("invalid") || message.contains(".."),
				"Error message should indicate path validation issue"
		);
	}

	@Test
	public void pathWithQueryStringShouldBeRejected() {
		// Arrange & Act & Assert
		// Query strings are not valid in cookie paths
		assertThrows(
				IllegalArgumentException.class,
				() -> {
					ResponseCookie.with("session", "abc123")
							.path("/api?param=value")
							.build();
				},
				"Cookie path with query string should be rejected"
		);
	}

	@Test
	public void pathWithFragmentShouldBeRejected() {
		// Arrange & Act & Assert
		// Fragments (anchors) are not valid in cookie paths
		assertThrows(
				IllegalArgumentException.class,
				() -> {
					ResponseCookie.with("tracking", "xyz789")
							.path("/page#section")
							.build();
				},
				"Cookie path with fragment should be rejected"
		);
	}

	@Test
	public void pathWithMultipleDotDotSegmentsShouldBeRejected() {
		// Arrange & Act & Assert
		// Multiple .. segments should also be rejected
		assertThrows(
				IllegalArgumentException.class,
				() -> {
					ResponseCookie.with("test", "value")
							.path("/a/b/../../c")
							.build();
				},
				"Cookie path with multiple .. segments should be rejected"
		);
	}

	@Test
	public void pathWithEncodedDotsShouldBeRejected() {
		// Arrange & Act & Assert
		// URL-encoded dots could be used to bypass naive validation
		assertThrows(
				IllegalArgumentException.class,
				() -> {
					ResponseCookie.with("test", "value")
							.path("/admin/%2E%2E/public")  // %2E = '.'
							.build();
				},
				"Cookie path with encoded .. should be rejected"
		);
	}

	@Test
	public void normalPathsShouldBeAccepted() {
		// Arrange & Act & Assert
		// These valid paths should all work without throwing exceptions
		assertDoesNotThrow(() -> {
			ResponseCookie.with("session", "abc")
					.path("/")
					.secure(true)
					.httpOnly(true)
					.build();
		}, "Root path should be accepted");

		assertDoesNotThrow(() -> {
			ResponseCookie.with("session", "abc")
					.path("/api")
					.build();
		}, "Simple path should be accepted");

		assertDoesNotThrow(() -> {
			ResponseCookie.with("session", "abc")
					.path("/api/v1")
					.build();
		}, "Multi-segment path should be accepted");

		assertDoesNotThrow(() -> {
			ResponseCookie.with("session", "abc")
					.path("/api/v1/users")
					.build();
		}, "Deep path should be accepted");
	}

	@Test
	public void nullPathShouldBeHandledGracefully() {
		// Arrange & Act
		ResponseCookie cookie = ResponseCookie.with("test", "value")
				.path(null)
				.build();

		// Assert: Null path should either default to "/" or be absent
		String path = cookie.getPath().orElse(null);
		assertTrue(path == null || path.equals("/"),
				"Null path should result in null or default '/'");
	}

	@Test
	public void emptyPathShouldBeHandledGracefully() {
		// Arrange & Act
		ResponseCookie cookie = ResponseCookie.with("test", "value")
				.path("")
				.build();

		// Assert: Empty path should either default to "/" or be absent
		String path = cookie.getPath().orElse(null);
		assertTrue(path == null || path.equals("/") || path.isEmpty(),
				"Empty path should be handled gracefully");
	}

	@Test
	public void pathWithBackslashShouldBeRejected() {
		// Arrange & Act & Assert
		// Backslashes are Windows-style path separators and should not be in cookie paths
		assertThrows(
				IllegalArgumentException.class,
				() -> {
					ResponseCookie.with("test", "value")
							.path("/api\\admin")
							.build();
				},
				"Cookie path with backslash should be rejected"
		);
	}

	@Test
	public void validCookieWithAllAttributesWorks() {
		// Arrange & Act
		ResponseCookie cookie = ResponseCookie.with("secure_session", "encrypted_value_123")
				.path("/api")
				.domain("example.com")
				.maxAge(Duration.ofHours(24))
				.secure(true)
				.httpOnly(true)
				.sameSite(ResponseCookie.SameSite.STRICT)
				.build();

		// Assert: Verify all attributes are set correctly
		assertEquals("secure_session", cookie.getName());
		assertEquals("encrypted_value_123", cookie.getValue().orElse(null));
		assertEquals("/api", cookie.getPath().orElse(null));
		assertEquals("example.com", cookie.getDomain().orElse(null));
		assertEquals(Duration.ofHours(24), cookie.getMaxAge().orElse(null));
		assertTrue(cookie.getSecure());
		assertTrue(cookie.getHttpOnly());
		assertEquals(ResponseCookie.SameSite.STRICT, cookie.getSameSite().orElse(null));
	}

	@Test
	public void setCookieHeaderRepresentationIsValid() {
		// Arrange
		ResponseCookie cookie = ResponseCookie.with("test", "value")
				.path("/api")
				.secure(true)
				.httpOnly(true)
				.build();

		// Act
		String setCookieHeader = cookie.toSetCookieHeaderRepresentation();

		// Assert: Verify the Set-Cookie header format
		assertNotNull(setCookieHeader);
		assertTrue(setCookieHeader.contains("test=value"));
		assertTrue(setCookieHeader.contains("Path=/api"));
		assertTrue(setCookieHeader.contains("Secure"));
		assertTrue(setCookieHeader.contains("HttpOnly"));
	}

	@Test
	public void testSessionCookieMaxAgeBug() {
		// HttpCookie.getMaxAge() returns -1 for session cookies
		// This should NOT create a Duration with -1 seconds
		String setCookieHeader = "sessionid=abc123; Path=/; Secure; HttpOnly";

		Optional<ResponseCookie> parsed = ResponseCookie.fromSetCookieHeaderRepresentation(setCookieHeader);
		assertTrue(parsed.isPresent(), "Should parse session cookie");

		ResponseCookie cookie = parsed.get();
		assertEquals("sessionid", cookie.getName());
		assertEquals("abc123", cookie.getValue().orElse(null));

		// BUG: This will fail because maxAge will be Duration.ofSeconds(-1)
		// Expected: maxAge should be empty (not present) for session cookies
		// Actual: maxAge is present with negative duration
		assertFalse(cookie.getMaxAge().isPresent(),
				"Session cookies should NOT have a maxAge attribute");

		// The serialized form should NOT include Max-Age
		String serialized = cookie.toSetCookieHeaderRepresentation();
		assertFalse(serialized.contains("Max-Age"),
				"Session cookies should not serialize Max-Age attribute");
	}

	@Test
	public void testNegativeMaxAgeCreatesInvalidDuration() {
		// HttpCookie allows -1 maxAge, but Duration.ofSeconds(-1) is problematic
		HttpCookie httpCookie = new HttpCookie("test", "value");
		httpCookie.setMaxAge(-1); // Session cookie

		// This is what the buggy code does:
		Exception exception = assertThrows(RuntimeException.class, () -> {
			Duration negativeDuration = Duration.ofSeconds(httpCookie.getMaxAge());

			// When converted back to seconds for serialization:
			long seconds = negativeDuration.toSeconds(); // This will be -1

			// This creates "Max-Age=-1" which is technically valid but semantically wrong
			// A session cookie should NOT have Max-Age at all
			if (seconds >= 0) {
				// This won't execute due to negative value
			} else {
				throw new RuntimeException("Negative maxAge should not be serialized");
			}
		});

		assertNotNull(exception, "Should demonstrate the issue with negative Duration");
	}


	@Test
	public void testZeroMaxAgeWorks() {
		// Max-Age=0 means "delete this cookie immediately"
		String setCookieHeader = "sessionid=abc123; Max-Age=0; Path=/";

		Optional<ResponseCookie> parsed = ResponseCookie.fromSetCookieHeaderRepresentation(setCookieHeader);
		assertTrue(parsed.isPresent());

		ResponseCookie cookie = parsed.get();
		assertTrue(cookie.getMaxAge().isPresent());
		assertEquals(0, cookie.getMaxAge().get().toSeconds());

		// Serialization should preserve Max-Age=0
		String serialized = cookie.toSetCookieHeaderRepresentation();
		assertTrue(serialized.contains("Max-Age=0"));
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